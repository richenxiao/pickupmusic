package com.shiyin.music.data.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v22: 拆分方案编解码 + 队列压缩规则的纯 JVM 单测。
 *
 * 这两块是"无缝切歌"的地基，而且都不依赖 Android —— 编解码一旦坏了，边界切歌会
 * 整个失效（表现为不切歌 / 切错 / 元数据错乱），压缩规则坏了则会把整轨文件重复塞进
 * 队列或把连续段拆开（产生引擎级 seek，破坏无缝）。所以单独钉死。
 */
class SplitPlanCodecTest {

    private fun vt(id: Long, s: Long, e: Long, title: String = "", artist: String = "") =
        VirtualTrack(SplitIds.trackId(id), s, e, title, artist)

    // ── 编解码往返 ──────────────────────────────────────────────────────────

    @Test fun roundTrip_preservesEveryField() {
        val src = listOf(
            vt(1, 0, 185_000, "intro", "某人"),
            vt(2, 185_000, 400_000, "黑", "A, B"),
            vt(3, 400_000, 600_000, "自由式", ""),
        )
        val back = SplitPlanCodec.decode(SplitPlanCodec.encode(src))
        assertEquals(src, back)
    }

    /** 曲名里常见的标点（冒号、分号、逗号、换行、括号）不能被当成分隔符。 */
    @Test fun roundTrip_titlesWithAwkwardPunctuation() {
        val src = listOf(
            vt(1, 0, 1000, "曲名: 副标题", "歌手: 某人"),
            vt(2, 1000, 2000, "a;b,c|d", "x\u0000y"),
            vt(3, 2000, 3000, "第一行\n第二行", "带 空格 的名字"),
            vt(4, 3000, 4000, "[03:05] 括号", "（全角）括号"),
        )
        assertEquals(src, SplitPlanCodec.decode(SplitPlanCodec.encode(src)))
    }

    @Test fun roundTrip_emptyTitles() {
        val src = listOf(vt(1, 0, 1000), vt(2, 1000, 2000))
        val back = SplitPlanCodec.decode(SplitPlanCodec.encode(src))
        assertEquals(2, back!!.size)
        assertEquals("", back[0].title)
    }

    /** 空列表编码成空串，解码回 null（调用方降级为非拆分曲目，不崩）。 */
    @Test fun emptyList_encodesEmptyAndDecodesNull() {
        assertEquals("", SplitPlanCodec.encode(emptyList()))
        assertNull(SplitPlanCodec.decode(""))
        assertNull(SplitPlanCodec.decode(null))
    }

    // ── 脏数据降级（宁可少一次无缝切歌，也不能崩）──────────────────────────

    @Test fun corruptInput_returnsNull() {
        assertNull(SplitPlanCodec.decode("garbage"))
        assertNull(SplitPlanCodec.decode("1:0:1000"))                       // 字段数不足
        assertNull(SplitPlanCodec.decode("a\u00010\u00011000\u0001t\u0001a")) // id 非数字
        assertNull(SplitPlanCodec.decode("1\u0001x\u00011000\u0001t\u0001a")) // startMs 非数字
        assertNull(SplitPlanCodec.decode("1\u00010\u0001y\u0001t\u0001a"))    // endMs 非数字
        assertNull(SplitPlanCodec.decode("\u0002"))                          // 空条目
    }

    /** 单条字段多余（未来加了字段）时应仍然解得出前 5 个，不整体失败。 */
    @Test fun extraFields_areTolerated() {
        val encoded = "1\u00010\u00011000\u0001title\u0001artist\u0001extra"
        val back = SplitPlanCodec.decode(encoded)
        assertEquals(1, back!!.size)
        assertEquals("title", back[0].title)
        assertEquals("artist", back[0].artist)
    }

    @Test fun isEncodable_flagsControlCharacters() {
        assertTrue(SplitPlanCodec.isEncodable(listOf(vt(1, 0, 1000, "正常曲名", "正常歌手"))))
        assertFalse(SplitPlanCodec.isEncodable(listOf(vt(1, 0, 1000, "含\u0001分隔符"))))
        assertFalse(SplitPlanCodec.isEncodable(listOf(vt(1, 0, 1000, "标题", "含\u0002分隔符"))))
    }
}

class SplitRunCollapseTest {

    private fun slot(src: Long, idx: Int) = SplitRunCollapse.Slot(src, idx)

    /** 全是普通曲目 → 一个都不合并，原样保留。 */
    @Test fun allPlainTracks_keptAsIs() {
        val slots = listOf(null, null, null)
        assertEquals(listOf(0, 1, 2), SplitRunCollapse.representativeIndices(slots))
    }

    /** 同一文件连续 3 首 → 压成 1 条（整张专辑 = 1 个 MediaItem，边界才可能无缝）。 */
    @Test fun oneContiguousRun_collapsesToOne() {
        val slots = listOf(slot(9, 0), slot(9, 1), slot(9, 2))
        assertEquals(listOf(0), SplitRunCollapse.representativeIndices(slots))
    }

    @Test fun runPlusPlainPlusRun() {
        val slots = listOf(slot(9, 0), slot(9, 1), null, slot(7, 0), slot(7, 1), slot(7, 2))
        assertEquals(listOf(0, 2, 3), SplitRunCollapse.representativeIndices(slots))
    }

    /** 同源但不连续（歌单里同时有第 1 首和第 5 首）→ 各自成条，各带方案。 */
    @Test fun sameSourceButNotContiguous_notMerged() {
        val slots = listOf(slot(9, 0), slot(9, 4))
        assertEquals(listOf(0, 1), SplitRunCollapse.representativeIndices(slots))
    }

    /** 不同源但下标连续（两张整轨专辑首尾相接）→ 不能合并。 */
    @Test fun differentSource_notMerged() {
        val slots = listOf(slot(9, 0), slot(7, 1))
        assertEquals(listOf(0, 1), SplitRunCollapse.representativeIndices(slots))
    }

    /** 被点的那首在段里 → 用它当代表（它前面的成员不会被播到）。 */
    @Test fun tappedInsideRun_becomesRepresentative() {
        val slots = listOf(slot(9, 0), slot(9, 1), slot(9, 2), slot(9, 3))
        assertEquals(listOf(2), SplitRunCollapse.representativeIndices(slots, tappedIndex = 2))
    }

    /** 被点的那首不在任何段里（普通曲目）→ 各段仍取段首。 */
    @Test fun tappedOutsideRun_usesRunStart() {
        val slots = listOf(slot(9, 0), slot(9, 1), null, slot(7, 0))
        assertEquals(listOf(0, 2, 3), SplitRunCollapse.representativeIndices(slots, tappedIndex = 2))
    }

    /** tappedIndex = -1（无明确起点，如专辑页「播放」）→ 全部取段首。 */
    @Test fun noTappedIndex_usesRunStart() {
        val slots = listOf(slot(9, 0), slot(9, 1), slot(9, 2))
        assertEquals(listOf(0), SplitRunCollapse.representativeIndices(slots, tappedIndex = -1))
    }

    @Test fun runAtEnd() {
        val slots = listOf(null, slot(9, 0), slot(9, 1))
        assertEquals(listOf(0, 1), SplitRunCollapse.representativeIndices(slots))
    }

    @Test fun emptyList() {
        val none = SplitRunCollapse.representativeIndices(emptyList<SplitRunCollapse.Slot?>())
        assertTrue(none.isEmpty())
    }

    /** 代表下标必须保持原顺序（队列顺序不能被打乱）。 */
    @Test fun representativesKeepOriginalOrder() {
        val slots = listOf(slot(1, 0), null, slot(2, 0), slot(2, 1), null, slot(1, 1))
        val idx = SplitRunCollapse.representativeIndices(slots)
        assertEquals(idx.sorted(), idx)
    }

    // ── v1.4.0: 每条代表负责的**分段区间**（止损"一个座位坐 11 个人"） ─────────────
    // 真机 bug：把拆出的单曲放进歌单，播完会接着播同一整轨文件的下一段，而不是歌单的
    // 下一首；手动「下一首」也要按十几次。根因是队列条目只带了 index、没带"管到第几首"。

    /** 歌单里只有一首拆分单曲（前后都是普通曲目）→ 区间就是它自己那一首。 */
    @Test fun playlistWithSingleSplitTrack_coversOnlyItself() {
        val slots = listOf(null, slot(9, 6), null)
        val runs = SplitRunCollapse.runs(slots)
        assertEquals(3, runs.size)
        assertEquals(SplitRunCollapse.Run(repIndex = 1, startSegment = 6, lastSegment = 6), runs[1])
    }

    /** 整张专辑（同源 + 下标连续）→ 一条，区间覆盖到末段。 */
    @Test fun wholeAlbum_coversAllSegments() {
        val slots = (0..12).map { slot(9, it) }
        val runs = SplitRunCollapse.runs(slots)
        assertEquals(1, runs.size)
        assertEquals(SplitRunCollapse.Run(repIndex = 0, startSegment = 0, lastSegment = 12), runs[0])
    }

    /** 同源但不连续（歌单里同时有第 1 首和第 5 首）→ 各自成条，各覆盖自己。 */
    @Test fun nonContiguousSameSource_eachCoversItself() {
        val runs = SplitRunCollapse.runs(listOf(slot(9, 0), slot(9, 4)))
        assertEquals(
            listOf(
                SplitRunCollapse.Run(repIndex = 0, startSegment = 0, lastSegment = 0),
                SplitRunCollapse.Run(repIndex = 1, startSegment = 4, lastSegment = 4),
            ),
            runs,
        )
    }

    /** 被点中的那首在段中间 → 区间从**它自己那段**起算（它前面的成员本来就不会被播到）。 */
    @Test fun tappedInsideRun_startsAtTappedSegment() {
        val slots = listOf(slot(9, 0), slot(9, 1), slot(9, 2), slot(9, 3))
        val runs = SplitRunCollapse.runs(slots, tappedIndex = 2)
        assertEquals(SplitRunCollapse.Run(repIndex = 2, startSegment = 2, lastSegment = 3), runs.single())
    }

    /** 普通曲目没有分段区间（-1/-1），不会被误判成"第一条"。 */
    @Test fun plainTracks_haveNoSegmentRange() {
        val runs = SplitRunCollapse.runs(listOf(null, null))
        assertTrue(runs.all { it.startSegment == -1 && it.lastSegment == -1 })
    }

    /** runs 与前缀代表下标必须一一对应（同一趟扫描的两个视图，不能各自漂移）。 */
    @Test fun runsAndRepresentativesAgree() {
        val slots = listOf(slot(1, 0), null, slot(2, 0), slot(2, 1), null, slot(1, 1))
        assertEquals(
            SplitRunCollapse.representativeIndices(slots),
            SplitRunCollapse.runs(slots).map { it.repIndex },
        )
    }
}

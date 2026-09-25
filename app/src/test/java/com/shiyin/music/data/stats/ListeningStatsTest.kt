package com.shiyin.music.data.stats

import com.shiyin.music.data.MediaScanner
import com.shiyin.music.data.Track
import com.shiyin.music.data.albumKeyOf
import com.shiyin.music.data.db.PlayEventEntity
import com.shiyin.music.data.split.INTERLUDE_MAX_SEC
import com.shiyin.music.data.split.PlayKind
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.8: ListeningStats 抽取的**特征化测试**(characterization test)。
 *
 * 背景:这套统计口径原本内联在 ListeningStatsScreen 的 Composable 里,为了让 Agent 的
 * listening_stats 技能复用同一口径而被抽成纯函数。用户给的硬性验收条件是
 * "统计页所有数字必须跟改动前完全一致,需要实际比对,不是大概率没问题"。
 *
 * 做法:把**原始内联实现逐字复制到本文件**作为 oracle([oracle]),用一批覆盖各口径
 * 分支的合成数据同时跑 oracle 与 [computeWeeklyStats],断言逐字段相等。这样验证的是
 * "抽取没有改变行为",而不是"新代码看起来对"——后者是自证,前者才是比对。
 *
 * 同时用显式期望值锁住几条最容易写错、且错了会静默给错数字的规则
 * (专辑循环折算 / Single 排除 / interlude 只出单曲榜 / 多歌手拆分 / 已删除曲目)。
 *
 * ⚠️ 本测试无法替代"在真机上看统计页数字" —— 见文件末尾的说明。
 */
class ListeningStatsTest {

    // ── 夹具 ──────────────────────────────────────────────────────────────

    /** 同 PlaybackSkillsTest:Track.uri 是 Android 桩,构造器非空校验会 NPE,
     *  故用 Gson(Unsafe 分配)造夹具。这里只用到 id/title/artist/album/albumId/durationMs。 */
    private fun track(
        id: Long,
        title: String,
        artist: String,
        album: String,
        albumId: Long = 0,
        durationMs: Long = 200_000,
    ): Track = Gson().fromJson(
        """{"id":$id,"title":"$title","artist":"$artist","album":"$album","albumId":$albumId,"durationMs":$durationMs}""",
        Track::class.java,
    )

    private fun ev(
        mediaId: Long,
        playedSec: Int = 200,
        kind: String = PlayKind.TRACK,
    ) = PlayEventEntity(
        mediaId = mediaId,
        playedAt = 0L,
        durationSec = 200,
        playedSec = playedSec,
        completed = true,
        kind = kind,
    )

    private fun isInterlude(t: Track): Boolean = t.durationMs / 1000 <= INTERLUDE_MAX_SEC

    // ── oracle:原始内联实现(逐字复制) ────────────────────────────────────

    private fun oracle(
        events: List<PlayEventEntity>,
        lib: List<Track>,
        trackById: (Long) -> Track?,
        isInterlude: (Track) -> Boolean,
        albumTypeFor: (Long) -> String?,
    ): WeeklyStats {
        if (events.isEmpty()) return WeeklyStats()
        val totalSec = events.sumOf { it.playedSec.toLong() }
        val albumTrackCounts = lib
            .groupBy { albumKeyOf(it.album, it.artist, it.albumId) }
            .mapValues { it.value.size }
        val singleKeys = lib
            .groupBy { albumKeyOf(it.album, it.artist, it.albumId) }
            .mapValues { (_, ts) -> ts.firstOrNull()?.albumId }
            .filterValues { id -> id != null && albumTypeFor(id) == "Single" }
            .keys
        val albumPlayMap = mutableMapOf<String, Int>()
        val artistMap = mutableMapOf<String, Int>()
        val trackMap = mutableMapOf<Long, Int>()
        for (ev in events) {
            val t = trackById(ev.mediaId)
            val isAlbumPlay = ev.kind == PlayKind.ALBUM
            if (t != null) {
                val key = albumKeyOf(t.album, t.artist, t.albumId)
                if (key !in singleKeys) {
                    val weight = if (isAlbumPlay) (albumTrackCounts[key] ?: 1) else 1
                    albumPlayMap[key] = (albumPlayMap[key] ?: 0) + weight
                }
            }
            for (n in (t?.let { MediaScanner.splitArtists(it.artist) } ?: listOf("未知"))) {
                artistMap[n] = (artistMap[n] ?: 0) + 1
            }
            if (!isAlbumPlay && (t == null || !isInterlude(t))) {
                trackMap[ev.mediaId] = (trackMap[ev.mediaId] ?: 0) + 1
            }
        }
        val albumCycles = albumPlayMap.mapValues { (key, plays) ->
            plays / (albumTrackCounts[key] ?: 1)
        }
        return WeeklyStats(
            totalSeconds = totalSec,
            topAlbums = albumCycles.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            topArtists = artistMap.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            topTracks = trackMap.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
        )
    }

    // ── 场景 ──────────────────────────────────────────────────────────────

    private class Fixture(
        val name: String,
        val events: List<PlayEventEntity>,
        val lib: List<Track>,
        val albumTypes: Map<Long, String> = emptyMap(),
        /** 只经 trackById 可见、**不在 lib() 里**的曲目——即「完整专辑」容器。
         *  这是 v22 口径的关键:整轨播放的 mediaId 是容器合成 id,用 lib() 查会得到
         *  null,把专辑播放整个漏掉。夹具必须如实建模,否则测不到这条规则。 */
        val containerOnly: List<Track> = emptyList(),
    )

    private fun Fixture.byId(id: Long): Track? =
        lib.firstOrNull { it.id == id } ?: containerOnly.firstOrNull { it.id == id }

    private fun fixtures(): List<Fixture> {
        val a1 = track(1, "以父之名", "周杰伦", "叶惠美", albumId = 100)
        val a2 = track(2, "懦夫", "周杰伦", "叶惠美", albumId = 100)
        val a3 = track(3, "晴天", "周杰伦", "叶惠美", albumId = 100)
        val inter = track(4, "Interlude", "周杰伦", "叶惠美", albumId = 100, durationMs = 45_000)
        val dup = track(5, "合唱曲", "周杰伦, 费玉清", "合唱专辑", albumId = 200)
        val single = track(6, "单曲A", "某人", "单曲合集", albumId = 300)
        val other = track(7, "别的歌", "陈奕迅", "其他专辑", albumId = 400)
        // 「完整专辑」容器:不在 libAll 里,只能通过 trackById 拿到
        val container = track(999, "叶惠美(完整专辑)", "周杰伦", "叶惠美", albumId = 100)

        val libAll = listOf(a1, a2, a3, inter, dup, single, other)

        return listOf(
            Fixture("空事件", emptyList(), libAll),
            Fixture(
                "重复听同一首",
                List(7) { ev(3) } + List(2) { ev(7) },
                libAll,
            ),
            Fixture(
                "过渡曲只计数不进单曲榜",
                // interlude 听了 5 次,但它不该出现在 topTracks;艺人榜仍要计入
                List(5) { ev(4) } + List(2) { ev(1) },
                libAll,
            ),
            Fixture(
                "完整专辑播放折算成循环",
                // 叶惠美在 lib 里有 3 首,整轨播 4 次 → 每次 weight=3 → 12 ÷ 3 = 4 个循环
                List(4) { ev(999, kind = PlayKind.ALBUM) },
                libAll,
                containerOnly = listOf(container),
            ),
            Fixture(
                "Single 不计入专辑榜",
                List(9) { ev(6) },
                libAll,
                albumTypes = mapOf(300L to "Single"),
            ),
            Fixture(
                "多歌手串拆分后各自计数",
                List(3) { ev(5) },
                libAll,
            ),
            Fixture(
                "已删除曲目计入未知艺人",
                // mediaId 不在库里 → trackById 为 null → 艺人记 "未知",单曲榜照常计数
                List(4) { ev(12345) },
                libAll,
            ),
            Fixture(
                "超过 topN 时截断",
                (1L..8L).map { ev(it) },
                libAll,
            ),
            Fixture(
                "总时长含各条 playedSec",
                listOf(ev(1, 200), ev(2, 30), ev(3, 1)),
                libAll,
            ),
        )
    }

    // ── 断言:抽取未改变行为 ───────────────────────────────────────────────

    @Test
    fun `抽取后的实现与原内联实现在所有场景下逐字段一致`() {
        for (f in fixtures()) {
            val byId = { id: Long -> f.byId(id) }
            val types = { id: Long -> f.albumTypes[id] }
            val expected = oracle(f.events, f.lib, byId, ::isInterlude, types)
            val actual = computeWeeklyStats(f.events, f.lib, byId, ::isInterlude, types)
            assertEquals("场景「${f.name}」totalSeconds 不一致", expected.totalSeconds, actual.totalSeconds)
            assertEquals("场景「${f.name}」topTracks 不一致", expected.topTracks, actual.topTracks)
            assertEquals("场景「${f.name}」topArtists 不一致", expected.topArtists, actual.topArtists)
            assertEquals("场景「${f.name}」topAlbums 不一致", expected.topAlbums, actual.topAlbums)
        }
    }

    // ── 显式期望值(锁住几条静默出错的口径规则) ───────────────────────────

    @Test
    fun `空事件返回全零而不是崩`() {
        val s = computeWeeklyStats(emptyList(), emptyList(), { null }, { false }, { null })
        assertEquals(0L, s.totalSeconds)
        assertTrue(s.topTracks.isEmpty() && s.topArtists.isEmpty() && s.topAlbums.isEmpty())
    }

    @Test
    fun `总时长按 playedSec 求和`() {
        val lib = listOf(track(1, "A", "X", "Al"))
        val s = computeWeeklyStats(
            listOf(ev(1, 200), ev(1, 30)), lib, { id -> lib.firstOrNull { it.id == id } }, { false }, { null },
        )
        assertEquals("必须用 playedSec(230)而不是曲目总长", 230L, s.totalSeconds)
    }

    @Test
    fun `过渡曲不进单曲榜但仍进艺人榜`() {
        val lib = listOf(track(1, "Interlude", "周杰伦", "叶惠美", albumId = 100, durationMs = 45_000))
        val s = computeWeeklyStats(
            List(5) { ev(1) }, lib, { id -> lib.firstOrNull { it.id == id } }, ::isInterlude, { null },
        )
        assertTrue("过渡曲不该出现在单曲榜", s.topTracks.isEmpty())
        assertEquals("但艺人榜要算上", 5, s.topArtists.first().second)
    }

    @Test
    fun `完整专辑播 3 次算 3 个循环`() {
        val lib = (1L..3L).map { track(it, "T$it", "周杰伦", "叶惠美", albumId = 100) }
        // 整轨播放的 mediaId 是容器合成 id —— 不在 lib() 里,必须能被 trackById 解析
        val container = track(999, "叶惠美(完整专辑)", "周杰伦", "叶惠美", albumId = 100)
        val byId = { id: Long -> lib.firstOrNull { it.id == id } ?: container.takeIf { it.id == id } }
        val s = computeWeeklyStats(
            List(3) { ev(999, kind = PlayKind.ALBUM) }, lib, byId, { false }, { null },
        )
        // 每次整轨播放 weight = 曲目数 3 → 累计 9 → ÷3 = 3 个循环
        assertEquals(1, s.topAlbums.size)
        assertEquals(3, s.topAlbums.first().second)
    }

    @Test
    fun `Single 类型专辑不计入专辑榜`() {
        val lib = listOf(track(1, "单曲A", "某人", "单曲合集", albumId = 300))
        val s = computeWeeklyStats(
            List(9) { ev(1) },
            lib,
            { id -> lib.firstOrNull { it.id == id } },
            { false },
            { id -> if (id == 300L) "Single" else null },
        )
        assertTrue("Single 不该出现在专辑榜", s.topAlbums.isEmpty())
        assertEquals("但单曲榜照常", 9, s.topTracks.first().second)
    }

    @Test
    fun `多歌手串按单名各自计数`() {
        val lib = listOf(track(1, "合唱曲", "周杰伦, 费玉清", "合唱专辑", albumId = 200))
        val s = computeWeeklyStats(
            List(3) { ev(1) }, lib, { id -> lib.firstOrNull { it.id == id } }, { false }, { null },
        )
        assertEquals("两个歌手各 3 次,不能是整串 key", 2, s.topArtists.size)
        assertTrue(s.topArtists.all { it.second == 3 })
    }

    @Test
    fun `库里查不到的曲目记为未知艺人`() {
        val s = computeWeeklyStats(
            List(4) { ev(99999) }, emptyList(), { null }, { false }, { null },
        )
        assertEquals("未知", s.topArtists.first().first)
        assertEquals("单曲榜仍按 mediaId 计数", 4, s.topTracks.first().second)
    }

    @Test
    fun `结果最多取五项`() {
        val lib = (1L..8L).map { track(it, "T$it", "A$it", "Al$it", albumId = it) }
        val s = computeWeeklyStats(
            (1L..8L).map { ev(it) }, lib, { id -> lib.firstOrNull { it.id == id } }, { false }, { null },
        )
        assertEquals(5, s.topTracks.size)
        assertEquals(5, s.topArtists.size)
    }

    // ── 本周起点 ──────────────────────────────────────────────────────────

    @Test
    fun `本周起点是周一零点且不晚于当前时刻`() {
        val now = System.currentTimeMillis()
        val start = currentWeekStartMillis(now)
        assertTrue("起点应早于当前时刻", start <= now)
        assertTrue("起点不应早于 7 天前", now - start <= 8 * 24 * 3600_000L)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = start }
        assertEquals("小时应归零", 0, cal.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals("分应归零", 0, cal.get(java.util.Calendar.MINUTE))
        assertEquals("周一", java.util.Calendar.MONDAY, cal.get(java.util.Calendar.DAY_OF_WEEK))
    }
}

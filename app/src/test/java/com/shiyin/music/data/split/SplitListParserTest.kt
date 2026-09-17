package com.shiyin.music.data.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「专辑整轨拆分为单曲」第一步 —— 粘贴歌曲列表解析器的全场景单测。
 *
 * 解析器是纯 JVM 的，粘贴文本又千变万化，最容易静默出错的地方就是这里，
 * 所以把需求里提到的每种格式（带/不带时间戳、各种序号写法、时间戳位置）
 * 都钉成测试。
 */
class SplitListParserTest {

    // ── 无时间戳 ────────────────────────────────────────────────────────────

    @Test fun plainNumberedList_firstIsZero_restUnknown() {
        val r = SplitListParser.parse(
            """
            1. intro
            2. 黑
            3. 自由式
            """.trimIndent()
        )
        assertEquals(3, r.rows.size)
        // 第一首固定 0:00:00，且原文没带时间戳 → hasTimestamp=false
        assertEquals(0L, r.rows[0].startMs)
        assertFalse(r.rows[0].hasTimestamp)
        assertEquals("intro", r.rows[0].title)
        // 其余留空等用户补
        assertEquals(SPLIT_START_UNKNOWN, r.rows[1].startMs)
        assertEquals(SPLIT_START_UNKNOWN, r.rows[2].startMs)
        assertEquals(2, r.unknownCount)
        assertFalse(r.allTimed)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test fun variousIndexSeparators() {
        val r = SplitListParser.parse(
            """
            1. 甲
            2、乙
            3) 丙
            4  丁
            05. 戊
            """.trimIndent()
        )
        assertEquals(listOf("甲", "乙", "丙", "丁", "戊"), r.rows.map { it.title })
    }

    /** 以数字开头的真实曲名不能被当成序号剥掉。 */
    @Test fun numericTitleIsNotTreatedAsIndex() {
        val r = SplitListParser.parse(
            """
            1. 500 Miles
            2. 100 Years
            """.trimIndent()
        )
        assertEquals("500 Miles", r.rows[0].title)
        assertEquals("100 Years", r.rows[1].title)
    }

    /** 两行纯文本（用户直接贴歌名，不写序号）也要能解析。 */
    @Test fun noIndexAtAll() {
        val r = SplitListParser.parse("intro\n黑\n自由式")
        assertEquals(listOf("intro", "黑", "自由式"), r.rows.map { it.title })
    }

    // ── 带时间戳 ────────────────────────────────────────────────────────────

    @Test fun timestampsInEveryPosition() {
        val r = SplitListParser.parse(
            """
            1. intro
            2. 03:05 黑
            3. [03:42] 自由式
            4. 0:04:10 未命名
            5. 尾声 12:30
            """.trimIndent()
        )
        assertEquals(0L, r.rows[0].startMs)
        assertEquals(185_000L, r.rows[1].startMs)   // 03:05
        assertEquals(222_000L, r.rows[2].startMs)   // [03:42]
        assertEquals(250_000L, r.rows[3].startMs)   // 0:04:10
        assertEquals(750_000L, r.rows[4].startMs)   // 12:30 在行尾
        assertEquals(listOf("intro", "黑", "自由式", "未命名", "尾声"), r.rows.map { it.title })
        assertTrue(r.allTimed)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test fun parenthesizedTimestamp() {
        val r = SplitListParser.parse("1. intro\n2. (01:30) 第二首")
        assertEquals(90_000L, r.rows[1].startMs)
        assertEquals("第二首", r.rows[1].title)
    }

    /** 第一首即便原文写了时间戳，也强制归零（专辑第一首必然从文件开头开始）。 */
    @Test fun firstRowForcedToZero() {
        val r = SplitListParser.parse("1. 00:07 intro\n2. 03:05 黑")
        assertEquals(0L, r.rows[0].startMs)
        assertTrue(r.rows[0].hasTimestamp)   // 如实反映原文带了时间戳
        assertEquals(185_000L, r.rows[1].startMs)
    }

    /** 更长的数字尾巴不能被截成时间戳：`123:45` 不是 23:45。 */
    @Test fun timestampNotMatchedInsideLongerNumber() {
        val r = SplitListParser.parse("1. intro\n2. 123:45 曲名")
        assertEquals(SPLIT_START_UNKNOWN, r.rows[1].startMs)
    }

    // ── 冲突检测 ────────────────────────────────────────────────────────────

    @Test fun nonIncreasingTimestampsAreFlagged() {
        val r = SplitListParser.parse(
            """
            1. intro
            2. 03:05 黑
            3. 01:00 自由式
            4. 05:00 尾奏
            """.trimIndent()
        )
        // 第 3 首（01:00）比第 2 首（03:05）小 → 冲突；第 4 首相对第 2 首是递增的，不算冲突
        assertEquals(setOf(3), r.conflicts)
    }

    @Test fun duplicateTimestampIsFlagged() {
        val r = SplitListParser.parse("1. intro\n2. 03:05 甲\n3. 03:05 乙")
        assertEquals(setOf(3), r.conflicts)
    }

    // ── 边界情况 ────────────────────────────────────────────────────────────

    @Test fun blankLinesAndNoiseAreSkipped() {
        val r = SplitListParser.parse(
            """
            1. intro

            2. 黑

            3.
            4. 自由式
            """.trimIndent()
        )
        assertEquals(listOf("intro", "黑", "自由式"), r.rows.map { it.title })
    }

    @Test fun emptyInputYieldsNothing() {
        assertTrue(SplitListParser.parse("").rows.isEmpty())
        assertTrue(SplitListParser.parse("   \n  \n").rows.isEmpty())
        assertFalse(SplitListParser.parse("").canSplit)
    }

    @Test fun singleRowCannotSplit() {
        assertFalse(SplitListParser.parse("1. 只有一首").canSplit)
    }

    // ── 时间显示 / 手动输入 ─────────────────────────────────────────────────

    @Test fun formatUsesHourMinuteSecond() {
        assertEquals("0:00:00", SplitListParser.format(0))
        assertEquals("0:03:05", SplitListParser.format(185_000))
        assertEquals("1:02:03", SplitListParser.format(3_723_000))
        assertEquals("--:--", SplitListParser.format(SPLIT_START_UNKNOWN))
    }

    @Test fun parseUserTimeAcceptsCommonForms() {
        assertEquals(185_000L, SplitListParser.parseUserTime("0:03:05"))
        assertEquals(185_000L, SplitListParser.parseUserTime("3:05"))
        assertEquals(185_000L, SplitListParser.parseUserTime("03:05"))
        assertEquals(185_000L, SplitListParser.parseUserTime("185"))
        assertEquals(185_000L, SplitListParser.parseUserTime(" 185000 "))
        assertNull(SplitListParser.parseUserTime(""))
        assertNull(SplitListParser.parseUserTime("abc"))
        assertNull(SplitListParser.parseUserTime("1:2:3:4"))
        assertNull(SplitListParser.parseUserTime("-5"))
    }

    // ── 合成 id 命名空间 ────────────────────────────────────────────────────

    @Test fun syntheticIdNamespacesDoNotOverlap() {
        // 拆分单曲与整轨容器分属两个互不相交的值域
        assertTrue(SplitIds.isSplitTrackId(SplitIds.trackId(1)))
        assertFalse(SplitIds.isSplitTrackId(SplitIds.containerId(1)))
        assertTrue(SplitIds.isContainerId(SplitIds.containerId(1)))
        assertFalse(SplitIds.isContainerId(SplitIds.trackId(1)))
        // 也不与既有的合成专辑 id 段（0x1_0000_0000）相撞
        assertFalse(SplitIds.isSynthetic(0x1_0000_0000L))
        // 容器 id → 源文件 id 可逆
        assertEquals(1234L, SplitIds.sourceMediaIdOf(SplitIds.containerId(1234L)))
    }

    @Test fun splitPlanDerivesIndexFromPosition() {
        val plan = SplitPlan(
            containerId = SplitIds.containerId(9L),
            sourceMediaId = 9L,
            tracks = listOf(
                VirtualTrack(SplitIds.trackId(1), 0, 185_000),
                VirtualTrack(SplitIds.trackId(2), 185_000, 400_000),
                VirtualTrack(SplitIds.trackId(3), 400_000, 600_000),
            ),
        )
        assertEquals(0, plan.indexAt(0))
        assertEquals(0, plan.indexAt(184_999))
        assertEquals(1, plan.indexAt(185_000))
        assertEquals(1, plan.indexAt(399_999))
        assertEquals(2, plan.indexAt(600_000))
        assertEquals(2, plan.indexAt(999_999))
        assertEquals(200_000L, plan.tracks[2].durationMs)
    }

    // ── 过渡曲（intro / outro）判定 ─────────────────────────────────────────
    // 用户定调：这类曲目**要计数**，但**不作为热度评判标准**。

    @Test fun interludeDurationRule() {
        // ≤60s 的曲目算"专辑过渡"
        assertTrue(isInterludeDuration(30))
        assertTrue(isInterludeDuration(1))
        assertTrue(isInterludeDuration(60))
        // >60s 是真歌
        assertFalse(isInterludeDuration(61))
        assertFalse(isInterludeDuration(185))
        assertFalse(isInterludeDuration(600))
        // 时长为 0 / 未知 不算过渡曲（宁可不排除，也别把真歌误判掉）
        assertFalse(isInterludeDuration(0))
        assertFalse(isInterludeDuration(-1))
        assertEquals(60, INTERLUDE_MAX_SEC)
    }
}

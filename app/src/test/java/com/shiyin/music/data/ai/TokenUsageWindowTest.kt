package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * v1.3.10 需求 A19:用量面板时间窗口的纯逻辑锁。
 *
 * 为什么值得钉死:天分桶原来是 SQL 里的 `timestamp / 86400000`(**UTC** 天序号),注释却
 * 写着"本地零点"。对 UTC+8 用户,天边界落在本地 08:00 —— 凌晨 0-8 点的用量被算进"昨天",
 * "今天"又漏掉这 8 小时。用户看到的数字与自己的直觉对不上(反馈"准度不够")。
 *
 * 下面第一条测试就是把这个 bug 本身记录下来,防止有人"顺手改回"简单写法。
 */
class TokenUsageWindowTest {

    /** 中国标准时间(UTC+8,无夏令时)。 */
    private val cst = TimeZone.getTimeZone("Asia/Shanghai")
    private val cstOffset = 8 * 3600_000L

    /** "本地墙上时间" → epoch 毫秒。 */
    private fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            .apply { timeZone = cst }
            .parse("%04d-%02d-%02d %02d:%02d".format(y, mo, d, h, mi))!!.time

    @Test
    fun `本地天边界是本地零点 - 凌晨与当晚同属一天`() {
        val earlyMorning = local(2026, 9, 29, 2, 0)
        val sameDayEvening = local(2026, 9, 29, 20, 0)
        val beforeMidnight = local(2026, 9, 28, 23, 0)
        assertEquals(
            "同一本地天的两个时刻必须落在同一个桶",
            TokenUsageWindow.dayIndex(earlyMorning, cstOffset),
            TokenUsageWindow.dayIndex(sameDayEvening, cstOffset),
        )
        assertTrue(
            "凌晨属于今天,不属于昨天",
            TokenUsageWindow.dayIndex(earlyMorning, cstOffset) >
                TokenUsageWindow.dayIndex(beforeMidnight, cstOffset),
        )
    }

    @Test
    fun `旧实现 UTC 分桶会把凌晨两点算成前一天`() {
        // local 2026-09-29 02:00 == UTC 2026-09-28 18:00 → 纯 UTC 除法给的是 28 号。
        // 这里断言旧算法**确实**指向 28 号,把"为什么必须加偏移"钉在测试里。
        val earlyMorning = local(2026, 9, 29, 2, 0)
        val utcDay = earlyMorning / TokenUsageWindow.DAY_MS
        assertEquals(TokenUsageWindow.dayIndex(local(2026, 9, 28, 23, 0), cstOffset), utcDay)
        assertTrue(
            "偏差就是本地 0-8 点这一整段",
            utcDay != TokenUsageWindow.dayIndex(earlyMorning, cstOffset),
        )
    }

    @Test
    fun `sinceFor 落在本地零点 - 近7天含今天共7个自然日`() {
        val now = local(2026, 9, 29, 13, 45)
        assertEquals(local(2026, 9, 29, 0, 0), TokenUsageWindow.sinceFor(1, now, cstOffset))
        assertEquals(local(2026, 9, 23, 0, 0), TokenUsageWindow.sinceFor(7, now, cstOffset))
        // 边界日当天的第一秒必须被包含(0-8 点那段不能再被漏掉)
        assertTrue(TokenUsageWindow.sinceFor(7, now, cstOffset) <= local(2026, 9, 29, 0, 30))
    }

    @Test
    fun `dayRange 与 since 自洽 - 首元素即 since 那天`() {
        val now = local(2026, 9, 29, 13, 45)
        val range = TokenUsageWindow.dayRange(7, now, cstOffset)
        assertEquals(7, range.size)
        assertEquals(
            TokenUsageWindow.dayIndex(TokenUsageWindow.sinceFor(7, now, cstOffset), cstOffset),
            range.first(),
        )
        assertEquals("最后一个桶是今天", TokenUsageWindow.dayIndex(now, cstOffset), range.last())
        assertEquals("曲线从左到右 = 时间正向", range.sorted(), range)
    }

    @Test
    fun `days 小于1 不会产生空窗口`() {
        val now = local(2026, 9, 29, 13, 45)
        assertEquals(1, TokenUsageWindow.dayRange(0, now, cstOffset).size)
        assertEquals(1, TokenUsageWindow.dayRange(-5, now, cstOffset).size)
        assertEquals(1, TokenUsageWindow.dayRange(0, now, cstOffset).size)
    }

    @Test
    fun `跨月与跨年标签正确`() {
        fun labels(days: Int, now: Long) =
            TokenUsageWindow.dayRange(days, now, cstOffset).map { TokenUsageWindow.dayLabel(it, cstOffset) }
        assertEquals(listOf("12/29", "12/30", "12/31"), labels(3, local(2026, 12, 31, 23, 30)))
        assertEquals(listOf("12/30", "12/31", "1/1"), labels(3, local(2027, 1, 1, 0, 30)))
        // 深夜(跨 UTC 天)仍显示本地日期
        assertEquals(listOf("9/28", "9/29"), labels(2, local(2026, 9, 29, 2, 0)))
    }

    @Test
    fun `零偏移退化为 UTC 天`() {
        val ts = 1_700_000_000_000L   // 2023-11-14T22:13:20Z
        assertEquals(ts / TokenUsageWindow.DAY_MS, TokenUsageWindow.dayIndex(ts, 0L))
        assertEquals("11/14", TokenUsageWindow.dayLabel(TokenUsageWindow.dayIndex(ts, 0L), 0L))
    }

    @Test
    fun `西半球负偏移同样收在本地当天`() {
        // 洛杉矶 UTC-7:local 2026-09-29 20:00 == UTC 2026-09-30 03:00 —— 加负偏移后仍是 29 号。
        val la = TimeZone.getTimeZone("America/Los_Angeles")
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            .apply { timeZone = la }.parse("2026-09-29 20:00")!!.time
        val laOffset = -7 * 3600_000L
        val day = TokenUsageWindow.dayIndex(ts, laOffset)
        assertEquals("9/29", TokenUsageWindow.dayLabel(day, laOffset))
        // 与同一天凌晨同桶
        assertEquals(
            day,
            TokenUsageWindow.dayIndex(
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                    .apply { timeZone = la }.parse("2026-09-29 00:30")!!.time,
                laOffset,
            ),
        )
    }

    @Test
    fun `功能标签 - 已登记的给中文名,未知的原样回退`() {
        assertEquals("歌词翻译", TokenUsageWindow.skillLabel("lyrics_translation"))
        assertEquals("对话回复", TokenUsageWindow.skillLabel("agent"))
        assertEquals("日语注音补全", TokenUsageWindow.skillLabel("furigana_assist"))
        // 新技能没登记也不能显示成空白(宁可不认识,不要丢信息)
        assertEquals("brand_new_skill", TokenUsageWindow.skillLabel("brand_new_skill"))
        assertEquals("其它", TokenUsageWindow.skillLabel(""))
    }

    @Test
    fun `范围标签 - 当天不叫1天`() {
        assertEquals("当天", TokenUsageWindow.rangeLabel(1))
        assertEquals("7天", TokenUsageWindow.rangeLabel(7))
        assertEquals("30天", TokenUsageWindow.rangeLabel(30))
    }
}

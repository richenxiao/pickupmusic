package com.shiyin.music.data.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v1.3.10 需求 A19:用量面板的**时间窗口**与**功能归因标签**——纯逻辑,零 Android 依赖。
 *
 * 为什么单独抽出来:天分桶原来写在 SQL 里 `timestamp / 86400000`(UTC 天序号),注释却
 * 声称"本地零点"。对 UTC+8 的用户,天边界落在本地 08:00 —— 凌晨 0-8 点的用量被算进
 * "昨天","今天"又漏掉这 8 小时。数字对不上,这正是使用场景的"准度不够"。
 *
 * 现在 SQL 与本文件用**同一套算术**(`(timestamp + tzOffsetMs) / 86400000`),而这层能被
 * 纯 JVM 单测覆盖(见 TokenUsageWindowTest)——时区相关的 bug 不该靠肉眼推。
 */
object TokenUsageWindow {

    const val DAY_MS = 86_400_000L

    /** 面板可选时间范围(天)。 */
    val RANGES = listOf(1, 3, 7, 14, 30)

    /** 当前时区在 [now] 时刻的 UTC 偏移毫秒。中国无夏令时,恒 +8h;取当前时刻即可。 */
    fun currentTzOffsetMs(now: Long = System.currentTimeMillis()): Long =
        TimeZone.getDefault().getOffset(now).toLong()

    /**
     * 本地天序号 —— 与 SQL 的 `(timestamp + :tzOffsetMs) / 86400000` **逐字一致**。
     * 用 floorDiv:正数下与整型除法相同,负数(理论上不该出现)也不会与 SQL 反向取整。
     */
    fun dayIndex(ts: Long, tzOffsetMs: Long): Long = Math.floorDiv(ts + tzOffsetMs, DAY_MS)

    /**
     * 覆盖近 [days] 天(**含今天**)的查询下界。
     * 先算本地天序号,再折回时间戳:`day * DAY_MS - tzOffsetMs` 正是那一天的本地零点。
     */
    fun sinceFor(days: Int, now: Long, tzOffsetMs: Long): Long {
        val first = dayIndex(now, tzOffsetMs) - (days.coerceAtLeast(1) - 1)
        return first * DAY_MS - tzOffsetMs
    }

    /** 本地天序号序列(旧→新),与 [TokenUsageStore.dailyMetrics] 的返回一一对应。 */
    fun dayRange(days: Int, now: Long, tzOffsetMs: Long): List<Long> {
        val last = dayIndex(now, tzOffsetMs)
        val first = last - (days.coerceAtLeast(1) - 1)
        return (first..last).toList()
    }

    /**
     * 天序号 → 本地日期标签 "M/d"。
     *
     * 时区**由 [tzOffsetMs] 自建**(GMT±HH:MM),而不是取默认时区 —— 与分桶的偏移严格同源,
     * 否则"曲线按 +8 分桶、标签按设备默认时区渲染"会在默认时区不是 +8 时错一天。
     */
    fun dayLabel(day: Long, tzOffsetMs: Long): String {
        val totalMin = tzOffsetMs / 60_000L
        val sign = if (totalMin < 0) "-" else "+"
        val absMin = kotlin.math.abs(totalMin)
        val zone = TimeZone.getTimeZone("GMT$sign%02d:%02d".format(absMin / 60, absMin % 60))
        return SimpleDateFormat("M/d", Locale.CHINA).apply { timeZone = zone }
            .format(Date(day * DAY_MS - tzOffsetMs))
    }

    /** 时间范围的中文标签(「当天」不叫「1天」)。 */
    fun rangeLabel(days: Int): String = if (days <= 1) "当天" else "${days}天"

    /**
     * skill 键 → 用户看得懂的功能名。**未登记的键回退成键名本身**——
     * 以后新增技能不会在面板上显示成空白(宁可难看,不要丢信息)。
     */
    fun skillLabel(skill: String): String = when (skill) {
        "agent" -> "对话回复"
        "agent_dispatch" -> "意图理解"
        "memory_extract" -> "记忆整理"
        "album_dates" -> "专辑信息补全"
        "lyrics_agent" -> "在线找词"
        "lyrics_translation" -> "歌词翻译"
        "furigana_assist" -> "日语注音补全"
        else -> skill.ifBlank { "其它" }
    }
}

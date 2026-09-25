package com.shiyin.music.data.lyrics

// v1.3.x 跟行滚动卡顿排查(用户点名根因):LyricLine/ParsedLyrics 全 val +
// 不可变字段(Long?/String/List),但 Kotlin List 接口在 Compose 稳定性推断
// 里默认不稳定(无法保证非 MutableList)——LyricsScreen 组合级读 lines 传给
// LazyColumn items,父级每次重组(vm.player.positionMs 每 300ms ticker 写)
// 整列 item lambda 不能跳过,26sp 全部重组合重测量(滚动/跟行卡顿)。
// @Immutable 是"承诺"而非验证,此处字段全不可变,承诺成立。LoadedLyrics
// (MainViewModel)同批处理。
@androidx.compose.runtime.Immutable
data class LyricLine(val timeMs: Long?, val text: String)

@androidx.compose.runtime.Immutable
data class ParsedLyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    /** Global [offset:±ms] tag from the LRC header, applied on top of the user offset. */
    val globalOffsetMs: Long = 0,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Active line for the given position. Synced lyrics anchor on timestamps;
     * plain lyrics fall back to the prototype's proportional mapping.
     *
     * v5.2 Bug3: when the lyrics are unsynced (no timestamps at all), return
     * -1 so the UI neither highlights a line nor auto-scrolls. The page sits
     * static until the user manually time-stamps each line via the per-row
     * sync button. Previously the proportional mapping was used here, which
     * gave a fake "follow-along" highlight on lyrics that have no real
     * timestamps — confusing because the highlight drifted unrelated to the
     * actual singer. v5.1 already exposed the manual sync path; this completes
     * it by removing the fake auto-advance.
     */
    fun activeIndex(posMs: Long, durMs: Long, offsetMs: Long): Int {
        if (lines.isEmpty()) return -1
        val p = posMs + offsetMs + globalOffsetMs
        if (synced) {
            var active = -1
            for (i in lines.indices) {
                val t = lines[i].timeMs
                if (t == null) {
                    // v1.3.x 第7项（用户实测"第 3 行亮、第 4/5 行延迟数秒才亮、第 6 行正常"）：
                    // **没有时间戳的行必须跟着前一行一起亮，不能被跳过**。
                    // 原实现 `?: continue` 把它跳过 → active 停在它之前不动 → 该行一直不亮，
                    // 直到后面某行到点，active 直接跳过去，它才"顺带"亮起（几秒延迟）。
                    // 现在：只要前面已经有行到点（active != -1），就把它一并纳入已唱范围。
                    if (active != -1) active = i
                    continue
                }
                if (t <= p) active = i else break
            }
            return active
        }
        // Unsynced: stay static so the user drives the highlight via per-line
        // sync buttons. No proportional fake-advance.
        return -1
    }
}

object LrcParser {
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val META_TAG = Regex("""^\[(ar|ti|al|by|offset|re|ve|length|au)[:\]]""", RegexOption.IGNORE_CASE)
    private val OFFSET_TAG = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    // v2.0: custom format "MM:SS lyrics" without brackets
    private val CUSTOM_TIME = Regex("""^(\d{1,2}):(\d{2})\s""")

    fun parse(raw: String): ParsedLyrics {
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<String>()
        var sawTimeTag = false
        var globalOffset = 0L
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            OFFSET_TAG.find(trimmed)?.let { m ->
                globalOffset = m.groupValues[1].toLong()
            }
            if (META_TAG.containsMatchIn(trimmed)) continue
            val tags = TIME_TAG.findAll(trimmed).toList()
            if (tags.isNotEmpty()) {
                sawTimeTag = true
                val text = trimmed.substring(tags.last().range.last + 1).trim()
                if (text.isEmpty()) continue
                for (m in tags) {
                    timed += LyricLine(parseTimeMs(m), text)
                }
                continue
            }
            // v2.0: try custom "MM:SS lyrics" format (no brackets)
            val customMatch = CUSTOM_TIME.find(trimmed)
            if (customMatch != null) {
                sawTimeTag = true
                val min = customMatch.groupValues[1].toLong()
                val sec = customMatch.groupValues[2].toLong()
                val text = trimmed.substring(customMatch.range.last + 1).trim()
                if (text.isNotEmpty()) {
                    timed += LyricLine(min * 60_000 + sec * 1000, text)
                }
                continue
            }
            plain += trimmed
        }
        return if (sawTimeTag && timed.isNotEmpty()) {
            ParsedLyrics(timed.sortedBy { it.timeMs }, synced = true, globalOffsetMs = globalOffset)
        } else {
            ParsedLyrics(plain.map { LyricLine(null, it) }, synced = false)
        }
    }

    private fun parseTimeMs(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val fracRaw = m.groupValues[3]
        val fracMs = when (fracRaw.length) {
            0 -> 0L
            1 -> fracRaw.toLong() * 100
            2 -> fracRaw.toLong() * 10
            else -> fracRaw.take(3).toLong()
        }
        return min * 60_000 + sec * 1000 + fracMs
    }
}

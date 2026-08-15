package com.shiyin.music.data.lyrics

data class LyricLine(val timeMs: Long?, val text: String)

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
                val t = lines[i].timeMs ?: continue
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

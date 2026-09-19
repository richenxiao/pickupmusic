package com.shiyin.music.data.split

/**
 * 「专辑整轨拆分为单曲」第一步：粘贴歌曲列表的解析器。
 *
 * 纯 JVM、无 Android 依赖 —— 可用单测全场景覆盖（见 SplitListParserTest）。
 *
 * 支持的行格式（一行一首，顺序即曲目序号）：
 * ```
 *   1. intro                → 无时间戳，序号 1
 *   2、黑                    → 中文顿号分隔
 *   03:05 自由式             → 行首时间戳（m:ss）
 *   4. [03:42] 未命名         → 序号 + 方括号时间戳
 *   5  0:04:10  尾奏         → 序号 + h:mm:ss
 *   尾声 12:30               → 时间戳在行尾
 * ```
 *
 * 规则：
 *  1. 时间戳可在行内任意位置（行首/行尾/中间），支持 `m:ss`、`h:mm:ss`，可带
 *     `[]`/`()`/`【】` 括号。取行内第一个合法时间戳。
 *  2. 序号剥除带保护：只有「1–3 位数字 + 显式分隔符（`. 、 , ) ] 】 :`）」或
 *     「1–2 位且 ≤99 的数字 + 空格」才当序号剥掉 —— 避免把 `500 Miles` 这类以
 *     数字开头的真实曲名当成序号。
 *  3. **第一首的起始时间恒为 0**（专辑第一首必然从文件开头开始），无需用户确认。
 *  4. 已确定的时间戳必须严格递增；违反的行进入 [Result.conflicts]，由 UI 高亮让
 *     用户修正，不静默丢弃。
 */
object SplitListParser {

    /** 一行解析结果。 */
    data class Row(
        val ordinal: Int,
        val title: String,
        /** 起始毫秒；[SPLIT_START_UNKNOWN] = 该行没带时间戳，等待用户在下一步补。 */
        val startMs: Long,
        /** 该行是否原本就带了时间戳（第一首被强制为 0 时仍如实反映原文）。 */
        val hasTimestamp: Boolean,
    )

    data class Result(
        val rows: List<Row>,
        /** 时间戳非严格递增的行序号（ordinal），UI 需高亮提示修正。 */
        val conflicts: Set<Int> = emptySet(),
    ) {
        val unknownCount: Int get() = rows.count { it.startMs == SPLIT_START_UNKNOWN }
        val allTimed: Boolean get() = rows.isNotEmpty() && unknownCount == 0
        /** 是否够格进入下一步（至少 2 首才谈得上"拆分"）。 */
        val canSplit: Boolean get() = rows.size >= 2
    }

    /** 行内时间戳：m:ss 或 h:mm:ss，可带括号。秒固定 2 位。 */
    private val TIMESTAMP = Regex("""(?:\[|\(|【)?(\d{1,2}):([0-5]\d)(?::([0-5]\d))?(?:\]|\)|】)?""")

    /** 行首序号：1–3 位数字，后面紧跟分隔符 / 空白 / 行尾。 */
    private val LEADING_INDEX = Regex("""^\s*(\d{1,3})\s*(?=[.、,，)）\]】:：]|\s|$)""")

    private val EXPLICIT_SEPS = ".、,，)）]】:："

    fun parse(text: String): Result {
        val rows = ArrayList<Row>()
        for (raw in text.lineSequence()) {
            val row = parseLine(raw, rows.size + 1) ?: continue
            rows += row
        }
        if (rows.isEmpty()) return Result(emptyList())
        // 规则 3：第一首恒为 0:00:00
        if (rows[0].startMs != 0L) {
            rows[0] = rows[0].copy(startMs = 0L)
        }
        return Result(rows, conflictsOf(rows))
    }

    /**
     * 公开的冲突检测 —— 编辑界面里用户每改一行的起始时间都要重算，不能只依赖 parse。
     * 返回时间戳非严格递增的行序号。
     */
    fun conflictsOf(rows: List<Row>): Set<Int> = detectConflicts(rows)

    /** 解析单行；返回 null = 该行无有效内容（空行 / 只有序号 / 只有时间戳）。 */
    private fun parseLine(raw: String, ordinal: Int): Row? {
        var line = raw.trim()
        if (line.isEmpty()) return null

        // 1) 先摘时间戳（必须在剥序号之前 —— 否则 "03:05 曲名" 的前两位会被当序号）
        var startMs = SPLIT_START_UNKNOWN
        var hasTimestamp = false
        extractTimestamp(line)?.let { (ms, rest) ->
            startMs = ms
            hasTimestamp = true
            line = rest
        }

        // 2) 再剥行首序号
        line = stripLeadingIndex(line)

        // 3) 剩余文本即曲名
        val title = line.trim()
        if (title.isEmpty()) return null
        return Row(ordinal = ordinal, title = title, startMs = startMs, hasTimestamp = hasTimestamp)
    }

    /**
     * 取行内第一个合法时间戳，返回 (毫秒, 摘掉时间戳后的剩余文本)。
     * 用前后字符判定挡住"更长数字的尾巴"：`123:45` 不会被误当成 `23:45`。
     */
    private fun extractTimestamp(line: String): Pair<Long, String>? {
        for (m in TIMESTAMP.findAll(line)) {
            val r = m.range
            if (r.first > 0) {
                val prev = line[r.first - 1]
                if (prev.isDigit() || prev == ':') continue
            }
            if (r.last + 1 < line.length && line[r.last + 1].isDigit()) continue
            val g = m.groupValues
            val a = g[1].toLong()
            val b = g[2].toLong()
            val secPart = g[3].takeIf { it.isNotEmpty() }?.toLong()
            val ms = if (secPart != null) (a * 3600 + b * 60 + secPart) * 1000 else (a * 60 + b) * 1000
            return ms to line.removeRange(r.first, r.last + 1)
        }
        return null
    }

    private fun stripLeadingIndex(s: String): String {
        val m = LEADING_INDEX.find(s) ?: return s
        val digits = m.groupValues[1]
        val after = s.substring(m.range.last + 1)
        val hasExplicitSep = after.isNotEmpty() && after.first() in EXPLICIT_SEPS
        // 纯空格分隔时只认 1–2 位（即 ≤99）的序号，避免剥掉 "500 Miles" 的 500。
        if (!hasExplicitSep && digits.length > 2) return s
        var rest = after.trimStart()
        if (rest.isNotEmpty() && rest.first() in EXPLICIT_SEPS) rest = rest.substring(1)
        if (rest.isNotEmpty() && rest.first() in "-–—") rest = rest.substring(1)
        return rest.trimStart()
    }

    private fun detectConflicts(rows: List<Row>): Set<Int> {
        val conflicts = LinkedHashSet<Int>()
        var last = Long.MIN_VALUE
        for (r in rows) {
            if (r.startMs == SPLIT_START_UNKNOWN) continue
            if (r.startMs <= last) conflicts += r.ordinal else last = r.startMs
        }
        return conflicts
    }

    // ── 时间显示 / 手动输入 ────────────────────────────────────────────────

    /** 展示格式：`h:mm:ss`（与需求里的 `0:03:05` 一致）。未定返回 `--:--`。 */
    fun format(ms: Long): String {
        if (ms == SPLIT_START_UNKNOWN || ms < 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "%d:%02d:%02d".format(h, m, s)
    }

    /**
     * 手动输入解析：接受 `h:mm:ss` / `m:ss` / `mm:ss` / 纯秒数 / 纯毫秒以外的常见写法。
     * 无法解析返回 null（UI 保持原值并提示）。
     */
    fun parseUserTime(input: String): Long? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (t.contains(':')) {
            val parts = t.split(':').map { it.trim() }
            if (parts.size !in 2..3) return null
            val nums = parts.map { it.toLongOrNull() ?: return null }
            if (nums.any { it < 0 }) return null
            val ms = if (nums.size == 3) {
                (nums[0] * 3600 + nums[1] * 60 + nums[2]) * 1000
            } else {
                (nums[0] * 60 + nums[1]) * 1000
            }
            return ms
        }
        // 纯数字：≤ 三位数按秒，更长按毫秒（兼容从别处拷来的毫秒值）
        val n = t.toLongOrNull() ?: return null
        if (n < 0) return null
        return if (t.length <= 3) n * 1000 else n
    }
}

package com.shiyin.music.data.translate

/**
 * 需求6「歌词本翻译」离线路径的纯逻辑引擎（无 Android / ML Kit 依赖，可纯 JVM 单测）。
 *
 * 存在的理由：ML Kit 是**句级** NMT 模型（Google Translate 离线同款）。逐行直译等于把一个
 * 长难句切成碎片分别喂给模型，模型被迫猜上下文——这是质量最差的情况。所以译前把连续行
 * 拼成完整"句组"，译后再拆回原行数；每一跳（日→英、英→中）都能看到完整句法结构。
 *
 * 核心纪律：**宁可缺行，不可错行**。拆回对不齐时绝不做猜测性分配，而是整组译文挂到组内
 * 第一行、其余行留空——"第 3 行挂着第 5 行的意思"是不可接受的错误，"缺一行译文"只是遗憾。
 */
object LyricsTranslationEngine {

    /** 句末标点（中/英/日，全角半角）——命中即闭合当前句组。 */
    private const val SENTENCE_END = "。！？!?…"

    /** 拆回对齐时的段末标点（比 [SENTENCE_END] 多分号，分号是长句内的自然断点）。 */
    private const val SEGMENT_END = "。！？；!?;…"

    /** 无句末标点时的句组上限：歌词一行常是半句，2-4 行拼成一句。 */
    private const val MAX_GROUP_LINES = 4

    /** 无句末标点时的拼接长度上限，防止把整段无标点歌词塞成一组。 */
    private const val MAX_GROUP_CHARS = 120

    /** 按长度比例分配时，切点允许偏离标点的容差（字符）；超出即判定对不齐、走兜底。 */
    private const val SNAP_TOLERANCE = 4

    /**
     * 一个待译句组。
     * @param lineIndices 组内各行在输入列表中的下标（升序、连续）
     * @param text 组内各行拼接后的待译文本
     * @param translatable 空行/纯符号行为 false——它们是段落分隔，不送翻译也不占译文位
     */
    data class Group(
        val lineIndices: List<Int>,
        val text: String,
        val translatable: Boolean,
    )

    /**
     * 拆回对齐结果。
     * @param byLine 行号 → 译文。**缺行号 = 该行无译文**（不是空串），渲染层据此留白
     * @param wholeGroupOnFirstLine 是否走了"整组挂第一行"的兜底（供日志/调试观察对齐率）
     */
    data class Alignment(
        val byLine: Map<Int, String>,
        val wholeGroupOnFirstLine: Boolean,
    )

    // ── 译前：行分组 + 组内拼接 ───────────────────────────────────────────────

    /**
     * 把歌词原文按"句组"切分。规则：
     * 1. 以句末标点（[SENTENCE_END]）为界闭合当前组；
     * 2. 无标点歌词按 [MAX_GROUP_LINES] 行 / [MAX_GROUP_CHARS] 字符上限切组；
     * 3. 空行 = 段落分隔：先闭合当前组，自身成为一个不可译组；
     * 4. 纯符号行（无任何字母/数字，如音乐记号）同样不可译。
     *
     * 组内各行用**空格**连接而不是换行——换行会被模型当成句界，等于没拼。
     */
    fun group(lines: List<String>): List<Group> {
        val out = ArrayList<Group>()
        var pending = ArrayList<Int>()

        fun flush() {
            if (pending.isEmpty()) return
            val idx = pending.toList()
            out += Group(idx, idx.joinToString(" ") { lines[it].trim() }, true)
            pending = ArrayList()
        }

        lines.forEachIndexed { i, raw ->
            val t = raw.trim()
            when {
                t.isEmpty() -> {
                    flush()
                    out += Group(listOf(i), "", false)
                }
                // 无字母/数字 = 纯符号行（音乐记号、破折号等），译了也没意义
                !t.any { it.isLetterOrDigit() } -> {
                    flush()
                    out += Group(listOf(i), t, false)
                }
                else -> {
                    pending.add(i)
                    val joined = pending.joinToString(" ") { lines[it].trim() }
                    val endsSentence = t.last() in SENTENCE_END
                    if (endsSentence || pending.size >= MAX_GROUP_LINES || joined.length >= MAX_GROUP_CHARS) {
                        flush()
                    }
                }
            }
        }
        flush()
        return out
    }

    /** 只取需要送翻译的组（跳过空行/纯符号行）。 */
    fun translatableGroups(lines: List<String>): List<Group> = group(lines).filter { it.translatable }

    // ── 译后：拆回原行数 + 错位兜底 ───────────────────────────────────────────

    /**
     * 把整组译文拆回组内各行。
     *
     * 三档策略，可靠性依次下降，任何一档都不允许"错行"：
     * 1. **单行组**：整段译文即该行译文（无歧义）。
     * 2. **标点段数 == 行数**：按 [SEGMENT_END] 切出的段与行一一对应（最可靠）。
     * 3. **长度比例分配**：按原文各行字数为权重切译文，切点必须吸附到标点（容差内）；
     *    出现空段/非单调切点即判定对不齐。
     * 兜底：以上都不成立 → 整组译文挂到组内第一行，其余行不给译文。
     *
     * @param translated 整组译文（模型输出）
     * @param lines 歌词原文全量列表（用于取组内各行的字数权重）
     */
    fun splitBack(translated: String, lines: List<String>, lineIndices: List<Int>): Alignment {
        val t = translated.trim()
        if (lineIndices.isEmpty() || t.isEmpty()) return Alignment(emptyMap(), false)

        // 档1：单行组，无歧义
        if (lineIndices.size == 1) return Alignment(mapOf(lineIndices[0] to t), false)

        // 档2：标点段数正好等于行数 → 一一对应
        val segs = splitSegments(t)
        if (segs.size == lineIndices.size) {
            return Alignment(lineIndices.indices.associate { lineIndices[it] to segs[it] }, false)
        }

        // 档3：按长度比例分配（切点吸附标点）
        val prop = tryProportional(t, lineIndices.map { lines.getOrElse(it) { "" } })
        if (prop != null) {
            return Alignment(lineIndices.indices.associate { lineIndices[it] to prop[it] }, false)
        }

        // 兜底：整组挂第一行，其余留空——宁可缺行，不可错行
        return Alignment(mapOf(lineIndices[0] to t), true)
    }

    /** 按 [SEGMENT_END] 切段；末尾无标点的残句并入上一段（避免多出一个碎段）。 */
    private fun splitSegments(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(ch)
            if (ch in SEGMENT_END) {
                out += sb.toString()
                sb.clear()
            }
        }
        if (sb.isNotBlank()) {
            val tail = sb.toString()
            if (out.isNotEmpty() && tail.trim().length < 4) {
                out[out.size - 1] = out[out.size - 1] + tail
            } else {
                out += tail
            }
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 按原文各行字数权重切译文。切点必须落在标点后（[SNAP_TOLERANCE] 容差内），
     * 且严格递增、每段非空；任一条不满足返回 null（交调用方走兜底）。
     */
    private fun tryProportional(translated: String, groupLines: List<String>): List<String>? {
        val n = groupLines.size
        if (n < 2) return null
        val weights = groupLines.map { it.trim().length.coerceAtLeast(1) }
        val total = weights.sum().toDouble()
        if (total <= 0.0) return null

        val cuts = ArrayList<Int>()
        var acc = 0
        for (i in 0 until n - 1) {
            acc += weights[i]
            val target = (translated.length * (acc / total)).toInt()
            val snapped = snapToPunct(translated, target) ?: return null
            if (snapped <= 0 || snapped >= translated.length) return null
            if (cuts.isNotEmpty() && snapped <= cuts.last()) return null
            cuts.add(snapped)
        }

        val pieces = ArrayList<String>(n)
        var start = 0
        for (c in cuts) {
            pieces += translated.substring(start, c).trim()
            start = c
        }
        pieces += translated.substring(start).trim()
        if (pieces.any { it.isEmpty() }) return null
        return pieces
    }

    /** 在 [target] 附近找最近的"标点之后"切点；容差内没有则返回 null。 */
    private fun snapToPunct(s: String, target: Int): Int? {
        val lo = (target - SNAP_TOLERANCE).coerceAtLeast(1)
        val hi = (target + SNAP_TOLERANCE).coerceAtMost(s.length - 1)
        if (lo > hi) return null
        var best: Int? = null
        var bestDist = Int.MAX_VALUE
        for (i in lo..hi) {
            if (s[i - 1] in SEGMENT_END) {
                val d = kotlin.math.abs(i - target)
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
        }
        return best
    }

    // ── 便捷入口 ─────────────────────────────────────────────────────────────

    /**
     * 把"组 → 整组译文"的结果汇总成全局 `行号 → 译文` 映射。
     * 缺行号的行（模型漏译 / 对齐失败 / 不可译行）不出现在结果里。
     */
    fun mergeAlignments(
        lines: List<String>,
        groups: List<Group>,
        translatedByGroup: Map<Group, String>,
    ): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for (g in groups) {
            if (!g.translatable) continue
            val t = translatedByGroup[g]?.takeIf { it.isNotBlank() } ?: continue
            out.putAll(splitBack(t, lines, g.lineIndices).byLine)
        }
        return out
    }
}

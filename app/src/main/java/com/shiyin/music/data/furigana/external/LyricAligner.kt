package com.shiyin.music.data.furigana.external

/**
 * 歌词对齐器（V2 重写版）。
 *
 * 放弃"整首逐字精确匹配"策略。改为逐行 fuzzy matching：
 * - 每行用归一化编辑距离计算相似度，超过阈值即匹配
 * - 重复行按出现顺序配对（绝不因重复而全部跳过）
 * - 行内 ruby run 按归一化后的字符序列逐字对齐
 * - 诚实报告：返回实际匹配了多少行/run，未匹配的不编造
 */
object LyricAligner {

    /** 相似度阈值：归一化编辑距离 <= 此值则认为行匹配（0.0=完全相同, 1.0=完全不同） */
    private const val SIMILARITY_THRESHOLD = 0.25

    /** 诊断结果。 */
    data class AlignDiagnostic(
        val success: Boolean,
        val occurrenceCount: Int = 0,
        val localLineCount: Int = 0,
        val extLineCount: Int = 0,
        val matchedLineCount: Int = 0,
        val totalRunCount: Int = 0,
        val firstMismatchDetail: String = "",
    )

    /**
     * 对齐外部带假名歌词到本地歌词。
     * @return (occurrence 列表, 诊断)。occurrence 为空列表表示没匹配到任何行。
     */
    fun alignWithDiagnostics(
        ext: ParsedExternalLyric,
        localLines: List<String>,
    ): Pair<List<OccurrenceReading>?, AlignDiagnostic> {
        if (localLines.isEmpty()) {
            return null to AlignDiagnostic(false, localLineCount = 0, extLineCount = ext.surface.count { it == '\n' } + 1)
        }

        // 外部按行切分
        val extLines = ext.surface.split('\n').filter { it.isNotEmpty() }
        val extNormLines = extLines.map { normalizeString(it) }
        val localNormLines = localLines.map { normalizeString(it) }

        // 逐行 fuzzy 匹配：外部每行找本地最相似的行
        // 重复行按顺序配对（用计数器跟踪每行已配对到本地第几次出现）
        val localUsage = HashMap<String, Int>()  // norm text → 已配对到第几次
        val extToLocal = HashMap<Int, Int>()     // ext line index → local line index
        var matchedLines = 0

        for (ei in extNormLines.indices) {
            val extNorm = extNormLines[ei]
            if (extNorm.isEmpty()) continue

            // 找本地最相似的未用完的行
            var bestLi = -1
            var bestDist = Double.MAX_VALUE
            for (li in localNormLines.indices) {
                val localNorm = localNormLines[li]
                if (localNorm.isEmpty()) continue
                // 如果完全相同，优先用顺序配对
                if (localNorm == extNorm) {
                    // 检查这个文本已被配对了几次，找下一个未用的
                    val used = localUsage[localNorm] ?: 0
                    // 统计本地该文本出现总次数
                    val total = localNormLines.count { it == localNorm }
                    if (used < total) {
                        // 找到第 (used+1) 次出现的 localNorm
                        var count = 0
                        for (k in localNormLines.indices) {
                            if (localNormLines[k] == localNorm) {
                                if (count == used) {
                                    bestLi = k
                                    bestDist = 0.0
                                    break
                                }
                                count++
                            }
                        }
                        break
                    }
                    // 已用完，继续 fuzzy 找
                }
                // fuzzy: 归一化编辑距离
                val dist = normalizedEditDistance(extNorm, localNorm)
                if (dist < bestDist && dist <= SIMILARITY_THRESHOLD) {
                    bestLi = li
                    bestDist = dist
                }
            }

            if (bestLi >= 0) {
                extToLocal[ei] = bestLi
                matchedLines++
                // 记录该本地行已被使用（用于完全相同行的顺序配对）
                val key = localNormLines[bestLi]
                localUsage[key] = (localUsage[key] ?: 0) + 1
            }
        }

        if (matchedLines == 0) {
            return null to AlignDiagnostic(
                success = false,
                localLineCount = localLines.size,
                extLineCount = extLines.size,
                firstMismatchDetail = "没有行能匹配（版本差异过大）",
            )
        }

        // 计算外部各行的起始偏移（在 ext.surface 中）
        // extLines 是 surface.split('\n').filter{nonEmpty}，其 index 与 surface 中
        // 的 \n 行号不同（跳过了空行）。需要精确映射 extLines index → surface offset。
        val extLineStarts = IntArray(extLines.size)
        var surfacePos = 0
        var extLineIdx = 0
        for (c in ext.surface) {
            if (extLineIdx < extLines.size && surfacePos == extLineStarts.getOrElse(extLineIdx) { -1 } && extLineIdx == 0) {
                // first line
            }
            if (c == '\n') {
                // next non-empty line starts after this \n
                val nextStart = surfacePos + 1
                if (extLineIdx + 1 < extLines.size) {
                    extLineStarts[extLineIdx + 1] = nextStart
                }
                // skip empty lines
                while (extLineIdx + 1 < extLines.size) {
                    val candidate = ext.surface.substring(nextStart)
                    if (candidate.isEmpty() || candidate[0] == '\n') {
                        extLineIdx++
                        if (extLineIdx + 1 < extLines.size) extLineStarts[extLineIdx + 1] = nextStart + 1
                        continue
                    }
                    break
                }
                extLineIdx++
            }
            surfacePos++
        }
        // extLineStarts[0] should be 0 (first non-empty line at start of surface)
        // But surface may start with empty lines. Fix: find actual start of each extLine.
        // Simpler approach: just find each extLine in surface sequentially.
        var searchFrom = 0
        for (ei in extLines.indices) {
            val found = ext.surface.indexOf(extLines[ei], searchFrom)
            extLineStarts[ei] = if (found >= 0) found else 0
            searchFrom = extLineStarts[ei] + extLines[ei].length
        }

        // 对每个匹配行内的 ruby run，映射到本地 (lineIndex, charStart)
        val out = ArrayList<OccurrenceReading>()
        for (run in ext.runs) {
            // 找该 run 属于哪个外部行
            var ei = -1
            for (j in extLines.indices) {
                val start = extLineStarts.getOrElse(j) { 0 }
                val end = if (j + 1 < extLines.size) extLineStarts[j + 1] - 1 else ext.surface.length
                if (run.startInSurface >= start && run.startInSurface < end) { ei = j; break }
            }
            if (ei < 0) continue
            val li = extToLocal[ei] ?: continue
            val extLineStart = extLineStarts.getOrElse(ei) { 0 }
            val extLine = extLines[ei]
            val localLine = localLines[li]

            // 行内逐字符对齐：外部 run 的偏移 → 本地行内的 charStart
            // 用 LCS（最长公共子序列）对齐两行的归一化序列，
            // 然后通过 LCS 映射把外部 run 的位置转到本地行内的位置
            val charMap = alignCharPositions(extLine, localLine)
            val runOffsetInExtLine = run.startInSurface - extLineStart
            val localCharStart = charMap[runOffsetInExtLine]
            if (localCharStart != null) {
                out.add(OccurrenceReading(li, localCharStart, run.length, run.reading, ""))
            }
        }

        return out to AlignDiagnostic(
            success = out.isNotEmpty(),
            occurrenceCount = out.size,
            localLineCount = localLines.size,
            extLineCount = extLines.size,
            matchedLineCount = matchedLines,
            totalRunCount = ext.runs.size,
            firstMismatchDetail = if (out.isEmpty()) "匹配了 ${matchedLines} 行但无 ruby run 可对齐" else "",
        )
    }

    /**
     * 行内字符对齐：用 LCS（最长公共子序列）对齐外部行和本地行（归一化后），
     * 返回 外部原始偏移 → 本地原始偏移 的映射。
     * 只映射被 LCS 选中的字符（未被选中的跳过）。
     */
    private fun alignCharPositions(extLine: String, localLine: String): Map<Int, Int> {
        // 归一化：提取非空白字符序列 + 记录原始偏移
        val extChars = ArrayList<Pair<Char, Int>>()  // (normalized char, original index)
        for (ci in extLine.indices) {
            val nc = normalizeChar(extLine[ci])
            if (nc.isNotEmpty()) extChars.add(nc[0] to ci)
        }
        val localChars = ArrayList<Pair<Char, Int>>()
        for (ci in localLine.indices) {
            val nc = normalizeChar(localLine[ci])
            if (nc.isNotEmpty()) localChars.add(nc[0] to ci)
        }

        // LCS 动态规划
        val m = extChars.size
        val n = localChars.size
        if (m == 0 || n == 0) return emptyMap()
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (extChars[i - 1].first == localChars[j - 1].first) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // 回溯：构建 外部原始偏移 → 本地原始偏移 映射
        val map = HashMap<Int, Int>()
        var i = m
        var j = n
        while (i > 0 && j > 0) {
            if (extChars[i - 1].first == localChars[j - 1].first) {
                map[extChars[i - 1].second] = localChars[j - 1].second
                i--; j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }
        return map
    }

    /**
     * 归一化编辑距离（Levenshtein distance / max length）。
     * 返回 0.0（完全相同）~ 1.0（完全不同）。
     */
    private fun normalizedEditDistance(a: String, b: String): Double {
        if (a == b) return 0.0
        if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
        if (b.isEmpty()) return 1.0
        val dist = levenshtein(a, b)
        return dist.toDouble() / maxOf(a.length, b.length)
    }

    /** Levenshtein 距离（两行归一化后通常 < 100 字符，O(mn) 可接受）。 */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val prev = IntArray(n + 1) { it }
        val curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            prev.indices.forEach { k -> prev[k] = curr[k] }
        }
        return prev[n]
    }

    /** @deprecated 用 alignWithDiagnostics 替代 */
    fun align(ext: ParsedExternalLyric, localLines: List<String>): List<OccurrenceReading>? {
        return alignWithDiagnostics(ext, localLines).first
    }

    // ── 以下为旧版整首匹配的辅助函数（保留但不再被主路径使用）──

    private fun buildLocalNorm(localLines: List<String>): Pair<String, Map<Int, Pair<Int, Int>>> {
        val sb = StringBuilder()
        val posToLoc = HashMap<Int, Pair<Int, Int>>()
        for ((lineIdx, line) in localLines.withIndex()) {
            var charStart = 0
            for (c in line) {
                for (nc in normalizeChar(c)) {
                    posToLoc[sb.length] = lineIdx to charStart
                    sb.append(nc)
                }
                charStart++
            }
        }
        return sb.toString() to posToLoc
    }

    private fun buildExtNorm(surface: String): Pair<String, IntArray> {
        val sb = StringBuilder()
        val origToNorm = IntArray(surface.length) { -1 }
        var origIdx = 0
        for (c in surface) {
            for (nc in normalizeChar(c)) {
                origToNorm[origIdx] = sb.length
                sb.append(nc)
            }
            origIdx++
        }
        return sb.toString() to origToNorm
    }

    /** 整个字符串归一化（去空白、全角→半角 ASCII）。 */
    private fun normalizeString(s: String): String {
        val sb = StringBuilder()
        for (c in s) sb.append(normalizeChar(c))
        return sb.toString()
    }

    /** 字符归一：去空白（空格/制表/换行/全角空格）；全角 ASCII→半角。其余原样保留。 */
    private fun normalizeChar(c: Char): String = when {
        c.isWhitespace() || c.code == 0x3000 -> ""
        c.code in 0xFF01..0xFF5E -> (c.code - 0xFEE0).toChar().toString()
        else -> c.toString()
    }
}

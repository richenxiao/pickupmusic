package com.shiyin.music.data.lyrics

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import com.shiyin.music.data.furigana.ContextResolver
import com.shiyin.music.data.furigana.LyricsReadingOverrides
import com.shiyin.music.data.furigana.ReadingDictionary
import com.shiyin.music.ui.components.RubySegment

/**
 * 振假名（furigana）分词引擎接口。V1.1 抽出此接口，便于未来用同一套上层流水线对
 * Kuromoji / Sudachi 等 engine 做 benchmark 与替换，而不改动上层架构。
 */
interface FuriganaEngine {
    /** 把一行切分为原始 token（surface + 片假名 reading + isKnown）。surface 拼接 == 输入。 */
    fun tokenize(text: String): List<FuriganaToken>?
}

/** 一个原始分词 token。reading 为 Kuromoji 返回的片假名读音（未知 token 为 "*" 或 null）。 */
data class FuriganaToken(val surface: String, val reading: String?, val isKnown: Boolean)

/**
 * Kuromoji IPADIC engine 实现（纯 Java、无网络、词表内置，APK +14MB）。
 * 冷启动 ~0.45s（lazy 单例），首次调用阻塞 → 必须在协程后台线程触发。
 */
object KuromojiEngine : FuriganaEngine {
    private val tokenizer: Tokenizer by lazy { Tokenizer() }
    override fun tokenize(text: String): List<FuriganaToken>? {
        val tokens = try { tokenizer.tokenize(text) } catch (t: Throwable) { return null }
        return tokens.map { Token -> FuriganaToken(Token.surface, Token.reading, Token.isKnown) }
            .filter { it.surface.isNotEmpty() }
            .also { ts ->
                if (ts.joinToString("") { it.surface } != text) return null
            }
    }
}

/**
 * V1.1 振假名流水线：把一行歌词切分为 [RubySegment]，按准确率优先级解析读法。
 *
 * 核心原则：**词典里存在一个 reading，不代表歌词里就该显示这个 reading。** 词典
 * reading 只是候选。优先级（高→低）：
 *
 *   1. Song Override   — 歌曲/作者特有当て字（魏→たか、何→なん），绑 mediaId+lyricsHash
 *   2. 歌词纠错小表     — 高频多读法词的歌词常用读法（二人→ふたり、今日→きょう…）
 *   3. JMdict 派生词表  — 单一读法→覆盖（含 IPADIC 不收录词如此方→こちら）；
 *                        多读法→CONFLICT→默认不显示（宁可无假名，不显错假名）
 *   4. Kuromoji         — 单 token 已知且读法合法平假名→采用；OOV/破裂→No Reading
 *   5. No Reading       — 汉字照显，上方不放错读法
 *
 * 词级处理（§6）：用最长匹配把连续 token 合并成 surface 再查各词典，使「二人」
 * 「此方」「真夏」作为整体 RubySegment，不被逐字拆错。送假名拆分（splitOkurigana）
 * 仍用于 Kuromoji 单 token fallback（美しい→美/うつく+しい）。
 */
object FuriganaTokenizer {

    private val engine: FuriganaEngine = KuromojiEngine

    /** V1 兼容：纯 Kuromoji（无 override / evidence / JMdict）。供旧测试与无网络降级使用。 */
    fun toSegments(line: String): List<RubySegment> =
        toSegments(line, lineOverrides = null, externalEvidence = null, correction = null, jmdict = null)

    /**
     * V1.1+ 流水线。优先级（高→低）：
     *   1. Occurrence Override（lineOverrides，position-keyed）— 最高，最终人工层
     *   2. 外部 evidence（externalEvidence，position-keyed）— UtaTen 等带假名歌词站对齐结果
     *   3. JMdict 单一读法（HIGH）/ CONFLICT→ContextResolver 消歧
     *   4. Kuromoji fallback / No Reading
     * 2 与 3-4 都在段确定后按 charStart 替换；1 最后替换（覆盖 2-3-4）。
     *
     * @param lineOverrides 该行 charStart→reading（Song Override，已按 song+line 过滤）
     * @param externalEvidence 该行 charStart→(length, reading)（外部 evidence，已按 song+line 过滤）
     * @param correction    自撰歌词纠错小表（可为空）
     * @param jmdict       JMdict 派生读法词典（ReadingDictionary 接口，可为空）
     */
    fun toSegments(
        line: String,
        lineOverrides: Map<Int, String>?,
        externalEvidence: Map<Int, Pair<Int, String>>?,
        correction: LyricsReadingOverrides?,
        jmdict: ReadingDictionary?,
    ): List<RubySegment> {
        if (line.isBlank()) return listOf(RubySegment(line, null, 0))
        val tokens = engine.tokenize(line) ?: return listOf(RubySegment(line, null, 0))
        val out = ArrayList<RubySegment>(tokens.size + 4)
        // token 在原文中的起始偏移（tokens surface 拼接==原文，故累加即可）
        val tokenStart = IntArray(tokens.size)
        var acc = 0
        for (t in tokens.indices) { tokenStart[t] = acc; acc += tokens[t].surface.length }
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            val segStart = tokenStart[i]
            if (!tok.surface.any(::isKanji)) {
                out.add(RubySegment(tok.surface, null, segStart)); i++; continue
            }
            // 最长匹配（仅 correction + JMdict，决定 token 合并与该段读法）。
            // Song Override 不参与合并搜索——它是"该位置段的读法替换"，最高优先级，
            // 在段确定后替换其 reading，不改变 token 消费。
            var consumed = 0
            var matchedReading: String? = null
            val maxK = minOf(i + 6, tokens.size)
            for (k in maxK downTo (i + 1)) {
                val surface = (i until k).joinToString("") { tokens[it].surface }
                if (consumed == 0) correction?.reading(surface)?.let { matchedReading = it; consumed = k - i }
                if (consumed == 0 && jmdict != null) {
                    val rs = jmdict.readings(surface)
                    if (rs != null) {
                        if (rs.size == 1) { matchedReading = rs[0]; consumed = k - i }
                        else {
                            // CONFLICT（多合法读法）——分层消歧：
                            // 1. ContextResolver 高置信规则（何が→なに / 何だ→なん）
                            // 2. Kuromoji 读法被 JMdict 确认（在候选中）→ 显示它（能确定就显示）
                            // 3. 无法可靠判断 → No Reading（不猜）
                            val nextSurf = tokens.getOrNull(k)?.surface
                            val resolved = ContextResolver.resolve(surface, rs, nextSurf)
                            if (resolved != null) {
                                matchedReading = resolved
                            } else {
                                val kmReading = (i until k).mapNotNull { j -> kuromojiReadingHira(tokens[j]) }.joinToString("")
                                matchedReading = if (kmReading.isNotEmpty() && rs.contains(kmReading)) kmReading else null
                            }
                            consumed = k - i
                        }
                    }
                }
                if (consumed > 0) break // 取最长匹配
            }
            if (consumed == 0) {
                // 无词典命中 → Kuromoji 单 token fallback。若有合法读法且可送假名拆分，
                // 拆成「汉字段/送假名段」子段（各带相对整行 offset）。
                // ⚠️ 拆分失败 → No Reading（不 group-ruby，绝不把读法覆盖到送假名上）。
                val readingHira = kuromojiReadingHira(tok)
                if (readingHira == null || readingHira == tok.surface) {
                    out.add(RubySegment(tok.surface, null, segStart))
                } else {
                    val split = splitOkurigana(tok.surface, readingHira)
                    if (split != null) {
                        for (sub in split) out.add(RubySegment(sub.surface, sub.reading, segStart + sub.startOffset))
                    } else out.add(RubySegment(tok.surface, null, segStart))  // 拆分失败 → 不注音
                }
                i++
                continue
            }
            // 合并段（JMdict/纠错/CONFLICT 命中）：若有读法，也跑 splitOkurigana，
            // 把读法只落在汉字段上、送假名段不注音。拆分失败 → No Reading（不 group-ruby）。
            val surface = (i until i + consumed).joinToString("") { tokens[it].surface }
            if (matchedReading != null) {
                val split = splitOkurigana(surface, matchedReading)
                if (split != null) {
                    for (sub in split) out.add(RubySegment(sub.surface, sub.reading, segStart + sub.startOffset))
                } else {
                    out.add(RubySegment(surface, null, segStart))  // 拆分失败 → 不注音
                }
            } else {
                out.add(RubySegment(surface, null, segStart))
            }
            i += consumed
        }
        // 外部 evidence（按出现位置，优先级 2）：UtaTen 等带假名歌词站对齐的 occurrence
        // 读法。在 base（JMdict/ContextResolver/Kuromoji）之后、Occurrence Override 之前替换。
        // ⚠️ 外部 source 常给逐字 run（音@0→おん, 楽@1→がく），而本流水线段是合并词
        // （音楽 一段）。故不能只查段起点的单个 run——要按段范围 [start, start+length)
        // 收集并拼接覆盖该段的 run，且仅当 run 精确铺满该段时才采用（否则跳过，安全不错位）。
        // 例：音楽段[0,2) → 音@0(1,おん)+楽@1(1,がく) 铺满 → おんがく；真実段[0,2) →
        // 真実@0(2,しんじつ) 精确 → しんじつ；真段[0,1) 被真実@0(2,..) 越界 → 不采用。
        if (externalEvidence != null && externalEvidence.isNotEmpty()) {
            for (idx in out.indices) {
                val seg = out[idx]
                val ev = resolveExternalForSegment(externalEvidence, seg.startOffset, seg.surface.length)
                if (ev != null) out[idx] = seg.copy(reading = ev)
            }
        }
        // Song Override（按出现位置，优先级 1 最高）：最后替换，覆盖外部 evidence。
        // 使同一 surface 多次出现可分别设（何 有的なに 有的なん，不归一）。按每段自身 charStart 定位。
        if (lineOverrides != null && lineOverrides.isNotEmpty()) {
            for (idx in out.indices) {
                lineOverrides[out[idx].startOffset]?.let { ov ->
                    out[idx] = out[idx].copy(reading = ov)
                }
            }
        }
        return if (out.joinToString("") { it.surface } == line) out
        else listOf(RubySegment(line, null, 0))
    }

    /** 对段 [start, start+length) 解析外部 evidence：若单个 run 精确匹配(start,length)
     *  或若干 run 从 start 起精确铺满 [start, start+length)，返回拼接读法；否则返回 null
     *  （不铺满/越界 → 不采用，安全不错位）。 */
    private fun resolveExternalForSegment(
        ext: Map<Int, Pair<Int, String>>,
        start: Int,
        length: Int,
    ): String? {
        // 1. 单个 run 精确匹配
        ext[start]?.let { (runLen, reading) -> if (runLen == length) return reading }
        // 2. 从 start 起逐 run 铺满
        val sb = StringBuilder()
        var pos = start
        while (pos < start + length) {
            val run = ext[pos] ?: return null  // 该位置无 run → 无法铺满
            if (pos + run.first > start + length) return null  // 越界 → 不采用
            sb.append(run.second)
            pos += run.first
        }
        return if (pos == start + length) sb.toString() else null
    }

    /** Kuromoji 单 token 读法：known 且 reading 非空/非 "*" → 平假名；须过合法假名校验。 */
    private fun kuromojiReadingHira(tok: FuriganaToken): String? {
        val kata = tok.reading
        if (!tok.isKnown || kata.isNullOrEmpty() || kata == "*") return null
        val hira = katakanaToHiragana(kata)
        return if (isValidKanaReading(hira)) hira else null
    }

    /**
     * 把 surface 拆成交替的「汉字段 / 非汉字段」，并按送假名位置切回读音。
     * 返回 null 表示无法对齐，调用方退化为 group-ruby。
     */
    private fun splitOkurigana(surface: String, reading: String): List<RubySegment>? {
        val runs = ArrayList<Pair<String, Boolean>>()
        var i = 0
        while (i < surface.length) {
            val kan = isKanji(surface[i])
            var j = i
            while (j < surface.length && isKanji(surface[j]) == kan) j++
            runs.add(surface.substring(i, j) to kan)
            i = j
        }
        if (runs.isEmpty()) return null
        var pos = 0
        var runStart = 0  // 当前 run 在 surface 中的字符起始偏移
        val out = ArrayList<RubySegment>(runs.size)
        for ((idx, run) in runs.withIndex()) {
            val (text, isKan) = run
            if (isKan) {
                val endPos = if (idx == runs.lastIndex) reading.length
                else {
                    val nextNonKanjiText = runs[idx + 1].first
                    val found = reading.indexOf(nextNonKanjiText, pos)
                    if (found < pos) return null
                    found
                }
                if (endPos > reading.length) return null
                out.add(RubySegment(text, reading.substring(pos, endPos).ifEmpty { null }, runStart))
                pos = endPos
            } else {
                if (pos + text.length > reading.length) return null
                if (!reading.regionMatches(pos, text, 0, text.length)) return null
                out.add(RubySegment(text, null, runStart))
                pos += text.length
            }
            runStart += text.length
        }
        return if (pos == reading.length) out else null
    }

    /** 片假名 → 平假名：0x30A1..0x30F6 区间减 0x60；长音 ー 保留。 */
    private fun katakanaToHiragana(katakana: String): String {
        val sb = StringBuilder(katakana.length)
        for (ch in katakana) {
            sb.append(
                if (ch.code in 0x30A1..0x30F6) (ch.code - 0x60).toChar()
                else if (ch.code == 0x30FC) 'ー'
                else ch
            )
        }
        return sb.toString()
    }

    /** 是否为汉字（常用 CJK 区 + 扩展 A + 兼容汉字）。 */
    private fun isKanji(ch: Char): Boolean {
        val c = ch.code
        return c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF
    }

    /**
     * 合法假名读音校验：reading 必须非空，且每个字符都是平假名 / 片假名 / 长音 ー /
     * 返点 々 ヽ ヾ / ゝ ゞ 。任何罗马字母、空格、数字、"*" 等非假名字符出现即视为
     * 格式非法 → 不可信 → 不注音（宁可无假名，不显错假名）。兜底防任何环节产出格式
     * 破碎的 reading 被当正常结果渲染。
     */
    private fun isValidKanaReading(reading: String): Boolean {
        if (reading.isEmpty()) return false
        for (ch in reading) {
            val c = ch.code
            val ok = (c in 0x3041..0x3096) ||
                (c in 0x30A1..0x30FA) ||
                c == 0x30FC || c == 0x3005 ||
                c == 0x30FD || c == 0x30FE ||
                c == 0x3006 || c == 0x3007
            if (!ok) return false
        }
        return true
    }
}

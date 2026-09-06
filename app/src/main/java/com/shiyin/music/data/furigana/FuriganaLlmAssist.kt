package com.shiyin.music.data.furigana

import com.google.gson.JsonParser
import com.shiyin.music.data.lyrics.FuriganaTokenizer
import com.shiyin.music.ui.components.RubySegment

/**
 * v1.3.x 振假名 LLM 兜底层（纯逻辑,无 Android 依赖,可单测）。
 *
 * 职责:本地词典/JMdict/UtaTen 都没解决的"空缺段"(无注音的汉字段),收集起来交给
 * LLM 结合整句语境补注。两条硬防线保证 LLM 不会引入坏数据:
 *  1. **位置白名单**:LLM 返回的 (line,start,len) 必须与发给它的空缺段逐一精确
 *     匹配——错位/编造位置直接丢弃,绝不回填到别处。
 *  2. **候选约束**:JMdict 有多读法候选(CONFLICT)的段,LLM 的答案必须从候选里选
 *     (LLM 只做语境选择题,不开放自由发挥);读法必须全是合法假名
 *     ([FuriganaTokenizer.isValidKanaReading],复用既有校验)。
 * 写入侧以 source="llm" 落 external_reading_evidence——管线优先级低于人工 Song
 * Override 与 UtaTen 行,LLM 错了可长按改、可被 UtaTen 到货后覆盖。
 */
object FuriganaLlmAssist {

    /** 一个待补注的空缺段(行号/行内起始/长度/原文/词典候选,可能为空)。 */
    data class Gap(
        val lineIndex: Int,
        val charStart: Int,
        val length: Int,
        val surface: String,
        val candidates: List<String> = emptyList(),
    )

    /** LLM 补注结果(已通过白名单+假名校验)。 */
    data class Fix(val lineIndex: Int, val charStart: Int, val length: Int, val reading: String)

    /** 从全曲分词结果里收集"无注音的汉字段"=[Gap]。candidatesOf 给该 surface 查
     *  JMdict 候选(CONFLICT 多读法),无候选(词典未收录)则空列表。 */
    fun collectGaps(
        segmentsByLine: List<List<RubySegment>>,
        candidatesOf: (String) -> List<String>,
    ): List<Gap> {
        val out = ArrayList<Gap>()
        for ((li, segs) in segmentsByLine.withIndex()) {
            for (seg in segs) {
                if (!seg.reading.isNullOrEmpty()) continue
                if (seg.surface.none { com.shiyin.music.data.lyrics.FuriganaTokenizer.isKanji(it) }) continue
                out.add(Gap(li, seg.startOffset, seg.surface.length, seg.surface, candidatesOf(seg.surface)))
            }
        }
        return out
    }

    /** 构建补注 prompt。歌词全文带行号(语境),空缺段清单列出候选。 */
    fun buildPrompt(lines: List<String>, gaps: List<Gap>): String {
        val lyricBlock = lines.withIndex().joinToString("\n") { (i, t) -> "L$i $t" }
        val gapBlock = gaps.joinToString("\n") { g ->
            val cand = if (g.candidates.isEmpty()) "" else " 候选:" + g.candidates.joinToString("|")
            "- line=${g.lineIndex} start=${g.charStart} len=${g.length} 「${g.surface}」$cand"
        }
        return """
            你是日语歌词注音助手。下面歌词中标出的汉字片段没有确定读音(词典多读法冲突或未收录)。请结合整句语境给出最可能的读音。

            规则:
            1. 片段列出了候选读法时,必须从候选里选一个;没有候选时才给出你自己判断的读音
            2. 读音是该片段的完整平假名读音(片段末尾若带假名,读音要包含那部分)
            3. 拿不准的片段直接跳过,不要编造
            4. 只返回 JSON:{"fixes":[{"line":<行号>,"start":<起始>,"len":<长度>,"reading":"平假名"}]},不要任何其他文字

            歌词:
            $lyricBlock

            待注音片段:
            $gapBlock
        """.trimIndent()
    }

    /**
     * 解析 LLM 返回的补注清单。逐条过三道校验,任何一道不过即丢弃该条(宁弃勿错):
     * ①(line,start,len) 必须与 [gaps] 白名单精确匹配;②读法必须全是合法假名;
     * ③该段有候选时读法必须在候选内。JSON 提取容错:直接解析失败时退化用大括号
     * 正则抠第一个 JSON 对象(模型偶尔包 ``` 围栏或前后缀文字)。
     */
    fun parseFixes(raw: String, gaps: List<Gap>): List<Fix> {
        val whitelist = HashMap<String, Gap>(gaps.size)
        for (g in gaps) whitelist["${g.lineIndex}|${g.charStart}|${g.length}"] = g
        val obj = runCatching { JsonParser.parseString(raw.trim()).asJsonObject }.getOrNull()
            ?: run {
                val m = Regex("""\{.*}""", RegexOption.DOT_MATCHES_ALL).find(raw) ?: return emptyList()
                runCatching { JsonParser.parseString(m.value).asJsonObject }.getOrNull() ?: return emptyList()
            }
        val arr = runCatching { obj.getAsJsonArray("fixes") }.getOrNull() ?: return emptyList()
        // 按位置去重(后到覆盖先到)——模型偶尔对同一片段给两行,保留它的最终判断
        val byPos = LinkedHashMap<String, Fix>()
        for (el in arr) {
            val o = runCatching { el.asJsonObject }.getOrNull() ?: continue
            val line = o.get("line")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            val start = o.get("start")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            val len = o.get("len")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            val reading = o.get("reading")?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: continue
            val g = whitelist["$line|$start|$len"] ?: continue
            if (!FuriganaTokenizer.isValidKanaReading(reading)) continue
            if (g.candidates.isNotEmpty() && reading !in g.candidates) continue
            byPos["$line|$start|$len"] = Fix(line, start, len, reading)
        }
        return byPos.values.toList()
    }
}

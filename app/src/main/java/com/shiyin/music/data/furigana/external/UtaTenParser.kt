package com.shiyin.music.data.furigana.external

/**
 * UtaTen 歌词 HTML 解析器（JVM-pure，无 Android 依赖，可单测）。
 *
 * UtaTen 的ふりがなブロック结构（实测，server-rendered，无需 JS）：
 *   <div class="hiragana">
 *     平假名/标点纯文本
 *     <span class="ruby"><span class="rb">漢字</span><span class="rt">かな</span></span>
 *     <br />            ← 行边界
 *     ...
 *   </div>
 * rb = ruby base（汉字 surface），rt = ruby text（假名读法）。
 *
 * 解析产出 [ParsedExternalLyric]：flat surface（<br>→\n，空格保留）+ 各 rb run 的读法。
 * 注：UtaTen 页面同时含一个 romaji 块（rt 为罗马字），本解析器只取 class="hiragana" 块。
 */
object UtaTenParser {

    /** 从整页 HTML 中提取 class="hiragana" 块并解析。返回 null 表示未找到/解析失败。 */
    fun parsePage(html: String): ParsedExternalLyric? {
        val block = extractHiraganaBlock(html) ?: return null
        return parseBlock(block)
    }

    /** 直接解析 hiragana 块 HTML。 */
    fun parseBlock(blockHtml: String): ParsedExternalLyric {
        val surface = StringBuilder()
        val runs = ArrayList<ParsedExternalLyric.ExtRun>()
        // token 化：扫描 <span class="ruby"> / <span class="rb"> / <span class="rt"> / </span> / <br>
        var i = 0
        val n = blockHtml.length
        val rbTag = "<span class=\"rb\">"
        val rtTag = "<span class=\"rt\">"
        val endSpan = "</span>"
        val br = Regex("<br\\s*/?>")
        while (i < n) {
            when {
                blockHtml.startsWith("<span class=\"ruby\"", i) -> {
                    // 找 rb 内容
                    val rbStart = blockHtml.indexOf(rbTag, i)
                    if (rbStart < 0) { i++; continue }
                    val rbContentStart = rbStart + rbTag.length
                    val rbContentEnd = blockHtml.indexOf(endSpan, rbContentStart)
                    if (rbContentEnd < 0) { i = rbContentStart; continue }
                    val rb = unescape(blockHtml.substring(rbContentStart, rbContentEnd))
                    // 找 rt 内容
                    val rtStart = blockHtml.indexOf(rtTag, rbContentEnd)
                    if (rtStart < 0) { i = rbContentEnd + endSpan.length; continue }
                    val rtContentStart = rtStart + rtTag.length
                    val rtContentEnd = blockHtml.indexOf(endSpan, rtContentStart)
                    if (rtContentEnd < 0) { i = rtContentStart; continue }
                    val rt = unescape(blockHtml.substring(rtContentStart, rtContentEnd))
                    // 追加 rb 到 surface（汉字 run），记录读法
                    if (rb.isNotEmpty()) {
                        runs.add(ParsedExternalLyric.ExtRun(surface.length, rb.length, rt))
                        surface.append(rb)
                    }
                    // 跳过整个 ruby span（到其闭合 </span>，即 rt 之后的 </span>）
                    i = rtContentEnd + endSpan.length
                }
                br.find(blockHtml, i) != null && blockHtml.indexOf("<br", i) == i -> {
                    val m = br.find(blockHtml, i)!!
                    surface.append('\n')
                    i += m.value.length
                }
                blockHtml[i] == '<' -> {
                    // 其他标签（div/span 开闭等）：跳到 >
                    val gt = blockHtml.indexOf('>', i)
                    i = if (gt >= 0) gt + 1 else i + 1
                }
                else -> {
                    // 纯文本（平假名/标点/空格）
                    val nextTag = blockHtml.indexOf('<', i)
                    val end = if (nextTag >= 0) nextTag else n
                    surface.append(unescape(blockHtml.substring(i, end)))
                    i = end
                }
            }
        }
        return ParsedExternalLyric(surface.toString(), runs)
    }

    private fun extractHiraganaBlock(html: String): String? {
        val key = "class=\"hiragana\""
        val attrIdx = html.indexOf(key)
        if (attrIdx < 0) return null
        // 回溯到包含该属性的 <div 开标签起点，使 parseBlock 先遇到 <div...> 被当作标签跳过，
        // 而不是把 "class=\"hiragana\" >" 当纯文本吞进 surface。
        var start = attrIdx
        while (start > 0 && html[start] != '<') start--
        val contentStart = html.indexOf('>', attrIdx) + 1
        val end = html.indexOf("</div>", contentStart)
        return if (end >= 0) html.substring(start, end + "</div>".length) else null
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&hellip;", "…")
        .replace("&nbsp;", " ")
}

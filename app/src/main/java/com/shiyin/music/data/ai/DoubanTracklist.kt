package com.shiyin.music.data.ai

/**
 * v1.3.15: 从**豆瓣音乐专辑页**抽取官方曲目顺序。
 *
 * 为什么走这条路(真机 2026-10-02《龙年》验证):
 *  - 通用网页搜索把"同名作品的 B 站搬运简介"当成了权威资料,曲目与本地一首都不重合,却排错还报成功;
 *  - 豆瓣专辑页有**结构化的"曲目"段**(服务端渲染),Tavily 的 extract 能**原样抓下来**,
 *    而且那个顺序**就是官方顺序**(实测《龙年》:抽出来的 14 首与用户手贴的官方序一字不差);
 *  - 于是分工回到正轨:**代码提供事实(官方曲目表),模型只在"官方名↔本地名"对不上时出手**。
 *
 * 纯函数:抽 URL、解析曲目段、按重合度挑最佳候选 —— 都可以脱机单测。
 */
internal object DoubanTracklist {

    /** "曲目"段之后的终止行(豆瓣页面里紧随其后的版块)。 */
    private val SECTION_STOP = Regex("""^(我来说两句|短评|乐评|喜欢这张唱片的人|以下豆列推荐|标签)""")

    /**
     * 专辑页 URL(带 subject id)。**注意两种域名形态**:
     *  - 桌面 `music.douban.com/subject/<id>`
     *  - 移动 `m.douban.com/music/subject/<id>` ← 实测搜索命中的就是这种(用域名白名单
     *    只写 music.douban.com 会把它挡住,这正是"限定域名"那一版失败的原因之一)
     */
    private val SUBJECT_URL = Regex("""https?://(?:[a-z0-9.]*\.)?douban\.com(?:/music)?/subject/(\d+)""")

    /** 从任意文本(搜索结果 / 抽取内容)里挖出豆瓣专辑页 URL,去重保序。 */
    fun subjectUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return SUBJECT_URL.findAll(text).map { it.value }.distinct().toList()
    }

    /**
     * 解析抽取出来的页面正文 → 官方曲目名列表(保持页面顺序)。
     *
     * 实测形态(豆瓣专辑页):
     *   ```
     *   ## 曲目 ······
     *      枣庄市马思唯
     *      轨道飞虎
     *      ...
     *   我来说两句
     *   ```
     * 解析纪律:**只在"曲目"段内取行**,遇到下一个版块标题/明显终止行就停 ——
     * 这张页面里"短评"段落也有一堆人名/句子,越界取行会把短评当曲目(真机上就是"排得莫名其妙")。
     */
    fun parseTracklist(extracted: String?): List<String> {
        if (extracted.isNullOrBlank()) return emptyList()
        val lines = extracted.lines()
        val start = lines.indexOfFirst { isTracklistHeader(it) }
        if (start < 0) return emptyList()
        val out = ArrayList<String>(24)
        for (i in start + 1 until lines.size) {
            val raw = lines[i].trim()
            if (raw.isEmpty()) continue
            if (raw.startsWith("##") || SECTION_STOP.containsMatchIn(raw)) break
            // 去掉行首编号/项目符号与 markdown 装饰(豆瓣的曲目行通常是纯名字,但兜一层)
            val name = raw.trimStart('-', '*', '·', ' ').trim()
                .replace(Regex("""^\d{1,2}[.、,，)）]\s*"""), "")
                .trim('「', '」', '《', '》', '"', '“', '”')
            if (name.isNotEmpty() && name.length <= 60) out += name
        }
        return out
    }

    private fun isTracklistHeader(line: String): Boolean {
        val t = line.trim().trimStart('#').trim()
        return t.startsWith("曲目")
    }

    /**
     * 在多个候选页面里挑"最像这张专辑"的那一份:与本地曲目**重合最多**者胜。
     *
     * 为什么必须挑:豆瓣搜索会返回同名的若干条目(以及标签页/豆列),抓错页面 = 排错。
     * 重合度就是判据(复用排序链路里同一套归一匹配),重合为 0 的一律不要。
     */
    fun pickBest(candidates: List<List<String>>, localTitles: List<String>): List<String>? {
        if (localTitles.isEmpty()) return null
        var best: List<String>? = null
        var bestHits = 0
        for (c in candidates) {
            if (c.isEmpty()) continue
            val hits = overlap(c, localTitles)
            if (hits > bestHits) { bestHits = hits; best = c }
        }
        return if (bestHits > 0) best else null
    }

    /** 两组曲名按归一后**精确包含**计数(与 AgentService.webContextHits 同一口径)。 */
    internal fun overlap(a: List<String>, b: List<String>): Int {
        val bn = b.map { AgentService.normTitleForMatch(it) }.filter { it.length >= 2 }.toSet()
        if (bn.isEmpty()) return 0
        return a.count { AgentService.normTitleForMatch(it) in bn }
    }
}

package com.shiyin.music.data.ai

import com.shiyin.music.data.search.FuzzySearch

/**
 * v1.3.12:联网搜索的**触发策略** —— 纯 Kotlin,零 Android,可单测。
 *
 * 真机实测两个反面:弱模型(1)**太不愿意搜**(问"最近出了什么新歌"也凭记忆答),
 * (2)一旦给了 web_search 又可能**什么都搜**(聊音乐感想也要搜一遍)。
 *
 * 分工:
 *  - [explicitQuery]:用户**明确要求**联网("帮我搜一下 X""网上查查 X")→ 返回查询词。
 *    这条是**确定性**的:调用方可以直接先搜好、把资料塞进上下文,不指望弱模型记得调工具。
 *  - [looksTimeSensitive]:问法天然需要最新信息("最近/最新/今年/发行/巡演…")→ 只作为
 *    **提示**(判断仍交给模型),不强制联网 —— 避免"所有问题都联网"。
 *
 * 反例(都不该触发):音乐观点/感想/闲聊("你觉得 X 怎么样""秋天适合听什么歌")。
 */
object WebSearchPolicy {

    /** 明确要求联网的说法。 */
    private val EXPLICIT = listOf(
        "帮我搜", "帮我查", "给我搜", "给我查", "搜一下", "搜索一下", "搜搜看", "搜搜",
        "查一下", "查一查", "查查", "网上查", "网上搜", "上网查", "上网搜", "联网查", "联网搜",
        "搜索", "百度一下", "google一下",
    )

    /** 需要"当下/最新"信息的问法(提示用,不强制)。 */
    private val TIME_SENSITIVE = listOf(
        "最新", "最近", "今年", "去年", "目前", "现在", "当前", "近期",
        "新歌", "新专辑", "新专", "新作", "新曲", "近况", "动态",
        "什么时候出", "什么时候发", "什么时候上映", "几号", "哪一年", "哪年",
        "发行", "发售", "上线", "出道", "成立", "解散", "复出",
        "巡演", "演唱会", "门票", "榜单", "排行", "销量", "获奖", "新闻", "消息",
    )

    /** 抽查询词时要剥掉的引导语/语气词。 */
    private val LEAD = listOf("帮我", "给我", "请", "麻烦", "你能不能", "你能", "可以", "一下", "下")

    /**
     * 用户**明确要求联网**吗?是 → 返回要搜的查询词(已剥掉"帮我搜一下"这类引导语);
     * 否(或剥完没剩下内容) → null。
     */
    fun explicitQuery(userText: String): String? {
        val t = userText.trim()
        if (t.isEmpty()) return null
        val marker = EXPLICIT.firstOrNull { t.contains(it) } ?: return null
        // 取标记词之后的部分为主;若标记词在句尾(「帮我搜一下」)则退回整句删掉标记词
        var rest = t.substringAfter(marker).trim()
        if (rest.length < 2) rest = t.replace(marker, "").trim()
        var q = rest
        for (lead in LEAD) q = q.removePrefix(lead).trim()
        q = q.trim('，', ',', '：', ':', '。', '.', '!', '！', '?', '？', ' ', '\n')
        // 去掉句尾语气词(「…最新消息吧」→「…最新消息」)
        while (q.isNotEmpty() && (q.endsWith("吧") || q.endsWith("啊") || q.endsWith("呢") || q.endsWith("哦"))) {
            q = q.dropLast(1).trim()
        }
        return q.takeIf { it.length >= 2 }
    }

    /** 问法是否天然依赖"当下/最新"的信息(命中 → 提示模型应该联网核实)。 */
    fun looksTimeSensitive(userText: String): Boolean {
        val t = FuzzySearch.normalize(userText)
        if (t.isEmpty()) return false
        return TIME_SENSITIVE.any { t.contains(it) }
    }

    /** 是否属于"音乐观点/感想"——这类**不要**因为语义沾边就去搜。 */
    fun looksOpinion(userText: String): Boolean {
        val t = FuzzySearch.normalize(userText)
        if (t.isEmpty()) return false
        val cues = listOf("你觉得", "怎么样", "好不好听", "喜欢", "很适合", "适合听", "好听", "伤感", "感觉", "想起", "有意思")
        return cues.any { t.contains(it) }
    }
}

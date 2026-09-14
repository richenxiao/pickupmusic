package com.shiyin.music.data.lyrics

/**
 * v1.3.x: agent websearch 歌词输出的内容守卫。
 *
 * 背景:LLM 拒绝回答时输出"抱歉,我无法提供歌词,因为版权…"——这段话会被
 * LrcParser 解析成"一行纯文本歌词"并当歌词导入(用户实测"AI 把道歉信显示
 * 成歌词")。模型的解释性长段落同理。这里在导入前做两层校验:
 *  ① 拒绝语识别 → 直接失败
 *  ② 歌词形态校验(多行短行结构) → 非歌词形态拒绝导入
 */
object LlmLyricsGuard {

    /** 拒绝语特征——模型说"我不给"的各种措辞(中英)。 */
    private val REFUSAL_PATTERNS = listOf(
        Regex("""抱歉|对不起|不好意思"""),
        Regex("""无法|不能|没办法|不予"""),
        Regex("""(提供|提供不了|生成|给出|复制|分享|展示)(歌词|完整歌词)"""),
        Regex("""(?i)(I can'?t|I cannot|I'm unable|sorry)[^.。!?！？]{0,40}(lyrics?|song)"""),
        Regex("""(?i)copyright|版权|受版权"""),
        Regex("""(?i)I (can only|apologize)"""),
    )

    /** 拒绝语判定:前 120 字符内命中 ≥2 个特征(拒绝语几乎总在开头)。 */
    fun isRefusal(text: String): Boolean {
        val head = text.take(120)
        return REFUSAL_PATTERNS.count { it.containsMatchIn(head) } >= 2
    }

    /** 失败原因里给用户看的拒绝语摘要(首句,截断)。 */
    fun refusalSummary(text: String): String {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        return first.take(40).ifEmpty { "模型拒绝了请求" }
    }

    /**
     * 歌词形态校验。真歌词的结构特征(与来源无关):
     *  - 行数 ≥ 4(歌一句一行,几行成段;单句/两行必不是歌)
     *  - 行长短:中位行长 ≤ 40 字符(副歌重复段、标点行也短)
     *  - 带时间戳([mm:ss.xx])直接放行(结构化 LRC 不可能是解释段落)
     * 解释性段落的特征恰恰相反:行数少、单行超长、无时间戳。
     */
    fun looksLikeLyrics(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        // 有 ≥4 个时间戳行 → 结构化 LRC,放行
        val tagged = lines.count { it.contains(Regex("""\[\d{1,3}:\d{1,2}[.:]?\d{0,3}]""")) }
        if (tagged >= 4) return true
        if (lines.size < 4) return false
        val sorted = lines.map { it.length }.sorted()
        val median = sorted[sorted.size / 2]
        return median <= 40
    }
}

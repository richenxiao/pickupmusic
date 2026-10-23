package com.shiyin.music.data.lyrics

/**
 * v1.3.10 需求 A30:LrcShare 歌词的来源署名清洗。
 *
 * LrcShare 返回的 LRC 会带一套"前缀+后缀"来源标注(真机实测不想要):
 *   [ti: 老师好]
 *   [ar: 阴三儿]
 *   [al: 未知艺术家]
 *   [length: 05:15.48]
 *   [00:00.000] 老师好-阴三儿
 *   [00:00.320] 词曲:阴三儿
 *   [00:00.680] LRC:X2ISPANDA[https://lrcshare.com]
 *   [00:00.970]
 *   ...真正的歌词...
 *   本内容来自LrcShare
 *   链接:https://lrcshare.com/song/s_in3_004
 *
 * 设计约束:
 *  - **只删署名/metadata,绝不删真歌词**:不做 `replace("本内容来自LrcShare", "")` 这种
 *    文本级替换——歌词正文出现"来自"两个字是常事,不能误删;
 *  - `[offset:]` **保留**——它是 LRC 的时间同步机制(删了全曲提前/拖后),不是署名;
 *  - 「标题-歌手」回显行(如"老师好-阴三儿")与 credits 行(如"词曲:阴三儿")只在
 *    **头部区域**判定,且标题/歌手必须与当前曲目完全一致——避免误删正文中恰好同名的歌词;
 *  - 大小写/全角冒号/空格差异都容忍;
 *  - **保留原始时间戳**(只剥来用于判定,不落进输出)。
 * 纯逻辑、零 Android/网络依赖——逐条单测(见 LrcShareAttributionTest)。
 */
object LrcShareAttribution {

    // ── 哪些行要剔除 ───────────────────────────────────────────────────────

    /** LRC 头部 metadata 标签。**刻意不含 offset**——那是时间同步机制,不是署名。 */
    private val META = Regex(
        """^\s*\[(ti|ar|al|by|re|ve|length|au)\s*:.*]\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** 行首时间戳(用于剥出正文判定,不用于输出)。 */
    private val TIME_PREFIX = Regex("""^\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]\s*""")

    /** 署名(任何位置都去)。 */
    private val HAS_LRCSHARE = Regex("""lrcshare""", RegexOption.IGNORE_CASE)
    private val FROM_ATTRIBUTION = Regex("""^本(歌词|内容)来自(于)?""", RegexOption.IGNORE_CASE)
    private val LINK_ATTRIBUTION = Regex("""^(链接|link)[:：]\s*https?://""", RegexOption.IGNORE_CASE)

    /** 头部区域才判的 credits / LRC 归属行("词曲:阴三儿"、"LRC:X2ISPANDA" 这类)。 */
    private val CREDITS =
        Regex("""^(词曲|作词|作曲|编曲|混音|制作人?|监制|出品)[:：]""", RegexOption.IGNORE_CASE)
    private val LRC_BY = Regex("""^LRC[:：]""", RegexOption.IGNORE_CASE)

    /** 「标题-歌手」回显只在头部前 [HEADER_LINES] 条非空行里判定。 */
    private const val HEADER_LINES = 8

    // ── 清洗(展示/落库用) ───────────────────────────────────────────────────

    /**
     * 去掉署名与 metadata,返回干净的歌词正文(**保留原始时间戳行格式**)。
     * @param title / [artist] 当前曲目——用于判"标题-歌手"回显行,防止误删正文。
     */
    fun clean(raw: String, title: String, artist: String): String {
        val t = norm(title)
        val a = norm(artist)
        val out = ArrayList<String>()
        var headerSeen = 0
        for (line in raw.lines()) {
            val text = TIME_PREFIX.replaceFirst(line, "").trim()
            val inHeader = headerSeen <= HEADER_LINES
            when {
                line.matches(META) -> Unit
                text.isBlank() -> Unit
                // 署名:任何位置都去(它们永远不会是歌词正文)
                FROM_ATTRIBUTION.containsMatchIn(text) -> Unit
                LINK_ATTRIBUTION.containsMatchIn(text) -> Unit
                HAS_LRCSHARE.containsMatchIn(text) -> Unit
                // credits / LRC 归属行:只在头部区域判定
                inHeader && CREDITS.containsMatchIn(text) -> headerSeen++
                inHeader && LRC_BY.containsMatchIn(text) -> headerSeen++
                inHeader && isTitleArtistEcho(text, t, a) -> headerSeen++
                else -> { out += line; headerSeen++ }
            }
        }
        return out.joinToString("\n")
    }

    // ── 署名提取(内部 metadata;不进正文) ──────────────────────────────────

    data class Attribution(val contributor: String?, val url: String?)

    private val URL_RE = Regex("""https?://lrcshare\.com/\S*""", RegexOption.IGNORE_CASE)
    private val LRC_BY_NAME = Regex("""^LRC[:：]\s*([^\[\s]+)""", RegexOption.IGNORE_CASE)

    /** 从原始 LRC 提取(贡献者, 来源 URL);都没有 → null。 */
    fun extract(raw: String): Attribution? {
        var contributor: String? = null
        var url: String? = null
        for (line in raw.lines()) {
            val text = TIME_PREFIX.replaceFirst(line, "").trim()
            if (url == null) url = URL_RE.find(text)?.value
            if (contributor == null) {
                LRC_BY_NAME.find(text)?.groupValues?.get(1)?.let { contributor = it }
            }
        }
        return if (contributor == null && url == null) null else Attribution(contributor, url)
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** 该行是否就是"标题-歌手"回显(任一分隔符/任意空白)。 */
    private fun isTitleArtistEcho(text: String, t: String, a: String): Boolean {
        if (t.isBlank() || a.isBlank()) return false
        val n = norm(text)
        return n == t + a || n == a + t
    }
}

package com.shiyin.music.data.lyrics

/**
 * LrcShare 的**协议层**（2026-10-24 站方改版后的对接）。
 *
 * 改版前它是无鉴权的 GET，现在两条硬要求（见 api.lrcshare.com/docs/guide/requirements）：
 *  1. **必须带 `X-API-Key`**：Key 由使用者**自己发邮件申请**（免费，`ls_` 前缀）。
 *     站方明确要求"不要把 Key 硬编码到开源仓库的公开代码中"，所以它只存在用户设置里，
 *     未配置时本类的一切动作都必须是**零请求**（不是请求失败，是根本不发）。
 *  2. **Catalog-First**：除 `/v1/catalog` 外所有端点还要 `X-LrcShare-Session`，
 *     取自 `/v1/catalog` 的 `session_token`，有效期 24h（过期回 419，需重拉目录）。
 *
 * 站方另外两条要求实现在这里：
 *  · **先取目录再搜索**：目录给的是"全库可搜索文本"，用来在本地**负向过滤** ——
 *    查询串在文本里找不到的歌，搜索必然 0 结果 ⇒ 直接跳过，一个请求都不发。
 *  · **不破坏边缘缓存**：URL 一律纯净，不加时间戳/随机数。
 *
 * `/v1/catalog` 本身虽是"唯一不需要会话凭证的端点"，但**仍需 Key** ⇒ 没 Key 时连目录
 * 都拿不到，所以"没 Key = 整源不可用"。
 */
object LrcShareApi {

    const val HOST = "https://api.lrcshare.com"

    /** 使用者自己申请的 Key（空 = 未配置 ⇒ 整源不可用）。 */
    @Volatile
    var apiKey: String = ""

    /** 请求间隔下限：站方要求批量场景串行 + 200~500ms 间隔，取中值。 */
    const val MIN_GAP_MS = 300L

    /** 目录/会话缓存时长：站方建议"本地缓存 24 小时"，会话令牌本身也是 24h。 */
    const val CATALOG_TTL_MS = 24L * 60 * 60 * 1000

    @Volatile private var catalogText: String = ""
    @Volatile private var sessionToken: String = ""
    @Volatile private var catalogAtMs: Long = 0L
    @Volatile private var lastRequestAtMs: Long = 0L

    /** 站方是不是给我们回过 419（会话过期）——报告用。 */
    @Volatile var sessionExpiredCount: Int = 0
        private set

    fun hasKey(): Boolean = apiKey.isNotBlank()

    /** 会话是否还在有效期内。 */
    fun sessionValid(nowMs: Long): Boolean =
        sessionToken.isNotBlank() && catalogAtMs > 0L && nowMs - catalogAtMs < CATALOG_TTL_MS

    /** 目录文本是否可用于负向过滤。 */
    fun catalogUsable(nowMs: Long): Boolean =
        catalogText.isNotEmpty() && catalogAtMs > 0L && nowMs - catalogAtMs < CATALOG_TTL_MS

    fun sessionTokenOrNull(): String? = sessionToken.takeIf { it.isNotBlank() }

    /** 记录一次目录拉取（HTTP 部分在 [LyricsFetcher] 里，这里只存解析结果）。 */
    fun putCatalog(text: String, token: String, nowMs: Long) {
        if (text.isNotEmpty()) catalogText = text
        if (token.isNotBlank()) sessionToken = token
        catalogAtMs = nowMs
    }

    /**
     * 会话过期（419）⇒ 丢掉令牌**只丢令牌**：目录文本视为仍新鲜（它的新鲜度按 TTL 管），
     * 免得为了换令牌把负向过滤也一起废掉。
     */
    fun invalidateSession() {
        sessionToken = ""
        sessionExpiredCount++
    }

    /** 距上次请求不足 [MIN_GAP_MS] 时该等多久（站方要求串行 + 间隔）。 */
    fun throttleWaitMs(nowMs: Long): Long {
        val gap = nowMs - lastRequestAtMs
        return if (lastRequestAtMs > 0L && gap < MIN_GAP_MS) MIN_GAP_MS - gap else 0L
    }

    fun markRequest(nowMs: Long) {
        lastRequestAtMs = nowMs
    }

    /** 测试/换 Key 用：清干净缓存。 */
    fun reset() {
        catalogText = ""
        sessionToken = ""
        catalogAtMs = 0L
        lastRequestAtMs = 0L
        sessionExpiredCount = 0
    }

    // ── 负向过滤（纯逻辑，可单测） ─────────────────────────────────────────

    /**
     * 归一化用的"匹配键"候选。**必须宽**：过滤判错方向是不对称的 ——
     * 误判"库里没有"会让一首歌**永远搜不到**（用户视角就是功能坏了），
     * 误判"库里有"只是多发一次搜索（他们建议避免、但可接受）。所以这里宁多给几个键。
     *
     * 站方的保证只对"**查询串原样**在快照里找不到 ⇒ 必然 0 条"成立；我们剥了括号/feat.
     * 之后就**不能**再倒过来要求"剥过的串找不到就一定没有" —— 所以本函数只用来**放行**，
     * 只要任一键命中就发请求；全不命中才跳过。
     */
    fun matchKeys(title: String, artist: String): List<String> {
        val keys = LinkedHashSet<String>()
        for (raw in listOf(title, artist).filter { it.isNotBlank() }) {
            val base = normKey(raw)
            if (base.isNotEmpty()) keys += base
            val noFeat = normKey(stripFeat(raw))
            if (noFeat.isNotEmpty()) keys += noFeat
        }
        // 歌名与歌手都剥过 feat. 后再拼一次：库里常把 "AAA（feat.XXX）" 记成
        // 歌名 AAA + 歌手含 XXX，逐字段比对与拼接比对都可能漏，两种都给。
        val t = normKey(stripFeat(title))
        val a = normKey(stripFeat(artist))
        if (t.isNotEmpty() && a.isNotEmpty()) keys += (t + a)
        return keys.toList()
    }

    /**
     * 本地负向过滤：目录文本里连一个匹配键都找不到 ⇒ 库里必然没有这歌，**不必发请求**。
     * 目录不可用（没拉过/过期）时返回 true（放行）——过滤只是省请求，不能变成准入闸门。
     */
    fun catalogMayContain(title: String, artist: String, nowMs: Long): Boolean {
        if (!catalogUsable(nowMs)) return true
        val hay = catalogText
        return matchKeys(title, artist).any { hay.contains(it) }
    }

    /** 归一化：剥括号补充说明 + 剥 feat. 尾巴 + 去标点空格 + 小写（与搜歌词侧同一套）。 */
    private fun normKey(s: String): String =
        com.shiyin.music.data.search.FuzzySearch.normalize(BRACKET.replace(stripFeat(s), ""))
            .filter { it.isLetterOrDigit() }

    /**
     * **不带括号**的演唱者补充说明：`AAA feat. XXX` / `AAA ft. XXX` / `AAA featuring XXX`。
     *
     * 只认 feat/ft/featuring 这三个词头 —— 不碰 `with`：`Dance with Me` 这类是歌名本身
     * 的一部分，剥了会把完全不同的一首歌认成同一首。
     */
    private fun stripFeat(s: String): String = FEAT.replace(s, " ").trim()

    private val BRACKET = Regex("""[(（\[【].*?[)）\]】]""")
    private val FEAT = Regex("""\s*[(（\[]?\s*\b(?:feat|ft|featuring)\b\.?\s.*$""", RegexOption.IGNORE_CASE)

    // ── 目录响应解析（手写取键：本工程禁止 Gson 反射绑定，只用它的树 API）──────────

    /**
     * 从 `/v1/catalog` 响应取 (text, session_token)。
     *
     * 文本**转小写**后存：站方文档说快照本来就是小写去重、`\n` 分隔的；这里再保险一次，
     * 因为我们的匹配键是小写的。
     *
     * ⚠️ 用 Gson 的树 API 而不是 `org.json`：本函数要能跑 **JVM 单测**，而 `org.json`
     * 在单元测试里只是 android.jar 的空壳（返回默认值），测不出真行为。
     * 全程字面键名、不碰反射，因此不受 release 包 R8 影响。
     */
    fun parseCatalog(body: String): Pair<String, String>? = runCatching {
        val root = com.google.gson.JsonParser.parseString(body)
        if (!root.isJsonObject) return@runCatching null
        val data = root.asJsonObject.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return@runCatching null
        val text = data.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase().orEmpty()
        val token = data.get("session_token")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (text.isEmpty() || token.isBlank()) null else text to token
    }.getOrNull()
}

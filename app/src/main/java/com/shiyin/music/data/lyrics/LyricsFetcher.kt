package com.shiyin.music.data.lyrics

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Online lyric matching, per handoff: query by title + artist (+album) + duration
 * with a ±2s duration tolerance. Sources rotate LRCLIB → 网易云音乐.
 */
object LyricsFetcher {
    // v1.3.10 需求 A30:加 LrcShare(api.lrcshare.com,无 key)——覆盖两个内置源搜不到的冷门歌。
    // 放在最后 = 兜底(不顶替前两个,只补它们搜不到的)。
    /** **内置**源(静态清单)。用户开关/自定义源见 [activeSources]。 */
    val SOURCES = listOf("LRCLIB", "网易云音乐", "LrcShare")

    /**
     * v1.3.10 需求 A29:当前**实际可用**的源 = 内置(去掉用户关掉的) + 用户自定义源。
     * 取歌词链路(自动匹配级联 / 来源菜单)一律用这个,不再直接用 [SOURCES]。
     */
    fun activeSources(): List<String> =
        SOURCES.filterNot { it in LyricsSourceConfig.disabledBuiltins } +
            LyricsSourceConfig.customSources.map { it.name }

    /** 该名字对应的自定义源定义(内置源/未知名字 → null)。 */
    private fun customSource(name: String): CustomLyricsSourceDef? =
        LyricsSourceConfig.customSources.firstOrNull { it.name == name }
    private const val UA = "Shiyin/1.0 (Android local music player)"
    // v1.3.x 2s→5s:±2s 把"搜到正确歌曲"误判为无结果(用户实测网易云明明有歌词却报
    // 无——本地 tag/VBR 头时长与官方曲库录制时长差 3-5s 是常态:intro 差异、首段静音、
    // ms→s 截断舍入再吃 ±1s)。放宽后:搜到正确歌曲不再被时长门否决。5s 仍显著低于
    // live/广播剪辑/混音版与原版的典型时长差(≥8s),不会误配到错误版本。
    private const val DURATION_TOLERANCE_SEC = 5

    /** 括号补充说明(「晴天 (Live)」「歌名【remix】」)——归一时剥掉,只编译一次。 */
    private val BRACKET_SUFFIX = Regex("[(（\\[【].*?[)）\\]】]")

    /** Returns raw lyric text (LRC or plain) or null when no match. */
    suspend fun fetch(source: String, title: String, artist: String, album: String, durationSec: Long): String? =
        withContext(Dispatchers.IO) {
            try {
                when (source) {
                    "LRCLIB" -> fetchLrclib(title, artist, album, durationSec)
                    "网易云音乐" -> fetchNetease(title, artist, durationSec)
                    "LrcShare" -> fetchLrcShare(title, artist, album, durationSec)
                    // v1.3.10 需求 A29:用户自定义源(URL 模板 + JSON 路径)
                    else -> customSource(source)?.let { fetchCustom(it, title, artist, album) }
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun http(
        url: String,
        referer: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
    ): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", UA)
            if (referer != null) conn.setRequestProperty("Referer", referer)
            // v1.3.10 需求 A29:自定义源的附加请求头(如鉴权)——用户填的,原样带上
            for ((k, v) in headers) runCatching { conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * v1.3.10 需求 A29:自定义源取歌词(两阶段,与内置源同构):
     *  ① 搜索 URL 模板(`{title}`/`{artist}`/`{album}` 占位)→ JSON → [CustomLyricsSourceDef.idJsonPath] 取 id
     *  ② 歌词 URL 模板(`{id}`/`{title}`/`{artist}` 占位)→ JSON → [CustomLyricsSourceDef.lyricJsonPath] 取歌词
     *
     * 任何一步失败都返回 null(掉给下一个源)。日志**只记哪一步失败**,不打整段响应
     * (用户自定义接口可能返回隐私内容,不进日志)。
     */
    private fun fetchCustom(
        def: CustomLyricsSourceDef, title: String, artist: String, album: String,
    ): String? {
        val hdrs = CustomLyricsSourceCodec.parseHeaders(def.headers)
        val vars = mapOf("title" to title, "artist" to artist, "album" to album)
        val searchUrl = CustomLyricsSourceCodec.fillTemplate(def.searchUrl, vars)
        val searchBody = http(searchUrl, referer = null, headers = hdrs)
        if (searchBody == null) {
            android.util.Log.i("Lyrics", "自定义源 ${def.name}: 搜索请求失败")
            return null
        }
        val id = runCatching {
            CustomLyricsSourceCodec.resolvePath(JsonParser.parseString(searchBody), def.idJsonPath)
        }.getOrNull()
        if (id.isNullOrBlank()) {
            android.util.Log.i("Lyrics", "自定义源 ${def.name}: idJsonPath「${def.idJsonPath}」没取到 id")
            return null
        }
        val lyricUrl = CustomLyricsSourceCodec.fillTemplate(def.lyricUrl, vars + ("id" to id))
        val lyricBody = http(lyricUrl, referer = null, headers = hdrs)
        if (lyricBody == null) {
            android.util.Log.i("Lyrics", "自定义源 ${def.name}: 歌词请求失败")
            return null
        }
        val text = runCatching {
            CustomLyricsSourceCodec.resolvePath(JsonParser.parseString(lyricBody), def.lyricJsonPath)
        }.getOrNull()
        if (text.isNullOrBlank()) {
            android.util.Log.i("Lyrics", "自定义源 ${def.name}: lyricJsonPath「${def.lyricJsonPath}」没取到歌词")
            return null
        }
        android.util.Log.i("Lyrics", "自定义源 ${def.name}: 取到 ${text.length} 字")
        return text
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun pickLyric(o: JSONObject): String? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return synced ?: plain
    }

    private fun fetchLrclib(title: String, artist: String, album: String, durationSec: Long): String? {
        // Exact match endpoint first.
        val albumParam = if (album.isNotBlank() && album != "—") "&album_name=${enc(album)}" else ""
        http(
            "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
                albumParam + "&duration=$durationSec"
        )?.let { body ->
            pickLyric(JSONObject(body))?.let { return it }
        }
        // Search fallback with duration tolerance.
        searchLrclib(title, artist, durationSec, requireArtistMatch = false)?.let { return it }
        // 2026-10-22（用户报「new note 识别不到歌词」）：LRCLIB **带 artist 过滤**时
        // 简繁对不上就 0 条（库里歌手是繁体「陳嫺靜」，本地 tag 是简体「陈娴静」——
        // 实测同一首歌带过滤 0 条、去掉过滤 7 条且首条 duration 与本地完全一致）。
        // 兜底：去掉 artist 过滤再搜一次，本地用 FuzzySearch.normalize（繁→简）
        // 归一后比对歌手，时长仍按 ±DURATION_TOLERANCE_SEC 把关。
        return searchLrclib(title, artist, durationSec, requireArtistMatch = true, dropArtistFilter = true)
    }

    /**
     * LRCLIB 搜索 + 打分。
     * @param requireArtistMatch 结果侧歌手是否必须命中（本地归一后双向包含）。
     *   [dropArtistFilter]=true 时必开 —— 服务端不再帮我们过滤，必须本地把关，
     *   否则同名异曲会被选中。
     * @param dropArtistFilter 请求里**不带** artist_name。
     */
    private fun searchLrclib(
        title: String,
        artist: String,
        durationSec: Long,
        requireArtistMatch: Boolean,
        dropArtistFilter: Boolean = false,
    ): String? {
        val url = if (dropArtistFilter)
            "https://lrclib.net/api/search?track_name=${enc(title)}"
        else
            "https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}"
        val body = http(url) ?: return null
        val arr = JSONArray(body)
        val artistKey = if (requireArtistMatch)
            com.shiyin.music.data.search.FuzzySearch.normalize(artist) else ""
        var best: String? = null
        var bestScore = Long.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lyric = pickLyric(o) ?: continue
            if (requireArtistMatch && artistKey.isNotBlank()) {
                // FuzzySearch.normalize 做全角→半角 + 繁→简 + 去空格 + 小写
                val retArtist = com.shiyin.music.data.search.FuzzySearch
                    .normalize(o.optString("artistName"))
                val hit = retArtist.isNotBlank() &&
                    (retArtist.contains(artistKey) || artistKey.contains(retArtist))
                if (!hit) continue
            }
            val d = o.optLong("duration", -1)
            val diff = if (d >= 0 && durationSec > 0) abs(d - durationSec) else Long.MAX_VALUE / 2
            val syncedBonus = if (o.optString("syncedLyrics").isNotBlank() && o.optString("syncedLyrics") != "null") 0 else 1000
            val score = diff + syncedBonus
            if (diff <= DURATION_TOLERANCE_SEC && score < bestScore) {
                bestScore = score
                best = lyric
            }
        }
        if (best == null && arr.length() > 0) {
            // No duration-close hit: accept the top result only if we had no duration info.
            if (durationSec <= 0 && !requireArtistMatch) best = pickLyric(arr.getJSONObject(0))
        }
        return best
    }

    private fun fetchNetease(title: String, artist: String, durationSec: Long): String? {
        val q = enc("$title $artist".trim())
        android.util.Log.i("Lyrics", "netease 查询: title=$title artist=$artist dur=${durationSec}s")
        // v1.3.9: 网易云会**限流**(实测返回 {"msg":"操作频繁，请稍候再试","code":405}),
        // 也可能因网络抖动返回空数组。原实现把这两种情况都当成"没有这首歌"直接 return null
        // → 用户看到"无歌词",而歌其实在网易云有词(中文歌主要靠这个源,LRCLIB 覆盖不到)。
        // 现在:识别错误/空返回 → 退避重试(0.4s / 1.2s),仍失败才放弃。
        var songs: JSONArray? = null
        for (attempt in 0..2) {
            val body = http(
                "https://music.163.com/api/search/get/web?s=$q&type=1&limit=10&offset=0",
                referer = "https://music.163.com",
            )
            val root = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            val arr = root?.optJSONObject("result")?.optJSONArray("songs")
            android.util.Log.i(
                "Lyrics",
                "netease 第${attempt + 1}次: bodyLen=${body?.length ?: -1} songs=${arr?.length() ?: -1} " +
                    "code=${root?.optInt("code", 0) ?: 0} msg=${root?.optString("msg")?.take(40) ?: "-"}",
            )
            if (arr != null && arr.length() > 0) { songs = arr; break }
            if (attempt < 2) Thread.sleep(if (attempt == 0) 400L else 1200L)
        }
        val songList = songs ?: run {
            android.util.Log.w("Lyrics", "netease 无候选(全部尝试失败)")
            return null
        }
        // v1.3.x 鉴权(用户反馈⑧:阴三儿《爽歪歪》vs 大张伟《爽歪歪》同名不同歌,
        // 旧实现只按时长差挑 bestId——同名不同歌手的版本时长恰好更接近时就配错)。
        // v1.3.9:候选构造与选择抽成 internal 纯函数,单测用**真实抓包数据**覆盖
        // (见 LyricsMatchTest)——此前这段逻辑只能靠线上复现,回归一次修一次。
        val cands = (0 until songList.length()).mapNotNull { i ->
            val s = songList.getJSONObject(i)
            val names = (s.optJSONArray("artists") ?: return@mapNotNull null).let { arr ->
                (0 until arr.length()).mapNotNull { k ->
                    arr.getJSONObject(k).optString("name").takeIf { it.isNotBlank() }
                }
            }
            val retTitle = s.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            candidateOf(
                id = s.optLong("id", -1), retNames = names, retTitle = retTitle,
                retDurSec = s.optLong("duration", -1) / 1000,
                artist = artist, title = title, durationSec = durationSec,
            )
        }
        val picked = selectNeteaseCandidate(cands, artist, durationSec) ?: run {
            android.util.Log.w(
                "Lyrics",
                "netease 候选全被拒: ${cands.joinToString("; ") { "id=${it.id} ah=${it.artistHit} th=${it.titleHit} ex=${it.exactTitleHit} sim=${it.artistSimilar} diff=${it.diff}" }}",
            )
            return null
        }
        android.util.Log.i("Lyrics", "netease 选中 id=${picked.id} diff=${picked.diff}")
        // v1.3.x yrc:部分新歌歌词只有逐字格式(yrc 字段),lrc 为空串——
        // 客户端展示的滚动歌词来自 yrc,只读 lrc 会把"明明有歌词"误报为无结果
        // (实测:《虎头奔》揽佬SKAI ISYOURGOD id=2080172427)。yv=1/yrv=1 请求 yrc,
        // lrc 空时解析 yrc 转标准 LRC。
        // v1.3.9: lv 参数改 -1。PC 端差分测试锁定:同一首歌、同样参数,仅 lv 不同——
        // lv=1 时 lrc="" 且无 yrc 字段(实测《开波》id=2130715020,以及《虎头奔》),
        // lv=-1 时完整歌词;UA 换 Mozilla/5.0 不影响(排除 UA 嫌疑)。
        // 即部分歌曲 lv=1 连 yrc 兜底都拿不到,这类歌此前彻底漏报"无歌词"。
        val lyricBody = http(
            "https://music.163.com/api/song/lyric?id=${picked.id}&lv=-1&kv=-1&tv=-1&yv=1&yrv=1",
            referer = "https://music.163.com",
        ) ?: return null
        val obj = JSONObject(lyricBody)
        val lrc = obj.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
        android.util.Log.i("Lyrics", "netease 歌词接口: lrc=${lrc?.length ?: 0}字 有无yrc=${obj.optJSONObject("yrc")?.optString("lyric")?.isNotBlank() == true}")
        if (lrc != null) return lrc
        val yrc = obj.optJSONObject("yrc")?.optString("lyric")?.takeIf { it.isNotBlank() } ?: return null
        return yrcToLrc(yrc) ?: return null
    }

    /**
     * v1.3.10 需求 A30:LrcShare(api.lrcshare.com,无 key、GET、JSON)。
     *
     * 两阶段(官方文档明确"不要在搜索阶段取歌词"):
     *  ① GET /v1/search?type=song&title=…&artist=…(结构化,不把 title+artist 拼成 keyword)
     *  ② 候选按 title/artist 归一化命中 + album 一致打分,阈值拦"同名异曲"(不盲目取第一条)
     *  ③ GET /v1/lyric/{id}?lyric_format=line 取 LRC → [LrcShareAttribution.clean] 去署名。
     *
     * 失败(网络/无候选/无歌词)一律 null——丢给下一个源,不把异常或半截结果透出去。
     */
    private fun fetchLrcShare(title: String, artist: String, album: String, durationSec: Long): String? {
        android.util.Log.i("Lyrics", "lrcshare 查询: title=$title artist=$artist dur=${durationSec}s")
        val q = buildString {
            append("https://api.lrcshare.com/v1/search?type=song")
            append("&title=${enc(title)}")
            // LrcShare 支持重复 artist 参数(AND)。只按明确的合作分隔符拆——
            // 不拆 "/":AC/DC 这类乐队名不能误拆(规格 V)。
            for (a in artist.split(Regex("""\s*[,,&、]\s*""")).map { it.trim() }.filter { it.isNotBlank() }
                .ifEmpty { listOf(artist.trim()) }
            ) append("&artist=${enc(a)}")
        }
        val body = http(q, referer = null) ?: return null
        val items = runCatching { JSONObject(body).optJSONObject("data")?.optJSONArray("items") }.getOrNull()
        android.util.Log.i("Lyrics", "lrcshare 搜索: 候选 ${items?.length() ?: 0}")
        if (items == null || items.length() == 0) return null
        val picked = pickLrcShareCandidate(items, title, artist, album) ?: return null
        val id = picked.optString("id").takeIf { it.isNotBlank() } ?: return null
        android.util.Log.i("Lyrics", "lrcshare 选中 id=$id")
        val lyricBody = http("https://api.lrcshare.com/v1/lyric/$id?lyric_format=line", referer = null) ?: return null
        val lrc = runCatching { JSONObject(lyricBody).optJSONObject("data")?.optString("lrc") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        android.util.Log.i("Lyrics", "lrcshare 歌词接口: ${lrc.length}字")
        return LrcShareAttribution.clean(lrc, title, artist).takeIf { it.isNotBlank() }
    }

    /**
     * LrcShare 候选评分。不盲目取第一条(规格 VI):
     *  - title:完全相等 +50,归一化后互相包含 +40,都不满足 → 这一条直接淘汰;
     *  - artist:完全相等 +40,归一化后互相包含 +30,全不中 +0;
     *  - album 一致 +15(加分项,不强制——很多冷门曲没有专辑名)。
     * 最低可信阈值 = title 命中 + artist 命中(40+30)。缺 artist 信息的候选
     * (score 只来自 title)到不了阈值 → 同名异曲不会被误配。
     */
    private fun pickLrcShareCandidate(items: JSONArray, title: String, artist: String, album: String): JSONObject? {
        val tN = normText(title)
        val aK = normArtist(artist)
        val alN = normText(album)
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val rt = normText(it.optString("title"))
            if (rt.isBlank()) continue
            var score = 0
            if (rt == tN) score += 50 else if (rt.contains(tN) || tN.contains(rt)) score += 40 else continue
            val rn = it.optJSONArray("artists")?.let { arr ->
                (0 until arr.length())
                    .mapNotNull { k -> arr.optJSONObject(k)?.optString("name") }
                    .joinToString(" ")
            } ?: ""
            val rK = normArtist(rn)
            score += when {
                rK.isBlank() -> 0
                rK == aK -> 40
                rK.contains(aK) || aK.contains(rK) -> 30
                else -> 0
            }
            val al = normText(it.optJSONObject("album")?.optString("name").orEmpty())
            if (alN.isNotBlank() && al.isNotBlank() && (al == alN || al.contains(alN) || alN.contains(al))) score += 15
            if (score > bestScore) { bestScore = score; best = it }
        }
        return best?.takeIf { bestScore >= 70 }
    }

    /**
     * v1.3.9: 网易云候选——构造与选择抽成 internal 纯函数,便于用**真实抓包数据**单测
     * (这段逻辑此前只能靠线上复现,修一次回归一次)。
     *
     * @param diff 时长差(未知时长时为 MAX_VALUE/2,不参与"贴近"判定)
     * @param artistHit 歌手名命中(去标点后双向包含,任一返回歌手名成立即可)
     * @param titleHit 歌名弱命中(取前 6 字双向包含)
     * @param exactTitleHit 歌名归一后**完全相等**
     * @param artistSimilar 与任一返回歌手名有 ≥3 字符公共前缀(受控兜底的安全闸)
     */
    internal data class LyricCandidate(
        val id: Long,
        val diff: Long,
        val artistHit: Boolean,
        val titleHit: Boolean,
        val exactTitleHit: Boolean,
        val artistSimilar: Boolean,
    )

    /** 用一条搜索结果的字段构造候选。 */
    internal fun candidateOf(
        id: Long,
        retNames: List<String>,
        retTitle: String,
        retDurSec: Long,
        artist: String,
        title: String,
        durationSec: Long,
    ): LyricCandidate {
        val artistNorm = normText(artist)
        val artistKey = normArtist(artist)
        val retNameNorm = retNames.joinToString(" ") { normText(it) }
        // v1.3.9: 歌手比较**去标点**后再双向包含——原实现保留 '/'、'-' 等符号,
        // 「PlayerJ/步步高先生」这类多歌手串与返回的「Player J / 步步高先生」
        // 拼串对不上;更致命的变体是「play J」vs「Player J」:归一化后
        // playj / playerj 双向都不包含 → 歌手闸门拒光候选 → 明明有歌词却报无。
        val artistHit = (artistKey.isNotBlank() && retNames.any { n ->
            val k = normArtist(n)
            k.isNotBlank() && (k.contains(artistKey) || artistKey.contains(k))
        }) || (artistNorm.isNotBlank() &&
            (retNameNorm.contains(artistNorm) || artistNorm.contains(retNameNorm)))
        val retTitleNorm = normText(retTitle)
        val titleNorm = normText(title)
        val diff = if (durationSec > 0 && retDurSec > 0) abs(retDurSec - durationSec) else Long.MAX_VALUE / 2
        return LyricCandidate(
            id = id,
            diff = diff,
            artistHit = artistHit,
            titleHit = retTitleNorm.contains(titleNorm.take(6)) || titleNorm.contains(retTitleNorm.take(6)),
            exactTitleHit = retTitleNorm == titleNorm,
            artistSimilar = artistKey.isNotBlank() && retNames.any { n ->
                commonPrefixLen(normArtist(n), artistKey) >= 3
            },
        )
    }

    /**
     * 选出最终候选(含时长闸门)。优先级:
     *  1. 歌手+歌名都命中 → 取时长最接近;
     *  2. 仅歌手命中 → 时长最接近;
     *  3. 本地**无歌手信息**时 → 仅歌名命中 → 时长最接近(兜底,不能空手);
     *  4. **受控兜底**(v1.3.9 新增):歌手名是变体写法(play J / Player J、aka 名、
     *     中英文名混用)时前三档全落空 → 归一化歌名**完全相等**、时长差 ≤5s、
     *     且与返回歌手名有公共前缀的候选**有且仅有一个**时接受。
     *     唯一性 + 公共前缀共同挡住"同名不同歌"(阴三儿《爽歪歪》vs 大张伟《爽歪歪》)。
     * 最后:本地有时长且选中候选时长差 >10s → 拒绝(live/混音版风险)。
     */
    internal fun selectNeteaseCandidate(
        cands: List<LyricCandidate>,
        artist: String,
        durationSec: Long,
    ): LyricCandidate? {
        val artistNorm = normText(artist)
        val picked = cands.filter { it.artistHit && it.titleHit }.minByOrNull { it.diff }
            ?: cands.filter { it.artistHit }.minByOrNull { it.diff }
            ?: if (artistNorm.isBlank()) cands.filter { it.titleHit }.minByOrNull { it.diff } else null
            ?: cands.filter { it.exactTitleHit && it.diff <= 5 && it.artistSimilar }.let { exact ->
                if (exact.size == 1) exact.first() else null
            }
        if (picked == null || picked.id < 0) return null
        if (durationSec > 0 && picked.diff > 10 && picked.diff != Long.MAX_VALUE / 2) return null
        return picked
    }

    /**
     * 归一化:剥括号补充说明(「晴天 (Live)」→「晴天」),再交给 FuzzySearch.normalize
     * 做**全角→半角 + 繁体→简体 + 去空格 + 小写**。
     *
     * ⚠️ 必须复用 FuzzySearch 而不是自己 lowercase():歌手/歌名里「周杰倫 vs 周杰伦」
     * 「Jay Chou vs 周杰倫」这类差异只靠小写消不掉,而匹配的 artistHit 是硬闸门——
     * 归一旦对不上,整首歌词就永远搜不到(实测回归)。
     * 正则提成 object 级常量:原实现每次调用都 Pattern.compile 两遍,
     * 20 条候选 × 多个字段 ≈ 每搜一次 200+ 次编译。
     */
    private fun normText(s: String): String =
        com.shiyin.music.data.search.FuzzySearch.normalize(BRACKET_SUFFIX.replace(s, ""))

    /**
     * v1.3.9: 歌手名的**去标点**归一——在 [normText] 基础上再剥掉所有非字母数字字符
     * (保留 CJK,`isLetterOrDigit` 对中文为 true)。
     * 动机:多歌手串与变体写法里,分隔符差异(「PlayerJ/步步高先生」vs「Player J, 步步高先生」)
     * 会让双向包含判定失败 → 歌手闸门拒光候选 → 有歌词却报无。
     */
    private fun normArtist(s: String): String =
        normText(s).filter { it.isLetterOrDigit() }

    /** 两串的公共前缀长度(受控兜底的"歌手相似"判据)。 */
    private fun commonPrefixLen(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /**
     * v1.3.x: 网易云逐字歌词(yrc)转标准 LRC。
     * yrc 行格式: [行起始ms,行持续ms](字起始ms,字持续ms,0)字(字起始ms,字持续ms,0)字…
     * 行头 [start,dur] 是行时间轴,后续每个 (start,dur,0) 后跟该字文本(可含空格)。
     * 转换: 取行起始 ms 作为 LRC 时间戳,把行内所有 (…) 标记剥掉、字文本顺序拼接。
     */
    private fun yrcToLrc(yrc: String): String? {
        val out = StringBuilder()
        val lineTag = Regex("""^\[(\d+),(\d+)\]""")
        val wordTag = Regex("""\((\d+),(\d+),\d+\)""")
        var anyLine = false
        for (line in yrc.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val m = lineTag.find(trimmed) ?: continue
            val startMs = m.groupValues[1].toLong()
            // 剥行头后按 (start,dur,0) 标记切分,标记间的文本就是字内容
            val rest = trimmed.substring(m.range.last + 1)
            val text = wordTag.replace(rest, "").trim()
            if (text.isEmpty()) continue
            val min = startMs / 60_000
            val sec = (startMs % 60_000) / 1000
            val cs = (startMs % 1000) / 10
            out.append('[').append(min).append(':')
                .append(if (sec < 10) "0$sec" else "$sec").append('.')
                .append(if (cs < 10) "0$cs" else "$cs").append(']').append(text).append('\n')
            anyLine = true
        }
        return if (anyLine) out.toString() else null
    }
}

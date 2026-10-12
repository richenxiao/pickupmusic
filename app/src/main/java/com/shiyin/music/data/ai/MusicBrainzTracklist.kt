package com.shiyin.music.data.ai

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v1.5.0：**MusicBrainz 官方曲序**（结构化接口，不抓页面、不靠模型猜）。
 *
 * 为什么加这条路（2026-10-12 实测《LSC》/ラブリーサマーちゃん）：
 *  - 10-02 的改动把曲序改成"优先豆瓣（查询写死为「专辑 歌手 豆瓣音乐」）"，而那个查询是
 *    按**中文专辑**调优的 → 日文/拉丁名专辑搜不到豆瓣页 → 退到无域名限定的通用搜索
 *    → 抓到无关页面（官方站新闻页挂着另一张专辑的曲目）→ 模型照错资料排 → **排错还报成功**。
 *  - MusicBrainz 是**曲目数据库**：按名字+歌手查发行版即可拿到有序曲目表，日文专辑同样覆盖
 *    （实测《LSC》12 首通常盘与用户本地完全一致，含 U+2010 连字符这类细节）。
 *
 * 只做两件事：查发行版 → 取曲序。所有解析都是纯函数（可单测）。
 */
object MusicBrainzTracklist {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** MusicBrainz 要求带可识别的 User-Agent，否则会被限流。 */
    private const val UA = "PickUpMusic/1.5.0 (https://github.com/richenxiao/PickUpMusic)"

    data class Release(val id: String, val title: String, val artist: String, val trackCount: Int, val date: String)

    /** 一次查询的结果：官方曲目名（按碟/轨序）。 */
    data class Hit(val release: Release, val titles: List<String>)

    /**
     * 按 专辑名 + 歌手 查发行版并取曲序。
     * @param expectTracks 本地曲目数——用来在多版本（通常盘/初回盘）里挑最接近的那个
     */
    suspend fun fetch(album: String, artist: String, expectTracks: Int = 0): Hit? = runCatching {
        val q = buildString {
            append("release:\"").append(escape(album)).append('"')
            if (artist.isNotBlank()) append(" AND artist:\"").append(escape(artist)).append('"')
        }
        val searchUrl = "https://musicbrainz.org/ws/2/release?query=" +
            java.net.URLEncoder.encode(q, "UTF-8") + "&fmt=json&limit=8"
        val releases = parseSearch(get(searchUrl))
        val best = pickBest(releases, album, artist, expectTracks) ?: return null
        val lookupUrl = "https://musicbrainz.org/ws/2/release/${best.id}?inc=recordings&fmt=json"
        val titles = parseTracklist(get(lookupUrl))
        if (titles.isEmpty()) null else Hit(best, titles)
    }.getOrNull()

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun escape(s: String) = s.replace("\"", "").replace("\\", "")

    /** 纯函数：解析搜索响应。 */
    internal fun parseSearch(json: String?): List<Release> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JsonParser.parseString(json).asJsonObject.getAsJsonArray("releases") ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el.asJsonObject
                val id = o.get("id")?.asString ?: return@mapNotNull null
                val title = o.get("title")?.asString ?: ""
                val artist = o.getAsJsonArray("artist-credit")
                    ?.firstOrNull()?.asJsonObject?.get("name")?.asString ?: ""
                Release(
                    id = id, title = title, artist = artist,
                    trackCount = o.get("track-count")?.asInt ?: 0,
                    date = o.get("date")?.asString ?: "",
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 纯函数：在多版本里挑一个。
     * 规则（按重要性）：专辑名归一化后一致 > 歌手出现 > 曲目数最接近本地。
     * 「曲目数最接近」很关键：同一张专辑常有通常盘(12)/初回盘(21)，本地 12 首时必须挑通常盘，
     * 否则会拿初回盘的顺序去排本地（多出的附赠曲会把顺序整体带偏）。
     */
    internal fun pickBest(releases: List<Release>, album: String, artist: String, expectTracks: Int): Release? {
        if (releases.isEmpty()) return null
        val a = canon(album)
        val ar = canon(artist)
        fun titleScore(r: Release): Int {
            val t = canon(r.title)
            return when {
                t == a -> 3
                t.contains(a) || a.contains(t) -> 2
                else -> 0
            }
        }
        fun artistScore(r: Release): Int = if (ar.isNotEmpty() && canon(r.artist).contains(ar)) 1 else 0
        return releases
            .filter { titleScore(it) > 0 && it.trackCount > 0 }
            .maxWithOrNull(
                compareBy<Release>(
                    { titleScore(it) },
                    { artistScore(it) },
                    // 越接近本地曲目数越好（差的绝对值取负，越大越优）
                    { if (expectTracks > 0) -kotlin.math.abs(it.trackCount - expectTracks) else 0 },
                ),
            )
            ?: releases.firstOrNull { it.trackCount > 0 }
    }

    /** 纯函数：解析 release + recordings 的有序曲目名。 */
    internal fun parseTracklist(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val media = JsonParser.parseString(json).asJsonObject.getAsJsonArray("media") ?: return emptyList()
            val rows = ArrayList<Pair<Pair<Int, Int>, String>>()
            for (mEl in media) {
                val tracks = mEl.asJsonObject.getAsJsonArray("tracks") ?: continue
                for (tEl in tracks) {
                    val o = tEl.asJsonObject
                    // 碟号 + 轨号一起排，多碟专辑不会串
                    val disc = o.get("number")?.asString?.toIntOrNull() ?: 1
                    val pos = o.get("position")?.asInt ?: 0
                    rows += (disc to pos) to (o.get("title")?.asString ?: "")
                }
            }
            rows.sortWith(
                compareBy<Pair<Pair<Int, Int>, String>> { it.first.first }.thenBy { it.first.second },
            )
            rows.map { it.second }
        }.getOrDefault(emptyList())
    }

    /** 归一化：与曲名匹配同一套注意事项（连字符变体/全角空格/大小写）。 */
    private fun canon(s: String): String = s.lowercase()
        .map { ch ->
            when (ch.code) {
                0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212, 0xFF0D -> '-'  // 各种连字符/减号
                0x3000 -> ' '                                                          // 全角空格
                else -> ch
            }
        }
        .joinToString("")
        .replace(Regex("\\s+"), "")
}

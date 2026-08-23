package com.shiyin.music.data.image

import com.google.gson.JsonParser
import com.shiyin.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v1.2.0 #6: 歌手写真自动源（无状态、不依赖 DAO，便于纯 JVM 单测）。
 * 各源单独失败不影响其他；首个命中即停。可达性优先级见 [ArtistImageResolver]。
 *
 * 可达性实测（2026-08，本机网络）：
 *  - Discogs   ✅ api + 图片 CDN 均 200，无需 key，人物照。
 *  - Last.fm   ✅ 端点可达(403 bad key 实测)，需 API key 才返回图。
 *  - MB         ✅ 可达(仅 MBID)；Wikidata ❌ 8s 超时(被墙)。
 *  - Deezer     ❌ api.deezer.com 不可达(被墙)。
 *  - iTunes     ✅ 可达，专辑封面(非人物)。
 */
internal object ArtistImageSources {

    private const val UA = "ShiyinMusic/2.0 (music-player; android)"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 给已知被墙的源(Wikidata/Deezer)短超时，快失败落到下一源。 */
    private val fastClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 按 [ArtistImageResolver] 的优先级依次尝试各源，首个命中即停。 */
    suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? {
        tryDiscogs(name)?.let { return it }
        tryLastfm(name)?.let { return it }
        tryMusicBrainzWikidata(name)?.let { return it }
        tryDeezer(name)?.let { return it }
        if (!personOnly) tryItunes(name)?.let { return it }
        return null
    }

    // Discogs: search → /artists/{id} → images[].primary.resource_url。无需 key。
    private suspend fun tryDiscogs(name: String): ArtistImage? = withContext(Dispatchers.IO) {
        try {
            val q = urlEncode(name)
            val sBody = client.newCall(Request.Builder()
                .url("https://api.discogs.com/database/search?q=$q&type=artist&per_page=5")
                .header("User-Agent", UA).build()).execute().body?.string() ?: return@withContext null
            val results = JsonParser.parseString(sBody).asJsonObject.getAsJsonArray("results") ?: return@withContext null
            val id = results.firstOrNull { el ->
                (el.asJsonObject.get("title")?.asString ?: "").equals(name, ignoreCase = true)
            }?.asJsonObject?.get("id")?.asString
                ?: results.firstOrNull()?.asJsonObject?.get("id")?.asString
                ?: return@withContext null
            val aBody = client.newCall(Request.Builder()
                .url("https://api.discogs.com/artists/$id")
                .header("User-Agent", UA).build()).execute().body?.string() ?: return@withContext null
            val imgs = JsonParser.parseString(aBody).asJsonObject.getAsJsonArray("images") ?: return@withContext null
            val pick = imgs.firstOrNull { (it.asJsonObject.get("type")?.asString ?: "") == "primary" }
                ?: imgs.firstOrNull()
            val url = pick?.asJsonObject?.get("resource_url")?.asString
            if (url.isNullOrBlank()) null else ArtistImage(url, "discogs")
        } catch (_: Exception) { null }
    }

    // Last.fm: artist.getinfo → image[extralarge/mega]。需 BuildConfig.LASTFM_API_KEY。
    private suspend fun tryLastfm(name: String): ArtistImage? = withContext(Dispatchers.IO) {
        val key = BuildConfig.LASTFM_API_KEY
        if (key.isBlank()) return@withContext null
        try {
            val q = urlEncode(name)
            val url = "http://ws.audioscrobbler.com/2.0/?method=artist.getinfo&artist=$q&api_key=$key&format=json"
            val body = client.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
                .execute().body?.string() ?: return@withContext null
            val artist = JsonParser.parseString(body).asJsonObject.getAsJsonObject("artist") ?: return@withContext null
            val imgs = artist.getAsJsonArray("image") ?: return@withContext null
            val pick = imgs.lastOrNull { it.asJsonObject.get("size")?.asString in listOf("extralarge", "mega") }
                ?: imgs.lastOrNull()
            val u = pick?.asJsonObject?.get("#text")?.asString
            if (u.isNullOrBlank()) null else ArtistImage(u, "lastfm")
        } catch (_: Exception) { null }
    }

    // MusicBrainz → Wikidata P18。MB 可达；wikidata.org 本机被墙，用 fastClient 快失败。
    private suspend fun tryMusicBrainzWikidata(name: String): ArtistImage? = withContext(Dispatchers.IO) {
        try {
            val q = urlEncode(name)
            val sBody = client.newCall(Request.Builder()
                .url("https://musicbrainz.org/ws/2/artist/?query=artist:$q&fmt=json&limit=3")
                .header("User-Agent", UA).header("Accept", "application/json").build()
            ).execute().body?.string() ?: return@withContext null
            val artists = JsonParser.parseString(sBody).asJsonObject.getAsJsonArray("artists") ?: return@withContext null
            val best = artists.firstOrNull { (it.asJsonObject.get("name")?.asString ?: "").equals(name, ignoreCase = true) }
                ?: artists.firstOrNull() ?: return@withContext null
            val mbid = best.asJsonObject.get("id")?.asString ?: return@withContext null
            val relBody = client.newCall(Request.Builder()
                .url("https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json")
                .header("User-Agent", UA).build()).execute().body?.string() ?: return@withContext null
            val relations = JsonParser.parseString(relBody).asJsonObject.getAsJsonArray("relations") ?: return@withContext null
            val wdUrl = relations.firstOrNull { r ->
                val t = r.asJsonObject.get("type")?.asString ?: ""
                val u = r.asJsonObject.get("url")?.asJsonObject?.get("resource")?.asString ?: ""
                t == "wikidata" && u.isNotBlank()
            }?.asJsonObject?.get("url")?.asJsonObject?.get("resource")?.asString ?: return@withContext null
            val qid = wdUrl.substringAfterLast("/").substringBefore("#")
            if (!qid.startsWith("Q")) return@withContext null
            val wdBody = fastClient.newCall(Request.Builder()
                .url("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qid&format=json&props=claims")
                .header("User-Agent", UA).header("Accept", "application/json").build()
            ).execute().body?.string() ?: return@withContext null
            val entity = JsonParser.parseString(wdBody).asJsonObject.getAsJsonObject("entities")?.getAsJsonObject(qid)
                ?: return@withContext null
            val p18 = entity.getAsJsonObject("claims")?.getAsJsonArray("P18")
            if (p18 == null || p18.size() == 0) return@withContext null
            val fn = p18[0].asJsonObject.getAsJsonObject("mainsnak")?.getAsJsonObject("datavalue")
                ?.get("value")?.asString ?: return@withContext null
            val safe = fn.replace(" ", "_")
            ArtistImage("https://commons.wikimedia.org/wiki/Special:FilePath/$safe", "wikidata")
        } catch (_: Exception) { null }
    }

    // Deezer: search → /artist/{id} → picture_xl。本机被墙，fastClient 快失败。
    private suspend fun tryDeezer(name: String): ArtistImage? = withContext(Dispatchers.IO) {
        try {
            val q = urlEncode("artist:\"$name\"")
            val sBody = fastClient.newCall(Request.Builder()
                .url("https://api.deezer.com/search?q=$q&limit=1")
                .header("User-Agent", UA).build()).execute().body?.string() ?: return@withContext null
            val id = JsonParser.parseString(sBody).asJsonObject.getAsJsonArray("data")
                ?.firstOrNull()?.asJsonObject?.getAsJsonObject("artist")?.get("id")?.asString
                ?: return@withContext null
            val aBody = fastClient.newCall(Request.Builder()
                .url("https://api.deezer.com/artist/$id")
                .header("User-Agent", UA).build()).execute().body?.string() ?: return@withContext null
            val pic = JsonParser.parseString(aBody).asJsonObject.get("picture_xl")?.asString
            if (pic.isNullOrBlank()) null else ArtistImage(pic, "deezer")
        } catch (_: Exception) { null }
    }

    // iTunes: search(entity=album) → artworkUrl100 → 400x400。专辑封面(非人物)。
    private suspend fun tryItunes(name: String): ArtistImage? = withContext(Dispatchers.IO) {
        try {
            val term = urlEncode(name)
            val body = client.newCall(Request.Builder()
                .url("https://itunes.apple.com/search?term=$term&media=music&limit=1&entity=album")
                .build()).execute().body?.string() ?: return@withContext null
            val results = JsonParser.parseString(body).asJsonObject.getAsJsonArray("results") ?: return@withContext null
            val art = results.firstOrNull()?.asJsonObject?.get("artworkUrl100")?.asString ?: return@withContext null
            val hq = art.replace("100x100bb", "400x400bb").replace("100x100", "400x400")
            ArtistImage(hq, "itunes")
        } catch (_: Exception) { null }
    }

    private fun urlEncode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

package com.shiyin.music.data.image

import com.google.gson.JsonParser
import com.shiyin.music.BuildConfig
import com.shiyin.music.data.db.ShiyinDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.TimeUnit

/** 解析结果：图片 URL + 来源标签（discogs/lastfm/wikidata/deezer/itunes/override）。 */
data class ArtistImage(val url: String, val source: String)

/**
 * v1.2.0 #6: 歌手写真解析器。独立数据层，与 album_art_cache 分离。
 *
 * 解析优先级：
 *  1. [ArtistImageOverride] 用户手选 → 永久最高，自动源永不覆盖。
 *  2. [ArtistImageCache] 本地缓存（有效 URL 直接返回；近期失败 TTL 内跳过重试）。
 *  3. 自动源（按可达性排序，首个命中即停）：
 *     - Discogs   ✅ 可达 / 无需 key / 人物照        （主源）
 *     - Last.fm   ✅ 可达(需 API key) / 人物照        （BuildConfig.LASTFM_API_KEY 缺省则跳过）
 *     - MB→Wikidata ❌ 本机被墙(快失败) / 便携        （换网络可恢复）
 *     - Deezer    ❌ 本机被墙(快失败) / 便携           （换网络可恢复）
 *     - iTunes    ✅ 可达 / 专辑封面(非人物) / 仅 !personOnly 兜底
 *  4. 都没有 → null（UI 走占位渐变）。
 *
 * 可达性实测（2026-08，本机网络）：见各源 KDoc 的 ms/code。死源(Deezer/Wikidata)用
 * [fastClient] 短超时快失败，且命中 Discogs 后根本不会触达它们；缓存 + 6h 失败 TTL
 * 进一步保证每个歌手至多触网一次。持久化在 Room，扫描/更新不丢、自动源不覆盖手选。
 */
class ArtistImageResolver(private val dao: ShiyinDao) {

    private val cache = ArtistImageCache(dao)
    private val override = ArtistImageOverride(dao)
    private val resolving = Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun resolve(name: String, personOnly: Boolean = false): ArtistImage? {
        // 1. 用户手选（最高优先级）
        override.get(name)?.let { return ArtistImage(it, "override") }

        val now = System.currentTimeMillis()
        val cached = cache.get(name)
        // 2. 缓存有效 URL → 直接返回
        if (cached != null && cached.isValid(now)) return ArtistImage(cached.url, cached.source)
        //    仍在失败 TTL 内 → 不重试，返回 null
        if (cached != null && cached.isFailureFresh(now)) return null

        // 3. 自动源（重入保护：同歌手并发只跑一次）
        if (!resolving.add(name)) return null
        return try {
            val result = fetchFromSources(name, personOnly)
            if (result != null) cache.putSuccess(name, result.url, result.source, now)
            else cache.putFailure(name, now, FAILURE_TTL_MS)
            result
        } finally {
            resolving.remove(name)
        }
    }

    /** 用户手动选择写真（写 override，立即生效；自动源永不覆盖）。 */
    suspend fun setOverride(name: String, url: String) =
        override.set(name, url, System.currentTimeMillis())

    /** 清除手选，回退到自动源。 */
    suspend fun clearOverride(name: String) = override.clear(name)

    private suspend fun fetchFromSources(name: String, personOnly: Boolean): ArtistImage? {
        tryDiscogs(name)?.let { return it }
        tryLastfm(name)?.let { return it }
        tryMusicBrainzWikidata(name)?.let { return it }
        tryDeezer(name)?.let { return it }
        if (!personOnly) tryItunes(name)?.let { return it }
        return null
    }

    // ── 源实现 ───────────────────────────────────────────────────────────

    // Discogs: 搜索 artist → /artists/{id} → images[].primary.resource_url。
    // 无需 API key，未鉴权请求按 IP 限流（~25/min），歌手页一次性取+缓存足够。
    private suspend fun tryDiscogs(name: String): ArtistImage? = withContext(Dispatchers.IO) {
        try {
            val q = urlEncode(name)
            val sBody = client.newCall(Request.Builder()
                .url("https://api.discogs.com/database/search?q=$q&type=artist&per_page=5")
                .header("User-Agent", UA).build()).execute().body?.string() ?: return@withContext null
            val results = JsonParser.parseString(sBody).asJsonObject.getAsJsonArray("results") ?: return@withContext null
            // 优先精确标题匹配，否则取首个（按相关度）
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

    // Last.fm: artist.getinfo → image[extralarge/mega]。需 API key（local.properties
    // LASTFM_API_KEY），缺省跳过。端点可达(403 invalid key 实测可达)，有 key 即返回图。
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

    // MusicBrainz → Wikidata P18 人物肖像。MB 可达；wikidata.org 本机实测 8s 超时失败
    // （被墙），用 fastClient(3s 读) 快失败。换网络（VPN/海外）可恢复，保留为便携源。
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

    // Deezer: search → /artist/{id} → picture_xl。本机实测 api.deezer.com 不可达（被墙），
    // 用 fastClient 快失败。换网络可恢复，保留为便携源。
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

    // iTunes: search(entity=album) → artworkUrl100 → 400x400。专辑封面（非人物），
    // 仅 personOnly=false 时作兜底（歌手列表小图标等不强调"必须人物"的场景）。
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

    companion object {
        private const val UA = "ShiyinMusic/2.0 (music-player; android)"
        private const val FAILURE_TTL_MS = 6 * 3600_000L  // 6h: 死源失败后短期不重试

        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        // 给已知被墙的源(Wikidata/Deezer)短超时，快失败落到下一源，不让歌手页干等。
        private val fastClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

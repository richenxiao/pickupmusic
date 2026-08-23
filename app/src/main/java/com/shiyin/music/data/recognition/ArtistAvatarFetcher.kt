package com.shiyin.music.data.recognition

import com.google.gson.JsonParser
import com.shiyin.music.data.db.ArtistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v2.0: Multi-step artist avatar fetch chain.
 *
 * Priority:
 *  1. [ArtistEntity.avatarUrl] already set → skip
 *  2. MusicBrainz search → MBID → Wikidata P18 (image property)
 *  3. iTunes Search API → first artworkUrl100
 *  4. Web search fallback (placeholder — needs DeepSeek config)
 *  5. Return null → caller uses gradient placeholder
 */
object ArtistAvatarFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // v1.2.0 #6: wikidata.org 实测读超时（且其图片在 commons.wikimedia.org 同样不稳）。
    // 给 wikidata 调用单独一个短读超时（4s），慢网/被墙时快速失败落到 iTunes 兜底，
    // 不让歌手页开页干等 10s。快网能在 4s 内拿到 P18 正面照。
    private val wikiClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT = "ShiyinMusic/2.0 (music-player; android)"

    /**
     * Try to fetch an avatar URL for [artistName].
     * Returns null if all sources fail.
     *
     * [personOnly]=true（歌手页大图头图用）：只要 MusicBrainz→Wikidata 的人物肖像照，
     * 查不到就返回 null（让 UI 走占位图），**不**降级用 iTunes 专辑封面——专辑封面当
     * 大图头图观感违和。iTunes 兜底留给不那么强调"必须是人物"的场景（如歌手列表小图标）。
     */
    suspend fun fetch(artistName: String, personOnly: Boolean = false): AvatarResult? = withContext(Dispatchers.IO) {
        // Step 1: MusicBrainz → Wikidata P18（人物肖像）
        val mbResult = tryMusicBrainz(artistName)
        if (mbResult != null) return@withContext mbResult

        if (personOnly) return@withContext null

        // Step 2: iTunes fallback（专辑封面——非人物，仅 personOnly=false 时用）
        val itResult = tryItunes(artistName)
        if (itResult != null) return@withContext itResult

        null
    }

    // ── MusicBrainz ──────────────────────────────────────────────────────

    private data class MbArtist(val mbid: String, val name: String)

    private fun tryMusicBrainz(artistName: String): AvatarResult? {
        try {
            // Search for the artist
            val searchUrl = "https://musicbrainz.org/ws/2/artist/?query=artist:${urlEncode(artistName)}&fmt=json&limit=3"
            val searchReq = Request.Builder().url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            val searchBody = client.newCall(searchReq).execute().body?.string() ?: return null
            val json = JsonParser.parseString(searchBody).asJsonObject
            val artists = json.getAsJsonArray("artists") ?: return null
            if (artists.size() == 0) return null

            // Pick the best match (first result, or exact name match)
            val best = artists.firstOrNull { elem ->
                val name = elem.asJsonObject.get("name")?.asString ?: ""
                name.equals(artistName, ignoreCase = true)
            } ?: artists[0]

            val mbid = best.asJsonObject.get("id")?.asString ?: return null

            // Try Wikidata P18 (image) via MusicBrainz relations
            val imgUrl = tryWikidata(mbid)
            if (imgUrl != null) return AvatarResult(imgUrl, "musicbrainz")
        } catch (_: Exception) {
        }
        return null
    }

    private fun tryWikidata(mbid: String): String? {
        try {
            // Get relations from MusicBrainz
            val relUrl = "https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json"
            val relReq = Request.Builder().url(relUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            val relBody = client.newCall(relReq).execute().body?.string() ?: return null
            val relJson = JsonParser.parseString(relBody).asJsonObject
            val relations = relJson.getAsJsonArray("relations") ?: return null

            // Find the wikidata URL
            val wikidataUrl = relations.firstOrNull { rel ->
                val type = rel.asJsonObject.get("type")?.asString ?: ""
                val url = rel.asJsonObject.get("url")?.asJsonObject?.get("resource")?.asString ?: ""
                type == "wikidata" && url.isNotBlank()
            }?.asJsonObject?.get("url")?.asJsonObject?.get("resource")?.asString ?: return null

            // Extract Q-ID from the wikidata URL
            val qid = wikidataUrl.substringAfterLast("/").substringBefore("#")
            if (!qid.startsWith("Q")) return null

            // Query Wikidata for P18 (image) — 用标准 wbgetentities API（比 Special:EntityData
            // 稳：后者在本机诊断里抛异常，wbgetentities props=claims 返回紧凑 JSON，结构同）。
            val wdUrl = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qid&format=json&props=claims"
            val wdReq = Request.Builder().url(wdUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            val wdBody = wikiClient.newCall(wdReq).execute().body?.string() ?: return null
            val wdJson = JsonParser.parseString(wdBody).asJsonObject
            val entities = wdJson.getAsJsonObject("entities") ?: return null
            val entity = entities.getAsJsonObject(qid) ?: return null
            val claims = entity.getAsJsonObject("claims") ?: return null
            val p18 = claims.getAsJsonArray("P18") ?: return null
            if (p18.size() == 0) return null

            val filename = p18[0].asJsonObject
                .getAsJsonObject("mainsnak")
                ?.getAsJsonObject("datavalue")
                ?.get("value")?.asString ?: return null

            // Convert filename to Commons URL
            val safeName = filename.replace(" ", "_")
            return "https://commons.wikimedia.org/wiki/Special:FilePath/$safeName"
        } catch (_: Exception) {
        }
        return null
    }

    // ── iTunes Search API ─────────────────────────────────────────────────

    private fun tryItunes(artistName: String): AvatarResult? {
        try {
            // v1.2.0 #6 修复：原 entity=musicArtist 不带 artwork 字段（诊断实测 artworkUrl100=null），
            // 故始终取不到图。改 entity=album——专辑结果带 artworkUrl100，取该歌手首张专辑封面
            // 当写真兜底（非正面照但总比占位强；正面照优先走 MusicBrainz→Wikidata P18）。
            val term = urlEncode(artistName)
            val url = "https://itunes.apple.com/search?term=$term&media=music&limit=1&entity=album"
            val req = Request.Builder().url(url).build()
            val body = client.newCall(req).execute().body?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject
            val results = json.getAsJsonArray("results") ?: return null
            if (results.size() == 0) return null
            val artwork = results[0].asJsonObject.get("artworkUrl100")?.asString ?: return null
            // Upscale to 400x400 for better quality
            val hq = artwork.replace("100x100bb", "400x400bb")
                .replace("100x100", "400x400")
            return AvatarResult(hq, "itunes")
        } catch (_: Exception) {
        }
        return null
    }

    // ── helpers ──────────────────────────────────────────────────────────

    data class AvatarResult(val url: String, val source: String)

    private fun urlEncode(s: String): String {
        return java.net.URLEncoder.encode(s, "UTF-8")
    }
}
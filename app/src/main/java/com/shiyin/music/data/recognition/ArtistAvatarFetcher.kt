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

    private const val USER_AGENT = "ShiyinMusic/2.0 (music-player; android)"

    /**
     * Try to fetch an avatar URL for [artistName].
     * Returns null if all sources fail.
     */
    suspend fun fetch(artistName: String): AvatarResult? = withContext(Dispatchers.IO) {
        // Step 1: MusicBrainz → Wikidata
        val mbResult = tryMusicBrainz(artistName)
        if (mbResult != null) return@withContext mbResult

        // Step 2: iTunes fallback
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

            // Query Wikidata for P18 (image)
            val wdUrl = "https://www.wikidata.org/wiki/Special:EntityData/$qid.json"
            val wdReq = Request.Builder().url(wdUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            val wdBody = client.newCall(wdReq).execute().body?.string() ?: return null
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
            val term = urlEncode("$artistName music")
            val url = "https://itunes.apple.com/search?term=$term&media=music&limit=1&entity=musicArtist"
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
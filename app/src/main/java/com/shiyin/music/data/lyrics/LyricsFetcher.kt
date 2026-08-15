package com.shiyin.music.data.lyrics

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
    val SOURCES = listOf("LRCLIB", "网易云音乐")
    private const val UA = "Shiyin/1.0 (Android local music player)"
    private const val DURATION_TOLERANCE_SEC = 2

    /** Returns raw lyric text (LRC or plain) or null when no match. */
    suspend fun fetch(source: String, title: String, artist: String, album: String, durationSec: Long): String? =
        withContext(Dispatchers.IO) {
            try {
                when (source) {
                    "LRCLIB" -> fetchLrclib(title, artist, album, durationSec)
                    "网易云音乐" -> fetchNetease(title, artist, durationSec)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun http(url: String, referer: String? = null): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", UA)
            if (referer != null) conn.setRequestProperty("Referer", referer)
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
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
        val body = http("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}") ?: return null
        val arr = JSONArray(body)
        var best: String? = null
        var bestScore = Long.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lyric = pickLyric(o) ?: continue
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
            if (durationSec <= 0) best = pickLyric(arr.getJSONObject(0))
        }
        return best
    }

    private fun fetchNetease(title: String, artist: String, durationSec: Long): String? {
        val q = enc("$title $artist".trim())
        val body = http(
            "https://music.163.com/api/search/get/web?s=$q&type=1&limit=10&offset=0",
            referer = "https://music.163.com",
        ) ?: return null
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
        var bestId = -1L
        var bestDiff = Long.MAX_VALUE
        for (i in 0 until songs.length()) {
            val s = songs.getJSONObject(i)
            val durSec = s.optLong("duration", -1) / 1000
            val diff = if (durationSec > 0 && durSec > 0) abs(durSec - durationSec) else Long.MAX_VALUE / 2
            if (diff < bestDiff) {
                bestDiff = diff
                bestId = s.optLong("id", -1)
            }
        }
        if (bestId < 0) return null
        if (durationSec > 0 && bestDiff > DURATION_TOLERANCE_SEC) return null
        val lyricBody = http(
            "https://music.163.com/api/song/lyric?id=$bestId&lv=1&kv=-1&tv=-1",
            referer = "https://music.163.com",
        ) ?: return null
        val lrc = JSONObject(lyricBody).optJSONObject("lrc")?.optString("lyric")
        return lrc?.takeIf { it.isNotBlank() }
    }
}

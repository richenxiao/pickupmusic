package com.shiyin.music.data.image

import com.google.gson.JsonParser
import com.shiyin.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v1.2.0 #6: 歌手写真自动源。可插拔：每个源实现 [ArtistImageSource]，[sources] 列表
 * 决定优先级，首个命中即停。新增源 = 加一个 object + 接到 [sources]。
 *
 * 各源只产 URL + 来源标签 + imageType（粗类目），不探测尺寸——尺寸由
 * [ArtistImageResolver] 在命中后用 BitmapFactory 探测（设备侧）。这样源本身
 * 无 Android 依赖、可纯 JVM 单测。
 *
 * 可达性实测（2026-08，本机网络）：
 *  - Discogs   ✅ api + 图片 CDN 均 200，无需 key，人物照。
 *  - Fanart.tv ✅ 端点可达(/v3.1，bad key→401)，需 API key + MBID；artistbackground 宽背景/artistthumb 方形。
 *  - Last.fm   ✅ 端点可达(403 bad key)，需 API key。
 *  - MB         ✅ 可达(仅 MBID，[MusicBrainz.lookupMbid] 共享给 Fanart + Wikidata)；
 *               Wikidata ❌ 8s 超时(被墙)。
 *  - Deezer     ❌ api.deezer.com 不可达(被墙)。
 *  - iTunes     ✅ 可达，专辑封面(非人物)。
 */
internal object ImageHttp {
    const val UA = "ShiyinMusic/2.0 (music-player; android)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 给已知被墙的源(Wikidata/Deezer)短超时，快失败落到下一源。 */
    val fastClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun urlEncode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

/** MusicBrainz artist 查询→MBID，供 Fanart / Wikidata 共享。 */
internal object MusicBrainz {
    suspend fun lookupMbid(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val q = ImageHttp.urlEncode(name)
            val body = ImageHttp.client.newCall(Request.Builder()
                .url("https://musicbrainz.org/ws/2/artist/?query=artist:$q&fmt=json&limit=3")
                .header("User-Agent", ImageHttp.UA).header("Accept", "application/json").build()
            ).execute().body?.string() ?: return@withContext null
            val artists = JsonParser.parseString(body).asJsonObject.getAsJsonArray("artists") ?: return@withContext null
            val best = artists.firstOrNull { (it.asJsonObject.get("name")?.asString ?: "").equals(name, ignoreCase = true) }
                ?: artists.firstOrNull() ?: return@withContext null
            best.asJsonObject.get("id")?.asString
        } catch (_: Exception) { null }
    }
}

/** 可插拔的歌手写真源。 */
internal interface ArtistImageSource {
    val key: String
    suspend fun fetch(name: String, personOnly: Boolean): ArtistImage?
    /** 该源所有可用图(供「选择写真」picker);默认仅 fetch() 那一张,多图源(Discogs/AudioDB/Fanart)覆写返回全部。 */
    suspend fun fetchAll(name: String, personOnly: Boolean): List<ArtistImage> = listOfNotNull(fetch(name, personOnly))
}

internal object DiscogsSource : ArtistImageSource {
    override val key = "discogs"
    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? {
        val all = fetchAll(name, personOnly)
        return all.firstOrNull { it.imageType == "photo" } ?: all.firstOrNull()
    }
    // 返回该歌手在 Discogs 的全部图(primary=photo 排前,secondary=thumb 随后),供选择写真。
    override suspend fun fetchAll(name: String, personOnly: Boolean): List<ArtistImage> = withContext(Dispatchers.IO) {
        try {
            val q = ImageHttp.urlEncode(name)
            val sBody = ImageHttp.client.newCall(Request.Builder()
                .url("https://api.discogs.com/database/search?q=$q&type=artist&per_page=5")
                .header("User-Agent", ImageHttp.UA).build()).execute().body?.string() ?: return@withContext emptyList()
            val results = JsonParser.parseString(sBody).asJsonObject.getAsJsonArray("results") ?: return@withContext emptyList()
            val id = results.firstOrNull { el ->
                (el.asJsonObject.get("title")?.asString ?: "").equals(name, ignoreCase = true)
            }?.asJsonObject?.get("id")?.asString
                ?: results.firstOrNull()?.asJsonObject?.get("id")?.asString
                ?: return@withContext emptyList()
            val aBody = ImageHttp.client.newCall(Request.Builder()
                .url("https://api.discogs.com/artists/$id")
                .header("User-Agent", ImageHttp.UA).build()).execute().body?.string() ?: return@withContext emptyList()
            val imgs = JsonParser.parseString(aBody).asJsonObject.getAsJsonArray("images") ?: return@withContext emptyList()
            val all = imgs.mapNotNull { el ->
                val o = el.asJsonObject
                val u = o.get("resource_url")?.asString
                val t = o.get("type")?.asString ?: ""
                if (!u.isNullOrBlank()) ArtistImage(u, "discogs", imageType = if (t == "primary") "photo" else "thumb") else null
            }
            all.sortedByDescending { if (it.imageType == "photo") 1 else 0 }
        } catch (_: Exception) { emptyList() }
    }
}

// Fanart.tv: /v3.1/music/{mbid}?api_key=KEY。需 API key(BuildConfig.FANART_API_KEY)+MBID。
// 取 artistbackground(宽背景,适合全屏头图) → artistthumb(方形)。跳过 hdmusiclogo/musicbanner(文字 logo)。
internal object FanartSource : ArtistImageSource {
    override val key = "fanart"
    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? = fetchAll(name, personOnly).firstOrNull()
    // 返回 Fanart 全部 artistbackground(宽背景) + artistthumb(方形),供选择写真。
    override suspend fun fetchAll(name: String, personOnly: Boolean): List<ArtistImage> = withContext(Dispatchers.IO) {
        val key = BuildConfig.FANART_API_KEY
        if (key.isBlank()) return@withContext emptyList()
        val mbid = MusicBrainz.lookupMbid(name) ?: return@withContext emptyList()
        try {
            val body = ImageHttp.client.newCall(Request.Builder()
                .url("https://webservice.fanart.tv/v3.1/music/$mbid?api_key=$key")
                .header("User-Agent", ImageHttp.UA).build()).execute().body?.string() ?: return@withContext emptyList()
            val obj = JsonParser.parseString(body).asJsonObject
            val bgs = obj.getAsJsonArray("artistbackground")?.mapNotNull { it.asJsonObject.get("url")?.asString?.takeIf { u -> u.isNotBlank() } } ?: emptyList()
            val thumbs = obj.getAsJsonArray("artistthumb")?.mapNotNull { it.asJsonObject.get("url")?.asString?.takeIf { u -> u.isNotBlank() } } ?: emptyList()
            bgs.map { ArtistImage(it, "fanart", "background") } + thumbs.map { ArtistImage(it, "fanart", "thumb") }
        } catch (_: Exception) { emptyList() }
    }
}

// Last.fm: artist.getinfo → image[extralarge/mega]。需 BuildConfig.LASTFM_API_KEY。
internal object LastfmSource : ArtistImageSource {
    override val key = "lastfm"
    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? = withContext(Dispatchers.IO) {
        val key = BuildConfig.LASTFM_API_KEY
        if (key.isBlank()) return@withContext null
        try {
            val q = ImageHttp.urlEncode(name)
            val url = "http://ws.audioscrobbler.com/2.0/?method=artist.getinfo&artist=$q&api_key=$key&format=json"
            val body = ImageHttp.client.newCall(Request.Builder().url(url).header("User-Agent", ImageHttp.UA).build())
                .execute().body?.string() ?: return@withContext null
            val artist = JsonParser.parseString(body).asJsonObject.getAsJsonObject("artist") ?: return@withContext null
            val imgs = artist.getAsJsonArray("image") ?: return@withContext null
            val pick = imgs.lastOrNull { it.asJsonObject.get("size")?.asString in listOf("extralarge", "mega") }
                ?: imgs.lastOrNull()
            val u = pick?.asJsonObject?.get("#text")?.asString
            if (u.isNullOrBlank()) null else ArtistImage(u, "lastfm", imageType = "photo")
        } catch (_: Exception) { null }
    }
}

// MusicBrainz → Wikidata P18 人物肖像。MB 可达；wikidata.org 本机被墙，用 fastClient 快失败。
internal object MusicBrainzWikidataSource : ArtistImageSource {
    override val key = "wikidata"
    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? = withContext(Dispatchers.IO) {
        try {
            val mbid = MusicBrainz.lookupMbid(name) ?: return@withContext null
            val relBody = ImageHttp.client.newCall(Request.Builder()
                .url("https://musicbrainz.org/ws/2/artist/$mbid?inc=url-rels&fmt=json")
                .header("User-Agent", ImageHttp.UA).build()).execute().body?.string() ?: return@withContext null
            val relations = JsonParser.parseString(relBody).asJsonObject.getAsJsonArray("relations") ?: return@withContext null
            val wdUrl = relations.firstOrNull { r ->
                val t = r.asJsonObject.get("type")?.asString ?: ""
                val u = r.asJsonObject.get("url")?.asJsonObject?.get("resource")?.asString ?: ""
                t == "wikidata" && u.isNotBlank()
            }?.asJsonObject?.get("url")?.asJsonObject?.get("resource")?.asString ?: return@withContext null
            val qid = wdUrl.substringAfterLast("/").substringBefore("#")
            if (!qid.startsWith("Q")) return@withContext null
            val wdBody = ImageHttp.fastClient.newCall(Request.Builder()
                .url("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qid&format=json&props=claims")
                .header("User-Agent", ImageHttp.UA).header("Accept", "application/json").build()
            ).execute().body?.string() ?: return@withContext null
            val entity = JsonParser.parseString(wdBody).asJsonObject.getAsJsonObject("entities")?.getAsJsonObject(qid)
                ?: return@withContext null
            val p18 = entity.getAsJsonObject("claims")?.getAsJsonArray("P18")
            if (p18 == null || p18.size() == 0) return@withContext null
            val fn = p18[0].asJsonObject.getAsJsonObject("mainsnak")?.getAsJsonObject("datavalue")
                ?.get("value")?.asString ?: return@withContext null
            ArtistImage("https://commons.wikimedia.org/wiki/Special:FilePath/${fn.replace(" ", "_")}", "wikidata", imageType = "photo")
        } catch (_: Exception) { null }
    }
}

// Deezer: search → /artist/{id} → picture_xl。本机被墙，fastClient 快失败。
internal object DeezerSource : ArtistImageSource {
    override val key = "deezer"
    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? = withContext(Dispatchers.IO) {
        try {
            val q = ImageHttp.urlEncode("artist:\"$name\"")
            val sBody = ImageHttp.fastClient.newCall(Request.Builder()
                .url("https://api.deezer.com/search?q=$q&limit=1")
                .header("User-Agent", ImageHttp.UA).build()).execute().body?.string() ?: return@withContext null
            val id = JsonParser.parseString(sBody).asJsonObject.getAsJsonArray("data")
                ?.firstOrNull()?.asJsonObject?.getAsJsonObject("artist")?.get("id")?.asString
                ?: return@withContext null
            val aBody = ImageHttp.fastClient.newCall(Request.Builder()
                .url("https://api.deezer.com/artist/$id")
                .header("User-Agent", ImageHttp.UA).build()).execute().body?.string() ?: return@withContext null
            val pic = JsonParser.parseString(aBody).asJsonObject.get("picture_xl")?.asString
            if (pic.isNullOrBlank()) null else ArtistImage(pic, "deezer", imageType = "photo")
        } catch (_: Exception) { null }
    }
}

// AudioDB: free API（demo key "2"，无需注册），搜索→人物照/宽背景。
// 实测 API + 图片 CDN(r2.theaudiodb.com) 均可达。CJK 英文名可搜，中文名部分不命中。
internal object AudioDBSource : ArtistImageSource {
    override val key = "audiodb"
    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? {
        val all = fetchAll(name, personOnly)
        return all.firstOrNull { it.imageType == "photo" } ?: all.firstOrNull()
    }
    // 返回 AudioDB 该歌手全部可用图(thumb/wideThumb 人物照排前,clearart/fanart 随后)。
    override suspend fun fetchAll(name: String, personOnly: Boolean): List<ArtistImage> = withContext(Dispatchers.IO) {
        try {
            val q = ImageHttp.urlEncode(name)
            val body = ImageHttp.client.newCall(Request.Builder()
                .url("https://www.theaudiodb.com/api/v1/json/2/search.php?s=$q")
                .header("User-Agent", ImageHttp.UA).build()).execute().body?.string() ?: return@withContext emptyList()
            val artist = JsonParser.parseString(body).asJsonObject.getAsJsonArray("artists")?.firstOrNull()?.asJsonObject
                ?: return@withContext emptyList()
            val fields = listOf(
                "strArtistThumb" to "photo",
                "strArtistWideThumb" to "photo",
                "strArtistClearart" to "thumb",
                "strArtistFanart" to "background",
                "strArtistFanart2" to "background",
            )
            fields.mapNotNull { (f, t) -> artist.get(f)?.asString?.takeIf { it.isNotBlank() }?.let { ArtistImage(it, "audiodb", imageType = t) } }
        } catch (_: Exception) { emptyList() }
    }
}

// iTunes: search(entity=album) → artworkUrl100 → 400x400。专辑封面(非人物)，仅 !personOnly 兜底。
internal object ItunesSource : ArtistImageSource {
    override val key = "itunes"
    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? = withContext(Dispatchers.IO) {
        if (personOnly) return@withContext null
        try {
            val term = ImageHttp.urlEncode(name)
            val body = ImageHttp.client.newCall(Request.Builder()
                .url("https://itunes.apple.com/search?term=$term&media=music&limit=1&entity=album")
                .build()).execute().body?.string() ?: return@withContext null
            val results = JsonParser.parseString(body).asJsonObject.getAsJsonArray("results") ?: return@withContext null
            val art = results.firstOrNull()?.asJsonObject?.get("artworkUrl100")?.asString ?: return@withContext null
            val hq = art.replace("100x100bb", "400x400bb").replace("100x100", "400x400")
            ArtistImage(hq, "itunes", imageType = "album")
        } catch (_: Exception) { null }
    }
}

/** 按优先级依次尝试各源，首个图片可下载的命（API 返回 URL + 图片头部可下载）。 */
internal object ArtistImageSources {
    // 可达性优先：Discogs → AudioDB(无key人物照) → Fanart(有key宽背景) →
    // Last.fm(有key) → MB→Wikidata(被墙快失败) → Deezer(被墙快失败) → iTunes(专辑封面兜底)。
    val sources: List<ArtistImageSource> = listOf(
        DiscogsSource, AudioDBSource, FanartSource, LastfmSource,
        MusicBrainzWikidataSource, DeezerSource, ItunesSource,
    )

    suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? {
        for (s in sources) {
            val r = s.fetch(name, personOnly)
            android.util.Log.d("AIM", "src ${s.key}: ${r?.url?.take(70) ?: "null"}")
            if (r != null) return r
        }
        android.util.Log.d("AIM", "all sources null (personOnly=$personOnly)")
        return null
    }
}

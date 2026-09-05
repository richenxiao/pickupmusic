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

/** 源层级:photo=人物照/写真语义;album=专辑封面语义,仅作 photo 组全空后的末位兜底。 */
internal enum class SourceTier { PHOTO, ALBUM }

/** 可插拔的歌手写真源。 */
internal interface ArtistImageSource {
    val key: String
    /** v1.2.1: 声明该源的语义层级。album 源(iTunes/Deezer 专辑封面)会被 resolver 降级到
     *  photo 组全部失败后才参与兜底,避免"快但错"的封面在并行竞速里顶替写真位。 */
    val tier: SourceTier get() = SourceTier.PHOTO
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
        // v1.2.1: 运行时 key(设置·写真源)优先,空则回退 BuildConfig(local.properties);都空→跳过
        val key = ImageSourceConfig.fanartApiKey.ifBlank { BuildConfig.FANART_API_KEY }
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
        // v1.2.1: 运行时 key(设置·写真源)优先,空则回退 BuildConfig(local.properties);都空→null
        val key = ImageSourceConfig.lastfmApiKey.ifBlank { BuildConfig.LASTFM_API_KEY }
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
    override val tier = SourceTier.ALBUM
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
    override val tier = SourceTier.ALBUM
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

// 百度百科:大陆可达、高准确度的人物照来源。解析失败/无词条→返回空,落到下一源,不污染结果。
// v1.2.1.1: 去掉旧 HTML 时代的"仅 CJK 名字"门——aiko 等拉丁名日系歌手词条在百科同样有
// 肖像(实测 bk_key=aiko 精确命中 newLemmaId=5352130);开放接口对无词条返回 {}、对疑似
// 错词条有 key/title 匹配校验,空/错都干净落下一源,门已无必要。
// v1.2.1 重构(实测排查):网页版 /item/ 对程序化请求已普遍 403「百度安全验证」(JS 验证码页,
// 换 Chrome UA/补齐全套浏览器头均无效),旧 HTML 抓取基本失效——这是"百科经常拿不到图"的主因。
// 改为优先走百科官方开放接口 BaikeLemmaCardApi(第三方程序化接入,无反爬):
//  - image 字段=摘要肖像(bkimg.cdn.bcebos.com,实测无防盗链、直连 200 JPEG),自带
//    isSummaryPic 标记与真实宽高 → 天然就是"信息框主图",不会抓到页内杂图/导航 logo。
//  - key=词条名(保留原字形,如"米津玄師")、title=简体化标题,与 name 都对不上 → 视为
//    命中同名异义词条,宁可空落下一源不抓错脸;消歧义/别名重定向由接口归到主词条。
//  - 不存在的词条返回 {} → 与"词条存在但无配图"(有 key 无 image)分开记日志可诊断。
// API 落空再回退旧网页抓取(部分网络/IP 仍可过;验证码页显式判空退出)。
// v1.2.1.1: fetchAll 增补图集——移动端 UA 请求词条页不触发桌面端 403 风控(实测 200),
// 页面 __NEXT_DATA__ 内嵌 albums 图集(概述图/人物照/词条图片,每项 src=图床 hash、
// url 常为空 → 拼 https://bkimg.cdn.bcebos.com/pic/{src} 原图,附 width/height)。
// fetch(自动解析)只走开放接口摘要,单请求轻量;fetchAll(picker)才拉移动页取全部。
internal object BaiduBaikeSource : ArtistImageSource {
    override val key = "baike"
    /** 移动端 UA:桌面 UA 请求 /item/ 页 403「百度安全验证」,移动 UA 实测 200 正常返回。 */
    private const val UA_MOBILE = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? = withContext(Dispatchers.IO) {
        // 自动解析只需摘要肖像(API 单请求);API 落空再退移动页首张,不为其白拉 300KB 页面
        fetchViaOpenApi(name).firstOrNull() ?: fetchGalleryFromMobilePage(name).firstOrNull()
    }
    override suspend fun fetchAll(name: String, personOnly: Boolean): List<ArtistImage> = withContext(Dispatchers.IO) {
        // 摘要(开放接口,高置信带尺寸)在前,图集(移动页 albums)随后;按 /pic/ 路径去重
        // (同一张图两处的 x-bce-process 处理串不同),picker 多图可挑。
        (fetchViaOpenApi(name) + fetchGalleryFromMobilePage(name))
            .distinctBy { it.url.substringBefore('?') }
            .take(12)
    }

    /** 百科官方开放接口:返回词条卡 JSON,取摘要肖像。 */
    private fun fetchViaOpenApi(name: String): List<ArtistImage> {
        return try {
            val q = ImageHttp.urlEncode(name)
            ImageHttp.client.newCall(Request.Builder()
                .url("https://baike.baidu.com/api/openapi/BaikeLemmaCardApi?scope=103&format=json&appid=379020&bk_key=$q&bk_length=600")
                .header("User-Agent", ImageHttp.UA).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.d("AIM", "baike api: HTTP ${resp.code} for $name")
                    return emptyList()
                }
                val body = resp.body?.string() ?: return emptyList()
                val obj = JsonParser.parseString(body).asJsonObject
                if (!obj.has("key")) {
                    android.util.Log.d("AIM", "baike api: no lemma for $name")
                    return emptyList()
                }
                // 词条匹配校验:key 保留原字形(米津玄師)、title 简体化(米津玄师),两者都校。
                // 前缀匹配容忍词条名带修饰;1 字名太易撞词,只认全等。
                val key = obj.get("key")?.asString ?: ""
                val title = obj.get("title")?.asString ?: ""
                val matched = key.equals(name, ignoreCase = true) || title.equals(name, ignoreCase = true) ||
                    (name.length >= 2 && (key.startsWith(name) || title.startsWith(name)))
                if (!matched) {
                    android.util.Log.d("AIM", "baike api: lemma mismatch name=$name key=$key title=$title")
                    return emptyList()
                }
                val url = obj.get("image")?.takeIf { it.isJsonPrimitive && it.asString.isNotBlank() }?.asString
                if (url == null) {
                    android.util.Log.d("AIM", "baike api: lemma $key exists but has no summary image")
                    return emptyList()
                }
                val w = (obj.get("imageWidth") as? com.google.gson.JsonPrimitive)?.asInt ?: 0
                val h = (obj.get("imageHeight") as? com.google.gson.JsonPrimitive)?.asInt ?: 0
                listOf(ArtistImage(url, "baike", imageType = "photo", width = w, height = h))
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 移动端词条页抓图集:移动 UA 不触发桌面端 403 风控(实测 200)。页面 __NEXT_DATA__
     *  内嵌 albums 图集数组,每张 content 项带 src(图床 hash,url 字段常为空)与宽高 →
     *  拼 bkimg 原图 URL。多图仅供 picker 挑选;开放接口摘要失败时本路径独立兜底。 */
    private fun fetchGalleryFromMobilePage(name: String): List<ArtistImage> {
        return try {
            val q = ImageHttp.urlEncode(name)
            ImageHttp.client.newCall(Request.Builder()
                .url("https://baike.baidu.com/item/$q")
                .header("User-Agent", UA_MOBILE)
                .build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.d("AIM", "baike mobile: HTTP ${resp.code} for $name")
                    return emptyList()
                }
                val body = resp.body?.string() ?: return emptyList()
                if (body.contains("百度安全验证")) {
                    android.util.Log.d("AIM", "baike mobile: captcha for $name")
                    return emptyList()
                }
                val nextData = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""")
                    .find(body)?.groupValues?.get(1) ?: return emptyList()
                val pageData = JsonParser.parseString(nextData).asJsonObject
                    .getAsJsonObject("props")?.getAsJsonObject("pageProps")
                    ?.getAsJsonObject("pageData") ?: return emptyList()
                // albums[].content[]:src=图床 hash,url 常空 → 拼 bkimg 原图;同图跨相册按 src 去重
                val images = mutableListOf<ArtistImage>()
                pageData.getAsJsonArray("albums")?.forEach { el ->
                    (el.asJsonObject).getAsJsonArray("content")?.forEach { c ->
                        val o = c.asJsonObject
                        val src = (o.get("src") as? com.google.gson.JsonPrimitive)?.asString
                            ?.takeIf { it.isNotBlank() } ?: return@forEach
                        images.add(ArtistImage(
                            "https://bkimg.cdn.bcebos.com/pic/$src", "baike", imageType = "photo",
                            width = (o.get("width") as? com.google.gson.JsonPrimitive)?.asInt ?: 0,
                            height = (o.get("height") as? com.google.gson.JsonPrimitive)?.asInt ?: 0,
                        ))
                    }
                }
                images.distinctBy { it.url }.take(12)
                    .also { android.util.Log.d("AIM", "baike mobile: gallery ${it.size} imgs for $name") }
            }
        } catch (_: Exception) { emptyList() }
    }
}

// 百度图片搜索(image.baidu.com acjson API):百度百科解析失败后的 CJK 补充源。
// v1.2.1 严格校验(避免重蹈 Bing 覆辙):① 标题(fromPageTitle)须含歌手全名(降同名/不相关);
// ② 非空尺寸时排除过小(<200,图标类)与极端宽高比(>2.5 横幅/<0.4 logo);③ 用百度代理图
// (middleURL/thumbURL,百度图床可信域名)。仍可能不相关,作为 baike 之后的补充非主力。
internal object BaiduImageSearchSource : ArtistImageSource {
    override val key = "baiduimg"
    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? = fetchAll(name, personOnly).firstOrNull()
    override suspend fun fetchAll(name: String, personOnly: Boolean): List<ArtistImage> = withContext(Dispatchers.IO) {
        if (!com.shiyin.music.data.normalize.CharUtil.isCjk(name)) return@withContext emptyList<ArtistImage>()
        try {
            val q = ImageHttp.urlEncode("$name 歌手 照片")
            val body = ImageHttp.client.newCall(Request.Builder()
                .url("https://image.baidu.com/search/acjson?tn=resultjson_com_ipython&word=$q&pn=0&rn=20")
                .header("User-Agent", ImageHttp.UA).build()).execute().body?.string() ?: return@withContext emptyList()
            val data = JsonParser.parseString(body).asJsonObject.getAsJsonArray("data") ?: return@withContext emptyList()
            data.mapNotNull { el ->
                runCatching {
                    val o = el.asJsonObject
                    val url = o.get("middleURL")?.asString?.takeIf { it.isNotBlank() }
                        ?: o.get("thumbURL")?.asString?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    // 校验1:来源页标题须含歌手全名
                    val title = o.get("fromPageTitle")?.asString ?: ""
                    if (!title.contains(name)) return@mapNotNull null
                    // 校验2:尺寸/比例(有尺寸时)——排除图标与横幅/logo
                    val w = o.get("width")?.asInt ?: 0
                    val h = o.get("height")?.asInt ?: 0
                    if (w > 0 && h > 0) {
                        if (w < 200 || h < 200) return@mapNotNull null
                        val ratio = w.toFloat() / h
                        if (ratio > 2.5f || ratio < 0.4f) return@mapNotNull null
                    }
                    ArtistImage(url, "baiduimg", imageType = "photo", width = w, height = h)
                }.getOrNull()
            }.take(5)
        } catch (_: Exception) { emptyList() }
    }
}

// 英文维基百科 pageimages API:取词条缩略图(upload.wikimedia.org)。对欧美歌手准;
// en.wikipedia.org 大陆被墙,fastClient 3s 快失败,翻墙/海外用户可用。比 MB→Wikidata P18 直接。
internal object WikipediaSource : ArtistImageSource {
    override val key = "wikipedia"
    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? = withContext(Dispatchers.IO) {
        try {
            val q = ImageHttp.urlEncode(name)
            val body = ImageHttp.fastClient.newCall(Request.Builder()
                .url("https://en.wikipedia.org/w/api.php?action=query&titles=$q&prop=pageimages&format=json&pithumbsize=600&pilicense=any")
                .header("User-Agent", ImageHttp.UA).header("Accept", "application/json").build())
                .execute().body?.string() ?: return@withContext null
            val pages = JsonParser.parseString(body).asJsonObject.getAsJsonObject("query")?.getAsJsonObject("pages")
                ?: return@withContext null
            val page = pages.entrySet().firstOrNull()?.value?.asJsonObject ?: return@withContext null
            val url = page.getAsJsonObject("thumbnail")?.get("source")?.asString
            if (url.isNullOrBlank()) null else ArtistImage(url, "wikipedia", imageType = "photo")
        } catch (_: Exception) { null }
    }
}

// v1.3.2: 自定义源(设置·写真源添加)。URL 里 {name} → URL 编码歌手名;
// 可选 API Key:URL 含 {key} 时替换进去,否则以 Authorization: Bearer 头附带。
// 模板以图片扩展名结尾 → 直连图片;否则请求后按 JSON 解析,递归收集响应里
// 像图片 URL 的字符串值(去重,最多 10 张)。任何失败返回空,干净落到下一源。
internal class CustomSource(private val def: CustomImageSourceDef) : ArtistImageSource {
    override val key = "custom-${def.id}"
    override suspend fun fetch(name: String, personOnly: Boolean): ArtistImage? =
        fetchAll(name, personOnly).firstOrNull()

    override suspend fun fetchAll(name: String, personOnly: Boolean): List<ArtistImage> = withContext(Dispatchers.IO) {
        val tpl = def.urlTemplate.trim()
        if (!tpl.startsWith("http") || !tpl.contains("{name}")) return@withContext emptyList()
        val req = Request.Builder().url(tpl
            .replace("{name}", ImageHttp.urlEncode(name))
            .replace("{key}", def.apiKey)
        ).header("User-Agent", ImageHttp.UA)
        if (def.apiKey.isNotBlank() && !tpl.contains("{key}")) req.header("Authorization", "Bearer ${def.apiKey}")
        try {
            val url = req.build().url.toString()
            if (IMG_EXT.containsMatchIn(url.substringAfterLast('/'))) {
                return@withContext listOf(ArtistImage(url, key, imageType = "photo"))
            }
            val body = ImageHttp.client.newCall(req.build()).execute().body?.string()
                ?: return@withContext emptyList()
            val out = LinkedHashSet<String>()
            collectImageUrls(JsonParser.parseString(body), out)
            out.take(10).map { ArtistImage(it, key, imageType = "photo") }
        } catch (_: Exception) { emptyList() }
    }

    private fun collectImageUrls(el: com.google.gson.JsonElement, out: MutableCollection<String>) {
        when {
            el.isJsonPrimitive -> el.asString.takeIf { s ->
                s.startsWith("http") && IMG_EXT.containsMatchIn(s.substringAfterLast('/'))
            }?.let { out.add(it) }
            el.isJsonArray -> el.asJsonArray.forEach { collectImageUrls(it, out) }
            el.isJsonObject -> el.asJsonObject.entrySet().forEach { collectImageUrls(it.value, out) }
        }
    }

    companion object {
        private val IMG_EXT = Regex("""\.(jpe?g|png|webp|gif|bmp)([?#].*)?$""", RegexOption.IGNORE_CASE)
    }
}

/** 按优先级依次尝试各源，首个图片可下载的命（API 返回 URL + 图片头部可下载）。 */
internal object ArtistImageSources {
    // v1.2.1: auto-resolver 分层竞速(photo 层先并行抢,album 层全空才兜底,见
    // enabledSourcesByTier);此处顺序在 photo/album 各自组内影响竞速稳定倾向,并决定
    // picker 展示序。可靠源排前:Discogs → AudioDB → 百度百科(CJK) → 百度图片搜索(CJK 补充,
    // 严格校验) → Fanart(有key宽背景) → Last.fm(有key) → 维基百科(海外) → MB→Wikidata(被墙快失败)
    // → Deezer(被墙快失败) → iTunes(专辑封面兜底)。移除 Bing(结果太杂)。
    val sources: List<ArtistImageSource> = listOf(
        DiscogsSource, AudioDBSource, BaiduBaikeSource, BaiduImageSearchSource, FanartSource, LastfmSource,
        WikipediaSource, MusicBrainzWikidataSource, DeezerSource, ItunesSource,
    )

    /** v1.2.1: 源 key → 显示名 + 说明(供设置·写真源开关列表用)。 */
    val sourceLabels: Map<String, String> = mapOf(
        "discogs" to "Discogs",
        "audiodb" to "AudioDB",
        "baike" to "百度百科",
        "baiduimg" to "百度图片搜索",
        "fanart" to "Fanart.tv",
        "lastfm" to "Last.fm",
        "wikipedia" to "维基百科",
        "wikidata" to "Wikidata",
        "deezer" to "Deezer",
        "itunes" to "iTunes 专辑封面",
    )

    /** v1.3.2: 源 key → 一句话说明(设置页开关行的小字)。 */
    val sourceDescriptions: Map<String, String> = mapOf(
        "discogs" to "人物照 · 无需 Key",
        "audiodb" to "人物照 / 宽背景 · 无需 Key",
        "baike" to "百度百科词条肖像 · 中文歌手准",
        "baiduimg" to "百度图片搜索 · 百科失败后的补充",
        "fanart" to "宽背景 / 方图 · 需 API Key(下方「自定义源」里填)",
        "lastfm" to "人物照 · 需 API Key(下方「自定义源」里填)",
        "wikipedia" to "维基百科词条图 · 海外网络可用",
        "wikidata" to "MusicBrainz → Wikidata 肖像",
        "deezer" to "艺人照片 · 大陆网络不可达",
        "itunes" to "专辑封面兜底 · 非人物照",
    )

    /** v1.3.2: 全部源 = 内置 + 设置·写真源添加的自定义源(排在内置之后)。 */
    fun allSources(): List<ArtistImageSource> =
        sources + ImageSourceConfig.customSources.map { CustomSource(it) }

    /** v1.2.1: 过滤掉用户在设置里关掉的源(resolver/picker 都用这个,使能即时生效)。 */
    fun enabledSources(): List<ArtistImageSource> =
        allSources().filter { it.key !in ImageSourceConfig.disabledSources }

    /** v1.2.1: 按 tier 分组(photo 组在前,各自保持 sources 原顺序)。resolver 先竞速
     *  photo 组,全空才跑 album 组(iTunes/Deezer 专辑封面)兜底——不让快但语义错的
     *  封面源在竞速里顶替写真位。 */
    fun enabledSourcesByTier(): Pair<List<ArtistImageSource>, List<ArtistImageSource>> {
        val enabled = enabledSources()
        return enabled.filter { it.tier == SourceTier.PHOTO } to enabled.filter { it.tier == SourceTier.ALBUM }
    }

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

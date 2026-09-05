package com.shiyin.music.data.image

import com.shiyin.music.data.db.ShiyinDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 解析结果：图片 URL + 来源标签 + 元数据。
 * - [imageType] 粗类目：photo/background/thumb/album/banner/logo（源给出，UI 据此 + 比例决定裁剪）。
 * - [width]/[height] 由 [ArtistImageResolver] 命中后探测（0=未探测/失败）。
 */
data class ArtistImage(
    val url: String,
    val source: String,
    val imageType: String = "",
    val width: Int = 0,
    val height: Int = 0,
) {
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 0f
}

/**
 * v1.2.0 #6: 歌手写真解析器。独立数据层，与 album_art_cache 分离。
 *
 * 解析优先级：
 *  1. [ArtistImageOverride] 用户手选 → 永久最高，自动源永不覆盖。
 *  2. [ArtistImageCache] 本地缓存（有效 URL 直接返回；近期失败 TTL 内跳过重试）。
 *  3. 自动源（[ArtistImageSources]，v1.2.1 分层竞速）：photo 层（人物照语义：
 *     Discogs/AudioDB/百科/Fanart/Last.fm/维基等）先并行竞速；全空才落到 album 层
 *     （iTunes/Deezer 专辑封面）兜底——快但语义错的封面不再顶替写真位。
 *     personOnly 语义下 album 层直接跳过（只要人物照）。
 *  4. 都没有 → null（UI 走占位渐变）。
 *
 * 命中后用 BitmapFactory(inJustDecodeBounds) 探测真实宽高，连同 imageType 一起写入
 * cache，供 UI 按比例决定裁剪方式（宽背景全屏铺、方形肖像居中裁、避免 logo/banner）。
 *
 * 持久化在 Room（artist_image_cache / artist_image_override），扫描/更新不丢、自动源不覆盖手选。
 */
class ArtistImageResolver(private val dao: ShiyinDao) {

    private val cache = ArtistImageCache(dao)
    private val override = ArtistImageOverride(dao)
    /** v1.2.1: 同名解析去重。旧实现 Set<String>+并发重入直接 return null——列表页多屏同时
     *  请求同一歌手时，后到者白等还拿不到结果。改成登记进行中的 Deferred，重入者 await
     *  同一个协程复用其结果（只发一次网络）。解析跑在 resolver 自有 [scope]（与调用方解耦）：
     *  发起者离开页面被取消也不影响已发出的请求，结果照常写缓存惠及后来者。 */
    private val resolving = ConcurrentHashMap<String, Deferred<ArtistImage?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // v1.2.0 #6: 清掉旧失败态（url='' 行）。旧版本 6h 失败 TTL 留下的失败行，
        // 在网络变化(开 VPN/换网)后源已可恢复但仍挡重试。启动清一次，不丢有效写真。
        scope.launch {
            runCatching { dao.clearArtistImageFailures() }
        }
    }

    suspend fun resolve(name: String, personOnly: Boolean = false): ArtistImage? {
        // 1. 用户手选（最高优先级）
        override.get(name)?.let {
            android.util.Log.d("AIM", "override hit")
            return ArtistImage(it, "override")
        }

        val now = System.currentTimeMillis()
        val cached = cache.get(name)
        // 2. 缓存有效 URL → 直接返回（带缓存的元数据）
        //    v1.3.5: personOnly(圆框头像)时,缓存里是 album 类型(封面/banner 兜底)
        //    的条目视作未命中——继续走 photo 层重解析,别把封面横幅塞进小圆框截成
        //    半张脸。photo/thumb/background 类型照常命中。album 缓存绕过不算失败,
        //    personOnly 搜空时上层保留已有 URL(见 fetchArtistAvatarPerson)。
        val cacheHit = cached != null && cached.isValid(now) &&
            !(personOnly && cached.imageType.equals("album", ignoreCase = true))
        if (cacheHit && cached != null) {
            android.util.Log.d("AIM", "cache valid: ${cached.source} ${cached.url.take(60)}")
            return ArtistImage(cached.url, cached.source, cached.imageType, cached.width, cached.height)
        }
        //    仍在失败 TTL 内 → 不重试，返回 null(只锁整名全空的失败行;personOnly
        //    绕过 album 缓存不构成失败,继续往下解析)。
        if (cached != null && cached.url.isBlank() && cached.isFailureFresh(now)) {
            android.util.Log.d("AIM", "cache failure-fresh (skip until ${cached.failUntilTs - now}ms)")
            return null
        }

        // 3. 自动源：同名并发 → await 同一个进行中的解析（不丢结果、不重复触网）；
        //    解析本体在 resolver 自有 scope 跑分层竞速，见 [resolveFromSources]。
        val deferred: Deferred<ArtistImage?> = synchronized(resolving) {
            resolving.getOrPut(name) {
                val gate = CompletableDeferred<ArtistImage?>()
                scope.launch {
                    val result = try {
                        resolveFromSources(name, personOnly)
                    } catch (e: CancellationException) {
                        gate.complete(null)  // 别让同名 await 者永远挂起
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.d("AIM", "resolve $name error: $e")
                        gate.complete(null)
                        null
                    }
                    gate.complete(result)
                    synchronized(resolving) { if (resolving[name] === gate) resolving.remove(name) }
                }
                gate
            }
        }
        return deferred.await()
    }

    /** v1.2.1: 分层竞速主体。photo 层（人物照语义）先并行抢，赢了直接用；全空才跑 album 层
     *  （iTunes/Deezer 专辑封面）兜底，personOnly 时连 album 层都不试。整名全空 → 短失败锁。
     *  旧实现全源混跑纯比速度：iTunes 单请求最快、总在 1s 内抢跑胜出，sources 里配置的
     *  优先级对谁胜出毫无影响，写真位常被无关专辑封面顶替——本轮修复的主因。 */
    private suspend fun resolveFromSources(name: String, personOnly: Boolean): ArtistImage? {
        val started = System.currentTimeMillis()
        val (photoSources, albumSources) = ArtistImageSources.enabledSourcesByTier()
        raceGroup(photoSources, name, personOnly)?.let { return cacheAndReturn(name, it, started) }
        if (personOnly) {
            android.util.Log.d("AIM", "photo tier exhausted (personOnly) → null ${System.currentTimeMillis() - started}ms")
            // v1.3.5: 不写失败锁——personOnly 只是头像场景的窄查询(跳过专辑兜底),
            // photo 层搜空不代表整名失败;写了会把已有的 album 兜底缓存覆盖成失败行,
            // 歌手页大写真跟着消失(用户反馈"写真明明存在却没了"的一半根因)。
            return null
        }
        raceGroup(albumSources, name, personOnly)?.let { return cacheAndReturn(name, it, started) }
        android.util.Log.d("AIM", "all tiers exhausted → cache failure ${FAILURE_TTL_MS / 1000}s (${System.currentTimeMillis() - started}ms)")
        cache.putFailure(name, started, FAILURE_TTL_MS)
        return null
    }

    /** v1.2.1: 组内并行竞速——组内所有源同时 fetch+probe，首个可用结果胜出，取消其余。
     *  组内不按 priority 串行等待（避免慢源拖累总耗时）。旧实现串行（前一个 null 才试
     *  下一个），大陆下 Wikidata/Deezer 各 3s 超时累加 → 卡顿；并行后总耗时≈最快命中源。 */
    private suspend fun raceGroup(
        sources: List<ArtistImageSource>, name: String, personOnly: Boolean,
    ): ArtistImage? {
        if (sources.isEmpty()) return null
        return coroutineScope {
            val jobs = sources.map { src ->
                async(Dispatchers.IO) {
                    // v1.2.1: 不用 runCatching(会吞 CancellationException→命中的源不协作取消、
                    // HTTP 跑到超时浪费流量)。CancellationException rethrow 让取消传播,其余异常→null。
                    try {
                        val result = src.fetch(name, personOnly) ?: return@async null
                        val (w, h) = probeDimensions(result.url) ?: (0 to 0)
                        if (w > 0 && h > 0) result.copy(width = w, height = h) else null
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
            }
            val w = raceFirstValid(jobs)
            jobs.forEach { it.cancel() }  // 有胜者后取消仍在跑的（如被墙源的 3s 超时）
            w
        }
    }

    /** 命中写缓存（含探测到的尺寸/类型）后原样返回，供 resolveFromSources 短路。 */
    private suspend fun cacheAndReturn(name: String, winner: ArtistImage, startedAt: Long): ArtistImage {
        val now = System.currentTimeMillis()
        android.util.Log.d("AIM", "resolved: ${winner.source} type=${winner.imageType} dims=${winner.width}x${winner.height} ${now - startedAt}ms")
        cache.putSuccess(
            name, winner.url, winner.source, now,
            width = winner.width, height = winner.height,
            aspectRatio = if (winner.height > 0) winner.width.toFloat() / winner.height else 0f,
            imageType = winner.imageType,
        )
        return winner
    }

    /** v1.2.1: 并行竞速——在 [deferreds] 中取首个非 null 结果返回，其余留给调用方取消。
     *  用 select 等任意一个完成即返回其值；null 则继续等下一个，全部完成仍 null → 返回 null。 */
    private suspend fun raceFirstValid(
        deferreds: List<kotlinx.coroutines.Deferred<ArtistImage?>>,
    ): ArtistImage? {
        val pending = deferreds.toMutableList()
        while (pending.isNotEmpty()) {
            val res = kotlinx.coroutines.selects.select<Pair<Int, ArtistImage?>> {
                pending.forEachIndexed { i, d ->
                    d.onAwait { value -> i to value }
                }
            }
            pending.removeAt(res.first)
            if (res.second != null) return res.second
        }
        return null
    }

    /** 用户手动选择写真（写 override，立即生效；自动源永不覆盖）。 */
    suspend fun setOverride(name: String, url: String) =
        override.set(name, url, System.currentTimeMillis())

    /** 清除手选，回退到自动源。 */
    suspend fun clearOverride(name: String) = override.clear(name)

    /** 探测图片真实宽高（只读头部，不解码像素）。失败返回 null（不影响 URL 缓存）。
     *  v1.3.3b review#B9: internal——picker(MainViewModel)也用它预检无尺寸元数据的
     *  候选(百度 middleURL 等),失效链接在入候选列表前就滤掉,不再等用户选完才发现头像空白。 */
    internal suspend fun probeDimensions(url: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", ImageHttp.UA)
            // Discogs 图片 CDN 可能检查 Referer，加一个匹配 discogs.com 的 Referer 绕过
            if (url.contains("discogs.com")) req.header("Referer", "https://www.discogs.com/")
            ImageHttp.client.newCall(req.build()).execute().body?.byteStream()?.use { input ->
                val opts = android.graphics.BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeStream(input, null, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
            }
        } catch (_: Exception) { null }
    }

    companion object {
        /** 死源失败后短期不重试，避免每次开歌手页干等。
         *  v1.2.0 #6: 原 6h 太长——VPN/切换网络后源可恢复，但旧失败缓存挡住重试(实测)，改 5min。
         *  v1.2.1: 再砍到 60s。分层竞速修掉"快源抢跑假失败"后，整名失败锁的副作用
         *  （网络抖动/源临时 5xx 也锁 5min，中文歌手本就命中难）远大于收益；
         *  60s 足够防开页干等，网络恢复后很快能重试。 */
        private const val FAILURE_TTL_MS = 60_000L
    }
}

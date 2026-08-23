package com.shiyin.music.data.image

import com.shiyin.music.data.db.ShiyinDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Collections

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
 *  3. 自动源（[ArtistImageSources]，按可达性排序，首个命中即停）：
 *     Discogs → Fanart → Last.fm → MB→Wikidata → Deezer → iTunes(仅 !personOnly)。
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
    private val resolving = Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun resolve(name: String, personOnly: Boolean = false): ArtistImage? {
        // 1. 用户手选（最高优先级）
        override.get(name)?.let {
            android.util.Log.d("AIM", "override hit")
            return ArtistImage(it, "override")
        }

        val now = System.currentTimeMillis()
        val cached = cache.get(name)
        // 2. 缓存有效 URL → 直接返回（带缓存的元数据）
        if (cached != null && cached.isValid(now)) {
            android.util.Log.d("AIM", "cache valid: ${cached.source} ${cached.url.take(60)}")
            return ArtistImage(cached.url, cached.source, cached.imageType, cached.width, cached.height)
        }
        //    仍在失败 TTL 内 → 不重试，返回 null
        if (cached != null && cached.isFailureFresh(now)) {
            android.util.Log.d("AIM", "cache failure-fresh (skip until ${cached.failUntilTs - now}ms)")
            return null
        }

        // 3. 自动源（重入保护：同歌手并发只跑一次）
        if (!resolving.add(name)) {
            android.util.Log.d("AIM", "already resolving $name")
            return null
        }
        return try {
            var lastSource = ""
            for (source in ArtistImageSources.sources) {
                val result = source.fetch(name, personOnly)
                lastSource = source.key
                if (result == null) continue
                val (w, h) = probeDimensions(result.url) ?: (0 to 0)
                if (w > 0 && h > 0) {
                    android.util.Log.d("AIM", "resolved: ${result.source} dims=${w}x${h}")
                    cache.putSuccess(
                        name, result.url, result.source, now,
                        width = w, height = h,
                        aspectRatio = if (h > 0) w.toFloat() / h else 0f,
                        imageType = result.imageType,
                    )
                    return result.copy(width = w, height = h)
                }
                android.util.Log.d("AIM", "src ${result.source} img unreachable(dims=0)→next")
            }
            android.util.Log.d("AIM", "all sources img unreachable(last=$lastSource)→cache failure 6h")
            cache.putFailure(name, now, FAILURE_TTL_MS)
            null
        } finally {
            resolving.remove(name)
        }
    }

    /** 用户手动选择写真（写 override，立即生效；自动源永不覆盖）。 */
    suspend fun setOverride(name: String, url: String) =
        override.set(name, url, System.currentTimeMillis())

    /** 清除手选，回退到自动源。 */
    suspend fun clearOverride(name: String) = override.clear(name)

    /** 探测图片真实宽高（只读头部，不解码像素）。失败返回 null（不影响 URL 缓存）。 */
    private suspend fun probeDimensions(url: String): Pair<Int, Int>? = withContext(Dispatchers.IO) {
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
        /** 死源失败后短期不重试，避免每次开歌手页干等。 */
        private const val FAILURE_TTL_MS = 6 * 3600_000L
    }
}

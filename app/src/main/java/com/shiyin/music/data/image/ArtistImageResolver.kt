package com.shiyin.music.data.image

import com.shiyin.music.data.db.ShiyinDao
import java.util.Collections

/** 解析结果：图片 URL + 来源标签（discogs/lastfm/wikidata/deezer/itunes/override）。 */
data class ArtistImage(val url: String, val source: String)

/**
 * v1.2.0 #6: 歌手写真解析器。独立数据层，与 album_art_cache 分离。
 *
 * 解析优先级：
 *  1. [ArtistImageOverride] 用户手选 → 永久最高，自动源永不覆盖。
 *  2. [ArtistImageCache] 本地缓存（有效 URL 直接返回；近期失败 TTL 内跳过重试）。
 *  3. 自动源（[ArtistImageSources]，按可达性排序，首个命中即停）：
 *     - Discogs   ✅ 可达 / 无需 key / 人物照        （主源）
 *     - Last.fm   ✅ 可达(需 API key) / 人物照        （BuildConfig.LASTFM_API_KEY 缺省则跳过）
 *     - MB→Wikidata ❌ 本机被墙(快失败) / 便携        （换网络可恢复）
 *     - Deezer    ❌ 本机被墙(快失败) / 便携           （换网络可恢复）
 *     - iTunes    ✅ 可达 / 专辑封面(非人物) / 仅 !personOnly 兜底
 *  4. 都没有 → null（UI 走占位渐变）。
 *
 * 命中 Discogs 后不触达被墙源；缓存 + 6h 失败 TTL 保证每个歌手至多触网一次。
 * 持久化在 Room（artist_image_cache / artist_image_override），扫描/更新不丢、自动源不覆盖手选。
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
            val result = ArtistImageSources.fetch(name, personOnly)
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

    companion object {
        /** 死源失败后短期不重试，避免每次开歌手页干等。 */
        private const val FAILURE_TTL_MS = 6 * 3600_000L
    }
}

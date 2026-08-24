package com.shiyin.music.data.image

import com.shiyin.music.data.db.ArtistImageCacheEntity
import com.shiyin.music.data.db.ShiyinDao

/**
 * v1.2.0 #6: 歌手写真自动源结果的本地缓存（独立于 album_art_cache）。
 *
 * 行为：
 *  - [putSuccess] 记录命中 URL + 来源，永久保留。
 *  - [putFailure] 记录"所有自动源都失败"+ TTL，TTL 内 [ArtistImageCacheEntity.isFailureFresh]
 *    返回 true，避免每次开歌手页都对死源重试干等。
 *
 * 持久化在 Room（artist_image_cache 表），扫描音乐/应用更新都不影响（独立表，
 * ensureArtistEntities 只写 artist 表，不碰本表）。
 */
class ArtistImageCache(private val dao: ShiyinDao) {

    suspend fun get(name: String): ArtistImageCacheEntity? = dao.artistImageCache(name)

    suspend fun putSuccess(
        name: String, url: String, source: String, now: Long,
        width: Int = 0, height: Int = 0, aspectRatio: Float = 0f, imageType: String = "",
    ) {
        dao.upsertArtistImageCache(ArtistImageCacheEntity(
            name = name, url = url, source = source, fetchedAt = now, failUntilTs = 0L,
            width = width, height = height, aspectRatio = aspectRatio, imageType = imageType,
        ))
    }

    suspend fun putFailure(name: String, now: Long, ttlMs: Long) {
        // url 留空标记"失败"；source=fail 便于诊断
        dao.upsertArtistImageCache(ArtistImageCacheEntity(name, "", "fail", now, now + ttlMs))
    }

    suspend fun clear(name: String) = dao.deleteArtistImageCache(name)
}

/** 命中了已验证可用的 URL（非空 + 有尺寸）→ 可直接用，无需触网。
 *  仅 url 非空但 w/h=0 视为"未验证/旧缓存"，不直接信任——走重新解析（新源 AudioDB 能接上）。 */
fun ArtistImageCacheEntity.isValid(now: Long): Boolean = url.isNotBlank() && width > 0 && height > 0

/** 自动源近期全失败且仍在 TTL 内 → 跳过重试（返回占位图），避免开页干等。 */
fun ArtistImageCacheEntity.isFailureFresh(now: Long): Boolean = url.isBlank() && failUntilTs > now

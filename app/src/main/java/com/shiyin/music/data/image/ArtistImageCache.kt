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

    suspend fun putSuccess(name: String, url: String, source: String, now: Long) {
        dao.upsertArtistImageCache(ArtistImageCacheEntity(name, url, source, now, 0L))
    }

    suspend fun putFailure(name: String, now: Long, ttlMs: Long) {
        // url 留空标记"失败"；source=fail 便于诊断
        dao.upsertArtistImageCache(ArtistImageCacheEntity(name, "", "fail", now, now + ttlMs))
    }

    suspend fun clear(name: String) = dao.deleteArtistImageCache(name)
}

/** 命中了有效 URL（非空）→ 可直接用，无需触网。 */
fun ArtistImageCacheEntity.isValid(now: Long): Boolean = url.isNotBlank()

/** 自动源近期全失败且仍在 TTL 内 → 跳过重试（返回占位图），避免开页干等。 */
fun ArtistImageCacheEntity.isFailureFresh(now: Long): Boolean = url.isBlank() && failUntilTs > now

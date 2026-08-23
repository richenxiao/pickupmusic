package com.shiyin.music.data.image

import com.shiyin.music.data.db.ArtistImageOverrideEntity
import com.shiyin.music.data.db.ShiyinDao

/**
 * v1.2.0 #6: 用户手动选择的歌手写真（永久最高优先级）。
 *
 * 持久化在 Room（artist_image_override 表）。和专辑封面手选一样：
 *  - 应用更新不影响（独立表，DB 迁移保留）。
 *  - 扫描音乐不影响（ensureArtistEntities 不碰本表）。
 *  - 自动匹配/自动源永不覆盖——[ArtistImageResolver.resolve] 先查 override，
 *    命中即返回，根本不触发自动源写入。
 */
class ArtistImageOverride(private val dao: ShiyinDao) {

    suspend fun get(name: String): String? = dao.artistImageOverride(name)?.url

    suspend fun set(name: String, url: String, now: Long) {
        dao.upsertArtistImageOverride(ArtistImageOverrideEntity(name, url, now))
    }

    suspend fun clear(name: String) = dao.deleteArtistImageOverride(name)
}

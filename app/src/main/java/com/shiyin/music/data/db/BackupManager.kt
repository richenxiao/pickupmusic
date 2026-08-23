package com.shiyin.music.data.db

import com.google.gson.Gson
import com.shiyin.music.data.db.AlbumOverrideEntity as ALO
import com.shiyin.music.data.db.AlbumInfoOverrideEntity as AIO
import com.shiyin.music.data.db.TrackInfoOverrideEntity as TIO
import com.shiyin.music.data.db.TrackAlbumMoveEntity as TAM
import com.shiyin.music.data.db.ArtistEntity as ART
import com.shiyin.music.data.db.SongArtistEntity as SA
import com.shiyin.music.data.db.ReadingOverrideEntity as RO
import com.shiyin.music.data.db.ExternalReadingEvidenceEntity as ERE
import com.shiyin.music.data.db.ArtistImageCacheEntity as AIC
import com.shiyin.music.data.db.ArtistImageOverrideEntity as AIOV

/**
 * v1.2.0 阶段三：用户修正数据的导出/导入。
 *
 * 导出的"修正数据"= 用户手动编辑、不可由重新扫描重建的数据：
 *  - album_override / album_info_override / track_info_override / track_album_move
 *    （专辑名·艺术家·封面·type 修正、单曲 title/artist/note/隐藏、单曲迁专辑）
 *  - artist（歌手合并 aliases + 头像）/ song_artist（曲目-歌手关联）
 *  - reading_override（振假名当て字）/ external_reading_evidence（外部假名 evidence）
 *  - artist_image_override（歌手手选写真，永久用户资产）/ artist_image_cache（自动源命中缓存，
 *    仅导出成功行，避免重抓；跨设备不导出失败 TTL 态以免毒到新网络）
 *
 * 不导出：saved_lyrics（歌词本体，量大且可重新识别）、album_art_cache（取色缓存，
 * 可重算）、play_count/play_event（播放统计，非修正）、playlist*（歌单，非修正）、
 * new_album/ignored_folder/trashed_track（运行态，非修正）。
 *
 * artist 跨设备导入：id 是自增，跨设备会撞；按 name 去重合并（已有则合 aliases/头像，
 * 无则插入新行），song_artist 的 artistId 重映射到本地新 id。
 */
internal class BackupManager(private val dao: ShiyinDao) {

    data class BackupData(
        val version: Int = 1,
        val app: String = "com.shiyin.music",
        val exportedAt: Long,
        val albumOverride: List<ALO>,
        val albumInfoOverride: List<AIO>,
        val trackInfoOverride: List<TIO>,
        val trackAlbumMove: List<TAM>,
        val artists: List<ART>,
        val songArtist: List<SA>,
        val readingOverride: List<RO>,
        val externalReadingEvidence: List<ERE>,
        // v1.2.0 #6: 歌手写真——用户手选 override 是永久资产；cache 成功行随附避免重抓。
        val artistImageCache: List<AIC>,
        val artistImageOverride: List<AIOV>,
    )

    /** 导出全部修正数据为 JSON 字符串。调用方负责写入 SAF/文件。 */
    suspend fun export(): String {
        val data = BackupData(
            exportedAt = System.currentTimeMillis(),
            albumOverride = dao.allAlbumOverride(),
            albumInfoOverride = dao.allAlbumInfoOverride(),
            trackInfoOverride = dao.allTrackInfoOverride(),
            trackAlbumMove = dao.allTrackAlbumMove(),
            artists = dao.allArtists(),
            songArtist = dao.allSongArtist(),
            readingOverride = dao.allReadingOverride(),
            externalReadingEvidence = dao.allExternalReadingEvidence(),
            artistImageCache = dao.allArtistImageCacheSuccess(),
            artistImageOverride = dao.allArtistImageOverride(),
        )
        return Gson().toJson(data)
    }

    /** 从 JSON 导入。artist 按 name 合并，song_artist 重映射 id。
     *  返回导入的各表条目数统计。 */
    suspend fun import(json: String): ImportStats {
        val data = Gson().fromJson(json, BackupData::class.java)
            ?: error("备份文件格式无法解析")
        // 简单表：直接批量 upsert（主键不依赖自增 id）
        dao.upsertAllAlbumOverride(data.albumOverride)
        dao.upsertAllAlbumInfoOverride(data.albumInfoOverride)
        dao.upsertAllTrackInfoOverride(data.trackInfoOverride)
        dao.upsertAllTrackAlbumMove(data.trackAlbumMove)
        dao.upsertAllReadingOverride(data.readingOverride)
        dao.upsertAllExternalReadingEvidence(data.externalReadingEvidence)
        // artist：按 name 合并，建 备份id → 本地id 映射
        val idMap = HashMap<Int, Int>() // backup artistId -> local artistId
        for (a in data.artists) {
            val local = dao.artistByName(a.name)
            val localId = if (local != null) {
                // 合并 aliases（取并集去重）+ 头像（本地空则用备份）
                val mergedAliases = mergeAliases(local.aliases, a.aliases)
                val avatar = local.avatarUrl.ifBlank { a.avatarUrl }
                val avatarSrc = local.avatarSource.ifBlank { a.avatarSource }
                dao.upsertArtist(local.copy(
                    aliases = mergedAliases,
                    avatarUrl = avatar,
                    avatarSource = avatarSrc,
                    updatedAt = maxOf(local.updatedAt, a.updatedAt),
                ))
                local.id
            } else {
                // 新艺术家，插入（id 自增），取返回的 id
                dao.insertArtistGetId(a.copy(id = 0)).toInt()
            }
            idMap[a.id] = localId
        }
        // song_artist：用映射后的 artistId 批量插入
        val remapped = data.songArtist.map { it.copy(artistId = idMap[it.artistId] ?: it.artistId) }
            .filter { idMap[it.artistId] != null } // 仅导入成功映射的
        if (remapped.isNotEmpty()) dao.upsertAllSongArtist(remapped)
        // 歌手写真：override(用户手选) + cache 成功行。PK=name，无自增 id，直接 upsert。
        // orEmpty 兼容旧备份（无这两个字段→null→空，跳过）。
        val imgCache = data.artistImageCache.orEmpty()
        val imgOverride = data.artistImageOverride.orEmpty()
        if (imgCache.isNotEmpty()) dao.upsertAllArtistImageCache(imgCache)
        if (imgOverride.isNotEmpty()) dao.upsertAllArtistImageOverride(imgOverride)
        return ImportStats(
            albumOverride = data.albumOverride.size,
            albumInfoOverride = data.albumInfoOverride.size,
            trackInfoOverride = data.trackInfoOverride.size,
            trackAlbumMove = data.trackAlbumMove.size,
            artists = data.artists.size,
            songArtist = remapped.size,
            readingOverride = data.readingOverride.size,
            externalReadingEvidence = data.externalReadingEvidence.size,
            artistImageCache = imgCache.size,
            artistImageOverride = imgOverride.size,
        )
    }

    /** 合并两个 aliases 串（逗号分隔），并集去重。空串→空 list。 */
    private fun mergeAliases(a: String, b: String): String {
        val set = (a.split(",").map { it.trim() }.filter { it.isNotEmpty() } +
                   b.split(",").map { it.trim() }.filter { it.isNotEmpty() }).toSet()
        return set.joinToString(",")
    }

    data class ImportStats(
        val albumOverride: Int,
        val albumInfoOverride: Int,
        val trackInfoOverride: Int,
        val trackAlbumMove: Int,
        val artists: Int,
        val songArtist: Int,
        val readingOverride: Int,
        val externalReadingEvidence: Int,
        val artistImageCache: Int,
        val artistImageOverride: Int,
    ) {
        override fun toString() =
            "专辑迁移$albumOverride / 专辑修正$albumInfoOverride / 曲目修正$trackInfoOverride / " +
            "曲目迁专辑$trackAlbumMove / 歌手$artists / 曲目-歌手$songArtist / " +
            "振假名注音$readingOverride / 外部假名$externalReadingEvidence / " +
            "歌手写真缓存$artistImageCache / 歌手写真手选$artistImageOverride"
    }
}

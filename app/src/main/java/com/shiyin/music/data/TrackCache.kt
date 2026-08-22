package com.shiyin.music.data

import android.content.Context
import android.provider.MediaStore
import com.google.gson.Gson
import java.io.File

/**
 * v1.2.0 阶段二：tracksRaw 的磁盘持久化，配合 [MediaScanner.signature] 实现增量扫描。
 *
 * 冷启动时若签名（MediaStore 音频总条数 + max date_added）与上次相同 → 库无增删 →
 * 直接反序列化上次的 tracksRaw，跳过全量 MediaStore.query 的逐行 Track 构造。
 * 签名不同 → 全量 [MediaScanner.scan] + 落盘新 tracksRaw + 更新签名。
 *
 * Uri 处理：Track.uri = ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, id)，
 * 由 id 完全决定，故序列化用无 uri 字段的 [TrackSer] 中间体，反序列化后用 id 重建。
 */
internal class TrackCache(context: Context) {
    private val file = File(context.filesDir, "tracks_cache.json")
    private val sigFile = File(context.filesDir, "tracks_cache_sig.txt")
    private val gson = Gson()

    /** 存的签名（"count|maxDateAdded"）。null = 无缓存。 */
    fun readSignature(): String? = if (sigFile.exists()) sigFile.readText().takeIf { it.isNotBlank() } else null

    fun writeSignature(sig: String) = runCatching { sigFile.writeText(sig) }

    fun read(): List<Track>? = runCatching {
        if (!file.exists()) return null
        val ser = gson.fromJson(file.readText(), Array<TrackSer>::class.java) ?: return null
        ser.map { it.toTrack() }
    }.getOrNull()

    fun write(tracks: List<Track>) = runCatching {
        file.writeText(gson.toJson(tracks.map { TrackSer.fromTrack(it) }.toTypedArray()))
    }

    /** 清除（清理识别缓存时与 ArtCache.clearAll 一起调）。 */
    fun clear() = runCatching { file.delete(); sigFile.delete() }

    /** 可序列化中间体：去掉 uri（由 id 重建），其余字段与 Track 一一对应。 */
    private data class TrackSer(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val trackNo: Int,
        val durationMs: Long,
        val sizeBytes: Long,
        val folder: String,
        val dateAdded: Long,
        val albumId: Long,
        val dataPath: String,
    ) {
        fun toTrack() = Track(
            id = id,
            uri = android.content.ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
            ),
            title = title, artist = artist, album = album, trackNo = trackNo,
            durationMs = durationMs, sizeBytes = sizeBytes, folder = folder,
            dateAdded = dateAdded, albumId = albumId, dataPath = dataPath,
        )
        companion object {
            fun fromTrack(t: Track) = TrackSer(
                t.id, t.title, t.artist, t.album, t.trackNo,
                t.durationMs, t.sizeBytes, t.folder, t.dateAdded, t.albumId, t.dataPath,
            )
        }
    }
}

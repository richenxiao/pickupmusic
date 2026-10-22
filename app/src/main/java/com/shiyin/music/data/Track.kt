package com.shiyin.music.data

import android.net.Uri

const val UNKNOWN_ARTIST = "未知艺术家"
const val NO_ALBUM = "—"

data class Track(
    val id: Long,
    val uri: Uri,
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
    /**
     * v1.5.0 A62：专辑作者（MediaStore ALBUM_ARTIST）。用于判断"客串歌手"——
     * 只在某首歌里 feat、专辑作者里没有他，就不占歌手栏位。空串=该文件没写这个标签。
     */
    val albumArtist: String = "",
) {
    val durationSec: Long get() = durationMs / 1000
    val initial: String get() = title.firstOrNull()?.uppercase() ?: "♪"
    /** Stable palette slot for the generative cover placeholder (id mod 5, per handoff). */
    val paletteIndex: Int get() = (id % 5).toInt()
}

fun albumKeyOf(album: String, artist: String, albumId: Long = 0): String {
    if (albumId > 0) return "aid:$albumId"
    return "$album|$artist" // fallback for tracks without albumId
}

/**
 * name-keyed 专辑（曲目没有 MediaStore albumId，key 形如 `"$album|$artist"`）的
 * **覆盖记录合成 id**。
 *
 * album_info_override / 封面覆盖表以 Long albumId 为主键，而这类专辑没有真实
 * albumId —— 旧实现传入 0 后三个入口全都 `return` 静默失败（用户 2026-10-22 报
 * 「更换封面一点反应都没有、没有一点提示」）。这里用 key 的稳定 64 位散列取**负值**
 * （真实 MediaStore albumId 恒为正，永不相撞），写入/读取两侧走同一函数即可对上。
 *
 * `aid:` 形式的 key 直接解析出真实 id。
 */
fun overrideIdForAlbumKey(key: String): Long {
    if (key.startsWith("aid:")) return key.removePrefix("aid:").toLongOrNull() ?: 0L
    if (key.isEmpty()) return 0L
    var h = 1125899906842597L
    for (c in key) h = 31 * h + c.code
    val v = h xor (h ushr 33)
    return -kotlin.math.abs(if (v == 0L) 1L else v).coerceAtLeast(1L)
}

fun formatDuration(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

fun formatSizeMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 100) "%.0f MB".format(mb) else "%.1f MB".format(mb)
}

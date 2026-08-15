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

fun formatDuration(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

fun formatSizeMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 100) "%.0f MB".format(mb) else "%.1f MB".format(mb)
}

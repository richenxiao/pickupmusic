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
 * v1.5.0：各专辑的**原生显示名**（单曲迁移改名修复）。
 *
 * 单曲迁移只改 albumId、不改曲目自带的专辑名标签——迁入曲带着旧标签进组，
 * 专辑格按组内第一首取名就可能被顶成迁入曲的旧专辑名（真机实测：一首从
 * 合辑迁进正式专辑的单曲，让专辑格显示了合辑名；点进详情页按曲目号排序
 * 取第一首才是正确的专辑名——两处口径不一致）。
 *
 * 这里用**原生归属该 albumId、且未被迁走**的曲目投票取名（并列时先到先得），
 * 作为该专辑的权威显示名；迁入曲的显示名改由它回填。整专被迁空的 albumId
 * 没有原生曲目可投票，不出现在结果里（迁入曲保留自己的标签名）。
 */
fun nativeAlbumNames(tracks: List<Track>, moves: Map<Long, Long>): Map<Long, String> {
    if (moves.isEmpty()) return emptyMap()
    val counts = LinkedHashMap<Long, LinkedHashMap<String, Int>>()
    for (t in tracks) {
        if (t.albumId <= 0 || t.album.isBlank() || t.album == NO_ALBUM) continue
        if (moves[t.id]?.let { it != t.albumId } == true) continue // 已迁出，不投票
        counts.getOrPut(t.albumId) { LinkedHashMap() }.merge(t.album, 1, Int::plus)
    }
    return counts.mapValues { (_, m) -> m.maxByOrNull { it.value }!!.key }
}

/**
 * name-keyed 专辑（曲目没有 MediaStore albumId，key 形如 `"$album|$artist"`）的
 * **覆盖记录合成 id**。
 *
 * album_info_override / 封面覆盖表以 Long albumId 为主键，而这类专辑没有真实
 * albumId —— 旧实现传入 0 后三个入口全都 `return` 静默失败（真机报障
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

package com.shiyin.music.ui.screens

import com.shiyin.music.data.Track
import com.shiyin.music.data.db.AlbumArtCacheEntity

/**
 * Classification of an album based on track count and total duration.
 * Single: 1-3 tracks
 * EP: 4-6 tracks, total duration ≤ 30 minutes
 * Album: 7+ tracks, or total duration > 30 minutes
 * Compilation (合集): 多歌手杂烩——同文件夹/同专辑名的多首不同歌手单曲被
 *   MediaStore 归到一张「专辑」，实为合集而非真正专辑。v1.2.0 #3 新增，且不在
 *   歌手页展示（不属于任一歌手）。
 */
enum class AlbumCategory { Single, EP, Album, Compilation }

/**
 * A classified album with its metadata.
 */
data class ClassifiedAlbum(
    val key: String,
    val tracks: List<Track>,
    val category: AlbumCategory,
    val firstTrack: Track,
    val trackCount: Int,
    val totalMinutes: Int,
    val releaseDate: String,  // ISO date string, empty if unknown
)

/**
 * Classify a single album's tracks into a category.
 *
 * @param overrideType Optional manual override stored in album_info_override.type
 *   ("Album" / "EP" / "Single"). When non-blank it wins outright — the heuristic
 *   is skipped. Needed for albums the user only partially downloaded (the count/
 *   duration heuristic would misread e.g. a 2-track album as 单曲). Blank → infer.
 */
fun classifyAlbum(tracks: List<Track>, overrideType: String? = null): AlbumCategory {
    overrideType?.takeIf { it.isNotBlank() }?.let { return parseCategory(it) }
    val count = tracks.size
    val totalMin = (tracks.sumOf { it.durationSec } / 60).toInt()
    // v1.2.0 #3: 合集优先判——多歌手杂烩单曲文件夹（distinct 歌手≥3 且过半、且≥4 首）
    // 判为 Compilation，不被曲目数误判为 Album。判在 Single/EP/Album 之前。
    if (isCompilationAlbum(tracks.map { it.artist }.distinct().size, count)) {
        return AlbumCategory.Compilation
    }
    return when {
        count in 1..3 -> AlbumCategory.Single
        count in 4..6 && totalMin <= 30 -> AlbumCategory.EP
        else -> AlbumCategory.Album
    }
}

/**
 * v1.2.0 #3: 合集启发式——多歌手杂烩单曲文件夹判为 Compilation。
 * distinctArtists = 不同歌手串数；trackCount = 曲目数。满足「≥4 首 + ≥3 个不同歌手
 * + 不同歌手过半」判合集。避免误伤：真正专辑即便有 feat. 合作，distinct 也远不过半。
 */
internal fun isCompilationAlbum(distinctArtists: Int, trackCount: Int): Boolean =
    trackCount >= 4 && distinctArtists >= 3 && distinctArtists.toFloat() / trackCount >= 0.5f

/** Map a stored type string to its AlbumCategory, tolerant of case/variants.
 *  Unknown values fall back to the track-count heuristic by returning whatever
 *  classifyAlbum([]) yields (Single for 0 tracks) — callers should pass real
 *  tracks for a sensible fallback. */
private fun parseCategory(type: String): AlbumCategory = when (type.trim().lowercase()) {
    "album", "专辑" -> AlbumCategory.Album
    "ep" -> AlbumCategory.EP
    "single", "单曲" -> AlbumCategory.Single
    "compilation", "合集" -> AlbumCategory.Compilation
    else -> AlbumCategory.Album
}

/**
 * Classify all albums and return them as a flat list grouped by category.
 * @param albums Map of albumKey -> tracks
 * @param releaseDates Map of albumId -> releaseDate ISO string (from album_art_cache)
 * @param newestFirst If true, sort by releaseDate descending (newest first)
 * @param typeOverrides Map of albumId -> manual type override ("Album"/"EP"/"Single"),
 *   sourced from album_info_override.type. Per-album override wins over the heuristic.
 */
fun classifyAlbums(
    albums: Map<String, List<Track>>,
    releaseDates: Map<Long, String> = emptyMap(),
    newestFirst: Boolean = true,
    typeOverrides: Map<Long, String> = emptyMap(),
): List<ClassifiedAlbum> {
    return albums.entries.map { (key, tracks) ->
        val first = tracks.first()
        val releaseDate = if (first.albumId > 0) {
            releaseDates[first.albumId] ?: ""
        } else ""
        ClassifiedAlbum(
            key = key,
            tracks = tracks,
            category = classifyAlbum(tracks, first.albumId.takeIf { it > 0 }?.let { typeOverrides[it] }),
            firstTrack = first,
            trackCount = tracks.size,
            totalMinutes = (tracks.sumOf { it.durationSec } / 60).toInt(),
            releaseDate = releaseDate,
        )
    }.let { all ->
        // Split dated vs undated so albums without an iTunes releaseDate always
        // sink to the bottom in BOTH sort directions. Otherwise, in "最早优先"
        // (compareBy), empty strings ("") would sort first and flood the top of
        // the list with undated albums — misleading. ISO 8601 strings sort
        // lexicographically in chronological order, so compareBy* on the raw
        // string is correct for the dated group.
        val dated = all.filter { it.releaseDate.isNotBlank() }
        val undated = all.filter { it.releaseDate.isBlank() }
        val datedSorted = dated.sortedWith(
            if (newestFirst) compareByDescending<ClassifiedAlbum> { it.releaseDate }
            else compareBy<ClassifiedAlbum> { it.releaseDate }
            .thenBy { it.firstTrack.album }
            .thenBy { it.key }
        )
        val undatedSorted = undated.sortedBy { it.firstTrack.album }
        datedSorted + undatedSorted
    }
}

/**
 * Chinese label for an album category.
 */
fun categoryLabel(category: AlbumCategory): String = when (category) {
    AlbumCategory.Single -> "单曲"
    AlbumCategory.EP -> "EP"
    AlbumCategory.Album -> "专辑"
    AlbumCategory.Compilation -> "合集"
}

/**
 * Build a map of albumId -> releaseDate from the DAO's allArtCache() result.
 */
fun buildReleaseDateMap(cache: List<AlbumArtCacheEntity>): Map<Long, String> {
    return cache.associate { it.albumId to it.releaseDate }
}
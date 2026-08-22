package com.shiyin.music.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Scans every audio file on the device via MediaStore (music, recordings,
 * chat voice notes alike — the handoff explicitly includes them all).
 *
 * v2.0: [forceReindex] walks known folders and asks the system to re-index
 * files before the MediaStore query, fixing the "new files never appear after
 * first scan" bug.
 */
object MediaScanner {

    val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "wv", "ape", "dsf", "dff")

    /** v3.0: Split a raw artist string into individual artist names.
     *  Handles: "/", "、", ",", "&", " feat.", " ft.", " x ", " × ", " vs ", " with ", "+",
     *  " duet with ", " presents ", " prod. ", " produced by " */
    fun splitArtists(raw: String): List<String> {
        if (raw.isBlank() || raw == UNKNOWN_ARTIST) return listOf(raw)
        val separators = listOf(
            Regex("""\s*/\s*"""),
            Regex("""\s*、\s*"""),
            Regex("""\s*,\s*"""),
            Regex("""\s*&\s*"""),
            Regex("""\s+[fF]eat\.?\s+"""),
            Regex("""\s+[fF]t\.?\s+"""),
            Regex("""\s*[xX×]\s*"""),
            Regex("""\s+[vV][sS]\.?\s+"""),
            Regex("""\s+[wW]ith\s+"""),
            Regex("""\s*[+]+\s*"""),
            Regex("""\s+[dD]uet\s+[wW]ith\s+"""),
            Regex("""\s+[pP]resents?\s+"""),
            Regex("""\s+[pP]rod\.?\s+"""),
            Regex("""\s+[pP]roduced\s+[bB]y\s+"""),
        )
        var result = listOf(raw)
        for (sep in separators) {
            result = result.flatMap { part -> part.split(sep).map { it.trim() }.filter { it.isNotBlank() } }
        }
        return result.distinct()
    }

    /**
     * v3.0 A3: Diagnose missing files by comparing filesystem listing with MediaStore results.
     * Writes the diff to a file for offline analysis.
     */
    suspend fun diagnoseMissingFiles(context: Context, folder: String, scanResult: List<Track>): String? = withContext(Dispatchers.IO) {
        try {
            val storageBase = Environment.getExternalStorageDirectory().absolutePath
            val dir = File("$storageBase/${folder.trimStart('/')}")
            if (!dir.isDirectory) return@withContext "Folder not found: $folder"

            // Get all audio files on filesystem
            val fsFiles = dir.walkTopDown().filter { f ->
                f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS
            }.map { it.absolutePath }.toSet()

            // Get all files indexed by MediaStore (from scan result)
            val msFiles = scanResult.filter { it.folder == folder }.map { it.dataPath }.toSet()

            // Files on filesystem but not in MediaStore
            val missing = fsFiles - msFiles

            if (missing.isEmpty()) {
                "Diagnostic: No missing files found for folder '$folder'\n" +
                "Filesystem: ${fsFiles.size}, MediaStore: ${msFiles.size}"
            } else {
                val sb = StringBuilder()
                sb.appendLine("=== A3 Diagnostic: Missing Files in '$folder' ===")
                sb.appendLine("Filesystem: ${fsFiles.size} files")
                sb.appendLine("MediaStore: ${msFiles.size} files")
                sb.appendLine("Missing: ${missing.size} files")
                sb.appendLine()
                for (f in missing.sorted()) {
                    val file = File(f)
                    sb.appendLine("  $f  (${file.length()} bytes, modified ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(file.lastModified()))})")
                }
                sb.appendLine()
                // Common features of missing files
                val extCounts = missing.groupBy { File(it).extension.lowercase() }.mapValues { it.value.size }
                sb.appendLine("Missing file format breakdown:")
                for ((ext, count) in extCounts) sb.appendLine("  .$ext: $count files")
                // Write to file
                val logDir = java.io.File(context.getExternalFilesDir("crash_logs") ?: context.filesDir, "..")
                val outFile = java.io.File(logDir, "diagnostic_missing_files.txt")
                java.io.FileWriter(outFile).use { w -> w.write(sb.toString()) }
                sb.toString()
            }
        } catch (e: Exception) {
            "Diagnostic error: ${e.message}"
        }
    }

    /**
     * Walks the known audio folders on the filesystem and triggers
     * [MediaScannerConnection.scanFile] on each audio file found, forcing
     * MediaStore to re-index files that it missed (e.g. files copied via MTP
     * or downloaded by apps that don't notify MediaStore).
     *
     * @param folders relative folder paths as stored in [Track.folder],
     *   e.g. "/Music", "/Download/KuwoMusic/music"
     */
    suspend fun forceReindex(context: Context, folders: Set<String>) = withContext(Dispatchers.IO) {
        if (folders.isEmpty()) return@withContext
        val storageBase = Environment.getExternalStorageDirectory().absolutePath
        val files = folders.flatMap { folder ->
            val dir = File("$storageBase/${folder.trimStart('/')}")
            if (dir.isDirectory) {
                dir.walkTopDown().filter { f ->
                    f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS
                }.map { it.absolutePath }.toList()
            } else emptyList()
        }
        if (files.isEmpty()) return@withContext
        val latch = CountDownLatch(files.size)
        MediaScannerConnection.scanFile(context, files.toTypedArray(), arrayOf("audio/*")) { _, _ ->
            latch.countDown()
        }
        // Wait up to 60 s for all files to be indexed; if it times out the
        // subsequent MediaStore query will still pick up whatever was indexed.
        latch.await(60, TimeUnit.SECONDS)
    }

    /**
     * v1.2.0 阶段二：轻量签名——MediaStore 音频总条数 + 最新一条的 date_added。
     * 用作增量扫描的变更检测：签名未变 → 库无增删 → 可复用上次持久化的
     * tracksRaw，跳过全量 [scan] 的逐行 Track 构造。只取 _ID/DATE_ADDED 两列、
     * 不构造 Track，比全量 scan 快得多（大库尤其明显）。
     *
     * 返回 "count|maxDateAdded"；查不到返回 "0|0"。
     */
    suspend fun signature(context: Context): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATE_ADDED)
        var maxDate = 0L
        var count = 0
        resolver.query(uri, proj, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
            count = c.count
            if (c.moveToFirst()) maxDate = c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
        }
        "$count|$maxDate"
    }

    suspend fun scan(
        context: Context,
        paced: Boolean = true,
        onProgress: suspend (count: Int, folder: String) -> Unit = { _, _ -> },
    ): List<Track> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val out = ArrayList<Track>()
        resolver.query(uri, projection, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iRel = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iAlbId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            // Pace progress callbacks so the onboarding ring is readable even
            // when MediaStore answers instantly ("按真实耗时推进", capped ~3s total).
            val total = c.count
            // Pacing keeps the onboarding ring readable; silent rescans skip it.
            val perItemDelay = if (paced && total > 0) (2500L / total).coerceIn(1L, 160L) else 0L
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val data = c.getString(iData) ?: ""
                val rel = c.getString(iRel) ?: ""
                val folder = folderDisplay(rel, data)
                val rawArtist = c.getString(iArtist)
                val rawAlbum = c.getString(iAlbum)
                val rawTrackNo = c.getInt(iTrack)
                out += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(uri, id),
                    title = c.getString(iTitle)?.takeIf { it.isNotBlank() }
                        ?: data.substringAfterLast('/').substringBeforeLast('.').ifBlank { "未知音频" },
                    artist = if (rawArtist.isNullOrBlank() || rawArtist == MediaStore.UNKNOWN_STRING) UNKNOWN_ARTIST else rawArtist,
                    album = if (rawAlbum.isNullOrBlank() || rawAlbum == MediaStore.UNKNOWN_STRING) NO_ALBUM else rawAlbum,
                    // MediaStore encodes CD track numbers as disc*1000+track.
                    trackNo = if (rawTrackNo > 0) rawTrackNo % 1000 else 0,
                    durationMs = c.getLong(iDur),
                    sizeBytes = c.getLong(iSize),
                    folder = folder,
                    dateAdded = c.getLong(iDate),
                    albumId = c.getLong(iAlbId),
                    dataPath = data,
                )
                onProgress(out.size, folder)
                if (perItemDelay > 0) delay(perItemDelay)
            }
        }
        out
    }

    private fun folderDisplay(relativePath: String, dataPath: String): String {
        val rel = relativePath.trim('/').trim()
        if (rel.isNotEmpty()) return "/$rel"
        val parent = File(dataPath).parent ?: return "/"
        // Strip the storage-volume prefix, e.g. /storage/emulated/0/Music -> /Music
        val idx = parent.indexOf("/emulated/0")
        return if (idx >= 0) parent.substring(idx + "/emulated/0".length).ifEmpty { "/" } else parent
    }
}

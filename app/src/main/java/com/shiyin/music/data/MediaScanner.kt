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

    /**
     * v1.5.0 P1：调试导出目录（`Download/PickUpMusic-debug/`）——里面的 WAV 永远不当歌。
     *
     * 与 `spike.karaoke.DebugExport.DIR` 是同一个目录，但**刻意不复用那个常量**：
     * `spike` 层会随正式版一起删掉，`data` 层不该反向依赖它。两边不一致时以本常量为准
     * （它是拦在曲库入口的那一道）。
     */
    private const val DEBUG_EXPORT_MARK = "Download/PickUpMusic-debug/"

    /** v3.0: Split a raw artist string into individual artist names.
     *  Handles: "/", "、", ",", "&", " feat.", " ft.", " x ", " × ", " vs ", " with ", "+",
     *  " duet with ", " presents ", " prod. ", " produced by " */
    fun splitArtists(raw: String): List<String> {
        if (raw.isBlank() || raw == UNKNOWN_ARTIST) return listOf(raw)
        // v1.2.1: 归一化全角标点→半角(共享 CharUtil)。CJK 输入法常出全角逗号"，"等,
        // 下面的分隔正则只认半角,全角"，"不会被拆分→"AAA， BBB"识别不到作者。
        val normalized = com.shiyin.music.data.normalize.CharUtil.normalizeFullWidthPunctuation(raw)
        val separators = listOf(
            Regex("""\s*/\s*"""),
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
        var result = listOf(normalized)
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
        val t0 = System.currentTimeMillis()
        // v1.5.0 B2①：只遍历「库里已知的目录 + 常见放歌位置」，存储根只扫它自己那一层。
        // 旧实现里 knownFolders 含 "/"（有歌直接躺在存储根下），walkTopDown 于是把整个手机
        // 存储走了一遍：真机实测 33s / 102,669 个文件，其中 96% 是相册与应用数据。
        val plan = ReindexPlanner.planWalk(folders)
        val storageBase = Environment.getExternalStorageDirectory().absolutePath
        fun dirOf(folder: String) = File("$storageBase/${folder.trimStart('/')}")
        fun isAudio(f: File) = f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS

        val files = plan.recursive.flatMap { folder ->
            val dir = dirOf(folder)
            if (dir.isDirectory) {
                dir.walkTopDown().filter { isAudio(it) }.map { it.absolutePath }.toList()
            } else emptyList()
        } + plan.shallow.flatMap { folder ->
            // 存储根本身只扫这一层；它的子目录由 recursive 计划（含 CURATED_DIRS）覆盖。
            dirOf(folder).listFiles()?.filter { isAudio(it) }?.map { it.absolutePath } ?: emptyList()
        }
        val tWalk = System.currentTimeMillis()
        if (files.isEmpty()) {
            android.util.Log.d("ScanTrace", "forceReindex: files=0 walk=${tWalk - t0}ms → no-op")
            return@withContext
        }
        // v1.5.0 B2①：只对 MediaStore 尚未收录的文件调 scanFile。真机实测 4090 个文件里
        // 2066 个早已收录，重扫它们只是让 MediaProvider 重新解析一遍标签 → 白等 12s。
        val indexed = indexedAudioPaths(context)
        val todo = ReindexPlanner.filesNeedingReindex(files, indexed)
        val tFilter = System.currentTimeMillis()
        android.util.Log.d(
            "ScanTrace",
            "forceReindex: known=${folders.size} roots=${plan.recursive.size} shallow=${plan.shallow.size} " +
                "files=${files.size} indexed=${indexed.size} " +
                "toScan=${todo.size} walk=${tWalk - t0}ms filter=${tFilter - tWalk}ms"
        )
        if (todo.isEmpty()) {
            android.util.Log.d("ScanTrace", "forceReindex: nothing to re-index TOTAL=${System.currentTimeMillis() - t0}ms")
            return@withContext
        }
        val latch = CountDownLatch(todo.size)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val tDispatch0 = System.currentTimeMillis()
        MediaScannerConnection.scanFile(context, todo.toTypedArray(), arrayOf("audio/*")) { _, _ ->
            done.incrementAndGet()
            latch.countDown()
        }
        val tDispatch = System.currentTimeMillis()
        // Wait up to 60 s for all files to be indexed; if it times out the
        // subsequent MediaStore query will still pick up whatever was indexed.
        val completed = latch.await(60, TimeUnit.SECONDS)
        val tEnd = System.currentTimeMillis()
        android.util.Log.d(
            "ScanTrace",
            "forceReindex: toScan=${todo.size} dispatch=${tDispatch - tDispatch0}ms wait=${tEnd - tDispatch}ms " +
                "completed=$completed callbacks=${done.get()}/${todo.size} TOTAL=${tEnd - t0}ms"
        )
    }

    /**
     * v1.5.0 B2①：MediaStore 已收录的音频绝对路径集合（DATA 列）。
     * 单列全表查询本身很轻，远快于 walkTopDown + 对每个文件逐个 scanFile 等回调。
     */
    private fun indexedAudioPaths(context: Context): Set<String> {
        val out = HashSet<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA),
                null, null, null,
            )?.use { c ->
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    c.getString(iData)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                }
            }
        }
        return out
    }

    /**
     * v1.2.0 阶段二：轻量签名——MediaStore 音频总条数 + 最新一条的 date_added。
     * 用作增量扫描的变更检测：签名未变 → 库无增删 → 可复用上次持久化的
     * tracksRaw，跳过全量 [scan] 的逐行 Track 构造。只取 _ID/DATE_ADDED 两列、
     * 不构造 Track，比全量 scan 快得多（大库尤其明显）。
     *
     * 前缀 `v2|` 是签名**格式版本**：2026-10-23 标题解析改为 TitleResolver（从文件名
     * 补回 (Remix) 等版本注记），旧缓存存的还是被截断的标题——签名加一个版本前缀，
     * 让升级后的首次启动因签名不匹配而强制重扫一次，把修正后的标题刷进缓存。
     *
     * 返回 "v2|count|maxDateAdded"；查不到返回 "v2|0|0"。
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
        "v2|$count|$maxDate"
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
            // v1.5.0 A62: 专辑作者——判断客串歌手的依据（"只 feat 一首单曲的歌手，
            // 专辑作者字段里不会有他的名字"）。
            MediaStore.Audio.Media.ALBUM_ARTIST,
        )
        val out = ArrayList<Track>()
        resolver.query(uri, projection, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbumArtist = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
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
                // v1.5.0 P1：调试导出的伴奏/人声 WAV **不是"歌"**，不进曲库（定稿 定调：
                // 伴奏/人声要一直圈在拾音自己的地盘内，只在开发期例外）。
                // 这里按路径拦一道，是**双保险**——正常情况根本走不到：DebugExport 落盘时用的是
                // 非音频 MIME，MediaProvider 不会把它归入 MediaStore.Audio，本查询也就查不到它。
                // ⚠️ 刻意写成 Kotlin 侧过滤而不是加进 selection：一条写错的 selection 会让
                //    MediaProvider 直接抛错 → **整个曲库变空**。为一个调试用的排除项冒这个风险不值得。
                if (data.contains(DEBUG_EXPORT_MARK) || rel.startsWith(DEBUG_EXPORT_MARK)) continue
                val folder = folderDisplay(rel, data)
                val rawArtist = c.getString(iArtist)
                val rawAlbum = c.getString(iAlbum)
                val rawTrackNo = c.getInt(iTrack)
                out += Track(
                    id = id,
                    uri = ContentUris.withAppendedId(uri, id),
                    // 2026-10-23：标题经 TitleResolver 调和——下载站常把版本注记
                    // （(Remix)/(Live)）只写进文件名、标签里只留基础歌名，导致同歌多版本
                    // 塌成同名。详见 TitleResolver。
                    title = TitleResolver.resolveTitle(c.getString(iTitle), data),
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
                    albumArtist = if (iAlbumArtist >= 0) {
                        c.getString(iAlbumArtist)?.takeIf {
                            it.isNotBlank() && it != MediaStore.UNKNOWN_STRING
                        } ?: ""
                    } else "",
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

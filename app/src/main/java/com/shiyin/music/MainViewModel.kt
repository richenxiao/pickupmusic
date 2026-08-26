package com.shiyin.music

import android.app.Application
import android.app.PendingIntent
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shiyin.music.data.MediaScanner
import com.shiyin.music.data.NO_ALBUM
import com.shiyin.music.data.SettingsStore
import com.shiyin.music.data.Track
import com.shiyin.music.data.UNKNOWN_ARTIST
import com.shiyin.music.data.albumKeyOf
import com.shiyin.music.data.db.AlbumOrderEntity
import com.shiyin.music.data.db.AlbumOverrideEntity
import com.shiyin.music.data.db.AppDatabase
import com.shiyin.music.data.db.ArtistAliasEntity
import com.shiyin.music.data.db.ArtistEntity
import com.shiyin.music.data.db.IgnoredFolderEntity
import com.shiyin.music.data.db.PlaylistEntity
import com.shiyin.music.data.db.PlaylistTrackEntity
import com.shiyin.music.data.db.SavedLyricEntity
import com.shiyin.music.data.db.TrashedTrackEntity
import com.shiyin.music.data.lyrics.LrcParser
import com.shiyin.music.data.lyrics.LyricsFetcher
import com.shiyin.music.data.lyrics.ParsedLyrics
import com.shiyin.music.data.lyrics.UsltReader
import com.shiyin.music.playback.DeviceRouter
import com.shiyin.music.playback.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

enum class Tab { Home, Search, Library }
enum class ObStage { Perm, Scan, Done }
enum class FilesView { Root, Clean, Trash, Folders, FolderContent, Ignored, Devices, About, Merges }
enum class LyState { Idle, Searching, Failed }
enum class LyricsKind { Embedded, Sidecar, Online, Imported }

data class ToastData(val text: String, val changeTargetId: Long?)

data class LoadedLyrics(
    val mediaId: Long,
    val raw: String,
    val parsed: ParsedLyrics,
    val source: String,
    val kind: LyricsKind,
    val saved: Boolean,
    val offsetMs: Long,
) {
    val canAdjust: Boolean get() = kind == LyricsKind.Online || kind == LyricsKind.Imported
    val sourceLabel: String
        get() = when (kind) {
            LyricsKind.Embedded -> "内嵌歌词 · ID3"
            LyricsKind.Sidecar -> "同名 .lrc"
            else -> (if (saved) "已保存 · " else "未保存 · ") + source
        }
}

sealed class TrashOp(val ids: List<Long>) {
    class Trash(ids: List<Long>) : TrashOp(ids)
    class Restore(ids: List<Long>) : TrashOp(ids)
    class Delete(ids: List<Long>) : TrashOp(ids)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val dao = db.dao()
    /** v1.2.0 阶段三：用户修正数据导出/导入。 */
    private val backupManager = com.shiyin.music.data.db.BackupManager(dao)
    // 共享 OkHttpClient，避免每次切歌新建连接池/线程池泄漏
    private val sharedHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()

    // ── v4: expose release dates for the artist album list ──────────────────
    /** Returns a map of albumId -> releaseDate ISO string from album_art_cache. */
    suspend fun releaseDates(): Map<Long, String> = withContext(Dispatchers.IO) {
        dao.allArtCache().associate { it.albumId to it.releaseDate }
    }
    val settingsStore = SettingsStore(app)
    val player: PlayerController = (app as ShiyinApp).player
    /** v1.2.0 阶段二：tracksRaw 磁盘缓存，配合 MediaScanner.signature 实现冷启动增量扫描。 */
    private val trackCache = com.shiyin.music.data.TrackCache(app)

    // ── data state ──────────────────────────────────────────────────────────
    var tracksRaw by mutableStateOf<List<Track>>(emptyList()); private set
    var aliases by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var ignored by mutableStateOf<Set<String>>(emptySet()); private set
    var orders by mutableStateOf<Map<String, List<Long>>>(emptyMap()); private set
    var trashMirror by mutableStateOf<List<TrashedTrackEntity>>(emptyList()); private set
    var playlists by mutableStateOf<List<PlaylistEntity>>(emptyList()); private set
    var playlistTracks by mutableStateOf<Map<String, List<Long>>>(emptyMap()); private set
    var savedLyricsMap by mutableStateOf<Map<Long, SavedLyricEntity>>(emptyMap()); private set
    var artistEntities by mutableStateOf<Map<String, ArtistEntity>>(emptyMap()); private set
    // v1.2.0 #6: 歌手写真 URL（经 ArtistImageResolver 解析：override→cache→Discogs 等自动源）。
    // 与 artist.avatarUrl 解耦——头图改走本独立层，album cover cache 不混。
    var artistImages by mutableStateOf<Map<String, String>>(emptyMap()); private set
    fun artistImage(name: String): String = artistImages[name] ?: ""
    var playCounts by mutableStateOf<Map<Long, Int>>(emptyMap()); private set
    var albumOverrides by mutableStateOf<Map<Long, AlbumOverrideEntity>>(emptyMap()); private set
    // v4.3: album-level manual edits (name/artist/cover) + single-track edits
    var albumInfoOverrides by mutableStateOf<Map<Long, com.shiyin.music.data.db.AlbumInfoOverrideEntity>>(emptyMap()); private set
    var trackInfoOverrides by mutableStateOf<Map<Long, com.shiyin.music.data.db.TrackInfoOverrideEntity>>(emptyMap()); private set
    // v4.3: 单曲迁移专辑（mediaId -> 目标 albumId），修正扫描错误的专辑归属
    var trackAlbumMoves by mutableStateOf<Map<Long, Long>>(emptyMap()); private set

    // v1.1.0: 振假名准确率层
    val furiganaJmdict = com.shiyin.music.data.furigana.JMdictReadingDictionary()
    /** 全局 Override（surface→reading），当前来自 DB GLOBAL 行；启动加载一次。 */
    var globalReadingOverrides by mutableStateOf<Map<String, String>>(emptyMap()); private set
    /** furigana 重算触发器：保存/删除 Song Override 后 bump，produceState 重算。 */
    var furiganaRevision by mutableStateOf(0); private set
    fun bumpFuriganaRevision() { furiganaRevision++ }

    // settings
    var settingsLoaded by mutableStateOf(false); private set
    var darkTheme by mutableStateOf(false); private set
    var gapless by mutableStateOf(true); private set
    var autoMatch by mutableStateOf(true); private set
    var onboarded by mutableStateOf(false); private set
    /** v5.2 Bug2: true only after the first full scan *since install* has
     *  finished absorbing albumIds into `knownAlbumIds`. Subsequent scans diff
     *  against that set and emit real new-album rows. Stays false after a
     *  fresh install or a DataStore wipe, flips true on the first scan exit. */
    var firstScanDone by mutableStateOf(false); private set
    var recentIds by mutableStateOf<List<Long>>(emptyList()); private set
    var deepseekApiKey by mutableStateOf(""); private set
    // v1.1+: 自动保存识别结果（默认开）。关时识别结果临时展示不写入持久化。
    var autoSaveRecognition by mutableStateOf(true); private set
    // v1.1+: 自动识别缓存占用（歌词+封面），供设置页显示与清理。null=未统计。
    var recognitionCacheBytes by mutableStateOf<Long?>(null); private set
    // v1.1+: 外部假名校验状态反馈。
    var externalFetchStatus by mutableStateOf<String?>(null); private set
    // v2: 播放速度调节（全局持久化）
    var playbackSpeed by mutableStateOf(1.0f); private set
    var retroSpeedMode by mutableStateOf(true); private set

    // ── UI state (mirrors the prototype's state machine) ────────────────────
    var isOnboarding by mutableStateOf(true)
    var obStage by mutableStateOf(ObStage.Perm)
    var scanCount by mutableIntStateOf(0)
    var scanFolder by mutableStateOf("")
    var scanResultFolders by mutableStateOf<List<Pair<String, Int>>>(emptyList())
    var scanDupGroups by mutableIntStateOf(0)
    var scanShortCount by mutableIntStateOf(0)
    var scanDiagnosticResult by mutableStateOf<String?>(null)

    var tab by mutableStateOf(Tab.Home)
    var sidebarOpen by mutableStateOf(false)
    var libSearchQuery by mutableStateOf("")
    var settingsOpen by mutableStateOf(false)
    var filesView by mutableStateOf(FilesView.Root)
    // v4: standalone sidebar pages
    var recentOpen by mutableStateOf(false)
    var statsOpen by mutableStateOf(false)
    var updatesOpen by mutableStateOf(false)
    var libChip by mutableStateOf<String?>(null)
    var libViewGrid by mutableStateOf(false)
    var libSortAz by mutableStateOf(false)
    var albumKey by mutableStateOf<String?>(null)
    var albumEdit by mutableStateOf(false)
    var albumEditSnapshot: List<Long>? = null
        private set
    var albumEditText by mutableStateOf(false)
    var albumEditInfo by mutableStateOf(false)
    // v5.2 #79: 批量迁移歌曲模式
    var batchMoveMode by mutableStateOf(false)
    var batchMoveSelected by mutableStateOf<Set<Long>>(emptySet())
    var albumBatchMoveSheet by mutableStateOf(false)

    // v4.5: 文本排序——当用户输入里有歌名匹配不到本专辑曲目时，预览阶段算出的
    // 待应用顺序 + 未匹配歌名暂存在此，由 UI 弹确认窗等待用户抉择。null=无待确认。
    var pendingOrder by mutableStateOf<Pair<List<Long>, List<String>>?>(null); private set
    private var pendingKey: String? = null
    // v4.3: album ⋮ menu (replaces the old top-right ⋮ which only toggled text
    // sort). Opens from the edit-pill next to 播放专辑 and offers four actions:
    // drag-sort, text-sort, edit-info, change-cover.
    var albumMenuOpen by mutableStateOf(false)
    var albumCoverEdit by mutableStateOf(false)
    // v4.3: confirmation dialog shown after an album-info or single-track edit.
    // "是" re-fetches cover + lyrics with the new name/artist; "否" keeps only
    // the manual edits.
    var rematchPromptFor by mutableStateOf<String?>(null)  // "album:<key>" or "track:<id>"
    var artistKey by mutableStateOf<String?>(null)
    var artistMerge by mutableStateOf(false)
    var folderKey by mutableStateOf<String?>(null)
    var plId by mutableStateOf<String?>(null)
    // v3.0: playlist detail ⋮ menu + edit dialogs (rename / cover / delete)
    var plMenuOpen by mutableStateOf(false)
    var plRenameFor by mutableStateOf<String?>(null)
    var plCoverPickerFor by mutableStateOf<String?>(null)
    var plDeleteFor by mutableStateOf<String?>(null)
    // v3.0: artist picker from the player's artist-name tap (multi-artist tracks)
    var artistPickerFor by mutableStateOf<List<String>?>(null)
    var playerOpen by mutableStateOf(false)
    var lyricsOn by mutableStateOf(false)
    var lySheet by mutableStateOf(false)
    var lyricsImportDialog by mutableStateOf(false)
    var lyricsSourcePicker by mutableStateOf(false)
    var sleepMenu by mutableStateOf(false)
    var q by mutableStateOf("")
    // v5.2: 搜索历史——SharedPreferences 持久化、保序、最近在前、去重、上限 12。
    // 用 "\n" join 存为单串（StringSet 不保序）。键盘"搜索"键或点结果播放时 commit。
    var searchHistory by mutableStateOf<List<String>>(emptyList()); private set
    private val searchPrefs by lazy { app.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE) }
    init {
        val saved = searchPrefs.getString("terms", "") ?: ""
        searchHistory = if (saved.isBlank()) emptyList() else saved.split("\n")
    }
    fun commitSearch() {
        val term = q.trim()
        if (term.isBlank()) return
        val updated = (listOf(term) + searchHistory.filter { it != term }).take(12)
        searchHistory = updated
        searchPrefs.edit().putString("terms", updated.joinToString("\n")).apply()
    }
    fun clearSearchHistory() {
        searchHistory = emptyList()
        searchPrefs.edit().remove("terms").apply()
    }
    var sel by mutableStateOf<Map<Long, Boolean>>(emptyMap())

    // v1.5–v1.8 UI state
    var saveSheetFor by mutableStateOf<Long?>(null)       // 保存到歌单 target (null = closed)
    var albumPickerId by mutableStateOf<Long?>(null)      // 归类到专辑 target (null = closed)
    var albumMoveFor by mutableStateOf<Long?>(null)       // v4.3: 迁移专辑 target (null = closed)
    var trackMenuFor by mutableStateOf<Long?>(null)       // per-row ⋮ menu target
    var pMenuView by mutableStateOf<String?>(null)        // player ⋮: null | "root" | "timer"
    var devicePopupVisible by mutableStateOf(false)
    var qSheetOpen by mutableStateOf(false)
    var qEdit by mutableStateOf(false)
    var hlId by mutableStateOf<Long?>(null)               // album-row highlight target
    var toast by mutableStateOf<ToastData?>(null)
    val deviceRouter = DeviceRouter(app)
    private var toastJob: Job? = null

    // lyrics runtime
    var lyState by mutableStateOf(LyState.Idle)
    var currentLyrics by mutableStateOf<LoadedLyrics?>(null); private set
    private val triedAuto = HashSet<Long>()
    private var lyricsJob: Job? = null

    // trash plumbing
    var pendingTrashOp: TrashOp? = null; private set
    var launchTrashIntent: ((PendingIntent) -> Unit)? = null

    var hasMediaPermission by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            settingsStore.flow.collect { s ->
                darkTheme = s.dark
                gapless = s.gapless
                player.gaplessEnabled = s.gapless
                autoMatch = s.autoMatch
                onboarded = s.onboarded
                firstScanDone = s.firstScanDone
                recentIds = s.recentIds
                deepseekApiKey = s.deepseekApiKey
                autoSaveRecognition = s.autoSaveRecognition
                playbackSpeed = s.playbackSpeed
                retroSpeedMode = s.retroSpeedMode
                if (!settingsLoaded) {
                    settingsLoaded = true
                    isOnboarding = !s.onboarded
                    if (permGranted) handlePermission()
                }
            }
        }
        viewModelScope.launch { dao.aliasFlow().collect { l -> aliases = l.associate { it.fromName to it.toName } } }
        viewModelScope.launch { dao.ignoredFlow().collect { l -> ignored = l.map { it.path }.toSet() } }
        viewModelScope.launch {
            dao.orderFlow().collect { l ->
                orders = l.associate { e -> e.albumKey to e.orderedIds.split(",").mapNotNull { it.toLongOrNull() } }
            }
        }
        viewModelScope.launch { dao.trashFlow().collect { trashMirror = it } }
        viewModelScope.launch { dao.playlistsFlow().collect { playlists = it } }
        viewModelScope.launch {
            dao.playlistTracksFlow().collect { l ->
                playlistTracks = l.sortedBy { it.addedAt }.groupBy({ it.playlistId }, { it.mediaId })
            }
        }
        viewModelScope.launch { dao.lyricsFlow().collect { l -> savedLyricsMap = l.associateBy { it.mediaId } } }
        viewModelScope.launch { dao.artistFlow().collect { l -> artistEntities = l.associateBy { it.name } } }
        viewModelScope.launch { dao.playCountFlow().collect { l -> playCounts = l.associate { it.mediaId to it.count } } }
        viewModelScope.launch { dao.albumOverrideFlow().collect { l -> albumOverrides = l.associateBy { it.mediaId } } }
        viewModelScope.launch { dao.albumInfoOverrideFlow().collect { l -> albumInfoOverrides = l.associateBy { it.albumId } } }
        viewModelScope.launch { dao.trackInfoOverrideFlow().collect { l -> trackInfoOverrides = l.associateBy { it.mediaId } } }
        viewModelScope.launch { dao.trackAlbumMoveFlow().collect { l -> trackAlbumMoves = l.associate { it.mediaId to it.albumId } } }
        // v1.1.0: 振假名准确率层——后台加载 JMdict 派生词典 + 全局 override。
        // JMdict 冷解析在 IO 线程；未完成时 pipeline 跳过该层（安全降级，Kuromoji 兜底）。
        // ⚠️ 加载完成后必须 bump furiganaRevision——否则 produceState 不重算，第一首歌
        // 一直停在 Kuromoji-only（code-review C：JMdict 加载与 produceState key 解耦）。
        viewModelScope.launch(Dispatchers.IO) {
            furiganaJmdict.load(getApplication())
            withContext(Dispatchers.Main) { bumpFuriganaRevision() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                globalReadingOverrides = dao.globalReadingOverrides().associate { it.surface to it.reading }
            } catch (_: Exception) { }
        }
        // v3.0: prime the in-memory color cache from disk so the very first
        // player/lyrics paint already shows the album's true mood, rather than
        // the fixed fallback that only morphs once the bitmap decodes.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rows = dao.allArtCache()
                com.shiyin.music.ui.components.ArtCache.primeColors(getApplication(), rows)
                // v4: pre-populate known album ids from cache so we can detect
                // brand-new albums on the next scan (for 你的更新).
                knownAlbumIds = rows.map { it.albumId }.toMutableSet()
                knownAlbumIds.addAll(dao.allNewAlbumIds())
            } catch (_: Exception) { }
        }
        viewModelScope.launch(Dispatchers.IO) { deviceRouter.refreshDeviceList() }
        // v5.2 Bug1: on every device-connection change, refresh the list and
        // forward any pending sticky-restore address to the playback service
        // (covers the "BT reconnect → auto-reroute" case).
        viewModelScope.launch {
            deviceRouter.deviceChanges().collect {
                applyPendingRoutingFromDeviceRouter()
            }
        }
        player.connect(app, { id -> onTrackStarted(id) }, { id -> onPlayCounted(id) }, { id, sec -> onPlayFinalized(id, sec) })
    }

    private var permGranted = false

    fun onPermissionGranted() {
        hasMediaPermission = true
        permGranted = true
        if (!settingsLoaded) return // dispatched once settings arrive
        handlePermission()
    }

    private fun handlePermission() {
        if (isOnboarding) {
            if (obStage == ObStage.Perm) startScan()
        } else if (tracksRaw.isEmpty()) {
            rescanSilently()
        }
    }

    // ── library derivation ─────────────────────────────────────────────────
    private val trashIds: Set<Long> get() = trashMirror.map { it.mediaId }.toSet()

    fun resolveArtist(name: String): String = aliases[name] ?: name

    /** v1.2.0 #6: 解析歌手写真并刷新 artistImages（override→cache→Discogs 等自动源）。
     *  重入由 ArtistImageResolver 内部保护；cache 命中即返回不触网。写真持久化在独立
     *  artist_image_cache / artist_image_override 表，扫描/更新不丢、自动源不覆盖手选。 */
    private val artistImageResolver = com.shiyin.music.data.image.ArtistImageResolver(dao)

    fun fetchArtistAvatar(name: String) {
        viewModelScope.launch {
            val img = artistImageResolver.resolve(name, personOnly = false)
            artistImages = artistImages + (name to (img?.url ?: ""))
        }
    }

    /** 用户手动选写真（写 override，永久最高优先级，自动源永不覆盖）。 */
    fun setArtistImageOverride(name: String, url: String) {
        viewModelScope.launch {
            artistImageResolver.setOverride(name, url)
            val img = artistImageResolver.resolve(name, personOnly = false)
            artistImages = artistImages + (name to (img?.url ?: ""))
        }
    }

    /** 清除手选写真，回退到自动源。 */
    fun clearArtistImageOverride(name: String) {
        viewModelScope.launch {
            artistImageResolver.clearOverride(name)
            val img = artistImageResolver.resolve(name, personOnly = false)
            artistImages = artistImages + (name to (img?.url ?: ""))
        }
    }

    /** v1.2.0 #6: 从本地文件(content URI)选写真——拷到 app 内部存储(持久),存 file:// 路径为覆盖。 */
    fun setArtistImageFromFile(name: String, contentUri: android.net.Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<android.app.Application>()
                    val safe = name.filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "artist" }
                    val dir = java.io.File(ctx.filesDir, "artist_image_override").apply { mkdirs() }
                    dir.listFiles()?.filter { it.name.startsWith("${safe}_") }?.forEach { it.delete() }  // 清旧本地文件
                    val file = java.io.File(dir, "${safe}_${System.currentTimeMillis()}.jpg")
                    ctx.contentResolver.openInputStream(contentUri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching false
                    artistImageResolver.setOverride(name, "file://${file.absolutePath}")
                    val img = artistImageResolver.resolve(name, personOnly = false)
                    artistImages = artistImages + (name to (img?.url ?: ""))
                    true
                }.getOrDefault(false)
            }
            if (!ok) showToast("本地写真保存失败", null)
        }
    }

    /** v1.2.0 #6: 用该歌手某张专辑的封面作写真——提取封面 bitmaps 存内部文件,file:// 覆盖。 */
    fun setArtistImageFromAlbumCover(name: String, track: com.shiyin.music.data.Track) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<android.app.Application>()
                    val bmp = ctx.contentResolver.loadThumbnail(track.uri, android.util.Size(600, 600), null)
                    val safe = name.filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "artist" }
                    val dir = java.io.File(ctx.filesDir, "artist_image_override").apply { mkdirs() }
                    dir.listFiles()?.filter { it.name.startsWith("${safe}_") }?.forEach { it.delete() }
                    val file = java.io.File(dir, "${safe}_${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    artistImageResolver.setOverride(name, "file://${file.absolutePath}")
                    val img = artistImageResolver.resolve(name, personOnly = false)
                    artistImages = artistImages + (name to (img?.url ?: ""))
                    true
                }.getOrDefault(false)
            }
            if (!ok) showToast("专辑封面保存失败", null)
        }
    }

    // v1.2.0 #6: 写真选择器——并行取所有源候选(各源 1 张,Discogs/AudioDB/Fanart 等),
    // 供用户挑换;选完调 setArtistImageOverride 写覆盖(永久,旧覆盖被替换),UI 自动刷新。
    var artistPhotoPickerFor by mutableStateOf<String?>(null)
    var artistImageCandidates by mutableStateOf<List<com.shiyin.music.data.image.ArtistImage>>(emptyList()); private set

    fun openArtistPhotoPicker(name: String) {
        artistPhotoPickerFor = name
        artistImageCandidates = emptyList()
        viewModelScope.launch {
            // 各源 fetchAll 返回该源全部图(Discogs 多张/AudioDB thumb+fanart/Fanart bg+thumb),flatten 后去重。
            // personOnly=true:跳过 iTunes(只回专辑封面,非人物照,且专辑封面已在「专辑封面」区可选)。
            val list = coroutineScope {
                com.shiyin.music.data.image.ArtistImageSources.sources
                    .map { async { runCatching { it.fetchAll(name, personOnly = true) }.getOrNull().orEmpty() } }
                    .awaitAll().flatten()
            }
            artistImageCandidates = list.distinctBy { it.url }
        }
    }

    /**
     * Cached derivation of the visible library. The list was previously rebuilt
     * on every call (every recomposition), which made the player screen, every
     * album/artist list, and the mini-player each O(n)-filter the full corpus
     * on the main thread — a major contributor to the v3.0 startup/scroll jank.
     *
     * The cache is keyed by a signature assembled from the inputs that can change
     * the result (tracksRaw size+firstId, alias/ignored/override/trash snapshots),
     * so it recomputes only when one of those actually changes.
     */
    private var libCache: List<Track> = emptyList()
    private var libSig: String = ""

    private fun libSignature(): String {
        // Cheap: identity hash of size + first id + counts; avoids hashing the
        // whole list while still catching any real mutation. The override/alias/
        // info maps additionally hash their VALUES so that an inline re-edit of
        // the same album/track (count unchanged) still invalidates the cache.
        val first = tracksRaw.firstOrNull()?.id ?: -1L
        val ignoredSorted = ignored.sorted().hashCode()
        val trashSorted = trashIds.sorted().hashCode()
        val aliasCount = aliases.size
        val aliasValues = aliases.entries.sortedBy { it.key }.hashCode()
        val ovCount = albumOverrides.size
        val ovValues = albumOverrides.entries.sortedBy { it.key }.hashCode()
        val infoCount = albumInfoOverrides.size
        val infoValues = albumInfoOverrides.entries.sortedBy { it.key }.hashCode()
        val trackInfoCount = trackInfoOverrides.size
        val trackInfoValues = trackInfoOverrides.entries.sortedBy { it.key }.hashCode()
        val albumMoveCount = trackAlbumMoves.size
        val albumMoveValues = trackAlbumMoves.entries.sortedBy { it.key }.hashCode()
        return "${tracksRaw.size}|$first|$ignoredSorted|$trashSorted|" +
            "$aliasCount|$aliasValues|$ovCount|$ovValues|" +
            "$infoCount|$infoValues|$trackInfoCount|$trackInfoValues|" +
            "$albumMoveCount|$albumMoveValues"
    }

    /** Visible library: not trashed, not in an ignored folder, alias + split artist + album override applied. */
    fun lib(): List<Track> {
        val sig = libSignature()
        if (sig == libSig) return libCache
        val trash = trashIds
        libCache = tracksRaw
            .filter { it.id !in trash && it.folder !in ignored }
            .map { t ->
                var r = t
                // v4.3: 迁移专辑 — re-parent the track into its real album FIRST,
                // so every downstream lookup (album-info override, album grouping,
                // cover keying) naturally uses the moved albumId.
                val movedAlbumId = trackAlbumMoves[t.id]?.takeIf { it > 0 && it != t.albumId }
                if (movedAlbumId != null) r = r.copy(albumId = movedAlbumId)
                val a = aliases[t.artist]
                val resolved = if (a != null) a else t.artist
                // v3.0: split multi-artist strings into display format
                val split = com.shiyin.music.data.MediaScanner.splitArtists(resolved)
                r = r.copy(artist = split.joinToString(", "))
                val ov = albumOverrides[t.id]
                if (ov != null) r = r.copy(album = ov.albumName)
                // v4.3: album-level manual edits — same name/artist for the whole album
                val an = albumInfoOverrides[r.albumId]
                if (an != null) {
                    if (an.albumName.isNotBlank()) r = r.copy(album = an.albumName)
                    if (an.artistName.isNotBlank()) r = r.copy(artist = an.artistName)
                }
                // v4.3: single-track manual edits — display-only title/artist/note
                val tn = trackInfoOverrides[t.id]
                if (tn != null) {
                    if (tn.title.isNotBlank()) r = r.copy(title = tn.title)
                    if (tn.artist.isNotBlank()) r = r.copy(artist = tn.artist)
                }
                r
            }
        libSig = sig
        // trackById reads this map; rebuild alongside lib() so it stays in sync
        // and lookups stay O(1) (player-screen open, mini-player, queue sheet).
        libById = libCache.associateBy { it.id }
        return libCache
    }

    private var libById: Map<Long, Track> = emptyMap()

    fun trackById(id: Long?): Track? = id?.let { libById[it] }

    fun playCountFor(id: Long): Int = playCounts[id] ?: 0

    private val collator: Collator = Collator.getInstance(Locale.CHINESE)
    // v4: tracks which albumIds are already known so we can detect new arrivals
    private var knownAlbumIds: MutableSet<Long> = mutableSetOf()

    fun sortedSongs(): List<Track> {
        val l = lib()
        return if (libSortAz) l.sortedWith(compareBy(collator) { it.title }) else l.sortedByDescending { it.dateAdded }
    }

    private var albumsCache: Map<String, List<Track>> = emptyMap()
    private var albumsSig: String = ""

    fun albumsMap(): Map<String, List<Track>> {
        // v5.2 #79d: 先调 lib() 把 libSig 刷新到当前值,再做缓存判定。
        // 否则 trackAlbumMoves/albumOverrides 改了之后,这里读到的 libSig 还是上次
        // lib() 留下的旧签名,会与 albumsSig 相等直接返回旧缓存——新建专辑不出现。
        // (lib() 命中缓存时 O(1;且其内部 libSignature() 会读 trackAlbumMoves
        //  这个 mutableStateOf,从而让消费 albumsMap 的 Composable 能感知变化重组。)
        lib()
        val sig = libSig
        if (sig == albumsSig) return albumsCache
        val m = LinkedHashMap<String, MutableList<Track>>()
        for (t in lib()) {
            if (t.album == NO_ALBUM) continue
            m.getOrPut(albumKeyOf(t.album, t.artist, t.albumId)) { mutableListOf() }.add(t)
        }
        // Dedupe identical titles inside an album (duplicate files), like the prototype.
        albumsCache = m.mapValues { (_, ts) ->
            val seen = HashSet<String>()
            ts.filter { seen.add(it.title) }
        }
        albumsSig = sig
        return albumsCache
    }

    private var artistsCache: Map<String, List<Track>> = emptyMap()
    private var artistsSig: String = ""

    fun artistsMap(): Map<String, List<Track>> {
        // v5.2 #79d: 同 albumsMap——先刷新 libSig,避免迁移/改名后返回旧缓存。
        lib()
        val sig = libSig
        if (sig == artistsSig) return artistsCache
        val m = LinkedHashMap<String, MutableList<Track>>()
        for (t in lib()) {
            val artists = com.shiyin.music.data.MediaScanner.splitArtists(t.artist)
            for (a in artists) {
                m.getOrPut(a) { mutableListOf() }.add(t)
            }
        }
        artistsCache = m
        artistsSig = sig
        return artistsCache
    }

    fun foldersMap(): Map<String, List<Track>> {
        val m = LinkedHashMap<String, MutableList<Track>>()
        for (t in lib()) m.getOrPut(t.folder) { mutableListOf() }.add(t)
        return m
    }

    /**
     * Rebuilds the service-side queue respecting the current queue kind:
     * album queues stay album-scoped, normal queues track the sorted library.
     */
    private fun resyncQueue() {
        val key = player.queueKey
        if (key != null && key.startsWith("album:")) {
            val ts = albumOrder(key.removePrefix("album:"))
            val cur = player.currentId
            if (ts.isEmpty() || cur == null || ts.none { it.id == cur }) {
                if (player.currentId != null && trackById(player.currentId) == null) {
                    player.stopAndClear()
                    playerOpen = false
                }
                return
            }
            player.syncQueue(ts)
        } else {
            player.syncQueue(sortedSongs())
        }
    }

    /** Album track order: custom order if set, else track-no asc (missing last), name tiebreak. */
    fun albumOrder(key: String): List<Track> {
        val ts = albumsMap()[key] ?: return emptyList()
        val custom = orders[key]
        if (custom != null) {
            val byId = ts.associateBy { it.id }
            val orderedPart = custom.mapNotNull { byId[it] }
            return orderedPart + ts.filter { it.id !in custom }
        }
        return ts.sortedWith(
            compareBy<Track> { if (it.trackNo > 0) it.trackNo else 999 }
                .thenComparator { a, b -> collator.compare(a.title, b.title) }
        )
    }

    fun moveAlbumTrack(key: String, index: Int, dir: Int) {
        val ids = albumOrder(key).map { it.id }.toMutableList()
        val j = index + dir
        if (j < 0 || j >= ids.size) return
        ids[index] = ids[j].also { ids[j] = ids[index] }
        // Optimistic local update so rapid taps never read a stale order
        // while the Room write round-trips.
        orders = orders + (key to ids.toList())
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertOrder(AlbumOrderEntity(key, ids.joinToString(",")))
        }
    }

    /** v3.0: drag-reorder — move item from [from] to [to] in one step. */
    fun reorderAlbumTrack(key: String, from: Int, to: Int) {
        android.util.Log.d("DragDebug", "reorderAlbumTrack key=$key from=$from to=$to")
        val ids = albumOrder(key).map { it.id }.toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        if (from == to) return
        val item = ids.removeAt(from)
        ids.add(to, item)
        orders = orders + (key to ids.toList())
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertOrder(AlbumOrderEntity(key, ids.joinToString(",")))
        }
    }

    fun resetAlbumOrder(key: String) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteOrder(key) }
    }

    fun snapshotAlbumOrder() {
        val key = albumKey ?: return
        albumEditSnapshot = orders[key]
        android.util.Log.d("OrderDebug", "snapshot key=$key snap=${albumEditSnapshot}")
    }

    fun clearAlbumEditSnapshot() {
        albumEditSnapshot = null
    }

    // v5.2 #79: 批量迁移
    fun toggleBatchMoveSelected(id: Long) {
        batchMoveSelected = if (id in batchMoveSelected) batchMoveSelected - id else batchMoveSelected + id
    }

    fun batchMoveToAlbum(albumId: Long) {
        val selected = batchMoveSelected.toList()
        android.util.Log.d("BatchMoveDebug", "batchMoveToAlbum albumId=$albumId count=${selected.size}")
        if (albumId <= 0 || selected.isEmpty()) return
        // 乐观更新内存(不调 moveTrackToAlbum——它有 albumMoveFor=null + toast 副作用)
        val moves = trackAlbumMoves.toMutableMap()
        for (id in selected) { moves[id] = albumId }
        trackAlbumMoves = moves
        viewModelScope.launch(Dispatchers.IO) {
            for (id in selected) {
                dao.upsertTrackAlbumMove(
                    com.shiyin.music.data.db.TrackAlbumMoveEntity(id, albumId, System.currentTimeMillis())
                )
            }
        }
        showToast("已迁移 ${selected.size} 首到该专辑", null)
        batchMoveSelected = emptySet()
        batchMoveMode = false
    }

    fun batchAssignToNewAlbum(name: String, artist: String) {
        val selected = batchMoveSelected.toList()
        android.util.Log.d("BatchMoveDebug", "batchAssignToNewAlbum name=$name artist=$artist count=${selected.size}")
        if (selected.isEmpty()) return
        // v5.2 #79c: 要让新专辑在 albumsMap() 里独立成组,光改 albumOverrides(显示名)
        // 不行——albumKeyOf() 在 albumId>0 时按 albumId 分组,名变了 key 不变。
        // 解法:生成一个合成 albumId(>0,不跟 MediaStore 真实 id 冲突),写进
        // trackAlbumMoves → lib() 里 r.copy(albumId = syntheticId) → albumsMap()
        // 按 aid:syntheticId 分组 → 独立新专辑。同时写 albumOverrides 改显示名。
        val syntheticId = (name.lowercase().trim().hashCode().toLong() and 0x7FFFFFFFL) + 0x100000000L
        val overrides = albumOverrides.toMutableMap()
        val moves = trackAlbumMoves.toMutableMap()
        // v5.2 #79e: 新专辑默认归属当前歌手。albumOverrides 只改显示名(lib() 只读
        // 它的 albumName,不读 artistName),光写它不够——曲目自带的 artist 字段会让
        // 这张专辑被 artistsMap 识别成「其他歌手」。补写 albumInfoOverrides[syntheticId]
        // (albumName + artistName):lib() 第 368 行先把 albumId 改成 syntheticId,第 377
        // 行再查 albumInfoOverrides[syntheticId]、第 380 行把 artist 覆盖成当前歌手。
        // 与 saveAlbumInfo 同路径,一并持久化到 Room。
        val infoEntity = com.shiyin.music.data.db.AlbumInfoOverrideEntity(
            albumId = syntheticId,
            albumName = name,
            artistName = artist,
            coverUri = "",
            type = "",
            updatedAt = System.currentTimeMillis(),
        )
        val infos = albumInfoOverrides.toMutableMap().also { it[syntheticId] = infoEntity }
        for (id in selected) {
            overrides[id] = AlbumOverrideEntity(id, name, artist)
            moves[id] = syntheticId
        }
        albumOverrides = overrides
        trackAlbumMoves = moves
        albumInfoOverrides = infos
        viewModelScope.launch(Dispatchers.IO) {
            for (id in selected) {
                dao.upsertAlbumOverride(AlbumOverrideEntity(id, name, artist))
                dao.upsertTrackAlbumMove(
                    com.shiyin.music.data.db.TrackAlbumMoveEntity(id, syntheticId, System.currentTimeMillis())
                )
            }
            dao.upsertAlbumInfoOverride(infoEntity)
        }
        showToast("已迁移 ${selected.size} 首到新专辑「$name」", null)
        batchMoveSelected = emptySet()
        batchMoveMode = false
    }

    fun restoreAlbumOrderIfUncommitted(key: String) {
        val snap = albumEditSnapshot
        android.util.Log.d("OrderDebug", "restore key=$key snap=$snap curOrders=${orders[key]}")
        if (snap == null) {
            viewModelScope.launch(Dispatchers.IO) { dao.deleteOrder(key) }
            orders = orders - key
        } else {
            orders = orders + (key to snap)
            viewModelScope.launch(Dispatchers.IO) { dao.upsertOrder(AlbumOrderEntity(key, snap.joinToString(","))) }
        }
        albumEditSnapshot = null
    }

    /** v3.0: apply track order from a text input like "1. Song A\n2. Song B". */
    fun applyAlbumOrderText(key: String, text: String) {
        val tracks = albumOrder(key)
        // Parse: "1. title" or "1 title" or "title" (no number)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // v5: an empty submission is a no-op — preserving the previous order is
        // far less surprising than silently appending every track unnumbered.
        if (lines.isEmpty()) return
        val newOrder = mutableListOf<Long>()
        val used = HashSet<Long>()
        val missed = mutableListOf<String>() // v4.5: 记录未能匹配的行，交给 UI 提示
        for (line in lines) {
            val title = line.replace(Regex("""^\d+[.、)\s]*"""), "").trim()
            if (title.isEmpty()) continue
            // Prefer exact match first; fall back to contains so shorthand works.
            val match =
                tracks.firstOrNull { it.id !in used && it.title.equals(title, ignoreCase = true) }
                    ?: tracks.firstOrNull { it.id !in used && it.title.contains(title, ignoreCase = true) }
            if (match == null) { missed += title; continue } // v4.5: 跳过前记一笔，不再静默
            newOrder.add(match.id)
            used += match.id
        }
        // Append any tracks not mentioned in the text (already-deduped via `used`)
        for (t in tracks) if (t.id !in used) { newOrder.add(t.id); used += t.id }
        if (newOrder.isEmpty()) return
        // v4.5: 有未匹配行 → 暂存待确认，不落库；无未匹配 → 保持原行为直接落库。
        if (missed.isNotEmpty()) {
            pendingOrder = newOrder.toList() to missed
            pendingKey = key
            return
        }
        commitOrder(key, newOrder.toList())
    }

    /** v4.5: 用户在确认窗点“应用已识别部分”——把待确认顺序真正落库。 */
    fun commitPendingOrder() {
        val po = pendingOrder ?: return
        val key = pendingKey ?: return
        pendingOrder = null
        pendingKey = null
        commitOrder(key, po.first)
    }

    /** v4.5: 用户点“取消返回修改”——丢弃预览，不动库，文本框保持可编辑。 */
    fun cancelPendingOrder() {
        pendingOrder = null
        pendingKey = null
    }

    /** 乐观更新内存 orders + 异步写库。applyAlbumOrderText / commitPendingOrder 共用。 */
    private fun commitOrder(key: String, final: List<Long>) {
        orders = orders + (key to final)  // 7-D: 即时刷新 UI
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertOrder(AlbumOrderEntity(key, final.joinToString(",")))
        }
    }

    // duplicates / short audio, per prototype semantics
    fun dupGroups(): List<List<Track>> = lib()
        .groupBy { it.title + "|" + it.artist }
        .values.filter { it.size > 1 }
        .map { g -> g.sortedBy { it.dateAdded } }

    fun shortTracks(): List<Track> = lib().filter { it.durationSec in 1..30 }

    fun cleanCount(): Int {
        val dups = dupGroups()
        val dupAll = dups.flatten().map { it.id }.toSet()
        return dups.sumOf { it.size - 1 } + shortTracks().count { it.id !in dupAll }
    }

    fun cleanSizeBytes(): Long {
        // Union of duplicate extras and short tracks, deduped by id so a
        // short duplicate isn't billed twice.
        val cleanable = HashMap<Long, Track>()
        for (g in dupGroups()) g.drop(1).forEach { cleanable[it.id] = it }
        for (t in shortTracks()) cleanable[t.id] = t
        return cleanable.values.sumOf { it.sizeBytes }
    }

    // ── scanning ───────────────────────────────────────────────────────────
    /** Folders known from the most recent scan, used by [forceReindexKnownFolders]. */
    private var knownFolders: Set<String> = emptySet()

    /** Force MediaStore to re-index audio files. On first scan (no known folders),
     *  walks common audio directories to ensure MediaStore has indexed them. */
    private suspend fun forceReindexKnownFolders() {
        val folders = if (knownFolders.isNotEmpty()) knownFolders else {
            // v3.0 A3: first scan — find all folders with audio files on the filesystem
            val storageBase = android.os.Environment.getExternalStorageDirectory().absolutePath
            val commonDirs = listOf("Music", "Download", "Podcasts", "Ringtones", "Alarms", "Notifications", "Recordings", "Android/media", "DCIM", "Movies", "Documents")
            commonDirs.mapNotNull { dir ->
                val f = java.io.File("$storageBase/$dir")
                if (f.isDirectory && f.walkTopDown().any { it.isFile && it.extension.lowercase() in com.shiyin.music.data.MediaScanner.AUDIO_EXTENSIONS }) {
                    "/$dir"
                } else null
            }.toSet()
        }
        if (folders.isNotEmpty()) {
            com.shiyin.music.data.MediaScanner.forceReindex(getApplication(), folders)
        }
    }

    // v4: detect brand-new albumIds from a scan result and insert them into
    // new_album (the "你的更新" source). Each is a one-shot unread reminder.
    //
    // v5.1 Bug2 fix: the very first scan (onboarding, before setOnboarded(true)) must
    // NOT seed new_album — the entire library is "new" on first run and would
    // flood 你的更新. We still absorb those albumIds into knownAlbumIds so the
    // second scan onward correctly detects fresh arrivals.
    //
    // v5.2 Bug2 root-cause: v5.1 only checked `!onboarded`, but覆盖安装升级用户
    // 的 onboarded 标志在 v4.3 时早已 true，所以 v5.1 的 fix 对升级用户没生效 —
    // 升级后第一次 rescan 走 onboarded=true 分支，把整个老库又灌一次 new_album
    // (且 addNewAlbums 用 IGNORE 策略，旧记录时间戳不刷)。真正的修复是新增
    // `firstScanDone` 标志：只有完成过至少一次扫描后，下一次扫描才开始 diff +
    // write new_album。这覆盖了 fresh install + 覆盖升级 两种场景。
    private fun detectNewAlbums(tracks: List<Track>) {
        val fresh = tracks.map { it.albumId }.filter { it > 0 }.toSet() - knownAlbumIds
        // Always absorb into the known set so the *next* scan can diff.
        if (fresh.isNotEmpty()) knownAlbumIds.addAll(fresh)
        // v5.2 Bug2: only seed new_album once we've completed at least one scan.
        // The very first scan post-install just primes knownAlbumIds.
        if (!firstScanDone) return
        if (fresh.isEmpty()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.addNewAlbums(fresh.map { id -> com.shiyin.music.data.db.NewAlbumEntity(id, now) })
            } catch (_: Exception) { }
        }
    }

    fun startScan() {
        isOnboarding = true
        obStage = ObStage.Scan
        scanCount = 0
        scanFolder = ""
        viewModelScope.launch {
            forceReindexKnownFolders()
            val result = MediaScanner.scan(getApplication()) { count, folder ->
                withContext(Dispatchers.Main) {
                    scanCount = count
                    scanFolder = folder
                }
            }
            tracksRaw = result
            knownFolders = result.map { it.folder }.toSet()
            // v1.2.0 阶段二：全量 scan 后落盘 tracksRaw + 签名，供下次冷启动增量复用
            runCatching {
                trackCache.write(result)
                trackCache.writeSignature(com.shiyin.music.data.MediaScanner.signature(getApplication()))
            }
            ensureArtistEntities(result)
            // v4: detect brand-new albums for 你的更新
            detectNewAlbums(result)
            // v5.2 Bug2: this scan just finished priming knownAlbumIds (or diffing
            // against it). Mark firstScanDone=true so the NEXT scan will begin
            // emitting new_album rows. On fresh install this stays false until
            // the first scan exits, so the initial library never floods 你的更新.
            if (!firstScanDone) {
                viewModelScope.launch { settingsStore.setFirstScanDone(true) }
            }
            resyncQueue()
            val l = lib()
            scanResultFolders = l.groupBy { it.folder }.map { (p, ts) -> p to ts.size }
            scanDupGroups = dupGroups().size
            scanShortCount = shortTracks().size
            scanFolder = "整理结果中…"
            kotlinx.coroutines.delay(600)
            obStage = ObStage.Done
        }
    }

    fun rescanSilently() {
        viewModelScope.launch {
            // v1.2.0 阶段二：增量扫描。先查 MediaStore 轻量签名（count + max date_added），
            // 与上次相同 → 库无增删 → 复用持久化的 tracksRaw，跳过全量 scan 的逐行 Track 构造。
            // 不同 → 全量 scan + 落盘新缓存 + 更新签名。签名查询只取 2 列不构造 Track，远快于全量。
            val newSig = try { com.shiyin.music.data.MediaScanner.signature(getApplication()) } catch (_: Exception) { "0|0" }
            val cachedSig = trackCache.readSignature()
            val result: List<Track> = if (cachedSig != null && cachedSig == newSig) {
                // 签名一致 → 复用缓存（读盘失败再 fallback 全量）
                val cached = trackCache.read()
                if (cached != null) {
                    android.util.Log.d("ScanTrace", "incremental HIT sig=$newSig tracks=${cached.size} (skipped full scan)")
                    cached
                } else {
                    android.util.Log.d("ScanTrace", "incremental sig-match but cache-miss → full scan sig=$newSig")
                    val r = com.shiyin.music.data.MediaScanner.scan(getApplication(), paced = false)
                    trackCache.write(r); trackCache.writeSignature(newSig); r
                }
            } else {
                // 签名变了或无缓存 → 全量
                val r = com.shiyin.music.data.MediaScanner.scan(getApplication(), paced = false)
                android.util.Log.d("ScanTrace", "incremental MISS full scan sig=$newSig cached=$cachedSig tracks=${r.size}")
                trackCache.write(r); trackCache.writeSignature(newSig); r
            }
            tracksRaw = result
            knownFolders = result.map { it.folder }.toSet()
            ensureArtistEntities(result)
            detectNewAlbums(result)
            // v5.2 Bug2: see comment in startScan() — same gate applies to the
            // cold-start silent rescan path.
            if (!firstScanDone) {
                viewModelScope.launch { settingsStore.setFirstScanDone(true) }
            }
            resyncQueue()
            // v4.3 perf: after the UI has its songs, kick a background re-index so new files
            // added since last run surface on the *next* cold start (cheap post-hoc, off the
            // critical path). Fire-and-forget — never blocks the list.
            if (knownFolders.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try { com.shiyin.music.data.MediaScanner.forceReindex(getApplication(), knownFolders) } catch (_: Exception) { }
                }
            }
        }
    }

    fun finishScan() {
        viewModelScope.launch { settingsStore.setOnboarded(true) }
        isOnboarding = false
        tab = Tab.Home
        settingsOpen = false
    }

    fun rescanWithAnimation() {
        settingsOpen = false
        playerOpen = false
        startScan()
    }

    /** v3.0 A3: diagnose missing files in a specific folder. */
    fun runScanDiagnostic(folder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            scanDiagnosticResult = "Running diagnostic for '$folder'..."
            val result = com.shiyin.music.data.MediaScanner.diagnoseMissingFiles(getApplication(), folder, tracksRaw)
            scanDiagnosticResult = result
        }
    }

    // ── playback actions ───────────────────────────────────────────────────
    private fun onTrackStarted(id: Long) {
        lyState = LyState.Idle
        // v2: 恢复全局播放速度设置
        player.setPlaybackSpeed(playbackSpeed, retroSpeedMode)
        viewModelScope.launch { settingsStore.pushRecent(id) }
        // v1.2.1: 计数口径改为"累计有效播放满 30 秒"(Spotify 式)。开始播放只记一条
        // completed=0 的事件(供 最近播放 显示);满 30 秒由 onPlayCounted 置 1(计为一次
        // 有效播放),切歌由 onPlayFinalized 回填 playedSec。不再"开始即 +1",误触/跳过不计入。
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val t = trackById(id) ?: return@launch
                dao.insertPlayEvent(
                    com.shiyin.music.data.db.PlayEventEntity(
                        mediaId = id,
                        playedAt = System.currentTimeMillis(),
                        durationSec = (t.durationMs / 1000).toInt(),
                        completed = false,
                    )
                )
            } catch (_: Exception) { }
        }
        // v2.0: push album art to the system MediaSession (notification pill,
        // lock screen, quick-settings media control). Uses setArtworkData
        // (bitmap bytes) because the system MediaController can't read
        // content:// URIs that the old setArtworkUri relied on.
        // v1.1+: 统一用「App 识别匹配后的封面」(AlbumArtCacheEntity.url)，与 App UI
        // 同源——此前读 content://media/.../albumart（文件内嵌旧封面）导致通知栏/锁屏
        // 仍显旧封面。识别封面缺失时回退文件内嵌封面。
        viewModelScope.launch(Dispatchers.IO) {
            val t = trackById(id) ?: return@launch
            val bitmap = fetchMediaSessionCover(t) ?: return@launch
            try {
                val bos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 90, bos)
                player.updateArtworkData(bos.toByteArray(), forMediaId = id)
            } catch (_: Exception) { }
        }
    }

    /** v1.1+: 取 MediaSession 封面 bitmap。优先 App 识别匹配的封面（albumArtCache.url），
     *  缺失则回退文件内嵌封面（content://media/.../albumart/<albumId>）。 */
    private suspend fun fetchMediaSessionCover(t: com.shiyin.music.data.Track): android.graphics.Bitmap? {
        // 1. 识别封面（AlbumArtCacheEntity.url，与 App UI 同源）
        if (t.albumId > 0) {
            try {
                val cache = dao.albumArtCache(t.albumId)
                val url = cache?.takeIf { it.url.isNotBlank() }?.url
                if (url != null) {
                    val hq = url.replace("100x100bb", "600x600bb").replace("100x100", "600x600")
                    val req = okhttp3.Request.Builder().url(hq).build()
                    // 复用共享 OkHttpClient，避免每次切歌新建连接池/线程池泄漏
                    sharedHttpClient.newCall(req).execute().body?.byteStream()?.use { stream ->
                        android.graphics.BitmapFactory.decodeStream(stream)
                    }?.let { return it }
                }
            } catch (_: Exception) { }
        }
        // 2. 回退：文件内嵌封面
        if (t.albumId <= 0) return null
        return try {
            val uri = android.content.ContentUris.withAppendedId(
                android.net.Uri.parse("content://media/external/audio/albumart"), t.albumId,
            )
            getApplication<android.app.Application>().contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) { null }
    }

    /** v1.2.1: 累计有效播放满 30 秒时由 PlayerController 触发——把该次 play_event
     *  计为一次有效播放(completed=1)。这是热度排序/收听统计的唯一计数口径,
     *  误触/跳过(<30s)永远到不了这里,不会污染数据。 */
    private fun onPlayCounted(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try { dao.markPlayCounted(id) } catch (_: Exception) { }
        }
    }

    /** v1.2.1: 切歌/播完时由 PlayerController 触发,回传该次播放的累计有效秒数——
     *  写入 play_event.playedSec,供 收听统计 总时长准确求和(而非用曲目总长冒充)。
     *  顺带 trim 掉 90 天前的事件(最近播放只看近 3 个月)。 */
    private fun onPlayFinalized(id: Long, playedSec: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.finalizePlayEvent(id, playedSec)
                val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
                dao.trimPlayEventsBefore(cutoff)
            } catch (_: Exception) { }
        }
    }

    /** Normal play: whole-library queue in current song order, per handoff. */
    fun play(id: Long) {
        // v5.2 隐藏曲:默认走过滤队列(整库播跳过 hidden)。但若用户点的正是
        // 被隐藏的那首,要让它响起来——但旧修法用 sortedSongs()(全量)会把其他
        // 隐藏曲也放进队列,被点这首播完后自动推进会播到其他隐藏曲,违反"跳过"。
        // 正确:保留被点这首 + 所有非隐藏曲,过滤掉【其他】隐藏曲。
        val queue = if (isHidden(id))
            sortedSongs().filter { it.id == id || !isHidden(it.id) }
        else playbackFilteredSortedSongs()
        player.playQueue(queue, id, null)
    }

    /** Play a track from a content URI (e.g. from a system VIEW intent). */
    fun playUri(uri: android.net.Uri) {
        // Find matching track in the library
        val track = lib().firstOrNull { it.uri.toString() == uri.toString() || it.dataPath == uri.toString() }
        if (track != null) {
            play(track.id)
            playerOpen = true
            return
        }
        // Not found in current library — add as a standalone track
        val t = Track(
            id = -uri.hashCode().toLong(),
            uri = uri,
            title = uri.lastPathSegment?.substringBeforeLast('.') ?: "未知音频",
            artist = UNKNOWN_ARTIST,
            album = NO_ALBUM,
            trackNo = 0,
            durationMs = 0,
            sizeBytes = 0,
            folder = "/",
            dateAdded = System.currentTimeMillis() / 1000,
            albumId = 0,
            dataPath = uri.toString(),
        )
        player.playQueue(listOf(t), t.id, null)
        playerOpen = true
    }

    fun playAlbum(key: String, startId: Long? = null) {
        // v5.2 隐藏曲:整张播放默认跳过 hidden 曲。但若用户点的就是 hidden 的那首,
        // 要让它响起来(用户明确点的才播)——用未过滤队列保证该曲在自己位置能播,
        // 否则 playQueue 找不到 startId 会回落到第一首、播成别的曲。null startId
        // (专辑页"播放"按钮,无明确起点)仍走过滤队列。
        val all = albumOrder(key)
        val tappedHidden = startId != null && isHidden(startId)
        // v5.2 隐藏曲:被点的隐藏曲要能播,但其他隐藏曲仍该跳过——保留被点这首 + 非隐藏曲,
        // 过滤掉【其他】隐藏曲。旧修法用 all(全量)会让其他隐藏曲漏进队列、播完后串播。
        val queue = if (tappedHidden) all.filter { it.id == startId || !isHidden(it.id) } else playbackFiltered(all)
        if (queue.isEmpty()) return
        val realStart = startId
            ?.takeIf { id -> queue.any { it.id == id } }
            ?: queue.first().id
        player.setShuffle(false)
        player.playQueue(queue, realStart, "album:$key")
    }

    fun playRandom() {
        val l = playbackFilteredSortedSongs()
        if (l.isEmpty()) return
        player.setShuffle(true)
        player.playQueue(l, l.random().id, null)
    }

    /** Shuffle-play a specific set of IDs (e.g. artist tracks). */
    fun playRandom(ids: List<Long>) {
        val tracks = playbackFiltered(ids.mapNotNull { trackById(it) })
        if (tracks.isEmpty()) return
        player.setShuffle(true)
        player.playQueue(tracks, tracks.random().id, null)
    }

    /** v1.2.0 #6: 顺序播放整个歌单（从 startId 起；null=从头播）。歌单详情页"播放"用。 */
    fun playPlaylist(pid: String, startId: Long? = null) {
        val all = playlistTrackList(pid)
        val tappedHidden = startId != null && isHidden(startId)
        val queue = if (tappedHidden) all.filter { it.id == startId || !isHidden(it.id) } else playbackFiltered(all)
        if (queue.isEmpty()) return
        val realStart = startId?.takeIf { id -> queue.any { it.id == id } } ?: queue.first().id
        player.setShuffle(false)
        player.playQueue(queue, realStart, "playlist:$pid")
    }

    /** v5.2 隐藏曲 helper:把 [tracks] 中被用户在 track_info_override 标 hidden
     *  的曲过滤掉。Album 整张播、整库播放、随机播都要走这条路径。Single play (单
     * 曲点击播放)不过滤 —— 用户明确点了一首曲,即使它是 hidden 也应该响起来,
     * 否则点击 row 没反应。 */
    private fun playbackFiltered(tracks: List<Track>): List<Track> =
        tracks.filterNot { t -> isHidden(t.id) }

    /** sortedSongs 内部走同样的过滤。 */
    fun playbackFilteredSortedSongs(): List<Track> =
        playbackFiltered(sortedSongs())

    /** v5.2 隐藏曲:用户是否在 track_info_override 标了 hidden。 */
    fun isHidden(mediaId: Long): Boolean =
        (trackInfoOverrides[mediaId]?.hidden ?: 0) != 0

    /** v5.2 隐藏曲:UI 触发时切换指定曲的 hidden 状态。读 fresh-override(避免覆盖
     *  用户在同一曲上设置的 title/artist/note),然后整行 upsert。这是覆盖式
     *  写,所以 UI 上改了 hidden 不会丢其他字段。 */
    fun toggleTrackHidden(mediaId: Long) {
        if (mediaId <= 0) return
        val curHidden = isHidden(mediaId)
        showToast(if (curHidden) "已恢复播放" else "已隐藏此曲", mediaId)
        viewModelScope.launch(Dispatchers.IO) {
            val cur = dao.trackInfoOverride(mediaId)
            dao.upsertTrackInfoOverride(
                com.shiyin.music.data.db.TrackInfoOverrideEntity(
                    mediaId = mediaId,
                    title = cur?.title ?: "",
                    artist = cur?.artist ?: "",
                    note = cur?.note ?: "",
                    hidden = if (curHidden) 0 else 1,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** Assign a single (NO_ALBUM) track to an existing album. */
    fun assignToAlbum(mediaId: Long, albumName: String, artistName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertAlbumOverride(AlbumOverrideEntity(mediaId, albumName, artistName))
        }
        albumPickerId = null
    }

    // ── v4.3: 单曲迁移专辑 ───────────────────────────────────────────────
    /** Move a track into its real album (fixes scan errors where a single was
     *  given its own albumId). Display-level: the track joins the target
     *  album's grouping, cover and album page everywhere. */
    fun moveTrackToAlbum(mediaId: Long, albumId: Long) {
        if (albumId <= 0) return
        // v4.3: 乐观更新内存 map 让 UI 立即 recompose——不等 Room Flow 异步 emit，
        // 否则用户点完迁移、关掉弹层、看到的还是旧归类(须退回首页才刷新，体验割裂)。
        // Flow emit 后会再次赋值 trackAlbumMoves，幂等，无重复副作用。libSig 含
        // albumMoveValues 值 hash，乐观赋值即触发 lib()/albumsMap() 失效重算。
        trackAlbumMoves = trackAlbumMoves + (mediaId to albumId)
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertTrackAlbumMove(
                com.shiyin.music.data.db.TrackAlbumMoveEntity(mediaId, albumId, System.currentTimeMillis())
            )
        }
        albumMoveFor = null
        showToast("已迁移到该专辑", null)
    }

    /** Undo a track → album migration, restoring the scanned albumId. */
    fun clearTrackAlbumMove(mediaId: Long) {
        trackAlbumMoves = trackAlbumMoves - mediaId
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteTrackAlbumMove(mediaId)
        }
        showToast("已恢复原专辑", null)
    }

    // ── v4.3: album-level + single-track manual edits ─────────────────────
    /** Save album-level manual edits (name/artist/type). Blank name/artist keeps
     *  the scanned value; blank `type` keeps the auto-classification. The type
     *  override is what flips the album's grouping in ArtistAlbumList (专辑/EP/单曲)
     *  when the track-count heuristic misreads a partially-downloaded album. */
    fun saveAlbumInfo(albumId: Long, name: String, artist: String, type: String) {
        if (albumId <= 0) return
        // v5.2 #75: DB 读写在 IO,但 state 更新回主线程——Compose snapshot 从后台线程
        // 写不保证立刻 recompose。先在 IO 读 cur(防覆盖 coverUri),回主线程写 state + DB。
        viewModelScope.launch(Dispatchers.IO) {
            val cur = dao.albumInfoOverride(albumId)
            val entity = com.shiyin.music.data.db.AlbumInfoOverrideEntity(
                albumId = albumId,
                albumName = name,
                artistName = artist,
                coverUri = cur?.coverUri ?: "",
                type = type,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsertAlbumInfoOverride(entity)
            if (name.isNotBlank() || artist.isNotBlank()) {
                try { dao.deleteArtCacheForAlbum(albumId) } catch (_: Exception) { }
            }
            // 回主线程更新 state
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                albumInfoOverrides = albumInfoOverrides.toMutableMap().also { it[albumId] = entity }
            }
        }
        com.shiyin.music.ui.components.ArtCache.invalidateAlbum(albumId)
        showToast("专辑信息已保存", null)
    }

    /** Resolve the effective album type ("Album"/"EP"/"Single") for [albumId]:
     *  manual override wins; otherwise fall back to the track-count heuristic
     *  over the album's current tracks. Exposed so the album-edit dialog can
     *  pre-select the current category. */
    fun albumTypeFor(albumId: Long): String {
        if (albumId <= 0) return ""
        albumInfoOverrides[albumId]?.type?.takeIf { it.isNotBlank() }?.let { return it }
        val tracks = albumsMap().entries.firstOrNull { (_, ts) -> ts.firstOrNull()?.albumId == albumId }?.value
            ?: return ""
        return when (com.shiyin.music.ui.screens.classifyAlbum(tracks, null)) {
            com.shiyin.music.ui.screens.AlbumCategory.Album -> "Album"
            com.shiyin.music.ui.screens.AlbumCategory.EP -> "EP"
            com.shiyin.music.ui.screens.AlbumCategory.Single -> "Single"
            com.shiyin.music.ui.screens.AlbumCategory.Compilation -> "Compilation"
        }
    }

    /** Pin a custom cover for an album (uri string from the photo picker). */
    fun saveAlbumCover(albumId: Long, coverUri: String) {
        if (albumId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            // fresh read — don't trust the Flow snapshot, see saveAlbumInfo
            val cur = dao.albumInfoOverride(albumId)
            dao.upsertAlbumInfoOverride(
                com.shiyin.music.data.db.AlbumInfoOverrideEntity(
                    albumId = albumId,
                    albumName = cur?.albumName ?: "",
                    artistName = cur?.artistName ?: "",
                    coverUri = coverUri,
                    type = cur?.type ?: "",
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
        com.shiyin.music.ui.components.ArtCache.invalidateAlbum(albumId)
        showToast("专辑封面已更新", null)
    }

    /** Clear a pinned cover; the album falls back to embedded/online art. */
    fun clearAlbumCover(albumId: Long) {
        if (albumId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            val cur = dao.albumInfoOverride(albumId)
            dao.upsertAlbumInfoOverride(
                com.shiyin.music.data.db.AlbumInfoOverrideEntity(
                    albumId = albumId,
                    albumName = cur?.albumName ?: "",
                    artistName = cur?.artistName ?: "",
                    coverUri = "",
                    type = cur?.type ?: "",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            dao.deleteArtCacheForAlbum(albumId)
        }
        com.shiyin.music.ui.components.ArtCache.invalidateAlbum(albumId)
        showToast("已恢复默认封面", null)
    }

    /** Save single-track display edits (title/artist/note). Blank keeps the scanned value. */
    fun saveTrackInfo(mediaId: Long, title: String, artist: String, note: String) {
        val entity = com.shiyin.music.data.db.TrackInfoOverrideEntity(
            mediaId = mediaId,
            title = title,
            artist = artist,
            note = note,
            updatedAt = System.currentTimeMillis(),
        )
        // v5.2 #75: state 更新在主线程做(不在 Dispatchers.IO 里)——Compose snapshot
        // 系统从后台线程写 state 不保证立刻触发 recompose。DB 写留在 IO,state 写回主线程。
        trackInfoOverrides = trackInfoOverrides.toMutableMap().also { it[mediaId] = entity }
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertTrackInfoOverride(entity)
        }
        showToast("歌曲信息已保存", null)
    }

    /**
     * Resolve the rematch confirmation. `doRematch=true` re-fetches resources with
     * the edited values (album art via iTunes + lyrics for the playing track);
     * `=false` keeps only the manual edits. Caller clears the prompt state.
     */
    fun confirmRematch(target: String, doRematch: Boolean) {
        rematchPromptFor = null
        if (!doRematch) {
            showToast("已保存（未重新匹配资源）", null)
            return
        }
        val albumId = target.removePrefix("album:").toLongOrNull()
        if (target.startsWith("album:") && albumId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try { dao.deleteArtCacheForAlbum(albumId) } catch (_: Exception) { }
            }
            com.shiyin.music.ui.components.ArtCache.invalidateAlbum(albumId)
            // if the playing track belongs to this album, re-fetch its lyrics too
            val cur = trackById(player.currentId)
            if (cur != null && cur.albumId == albumId) {
                currentLyrics = null
                lyricsJob?.cancel()
                lyricsJob = viewModelScope.launch {
                    matchOnline(cur, sourceIndex = 0, thenNextSource = true)
                }
            }
            showToast("已重新匹配专辑资源", null)
        } else if (target.startsWith("track:")) {
            val mediaId = target.removePrefix("track:").toLongOrNull()
            if (mediaId != null && player.currentId == mediaId) {
                currentLyrics = null
                lyricsJob?.cancel()
                lyricsJob = viewModelScope.launch {
                    trackById(mediaId)?.let { matchOnline(it, sourceIndex = 0, thenNextSource = true) }
                }
            }
            showToast("已重新匹配歌曲资源", null)
        }
    }

    fun queueLabel(): String? {
        val k = player.queueKey ?: return null
        if (!k.startsWith("album:")) return null
        return "专辑连播 · " + if (gapless) "无缝衔接" else "常规间隔"
    }

    /** v3.0: create a new playlist. */
    fun createPlaylist() {
        val now = System.currentTimeMillis()
        val id = "p${now}"
        val name = "新歌单 #${playlists.size + 1}"
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertPlaylist(com.shiyin.music.data.db.PlaylistEntity(id, name, playlists.size))
        }
        showToast("已创建「$name」", null)
    }

    // ── v2.0/v3.0: auto-populate artist entities from library (with multi-artist splitting) ─
    private fun ensureArtistEntities(tracks: List<Track>) {
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            // Collect all album names for checking misidentified artists
            val albumNames = tracks.map { it.album.lowercase() }.toSet()
            // Collect all (trackId, artistName) pairs with split artist names
            val pairs = mutableListOf<Pair<Long, String>>()
            for (t in tracks) {
                val resolved = resolveArtist(t.artist)
                val artists = com.shiyin.music.data.MediaScanner.splitArtists(resolved)
                for (a in artists) {
                    pairs.add(t.id to a)
                }
            }
            // Create/update artist entities and song_artist links
            val seenNames = mutableSetOf<String>()
            for ((trackId, artistName) in pairs) {
                if (artistName.isBlank() || artistName == com.shiyin.music.data.UNKNOWN_ARTIST) continue
                // v3.0: skip if artist name matches an album name (likely MediaStore metadata error)
                val nameLower = artistName.lowercase().trim()
                if (nameLower in albumNames) {
                    continue // skip auto-creation, wait for manual confirmation
                }
                try {
                    // Create artist entity if not exists
                    if (artistName !in seenNames) {
                        seenNames.add(artistName)
                        val existing = dao.artistByName(artistName)
                        if (existing == null) {
                            dao.upsertArtist(ArtistEntity(name = artistName, updatedAt = now))
                        }
                    }
                    // Create song_artist link — find the artist id
                    val artist = dao.artistByName(artistName) ?: continue
                    dao.upsertSongArtist(com.shiyin.music.data.db.SongArtistEntity(trackId, artist.id))
                } catch (_: Exception) { }
            }
        }
    }

    // ── artist merge ───────────────────────────────────────────────────────
    /** Merge [from] into [to]. Updates the in-memory alias map synchronously so a
     *  tight forEach over several `from` names — which all share one stale copy
     *  of the live Room-backed `aliases` until the flow re-emits — sees the
     *  cumulative change instead of each call rebuilding from the same snapshot
     *  and dropping its siblings' writes. */
    fun mergeArtist(from: String, to: String) {
        val updated = aliases.toMutableMap()
        for ((k, v) in updated.toMap()) if (v == from) updated[k] = to
        updated[from] = to
        aliases = updated  // sync update so the next merge in a batch sees this
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertAliases(updated.map { (f, t) -> ArtistAliasEntity(f, t) })
        }
        artistKey = to
        artistMerge = false
    }

    fun unmergeArtist(from: String) {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteAlias(from) }
    }

    // ── folders ────────────────────────────────────────────────────────────
    fun ignoreFolder(path: String) {
        ignored = ignored + path // optimistic; the Room flow re-emits the same set
        viewModelScope.launch(Dispatchers.IO) { dao.addIgnored(IgnoredFolderEntity(path)) }
        val cur = player.currentId
        if (cur != null && tracksRaw.firstOrNull { it.id == cur }?.folder == path) {
            player.stopAndClear()
            playerOpen = false
        } else {
            resyncQueue()
        }
    }

    fun restoreFolder(path: String) {
        viewModelScope.launch(Dispatchers.IO) { dao.removeIgnored(path) }
    }

    // ── trash ──────────────────────────────────────────────────────────────
    private fun uriOf(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    fun requestTrash(ids: List<Long>) {
        if (ids.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val pi = MediaStore.createTrashRequest(resolver, ids.map(::uriOf), true)
        pendingTrashOp = TrashOp.Trash(ids)
        launchTrashIntent?.invoke(pi)
    }

    fun requestRestore(ids: List<Long>) {
        if (ids.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val pi = MediaStore.createTrashRequest(resolver, ids.map(::uriOf), false)
        pendingTrashOp = TrashOp.Restore(ids)
        launchTrashIntent?.invoke(pi)
    }

    fun requestEmptyTrash() {
        val ids = trashMirror.map { it.mediaId }
        if (ids.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val pi = MediaStore.createDeleteRequest(resolver, ids.map(::uriOf))
        pendingTrashOp = TrashOp.Delete(ids)
        launchTrashIntent?.invoke(pi)
    }

    fun onTrashResult(ok: Boolean) {
        val op = pendingTrashOp ?: return
        pendingTrashOp = null
        if (!ok) return
        when (op) {
            is TrashOp.Trash -> {
                val byId = tracksRaw.associateBy { it.id }
                val now = System.currentTimeMillis()
                val entries = op.ids.mapNotNull { id ->
                    byId[id]?.let {
                        TrashedTrackEntity(id, it.title, resolveArtist(it.artist), it.folder, it.sizeBytes, now)
                    }
                }
                viewModelScope.launch(Dispatchers.IO) { dao.addTrashed(entries) }
                sel = emptyMap()
                if (filesView == FilesView.Clean) filesView = FilesView.Root
                val removed = op.ids.toSet()
                val cur = player.currentId
                tracksRaw = tracksRaw.filter { it.id !in removed }
                if (cur != null && cur in removed) {
                    player.stopAndClear()
                    playerOpen = false
                    lyricsOn = false
                } else {
                    resyncQueue()
                }
            }
            is TrashOp.Restore -> {
                viewModelScope.launch(Dispatchers.IO) { dao.removeTrashed(op.ids) }
                rescanSilently()
            }
            is TrashOp.Delete -> {
                viewModelScope.launch(Dispatchers.IO) { dao.removeTrashed(op.ids) }
            }
        }
    }

    // ── settings toggles ───────────────────────────────────────────────────
    fun setDark(v: Boolean) = viewModelScope.launch { settingsStore.setDark(v) }
    fun setGapless(v: Boolean) = viewModelScope.launch { settingsStore.setGapless(v) }
    fun setAutoMatch(v: Boolean) = viewModelScope.launch { settingsStore.setAutoMatch(v) }
    fun setAutoSaveRecognition(v: Boolean) = viewModelScope.launch { settingsStore.setAutoSaveRecognition(v) }
    fun setSpeed(v: Float) {
        playbackSpeed = v
        player.setPlaybackSpeed(v, retroSpeedMode)
        viewModelScope.launch { settingsStore.setPlaybackSpeed(v) }
    }
    fun setRetroMode(v: Boolean) {
        retroSpeedMode = v
        player.setPlaybackSpeed(playbackSpeed, v)
        viewModelScope.launch { settingsStore.setRetroSpeedMode(v) }
    }
    fun setDeepSeekKey(v: String) = viewModelScope.launch { settingsStore.setDeepSeekKey(v) }

    // ── playlists ──────────────────────────────────────────────────────────
    fun playlistTrackList(pid: String): List<Track> {
        val ids = playlistTracks[pid] ?: return emptyList()
        val byId = lib().associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    fun togglePlaylistMembership(pid: String, mediaId: Long) {
        val inPl = playlistTracks[pid]?.contains(mediaId) == true
        viewModelScope.launch(Dispatchers.IO) {
            if (inPl) dao.removeFromPlaylist(pid, mediaId)
            else dao.addToPlaylist(PlaylistTrackEntity(pid, mediaId, System.currentTimeMillis()))
        }
    }

    // v3.0: playlist management (rename / cover / delete)
    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) { dao.renamePlaylist(id, name.trim()) }
    }

    /** Set a playlist's cover to a chosen album, or null to revert to the auto mosaic. */
    fun setPlaylistCover(id: String, albumId: Long?) {
        viewModelScope.launch(Dispatchers.IO) { dao.setPlaylistCover(id, albumId) }
    }

    fun deletePlaylist(id: String) {
        // v1.2.0 #6: 内置「我的喜欢」(p3)默认存在不可删。
        if (id == "p3") { showToast("此歌单不可删除", null); return }
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearPlaylistTracks(id)
            dao.deletePlaylist(id)
        }
        plId = null
    }

    /**
     * v3.0: a representative [Track] to render the chosen playlist cover.
     * Prefers the playlist's explicitly-chosen [coverAlbumId]; otherwise picks
     * the first track (for the auto-mosaic, callers pass the whole track list
     * and let CoverArtMosaic assemble up to 4 covers from it).
     */
    fun playlistCoverTrack(pid: String): Track? {
        val pl = playlists.firstOrNull { it.id == pid } ?: return null
        val coverId = pl.coverAlbumId
        if (coverId != null) {
            // Any library track that belongs to the chosen album drives a single-cover render.
            return lib().firstOrNull { it.albumId == coverId }
        }
        return null
    }

    // ── lyrics ─────────────────────────────────────────────────────────────
    /** Resolution pipeline: saved → embedded USLT → sidecar .lrc → (auto) online. */
    fun loadLyricsFor(id: Long) {
        android.util.Log.d("LyricsLoadDebug", "loadLyricsFor id=$id curMediaId=${currentLyrics?.mediaId} lyState=$lyState")
        // v5.2 #67: 旧守卫 `lyState != LyState.Failed` 会让失败后重新进歌词本直接 return
        // 不重试——用户只能手动切源才触发(于是"显示无资源、手动切又有")。改成:同一首歌
        // 且非失败态才跳过;失败态允许重试级联(源1无→源2),两个都没才真显示无资源。
        if (currentLyrics?.mediaId == id && lyState != LyState.Failed) {
            android.util.Log.d("LyricsLoadDebug", "loadLyricsFor SKIP (same id, not failed)")
            return
        }
        lyricsJob?.cancel()
        currentLyrics = null
        lyState = LyState.Idle
        lySheet = false
        val track = trackById(id) ?: return
        lyricsJob = viewModelScope.launch {
            savedLyricsMap[id]?.let { e ->
                currentLyrics = LoadedLyrics(
                    id, e.lyrics, LrcParser.parse(e.lyrics), e.source,
                    if (e.source.endsWith(".lrc")) LyricsKind.Imported else LyricsKind.Online,
                    saved = true, offsetMs = e.offsetMs,
                )
                return@launch
            }
            val embedded = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(track.uri)?.use { UsltReader.read(it) }
                } catch (_: Exception) {
                    null
                }
            }
            if (embedded != null) {
                currentLyrics = LoadedLyrics(
                    id, embedded, LrcParser.parse(embedded), "内嵌歌词 · ID3",
                    LyricsKind.Embedded, saved = true, offsetMs = 0,
                )
                return@launch
            }
            val sidecar = withContext(Dispatchers.IO) {
                try {
                    val f = File(track.dataPath.substringBeforeLast('.') + ".lrc")
                    if (f.exists() && f.canRead()) f.readText() else null
                } catch (_: Exception) {
                    null
                }
            }
            if (sidecar != null) {
                currentLyrics = LoadedLyrics(
                    id, sidecar, LrcParser.parse(sidecar), "同名 .lrc",
                    LyricsKind.Sidecar, saved = true, offsetMs = 0,
                )
                return@launch
            }
            if (autoMatch && track.artist != UNKNOWN_ARTIST && id !in triedAuto) {
                triedAuto += id
                matchOnline(track, sourceIndex = 0, thenNextSource = true)
            }
        }
    }

    fun matchLyricsManually() {
        val track = trackById(player.currentId) ?: return
        triedAuto += track.id
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch { matchOnline(track, 0, thenNextSource = true) }
    }

    fun rematchNextSource() {
        val track = trackById(player.currentId) ?: return
        val curSrc = currentLyrics?.source
        val idx = (LyricsFetcher.SOURCES.indexOf(curSrc) + 1).mod(LyricsFetcher.SOURCES.size)
        lySheet = false
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            matchOnline(track, idx, thenNextSource = false, reopenSheet = true)
        }
    }

    /** v3.0: match lyrics from a specific source. */
    fun rematchSource(source: String) {
        val track = trackById(player.currentId) ?: return
        val idx = LyricsFetcher.SOURCES.indexOf(source).coerceAtLeast(0)
        lySheet = false
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            matchOnline(track, idx, thenNextSource = false, reopenSheet = true)
        }
    }

    /** v2.0: try DeepSeek AI for lyrics. */
    fun rematchWithAI() {
        val track = trackById(player.currentId) ?: return
        if (deepseekApiKey.isBlank()) return
        lySheet = false
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            lyState = LyState.Searching
            try {
                val raw = com.shiyin.music.data.ai.DeepSeekService.searchLyrics(deepseekApiKey, track.title, track.artist)
                if (raw != null) {
                    val parsed = LrcParser.parse(raw)
                    if (!parsed.isEmpty) {
                        currentLyrics = LoadedLyrics(track.id, raw, parsed, "deepseek", LyricsKind.Online, saved = false, offsetMs = 0)
                        lyState = LyState.Idle
                        lySheet = true
                        autoSaveRecognizedLyrics(track.id, raw, "deepseek")
                        return@launch
                    }
                }
            } catch (_: Exception) { }
            lyState = LyState.Failed
        }
    }

    private suspend fun matchOnline(track: Track, sourceIndex: Int, thenNextSource: Boolean, reopenSheet: Boolean = false) {
        lyState = LyState.Searching
        val sources = LyricsFetcher.SOURCES
        // Bug4a: try sources in order, preferring any that yields timestamped
        // lyrics (LRC). Plain-text results are accepted only as a last resort
        // (no other source produced anything) — same logic for the auto
        // `thenNextSource` path and the explicit source-pick path.
        var syncedFound: Pair<String, String>? = null
        var plainFound: Pair<String, String>? = null
        var i = sourceIndex
        var attempts = 0
        val maxAttempts = if (thenNextSource) sources.size else 1
        while (attempts < maxAttempts) {
            val src = sources[i % sources.size]
            val raw = LyricsFetcher.fetch(src, track.title, track.artist, track.album, track.durationSec)
            if (raw != null) {
                val parsed = LrcParser.parse(raw)
                if (!parsed.isEmpty) {
                    if (parsed.synced) {
                        syncedFound = src to raw
                        break
                    } else if (plainFound == null) {
                        plainFound = src to raw
                    }
                }
            }
            i++; attempts++
        }
        var found: Pair<String, String>? = syncedFound ?: plainFound
        // v2.0: try DeepSeek as fallback when API key is set
        if (found == null && deepseekApiKey.isNotBlank()) {
            val aiRaw = com.shiyin.music.data.ai.DeepSeekService.searchLyrics(deepseekApiKey, track.title, track.artist)
            if (aiRaw != null) {
                val parsed = LrcParser.parse(aiRaw)
                if (!parsed.isEmpty) {
                    found = "deepseek" to aiRaw
                    // AI fallback always wins over a plain-text local source.
                }
            }
        }
        if (found == null) {
            // v5.2 #67: 级联全失败时,从 triedAuto 移除该曲——下次进歌词本能重新自动级联
            // (否则 triedAuto 永久挡住,只能手动切源)。这样"两个都没才显示无资源"成立。
            triedAuto -= track.id
            lyState = LyState.Failed
            return
        }
        val (src, raw) = found
        val parsed = LrcParser.parse(raw)
        if (parsed.isEmpty) {
            lyState = LyState.Failed
            return
        }
        currentLyrics = LoadedLyrics(track.id, raw, parsed, src, LyricsKind.Online, saved = false, offsetMs = 0)
        lyState = LyState.Idle
        if (reopenSheet) lySheet = true
        // v1.1+: 自动保存识别结果——开关开时把匹配成功的歌词持久化（autoSaved=1），
        // 下次打开直接从本地加载，不再联网。关时仅临时展示。封面已由 album_art_cache 持久化。
        autoSaveRecognizedLyrics(track.id, raw, src)
    }

    /** [targetId] is captured when the picker is launched, so a track change
     *  mid-pick never attaches the file to the wrong song. */
    fun importLrcContent(name: String, content: String, targetId: Long) {
        if (player.currentId != targetId) return
        val parsed = LrcParser.parse(content)
        if (parsed.isEmpty) return
        triedAuto += targetId
        currentLyrics = LoadedLyrics(targetId, content, parsed, name, LyricsKind.Imported, saved = false, offsetMs = 0)
        lyState = LyState.Idle
    }

    fun bumpLyricsOffset(deltaMs: Long) {
        val cur = currentLyrics ?: return
        currentLyrics = cur.copy(offsetMs = cur.offsetMs + deltaMs, saved = false)
    }

    /** v2.0: sync a line of unsynced lyrics to the current playback position.
     *  Bug4b fix: when the user's click turns the last un-timestamped line into
     *  a stamped one (i.e. parsed.synced flips false→true), persist immediately
     *  so they don't have to remember to hit "保存". */
    fun syncLyricLine(lineIdx: Int) {
        val cur = currentLyrics ?: return
        val pos = player.positionMs
        val lines = cur.parsed.lines.toMutableList()
        if (lineIdx < 0 || lineIdx >= lines.size) return
        val newTime = (pos - cur.offsetMs).coerceAtLeast(0)
        lines[lineIdx] = com.shiyin.music.data.lyrics.LyricLine(newTime, lines[lineIdx].text)
        // Convert to timed: sort by time, mark synced
        val sorted = lines.sortedBy { it.timeMs ?: 0 }
        val wasSynced = cur.parsed.synced
        val nowSynced = sorted.all { it.timeMs != null }
        val next = cur.copy(
            parsed = com.shiyin.music.data.lyrics.ParsedLyrics(
                lines = sorted,
                synced = nowSynced,
            ),
            saved = false,
        )
        currentLyrics = next
        if (!wasSynced && nowSynced) {
            // The user just finished time-stamping every line — save right away.
            viewModelScope.launch(Dispatchers.IO) {
                dao.upsertLyric(SavedLyricEntity(next.mediaId, next.raw, next.source, next.offsetMs, System.currentTimeMillis()))
            }
            currentLyrics = next.copy(saved = true)
        }
    }

    fun saveLyrics() {
        val cur = currentLyrics ?: return
        if (!cur.canAdjust) return
        currentLyrics = cur.copy(saved = true)
        viewModelScope.launch(Dispatchers.IO) {
            // 手动确认保存 → autoSaved=0（清理缓存时保留）
            dao.upsertLyric(SavedLyricEntity(cur.mediaId, cur.raw, cur.source, cur.offsetMs, System.currentTimeMillis(), autoSaved = 0))
        }
    }

    /** v1.1+: 自动保存识别结果。开关开时把联网匹配成功的歌词持久化（autoSaved=1），
     *  下次打开直接本地加载。关时不写。任一异常静默（不影响展示）。
     *  ⚠️ 用 upsertAutoSavedLyric：只更新已有自动行或插入新行，**绝不覆盖手动保存
     *  (autoSaved=0)**——否则 clearRecognitionCache 会误删手动歌词（code-review 数据丢失 bug）。 */
    fun autoSaveRecognizedLyrics(mediaId: Long, raw: String, source: String) {
        if (!autoSaveRecognition) return
        val cur = currentLyrics ?: return
        // 不在写入前设 saved=true：DB 写入失败时 UI 不应显示"已保存"。
        // 旧代码在 launch 前设 saved=true，写入失败被 catch 吞掉 → 假成功。
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.upsertAutoSavedLyric(mediaId, raw, source, cur.offsetMs, System.currentTimeMillis())
                withContext(Dispatchers.Main) {
                    currentLyrics = currentLyrics?.copy(saved = true)
                }
            } catch (_: Exception) { }
        }
    }

    /** v1.1+: 统计自动识别缓存占用（自动保存的歌词字节数 + 封面缓存行数*估算）。 */
    fun refreshRecognitionCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val autoLyrics = savedLyricsMap.values.filter { it.autoSaved == 1 }
                    .sumOf { it.lyrics.toByteArray(Charsets.UTF_8).size }
                val coverRows = dao.albumArtCacheCount()
                recognitionCacheBytes = (autoLyrics + coverRows * 30 * 1024).toLong()
            } catch (_: Exception) { recognitionCacheBytes = 0L }
        }
    }

    /** v1.1+: 清理自动识别缓存（自动保存歌词 autoSaved=1 + 封面缓存）。保留：手动保存/导入
     *  歌词(autoSaved=0)、Song Override、外部假名 evidence、track_info_override。 */
    fun clearRecognitionCache() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.deleteAutoSavedLyrics()
                dao.clearAlbumArtCache()
                // 清色缓存内存态，下次按新算法重取
                com.shiyin.music.ui.components.ArtCache.clearAll(getApplication())
                // v1.2.0 阶段二：清 tracksRaw 磁盘缓存 + 签名，下次冷启动强制全量重扫
                trackCache.clear()
            } catch (_: Exception) { }
            refreshRecognitionCacheSize()
        }
    }

    // ── v1.2.0 阶段三：修正数据导出/导入 ───────────────────────────────────
    /** 导出全部修正数据为 JSON 字符串。UI 用 SAF 拿到 OutputStream 后调用。 */
    suspend fun exportBackup(): String = withContext(Dispatchers.IO) { backupManager.export() }

    /** 从 JSON 导入修正数据。返回导入统计；失败抛异常由 UI 捕获提示。 */
    suspend fun importBackup(json: String): String = withContext(Dispatchers.IO) {
        val stats = backupManager.import(json)
        // 导入后刷新内存态，让 UI 立即反映新修正
        if (!firstScanDone) { /* 未扫完不重载 */ }
        else { rescanSilently() }
        stats.toString()
    }

    fun deleteLyrics() {
        val cur = currentLyrics ?: return
        currentLyrics = null
        lySheet = false
        lyricsOn = false
        lyState = LyState.Idle
        triedAuto -= cur.mediaId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.deleteLyric(cur.mediaId)
                dao.deleteReadingOverridesForMedia(cur.mediaId)
                dao.deleteExternalEvidenceForMedia(cur.mediaId)
            } catch (_: Exception) { }
        }
    }

    // ── v1.1.0: 振假名 Song Override（当て字/歌曲专属读法）─────────────────────
    /** 对当前歌词跑 V1.1+ 准确率流水线，返回逐行 RubySegment（含 startOffset）。
     *  优先级：Occurrence Override > 外部 evidence（缓存）> 纠错小表 > JMdict
     *  （单读法/CONFLICT→ContextResolver）> Kuromoji > No Reading。后台线程调用。 */
    suspend fun furiganaSegmentsFor(ly: com.shiyin.music.LoadedLyrics): List<List<com.shiyin.music.ui.components.RubySegment>> {
        val hash = com.shiyin.music.data.furigana.LyricsHash.of(ly.raw)
        val overrides = try {
            dao.songReadingOverrides(ly.mediaId, hash)
        } catch (_: Exception) { emptyList() }
        val byLine: Map<Int, Map<Int, String>> = overrides.groupBy { it.lineIndex }
            .mapValues { (_, rows) -> rows.associate { it.charStart to it.reading } }
        // 外部 evidence（缓存，离线）—— 优先级 2，介于 Occurrence Override 与 ContextResolver。
        // charStart→(length, reading)：保留 run 长度，使合并段（音楽）能拼接逐字 run（おん+がく）。
        val extByLine: Map<Int, Map<Int, Pair<Int, String>>> = try {
            dao.externalEvidence(ly.mediaId, hash).groupBy { it.lineIndex }
                .mapValues { (_, rows) -> rows.associate { it.charStart to (it.length to it.reading) } }
        } catch (_: Exception) { emptyMap() }
        return ly.parsed.lines.mapIndexed { idx, line ->
            com.shiyin.music.data.lyrics.FuriganaTokenizer.toSegments(
                line.text,
                lineOverrides = byLine[idx],
                externalEvidence = extByLine[idx],
                correction = com.shiyin.music.data.furigana.LyricsReadingOverrides,
                jmdict = furiganaJmdict,
            )
        }
    }

    /**
     * v1.1+: 异步抓取外部带假名歌词 evidence。每步给用户明确反馈（不吞异常）。
     * 成功后缓存到 (mediaId+lyricsHash)，后续完全离线。任一失败不影响本地显示。
     * ⚠️ UtaTen 假名为第三方内容——正式发行前须合规评估，当前为实验性手动触发。
     */
    fun fetchExternalReadingEvidence() {
        val cur = currentLyrics ?: run {
            externalFetchStatus = "错误：歌词尚未加载"
            return
        }
        val track = trackById(cur.mediaId) ?: run {
            externalFetchStatus = "错误：未找到曲目信息"
            return
        }
        val hash = com.shiyin.music.data.furigana.LyricsHash.of(cur.raw)
        externalFetchStatus = "loading"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 已缓存则跳过
                if (dao.externalEvidence(cur.mediaId, hash).isNotEmpty()) {
                    withContext(Dispatchers.Main) { externalFetchStatus = "已有缓存（无需重复获取）" }
                    return@launch
                }
                val localLines = cur.parsed.lines.map { it.text }
                val provider = com.shiyin.music.data.furigana.external.UtaTenProvider(
                    diagFilePath = getApplication<android.app.Application>().getExternalFilesDir(null)?.resolve("furigana_align_diag.txt")?.absolutePath
                )
                val occ = provider.resolve(track.title, track.artist, localLines)
                if (occ == null) {
                    withContext(Dispatchers.Main) { externalFetchStatus = "失败：未知原因" }
                    return@launch
                }
                // 存 evidence
                val now = System.currentTimeMillis()
                val rows = occ.map {
                    com.shiyin.music.data.db.ExternalReadingEvidenceEntity(
                        mediaId = cur.mediaId, lyricsHash = hash,
                        lineIndex = it.lineIndex, charStart = it.charStart, length = it.length,
                        surface = "", reading = it.reading, source = it.source, confidence = 1, fetchedAt = now,
                    )
                }
                dao.upsertExternalEvidence(rows)
                bumpFuriganaRevision()
                withContext(Dispatchers.Main) { externalFetchStatus = "成功：保存了 ${occ.size} 个注音" }
            } catch (e: com.shiyin.music.data.furigana.external.UtaTenProvider.ResolveException) {
                val msg = when (e.reason) {
                    com.shiyin.music.data.furigana.external.UtaTenProvider.Reason.NO_MATCH -> "失败：未找到匹配歌曲"
                    com.shiyin.music.data.furigana.external.UtaTenProvider.Reason.NO_FURIGANA -> "失败：来源页面无假名数据"
                    com.shiyin.music.data.furigana.external.UtaTenProvider.Reason.NETWORK -> "失败：${e.message}"
                    com.shiyin.music.data.furigana.external.UtaTenProvider.Reason.PARSE -> "失败：解析异常"
                    com.shiyin.music.data.furigana.external.UtaTenProvider.Reason.ALIGN -> "失败：本地歌词无法可靠对齐"
                }
                withContext(Dispatchers.Main) { externalFetchStatus = msg }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { externalFetchStatus = "失败：${t.message ?: "未知错误"}" }
            }
        }
    }

    /** 保存当前曲当前版本某出现位置的 Song Override（当て字/歌曲专属读法）。
     *  按 lineIndex+charStart 定位，同一 surface 多次出现可分别设（何 有的なに 有的なん）。
     *  绑 mediaId+lyricsHash，换源/改文本自动失效。不碰 raw LRC/LyricLine/SavedLyrics。 */
    fun saveReadingOverrideAt(lineIndex: Int, charStart: Int, surface: String, reading: String) {
        val cur = currentLyrics ?: return
        val hash = com.shiyin.music.data.furigana.LyricsHash.of(cur.raw)
        val clean = reading.trim()
        viewModelScope.launch(Dispatchers.IO) {
            if (clean.isEmpty()) {
                dao.deleteReadingOverride(cur.mediaId, hash, lineIndex, charStart)
            } else {
                dao.upsertReadingOverride(
                    com.shiyin.music.data.db.ReadingOverrideEntity(
                        scope = "SONG",
                        mediaId = cur.mediaId,
                        lyricsHash = hash,
                        lineIndex = lineIndex,
                        charStart = charStart,
                        surface = surface,
                        reading = clean,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            bumpFuriganaRevision()
        }
    }

    /**
     * v1.2.0: 批量保存某 surface 在本曲全部出现位置的读法（振假名批量修正）。
     * [occurrences] = 该 surface 在本曲的 (lineIndex, charStart) 列表（由 UI 从
     * 分词段收集）。空 reading 清除全部（回退自动读法）。一次 IO 协程 + 一次
     * furiganaRevision bump，避免 N 次单独保存触发 N 次重算。
     */
    fun saveReadingOverrideBatch(occurrences: List<Pair<Int, Int>>, surface: String, reading: String) {
        val cur = currentLyrics ?: return
        val hash = com.shiyin.music.data.furigana.LyricsHash.of(cur.raw)
        val clean = reading.trim()
        viewModelScope.launch(Dispatchers.IO) {
            for ((lineIndex, charStart) in occurrences) {
                if (clean.isEmpty()) {
                    dao.deleteReadingOverride(cur.mediaId, hash, lineIndex, charStart)
                } else {
                    dao.upsertReadingOverride(
                        com.shiyin.music.data.db.ReadingOverrideEntity(
                            scope = "SONG", mediaId = cur.mediaId, lyricsHash = hash,
                            lineIndex = lineIndex, charStart = charStart,
                            surface = surface, reading = clean, updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            bumpFuriganaRevision()
        }
    }

    /** 清除当前曲当前版本某出现位置的 Song Override（回退到 JMdict/Kuromoji）。 */
    fun deleteReadingOverrideAt(lineIndex: Int, charStart: Int) {
        val cur = currentLyrics ?: return
        val hash = com.shiyin.music.data.furigana.LyricsHash.of(cur.raw)
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteReadingOverride(cur.mediaId, hash, lineIndex, charStart)
            bumpFuriganaRevision()
        }
    }

    // ── clean-suggestion selection (prefilled per prototype) ───────────────
    fun openClean() {
        val prefill = HashMap<Long, Boolean>()
        for (g in dupGroups()) g.drop(1).forEach { prefill[it.id] = true }
        for (t in shortTracks()) prefill[t.id] = true
        sel = prefill
        filesView = FilesView.Clean
        settingsOpen = true
    }

    fun toggleSel(id: Long) {
        sel = sel.toMutableMap().apply { this[id] = !(this[id] ?: false) }
    }

    // ── navigation helpers (mirror the prototype's setState calls) ─────────
    fun openPlaylist(pid: String) {
        plId = pid; tab = Tab.Library; libChip = "pls"
        settingsOpen = false; albumKey = null; artistKey = null
    }

    fun openArtist(name: String) {
        artistKey = name; tab = Tab.Library
        settingsOpen = false; artistMerge = false; albumKey = null; plId = null
    }

    fun openAlbum(key: String) {
        albumKey = key; albumEdit = false; tab = Tab.Library; settingsOpen = false
        // v4: mark album as "viewed" for 你的更新 (one-shot lifecycle)
        if (key.startsWith("aid:")) {
            val aid = key.removePrefix("aid:").toLongOrNull() ?: return
            viewModelScope.launch(Dispatchers.IO) {
                try { dao.markAlbumViewed(aid) } catch (_: Exception) { }
            }
        }
    }

    // ── v1.1/v1.7 navigation from player & row menus ───────────────────────
    private fun closeOverlays() {
        playerOpen = false; lyricsOn = false; lySheet = false
        pMenuView = null; qSheetOpen = false; qEdit = false; trackMenuFor = null
    }

    /** Tap cover / 查看专辑: open the track's album; [highlight] scroll-marks it. */
    fun goAlbumOf(id: Long, highlight: Boolean) {
        val t = trackById(id) ?: return
        if (t.album == NO_ALBUM) return
        closeOverlays()
        hlId = if (highlight) t.id else null
        plId = null; artistKey = null
        openAlbum(albumKeyOf(t.album, t.artist, t.albumId))
    }

    /**
     * Open the artist page for the given track's primary artist.
     *
     * v3.0: previously this passed `t.artist` straight to [openArtist], but that
     * field is the *split-and-joined* multi-artist display string (e.g. "A, B"),
     * while the artist page's key is a single split name. So taps on multi-artist
     * tracks always missed and the page bounced right back — "点歌手名跳不出来了".
     *
     * Now: single-artist tracks go straight in; multi-artist tracks pop up a
     * picker (see [artistPickerFor]) so the user can choose which one to open,
     * rather than guessing the first. This is independent of the drag-reorder
     * work in another session — no LibraryScreen row/touch code is touched here.
     */
    fun goArtistOf(id: Long) {
        val t = trackById(id) ?: return
        val artists = com.shiyin.music.data.MediaScanner.splitArtists(t.artist)
        closeOverlays()
        if (artists.size <= 1) {
            openArtist(artists.firstOrNull() ?: t.artist)
        } else {
            // Surface the picker; the AppRoot overlay renders the dialog and calls
            // openArtist(name) on selection. We don't auto-open so multi-artist users
            // can pick exactly which collaborator's page they meant to land on.
            artistPickerFor = artists
        }
    }

    // ── v1.1/v1.5 favorites + toast ────────────────────────────────────────
    fun isFav(id: Long): Boolean = playlistTracks["p3"]?.contains(id) == true

    fun toggleFav(id: Long) {
        val adding = !isFav(id)
        togglePlaylistMembership("p3", id)
        showToast(if (adding) "已收藏至「我的喜欢」" else "已取消收藏", id)
    }

    fun showToast(text: String, changeTargetId: Long? = null) {
        toast = ToastData(text, changeTargetId)
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1800)
            toast = null
        }
    }

    /** v1.2.0: 保存赞赏码图到相册(Pictures/PickUpMusic),方便用另一台手机扫码。 */
    fun saveSupportImage(resId: Int, displayName: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<android.app.Application>()
                    val bmp = android.graphics.BitmapFactory.decodeResource(ctx.resources, resId) ?: return@runCatching false
                    val resolver = ctx.contentResolver
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PickUpMusic")
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                    resolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } ?: return@runCatching false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                    true
                }.getOrDefault(false)
            }
            showToast(if (ok) "已保存到相册：Pictures/PickUpMusic" else "保存失败，请重试")
        }
    }

    // ── v1.7 manual queue ──────────────────────────────────────────────────
    fun addToQueue(id: Long) {
        val t = trackById(id) ?: return
        // v5.2 隐藏曲:不让 hidden 曲进队列。菜单里隐藏曲的三点 ⋮ 项已经换成
        // "恢复播放",所以走 menu 加队列通常不存在;这里 double-check 防直接从
        // Library row tap 抢过去就播(虽然 Library row tap 是单曲 play 路径,
        // 不过 addToQueue 也会从 AlbumOrder 内部加,如果某行 status 有 hidden
        // 状态,它本来就不该被列为 queue next)。
        if (isHidden(t.id)) {
            showToast("已隐藏此曲,无法加入队列", t.id)
            return
        }
        player.addToQueueNext(t)
        trackMenuFor = null
        showToast("已加入播放队列", id)
    }

    // ── v2.0 playback devices (real routing via DeviceRouter) ────────────────
    fun curDeviceName(): String = deviceRouter.availableDevices
        .firstOrNull { it.id == deviceRouter.activeDeviceId }?.name
        ?: deviceRouter.availableDevices.firstOrNull()?.name
        ?: "本机扬声器"

    /** Active device's icon category, for dynamic icon in PlayerScreen / sheets. */
    fun activeDeviceKind(): String = deviceRouter.activeKind()

    /**
     * v5.2 Bug1: User tapped a device in the in-app picker. Persist the
     * sticky preference (DeviceRouter) and forward the address to the
     * PlaybackService via the custom SessionCommand (PlayerController) so
     * `MediaCodecAudioRenderer.MSG_SET_PREFERRED_AUDIO_DEVICE` is applied to
     * the live ExoPlayer — real in-app routing, no system-panel detour.
     * Pass "" or null to clear routing back to the built-in speaker.
     */
    fun selectDevice(address: String?) {
        val resolved = address?.takeIf { it.isNotBlank() }
        deviceRouter.selectDevice(resolved)
        player.requestDeviceRouting(resolved.orEmpty())
        refreshActiveDeviceSoon()
    }

    /**
     * v5.2 Bug2: one-tap wipe of the "你的更新" history. Clears the new_album
     * table only — does NOT touch knownAlbumIds (the in-memory skip-set),
     * so new arrivals on the *next* scan are still detected correctly. Used
     * to discard residuals left by older-version scans that didn't have the
     * firstScanDone gate. See detectNewAlbums comment.
     */
    fun clearAlbumUpdatesHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try { dao.clearAllNewAlbums() } catch (_: Exception) { }
        }
        showToast("已清空\"你的更新\"历史", null)
    }

    /**
     * v5.2 Bug1: when deviceChanges() reports a pending sticky-restore (e.g.
     * the user's BT device just reconnected and we want to re-route to it),
     * consume the pending address and forward it to the service. Called from
     * the deviceChanges collector.
     */
    fun applyPendingRoutingFromDeviceRouter() {
        val addr = deviceRouter.consumePendingRoutingAddress()
        if (addr != null) player.requestDeviceRouting(addr)
    }

    private var refreshJob: kotlinx.coroutines.Job? = null
    private fun refreshActiveDeviceSoon() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            deviceRouter.refreshDeviceList()
            deviceRouter.refreshActiveDevice()
        }
    }
}

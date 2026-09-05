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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

enum class Tab { Home, Search, Library }
enum class ObStage { Perm, Scan, Done }
enum class FilesView { Root, Clean, Trash, Folders, FolderContent, Ignored, Devices, About, Merges, ImageSources }
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
    /** v1.2.1: 与 player 同寿命的 app 级 scope。play-tracking DB 写(insertPlayEvent/markCounted/
     *  finalizePlayEvent/pushRecent)用它而非 viewModelScope——viewModelScope 在 onCleared 前已被
     *  cancel,退出时 flush 的 finalizePlayEvent 会变 no-op 致 playedSec 永久丢;且 Activity 销毁后
     *  player 仍在 appScope 后台播,改用 appScope 后台播放仍被追踪。 */
    private val appScope = (app as com.shiyin.music.ShiyinApp).appScope
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

    /** v1.3.3b: 单张专辑的发布日期(ISO 串,如 "1987-07-21T07:00:00Z";空=无)——
     *  专辑页歌手名下显示用。 */
    suspend fun releaseDateOf(albumId: Long): String = withContext(Dispatchers.IO) {
        if (albumId <= 0) "" else dao.albumArtCache(albumId)?.releaseDate ?: ""
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
    /** v1.3.5: 经 alias 归一再查(统计页/搜索页拿到的歌手名可能是合并前的原名,
     *  直接按原名查缓存 miss → resolve 又触发一次网络、还常拿空 →"写真明明有,
     *  这里却占位"的根因)。命中别名归并直接复用规范名的 URL。 */
    fun artistImage(name: String): String =
        artistImages[name] ?: artistImages[resolveArtist(name)] ?: ""
    var playCounts by mutableStateOf<Map<Long, Int>>(emptyMap()); private set
    var albumOverrides by mutableStateOf<Map<Long, AlbumOverrideEntity>>(emptyMap()); private set
    // v4.3: album-level manual edits (name/artist/cover) + single-track edits
    var albumInfoOverrides by mutableStateOf<Map<Long, com.shiyin.music.data.db.AlbumInfoOverrideEntity>>(emptyMap()); private set
    var trackInfoOverrides by mutableStateOf<Map<Long, com.shiyin.music.data.db.TrackInfoOverrideEntity>>(emptyMap()); private set
    // v4.3: 单曲迁移专辑（mediaId -> 目标 albumId），修正扫描错误的专辑归属
    var trackAlbumMoves by mutableStateOf<Map<Long, Long>>(emptyMap()); private set
    // v1.3.3b: 歌手页专辑手动排序（歌手名 -> 专辑 key 有序列表），有则覆盖日期排序。
    var artistAlbumOrders by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set
    /** v1.3.3b: 歌手页专辑列表日期异步加载结果（albumId -> ISO 日期），加载后 bump 触发重组。 */
    var albumDateRevision by mutableStateOf(0); private set
    private var albumDateCache: Map<Long, String> = emptyMap()
    fun albumDateOf(albumId: Long): String = albumDateCache[albumId] ?: ""

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
    /** v1.2.2 Agent: Tavily 联网搜索 key。 */
    var tavilyApiKey by mutableStateOf(""); private set
    /** v1.2.2 Agent: LLM 提供商配置(多供应商)+ 当前激活的 provider key。Agent 引擎据此选 LLM。 */
    var llmProviders by mutableStateOf<List<com.shiyin.music.data.ai.LlmProviderConfig>>(com.shiyin.music.data.ai.LlmConfig.PRESETS); private set
    var llmActiveProvider by mutableStateOf("deepseek"); private set
    // v1.2.1: 写真源运行时可配(设置·写真源里填/开关)。空串=未配;disabledImageSources=关掉的源 key。
    var fanartApiKey by mutableStateOf(""); private set
    var lastfmApiKey by mutableStateOf(""); private set
    var disabledImageSources by mutableStateOf<Set<String>>(emptySet()); private set
    /** v1.3.2: 自定义写真源(设置·写真源可增删,CustomSource 按模板拉图)。 */
    var customImageSources by mutableStateOf<List<com.shiyin.music.data.image.CustomImageSourceDef>>(emptyList()); private set
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
    /** v1.3.3 返回恢复:下钻(openAlbum/openArtist/openPlaylist)把非 Library 的 tab 切到
     *  Library 前,先记下来源 tab;返回清下钻层时恢复它(否则从首页进专辑返回落到音乐库)。
     *  只有一层深度(下钻层互斥,进新层前清旧层),单字段即可,无栈。 */
    private var prevTab: Tab? = null
    /** v1.3.3 返回恢复:首页滚动位置常驻 VM——HomeScreen 被 AnimatedContent 销毁
     *  重建(进专辑切 contentKey)后 rememberScrollState 归零;VM 持有则位置保留。
     *  lazy:构造期不碰 Compose 快照(KSP/构造链安全),首次访问才创建。 */
    val homeScroll: androidx.compose.foundation.ScrollState by lazy { androidx.compose.foundation.ScrollState(0) }
    /** v1.3.3 返回恢复:歌手页 UI 状态快照(展开/滚动位),key=歌手名。
     *  onDispose 即写回;完整退出歌手页清(不跨"完整退出"记住,参照主流)。 */
    private val _artistUiStates = androidx.compose.runtime.mutableStateOf<Map<String, com.shiyin.music.ui.ArtistUiState>>(emptyMap())
    val artistUiStates: Map<String, com.shiyin.music.ui.ArtistUiState> get() = _artistUiStates.value

    fun artistUiState(name: String): com.shiyin.music.ui.ArtistUiState =
        _artistUiStates.value[name] ?: com.shiyin.music.ui.ArtistUiState()
    fun saveArtistUiState(name: String, s: com.shiyin.music.ui.ArtistUiState) {
        _artistUiStates.value = _artistUiStates.value + (name to s)
    }
    /** 完整退出/主动离开歌手页时清快照,不残留污染下次进入。默认清当前 artistKey
     *  对应歌手;artistKey 已被清空的场景(BottomNav 先清 key)可显式传 [name]。 */
    fun clearArtistUiState(name: String? = null) {
        val n = name ?: artistKey ?: return
        _artistUiStates.value = _artistUiStates.value - n
    }
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
    /** v1.3.3: 待确认顺序的来源("text"=专辑文本排序框 / "agent"=Agent 写回)——
     *  决定确认窗显示在哪:文本排序的窗留在专辑页(有"返回修改"联动),Agent 的窗
     *  提到 AppRoot 全局层(Agent 页打开时专辑页不在组合中,原窗根本不出现 → 卡死)。 */
    var pendingSource by mutableStateOf<String?>(null); private set
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
                tavilyApiKey = s.tavilyApiKey
                // v1.2.2 Agent: LLM 提供商配置 + 当前激活 provider(引擎据此选 LLM)
                llmProviders = s.llmProviders
                llmActiveProvider = s.llmActiveProvider
                // v1.2.2 Agent: 把 LLM key 同步到 provider(源读取它,即时生效)
                com.shiyin.music.data.ai.DeepSeekProvider.apiKey = s.deepseekApiKey
                autoSaveRecognition = s.autoSaveRecognition
                playbackSpeed = s.playbackSpeed
                retroSpeedMode = s.retroSpeedMode
                // v1.2.1: 写真源运行时配置同步到 ImageSourceConfig(源读取它,即时生效)
                fanartApiKey = s.fanartApiKey
                lastfmApiKey = s.lastfmApiKey
                disabledImageSources = s.disabledImageSources
                customImageSources = s.customImageSources
                com.shiyin.music.data.image.ImageSourceConfig.fanartApiKey = s.fanartApiKey
                com.shiyin.music.data.image.ImageSourceConfig.lastfmApiKey = s.lastfmApiKey
                com.shiyin.music.data.image.ImageSourceConfig.disabledSources = s.disabledImageSources
                com.shiyin.music.data.image.ImageSourceConfig.customSources = s.customImageSources
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
        // v1.3.3b: 歌手页专辑手动排序 + 专辑日期缓存(一次性读全表——歌手/专辑数有限)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val orders = dao.allArtistAlbumOrders().associate { e ->
                    e.artistName to e.albumKeys.split(",").filter { it.isNotBlank() }
                }
                val dates = dao.allArtCache().associate { it.albumId to it.releaseDate }
                withContext(Dispatchers.Main) {
                    artistAlbumOrders = orders
                    albumDateCache = dates
                    albumDateRevision++
                }
            } catch (_: Exception) { }
        }
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
        player.connect(app, { id, sid -> onTrackStarted(id, sid) }, { rowId, id -> onPlayCounted(rowId, id) }, { rowId, id, sec -> onPlayFinalized(rowId, id, sec) })
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

    /** v1.3.5: 头像语义的取图(列表/统计页小圆框用)——personOnly=true:只找人物照,
     *  不拿专辑封面/banner 兜底。封面横幅塞进圆框会把人截成半张脸("头像显示不全"
     *  的根因);歌手页大写真仍走 [fetchArtistAvatar](允许专辑兜底,宽图整张展示)。 */
    fun fetchArtistAvatarPerson(name: String) {
        viewModelScope.launch {
            val canonical = resolveArtist(name)
            val img = artistImageResolver.resolve(canonical, personOnly = true)
            val url = img?.url ?: ""
            // personOnly 解析为空时不清已有 URL(artistImages 里可能已有非 person 图,
            // 有一张比空占位好);只在拿到结果时覆盖。
            if (url.isNotBlank()) {
                val extra = buildMap {
                    put(name, url)
                    if (canonical != name) put(canonical, url)
                }
                artistImages = artistImages + extra
            }
        }
    }

    fun fetchArtistAvatar(name: String) {
        viewModelScope.launch {
            // v1.3.5: 先经 alias 归一——与歌手页同 key,别名合并过的歌手不重复解析,
            // 也修"统计页/搜索页按原名解析 miss → 占位,写真其实在规范名下"。
            val canonical = resolveArtist(name)
            val img = artistImageResolver.resolve(canonical, personOnly = false)
            val url = img?.url ?: ""
            val extra = buildMap {
                put(name, url)
                if (canonical != name) put(canonical, url)
            }
            artistImages = artistImages + extra
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

    /** v1.2.0 #6: 用该歌手某张专辑的封面作写真——提取封面 bitmaps 存内部文件,file:// 覆盖。
     *  v1.2.1: 直接复用 ArtCache.load(UI 显示封面走的同一条解析链:custom cover→内嵌→
     *  iTunes 远程),保证"专辑页能显示的封面,这里就取得到"。旧实现自己重造 loadThumbnail
     *  三级回退,对"封面已显示但 loadThumbnail 失败"的专辑仍报"暂无可用封面"。 */
    fun setArtistImageFromAlbumCover(name: String, track: com.shiyin.music.data.Track) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<android.app.Application>()
                    val bmp = com.shiyin.music.ui.components.ArtCache.load(ctx, track, 600)
                        ?: return@runCatching false
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
            if (!ok) showToast("该专辑无可用封面，可改用粘贴链接", null)
        }
    }

    // v1.2.0 #6: 写真选择器——并行取所有源候选(各源 1 张,Discogs/AudioDB/Fanart 等),
    // 供用户挑换;选完调 setArtistImageOverride 写覆盖(永久,旧覆盖被替换),UI 自动刷新。
    var artistPhotoPickerFor by mutableStateOf<String?>(null)
    var artistImageCandidates by mutableStateOf<List<com.shiyin.music.data.image.ArtistImage>>(emptyList()); private set
    /** v1.2.1: picker 增量进度。pending=尚未完成的源数;total=参与源总数。UI 据此显示"搜索中… N/total",
     *  不再空等所有源(最慢 18s 超时)才出结果——源完成一个就追加候选,数量实时涨,用户知道没卡死。 */
    var artistPickerPending by mutableStateOf(0); private set
    var artistPickerTotal by mutableStateOf(0); private set

    fun openArtistPhotoPicker(name: String) {
        artistPhotoPickerFor = name
        artistImageCandidates = emptyList()
        val sources = com.shiyin.music.data.image.ArtistImageSources.enabledSources()
        artistPickerTotal = sources.size
        artistPickerPending = sources.size
        viewModelScope.launch {
            // v1.2.1: 增量——每源完成立刻追加新候选(去重),pending 递减。不 awaitAll 等所有。
            val mu = kotlinx.coroutines.sync.Mutex()
            val seenUrls = mutableSetOf<String>()
            val jobs = sources.map { src ->
                launch {
                    var r = runCatching { src.fetchAll(name, personOnly = true) }.getOrNull().orEmpty()
                    // v1.3.3b review#B9: picker 候选预检——无尺寸元数据的图(百度 middleURL、
                    // 百科抓取图等)探测一次真实尺寸:死链/无法解码的在此滤掉,不让用户选完
                    // 写进 override 后才发现头像空白(override 最高优先级且永不自动回退)。
                    // 探测带出真实尺寸还能让 UI 裁剪按比例走。
                    r = r.mapNotNull { img ->
                        if (img.width > 0 && img.height > 0) img
                        else artistImageResolver.probeDimensions(img.url)?.let { (w, h) -> img.copy(width = w, height = h) }
                    }
                    android.util.Log.d("AIM", "picker src ${src.key}: ${r.size} imgs" + (r.firstOrNull()?.url?.let { " first=${it.take(70)}" } ?: ""))
                    mu.withLock {
                        val fresh = r.filter { seenUrls.add(it.url) }  // add 返回 false=已存在→过滤掉
                        if (fresh.isNotEmpty()) artistImageCandidates = (artistImageCandidates + fresh)
                    }
                    artistPickerPending = (artistPickerPending - 1).coerceAtLeast(0)
                }
            }
            jobs.forEach { it.join() }
            artistPickerPending = 0
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
        } else if (key != null && key.startsWith("artist:")) {
            // v1.3.2: 歌手队列保持歌手范围,与 album: 同样不被同步成全库——
            // 旧逻辑 artist: 队列落进 else 分支,库一变队列就被替换成 sortedSongs()(全库)。
            val ts = artistQueue(key.removePrefix("artist:"))
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

    // ── v1.3.3b: 歌手页专辑排序(手动 + AI 按发布时间) ──────────────────────
    /** 保存某歌手的专辑顺序(手动拖拽/AI 排完后调用)。乐观更新内存 + 异步落库。
     *  v1.3.3b review#2: state 写入切主线程(v5.2 #75 同因——后台线程写 snapshot
     *  不保证立刻触发重组;fetchArtistAlbumDates 在 IO 协程里调这里)。 */
    fun saveArtistAlbumOrder(artistName: String, keys: List<String>) {
        viewModelScope.launch(Dispatchers.Main) {
            artistAlbumOrders = artistAlbumOrders + (artistName to keys)
        }
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertArtistAlbumOrder(
                com.shiyin.music.data.db.ArtistAlbumOrderEntity(
                    artistName, keys.joinToString(","), System.currentTimeMillis(),
                )
            )
        }
    }

    /** 清除某歌手的手动专辑顺序(恢复按日期/名称自动排序)。 */
    fun clearArtistAlbumOrder(artistName: String) {
        viewModelScope.launch(Dispatchers.Main) { artistAlbumOrders = artistAlbumOrders - artistName }
        viewModelScope.launch(Dispatchers.IO) { dao.deleteArtistAlbumOrder(artistName) }
    }

    /** 写入一张专辑的发布日期(iTunes 已有/AI 补查都用此入口),刷新内存缓存。
     *  review#2: state 写入切主线程,DB 写留 IO。 */
    fun saveAlbumReleaseDate(albumId: Long, iso: String) {
        // v1.3.5: iso 允许空串=清除日期(编辑弹窗把年/月/日滑到 0 保存 → 清除);
        // albumId<=0 仍然忽略。空串也走缓存+落库,专辑页/歌手栏随即不再显示日期。
        if (albumId <= 0) return
        viewModelScope.launch(Dispatchers.Main) {
            albumDateCache = if (iso.isBlank()) albumDateCache - albumId else albumDateCache + (albumId to iso)
            albumDateRevision++
        }
        viewModelScope.launch(Dispatchers.IO) {
            try { dao.updateAlbumReleaseDate(albumId, iso) } catch (_: Exception) { }
        }
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
        // v1.3.2: 归一化匹配——AI/网络版歌名与本地常有一丁点形式差异:括号注记
        // ((feat. XX)/(Live))、空格差异(「何なん w」vs「何なんw」)等。比对时剥掉
        // 括号注记与全部空白,这类行不再落进"未识别"。
        fun normTitle(s: String): String =
            s.replace(Regex("[(（\\[【].*?[)）\\]】]]"), "").replace(Regex("""\s+"""), "")
        val normById = tracks.associate { it.id to normTitle(it.title).lowercase() }
        for (line in lines) {
            val title = line.replace(Regex("""^\d+[.、)\s]*"""), "").trim()
            if (title.isEmpty()) continue
            val nTitle = normTitle(title).lowercase()
            // Prefer exact match first; fall back to contains so shorthand works.
            val match =
                tracks.firstOrNull { it.id !in used && it.title.equals(title, ignoreCase = true) }
                    ?: tracks.firstOrNull { it.id !in used && it.title.contains(title, ignoreCase = true) }
                    ?: tracks.firstOrNull { it.id !in used && nTitle.isNotEmpty() && normById[it.id] == nTitle }
                    ?: tracks.firstOrNull { it.id !in used && nTitle.isNotEmpty() && normById[it.id]?.contains(nTitle) == true }
                    ?: tracks.firstOrNull { it.id !in used && title.contains(it.title, ignoreCase = true) }
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
            pendingSource = "text"
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
        pendingSource = null
        commitOrder(key, po.first)
    }

    /** v4.5: 用户点“取消返回修改”——丢弃预览，不动库，文本框保持可编辑。 */
    fun cancelPendingOrder() {
        pendingOrder = null
        pendingKey = null
        pendingSource = null
    }

    /**
     * v1.3.3: Agent 按本地索引写回专辑排序——AI 直接输出"本地编号序列"(对哪首排第几
     * 做判断),不再输出官方歌名文本让本地猜匹配。名字与官方有一丁点差异(feat. 括号/
     * 空格/繁简)由 AI 在分析时理清对应关系,这就是"AI 智能决策"而非"本地容错兜底"。
     *
     * 编号覆盖全部曲目 → 直接落库;有缺(AI 没确定全部)→ 走 pendingOrder 确认窗,
     * 未确定的曲目按原相对顺序补尾,绝不硬编。
     */
    fun applyAlbumOrderIndices(key: String, indices: List<Int>) {
        val tracks = albumOrder(key)
        if (tracks.isEmpty()) return
        val seen = HashSet<Int>()
        val valid = indices.filter { it in tracks.indices && seen.add(it) }
        if (valid.isEmpty()) return
        val full = valid.map { tracks[it].id } +
            tracks.filterIndexed { i, _ -> i !in seen }.map { it.id }
        if (valid.size < tracks.size) {
            pendingOrder = full to tracks.filterIndexed { i, _ -> i !in seen }.map { it.title }
            pendingKey = key
            pendingSource = "agent"
        } else {
            commitOrder(key, full)
        }
    }

    /** 乐观更新内存 orders + 异步写库。applyAlbumOrderText / commitPendingOrder 共用。 */
    private fun commitOrder(key: String, final: List<Long>) {        orders = orders + (key to final)  // 7-D: 即时刷新 UI
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertOrder(AlbumOrderEntity(key, final.joinToString(",")))
        }
    }

    // ── v1.2.2 Agent:对话式引擎入口(独立对话页) ────────────────────────────
    // 用户在 Agent 页发自然语言指令 → AgentEngine.understand 选技能 → 执行 → 步骤面板
    // 实时更新 → 写回前走 pendingOrder 闸门。写入层只碰 track_info_override/album_order/
    // track_album_move,绝不碰 artist_aliases 全局表。
    var agentOpen by mutableStateOf(false)
    var agentSettingsOpen by mutableStateOf(false)
    /** 对话消息流(用户消息 + Agent 消息,含步骤面板 + tokens)。 */
    var agentMessages by mutableStateOf<List<AgentMessage>>(emptyList()); private set
    var agentRunning by mutableStateOf(false); private set
    /** 本次最近一次 LLM 调用的 tokens(供设置页/对话显示)。 */
    var agentLastTokens by mutableStateOf<Pair<Int, Int>>(0 to 0); private set
    /** 累计 prompt/completion tokens(从 token_usage_log 表读)。 */
    var agentTotalTokens by mutableStateOf<Pair<Long, Long>>(0L to 0L); private set
    /** v1.3.2: 默认回复建议(专辑页 Agent 入口带入,Agent 页点一条即发送)。 */
    var agentSuggestions by mutableStateOf<List<String>>(emptyList()); private set
    /** v1.3.3: 输入框草稿——离开 Agent 页暂存,回来恢复;执行中也能继续码字。 */
    var agentDraft by mutableStateOf("")
    /** v1.3.3: 当前 Agent 执行协程——供打断(对标 Claude/ChatGPT 的停止按钮)。 */
    private var agentJob: kotlinx.coroutines.Job? = null

    /** v1.3.3: 用户主动打断当前 Agent 回复/执行(停止按钮)。 */
    fun stopAgent() {
        agentJob?.let { job ->
            if (job.isActive) {
                job.cancel()
                // 在当前消息尾部补一条打断提示(如果有进行中的消息);同时把步骤面板
                // 里还在 RUNNING 的行标成终态——停止后波浪动画必须立刻停,不能继续转。
                agentMessages.lastOrNull()?.let { last ->
                    if (last.role == "agent") {
                        val idx = agentMessages.size - 1
                        val stoppedSteps = last.steps?.map {
                            if (it.status == com.shiyin.music.data.ai.AgentEngine.StepStatus.RUNNING)
                                it.copy(status = com.shiyin.music.data.ai.AgentEngine.StepStatus.FAILED, error = "已手动停止")
                            else it
                        }
                        val stoppedText = if (last.text.isBlank()) "已停止。" else "${last.text}\n\n（已手动停止）"
                        agentMessages = agentMessages.replaceAt(idx, last.copy(text = stoppedText, steps = stoppedSteps ?: last.steps))
                    } else {
                        agentMessages = agentMessages + AgentMessage("agent", "已停止。")
                    }
                }
            }
        }
        agentJob = null
        agentRunning = false
    }

    /** 打开 Agent 设置面板(右上齿轮)。 */
    fun openAgentSettings() { agentSettingsOpen = true }

    /** 刷新累计 tokens(从 DB 读)。Agent 页/设置页 onAppear 调。
     *  v1.3.3b review#A1: state 写入切主线程(v5.2 #75 同因)。 */
    fun refreshAgentTokens() {
        viewModelScope.launch(Dispatchers.IO) {
            val t = com.shiyin.music.data.ai.TokenUsageStore.totals(getApplication())
            kotlinx.coroutines.withContext(Dispatchers.Main) { agentTotalTokens = t }
        }
    }

    /** Agent 对话消息。step=步骤面板(仅 Agent 消息);tokens=本次调用消耗。 */
    data class AgentMessage(
        val role: String,  // "user" / "agent"
        val text: String,
        val steps: List<com.shiyin.music.data.ai.AgentEngine.Step>? = null,
        val tokens: Pair<Int, Int>? = null,
        /** v1.3.3: 模型思考链(默认折叠,可展开)——让用户看到模型在想什么,不是空等。 */
        val thinking: String? = null,
        /** v1.3.3: 思考耗时 ms(UI 折叠态显示"已思考 X 秒",真实计时不用长度估)。 */
        val thinkingMs: Long = 0L,
    )

    /** 当前上下文 + 写回桥,供 AgentEngine 技能调用。实现 SkillContext 接口。 */
    private val agentCtx = object : com.shiyin.music.data.ai.AgentEngine.SkillContext {
        override val albumKey: String? get() = this@MainViewModel.albumKey
        override val artistKey: String? get() = this@MainViewModel.artistKey
        override fun currentTrack(): Track? = player.currentId?.let { trackById(it) }
        override fun albumTracks(): List<Track> = albumKey?.let { albumOrder(it) } ?: emptyList()
        override fun albumTracksOf(key: String): List<Track> = albumOrder(key)

        /** 按名找专辑:先精确(规范化后全等),再包含式;忽略大小写/空格/标点。 */
        override fun findAlbum(query: String): String? {
            val q = query.lowercase().filter { it.isLetterOrDigit() }
            if (q.isBlank()) return null
            val entries = albumsMap()
            entries.forEach { (k, ts) ->
                if (ts.firstOrNull()?.album?.lowercase()?.filter { it.isLetterOrDigit() } == q) return k
            }
            entries.forEach { (k, ts) ->
                val n = ts.firstOrNull()?.album?.lowercase()?.filter { it.isLetterOrDigit() } ?: return@forEach
                if (n.contains(q) || q.contains(n)) return k
            }
            return null
        }

        /** v1.3.6: 全库搜歌——标题/歌手/专辑任一模糊匹配(规范化后 contains),
         *  按播放次数排序返回前 [limit] 条。"库里有没有XX"类问题不再靠模型瞎编。 */
        override fun searchTracks(query: String, limit: Int): List<Track> {
            val q = query.lowercase().filter { it.isLetterOrDigit() }
            if (q.isBlank()) return emptyList()
            val seen = HashSet<Long>()
            return lib()
                .filter { t ->
                    seen.add(t.id) &&
                        (t.title.lowercase().filter { it.isLetterOrDigit() }.contains(q) ||
                            t.artist.lowercase().filter { it.isLetterOrDigit() }.contains(q) ||
                            t.album.lowercase().filter { it.isLetterOrDigit() }.contains(q))
                }
                .sortedByDescending { playCountFor(it.id) }
                .take(limit)
        }

        /** v1.3.6: 搜歌手名——库内歌手名模糊匹配,按该歌手总播放次数排序。 */
        override fun searchArtists(query: String, limit: Int): List<String> {
            val q = query.lowercase().filter { it.isLetterOrDigit() }
            if (q.isBlank()) return emptyList()
            val counts = HashMap<String, Int>()
            for (t in lib()) {
                for (a in com.shiyin.music.data.MediaScanner.splitArtists(t.artist)) {
                    counts.merge(a, playCountFor(t.id), Int::plus)
                }
            }
            return counts.entries
                .filter { it.key.lowercase().filter { ch -> ch.isLetterOrDigit() }.contains(q) }
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
        }

        override fun applyAlbumOrderText(key: String, text: String) = this@MainViewModel.applyAlbumOrderText(key, text)
        override fun applyAlbumOrderIndices(key: String, indices: List<Int>) = this@MainViewModel.applyAlbumOrderIndices(key, indices)
        override fun saveTrackInfo(mediaId: Long, title: String, artist: String) =
            this@MainViewModel.saveTrackInfo(mediaId, title, artist, "")
        override fun hasPendingOrder(): Boolean = pendingOrder != null
        override fun logUsage(promptTokens: Int, completionTokens: Int, cacheTokens: Int) {
            viewModelScope.launch(Dispatchers.IO) {
                com.shiyin.music.data.ai.TokenUsageStore.log(
                    getApplication(), llmActiveProvider, "agent", promptTokens, completionTokens, cacheTokens,
                )
            }
        }
    }

    /** 用户在 Agent 页发指令。 */
    fun sendAgentMessage(text: String) {
        if (agentRunning || text.isBlank()) return
        // v1.3.3b review#B6: 闸门过即置位——原 agentRunning=true 在消息 append 之后,
        // 双击/IME onSend 与按钮点击并发时两次都能过闸 → 同一句话发两条、烧双份 token。
        agentRunning = true
        // v1.3.2: 用户开始对话后,初始建议立即消失(不常驻);本轮结束后按结果给后续建议
        agentSuggestions = emptyList()
        val config = activeLlmConfig()
        android.util.Log.d(
            "Agent",
            "sendAgentMessage: provider=$llmActiveProvider model=${config?.model} baseUrl=${config?.baseUrl} " +
                "keyLen=${config?.apiKey?.length ?: 0} tavilySet=${tavilyApiKey.isNotBlank()} ctx(album=$albumKey artist=$artistKey)",
        )
        agentMessages = agentMessages + AgentMessage("user", text.trim())
        if (config == null) {
            agentMessages = agentMessages + AgentMessage("agent", "尚未配置 LLM(右上设置里填 API Key + 选模型)。")
            // review#B6: 未配置也是一次完整往返(同步回复),闸门放开
            agentRunning = false
            return
        }
        if (tavilyApiKey.isBlank()) {
            agentMessages = agentMessages + AgentMessage("agent", "尚未配置 Tavily API Key(联网搜证不可用,在右上设置里填)。")
        }
        // v1.3.3: 持有 job 供 stopAgent() 打断
        agentJob = viewModelScope.launch {
            // 步骤面板状态(实时更新):先放意图理解的步骤,执行中按 skill 补 steps
            val steps = mutableListOf<com.shiyin.music.data.ai.AgentEngine.Step>()
            val msgIdx = agentMessages.size
            // 占位一条 Agent 消息(后面替换)
            agentMessages = agentMessages + AgentMessage("agent", "", steps.toList())
            try {
                // 1. 单次调用路由(v1.3.3: 技能判定 + 聊天回答合并为一次 LLM 往返,
                //    闲聊不再"意图理解→聊天兜底"跑两次模型——那让"你好"也要等半分钟)
                val history = agentMessages
                    .filter { it.text.isNotBlank() && it.steps == null }
                    .dropLast(1)  // 最后一条是刚 append 的当前指令,不算历史
                    .takeLast(4)
                    .map { it.role to it.text }
                // 思考链实时流出(reasoning 增量) + 聊天回复实时流出(打字机数据源)
                var intentThinking: String? = null
                var streamedReply: String? = null
                // v1.3.3b review#B2: SSE 回调在 IO 线程触发(LlmClient.callStream 在
                // Dispatchers.IO)——按项目 v5.2 #75 约定,state 写入投递主线程执行,
                // 思考胶囊/打字机不依赖"后台写 snapshot 恰好触发重组"。
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                // v1.3.5: 事实性问题**先搜后答**——本地判定(问句+事实疑问词)在发车前
                // 就查一遍,是事实问题 → 先 Tavily 搜证、带着资料路由,模型一次输出
                // 核实后的答案。不再"先流出猜测、发现是 FACT 再吞回去重搜"(用户看到
                // 答案闪现又消失,且猜测的瞎编答案不该露脸)。判定完全本地化,模型
                // 不再打任何标记(不输出 FACT 字样,聊天文本零协议记号)。
                val isFactQ = com.shiyin.music.data.ai.AgentEngine.looksLikeFactQuestion(text)
                var webContext = ""
                if (isFactQ && tavilyApiKey.isNotBlank()) {
                    mainHandler.post {
                        steps.upsert("web", "联网核实", com.shiyin.music.data.ai.AgentEngine.StepStatus.RUNNING)
                        agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "", steps.toList()))
                    }
                    webContext = runCatching {
                        com.shiyin.music.data.ai.TavilyService.search(tavilyApiKey, text.take(120))
                    }.getOrNull() ?: ""
                    mainHandler.post {
                        steps.upsert("web", "联网核实", if (webContext.isBlank()) com.shiyin.music.data.ai.AgentEngine.StepStatus.FAILED else com.shiyin.music.data.ai.AgentEngine.StepStatus.DONE)
                    }
                }
                var route = routeOnce(text, history, config, steps, msgIdx, mainHandler, webContext) { th, rp ->
                    intentThinking = th; streamedReply = rp
                }
                if (route == null) {
                    // 调用失败(429/网络/配置)——透传真实原因
                    val err = com.shiyin.music.data.ai.LlmClient.lastError ?: "未知错误"
                    agentMessages = agentMessages.replaceAt(
                        msgIdx,
                        AgentMessage("agent", "LLM 调用失败:$err\n请到右上「设置」检查供应商额度 / Key / 模型名。", steps.toList()),
                    )
                    return@launch
                }
                // v1.3.3b review#B7: 路由调用真实 usage(fast-path 无 LLM 调用=0)
                val routeTokens = route.promptTokens to route.completionTokens
                if (route.chatReply != null) {
                    // 2a. 闲聊:回复已在 onContent 流式写入消息(打字机数据源已在)。
                    // 这里只确保最终态一致并带真实思考耗时 + 本次 tokens。
                    agentMessages = agentMessages.replaceAt(
                        msgIdx,
                        AgentMessage("agent", streamedReply ?: route.chatReply, null, tokens = routeTokens, thinking = route.thinking, thinkingMs = route.thinkingMs),
                    )
                    refreshAgentTokens()
                    return@launch
                }
                // 2b. 技能路由。skill 与 chatReply 双空 = 模型无有效输出——按失败透传,不崩。
                val plan = route.skill ?: run {
                    val err = com.shiyin.music.data.ai.LlmClient.lastError ?: "模型无返回"
                    agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "LLM 无有效回复:$err", steps.toList()))
                    return@launch
                }
                android.util.Log.i("Agent", "plan: skill=${plan.skillKey} args=${plan.args} steps=${plan.steps}")
                // v1.3.3 动画统一:步骤面板 = 思考后的产物,由路由(plan.steps)生成、显示在
                // 思考胶囊下方(先思考→出计划→逐步执行)。步骤行文案优先用模型生成清单,
                // fastPath/模型没给 steps 时用技能预置清单。
                steps.clear()
                val panelSteps = plan.steps.ifEmpty { panelStepsFor(plan.skillKey) }
                for ((i, s) in panelSteps.withIndex()) {
                    steps.add(com.shiyin.music.data.ai.AgentEngine.Step("step_$i", s, com.shiyin.music.data.ai.AgentEngine.StepStatus.PENDING))
                }
                agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "", steps.toList(), thinking = intentThinking))
                // 2. 技能分发执行
                val skill = com.shiyin.music.data.ai.AgentEngine.skillByKey(plan.skillKey)
                if (skill == null) {
                    agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "没有可执行技能「${plan.skillKey}」。"))
                    return@launch
                }
                // v1.3.3b review#B7: lastTokens 原来恒 null(声明后从未赋值),"本次 N tokens"
                // 从不显示——改记路由调用的真实 usage,技能内调用靠 refreshAgentTokens 累计。
                val lastTokens: Pair<Int, Int>? = routeTokens
                val result = skill.execute(
                    agentCtx, plan.args, config, tavilyApiKey,
                ) { id, st ->
                    // 技能回调的语义步骤 id → 面板行:行文案用 plan 生成清单(upsert 就地
                    // 更新时保留行上文案);语义索引超出模型给的步数时(模型只给 2 步但
                    // 技能有 4 个回调)落到最后一行,不追加裸 id 行。
                    val rowId = when (plan.skillKey) {
                        "album_sort" -> when (id) {
                            "tracks" -> "step_0"
                            "search" -> "step_1"
                            "analyze" -> "step_2"
                            "writeback" -> "step_3"
                            else -> id
                        }
                        // v1.3.6e: track_rename 三条路的步骤清单不同,按 args 分派行号:
                        // rule=批量规则(分析/写回 2 行)|new_title=点名直改(定位/改名 2 行)
                        // |其余=联网比对官方表(搜证/分析/写回 3 行)。
                        "track_rename" -> when {
                            plan.args.containsKey("rule") -> when (id) {
                                "analyze" -> "step_0"
                                "writeback" -> "step_1"
                                else -> id
                            }
                            plan.args.containsKey("new_title") -> when (id) {
                                "locate" -> "step_0"
                                "rename" -> "step_1"
                                else -> id
                            }
                            else -> when (id) {
                                "search" -> "step_0"
                                "analyze" -> "step_1"
                                "writeback" -> "step_2"
                                else -> id
                            }
                        }
                        else -> when (id) {
                            "search" -> "step_0"
                            "analyze" -> "step_1"
                            "writeback" -> "step_2"
                            else -> id
                        }
                    }
                    val effectiveRow = if (steps.none { it.id == rowId }) "step_${(steps.size - 1).coerceAtLeast(0)}" else rowId
                    // v1.3.3: 联网搜证没搜到资料 → 行内给"未搜到相关资料"而不是空 ✗
                    // (upsert 自动取的 lastError 已被业务路径清空)。
                    val bizErr = if (st == com.shiyin.music.data.ai.AgentEngine.StepStatus.FAILED && id == "search")
                        "未搜到相关资料" else null
                    // v1.3.3b review#B2: 技能协程在 IO 线程回调——state 写入投递主线程。
                    mainHandler.post {
                        steps.upsert(effectiveRow, id, st, explicitError = bizErr)
                        agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "", steps.toList(), lastTokens))
                    }
                }
                // 记 tokens(意图理解 + 技能内 LLM 调用累计由 LlmClient 不自动汇总,
                // 这里用一个近似:从 TokenUsageStore 读增量。简化:记录本次为 0 占位,
                // 真实累计靠 refreshAgentTokens 从 DB 读)。
                // v1.3.5: 面板清扫——技能 return 后仍可能有行停在 RUNNING(mainHandler
                // 排队的 post 没到/技能路径漏回调,用户看到的"联网核实一直在滚、
                // 写回永远 ○")。收尾时强制终态:还 RUNNING 的标 DONE(技能都 return 了,
                // 说明流程走完)、没跑过的 PENDING 行从面板移除(不误导"还有事没做")。
                // IO 协程里同步改 steps + 主线程写消息,一次落定。
                for (i in steps.indices) {
                    if (steps[i].status == com.shiyin.music.data.ai.AgentEngine.StepStatus.RUNNING) {
                        steps[i] = steps[i].copy(status = com.shiyin.music.data.ai.AgentEngine.StepStatus.DONE)
                    }
                }
                steps.removeAll { it.status == com.shiyin.music.data.ai.AgentEngine.StepStatus.PENDING }
                // v1.3.3: 最终消息带上思考链(意图理解 + 技能内 LLM 调用,取最全的一条)+ 真实耗时。
                val finalThinking = result.thinking ?: intentThinking
                agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", result.summary, steps.toList(), lastTokens, thinking = finalThinking, thinkingMs = route.thinkingMs))
                // v1.3.2: 按本轮任务结果更新建议(点过/发过即消失,不常驻)
                // v1.3.3: 删掉「查歌手名识别是否正确」建议——意义不明,任务完成后清空。
                agentSuggestions = emptyList()
                if (result.needsConfirm) {
                    agentMessages = agentMessages + AgentMessage("agent", "部分曲目未匹配,请在弹出的确认窗里选择「应用已识别部分」或「取消」。")
                }
                refreshAgentTokens()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    // v1.3.3: 用户打断——消息收尾(提示语+步骤标 FAILED)已由 stopAgent()
                    // 在主线程完成,这里不再重复写消息(两处都写会竞态覆盖),只向上抛。
                    throw e
                }
                agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "Agent 执行出错:${e.message ?: e.javaClass.simpleName}"))
            } finally {
                agentRunning = false
            }
        }
    }

    /** 面板预置步骤(按技能固定文案,与技能回调的语义 id 顺序一一对应)。 */
    private fun panelStepsFor(skillKey: String): List<String> = when (skillKey) {
        "album_sort" -> listOf("读取专辑曲目", "联网搜索官方顺序", "AI 核对生成顺序", "写回排序")
        "artist_fix" -> listOf("联网搜证", "AI 分析修正", "写回本地")
        "track_rename" -> listOf("联网搜证", "AI 分析修正", "写回本地")
        "lyrics_fetch" -> listOf("搜索歌词源")
        "library_query" -> listOf("检索音乐库", "组织回答")
        else -> emptyList()
    }

    /**
     * v1.3.4: 单次 route 调用(把 SSE 三回调的样板抽成一处——联网核实重发要再走
     * 一遍同样的流)。思考链/打字机经 [onState] 回写调用方的局部变量(最终消息用),
     * UI 更新(消息列表替换)仍在本函数内投递主线程。
     */
    private suspend fun routeOnce(
        text: String,
        history: List<Pair<String, String>>,
        config: com.shiyin.music.data.ai.LlmProviderConfig,
        steps: MutableList<com.shiyin.music.data.ai.AgentEngine.Step>,
        msgIdx: Int,
        mainHandler: android.os.Handler,
        webContext: String = "",
        onState: (thinking: String?, streamedReply: String?) -> Unit = { _, _ -> },
    ): com.shiyin.music.data.ai.AgentEngine.Route? {
        // v1.3.4: onThinking 传的是增量碎片——live 展开要看完整链,本地累积;
        // null=重试轮开始,清空重攒(重试成功会从头重发全量)。
        var thinkAcc: String? = null
        return com.shiyin.music.data.ai.AgentEngine.route(
            text, agentCtx, config,
            onStep = { _, st ->
                // 路由阶段不显示"分析意图"步骤行(与思考胶囊两套表达,同时出现动画
                // 不统一);失败才落面板行透传原因。
                if (st == com.shiyin.music.data.ai.AgentEngine.StepStatus.FAILED) {
                    mainHandler.post {
                        steps.upsert("intent", "思考中", st)
                        agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "", steps.toList()))
                    }
                }
            },
            history = history,
            webContext = webContext,
            onThinking = { th ->
                thinkAcc = if (th == null) "" else (thinkAcc ?: "") + th
                mainHandler.post {
                    onState(thinkAcc, null)
                    agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "", steps.toList(), thinking = thinkAcc))
                }
            },
            onContent = { partial ->
                // sensenova 等模型的回复常以 \n\n 开头——不 trim 气泡顶会渲染一整行空白。
                mainHandler.post {
                    val clean = partial.trimStart()
                    onState(null, clean)
                    agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", clean, steps.toList()))
                }
            },
        )
    }

    /** 步骤面板用:按 id 就地更新步骤状态(行已存在则保留预置文案),无则追加。
     *  v1.3.3: FAILED 时行内显示原因——[explicitError] 优先(业务失败如"未搜到资料"),
     *  否则自动取 LlmClient.lastError(LLM/网络失败,如 HTTP 429)。 */
    private fun MutableList<com.shiyin.music.data.ai.AgentEngine.Step>.upsert(
        id: String, label: String, status: com.shiyin.music.data.ai.AgentEngine.StepStatus,
        explicitError: String? = null,
    ) {
        val i = indexOfFirst { it.id == id }
        val err = if (status == com.shiyin.music.data.ai.AgentEngine.StepStatus.FAILED)
            explicitError ?: com.shiyin.music.data.ai.LlmClient.lastError
        else null
        if (i >= 0) set(i, com.shiyin.music.data.ai.AgentEngine.Step(id, this[i].label, status, err))
        else add(com.shiyin.music.data.ai.AgentEngine.Step(id, label, status, err))
    }

    /** 对话消息:替换指定位置的那条(步骤面板逐帧更新用)。 */
    private fun List<AgentMessage>.replaceAt(idx: Int, m: AgentMessage): List<AgentMessage> =
        mapIndexed { i, old -> if (i == idx) m else old }

    /** 打开/开关 Agent 页(侧边栏/专辑菜单)。agentOpen 公开可写,UI 直接赋值。 */

    // v1.3.2: 专辑菜单的 Agent 入口——不自动执行排序,而是打开对话页打招呼 +
    // 给出针对该专辑的建议回复,用户点建议或自行输入(Agent 不局限于排序一种处理)。
    fun clearAgentSuggestions() { agentSuggestions = emptyList() }

    fun openAgentForAlbum(key: String) {
        agentOpen = true
        val name = albumOrder(key).firstOrNull()?.album ?: key
        if (agentMessages.isEmpty()) {
            agentMessages = agentMessages + AgentMessage("agent", "你想让我对《$name》做什么呢?")
        }
        agentSuggestions = listOf(
            "把《$name》排成正确顺序",
            "介绍一下《$name》这张专辑",
        )
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
    private fun onTrackStarted(id: Long, sessionId: Long) {
        lyState = LyState.Idle
        // v2: 恢复全局播放速度设置
        player.setPlaybackSpeed(playbackSpeed, retroSpeedMode)
        appScope.launch { settingsStore.pushRecent(id) }
        // v1.2.1: 计数口径"累计满 30s"。插一条 completed=0 的 play_event,拿回行 id 回填到
        // session,使后续 markCounted/finalize 按主键 id 精确定位(不靠"mediaId 最新行"子查询,
        // 避免同 mediaId 切歌/单曲循环并发 insertPlayEvent 时写错行)。用 appScope:Activity 销毁后
        // player 仍在后台播,DB 写不能随 viewModelScope 死。
        appScope.launch(Dispatchers.IO) {
            try {
                val t = trackById(id) ?: return@launch
                val rowId = dao.insertPlayEvent(
                    com.shiyin.music.data.db.PlayEventEntity(
                        mediaId = id,
                        playedAt = System.currentTimeMillis(),
                        durationSec = (t.durationMs / 1000).toInt(),
                        completed = false,
                    )
                )
                player.setSessionRowId(sessionId, rowId)
            } catch (_: Exception) { }
        }
        // v2.0: push album art to the system MediaSession (notification pill,
        // lock screen, quick-settings media control). Uses setArtworkData
        // (bitmap bytes) because the system MediaController can't read
        // content:// URIs.
        // v1.2.2: 改走 ArtCache.loadForMediaSession(与 UI 同源本地优先链,内存/磁盘命中不联网),
        // 修复系统控制台/灵动岛不显示本地已保存封面。协程用 appScope——activity 销毁后
        // 后台播放时 viewModelScope 已 cancel 推不上封面。
        appScope.launch(Dispatchers.IO) {
            val t = trackById(id) ?: return@launch
            val app = getApplication<android.app.Application>()
            val bitmap = com.shiyin.music.ui.components.ArtCache.loadForMediaSession(app, t, 600)
            if (bitmap == null) {
                android.util.Log.d("MediaArt", "no cover for mediaSession #$id")
                return@launch
            }
            try {
                val bos = java.io.ByteArrayOutputStream()
                // v1.2.2: 压缩用 JPEG(ColorOS 通知/灵动岛对 WEBP 兼容差)。
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bos)
                val bytes = bos.toByteArray()
                // v1.2.2: ColorOS 灵动岛用 UriArtworkLoader 从 artworkUri(content://)读封面,
                // 不读 artworkData。把封面写进 FileProvider 暴露的私有文件(filesDir/cover_cache,
                // 系统相册不可见、零污染),生成 content:// URI 一并设置。
                var artworkUri: android.net.Uri? = null
                try {
                    val dir = java.io.File(app.filesDir, "cover_cache").apply { mkdirs() }
                    val file = java.io.File(dir, "current_cover.jpg")
                    file.writeBytes(bytes)
                    artworkUri = androidx.core.content.FileProvider.getUriForFile(
                        app,
                        com.shiyin.music.BuildConfig.APPLICATION_ID + ".coverprovider",
                        file,
                    )
                } catch (_: Exception) { /* FileProvider 失败则仅用 artworkData 兜底 */ }
                player.updateArtworkData(bytes, forMediaId = id, artworkUri = artworkUri)
            } catch (_: Exception) { }
        }
    }

    /** v1.2.1: 累计有效播放满 30 秒时由 PlayerController 触发——把该次 play_event
     *  计为一次有效播放(completed=1)。这是热度排序/收听统计的唯一计数口径,
     *  误触/跳过(<30s)永远到不了这里,不会污染数据。 */
    private fun onPlayCounted(rowId: Long, id: Long) {
        appScope.launch(Dispatchers.IO) {
            try { dao.markPlayCounted(rowId) } catch (_: Exception) { }
        }
    }

    /** v1.2.1: 切歌/播完时由 PlayerController 触发,回传该次播放的累计有效秒数——
     *  写入 play_event.playedSec(按主键 [rowId] 定位),供 收听统计 总时长准确求和。
     *  用 appScope:onCleared 时 viewModelScope 已 cancel,改 appScope 才能让退出 flush 真正落库。
     *  顺带 trim 掉 90 天前的事件(最近播放只看近 3 个月)。 */
    private fun onPlayFinalized(rowId: Long, id: Long, playedSec: Int) {
        appScope.launch(Dispatchers.IO) {
            try {
                dao.finalizePlayEvent(rowId, playedSec)
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

    /**
     * v1.3.2: 歌手页的完整歌曲全集——播放队列的数据源。
     * = 直接匹配(track 歌手字段 split 含该歌手)+ 歌手页专辑栏归属专辑的全部曲目,按 id 去重。
     *
     * 修复:之前只取 artistsMap()[name](按 track 歌手分组),与歌手页专辑栏(按「专辑位歌手」
     * 归属)不一致——改过归属/批量迁移过的专辑整张缺席,播放列表/随机播放缺歌。
     * 专辑内用 albumOrder(专辑页当前排序),专辑间按库顺序追加在直接匹配曲目之后。
     */
    fun artistQueue(name: String): List<Track> {
        val seen = HashSet<Long>()
        val q = ArrayList<Track>()
        artistsMap()[name]?.forEach { t -> if (seen.add(t.id)) q.add(t) }
        for ((k, ts) in albumsMap()) {
            val attributed = name in com.shiyin.music.data.MediaScanner.splitArtists(ts.first().artist) &&
                com.shiyin.music.ui.screens.classifyAlbum(ts) != com.shiyin.music.ui.screens.AlbumCategory.Compilation
            if (!attributed) continue
            for (t in albumOrder(k)) if (seen.add(t.id)) q.add(t)
        }
        return q
    }

    /** v1.3.1: 歌手页播放——队列只放该歌手的曲目,不是全库。
     *  v1.3.2: 队列源改为 artistQueue(全集),覆盖归属专辑里 track 歌手字段不一致的曲。 */
    fun playArtist(name: String, startId: Long? = null) {
        val artistTracks = artistQueue(name)
        val tappedHidden = startId != null && isHidden(startId)
        val queue = if (tappedHidden) artistTracks.filter { it.id == startId || !isHidden(it.id) } else playbackFiltered(artistTracks)
        if (queue.isEmpty()) return
        val realStart = startId
            ?.takeIf { id -> queue.any { it.id == id } }
            ?: queue.first().id
        player.setShuffle(false)
        player.playQueue(queue, realStart, "artist:$name")
    }

    fun playRandom() {
        val l = playbackFilteredSortedSongs()
        if (l.isEmpty()) return
        player.setShuffle(true)
        player.playQueue(l, l.random().id, null)
    }

    /** Shuffle-play a specific set of IDs (e.g. artist tracks).
     *  v1.3.2: [queueKey] 可选——传入时队列带 key(如 "artist:XX"),resyncQueue 保持范围,
     *  否则库一变随机队列会被同步成全库。 */
    fun playRandom(ids: List<Long>, queueKey: String? = null) {
        val tracks = playbackFiltered(ids.mapNotNull { trackById(it) })
        if (tracks.isEmpty()) return
        player.setShuffle(true)
        player.playQueue(tracks, tracks.random().id, queueKey)
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
        com.shiyin.music.ui.components.ArtCache.invalidateAlbum(getApplication<android.app.Application>(), albumId)
        showToast("专辑信息已保存", null)
    }

    /**
     * v1.2.2: 设置整张专辑(含合集)的歌手。修复合集"专辑歌手设为未知艺术家不生效"——
     * 旧实现只能经 saveAlbumInfo(albumId>0) 改,而合集常无共享正 albumId(albumsMap 按
     * "$album|$artist" 分组),编辑对话框连 albumId 都拿不到,设置静默丢弃。
     * 这里按 albumKey 定位整组曲目:有共享 albumId>0 → 走 album_info_override(整组一个值);
     * 否则 → 对组内每首写 track_info_override.artist(display-only,单曲级),使组内
     * 每首(含 first.artist)都显示为该歌手(如"未知艺术家")。
     */
    fun setAlbumArtist(key: String, artist: String) {
        val tracks = albumOrder(key)
        android.util.Log.d("ArtistEdit", "setAlbumArtist key=$key artist=$artist tracks=${tracks.size}")
        if (tracks.isEmpty()) return
        val sharedAlbumId = tracks.first().albumId
        val hasSharedId = sharedAlbumId > 0 && tracks.all { it.albumId == sharedAlbumId }
        android.util.Log.d("ArtistEdit", "  sharedAlbumId=$sharedAlbumId allSame=${tracks.all { it.albumId == sharedAlbumId }} hasSharedId=$hasSharedId")
        if (hasSharedId) {
            // 正常专辑 / 共享 albumId 的合集:存 album_info_override.artistName
            val cur = albumInfoOverrides[sharedAlbumId]
            android.util.Log.d("ArtistEdit", "  -> album_info_override path, cur=${cur?.albumName}")
            saveAlbumInfo(sharedAlbumId, cur?.albumName ?: "", artist, cur?.type ?: "")
            return
        }
        // 无共享 albumId(孤立单曲/杂烩合集):对每首写单曲级 artist override
        val now = System.currentTimeMillis()
        android.util.Log.d("ArtistEdit", "  -> per-track override path, ${tracks.size} tracks")
        for (t in tracks) {
            val cur = trackInfoOverrides[t.id]
            val entity = com.shiyin.music.data.db.TrackInfoOverrideEntity(
                mediaId = t.id,
                title = cur?.title ?: "",
                artist = artist,
                note = cur?.note ?: "",
                updatedAt = now,
            )
            trackInfoOverrides = trackInfoOverrides.toMutableMap().also { it[t.id] = entity }
            viewModelScope.launch(Dispatchers.IO) { try { dao.upsertTrackInfoOverride(entity) } catch (_: Exception) { } }
        }
        showToast("已更新 ${tracks.size} 首的歌手", null)
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
        com.shiyin.music.ui.components.ArtCache.invalidateAlbum(getApplication<android.app.Application>(), albumId)
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
        com.shiyin.music.ui.components.ArtCache.invalidateAlbum(getApplication<android.app.Application>(), albumId)
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
            com.shiyin.music.ui.components.ArtCache.invalidateAlbum(getApplication<android.app.Application>(), albumId)
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
    /** v1.2.2 Agent: Tavily 联网搜索 key 设置。 */
    fun setTavilyKey(v: String) = viewModelScope.launch { settingsStore.setTavilyKey(v) }
    /** v1.2.2 Agent: 保存全部 LLM 提供商配置。 */
    fun setLlmProviders(providers: List<com.shiyin.music.data.ai.LlmProviderConfig>) =
        viewModelScope.launch { settingsStore.setLlmProviders(providers) }
    /** v1.2.2 Agent: 设置当前激活的 provider key。 */
    fun setLlmActiveProvider(key: String) = viewModelScope.launch { settingsStore.setLlmActiveProvider(key) }
    /** v1.2.2 Agent: 取当前激活 provider 的完整配置(含 key/endpoint/model)。无激活→null。 */
    fun activeLlmConfig(): com.shiyin.music.data.ai.LlmProviderConfig? =
        llmProviders.firstOrNull { it.key == llmActiveProvider }?.takeIf { it.isConfigured }

    /**
     * v1.3.3b: AI 获取歌手各专辑/EP/单曲的发布时间并按时间排序。
     * 流程:Tavily 搜该歌手的作品年表 → LLM 输出每张专辑的发布日期(JSON,格式
     * {"albums":[{"name":"...","date":"YYYY-MM-DD"}]}) → 逐张写 album_art_cache
     * .releaseDate → 按日期(旧→新)生成顺序串存 artist_album_order。
     * @return 成功解析到日期的专辑数;0=失败([onProgress] 已给原因)
     */
    suspend fun fetchArtistAlbumDates(
        artistName: String,
        /** 专辑三元组:albumKey(排序写回用)、albumId(日期写库用,≤0 跳过)、显示名(LLM 识别用) */
        albums: List<Triple<String, Long, String>>,
        onProgress: (String) -> Unit = {},
    ): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        // v1.3.5: onProgress 在 IO 协程触发,调用方把回调直写 Compose state——v5.2 #75
        // 同因(后台写不保证立即重组),状态行不更新/结束后残留"AI 正在联网获取…"。
        // 统一投递主线程,同时打一条 logcat 供真机排查"点了没返回"到底卡在哪一步。
        val progress: (String) -> Unit = { s ->
            android.util.Log.i("Agent", "albumDates[$artistName]: $s")
            android.os.Handler(android.os.Looper.getMainLooper()).post { onProgress(s) }
        }
        val config = activeLlmConfig()
        if (config == null) { progress("未配置 LLM(Agent 设置里填 Key + 选模型)"); return@withContext 0 }
        if (tavilyApiKey.isBlank()) { progress("未配置 Tavily 搜索 Key"); return@withContext 0 }

        // 1. 联网搜该歌手的作品发布年表(中英关键词并搜,取先到的)
        progress("联网搜索 $artistName 作品年表…")
        val query = "$artistName albums discography release dates 专辑 发行日期 年表"
        val tavilyCtx = runCatching {
            com.shiyin.music.data.ai.TavilyService.search(tavilyApiKey, query)
        }.getOrNull()
        val ctxBlock = if (tavilyCtx.isNullOrBlank()) "(未搜到网络资料,凭你的知识回答)" else "网络资料:\n$tavilyCtx"

        // 2. LLM 输出每张专辑的日期(编号方案:给本地列表编号,模型输出名字+日期)
        progress("AI 分析发布时间…")
        val listBlock = albums.joinToString("\n") { (_, _, name) -> "· $name" }
        val prompt = """
            歌手:$artistName 的本地专辑/EP/单曲清单(名字可能与官方写法略有差异,由你对应):
            $listBlock

            $ctxBlock

            为上面每张作品输出官方发布日期。只返回 JSON:
            {"albums":[{"name":"<上面清单里的原样名字>","date":"YYYY-MM-DD"}]}
            规则:date 必须是真实发布日期(不确定的不要编造,直接不返回那一张);不要多余文字。
        """.trimIndent()
        val resp = com.shiyin.music.data.ai.LlmClient.call(config, listOf("user" to prompt))
        val failReason = com.shiyin.music.data.ai.LlmClient.lastError
        com.shiyin.music.data.ai.LlmClient.clearLastError()
        if (resp == null) {
            progress("LLM 调用失败:$failReason")
            return@withContext 0
        }
        // 记 token
        viewModelScope.launch(Dispatchers.IO) {
            com.shiyin.music.data.ai.TokenUsageStore.log(
                getApplication(), llmActiveProvider, "album_dates", resp.promptTokens, resp.completionTokens, resp.cacheTokens,
            )
        }

        // 3. 解析 JSON → 名字→日期(归一化匹配:剥括号/空白,contains 兜底)
        fun norm(s: String) = s.replace(Regex("[(（\\[【].*?[)）\\]】]]"), "").replace(Regex("""\s+"""), "").lowercase()
        val dateByName = mutableMapOf<String, String>()
        try {
            val obj = com.google.gson.JsonParser.parseString(
                Regex("""\{.*}""", RegexOption.DOT_MATCHES_ALL).find(resp.content ?: "")?.value ?: ""
            ).asJsonObject
            obj.getAsJsonArray("albums")?.forEach { el ->
                val o = el?.asJsonObject ?: return@forEach
                val n = o.get("name")?.asString ?: return@forEach
                val d = o.get("date")?.asString ?: return@forEach
                if (Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(d)) dateByName[norm(n)] = d.take(10)
            }
        } catch (_: Exception) { }

        // 4. 匹配本地专辑 + 写日期 + 按日期生成顺序(v1.3.4: 新→旧——最新发布在最上,
        //    与歌手页作品列表的"栈"直觉一致;无日期保持原相对顺序补尾)
        progress("写回日期与顺序…")
        val dated = mutableListOf<Pair<Triple<String, Long, String>, String>>() // (专辑, 日期)
        val undated = mutableListOf<Triple<String, Long, String>>()
        for (a in albums) {
            val hit = dateByName[a.third.let(::norm)]
                ?: dateByName.entries.firstOrNull { it.key.contains(norm(a.third)) || norm(a.third).contains(it.key) }?.value
            if (hit != null) {
                dated.add(a to hit)
                if (a.second > 0) saveAlbumReleaseDate(a.second, hit)
            } else undated.add(a)
        }
        if (dated.isEmpty()) {
            progress("AI 未能确定任何专辑的发布日期(可重试或换更强模型)")
            return@withContext 0
        }
        val ordered = dated.sortedByDescending { it.second }.map { it.first } + undated
        saveArtistAlbumOrder(artistName, ordered.map { it.first })
        progress("")
        dated.size
    }
    /** v1.2.1: 写真源 API key / 源开关(设置·写真源用)。 */
    fun setFanartApiKey(v: String) = viewModelScope.launch { settingsStore.setFanartApiKey(v) }
    fun setLastfmApiKey(v: String) = viewModelScope.launch { settingsStore.setLastfmApiKey(v) }
    fun setImageSourceEnabled(sourceKey: String, enabled: Boolean) = viewModelScope.launch {
        settingsStore.setImageSourceEnabled(sourceKey, enabled)

    }

    /** v1.3.2: 新增自定义写真源(名称 + URL,URL 里用 {name} 表示歌手名;apiKey 可选)。 */
    fun addCustomImageSource(name: String, urlTemplate: String, apiKey: String = "") = viewModelScope.launch {
        val def = com.shiyin.music.data.image.CustomImageSourceDef(
            id = System.currentTimeMillis().toString(36),
            name = name.trim().ifBlank { "自定义源" },
            urlTemplate = urlTemplate.trim(),
            apiKey = apiKey.trim(),
        )
        settingsStore.setCustomImageSources(customImageSources + def)
    }

    /** v1.3.2: 删除自定义写真源,并同步清掉它的禁用标记(源已删,标记无意义)。 */
    fun removeCustomImageSource(id: String) = viewModelScope.launch {
        settingsStore.setCustomImageSources(customImageSources.filter { it.id != id })
        settingsStore.setImageSourceEnabled("custom-$id", true)
    }

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
    /** v1.3.5: 本次设置页是否由 openClean(首页清理建议卡)直接进入——true 时返回
     *  跳过"设置根"直接回来源 tab(用户原则:从哪来回哪;此前从清理退出先落设置
     *  根,再返回才回首页)。 */
    var cleanEntry = false

    fun openClean() {
        val prefill = HashMap<Long, Boolean>()
        for (g in dupGroups()) g.drop(1).forEach { prefill[it.id] = true }
        for (t in shortTracks()) prefill[t.id] = true
        sel = prefill
        filesView = FilesView.Clean
        settingsOpen = true
        // v1.3.5: 记来源 tab,返回时直接回它(首页)
        if (tab != Tab.Library && prevTab == null) prevTab = tab
        cleanEntry = true
    }

    fun toggleSel(id: Long) {
        sel = sel.toMutableMap().apply { this[id] = !(this[id] ?: false) }
    }

    // ── navigation helpers (mirror the prototype's setState calls) ─────────
    fun openPlaylist(pid: String) {
        // v1.3.3 返回恢复:tab 即将从非 Library 切到 Library → 记来源(仅第一次离开,
        // 连续下钻歌手→专辑时不清,返回层层恢复仍回原 tab)。
        if (tab != Tab.Library && prevTab == null) prevTab = tab
        plId = pid; tab = Tab.Library; libChip = "pls"
        settingsOpen = false; albumKey = null; artistKey = null
    }

    fun openArtist(name: String) {
        if (tab != Tab.Library && prevTab == null) prevTab = tab
        artistKey = name; tab = Tab.Library
        settingsOpen = false; artistMerge = false; albumKey = null; plId = null
    }

    fun openAlbum(key: String) {
        if (tab != Tab.Library && prevTab == null) prevTab = tab
        albumKey = key; albumEdit = false; tab = Tab.Library; settingsOpen = false
        // v4: mark album as "viewed" for 你的更新 (one-shot lifecycle)
        if (key.startsWith("aid:")) {
            val aid = key.removePrefix("aid:").toLongOrNull() ?: return
            viewModelScope.launch(Dispatchers.IO) {
                try { dao.markAlbumViewed(aid) } catch (_: Exception) { }
            }
        }
    }

    /**
     * v1.3.3 返回恢复:RootBackHandler 清完一层下钻(albumKey/artistKey/plId)后调。
     * 若清完这层后还有更上层的下钻(如 首页→歌手→专辑 返回到歌手层),不动 tab;
     * 全部下钻层清空(真正退回原 tab 页面)→ 恢复 prevTab 并清字段,用后即清无残留。
     * 返回 true = tab 已被恢复(调用方无需再动)。
     */
    fun restoreTabIfDrillFullyClosed(): Boolean {
        val deeperExists = albumKey != null || artistKey != null || plId != null
        if (!deeperExists && prevTab != null) {
            tab = prevTab!!
            prevTab = null
            return true
        }
        return false
    }

    /** v1.3.3 返回恢复:用户主动切 tab(BottomNav/首页快捷入口)时作废记录的来源,
     *  防止之后从下钻返回时错误恢复到陈旧 tab。 */
    fun discardPrevTab() { prevTab = null }

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

    /** v1.2.1: 进程关停时(sliding-away 触发 onCleared)flush 当前播放 session——
     *  把累计的真实播放秒数(playedSec)写回 play_event 行,避免"播满 30s 后暂停、
     *  随后退出"导致 playedSec 停在初值、丢失 30s 之后的真实时长。硬杀(SIGKILL)
     *  不可恢复,接受。 */
    override fun onCleared() {
        super.onCleared()
        player.finalizeCurrent()
    }
}

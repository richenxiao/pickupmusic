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
import androidx.room.withTransaction
import com.shiyin.music.data.MediaScanner
import com.shiyin.music.data.NO_ALBUM
import com.shiyin.music.data.SettingsStore
import com.shiyin.music.data.Track
import com.shiyin.music.data.UNKNOWN_ARTIST
import com.shiyin.music.data.albumKeyOf
import com.shiyin.music.data.db.AlbumOrderEntity
import com.shiyin.music.data.memory.logTag
import com.shiyin.music.data.db.AlbumOverrideEntity
import com.shiyin.music.data.db.AppDatabase
import com.shiyin.music.data.db.ArtistAliasEntity
import com.shiyin.music.data.db.ArtistEntity
import com.shiyin.music.data.db.IgnoredFolderEntity
import com.shiyin.music.data.db.PlaylistEntity
import com.shiyin.music.data.db.PlaylistTrackEntity
import com.shiyin.music.data.db.SavedLyricEntity
import com.shiyin.music.data.db.TrashedTrackEntity
import com.shiyin.music.data.lyrics.LlmLyricsGuard
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
enum class FilesView { Root, Clean, Trash, Folders, FolderContent, Ignored, Devices, About, Merges, ImageSources, LyricsBook, LyricsSources, CloudBackup, PlaySettings, Equalizer, Logs, DataManage, FilesManage }
enum class LyState { Idle, Searching, Failed }
enum class LyricsKind { Embedded, Sidecar, Online, Imported }

data class ToastData(val text: String, val changeTargetId: Long?)

// v1.3.x 跟行滚动卡顿排查:@Immutable 同 LrcParser.kt 批注(parsed 为唯一
// 不稳定字段,标注后整链稳定,vm.currentLyrics 相同实例时 LyricsScreen 可跳过)。
@androidx.compose.runtime.Immutable
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
    /** v22: 设置也随备份走，故提到 backupManager 之前声明（原来在下面）。 */
    val settingsStore = SettingsStore(app)
    /** v1.2.0 阶段三：用户数据导出/导入（v22 起范围从"修正数据"扩到"用户资产"）。 */
    private val backupManager = com.shiyin.music.data.db.BackupManager(db, settingsStore)
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
    /**
     * v22: 「完整专辑」的播放次数（key = 容器合成 id），与单曲口径**完全分离**。
     * 播放完整专辑只进这张表，绝不进 [playCounts]；播拆分单曲只进 [playCounts]。
     * 数据层由 play_event.kind 保证（见 AppDatabase.playCountFlow / albumPlayCountFlow）。
     */
    var albumPlayCounts by mutableStateOf<Map<Long, Int>>(emptyMap()); private set
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

    // ── v22: 专辑整轨拆分为单曲 ──────────────────────────────────────────────
    /** 拆分方案（key = 整轨文件的 MediaStore id）。 */
    var albumSplits by mutableStateOf<Map<Long, com.shiyin.music.data.db.AlbumSplitEntity>>(emptyMap()); private set
    /** 全部拆分曲目（按 源文件, ordinal 排序）。lib() 展开的唯一数据源。 */
    var splitTracks by mutableStateOf<List<com.shiyin.music.data.db.SplitTrackEntity>>(emptyList()); private set
    /** 源文件 id → 该文件的拆分曲目（含草稿行）。 */
    private var splitTracksBySource: Map<Long, List<com.shiyin.music.data.db.SplitTrackEntity>> = emptyMap()
    /** 方案版本号，用于 lib() 缓存签名 —— 拆分内容一变必须失效缓存。 */
    private fun splitSignature(): String {
        val srcSig = albumSplits.entries.sortedBy { it.key }.hashCode()
        val rowSig = splitTracks.sortedBy { it.id }.hashCode()
        return "${albumSplits.size}|$srcSig|${splitTracks.size}|$rowSig"
    }

    // ── v22: 拆分录入编辑器状态（专辑页 → ⋮ → 专辑整轨拆分为单曲）────────────
    /** 正在编辑的整轨文件 mediaId；null = 编辑器未打开。 */
    var splitEditFor by mutableStateOf<Long?>(null); private set
    /** 0 = 第一步「粘贴歌曲列表」；1 = 第二步「时间轴校准」。 */
    var splitEditStep by mutableIntStateOf(0); private set
    /** 粘贴框内容（编辑器直接双向绑定，故不设 private set）。 */
    var splitPasteText by mutableStateOf("")
    /** 解析出的曲目列表（编辑中，尚未落库）。 */
    var splitRows by mutableStateOf<List<com.shiyin.music.data.split.SplitListParser.Row>>(emptyList()); private set
    /** 时间戳非严格递增的行序号 —— UI 高亮提示修正，不静默丢弃。 */
    var splitConflicts by mutableStateOf<Set<Int>>(emptySet()); private set
    /** 粘贴解析的提示文案（空 = 无问题）。 */
    var splitPasteError by mutableStateOf(""); private set

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
    /** v1.4.0: 跟随系统深浅（设置页三档里的「系统主题」）。 */
    var themeFollowSystem by mutableStateOf(false); private set
    /** 用户选的浅/深；[themeFollowSystem] 为真时被系统当时的值覆盖。 */
    private var darkChoice = false

    /**
     * v1.4.0：把**镜像**里的主题**档位**作为首帧初值 —— `MainActivity.onCreate` 在 `setContent` 之前调用。
     *
     * 只负责"第一帧别画错色"：DataStore 读回来后仍以它为准（并回写镜像）。
     * 全新安装没有镜像时调用方传 light（与 DataStore 的默认值一致）。
     */
    fun applyInitialTheme(mode: String) {
        themeFollowSystem = mode == com.shiyin.music.data.ThemeMirror.MODE_SYSTEM
        darkChoice = mode == com.shiyin.music.data.ThemeMirror.MODE_DARK
        darkTheme = if (themeFollowSystem) systemIsDark() else darkChoice
    }

    /** 系统当前是否深色。 */
    private fun systemIsDark(): Boolean =
        (getApplication<android.app.Application>().resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** 系统日夜切换时由 Activity 的 `onConfigurationChanged` 调用（只对「系统主题」档生效）。 */
    fun onSystemUiModeChanged() {
        if (themeFollowSystem) darkTheme = systemIsDark()
    }
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
    /**
     * v1.5.0 右滑固定（规格 docs/internal/v1.4.1-backlog.md §1）：被用户固定的音乐库条目 key。
     * key 形如 "pl:p3" / "album:<albumKey>" / "artist:<名字>" / "song:<trackId>"。
     * 消费方是 LibraryScreen 既有的「固定优先」排序（LibItem.pinned），不另造排序。
     */
    var pinnedLibItems by mutableStateOf<Set<String>>(emptySet()); private set
    /**
     * v1.5.0 伴唱按钮的视觉状态。对应用户 2026-10-07 口述的「无底色 → 动画过渡到底色 →
     * 完成后变六边形」。**分离引擎尚未接入**，现在只会 Idle <-> Loading；Active 由引擎回写。
     */
    var karaokeState by mutableStateOf(com.shiyin.music.ui.components.KaraokeButtonState.Idle); private set
    /** v1.3.2: 自定义写真源(设置·写真源可增删,CustomSource 按模板拉图)。 */
    var customImageSources by mutableStateOf<List<com.shiyin.music.data.image.CustomImageSourceDef>>(emptyList()); private set
    // v1.1+: 自动保存识别结果（默认开）。关时识别结果临时展示不写入持久化。
    var autoSaveRecognition by mutableStateOf(true); private set
    // v1.1+: 自动识别缓存占用（歌词+封面），供设置页显示与清理。null=未统计。
    var recognitionCacheBytes by mutableStateOf<Long?>(null); private set
    // v2: 播放速度调节（全局持久化）
    var playbackSpeed by mutableStateOf(1.0f); private set
    var retroSpeedMode by mutableStateOf(true); private set
    /**
     * v1.5.0 伴唱 §8 升降 Key（半音，-6..+6）。**刻意不持久化** —— 规格要求 App 重启恢复原调；
     * 切歌是否复位见 onTrackStarted。
     */
    var keyShift by mutableStateOf(0); private set
    /** 上一次触发 onTrackStarted 的曲目 id，用来区分「真的换曲了」与「单曲循环重播」。 */
    private var lastKeyShiftTrackId: Long? = null
    // ── v1.3.x 歌词本设置(设置·歌词本页)────────────────────────────────────
    /** 日语歌打开歌词本时默认开启振假名标注(默认 true)。 */
    var furiganaDefaultOn by mutableStateOf(true); private set
    /** 日语歌开振假名时自动跑三层校验 UtaTen+LLM(默认 true)。中英文歌永不触发。 */
    var furiganaAutoVerify by mutableStateOf(true); private set
    /** 歌词本歌词颜色:"default"=跟随封面取色(现状)/"white"/"black"。 */
    var lyricsColorMode by mutableStateOf("default"); private set
    /** v1.3.10 需求 A26:已同步歌词是否也**始终显示时间轴校对入口**(默认 false)。
     *  默认行为:只有**未同步**(无时间戳、需要人工打轴)的歌词显示校对入口——正常在线
     *  抓到的歌词本来就有时间戳,不该摆一个校对入口在那儿。开了这个开关才常显(便于反复微调)。 */
    var lyricsCalibAlwaysOn by mutableStateOf(false); private set
    // ── v1.3.12:拾光 Agent 行为偏好(设置页可改,见 SettingsStore 的 key 说明) ──
    /** 思考链默认收起(设置 → 拾光Agent)。 */
    var agentThinkingCollapsed by mutableStateOf(false); private set
    /** 强制联网搜索：没把握的具体事实一律先搜再答（默认关，见 SettingsStore 的 key 说明）。 */
    var agentForceSearch by mutableStateOf(false); private set
    /** 允许从对话里自动记住用户信息。 */
    var memoryAutoExtract by mutableStateOf(true); private set
    /** 连"情绪与近况"这类一次性的也记。 */
    var memoryIncludeTransient by mutableStateOf(false); private set
    /** v1.3.14:是否记录 Agent 诊断样本(含模型回复开头)。 */
    var agentDiagEnabled by mutableStateOf(true); private set
    /** v1.3.10 A29:歌词源管理——被关掉的内置源名 + 用户自定义源(设置页可改)。 */
    var lyricsDisabledSources by mutableStateOf<Set<String>>(emptySet()); private set
    var customLyricsSources by mutableStateOf<List<com.shiyin.music.data.lyrics.CustomLyricsSourceDef>>(emptyList())
        private set

    /** v1.3.10 A29:把歌词源配置推给取歌词链路(它零依赖地读这个运行时配置)。 */
    private fun pushLyricsSourceConfig() {
        com.shiyin.music.data.lyrics.LyricsSourceConfig.disabledBuiltins = lyricsDisabledSources
        com.shiyin.music.data.lyrics.LyricsSourceConfig.customSources = customLyricsSources
    }

    /** v1.3.10 A29:开关内置歌词源(关掉的不再参与自动匹配与来源菜单)。 */
    fun setLyricsSourceEnabled(source: String, enabled: Boolean) {
        lyricsDisabledSources = if (enabled) lyricsDisabledSources - source else lyricsDisabledSources + source
        pushLyricsSourceConfig()
        viewModelScope.launch { settingsStore.setLyricsSourceEnabled(source, enabled) }
    }

    /** v1.3.10 A29:新增/更新一个自定义歌词源(同 id 覆盖)。 */
    fun saveCustomLyricsSource(def: com.shiyin.music.data.lyrics.CustomLyricsSourceDef) {
        val list = customLyricsSources.filterNot { it.id == def.id } + def
        customLyricsSources = list
        pushLyricsSourceConfig()
        viewModelScope.launch { settingsStore.setCustomLyricsSources(list) }
    }

    /** v1.3.10 A29:删除一个自定义歌词源。 */
    fun removeCustomLyricsSource(id: String) {
        val list = customLyricsSources.filterNot { it.id == id }
        customLyricsSources = list
        pushLyricsSourceConfig()
        viewModelScope.launch { settingsStore.setCustomLyricsSources(list) }
    }
    /** v1.3.x 歌词本翻译:外语歌进入歌词本时自动触发 AI 翻译(默认 false——AI 永远用户主动触发)。 */
    var translateAutoForeign by mutableStateOf(false); private set
    /** v1.3.x 歌词本翻译:有保存的译文时默认显示(默认 true——译完即存,进来就给看)。 */
    var translateDefaultShow by mutableStateOf(true); private set
    /** v1.3.10 「翻译按钮显示」三档:never=永不显示 / majority=外文较多时(现状,默认) /
     *  any=检测到任何外文就显示。**只影响按钮显隐**,不影响「外语歌自动翻译」(那条仍按
     *  占比判定,见 [isForeignLyrics])。 */
    var translateButtonMode by mutableStateOf("majority"); private set
    /** A36: 翻译失败时是否弹提示(默认关——用户嫌失败提示太频繁,想安静时就不弹)。 */
    var translateErrorToast by mutableStateOf(false); private set

    // ── v1.3.x 播放设置(设置·播放页,用户 2026-09-22 指定分组)──────────────────
    /** 音量均衡:平衡所有曲目的播放音量(响度归一,ExoPlayer setVolume 实现见 PlaybackService)。 */
    var volumeNormalize by mutableStateOf(false); private set
    /** 单声道音频:左右扬声器播放相同的音频。 */
    var monoAudio by mutableStateOf(false); private set
    /** 允许与其他应用同时播放(关闭=播放时暂停其他应用的音频焦点)。 */
    var allowConcurrentPlayback by mutableStateOf(false); private set
    /** 均衡器开关(5 段 EQ,曲线见 equalizerBands)。 */
    var equalizerOn by mutableStateOf(false); private set
    /** 均衡器各频段增益(mB)。默认全 0=平坦;长度即段数(超设备支持段数的忽略)。 */
    var equalizerBands by mutableStateOf(listOf(0, 0, 0, 0, 0)); private set

    // ── v22: 检查更新（本项目没有服务器，版本信息只在 GitHub Releases）────────
    var updateState by mutableStateOf(com.shiyin.music.data.update.UpdateState()); private set
    /** 正在检查中（关于页显示「检查中…」）。 */
    var updateChecking by mutableStateOf(false); private set
    /** 打开关于页时的检查节流：6 小时内查过就用缓存，不重复打网络。 */
    private val updateCheckIntervalMs = 6L * 60 * 60 * 1000

    /** 当前安装版本（取自 BuildConfig，与 build.gradle 的 versionName 同源）。 */
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** 是否该在关于页显示「发现更新」（有更新，且不是用户已忽略的那个版本）。 */
    fun updateAvailable(): Boolean = updateState.shouldPrompt(currentVersion)

    /**
     * 检查更新。
     *
     * [force] = true（用户手动点「检查更新」）忽略节流并给出结果提示；
     * [force] = false（打开关于页）6 小时内查过就直接用缓存。
     *
     * 全链路失败时**只更新时间戳、不清掉上次结果** —— 可能只是这次网络不好，
     * 把已知的"有新版本"擦掉反而更糟。
     */
    fun checkForUpdate(force: Boolean = false) {
        if (updateChecking) return
        val now = System.currentTimeMillis()
        if (!force && now - updateState.lastCheckAt < updateCheckIntervalMs) return
        updateChecking = true
        appScope.launch {
            val info = try {
                com.shiyin.music.data.update.UpdateChecker.fetchLatest()
            } catch (_: Exception) {
                null
            }
            val next = if (info == null) {
                updateState.copy(lastCheckAt = now)
            } else {
                updateState.copy(
                    lastCheckAt = now,
                    latestVersion = info.version,
                    latestTag = info.tag,
                    notes = info.notes,
                    apkUrl = info.apkUrl,
                    pageUrl = info.pageUrl.ifBlank { updateState.pageUrl },
                )
            }
            updateState = next
            updateChecking = false
            settingsStore.setUpdateState(next)
            if (force) {
                withContext(Dispatchers.Main) {
                    when {
                        info == null -> showToast("检查更新失败，请检查网络后重试", null)
                        com.shiyin.music.data.update.VersionCompare.isNewer(info.version, currentVersion) ->
                            showToast("发现新版本 v${info.version}", null)
                        else -> showToast("已是最新版本 v$currentVersion", null)
                    }
                }
            }
        }
    }

    /** 「忽略此版本」：同一版本不再提示，直到出现更新的版本。 */
    fun dismissUpdate() {
        val next = updateState.copy(dismissedVersion = updateState.latestVersion)
        updateState = next
        appScope.launch { settingsStore.setUpdateState(next) }
    }

    /** 「下载新版本」：优先 .apk 直链，拿不到就退回 Release 页面。 */
    fun openUpdateDownload() {
        val url = updateState.downloadUrl
        if (url.isBlank()) {
            showToast("暂时取不到下载地址，请到 GitHub Releases 查看", null)
            return
        }
        openExternalUrl(url)
    }

    /** 用系统浏览器/下载器打开外链（ACTION_VIEW）。 */
    fun openExternalUrl(url: String) {
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(i)
        } catch (_: Exception) {
            showToast("无法打开链接", null)
        }
    }

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
    /**
     * v1.3.x 第4项:设置子页导航改为**返回栈**。
     *
     * 之前 filesView 是扁平状态,系统/手势返回一律 `filesView = Root`(跳过中间
     * 层级),而页面返回按钮走各自写死的父级(均衡器→播放设置)——两条路径不一致,
     * 三级页手势返回直接跳回设置根(用户实测)。
     *
     * 现在 forward 一律 [navFilesTo](push),back 一律 [navFilesBack](pop),
     * 按钮返回与系统/手势返回共用同一个栈,逐级退出。栈在打开设置时清空
     * (每次进入都是全新导航)。folderKey(文件夹内容页)与 cleanEntry(清理建议
     * 首页直达)是两个真实存在的旁路状态,在返回处各自特殊处理——不是"给某个
     * 页面写死 route",而是这两个页面的进入本来就不只依赖 filesView。
     */
    var filesView by mutableStateOf(FilesView.Root)
        private set
    private val filesNavStack = ArrayDeque<FilesView>()

    /** 进入子页(push)。重复进入当前页不入栈。 */
    fun navFilesTo(view: FilesView) {
        if (view != filesView) {
            filesNavStack.addLast(filesView)
            filesView = view
        }
    }

    /** 返回上一级(pop)。栈空(已在根)返回 false,由调用方决定关设置。 */
    fun navFilesBack(): Boolean {
        val prev = filesNavStack.removeLastOrNull() ?: return false
        filesView = prev
        return true
    }

    /** 打开设置(每次进入清空返回栈,从根页开始)。 */
    fun openSettingsView() {
        filesNavStack.clear()
        filesView = FilesView.Root
        settingsOpen = true
    }
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
    // v1.3.x morph P0(设计文档 §3.3-2):歌词卡片 window 坐标 Rect 持续上报
    // (onGloballyPositioned)——morph overlay 展开起点/收起终点取卡片实时位置。
    // P0 只上报暂无消费者;P1 overlay 读取。Rect 非稳定类型,存 field 不做
    // 状态订阅(overlay 用 snapshotFlow 轮询读,避免 Rect 相等性缺失导致的
    // 无效重组)。
    var lyricsCardBounds: androidx.compose.ui.geometry.Rect? = null
    // v1.3.x 状态收敛:progress 真源由 overlay 内 remember 上提为 VM 常驻单例——
    // PlayerScreen(dim/卡片 alpha)、LyricsScreen(跟行暂停)、overlay 全部直接读
    // vm.morph.progress.value;删除 lyricsMorphProgress 镜像、lyricsMorphDrag/
    // lyricsExitRequest 回调注入、phase 枚举(销毁门改调用方收尾,见 collapseLyrics)。
    val morph = com.shiyin.music.ui.components.LyricsMorphState(viewModelScope)
    /** 收起收尾统一入口(返回键/系统手势/下拉闸门共用):collapse() suspend 到
     *  p==0 才返回,此时才卸载 overlay。中途被新拖拽打断时 snapTo 经 Animatable
     *  内部 mutex 取消 animateTo → 协程取消异常 → 本行不执行,overlay 存活。 */
    fun collapseLyrics(velocityPx: Float = 0f) {
        viewModelScope.launch {
            morph.collapse(lyricsCardBounds, velocityPx)
            lyricsOn = false
            lySheet = false
        }
    }
    /** 强制关闭(无动画):切歌空歌词/删歌词/关全部 overlay 等场景。snapTo(0)
     *  经 mutex 取消可能在跑的 open/collapse 动画,并把 progress 归 0(播放页
     *  dim/卡片 alpha 跟着归位,不残留半淡出状态)。 */
    fun closeLyricsInstant() {
        lyricsOn = false
        lySheet = false
        viewModelScope.launch { morph.progress.snapTo(0f) }
    }
    // v1.3.x morph P3(§3.5-4):手势协同状态——指针闸门判"起点是否 chrome 区
    // (列表区外)"与"列表是否已在顶"。LyricsScreen 写入(列表 bounds 随布局
    // 更新 + canScrollBackward 派生),overlay 的 pointerInput 读。普通 field,
    // 手势事件里读不触发重组。
    var lyricsListTopPx: Float? = null
    var lyricsListBottomPx: Float? = null
    var lyricsListAtTop: Boolean = true
    /** 退出请求(左上角返回键/系统返回统一入口):走带动画的 collapse。 */
    fun requestLyricsExit() { collapseLyrics() }
    // v1.3.x bug① 修复:播放页"带动画退出"的请求回调。RootBackHandler 的
    // playerOpen 兜底分支(歌词关着时的系统返回)原本直接置 playerOpen=false
    // 瞬间消失;sheet 返回回调改穿透(shouldDismissOnBackPress=false)后该分支
    // 成为系统手势关播放页的主路径,必须播 sheet 下滑动画。sheetState 在 AppRoot
    // 组合作用域,BackHandler 拿不到——AppRoot 把 sheetState.hide() 注入这里,
    // hide 完成→currentValue=Hidden→既有 hotfix-3 LaunchedEffect 置
    // playerOpen=false 收尾(同 onDismissRequest 既有收尾链,零新状态机)。
    // 注入方为空(播放 sheet 未组合)时兜底直接关,防手势空窗。
    var playerExitRequest by mutableStateOf<(() -> Unit)?>(null)
    fun requestPlayerExit() { playerExitRequest?.invoke() ?: run { playerOpen = false; sleepMenu = false } }
    var lyricsImportDialog by mutableStateOf(false)
    var lyricsSourcePicker by mutableStateOf(false)
    var sleepMenu by mutableStateOf(false)
    var q by mutableStateOf("")
    // v5.2: 搜索历史——SharedPreferences 持久化、保序、最近在前、去重、上限 12。
    // 用 "\n" join 存为单串（StringSet 不保序）。键盘"搜索"键或点结果播放时 commit。
    var searchHistory by mutableStateOf<List<String>>(emptyList()); private set
    private val searchPrefs by lazy { app.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE) }
    /**
     * v1.3.x 鉴权: 用户手动删除歌词的 mediaId 黑名单(SharedPreferences 持久化,与
     * searchHistory 同一做法,不引入新依赖)。自动匹配(loadLyricsFor)查这里命中即跳过。
     *
     * ⚠️ 必须声明在第一个 [init] 之前——`init { loadManualDeletedLyrics() }` 会
     * `synchronized(manualDeletedLyrics)`;Kotlin 属性按**声明顺序**初始化,声明
     * 在 init 之后就仍是 null → `synchronized(null)` 抛
     * "NullPointerException: Null reference used for synchronization",
     * 启动即崩(本轮 13:19 版本黑屏的根因)。
     */
    private val manualDeletedLyrics = HashSet<Long>()
    init {
        val saved = searchPrefs.getString("terms", "") ?: ""
        searchHistory = if (saved.isBlank()) emptyList() else saved.split("\n")
        // v1.3.x 鉴权:启动时加载用户手动删除过的歌词黑名单(持久化,见 deleteLyrics)
        loadManualDeletedLyrics()
    }
    init {
        // v1.3.x 需求4:封面预加载——切歌后后台预取相邻两首(nextTrackId/
        // prevTrackId 尊重 shuffle 顺序与循环模式,与 next()/prev() 实际切到
        // 的曲一致)到 ArtCache 内存 LruCache(播放页 1024 桶),滑动/按钮切歌
        // 时直接命中,消除"不同专辑封面空白 1-2 帧"。同专辑跳过(当前曲的
        // 1024 桶位图播放页正在显示,必然已缓存)。IO 线程,不碰 UI。快速连
        // 滑预加载没跑完则回退现场解码路径(=现状,不更差)。
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { player.currentId }
                .collect { id ->
                    if (id == null) return@collect
                    val cur = trackById(id) ?: return@collect
                    val neighbors = listOfNotNull(player.nextTrackId(), player.prevTrackId())
                        .mapNotNull { trackById(it) }
                        .filter { it.albumId != cur.albumId }  // 同专辑跳过
                        .distinct()
                    for (t in neighbors) {
                        launch(Dispatchers.IO) {
                            runCatching {
                                com.shiyin.music.ui.components.ArtCache.load(
                                    getApplication<Application>(), t, 1024,
                                )
                                // v1.3.x 切歌动画补做:迷你播放条 40dp 封面走 160 桶,
                                // 与 1024 桶互不共享(LruCache 按 albumId@bucket 分桶)——
                                // 动画滑入的新封面若 160 桶未暖会先字母占位再突变成图,
                                // 相邻曲两个桶都预热。
                                com.shiyin.music.ui.components.ArtCache.load(
                                    getApplication<Application>(), t, 160,
                                )
                            }
                        }
                    }
                }
        }
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
    /**
     * 当前歌词。**写入即触发一次 hash 搬键**（见 [maybeMigrateLyricsHash]）。
     *
     * 为什么挂在 setter 而不是各个加载点：歌词有 6 条加载路径（已存 / 内嵌 ID3 / 同名 .lrc /
     * 联网匹配 / Agent 联网搜 / 导入文本），逐个挂容易漏，将来再加一条也会漏。
     */
    private val currentLyricsState = mutableStateOf<LoadedLyrics?>(null)
    var currentLyrics: LoadedLyrics?
        get() = currentLyricsState.value
        private set(value) {
            currentLyricsState.value = value
            value?.let { maybeMigrateLyricsHash(it) }
        }

    /**
     * v1.4.0:把**旧 hash 名下**的注音/译文搬到新 hash 名下（一次性）。
     *
     * 背景：hash 算法改过（改为剥掉行首 `[mm:ss.xx]`，修"打轴后翻译恢复不到"）。升级后按新
     * hash 查不到升级前存的数据 → 用户表现为"我的注音/译文不见了"。搬键后所有读取点（它们
     * 一律用 `LyricsHash.of`）自然命中，无需逐个改。
     *
     * 守卫：新 hash 名下**已经有行**就跳过（用户升级后又存取过，旧行属下堂），
     * 避免 UPDATE 撞主键。无时间戳的歌词两个算法同值，[needsMigration] 直接短路。
     */
    private fun maybeMigrateLyricsHash(ly: LoadedLyrics) {
        val fg = com.shiyin.music.data.furigana.LyricsHash
        if (!fg.needsMigration(ly.raw)) return
        val newHash = fg.of(ly.raw)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // 决策抽成纯函数(planHashMigration),三种情形由单测钉住:
                // 不需要搬 / 需要搬(from=旧算法 hash → to=新算法 hash) / 新 hash 下已有行(跳过防撞主键)。
                val plan = com.shiyin.music.data.furigana.planHashMigration(
                    ly.raw, dao.countRowsUnderHash(ly.mediaId, newHash),
                ) ?: return@runCatching
                dao.migrateReadingOverrideHash(ly.mediaId, plan.from, plan.to)
                dao.migrateExternalEvidenceHash(ly.mediaId, plan.from, plan.to)
                dao.migrateLyricsTranslationHash(ly.mediaId, plan.from, plan.to)
                android.util.Log.i("Lyrics", "歌词 hash 搬键完成 mediaId=${ly.mediaId}")
            }
        }
    }
    /** v1.3.x: 手动匹配的实时进度文案(agent websearch 两阶段:联网搜索→AI 生成);
     *  null=普通匹配,预览卡用默认"正在匹配歌词…"。 */
    var lyProgressText by mutableStateOf<String?>(null); private set
    /** v1.3.x: 最近一次匹配失败的可读原因(LLM HTTP 429/网络异常/未配置等);null=用默认文案。 */
    var lyFailReason by mutableStateOf<String?>(null); private set
    private val triedAuto = HashSet<Long>()
    private var lyricsJob: Job? = null

    // ── v1.3.x: 打轴模式(歌词手动打轴交互重做) ──────────────────────────────
    /**
     * 打轴模式状态机:未开始 / 进行中 / 已完成。
     * - NotStarted: 默认状态,歌词本正常浏览
     * - InProgress: 打轴进行中(顶部进度/当前行高亮/点击锚定/撤销/跳过/微调)
     * - Preview: 打轴完成,预览模式(播放时高亮跟随,确认无误再保存)
     */
    enum class TapSyncMode { NotStarted, InProgress, Preview }
    var tapSyncMode by mutableStateOf(TapSyncMode.NotStarted); private set
    /** 打轴模式当前行索引(待锚定的句子)。 */
    var tapSyncCurrentLine by mutableStateOf(0); private set
    /** 撤销栈:最近 20 步(行索引 + 旧时间戳)。 */
    private val tapSyncUndoStack = ArrayDeque<Pair<Int, Long?>>(20)
    /** 打轴模式启动时的原始歌词(退出时若用户放弃改动,回滚到这里)。 */
    private var tapSyncOriginalLines: List<com.shiyin.music.data.lyrics.LyricLine>? = null

    // ── v1.3.x 第9项:歌词时间轴校准 ────────────────────────────────────────
    /** 连续调整已顶到相邻时间戳的边界(第一次拦截)。松开按钮后复位;
     *  再次长按同一方向 → 才允许进入涟漪平移(第9项 I/J:第一次拦截≠用户已确认
     *  要改后面)。 */
    var calibAtBoundary by mutableStateOf(false); private set
    /** 已进入涟漪平移(第二次长按):后续调整带动**后面所有已有时间戳的行**整体平移。 */
    var calibRippling by mutableStateOf(false); private set
    /** 涟漪预览(未提交):整段后续时间轴平移后的结果。null = 无预览。
     *  长按期间只改这里、不落库——用户中途松手/取消/返回都不会留下半完成的时间轴;
     *  松手时才一次性 commitLyricLines 提交(第9项 L)。 */
    var calibPendingLines by mutableStateOf<List<com.shiyin.music.data.lyrics.LyricLine>?>(null); private set

    /** 校准模式下"当前展示"的歌词行(预览优先)。UI 用它渲染时间胶囊。 */
    val calibDisplayLines: List<com.shiyin.music.data.lyrics.LyricLine>
        get() = calibPendingLines ?: currentLyrics?.parsed?.lines.orEmpty()

    /** 上一句**真实存在**的时间戳(跳过无时间戳行——不凭空造边界)。 */
    private fun calibLowerBound(lines: List<com.shiyin.music.data.lyrics.LyricLine>, i: Int): Long? {
        for (k in i - 1 downTo 0) lines.getOrNull(k)?.timeMs?.let { return it }
        return null
    }

    /** 下一句**真实存在**的时间戳(同上)。 */
    private fun calibUpperBound(lines: List<com.shiyin.music.data.lyrics.LyricLine>, i: Int): Long? {
        for (k in i + 1 until lines.size) lines.getOrNull(k)?.timeMs?.let { return it }
        return null
    }

    /**
     * 进入校准(准星按钮)。与打轴共用同一模式标志,但交互改为
     * "选句 → 点该行时钟绑定 / 底部微调"(第9项 C/F/Q)。
     */
    fun calibEnter() = tapSyncStart()

    /** 选句:与 index 绑定(不依赖列表位置)。该行已有时间戳时，把播放位置带到它附近，
     *  用户不必自己从当前进度往回找(第9项 F)。 */
    fun calibSelect(i: Int) {
        val cur = currentLyrics ?: return
        if (tapSyncMode != TapSyncMode.InProgress) return
        if (i < 0 || i >= cur.parsed.lines.size) return
        tapSyncCurrentLine = i
        calibAtBoundary = false
        calibRippling = false
        calibPendingLines = null
        calibBoundaryArmed = false
        // 有既有时间戳 → 播放位置回到该句附近(留 1.2s 余量便于听辨)。
        cur.parsed.lines[i].timeMs?.let { t ->
            player.seekToDisplayMs((t - cur.offsetMs - 1200).coerceAtLeast(0))
        }
    }

    /** 把**当前播放时间**绑定为当前选中句的时间戳(点这一行的时钟)。
     *  受相邻边界约束:结果钳在 (上一真实时间戳, 下一真实时间戳) 内。 */
    fun calibBindCurrentTime() {
        if (tapSyncMode != TapSyncMode.InProgress) return
        val cur = currentLyrics ?: return
        val i = tapSyncCurrentLine
        val lines = calibDisplayLines.toMutableList()
        if (i !in lines.indices) return
        val pos = player.displayPositionMs()
        var t = (pos - cur.offsetMs).coerceAtLeast(0)
        val lo = calibLowerBound(lines, i)
        val hi = calibUpperBound(lines, i)
        if (lo != null) t = t.coerceAtLeast(lo + 1)
        if (hi != null) t = t.coerceAtMost(hi - 1)
        tapSyncUndoStack.addLast(i to lines[i].timeMs)
        if (tapSyncUndoStack.size > 20) tapSyncUndoStack.removeFirst()
        lines[i] = lines[i].copy(timeMs = t)
        commitLyricLines(lines)
        calibAtBoundary = false
        calibRippling = false
        calibPendingLines = null
    }

    /** 微调(单击 / 长按连续)。返回 true = 这一次被**边界拦下**(时间未再变化)。
     *  严格递增:结果钳在 (上一真实时间戳, 下一真实时间戳) 内(第9项 G/H)。 */
    fun tapSyncAdjust(deltaMs: Long): Boolean {
        if (tapSyncMode != TapSyncMode.InProgress) return false
        val cur = currentLyrics ?: return false
        val i = tapSyncCurrentLine
        val lines = calibDisplayLines.toMutableList()
        if (i !in lines.indices) return false
        val before = lines[i].timeMs ?: return false
        val lo = calibLowerBound(lines, i)
        val hi = calibUpperBound(lines, i)
        var nt = before + deltaMs
        if (lo != null) nt = nt.coerceAtLeast(lo + 1)
        if (hi != null) nt = nt.coerceAtMost(hi - 1)
        nt = nt.coerceAtLeast(0)
        val blocked = nt == before
        if (blocked) {
            calibAtBoundary = true
            return true
        }
        lines[i] = lines[i].copy(timeMs = nt)
        // 涟漪进行中 → 只更新预览;单击(非涟漪) → 立即提交。
        if (calibRippling) calibPendingLines = lines else commitLyricLines(lines)
        return false
    }

    /**
     * 第二次长按 → 正式进入涟漪平移(第9项 J/K):从选中句起，
     * 把**后面所有已有时间戳的行**按"相对原始时间戳的同一 offset"整体平移，
     * 保持它们之间原有的时间间隔;无时间戳的行**不动、也不被赋时间**。
     * 结果先写进 pending 预览，松手时提交。
     */
    fun calibRippleStep(deltaMs: Long) {
        if (tapSyncMode != TapSyncMode.InProgress) return
        val cur = currentLyrics ?: return
        val orig = tapSyncOriginalLines ?: return
        val i = tapSyncCurrentLine
        val base = calibDisplayLines
        val selOrig = orig.getOrNull(i)?.timeMs ?: return
        val selNow = base.getOrNull(i)?.timeMs ?: return
        calibRippling = true
        val target = selNow + deltaMs
        val offset = target - selOrig
        val out = base.toMutableList()
        for (k in i until out.size) {
            val ot = orig.getOrNull(k)?.timeMs ?: continue   // 无时间戳行保持原样
            out[k] = out[k].copy(timeMs = (ot + offset).coerceAtLeast(0))
        }
        calibPendingLines = out
    }

    /** 松手:把涟漪预览一次性提交(第9项 L)。无预览则空操作。 */
    fun calibCommitPending() {
        val pending = calibPendingLines ?: return
        calibPendingLines = null
        calibRippling = false
        calibAtBoundary = false
        commitLyricLines(pending)
    }

    /** 松手/松开边界提示:复位"已拦截""涟漪"两个手势态。 */
    fun calibReleaseGesture() {
        calibAtBoundary = false
    }

    /** 校准目标前后移动(第9项 R):语义是移动**校准选句**,不是切歌。 */
    fun calibMoveTarget(delta: Int) {
        val cur = currentLyrics ?: return
        val n = cur.parsed.lines.size
        if (n == 0) return
        calibSelect((tapSyncCurrentLine + delta).coerceIn(0, n - 1))
    }

    // ── 长按连续调整(单击/长按共用同一约束;涟漪只在"第二次长按"才进) ────────────
    /** 上一次长按已顶到边界且已松手——下一次同向长按进入涟漪平移(第9项 I→J)。 */
    private var calibBoundaryArmed = false

    /**
     * 长按的一步(由 UI 的长按循环每 tick 调一次)。
     *
     *  - 还没进涟漪：走 [tapSyncAdjust](带相邻边界钳制)；顶到边界返回 true
     *    → UI 这一轮长按就此停止（**第一次到达边界=拦截**，不偷偷把后面一起挪）。
     *  - 上次已"顶到边界过且松手过"（[calibBoundaryArmed]）→ 这次长按进涟漪平移，
     *    走 [calibRippleStep]（只更新预览，松手才提交）。
     */
    fun calibHoldStep(directionMs: Long): Boolean {
        if (calibBoundaryArmed && !calibRippling) calibRippling = true
        return if (calibRippling) {
            calibRippleStep(directionMs)
            false
        } else {
            tapSyncAdjust(directionMs)
        }
    }

    /** 松手。涟漪进行中 → 一次性提交预览；只是顶到边界 → 记下"armed"(下次同向长按进涟漪)。 */
    fun calibHoldRelease() {
        if (calibRippling) {
            calibCommitPending()
        } else if (calibAtBoundary) {
            calibBoundaryArmed = true
        }
        calibRippling = false
        calibAtBoundary = false
    }

    fun tapSyncStart() {
        val cur = currentLyrics ?: return
        tapSyncOriginalLines = cur.parsed.lines
        tapSyncCurrentLine = 0
        tapSyncUndoStack.clear()
        calibAtBoundary = false
        calibRippling = false
        calibPendingLines = null
        tapSyncMode = TapSyncMode.InProgress
    }

    fun tapSyncExit() {
        // 中途退出保留已打轴结果(用户明确:"中途退出保留已打轴结果")
        // 第9项 L:涟漪的 pending 预览若还没提交,退出时丢弃——不留半完成的时间轴。
        calibPendingLines = null
        calibRippling = false
        calibAtBoundary = false
        tapSyncMode = TapSyncMode.NotStarted
        tapSyncOriginalLines = null
        tapSyncUndoStack.clear()
    }

    /**
     * 歌词行统一提交:重建 raw(带时间戳的 LRC)→ 更新 currentLyrics → 立即落库。
     *
     * 打轴的全部动作(tapSyncTap / Undo / Adjust / Discard)与 syncLyricLine 共用。
     * 抽出来的原因:这段「重建 raw + copy + upsert」原先在四处逐字复制,任一处漏改
     * 就出 bug——历史上正是它导致「只改了 parsed 没改 raw,打好轴的时间戳存不进去」,
     * 以及「放弃打轴只回滚 parsed、不回滚 raw/DB,等于没放弃」。现在只有一处定义,
     * LRC 时间戳格式与 synced 判据也只有一个来源。
     */
    private fun commitLyricLines(lines: List<com.shiyin.music.data.lyrics.LyricLine>) {
        val cur = currentLyrics ?: return
        val newRaw = lines.joinToString("\n") { line ->
            val t = line.timeMs
            if (t != null) {
                "[${(t / 60_000).toString().padStart(2, '0')}:" +
                    "${((t % 60_000) / 1000).toString().padStart(2, '0')}." +
                    "${(t % 1000).toString().padStart(3, '0')}] ${line.text}"
            } else {
                line.text
            }
        }
        val next = cur.copy(
            raw = newRaw,
            parsed = com.shiyin.music.data.lyrics.ParsedLyrics(
                lines = lines,
                synced = lines.all { it.timeMs != null },
            ),
            saved = false,
        )
        currentLyrics = next
        // 每点一句立即落库(切歌/杀进程都不丢)
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertLyric(SavedLyricEntity(next.mediaId, newRaw, next.source, next.offsetMs, System.currentTimeMillis(), autoSaved = 0))
        }
    }

    /** 打轴模式下点击屏幕任意位置(或空格/回车)锚定当前行。 */
    fun tapSyncTap() {
        val cur = currentLyrics ?: return
        if (tapSyncMode != TapSyncMode.InProgress) return
        val lines = cur.parsed.lines.toMutableList()
        if (tapSyncCurrentLine < 0 || tapSyncCurrentLine >= lines.size) return
        // v23: 打的时间戳必须是**当前曲目**坐标(歌词时间戳的坐标系)。player.positionMs
        // 在拆分单曲下是整轨文件绝对位置,直接存下去会让这首的歌词整体偏移。
        val pos = player.displayPositionMs()
        val newTime = (pos - cur.offsetMs).coerceAtLeast(0)
        // 撤销栈:记录该行旧时间戳(撤销时恢复)
        tapSyncUndoStack.addLast(tapSyncCurrentLine to lines[tapSyncCurrentLine].timeMs)
        if (tapSyncUndoStack.size > 20) tapSyncUndoStack.removeFirst()
        lines[tapSyncCurrentLine] = com.shiyin.music.data.lyrics.LyricLine(newTime, lines[tapSyncCurrentLine].text)
        commitLyricLines(lines)
        // 前进到下一句;最后一句时进入预览模式
        if (tapSyncCurrentLine < lines.size - 1) {
            tapSyncCurrentLine++
        } else {
            tapSyncMode = TapSyncMode.Preview
        }
    }

    /** 撤销:回退上一句,时间戳清空,高亮回到上一句。 */
    fun tapSyncUndo() {
        if (tapSyncMode != TapSyncMode.InProgress) return
        if (tapSyncUndoStack.isEmpty()) return
        val (idx, oldTime) = tapSyncUndoStack.removeLast()
        val cur = currentLyrics ?: return
        val lines = cur.parsed.lines.toMutableList()
        lines[idx] = lines[idx].copy(timeMs = oldTime)
        commitLyricLines(lines)
        tapSyncCurrentLine = idx
    }

    /** 跳过:这句不打,留空,前进到下一句。 */
    fun tapSyncSkip() {
        if (tapSyncMode != TapSyncMode.InProgress) return
        val cur = currentLyrics ?: return
        val lines = cur.parsed.lines
        if (tapSyncCurrentLine < lines.size - 1) {
            tapSyncCurrentLine++
        } else {
            tapSyncMode = TapSyncMode.Preview
        }
    }

    /** 预览模式确认:保存并退出。 */
    fun tapSyncConfirm() {
        if (tapSyncMode != TapSyncMode.Preview) return
        tapSyncMode = TapSyncMode.NotStarted
        tapSyncOriginalLines = null
        tapSyncUndoStack.clear()
        saveLyrics()
    }

    /** 预览模式放弃:回滚到打轴前的原始歌词。 */
    fun tapSyncDiscard() {
        if (tapSyncMode != TapSyncMode.Preview) return
        val original = tapSyncOriginalLines ?: return
        // v23 修复:"放弃"必须把 raw 与**数据库**一起回滚。原实现只 copy 了 parsed,
        // 而打轴每点一句都已 upsertLyric 落库 —— 用户点"放弃"后退出再进,时间戳
        // 原样复活(等于没放弃)。改走统一的 commitLyricLines:重建无时间戳的 raw
        // 并覆盖落库。
        commitLyricLines(original)
        tapSyncMode = TapSyncMode.NotStarted
        tapSyncOriginalLines = null
        tapSyncUndoStack.clear()
    }

    // trash plumbing
    var pendingTrashOp: TrashOp? = null; private set
    var launchTrashIntent: ((PendingIntent) -> Unit)? = null

    var hasMediaPermission by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            settingsStore.flow.collect { s ->
                // v1.4.0：主题三档 = 跟随系统开关 + 浅/深选择。「系统」档用系统当时的日夜解析成浅或深。
                themeFollowSystem = s.themeFollowSystem
                darkChoice = s.dark
                darkTheme = if (s.themeFollowSystem) systemIsDark() else s.dark
                // v1.4.0：DataStore 是**唯一权威** —— 这里把镜像与系统夜间模式都对齐到它。
                // 镜像：万一陈旧（例如用户在国际版/别处改过、或镜像写入失败），以 DataStore 为准改写；
                // 系统夜间模式：让 values-night 资源（窗口底/启动画面底）跟随应用内主题，
                // 只在档位变化时才真正调用（见 ThemeMode）。
                val themeMode = when {
                    s.themeFollowSystem -> com.shiyin.music.data.ThemeMirror.MODE_SYSTEM
                    s.dark -> com.shiyin.music.data.ThemeMirror.MODE_DARK
                    else -> com.shiyin.music.data.ThemeMirror.MODE_LIGHT
                }
                com.shiyin.music.data.ThemeMirror.write(getApplication(), themeMode)
                com.shiyin.music.data.ThemeMode.apply(getApplication(), themeMode)
                gapless = s.gapless
                player.gaplessEnabled = s.gapless
                autoMatch = s.autoMatch
                onboarded = s.onboarded
                firstScanDone = s.firstScanDone
                recentIds = s.recentIds
                // v22: 检查更新状态（最近一次检查结果 + 已忽略版本）
                updateState = s.updateState
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
                // v1.3.x 歌词本设置同步
                furiganaDefaultOn = s.furiganaDefaultOn
                furiganaAutoVerify = s.furiganaAutoVerify
                lyricsColorMode = s.lyricsColorMode
                lyricsCalibAlwaysOn = s.lyricsCalibAlwaysOn
                agentThinkingCollapsed = s.agentThinkingCollapsed
                agentForceSearch = s.agentForceSearch
                memoryAutoExtract = s.memoryAutoExtract
                memoryIncludeTransient = s.memoryIncludeTransient
                agentDiagEnabled = s.agentDiagEnabled
                // v1.3.10 A29:歌词源配置 → 运行时(取歌词链路读它)
                lyricsDisabledSources = s.lyricsDisabledSources
                customLyricsSources = s.customLyricsSources
                pushLyricsSourceConfig()
                // v1.3.x 歌词本翻译设置同步
                translateAutoForeign = s.translateAutoForeign
                translateDefaultShow = s.translateDefaultShow
                translateErrorToast = s.translateErrorToast
                translateButtonMode = s.translateButtonMode
                // v1.3.x 播放设置同步
                volumeNormalize = s.volumeNormalize
                monoAudio = s.monoAudio
                allowConcurrentPlayback = s.allowConcurrentPlayback
                equalizerOn = s.equalizerOn
                equalizerBands = s.equalizerBands
                // 下发给服务进程的 ExoPlayer 生效(音量均衡/焦点/均衡器)。
                player.applyAudioSettings(
                    s.volumeNormalize, s.monoAudio, s.allowConcurrentPlayback, s.equalizerOn,
                    s.equalizerBands,
                )
                // v1.2.1: 写真源运行时配置同步到 ImageSourceConfig(源读取它,即时生效)
                fanartApiKey = s.fanartApiKey
                lastfmApiKey = s.lastfmApiKey
                disabledImageSources = s.disabledImageSources
        pinnedLibItems = s.pinnedLibItems
                customImageSources = s.customImageSources
                com.shiyin.music.data.image.ImageSourceConfig.fanartApiKey = s.fanartApiKey
                com.shiyin.music.data.image.ImageSourceConfig.lastfmApiKey = s.lastfmApiKey
                com.shiyin.music.data.image.ImageSourceConfig.disabledSources = s.disabledImageSources
                com.shiyin.music.data.image.ImageSourceConfig.customSources = s.customImageSources
                if (!settingsLoaded) {
                    settingsLoaded = true
                    isOnboarding = !s.onboarded
                    if (permGranted) handlePermission()
                    // v22: 启动时补一份本地快照（距上次超 24h 才写）—— 防手滑/防误操作。
                    // 内部有节流与 firstScanDone 检查，重复调用是廉价的 no-op。
                    maybeAutoSnapshot()
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
        // v22: 专辑级计数单独一条 flow（kind='album'），与单曲口径物理隔离。
        viewModelScope.launch { dao.albumPlayCountFlow().collect { l -> albumPlayCounts = l.associate { it.mediaId to it.count } } }
        viewModelScope.launch { dao.albumOverrideFlow().collect { l -> albumOverrides = l.associateBy { it.mediaId } } }
        viewModelScope.launch { dao.albumInfoOverrideFlow().collect { l -> albumInfoOverrides = l.associateBy { it.albumId } } }
        viewModelScope.launch { dao.trackInfoOverrideFlow().collect { l -> trackInfoOverrides = l.associateBy { it.mediaId } } }
        viewModelScope.launch { dao.trackAlbumMoveFlow().collect { l -> trackAlbumMoves = l.associate { it.mediaId to it.albumId } } }
        // v22: 专辑整轨拆分为单曲 —— 方案 + 拆分曲目。两者都进 libSignature()，
        // 拆分内容一变 lib() 缓存立即失效（否则拆完专辑界面"没反应"）。
        viewModelScope.launch {
            dao.albumSplitFlow().collect { l -> albumSplits = l.associateBy { it.sourceMediaId } }
        }
        viewModelScope.launch {
            dao.splitTrackFlow().collect { l ->
                splitTracks = l
                splitTracksBySource = l.groupBy { it.sourceMediaId }
            }
        }
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
        // v22: 拆分单曲的播放引擎桥 —— 队列构造时把方案写进 MediaItem.extras，
        // 边界切歌与通知栏切歌都靠它（播放服务进程也能独立读，无需回调 VM）。
        player.planProvider = { t -> splitCarryFor(t) }
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

    /** v1.3.10: 头像 URL 加载失败(典型:Discogs 签名过期返回 403)时的自愈——
     *  清掉该歌手的自动源缓存行并重新解析,换一个可用的新 URL。
     *  此前没有这条路:缓存行永远指向死链,头像永久空白("改几次都没修好"的另一半)。
     *  同一会话同一歌手只自愈一次,源侧若持续给不出可用图也不会反复触网。 */
    private val artistImageHealed = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    fun invalidateArtistImage(name: String, personOnly: Boolean) {
        viewModelScope.launch {
            val canonical = resolveArtist(name)
            if (!artistImageHealed.add(canonical)) return@launch
            android.util.Log.i("AIM", "heal: 清缓存重解析 $canonical (personOnly=$personOnly)")
            artistImageResolver.invalidate(canonical)
            val img = artistImageResolver.resolve(canonical, personOnly = personOnly)
            val url = img?.url ?: ""
            if (url.isNotBlank()) {
                val extra = buildMap {
                    put(name, url)
                    if (canonical != name) put(canonical, url)
                }
                artistImages = artistImages + extra
            }
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
    /** v22: 未做拆分展开的可见物理曲目（文件夹视图用 —— 拆分只是软件层面的划分，
     *  不改变文件性质，所以文件夹里整轨文件仍是 1 条）。与 libCache 同签名同寿命。 */
    private var physicalCache: List<Track> = emptyList()
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
        // v22: 拆分方案必须进签名 —— 少了它，用户拆完专辑后 lib() 会命中旧缓存，
        // 界面"没反应"（新增的拆分单曲不出现）。
        val splitSig = splitSignature()
        return "${tracksRaw.size}|$first|$ignoredSorted|$trashSorted|" +
            "$aliasCount|$aliasValues|$ovCount|$ovValues|" +
            "$infoCount|$infoValues|$trackInfoCount|$trackInfoValues|" +
            "$albumMoveCount|$albumMoveValues|$splitSig"
    }

    // ── v22: 拆分展开 ────────────────────────────────────────────────────────

    /**
     * 源文件 id → 该文件的拆分曲目。主键用 MediaStore id，失效时用 sourcePath 兜底
     * 重解析（重扫 / 换机后 id 必然变，路径通常不变）。
     */
    private fun resolveSplitPlans(): Map<Long, List<com.shiyin.music.data.db.SplitTrackEntity>> {
        if (albumSplits.isEmpty() || splitTracksBySource.isEmpty()) return emptyMap()
        val rawIds = tracksRaw.mapTo(HashSet()) { it.id }
        val byPath = tracksRaw.filter { it.dataPath.isNotEmpty() }.associateBy { it.dataPath }
        val out = HashMap<Long, List<com.shiyin.music.data.db.SplitTrackEntity>>()
        for ((srcId, split) in albumSplits) {
            val rows = splitTracksBySource[srcId] ?: continue
            val effective = if (srcId in rawIds) srcId
                else byPath[split.sourcePath]?.id
                ?: continue
            out[effective] = rows
        }
        return out
    }

    /**
     * 把一个整轨文件展开成 N 首拆分单曲。
     *
     * - 只展开**已定起始时间**的行（草稿行不显示，避免时长未知的幽灵行）。
     * - 时长一律用**分段时长**（endMs - startMs），不是文件总长 —— 否则专辑总时长、
     *   classifyAlbum、行内时长、进度条全错。
     * - sizeBytes 归 0：物理文件只有一个，按文件大小算会让占用统计重复计数。
     * - 曲名取用户录入（唯一来源），绝不被文件内嵌标签覆盖；`track_info_override`
     *   仍可在后面覆盖它（用户在曲目菜单改名照样生效）。
     */
    private fun expandSplitTracks(
        src: Track,
        rows: List<com.shiyin.music.data.db.SplitTrackEntity>,
    ): List<Track> {
        val timed = rows.filter { it.startMs != com.shiyin.music.data.split.SPLIT_START_UNKNOWN }
            .sortedBy { it.ordinal }
        if (timed.isEmpty()) return emptyList()
        val fileEnd = src.durationMs
        return timed.mapIndexed { i, r ->
            val start = r.startMs
            val end = timed.getOrNull(i + 1)?.startMs ?: maxOf(fileEnd, start)
            src.copy(
                id = com.shiyin.music.data.split.SplitIds.trackId(r.id),
                title = r.title,
                trackNo = r.ordinal,
                durationMs = (end - start).coerceAtLeast(0L),
                sizeBytes = 0L,
            )
        }
    }

    /** 「完整专辑」容器曲目 —— 只进 libById（供 trackById 解析），不进 lib()。 */
    private fun containerTrackOf(src: Track): Track = src.copy(
        id = com.shiyin.music.data.split.SplitIds.containerId(src.id),
        title = src.album.takeIf { it.isNotBlank() && it != NO_ALBUM } ?: src.title,
        trackNo = 0,
        sizeBytes = 0L,
    )

    /** 显示层覆盖链（别名 / 专辑覆盖 / 专辑级 / 单曲级）—— 物理、拆分、容器三类曲目共用。 */
    private fun applyDisplayOverrides(t: Track): Track {
        var r = t
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
        // v23: 「完整专辑」容器曲目的标题 = 专辑的**最终显示名**。
        // 容器标题在 containerTrackOf 里取自音频文件的 album 标签,而专辑名常被
        // 手动改名(albumInfoOverrides / albumOverrides)覆盖——只改 album 不改 title
        // 会让播放栏显示文件里的旧标签(实测显示 "Music")。此处按已解析出的 album 回填。
        if (com.shiyin.music.data.split.SplitIds.isContainerId(t.id)) {
            val albumName = r.album.takeIf { it.isNotBlank() && it != NO_ALBUM }
            if (albumName != null) r = r.copy(title = albumName)
        }
        return r
    }

    /** Visible library: not trashed, not in an ignored folder, alias + split artist + album override applied.
     *  v22: 已被拆分的整轨文件在这里被替换成 N 首拆分单曲（物理文件不再单独出现）。 */
    fun lib(): List<Track> {
        val sig = libSignature()
        if (sig == libSig) return libCache
        val trash = trashIds
        val splits = resolveSplitPlans()
        val physical = ArrayList<Track>()
        val expanded = ArrayList<Track>()
        val containers = ArrayList<Track>()
        for (t in tracksRaw) {
            if (t.id in trash || t.folder in ignored) continue
            // v4.3: 迁移专辑 — re-parent the track into its real album FIRST,
            // so every downstream lookup (album-info override, album grouping,
            // cover keying) naturally uses the moved albumId.
            var r = t
            val movedAlbumId = trackAlbumMoves[t.id]?.takeIf { it > 0 && it != t.albumId }
            if (movedAlbumId != null) r = r.copy(albumId = movedAlbumId)
            physical += r
            val rows = splits[t.id]
            if (rows == null) {
                expanded += r
            } else {
                expanded += expandSplitTracks(r, rows)
                containers += containerTrackOf(r)
            }
        }
        libCache = expanded.map(::applyDisplayOverrides)
        physicalCache = physical.map(::applyDisplayOverrides)
        libSig = sig
        // trackById reads this map; rebuild alongside lib() so it stays in sync
        // and lookups stay O(1) (player-screen open, mini-player, queue sheet).
        // v22: 容器曲目也必须能解析 —— onTrackStarted 有 `trackById(id) ?: return`
        // (MainViewModel.kt onTrackStarted)，容器 id 解析不到则整轨播放不计次。
        libById = libCache.associateBy { it.id } +
            containers.map(::applyDisplayOverrides).associateBy { it.id }
        return libCache
    }

    /**
     * v22: 未展开的可见物理曲目 —— 只给「文件夹」视图用。
     * 文件夹是文件视图，拆分是软件层面的划分、不改变文件性质，所以整轨文件在这里
     * 仍然显示为 1 条；歌曲列表 / 专辑页 / 歌手页 / 搜索则走 lib() 展开成 N 条。
     */
    fun physicalTracks(): List<Track> {
        lib()
        return physicalCache
    }

    private var libById: Map<Long, Track> = emptyMap()

    fun trackById(id: Long?): Track? = id?.let { libById[it] }

    /** v22: 是否为一首拆分单曲（合成 id）。 */
    fun isSplitTrack(id: Long): Boolean = com.shiyin.music.data.split.SplitIds.isSplitTrackId(id)

    /** v22: 是否为「完整专辑」容器（合成 id）。 */
    fun isAlbumContainer(id: Long): Boolean = com.shiyin.music.data.split.SplitIds.isContainerId(id)

    // ── v22: 拆分方案 → 播放引擎的桥 ─────────────────────────────────────────

    /**
     * 某个整轨文件的完整拆分方案（按 ordinal 排好、只含已定起始时间的曲目）。
     * endMs 派生：下一首的 startMs；最后一首取文件末尾。
     */
    fun splitPlanOf(sourceMediaId: Long): com.shiyin.music.data.split.SplitPlan? {
        val rows = splitTracksBySource[sourceMediaId]
            ?.filter { it.startMs != com.shiyin.music.data.split.SPLIT_START_UNKNOWN }
            ?.sortedBy { it.ordinal }
            ?: return null
        if (rows.isEmpty()) return null
        val src = tracksRaw.firstOrNull { it.id == sourceMediaId } ?: return null
        val fileEnd = src.durationMs
        val vts = rows.mapIndexed { i, r ->
            val end = rows.getOrNull(i + 1)?.startMs ?: maxOf(fileEnd, r.startMs)
            val vtId = com.shiyin.music.data.split.SplitIds.trackId(r.id)
            // 曲名/歌手取 lib() 的最终展示值（含 track_info_override 的改名），
            // 使通知栏/锁屏标题与 App 内一致。
            val shown = trackById(vtId)
            com.shiyin.music.data.split.VirtualTrack(
                id = vtId,
                startMs = r.startMs,
                endMs = end,
                title = shown?.title ?: r.title,
                artist = shown?.artist ?: src.artist,
            )
        }
        return com.shiyin.music.data.split.SplitPlan(
            containerId = com.shiyin.music.data.split.SplitIds.containerId(sourceMediaId),
            sourceMediaId = sourceMediaId,
            tracks = vts,
        )
    }

    /** 合成 mediaId → 该曲目所属方案 + 它在方案里的下标（普通曲目/容器返回 null）。 */
    private fun splitCarryFor(t: Track): com.shiyin.music.playback.PlanCarry? {
        if (!isSplitTrack(t.id)) return null
        val rowId = t.id - com.shiyin.music.data.split.SplitIds.TRACK_BASE
        val row = splitTracks.firstOrNull { it.id == rowId } ?: return null
        val plan = splitPlanOf(row.sourceMediaId) ?: return null
        val idx = plan.tracks.indexOfFirst { it.id == t.id }
        if (idx < 0) return null
        return com.shiyin.music.playback.PlanCarry(plan, idx)
    }

    /**
     * v22: 逻辑曲目列表 → 引擎队列。
     *
     * 同一个整轨文件的**相邻且下标连续**的拆分曲目合并成一个条目（uri 指向整轨文件、
     * mediaId 取代表那首的合成 id、方案随 MediaItem.extras 走）。这样：
     *  - 专辑整张 = 1 个条目 → 边界处零引擎动作，真正无缝（需求四）；
     *  - 整库/歌单/歌手队列里相邻的拆分曲目同样合并 → 播到文件末尾后自然接下一首别的曲目；
     *  - 不相邻（歌单里同时有第 1 首和第 5 首）→ 各自成条目，各带方案，切条目时才发生
     *    真正的引擎切换（那本来就不是同一段连续音频）。
     *
     * [startId] 被点的那首若在某段里，就用它当该段的代表 —— 它前面的成员本来也排在
     * 它前面、不会被播到。
     *
     * 合并规则本身在 [com.shiyin.music.data.split.SplitRunCollapse]（纯逻辑、可单测）；
     * 这里只负责把曲目翻译成「方案位置」。
     */
    private fun collapseSplitRuns(tracks: List<Track>, startId: Long?): List<Track> {
        if (albumSplits.isEmpty() || splitTracks.isEmpty()) return tracks
        val slots = tracks.map { splitSlotOf(it) }
        val tapped = if (startId == null) -1 else tracks.indexOfFirst { it.id == startId }
        return com.shiyin.music.data.split.SplitRunCollapse
            .representativeIndices(slots, tapped)
            .map { tracks[it] }
    }

    /**
     * v22: 曲目在拆分方案里的位置（普通曲目返回 null）。
     *
     * 与 [splitCarryFor] 的区别：这里**不构造完整的 VirtualTrack 列表**（队列压缩只需要
     * 「哪个源文件 + 第几首」两个数），避免在整库队列上做无谓的分配。
     */
    private fun splitSlotOf(t: Track): com.shiyin.music.data.split.SplitRunCollapse.Slot? {
        if (!isSplitTrack(t.id)) return null
        val rowId = t.id - com.shiyin.music.data.split.SplitIds.TRACK_BASE
        val row = splitTracks.firstOrNull { it.id == rowId } ?: return null
        val ordered = splitTracksBySource[row.sourceMediaId]
            ?.filter { it.startMs != com.shiyin.music.data.split.SPLIT_START_UNKNOWN }
            ?.sortedBy { it.ordinal }
            ?: return null
        val idx = ordered.indexOfFirst { it.id == rowId }
        if (idx < 0) return null
        return com.shiyin.music.data.split.SplitRunCollapse.Slot(row.sourceMediaId, idx)
    }

    // ── v22: 拆分录入 —— 两步流程的 ViewModel 侧 ────────────────────────────

    /**
     * 打开拆分编辑器。已有方案 → 直接进第二步继续编辑；否则从粘贴列表开始。
     *
     * 编辑器期间用**容器模式**播放整轨文件（队列只放容器），这样：
     *  - 进度条覆盖的是文件总时长，用户能拖到任意位置试听、打点；
     *  - 不会因为内部区间边界触发任何单曲切换，打点时统计干干净净。
     */
    fun openSplitEditor(sourceMediaId: Long) {
        val existing = splitTracksBySource[sourceMediaId]?.sortedBy { it.ordinal }.orEmpty()
        splitRows = existing.map {
            com.shiyin.music.data.split.SplitListParser.Row(
                ordinal = it.ordinal,
                title = it.title,
                startMs = it.startMs,
                hasTimestamp = it.startMs != com.shiyin.music.data.split.SPLIT_START_UNKNOWN,
            )
        }
        splitConflicts = com.shiyin.music.data.split.SplitListParser.conflictsOf(splitRows)
        splitPasteText = ""
        splitPasteError = ""
        splitEditStep = if (existing.isNotEmpty()) 1 else 0
        splitEditFor = sourceMediaId
        // v22 修补: 首次拆分(专辑还没有 split 方案)时容器曲目**从未进 libById**——
        // lib() 里 containers 只为已有拆分方案的文件构建。trackById 解析不到 →
        // loadQueue 从未被调用 → 编辑页播放键 toggle 的是空队列(静默无响应,
        // 用户反馈"全长自己无法点击播放"的根因)。这里按需构建容器并补进 libById;
        // 保存后 split 方案存在,lib() 重建时容器自然进入。
        val container = trackById(com.shiyin.music.data.split.SplitIds.containerId(sourceMediaId))
            ?: physicalTracks().firstOrNull { it.id == sourceMediaId }?.let { src ->
                val c0 = containerTrackOf(src)
                libById = libById + (c0.id to applyDisplayOverrides(c0))
                c0
            }
        container?.let {
            player.setShuffle(false)
            // v22 修补:只装载队列**不自动播放**(用户反馈——进编辑页就自己响起来,
            // 打断了"先看一眼曲目表"的预期)。让用户自己点播放键;加载停在暂停态,
            // 进度条/时长/插旗照常可读。
            player.loadQueue(listOf(it), it.id, null)
        }
    }

    /** 第一步 → 解析粘贴文本，成功后进第二步。 */
    fun splitParse() {
        val r = com.shiyin.music.data.split.SplitListParser.parse(splitPasteText)
        splitRows = r.rows
        splitConflicts = r.conflicts
        splitPasteError = when {
            r.rows.isEmpty() -> "没有解析到任何曲目，请确认每行都含曲名"
            !r.canSplit -> "至少需要 2 首才能拆分"
            else -> ""
        }
        if (r.rows.isNotEmpty() && r.canSplit) splitEditStep = 1
    }

    fun splitBackToPaste() {
        splitEditStep = 0
        splitPasteText = splitRows.joinToString("\n") { r ->
            if (r.startMs == com.shiyin.music.data.split.SPLIT_START_UNKNOWN) "${r.ordinal}. ${r.title}"
            else "${r.ordinal}. ${com.shiyin.music.data.split.SplitListParser.format(r.startMs)} ${r.title}"
        }
    }

    /**
     * 设置/覆盖某行的起始时间（null = 清回未定）。
     *
     * 与歌词打轴的 `syncLyricLine` 同一范式：**再点即覆盖更新**，不做二次确认。
     * endMs 不需要跟着改 —— 它是派生的（= 下一首的 startMs）。
     */
    fun splitSetStart(ordinal: Int, ms: Long?) {
        val i = splitRows.indexOfFirst { it.ordinal == ordinal }
        if (i < 0) return
        val rows = splitRows.toMutableList()
        rows[i] = rows[i].copy(
            startMs = ms ?: com.shiyin.music.data.split.SPLIT_START_UNKNOWN,
            hasTimestamp = ms != null,
        )
        splitRows = rows
        splitConflicts = com.shiyin.music.data.split.SplitListParser.conflictsOf(rows)
    }

    /** 「设为当前时间」：把进度条当前播放位置赋给该行（歌词打轴的 Clock 按钮同款）。 */
    fun splitSetStartNow(ordinal: Int) {
        splitSetStart(ordinal, player.positionMs)
    }

    /** 第一首固定 0:00:00，UI 上禁掉它的编辑入口。 */
    fun splitRowLocked(ordinal: Int): Boolean = ordinal == 1

    /**
     * v22 修补: 编辑页补录——漏粘贴的曲目在这里追加一行(用户反馈:50 分钟专辑漏了
     * 后面几首,便导致后面不知道绑定哪些歌曲,应提供加号键)。
     * 新行序号 = 当前最大序号 + 1,起始时间未定(留给「设为当前时间」补)。
     */
    fun splitAppendRow() {
        val nextOrdinal = (splitRows.maxOfOrNull { it.ordinal } ?: 0) + 1
        splitRows = splitRows + com.shiyin.music.data.split.SplitListParser.Row(
            ordinal = nextOrdinal,
            title = "",
            startMs = com.shiyin.music.data.split.SPLIT_START_UNKNOWN,
            hasTimestamp = false,
        )
        // 空标题行允许出现(用户马上要填);冲突检测照常重算。
        splitConflicts = com.shiyin.music.data.split.SplitListParser.conflictsOf(splitRows)
    }

    /** v22 修补: 编辑页删行(补录加错了/不要某行)。 */
    fun splitRemoveRow(ordinal: Int) {
        val rows = splitRows.filter { it.ordinal != ordinal }
        splitRows = rows
        splitConflicts = com.shiyin.music.data.split.SplitListParser.conflictsOf(rows)
    }

    /**
     * v22 修补: 编辑页改曲名(补录行的空标题在这里填,已有行也可改名)。
     * 与 splitSetStart 同一"再点即生效"范式,不做二次确认。
     */
    fun splitRenameRow(ordinal: Int, title: String) {
        val i = splitRows.indexOfFirst { it.ordinal == ordinal }
        if (i < 0) return
        val rows = splitRows.toMutableList()
        rows[i] = rows[i].copy(title = title.trim())
        splitRows = rows
    }

    /**
     * v22 修补: 编辑页排序——把 [from] 行移到 [to] 位置(拖拽/上下移按钮共用)。
     * 序号跟随位置重排(1..n 连续),起始时间**跟着行走**(用户以编辑页顺序为准)。
     * 保存时 entities 的 ordinal = i+1 按位置重建,所以这里只需保证 rows 顺序正确。
     */
    fun splitMoveRow(fromOrdinal: Int, toOrdinal: Int) {
        val fromIdx = splitRows.indexOfFirst { it.ordinal == fromOrdinal }
        val toIdx = splitRows.indexOfFirst { it.ordinal == toOrdinal }
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return
        val rows = splitRows.toMutableList()
        val item = rows.removeAt(fromIdx)
        rows.add(toIdx, item)
        splitRows = rows.mapIndexed { i, r -> r.copy(ordinal = i + 1) }
        splitConflicts = com.shiyin.music.data.split.SplitListParser.conflictsOf(splitRows)
    }

    fun splitCancel() {
        splitEditFor = null
        splitEditStep = 0
        splitRows = emptyList()
        splitConflicts = emptySet()
        splitPasteError = ""
    }

    /**
     * 保存拆分方案。
     *
     * [done] = true 表示用户点了「完成」（允许存在未定时间的行 —— 那些行按草稿处理、
     * 不显示在专辑页）；false 表示「保存草稿」，随时可以回来继续编辑（歌词打轴的
     * "允许中途保存进度"先例）。
     */
    fun splitSave(done: Boolean) {
        val src = splitEditFor ?: return
        val rows = splitRows
        if (rows.size < 2) { showToast("至少需要 2 首才能拆分", null); return }
        if (rows.none { it.startMs != com.shiyin.music.data.split.SPLIT_START_UNKNOWN }) {
            showToast("还没有任何曲目设置起始时间", null); return
        }
        // v22 修补: 空标题行(补录还没来得及填的)自动命名「未命名」——
        // 否则专辑页出现空名幽灵行。
        val named = rows.map { if (it.title.isBlank()) it.copy(title = "未命名") else it }
        val path = tracksRaw.firstOrNull { it.id == src }?.dataPath ?: ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.withTransaction {
                    // 保留已有行的自增 id —— 对外合成 mediaId = TRACK_BASE + id，
                    // 收藏 / 歌单 / 歌词 / 隐藏都按它存，id 一变这些引用全断。
                    val existing = dao.splitTracksFor(src).associateBy { it.ordinal }
                    val now = System.currentTimeMillis()
                    val entities = named.mapIndexed { i, r ->
                        val ord = i + 1
                        com.shiyin.music.data.db.SplitTrackEntity(
                            id = existing[ord]?.id ?: 0L,
                            sourceMediaId = src,
                            ordinal = ord,
                            title = r.title,
                            startMs = r.startMs,
                            updatedAt = now,
                        )
                    }
                    dao.deleteSplitTracksFor(src)
                    dao.insertSplitTracks(entities)
                    val prev = dao.albumSplit(src)
                    dao.upsertAlbumSplit(
                        com.shiyin.music.data.db.AlbumSplitEntity(
                            sourceMediaId = src,
                            sourcePath = path,
                            status = if (done) com.shiyin.music.data.db.AlbumSplitEntity.STATUS_DONE
                            else com.shiyin.music.data.db.AlbumSplitEntity.STATUS_DRAFT,
                            createdAt = prev?.createdAt ?: now,
                            updatedAt = now,
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    showToast(
                        if (done) "拆分完成，已按 ${named.count { it.startMs != com.shiyin.music.data.split.SPLIT_START_UNKNOWN }} 首生成单曲"
                        else "已保存草稿，可随时继续编辑",
                        null,
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showToast("保存失败：${e.message}", null) }
            }
        }
        splitCancel()
    }

    /**
     * 取消拆分（合并回整轨）。
     *
     * 顺带清理对已失效合成 id 的引用 —— 否则歌单/收藏里会留下点不响的幽灵项。
     */
    fun removeSplit(sourceMediaId: Long) {
        val staleIds = splitTracksBySource[sourceMediaId].orEmpty().map {
            com.shiyin.music.data.split.SplitIds.trackId(it.id)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.withTransaction {
                    dao.deleteSplitTracksFor(sourceMediaId)
                    dao.deleteAlbumSplit(sourceMediaId)
                    for (id in staleIds) {
                        dao.removeTrackFromAllPlaylists(id)
                        dao.deleteTrackInfoOverride(id)
                    }
                }
                withContext(Dispatchers.Main) { showToast("已取消拆分，恢复为整轨文件", null) }
            } catch (_: Exception) { }
        }
    }

    // ── v22: 专辑页的拆分入口判定 ────────────────────────────────────────────

    /** 该专辑是否已拆分（曲目里有合成 id 的拆分单曲）。 */
    fun albumIsSplit(key: String): Boolean = albumOrder(key).any { isSplitTrack(it.id) }

    /** 该专辑对应的整轨文件 mediaId（未拆分时=唯一那首；已拆分时=源文件）。 */
    fun splitSourceOfAlbum(key: String): Long? {
        val ts = albumOrder(key)
        if (ts.isEmpty()) return null
        ts.firstOrNull { isSplitTrack(it.id) }?.let { t ->
            val rowId = t.id - com.shiyin.music.data.split.SplitIds.TRACK_BASE
            return splitTracks.firstOrNull { it.id == rowId }?.sourceMediaId
        }
        return ts.singleOrNull()?.id
    }

    /**
     * 是否显示「专辑整轨拆分为单曲」入口。
     *
     * 条件（用户定调）：**专辑类型为「单曲」即可，不设时长门槛** —— 8 分钟的串烧、
     * 45 分钟的全长整轨专辑都能用（后者因为只有 1 个文件，本身就被分类为单曲）。
     * `classifyAlbum` 是手动覆盖优先，用户若把单文件专辑手动标成「专辑」会被误伤，
     * 因此再用**曲目数 == 1** 兜底。
     */
    fun canSplitAlbum(key: String): Boolean {
        val ts = albumOrder(key)
        if (ts.isEmpty()) return false
        if (albumIsSplit(key)) return true
        return ts.size == 1 ||
            com.shiyin.music.ui.screens.classifyAlbum(ts, null) == com.shiyin.music.ui.screens.AlbumCategory.Single
    }

    /** 该专辑的拆分方案是否还是草稿（有未定时间点的曲目）。 */
    fun albumSplitIsDraft(key: String): Boolean {
        val src = splitSourceOfAlbum(key) ?: return false
        return albumSplits[src]?.status == com.shiyin.music.data.db.AlbumSplitEntity.STATUS_DRAFT
    }

    fun playCountFor(id: Long): Int = playCounts[id] ?: 0

    /** v22: 「完整专辑」被播放了多少次（专辑级口径，与单曲口径互不干扰）。 */
    fun albumPlayCountFor(containerId: Long): Int = albumPlayCounts[containerId] ?: 0

    /**
     * v22: 是否「专辑过渡曲」—— intro / outro / interlude 这类 ≤60 秒的短曲。
     *
     * 用户定调：这类曲目**要计数**（[playCountFor] 照常返回真实次数），但
     * **不作为热度评判标准** —— 它某种程度上不算「歌」，而是专辑的过渡。
     *
     * 判定纯派生（时长即可），不落库：用户改了拆分时间点后不会出现标记不同步。
     */
    fun isInterlude(t: Track): Boolean =
        com.shiyin.music.data.split.isInterludeDuration(t.durationSec)

    /**
     * v22: **热度口径**的播放次数 —— 过渡曲恒返回 0。
     *
     * 所有"按播放次数排序 / 上榜单"的地方都必须用它，而不是 [playCountFor]：
     * 收听统计「最常听单曲」、Agent 搜歌/搜歌手排序、歌手页曲目排序、搜索结果排序。
     * 展示"总共播了多少次"这种纯数字仍可用 [playCountFor]（含过渡曲）。
     */
    fun heatCountFor(id: Long): Int {
        val t = trackById(id) ?: return playCountFor(id)
        return if (isInterlude(t)) 0 else playCountFor(id)
    }

    /**
     * v1.3.8: 本周收听统计。
     *
     * 计算口径在 data/stats/ListeningStats.kt —— **统计页与 Agent 的 listening_stats
     * 技能共用同一实现**,不是各算一份。口径统一保护的不只是算法一致,还有"用户在统计页
     * 与 Agent 两个入口得到的答案必须是同一件事"。
     */
    suspend fun weeklyListeningStats(): com.shiyin.music.data.stats.WeeklyStats =
        withContext(Dispatchers.IO) {
            val events = dao.completedPlayEventsBetween(
                com.shiyin.music.data.stats.currentWeekStartMillis(),
                System.currentTimeMillis(),
            )
            com.shiyin.music.data.stats.computeWeeklyStats(
                events = events,
                lib = lib(),
                trackById = { trackById(it) },
                isInterlude = { isInterlude(it) },
                albumTypeFor = { albumTypeFor(it) },
            )
        }

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
        // v22: 文件夹是**文件视图** —— 走未展开的物理曲目，整轨文件仍显示为 1 条。
        // 拆出来的 N 首只在 歌曲列表 / 专辑页 / 歌手页 / 搜索 里出现（见 lib()）。
        val m = LinkedHashMap<String, MutableList<Track>>()
        for (t in physicalTracks()) m.getOrPut(t.folder) { mutableListOf() }.add(t)
        return m
    }

    /**
     * Rebuilds the service-side queue respecting the current queue kind:
     * album queues stay album-scoped, normal queues track the sorted library.
     */
    private fun resyncQueue() {
        val key = player.queueKey
        val cur = player.currentId
        if (key != null && key.startsWith("album:")) {
            val ts = albumOrder(key.removePrefix("album:"))
            if (ts.isEmpty() || cur == null || ts.none { it.id == cur }) {
                if (player.currentId != null && trackById(player.currentId) == null) {
                    player.stopAndClear()
                    playerOpen = false
                }
                return
            }
            player.syncQueue(collapseSplitRuns(ts, cur))
        } else if (key != null && key.startsWith("artisthot:")) {
            // v1.3.10 需求 A15:热门队列按**热度序**重建,与歌手页「热门」列表保持同一顺序。
            // (key 前缀刻意与 artist: 区分——"artisthot:" 不以 "artist:" 开头,不会误匹配。)
            val ts = artistQueue(key.removePrefix("artisthot:")).sortedByDescending { heatCountFor(it.id) }
            if (ts.isEmpty() || cur == null || ts.none { it.id == cur }) {
                if (player.currentId != null && trackById(player.currentId) == null) {
                    player.stopAndClear()
                    playerOpen = false
                }
                return
            }
            player.syncQueue(collapseSplitRuns(ts, cur))
        } else if (key != null && key.startsWith("artist:")) {
            // v1.3.2: 歌手队列保持歌手范围,与 album: 同样不被同步成全库——
            // 旧逻辑 artist: 队列落进 else 分支,库一变队列就被替换成 sortedSongs()(全库)。
            val ts = artistQueue(key.removePrefix("artist:"))
            if (ts.isEmpty() || cur == null || ts.none { it.id == cur }) {
                if (player.currentId != null && trackById(player.currentId) == null) {
                    player.stopAndClear()
                    playerOpen = false
                }
                return
            }
            player.syncQueue(collapseSplitRuns(ts, cur))
        } else {
            player.syncQueue(collapseSplitRuns(sortedSongs(), cur))
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
     *  review#2: state 写入切主线程,DB 写留 IO。
     *  @param invalidateArtistOrder **用户手改**日期时传 true(默认):日期变了就把该歌手的
     *  手动顺序作废、回落到按日期排。AI 按时间排序那条路传 false —— 它自己会在最后重写整份
     *  顺序,中途作废只会让"AI 跑到一半失败"把用户原有的拖拽顺序也清掉。 */
    fun saveAlbumReleaseDate(albumId: Long, iso: String, invalidateArtistOrder: Boolean = true) {
        // v1.3.5: iso 允许空串=清除日期(编辑弹窗把年/月/日滑到 0 保存 → 清除);
        // albumId<=0 仍然忽略。空串也走缓存+落库,专辑页/歌手栏随即不再显示日期。
        if (albumId <= 0) return
        viewModelScope.launch(Dispatchers.Main) {
            albumDateCache = if (iso.isBlank()) albumDateCache - albumId else albumDateCache + (albumId to iso)
            albumDateRevision++
        }
        viewModelScope.launch(Dispatchers.IO) {
            val prev = runCatching { dao.albumArtCache(albumId)?.releaseDate }.getOrNull()
            try { dao.updateAlbumReleaseDate(albumId, iso) } catch (_: Exception) { }
            // v1.3.15:日期真的变了 → 该歌手的手动专辑顺序**作废**。
            // 真机反馈:「1999 被识别成 2022,排在 1997、2002 前面;改成 1999 后还是排在最前」——
            // 根因是歌手栏的优先级"手动顺序 > 日期",而 AI 按时间排序写下的那份顺序仍压着日期,
            // 于是手工修正永远不生效。作废后回落到按日期排,修正立刻可见。
            if (!invalidateArtistOrder) return@launch
            if (!com.shiyin.music.data.AlbumOrderRules.dateChanged(prev, iso)) return@launch
            val artists = lib().filter { it.albumId == albumId }
                .flatMap { com.shiyin.music.data.MediaScanner.splitArtists(it.artist) }
                .filter { it.isNotBlank() }.distinct()
            if (artists.isEmpty()) return@launch
            android.util.Log.i("AlbumOrder", "日期 $prev → $iso,作废手动顺序:$artists")
            artists.forEach { a -> runCatching { dao.deleteArtistAlbumOrder(a) } }
            val next = com.shiyin.music.data.AlbumOrderRules.invalidate(artistAlbumOrders, artists)
            withContext(Dispatchers.Main) { artistAlbumOrders = next }
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
    /** v1.3.8: 对话历史页(Agent 页右上"历史"入口)。 */
    var agentHistoryOpen by mutableStateOf(false)
    /** 对话消息流(用户消息 + Agent 消息,含步骤面板 + tokens)。 */
    var agentMessages by mutableStateOf<List<AgentMessage>>(emptyList()); private set
    var agentRunning by mutableStateOf(false); private set
    /** 本次最近一次 LLM 调用的 tokens(供设置页/对话显示)。 */
    var agentLastTokens by mutableStateOf<Pair<Int, Int>>(0 to 0); private set
    /** 累计 prompt/completion tokens(从 token_usage_log 表读)。 */
    var agentTotalTokens by mutableStateOf<Pair<Long, Long>>(0L to 0L); private set
    /** v1.3.2: 默认回复建议(专辑页 Agent 入口带入,Agent 页点一条即发送)。 */
    var agentSuggestions by mutableStateOf<List<String>>(emptyList()); private set

    // ── v1.3.13: 空态欢迎语的会话状态(只活在进程内——进程即"会话") ──────────
    // 放 VM 而不是 rememberSaveable:切 tab 会离开组合,而 AppRoot 下没有
    // SaveableStateHolder,rememberSaveable 撑不住"切回来不重放"。
    /** 本会话已经播过打字机欢迎语。 */
    var agentGreetingPlayed by mutableStateOf(false)
    /** 本会话选中的欢迎语序号(进空态时随机一次,会话内固定;跨会话才有新鲜感)。 */
    var agentGreetingIndex by mutableStateOf(-1)

    /**
     * v1.3.10: 模型发起的「选择题」(clarify)的**独立状态**——非空时输入框上方显示专属卡片。
     *
     * 刻意与 [agentSuggestions] 分开(用户 2026-09-28 定调):
     * - [agentSuggestions] = **专辑页入口建议**(产品预设文案,进来就摆着);
     * - [agentClarify] = **模型现场生成**的选择题(问题+选项都来自当前对话)。
     * 两者语义、生命周期、样式都不同;此前共用一个状态,正是"clarify 的选项看起来像
     * 气泡里的小胶囊"的根源。现在各走各的渲染路径,互不影响。
     */
    var agentClarify by mutableStateOf<com.shiyin.music.data.ai.AgentTools.ClarifyRequest?>(null)
        private set

    // ── v1.3.10 需求 A21:长期记忆(P2 = 接线) ────────────────────────────────
    /**
     * 长期记忆管理器——**访问记忆的唯一入口**(Agent 层不碰数据库)。
     * 写入裁决(类型白名单/长度上限/一次性情绪拒表/去重/冲突取代)与检索(相关度门槛 + top-3)
     * 全在 [com.shiyin.music.data.memory.MemoryRules] / [MemoryManager](纯逻辑,32 条单测)。
     */
    private val memoryManager = com.shiyin.music.data.memory.MemoryManager(
        com.shiyin.music.data.memory.RoomMemoryStore(dao),
    )

    // ── v1.3.11:本回合的记忆运行状态(可观测性,UI runtime state) ─────────────
    /**
     * 当前回合"记忆这件事"走到哪一步了 —— UI **只渲染这一条**。
     *
     * v1.3.11 第二阶段修正:上一版把"保存"和"检索"当成两个独立状态各自成行,于是同一轮会
     * 并列出现「🧠 记忆已保存」「🧠 已找到记忆」——那是"事件列表",不是"这一轮的状态"。
     * 现在一轮只有一条,相位在同一条上更新(结构性保证见 [MemoryTraceFlow.uiRows])。
     *
     * 为什么是 VM 级状态、不挂在 AgentMessage 上:回合内每一次思考/打字机/步骤回调都会
     * **重建** AgentMessage,挂在消息上的状态会被反复抹掉;而它本来就不是某个气泡的属性。
     * 它也不进 messagesJson(不永久占据聊天记录),新一轮开始时清空。
     */
    var agentMemoryTrace by mutableStateOf<com.shiyin.music.data.memory.MemoryTrace?>(null)
        private set

    /** 本轮的状态机:相位推进全部由真实执行结果驱动,模型既不产生也影响不了它。 */
    private val agentMemoryFlow = com.shiyin.music.data.memory.MemoryTraceFlow()

    // ── v1.3.15:推荐槽位(上一轮"他让你挑的什么")─────────────────────────────
    /**
     * 上一轮 [com.shiyin.music.data.ai.skills.DiscoverMusicSkill] 的槽位(场景/歌手/专辑)。
     *
     * 为什么要有它:真机里「推荐方大同适合睡觉的歌」→ 用户补一句「方大同的」时,弱模型
     * 常常把场景丢掉,于是推荐变成"按热度排"。这里存住合并后的槽位,下轮既注入提示
     * (让模型自己接得上)又在技能里兜底继承(代码保底)。
     *
     * **只在内存**:不落库、不进 messagesJson、**只在当前会话内有效**(换对话/清屏即清空)。
     */
    private var agentRecIntent: com.shiyin.music.data.ai.RecIntent? = null

    /** 用户回合序号(推荐槽位的 TTL 按**回合**算,与按时间算的那条独立过期)。 */
    private var agentTurnIndex = 0

    /** 本轮用户原话(推荐技能要在原话里兜底扫库内歌手名,见 resolveArtists)。 */
    private var agentTurnUserText = ""

    /**
     * v1.3.15:工具调用轨迹(「导出对话」用)。
     *
     * 为什么另记一份:技能调用与参数只打在 logcat 里,而真机 release 包的 logcat 被系统抑制
     * (用户实测自定义 tag 打不出来)——深度排查时**看不到模型到底调了什么、带了什么参数、返回了什么**。
     * 跨轮保留(不每轮清空),只留最近 [toolTrailMax] 行,避免长会话无限增长。
     */
    private val agentToolTrail = ArrayDeque<String>()
    private val toolTrailMax = 60

    private fun trailAdd(line: String) {
        agentToolTrail.addLast(line)
        while (agentToolTrail.size > toolTrailMax) agentToolTrail.removeFirst()
    }

    /** v1.3.15:「导出对话」写到外部私有目录后的绝对路径(null=未导出/失败)。 */
    var agentDumpPath by mutableStateOf<String?>(null)
        private set

    /**
     * v1.3.15:导出本次会话现场(文字 + 思考链 + 工具调用 + 诊断样本)到
     * `Android/data/<pkg>/files/agent_dump/chat_<ts>.json`,返回路径供 App 内复制、adb pull 读取。
     *
     * 为什么需要:设备上装的是 release 包(非 debuggable)——Room 库、内部存储、logcat 都取不到,
     * 而"模型看了什么提示、回了什么、被解析成什么"这类深度调查必须有完整现场。[AgentChatDump]
     * 负责脱敏与构造(纯函数,可脱机单测)。
     */
    fun exportAgentChatDump() {
        val ctx = getApplication<android.app.Application>()
        val msgs = agentMessages.map { m ->
            com.shiyin.music.data.log.AgentChatDump.Msg(
                role = m.role,
                text = m.text,
                thinking = m.thinking,
                steps = m.steps?.map { "${it.label}:${it.status}" }.orEmpty(),
                tokens = m.tokens,
            )
        }
        val active = llmProviders.firstOrNull { it.key == llmActiveProvider }
        val meta = com.shiyin.music.data.log.AgentChatDump.Meta(
            appVersion = currentVersion,
            device = android.os.Build.MODEL ?: "?",
            android = android.os.Build.VERSION.SDK_INT.toString(),
            provider = active?.displayName ?: llmActiveProvider,
            model = active?.model.orEmpty(),
            skills = com.shiyin.music.data.ai.AgentEngine.skills.map { it.key },
            diagEnabled = agentDiagEnabled,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val ts = System.currentTimeMillis()
            val text = com.shiyin.music.data.log.AgentChatDump.build(
                messages = msgs,
                toolTrail = agentToolTrail.toList(),
                samples = com.shiyin.music.data.log.AgentDiag.readAll(ctx),
                meta = meta,
                tsMs = ts,
            )
            val f = com.shiyin.music.data.log.AgentChatDump.write(ctx, text, ts)
            withContext(Dispatchers.Main) { agentDumpPath = f?.absolutePath }
        }
    }

    /** 把状态机的当前相位同步给 UI。 */
    private fun publishMemory() {
        agentMemoryTrace = agentMemoryFlow.current?.takeIf {
            it.phase != com.shiyin.music.data.memory.MemoryTrace.Phase.IDLE
        }
    }

    // ── v1.3.12:最近一次执行型动作(轻量 runtime 状态,不落库、不进 messagesJson) ──
    /**
     * 上一轮"真的做了什么"。用途见 [buildAgentContext] —— 弱模型读一句结构化
     * 「[上一轮动作] 播放:Pretender · 已成功」比读一大段聊天记录更容易理解
     * "这事已经做完了",从而不会在下一轮重复执行。
     */
    private data class AgentAction(val kind: String, val detail: String, val success: Boolean)
    private var lastAgentAction: AgentAction? = null

    /**
     * 技能 key → 用户能懂的动作名。
     *
     * **刻意不把裸技能 key 写进上下文**:弱模型会把提示词里的机器词原样念给用户
     * (项目已有实测教训——它连 track_id 都念出来过)。所以这里只给中文动作名。
     */
    private fun humanActionLabel(skillKey: String): String = when (skillKey) {
        "playback_control", "play_track" -> "播放"
        "discover_music" -> "挑歌播放"
        "playback_mode" -> "播放模式"
        "sleep_timer" -> "睡眠定时"
        "library_query" -> "查音乐库"
        "lyrics_fetch", "save_lyrics" -> "找歌词"
        "album_sort" -> "整理专辑曲序"
        "artist_fix", "track_rename" -> "修改曲目信息"
        "album_dates" -> "补全专辑日期"
        "listening_stats" -> "查收听统计"
        else -> "操作"
    }

    /** mm:ss(给模型看的播放进度)。 */
    private fun fmtProgress(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    /**
     * v1.3.12:Agent 的动态上下文(领域事实 + 播放状态 + 上一轮动作)。
     *
     * 为什么要把播放状态和上一轮动作都摆进来:真机 bug 的根因之一是**模型只能猜**——
     * 它看不到"Pretender 正在播放",也看不到"刚才那次播放已经成功",于是把用户的感想
     * 读成"再放一遍"。这里把两者都做成明确事实,再配一条"已完成,别重复"的指令。
     *
     * v1.3.13 修正(用户实测):播放状态/上一轮动作**只在用户这条消息确实与播放相关时**
     * 才注入。闲聊、评价别的歌手、问与当前歌无关的问题,一律不带——模型从没问过"现在
     * 在放什么",这条信息就不该出现在语境里(否则"正在听 A"会被它错接成"B 的答案")。
     */
    private fun buildAgentContext(userText: String): String = buildString {
        albumKey?.let { k -> albumOrder(k).firstOrNull()?.album }?.let { appendLine("当前专辑: $it") }
        // 关键:只有用户消息确实涉及播放(指令/控制/重来/纠正)时,才把"正在播放"与
        // "上一轮动作"告诉模型。否则闲聊带进来就是污染源。
        val playbackRelated = com.shiyin.music.data.ai.PlaybackIntentGuard.classify(userText) !=
            com.shiyin.music.data.ai.PlaybackIntentGuard.Intent.NONE
        // 当前歌手也分:专辑页/歌手页的"当前歌手"是页面语境,可以带;
        // 但"由正在播放的曲目推断出的歌手"只在播放相关时才带(否则"听着 A 问 B"会串台)。
        (artistKey ?: if (playbackRelated) player.currentId?.let { trackById(it)?.artist } else null)
            ?.let { appendLine("当前歌手: $it") }
        if (playbackRelated) {
            player.currentId?.let { id ->
                trackById(id)?.let { t ->
                    appendLine("当前播放曲目: ${t.title}")
                    appendLine(
                        "[当前播放] ${t.title} — ${t.artist} · " +
                            (if (player.isPlaying) "正在播放" else "已暂停") +
                            " · 进度 ${fmtProgress(player.positionMs)}",
                    )
                }
            }
            lastAgentAction?.let {
                appendLine(
                    "[上一轮动作] ${it.kind}:${it.detail.ifBlank { "—" }} · " +
                        (if (it.success) "已成功" else "失败") +
                        " —— **已完成,除非用户本轮明确要求,不要重复执行**",
                )
            }
        }
        // ── v1.3.15:上一轮的推荐槽位 ─────────────────────────────────────────
        // 真机缺陷:「推荐方大同适合睡觉的歌」→ 用户补一句「方大同的」,弱模型把"睡觉"丢了,
        // 于是推荐退化成按热度排。这里把上一轮**合并后**的槽位贴给模型,让它自己接得上;
        // 技能里还有一层代码兜底继承(见 RecommendationPlan.mergeIntent),两层用同一 TTL。
        val rec = agentRecIntent
        if (com.shiyin.music.data.ai.RecommendationPlan.isIntentValid(
                rec, agentTurnIndex, System.currentTimeMillis(),
            )
        ) {
            val slots = listOfNotNull(
                rec?.scene?.let { "场景「$it」" },
                rec?.artist?.let { "歌手「$it」" },
                rec?.album?.let { "专辑「$it」" },
            )
            if (slots.isNotEmpty()) {
                appendLine(
                    "[上一轮的推荐] 他上一轮让你挑的是 ${slots.joinToString("、")}。" +
                        "这一轮若只是就同一件事做补充(例如只说「方大同的」),按**同一个场景**继续;" +
                        "若是换了新话题,当这段不存在。",
                )
            }
        }
    }.trimEnd()

    // ── v1.3.10 需求 P3:「设置→拾光记忆」管理页 ──────────────────────────────
    /** 当前有效记忆(管理页展示;按 updatedAt 倒序)。 */
    var memories by mutableStateOf<List<com.shiyin.music.data.memory.Memory>>(emptyList())
        private set
    /** 管理页是否已加载过(首次进入才查库,避免每次进设置都读一遍)。 */
    private var memoriesLoaded = false

    /** 重新读记忆列表(进入管理页时调;增/删后也调)。 */
    fun refreshMemories() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { memoryManager.list() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                memories = list
                memoriesLoaded = true
            }
        }
    }

    /** 管理页进入时按需加载(已加载过就直接显示缓存,不闪)。 */
    fun ensureMemoriesLoaded() {
        if (!memoriesLoaded) refreshMemories()
    }

    /** 用户要求拾光忘记某条(硬删除)。 */
    fun forgetMemory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { memoryManager.forget(id) }
            refreshMemories()
        }
    }

    /**
     * v1.3.11:用户改写某条记忆的内容(改错字/写得更准)。
     *
     * 复用 [com.shiyin.music.data.memory.MemoryManager.update]——它会重新归一化、截断并重算
     * 检索标签;空内容/命中不到条目返回 false,**静默不写**(不制造假成功)。
     */
    fun updateMemory(id: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { memoryManager.update(id, content) }
            refreshMemories()
        }
    }

    /** v1.3.11:「拾光记忆」管理页的开关(入口在拾光 Agent 设置页里)。 */
    var agentMemoryScreenOpen by mutableStateOf(false)

    /**
     * 用户在管理页手加一条记忆。来源记为 [MemorySource.MANUAL]:
     * 用户亲自写的,不该被"推断"的名义降级,也不该被一次性情绪拒表挡住
     * (都点添加了还被拒是说不通的——见 MemoryManager.remember)。
     */
    fun addMemory(content: String, type: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                memoryManager.remember(
                    com.shiyin.music.data.memory.MemoryCandidate(
                        type = type,
                        content = content,
                        importance = 3,
                        source = com.shiyin.music.data.memory.MemorySource.MANUAL,
                        userSaid = content,
                    ),
                )
            }
            refreshMemories()
        }
    }

    // 注:v1.3.11「纯指令词」判定已上移到 MemoryRules.looksLikeCommand(单一真源,
    // 回复前/回复后两条提取路径共用同一份判据,不再各写一份)。

    /**
     * 解析提取环节返回的候选(JSON 行或 JSON 数组)。**字面键名手动解析**(项目规矩:
     * release 包禁用 Gson 反射绑定,见 gson-r8 禁令);解析不出的行直接丢。
     */
    private fun parseMemoryCandidates(raw: String): List<com.shiyin.music.data.memory.MemoryCandidate> =
        runCatching {
            val body = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = if (body.startsWith("[")) {
                org.json.JSONArray(body)
            } else {
                org.json.JSONArray().also { a ->
                    body.lineSequence().filter { it.trim().startsWith("{") }
                        .forEach { line -> runCatching { a.put(org.json.JSONObject(line.trim())) } }
                }
            }
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = o.optString("type").trim()
                val content = o.optString("content").trim()
                if (type.isBlank() || content.isBlank()) return@mapNotNull null
                com.shiyin.music.data.memory.MemoryCandidate(
                    type = type, content = content, importance = o.optInt("importance", 2),
                )
            }.take(3)
        }.getOrDefault(emptyList())

    /**
     * v1.3.10 需求 A21(P2):**回合后记忆提取**。
     *
     * 分工(用户定调):LLM 只负责**提名**"这句有没有长期价值";**入不入库、跟旧记忆什么关系**
     * 全由确定性规则裁决([MemoryManager] → [MemoryRules])——弱模型不可靠,数据质量不能交给它。
     *
     * 为什么用"独立调用"而不是让主对话调工具:实测轻量模型不会稳定地二次调工具;而且这条链路
     * 必须在**任何**回复路径(闲聊 / 技能 / clarify)之后都能跑,工具形式覆盖不到。
     */
    /**
     * 记忆提取的**单用途调用**:只回答"这句话有没有值得长期记住的用户信息"。
     *
     * 两处共用本函数(v1.3.11 第二阶段统一):
     *  - 回复**前**:消息经 [com.shiyin.music.data.memory.MemoryRules.shouldExtractSave] 预筛命中时
     *    (自我介绍一类),先把提取做完再回复——用户第一次介绍自己就能看到「记忆已保存」;
     *  - 回复**后**(兜底):其余轮次仍走异步提取,不丢潜在记忆、也不给回复加延迟。
     *
     * @param agentReply 回复后路径传入助手回复;回复前路径传 null(那时还没有回复)。
     */
    private suspend fun extractMemoryCandidates(
        userText: String,
        agentReply: String?,
    ): List<com.shiyin.music.data.memory.MemoryCandidate> {
        val cfg = activeLlmConfig() ?: return emptyList()
        val t = userText.trim()
        // 廉价预筛:太短 / 太长(一般在贴东西)/ 纯指令 → 不花这次调用
        if (t.length < 6 || t.length > 600) return emptyList()
        if (com.shiyin.music.data.memory.MemoryRules.looksLikeCommand(t)) return emptyList()
        val replyBlock = if (agentReply.isNullOrBlank()) "" else "\n助手:${agentReply.take(300)}"
        val prompt = """
            从下面这轮对话里,挑出**值得长期记住**的用户信息(0-3 条;一条都没有就输出 [])。
            值得记:稳定偏好、长期习惯、重要个人背景、长期有意义的音乐偏好、用户明确要求记住的事。
            **一定不要记**:
              · 一次性的情绪(如"今天有点累")、临时计划、当下的指令、助手说过的话;
              · **用户下过的操作指令本身** —— 「播放 X」「放一下 X」「换一首」只说明他**此刻想听**,
                **不代表他喜欢 X**(也可能只是偶尔想听一下)。除非同一句里明确表达了对它的喜爱
                (「我一直很喜欢」「这是我最喜欢的」「我特别喜欢」),否则**不要**生成"用户喜欢 X"
                这类偏好,更不要把"要求播放 / 曾要求播放"写进内容。
            每条一行 JSON:{"type":"preference|personal|episodic|music|relationship","content":"第三人称一句话,以「用户」开头","importance":1-3}
            只输出 JSON,不要解释、不要代码块。

            用户:${t.take(300)}$replyBlock
        """.trimIndent()
        val resp = com.shiyin.music.data.ai.LlmClient.call(
            cfg, listOf("user" to prompt), temperature = 0.2, maxTokens = 300,
        ) ?: return emptyList()
        com.shiyin.music.data.ai.TokenUsageStore.log(
            getApplication(), llmActiveProvider, "memory_extract",
            resp.promptTokens, resp.completionTokens, resp.cacheTokens,
        )
        return parseMemoryCandidates(resp.content ?: "")
    }

    /**
     * 把一批候选交给**确定性规则**落库(去重/冲突取代/拒表全在 MemoryManager)。
     *
     * 返回**是否全部成功**:回复前的流程靠它给出真实终态(成功→记忆已保存,失败→记忆保存失败),
     * 绝不假装保存成功——UI 与给模型的状态提示都以此为准。
     */
    private suspend fun rememberAll(
        cands: List<com.shiyin.music.data.memory.MemoryCandidate>,
        userSaid: String,
    ): Boolean = withContext(Dispatchers.IO) {
        var allOk = true
        for (c in cands) {
            // userSaid 传原始话术:MemoryRules 据此判"用户是否明确要求记住"
            // (命中则抬 importance 并绕过一次性情绪拒表)
            val res = runCatching {
                // v1.3.12:设置里开了「连情绪与近况也记」→ 绕过一次性情绪拒表(默认关)
                memoryManager.remember(c.copy(userSaid = userSaid), allowTransient = memoryIncludeTransient)
            }
            val outcome = res.getOrNull()
            val ok = res.isSuccess && outcome !is com.shiyin.music.data.memory.RememberOutcome.Rejected
            if (!ok) allOk = false
            // v1.3.14:用**稳定标签**(见 RememberOutcome.logTag),不用 ::class.simpleName ——
            // release 经 R8 会把 Saved/Updated 缩成单字母,使用日志里的"memory C"就是这么来的。
            val tag = outcome?.logTag ?: "Failed"
            android.util.Log.i("Agent", "memory $tag: ${c.type} · ${c.content.take(30)}")
            com.shiyin.music.data.log.UsageLog.event(getApplication(), "Agent", "memory $tag · ${c.type}")
        }
        allOk
    }

    /**
     * v1.3.10 需求 A21(P2):**回合后记忆提取**(兜底路径)。
     *
     * v1.3.11 第二阶段:回复前流程已处理过的轮次**不再走这里**(避免同一轮两次提取调用);
     * 其余轮次保持既有行为——异步、不影响回复速度。
     */
    private fun extractMemories(userText: String, agentReply: String) {
        // v1.3.12:自动记忆关掉 → 连兜底提取也不做(明说的「记住…」走确定性路径,不经这里)
        if (!memoryAutoExtract) return
        if (activeLlmConfig() == null) return
        appScope.launch(Dispatchers.IO) {
            try {
                val cands = extractMemoryCandidates(userText, agentReply)
                if (cands.isEmpty()) return@launch
                rememberAll(cands, userText.trim())
            } catch (_: Exception) { }
        }
    }
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

    // ── v1.3.9: Agent 对话持久化 + 管理 ────────────────────────────────────
    // 历史按「对话」组织（与 ChatGPT/Claude 一致）：一次对话 = 一行 agent_conversation。
    // 当前页消息流 agentMessages 属于 agentConversationId 指向的对话；一轮完成/被打断
    // 时整体落库（Gson 序列化），历史页可读可改名可删可收藏。
    /** 当前对话的持久化 id；null = 尚未落库的新对话。 */
    var agentConversationId by mutableStateOf<String?>(null); private set
    /** 历史页列表（摘要，不含消息体——体在打开时才从 messagesJson 解出）。 */
    var agentConversations by mutableStateOf<List<com.shiyin.music.data.db.AgentConversationEntity>>(emptyList()); private set
    /** v1.3.9: 「覆盖重发」锚点。>=0 = 下一次发送将**替换**该下标起的消息
     *  （点击历史消息/打开历史对话时置位；发送或切走对话时复位）。 */
    var agentEditFromIndex by mutableStateOf(-1); private set

    /** 历史页打开时刷新对话列表。 */
    fun loadAgentConversations() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = dao.allAgentConversations()
            kotlinx.coroutines.withContext(Dispatchers.Main) { agentConversations = list }
        }
    }

    /** 对话标题：第一条用户消息截断（可随后手动改名）。 */
    private fun agentConversationTitle(msgs: List<AgentMessage>): String {
        val first = msgs.firstOrNull { it.role == "user" }?.text?.trim() ?: return "新对话"
        return if (first.length > 24) first.take(24) + "…" else first
    }

    /** 当前对话整体落库（一轮完成/被打断/离开页面时调用）。空对话不落。 */
    fun persistAgentConversation() {
        val msgs = agentMessages
        if (msgs.isEmpty()) return
        val id = agentConversationId ?: java.util.UUID.randomUUID().toString()
            .also { agentConversationId = it }
        val now = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            val prev = dao.allAgentConversations().firstOrNull { it.id == id }
            dao.upsertAgentConversation(
                com.shiyin.music.data.db.AgentConversationEntity(
                    id = id,
                    title = prev?.title?.takeIf { it.isNotBlank() && it != "新对话" }
                        ?: agentConversationTitle(msgs),
                    pinned = prev?.pinned ?: 0,
                    createdAt = prev?.createdAt ?: now,
                    updatedAt = now,
                    messagesJson = com.google.gson.Gson().toJson(msgs),
                ),
            )
        }
    }

    /** 打开一段历史对话：消息装入当前页 + 最后一条用户消息复制进输入框
     *  （发送时**覆盖**该条起的后续内容——主流产品的「编辑重发」语义，
     *  不是另起新消息）。 */
    fun openAgentConversation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = dao.allAgentConversations().firstOrNull { it.id == id } ?: return@launch
            val msgs = decodeAgentMessages(row.messagesJson)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                agentConversationId = id
                agentMessages = msgs
                agentHistoryOpen = false
                // v1.3.15:换到另一段对话 → 推荐槽位跟着断(它属于刚才那段会话)。
                agentRecIntent = null
                // v1.4.0:打开历史对话**不再把最后一条用户消息回填输入框**。
                // 原实现把它塞进 draft 并置覆盖锚点(当"重发/编辑"入口),但用户只是想翻看时,
                // 输入框莫名粘上上一句问话(真机反馈)。编辑/重发留给用户在该条消息上显式触发。
                agentEditFromIndex = -1
            }
        }
    }

    /** 点击用户气泡 = 装回输入框 + 置覆盖锚点（发送时替换该条起的后续内容）。 */
    fun prepareAgentMessageEdit(index: Int) {
        val msg = agentMessages.getOrNull(index) ?: return
        if (msg.role != "user") return
        agentDraft = msg.text
        agentEditFromIndex = index
    }

    /** 新对话：先把当前对话落库，再清屏。 */
    fun newAgentConversation() {
        persistAgentConversation()
        agentMessages = emptyList()
        agentConversationId = null
        agentEditFromIndex = -1
        agentHistoryOpen = false
        // v1.3.11:清屏也把记忆状态收掉——它属于刚才那一轮,不该跟着新对话留在界面上
        agentMemoryFlow.reset()
        publishMemory()
        // v1.3.15:推荐槽位同理——"上一轮让你挑的睡觉的歌"属于那个会话,开了新对话就该忘掉
        // (否则新对话第一句"方大同的"会莫名其妙接上上一段会话的场景)。
        agentRecIntent = null
    }

    fun renameAgentConversation(id: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.renameAgentConversation(id, title.trim().ifBlank { "新对话" })
            loadAgentConversations()
        }
    }

    fun deleteAgentConversation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAgentConversation(id)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (agentConversationId == id) {
                    agentMessages = emptyList()
                    agentConversationId = null
                    agentEditFromIndex = -1
                }
                loadAgentConversations()
            }
        }
    }

    fun toggleAgentConversationPinned(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = dao.allAgentConversations().firstOrNull { it.id == id } ?: return@launch
            dao.setAgentConversationPinned(id, if (row.pinned == 1) 0 else 1)
            loadAgentConversations()
        }
    }

    /** 重试某条 Agent 回复：找回它前面的用户消息，覆盖从该用户消息起的后续内容并重新发送。 */
    fun retryAgentMessage(agentIndex: Int) {
        if (agentRunning) return
        val userIdx = (agentIndex - 1 downTo 0).firstOrNull { agentMessages[it].role == "user" } ?: return
        // v1.3.15:不再把原问句回填输入框(用户反馈:点【重试】后问句被塞进输入框,还得手动清掉)。
        // 重试只是"用同一句重发一遍":问句本来就在上方那条用户气泡里看得见,回填纯属噪音。
        // 仍需 agentEditFromIndex-覆盖语义:从该用户消息起替换后续内容,而不是追加。
        agentEditFromIndex = userIdx
        sendAgentMessage(agentMessages[userIdx].text)
    }

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
        /** v1.3.8: 中间状态历史(方案C折叠式)——记录 THINKING/TOOL 的时间顺序,
         *  用于"查看思考过程"折叠区域。只在多轮交替或有 thinking summary 时显示入口。 */
        val processHistory: List<ProcessStep> = emptyList(),
    )

    /** v1.3.8: Agent 消息的中间处理步骤(方案C折叠式)。 */
    data class ProcessStep(
        val type: String,  // "thinking" / "tool"
        val label: String, // thinking: "思考中..." / tool: "正在搜索周杰伦的专辑信息..."
        val durationMs: Long = 0L, // 耗时
        val summary: String? = null, // thinking summary（如果模型支持）
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

        /** v1.3.6: 全库搜歌——标题/歌手/专辑任一模糊匹配,按播放次数排序返回前 [limit] 条。
         *  v1.3.10: **改走 FuzzySearch**(与 UI 搜索同一套实现)。旧实现是
         *  `lowercase().filter{isLetterOrDigit()}.contains()`:不做全角→半角、不做繁→简、
         *  没有编辑距离,更没有假名/罗马字——「カブトムシ」按 kabutomushi 搜、繁体名、
         *  全角标点全都搜不到,Agent 于是报"库里没有"。归一化层必须全局只有一套。 */
        override fun searchTracks(query: String, limit: Int): List<Track> {
            if (query.isBlank()) return emptyList()
            val seen = HashSet<Long>()
            return com.shiyin.music.data.search.FuzzySearch.search(lib(), query)
                .map { it.track }
                .filter { seen.add(it.id) }
                // 契约不变:命中的曲目按"最常听"排序(playback_control 会拿这批当队列,
                // 首位=最常听的那首)。
                .sortedByDescending { heatCountFor(it.id) }
                .take(limit)
        }

        /** v1.3.6: 搜歌手名——库内歌手名模糊匹配,按该歌手总播放次数排序。
         *  v22: 走热度口径（排除 intro/outro 这类过渡曲），否则短曲会挤掉真歌。 */
        override fun searchArtists(query: String, limit: Int): List<String> {
            val q = query.lowercase().filter { it.isLetterOrDigit() }
            if (q.isBlank()) return emptyList()
            val counts = HashMap<String, Int>()
            for (t in lib()) {
                val heat = heatCountFor(t.id)
                if (heat <= 0) continue
                for (a in com.shiyin.music.data.MediaScanner.splitArtists(t.artist)) {
                    counts.merge(a, heat, Int::plus)
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
        override fun setSleepTimer(minutes: Int) = player.setSleepTimer(minutes)
        override fun setSleepTimerEndOfTrack() = player.setSleepEndOfTrack()
        override fun sleepTimerStatus(): String = player.sleepStatusText()

        // v1.3.9: 技能在 IO 线程执行,而 MediaController 只允许在应用主线程调用——
        // 真机实证:Agent 播放控制从 IO 线程直调 controller 直接报错("没能力执行")。
        // 所有**写**操作统一 post 到主线程;读的是 Compose 状态快照,跨线程安全。
        val playbackMain = android.os.Handler(android.os.Looper.getMainLooper())
        override fun playerReady(): Boolean = player.isReady
        // v1.3.8: 播放控制/模式桥。只动播放状态,不碰音乐库内容。
        override fun playTracks(tracks: List<Track>, startId: Long) {
            android.util.Log.i("Agent", "playTracks: ${tracks.size} 首, startId=$startId, ready=${player.isReady}")
            playbackMain.post {
                android.util.Log.i("Agent", "playTracks 已投递主线程执行, ready=${player.isReady}")
                player.playQueue(tracks, startId, null)
            }
        }
        override fun pausePlayback() { playbackMain.post { if (player.isPlaying) player.toggle() } }
        override fun resumePlayback() { playbackMain.post { if (!player.isPlaying) player.toggle() } }
        override fun nextTrack() { playbackMain.post { player.next() } }
        override fun previousTrack() { playbackMain.post { player.prev() } }
        override fun isPlaying(): Boolean = player.isPlaying
        override fun setShuffle(on: Boolean) { playbackMain.post { player.setShuffle(on) } }
        override fun isShuffleOn(): Boolean = player.shuffleFlag
        override fun setRepeatMode(mode: com.shiyin.music.data.ai.AgentEngine.RepeatMode) {
            playbackMain.post {
                player.setRepeat(
                    when (mode) {
                        com.shiyin.music.data.ai.AgentEngine.RepeatMode.OFF -> androidx.media3.common.Player.REPEAT_MODE_OFF
                        com.shiyin.music.data.ai.AgentEngine.RepeatMode.ALL -> androidx.media3.common.Player.REPEAT_MODE_ALL
                        com.shiyin.music.data.ai.AgentEngine.RepeatMode.ONE -> androidx.media3.common.Player.REPEAT_MODE_ONE
                    },
                )
            }
        }
        override fun repeatMode(): com.shiyin.music.data.ai.AgentEngine.RepeatMode = when (player.repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_ALL -> com.shiyin.music.data.ai.AgentEngine.RepeatMode.ALL
            androidx.media3.common.Player.REPEAT_MODE_ONE -> com.shiyin.music.data.ai.AgentEngine.RepeatMode.ONE
            else -> com.shiyin.music.data.ai.AgentEngine.RepeatMode.OFF
        }
        override fun setPlaybackSpeed(speed: Float) { playbackMain.post { setSpeed(speed) } }
        override suspend fun weeklyListeningStats() = this@MainViewModel.weeklyListeningStats()
        override fun trackById(id: Long): Track? = this@MainViewModel.trackById(id)

        /** v1.3.10 需求 A27:模型搜到的歌词落地到当前曲目(复用 .lrc 导入通道)。
         *  切回主线程写 Compose 状态(与播放类桥同惯例),并锚定"发指令时那首"。 */
        override fun importLyricsForCurrentTrack(name: String, text: String) {
            playbackMain.post {
                val id = player.currentId ?: return@post
                importLrcContent(name, text, id)
            }
        }

        /** v1.3.10: 全库曲目按最常听排序——「你自己挑一首」的候选池(discover_music)。
         *  只读;排序用与搜索/播放队列一致的热度口径。 */
        override fun listAllTracks(): List<Track> = lib().sortedByDescending { heatCountFor(it.id) }

        /**
         * v1.3.8: Agent 的 album_dates 技能走这里。
         *
         * 作品筛选**必须**与歌手页 ArtistDetail 逐字一致(专辑位歌手 + 排除合集),
         * 否则同一个歌手在歌手页与 Agent 里会看到不同的作品集/不同的顺序——
         * "两个入口的答案是同一件事"这条规矩要求复刻规则,而不是另写一套。
         * (classifyAlbum 是 ui.screens 里的公开纯函数,内容判定逻辑放那儿了,这里直接用。)
         */
        override suspend fun sortArtistAlbumsByDate(artistName: String): com.shiyin.music.data.ai.AgentEngine.AlbumDateOutcome {
            val entries = albumsMap().entries.filter { e ->
                artistName in com.shiyin.music.data.MediaScanner.splitArtists(e.value.first().artist) &&
                    com.shiyin.music.ui.screens.classifyAlbum(e.value) != com.shiyin.music.ui.screens.AlbumCategory.Compilation
            }
            if (entries.isEmpty()) {
                return com.shiyin.music.data.ai.AgentEngine.AlbumDateOutcome(0, "音乐库里没有「$artistName」的专辑")
            }
            val triples = entries.map { (key, tracks) ->
                Triple(key, tracks.first().albumId, tracks.first().album)
            }
            // 进度回调在主线程 post,fetchArtistAlbumDates 内部已处理;这里只留存最后一条
            // 非空原因,失败时原样回执(与歌手页状态行同一套文案)
            var lastReason = ""
            val n = fetchArtistAlbumDates(artistName, triples) { if (it.isNotBlank()) lastReason = it }
            return com.shiyin.music.data.ai.AgentEngine.AlbumDateOutcome(
                n,
                if (n > 0) "" else lastReason.ifBlank { "未能获取「$artistName」的发布时间" },
            )
        }
        override fun logUsage(promptTokens: Int, completionTokens: Int, cacheTokens: Int) {
            viewModelScope.launch(Dispatchers.IO) {
                com.shiyin.music.data.ai.TokenUsageStore.log(
                    getApplication(), llmActiveProvider, "agent", promptTokens, completionTokens, cacheTokens,
                )
            }
        }

        override fun recordDiag(
            skill: String, stage: String, ok: Boolean, counts: Map<String, Int>,
            replyRaw: String?, errorCode: String?, errorDetail: String?, webRaw: String?,
        ) {
            // 脱敏与截断统一在这里(技能只管报事实);开关关闭时 AgentDiag 自己会跳过写入。
            com.shiyin.music.data.log.AgentDiag.record(
                getApplication(),
                com.shiyin.music.data.log.AgentDiag.Sample(
                    tsMs = System.currentTimeMillis(),
                    skill = skill, stage = stage, ok = ok, counts = counts,
                    replyHead = com.shiyin.music.data.log.AgentDiag.replyHeadOf(replyRaw),
                    errorCode = errorCode, errorDetail = errorDetail,
                    webHead = com.shiyin.music.data.log.AgentDiag.webHeadOf(webRaw),
                ),
                enabled = agentDiagEnabled,
            )
        }

        /** v1.3.15:本轮推荐槽位(上一轮意图 + 回合序号 + 时间 + 本轮原话)。技能据此自行
         *  判定"是否继承上一轮场景"。 */
        override fun recTurnContext(): com.shiyin.music.data.ai.RecTurnContext =
            com.shiyin.music.data.ai.RecTurnContext(
                prev = agentRecIntent,
                turnIndex = agentTurnIndex,
                nowMs = System.currentTimeMillis(),
                userMessage = agentTurnUserText,
            )

        /** v1.3.15:技能执行完把**合并后**的槽位写回(含代码继承来的场景,补充轮不冲掉场景)。 */
        override fun recordRecIntent(scene: String?, artist: String?, album: String?) {
            if (scene.isNullOrBlank() && artist.isNullOrBlank() && album.isNullOrBlank()) return
            agentRecIntent = com.shiyin.music.data.ai.RecIntent(
                scene = scene?.takeIf { it.isNotBlank() },
                artist = artist?.takeIf { it.isNotBlank() },
                album = album?.takeIf { it.isNotBlank() },
                turnIndex = agentTurnIndex,
                timestampMs = System.currentTimeMillis(),
            )
        }
    }

    /** 用户在 Agent 页发指令。 */
    fun sendAgentMessage(text: String) {
        if (agentRunning || text.isBlank()) return
        // v1.3.15:新一轮 → 回合序号 +1,并记下原话(推荐槽位的 TTL 按回合算;
        // 技能还要在原话里兜底扫"用户到底点了哪个歌手")。
        agentTurnIndex += 1
        agentTurnUserText = text.trim()
        // v1.3.11:记忆状态是**本回合**的过程反馈——新一轮开始即清空重来。
        // 上一轮的终态(记忆已保存/未找到相关记忆)故意保留到这一刻才清:用户回复后
        // 仍能回头看"刚才到底存下来没有",而不会永久占据聊天记录。
        agentMemoryFlow.reset()
        publishMemory()
        // v1.3.9: 覆盖重发——点击历史用户消息/打开历史对话后,发送 = **替换**锚点起的
        // 后续内容(主流产品的"编辑重发"语义),不是另起一条。截断后按新消息走正常流程。
        val editFrom = agentEditFromIndex
        if (editFrom >= 0) {
            agentMessages = agentMessages.take(editFrom)
            agentEditFromIndex = -1
        }
        // v1.3.3b review#B6: 闸门过即置位——原 agentRunning=true 在消息 append 之后,
        // 双击/IME onSend 与按钮点击并发时两次都能过闸 → 同一句话发两条、烧双份 token。
        agentRunning = true
        // v1.3.2: 用户开始对话后,初始建议立即消失(不常驻);本轮结束后按结果给后续建议
        agentSuggestions = emptyList()
        // v1.3.10: 直接发消息 = 视作选了卡片上的「其他」答案,卡片随之收起(与卡片提示一致)。
        agentClarify = null
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
            // v1.3.8: 中间状态历史(方案C折叠式)——记录 THINKING/TOOL 的时间顺序
            val processHistory = mutableListOf<ProcessStep>()
            // 占位一条 Agent 消息(后面替换)
            agentMessages = agentMessages + AgentMessage("agent", "", steps.toList())
            // v1.3.9 封口闸:终态消息(最终回答/失败文案)一旦写入,所有经 mainHandler
            // 排队的中间态回调一律作废——杜绝"迟到的旧 post 把最终回答覆盖回空文本"。
            // (tool 执行完成 ≠ turn 完成;只有最终 assistant 文本落进消息列表才封口。)
            val turnSealed = java.util.concurrent.atomic.AtomicBoolean(false)
            fun seal() {
                turnSealed.set(true)
                val m = agentMessages.getOrNull(msgIdx)
                val report = "turn sealed: msgIdx=$msgIdx textLen=${m?.text?.length ?: -1} steps=${steps.joinToString { "${it.id}:${it.status}" }}"
                android.util.Log.i("Agent", report)
                // 持久化事件(logcat 会滚动丢失,UsageLog 落库可查)——"停在中间步骤"
                // 类问题复发时,这里能直接看到终态写没写、写了多长。
                com.shiyin.music.data.log.UsageLog.event(getApplication(), "Agent", report)
            }
            // ── v1.3.11:本轮的记忆判据(纯函数,无副作用) ──────────────────────
            // 在 try 外先算好:finally 要靠 memoryExplicit/memoryPreExtract 判断
            // "回复后还要不要再提取一次"——已经处理过的不重复花一次调用。
            val memoryExplicit = com.shiyin.music.data.memory.MemoryRules.explicitCandidate(text)
            val memoryWarrants = com.shiyin.music.data.memory.MemoryRules.memoryWarrantsTrace(text)
            // v1.3.12:自动记忆总开关(设置 → 拾音记忆「从对话里自动记住」)。
            // 关掉后:不预提取、回复后也不兜底提取 —— 只有明说「记住…」才落库(确定性路径不受影响)。
            val memoryPreExtract = memoryAutoExtract &&
                com.shiyin.music.data.memory.MemoryRules.shouldExtractSave(text)
            try {
                // 1. v1.3.8: 单次流式工具调用分发。原生 tool calling 让「选技能 / 聊天回答 /
                //    要不要联网」在**同一次请求**里完成——取代旧的「决策调用 + 路由调用」
                //    两次串行往返(旧链路连"你好"都要先等一次非流式决策调用,callSec=45s)。
                // v1.3.12:原来这里带 `it.steps == null`,于是**所有技能回复都被排除在历史之外**
                // —— 模型看不到"上一轮那次播放已经成功完成",下一轮就容易把用户的感想读成
                // "再放一遍"(真机 bug 的直接根因之一)。现在技能回复也进历史;每条限长,
                // 免得长验收清单把上下文挤爆。
                val history = agentMessages
                    .filter { it.text.isNotBlank() }
                    .dropLast(1)  // 当前指令的占位是空文本,已被过滤;这里再兜一层
                    .takeLast(4)
                    .map { it.role to it.text.take(300) }
                // 思考链实时流出(reasoning 增量) + 聊天回复实时流出(打字机数据源)
                var intentThinking: String? = null
                var streamedReply: String? = null
                // v1.3.3b review#B2: SSE 回调在 IO 线程触发——state 写入投递主线程执行,
                // 思考胶囊/打字机不依赖"后台写 snapshot 恰好触发重组"。
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

                /**
                 * v1.3.13:中间态的**保号更新** —— 同一时刻已有的正文与思考链都要带上。
                 *
                 * 真机 bug:原来每个中间回调都自己 `AgentMessage("agent", …, steps)` 重建消息,
                 * 只带其中一个字段 —— onThinking 丢正文、onContent 丢思考链、onSearch 两个都丢。
                 * 于是联网搜索的多轮里,「思考中」被反复抹掉又出现(用户实测:"1s 又消失,反复 2-3 次"),
                 * 正文一旦开始流就把思考链整块抹掉。统一走这一个构造口,谁都不会被顺手丢掉。
                 */
                fun liveAgentMsg(
                    stepsNow: List<com.shiyin.music.data.ai.AgentEngine.Step>,
                    text: String? = null,
                    thinking: String? = null,
                ) = AgentMessage(
                    role = "agent",
                    text = text ?: streamedReply.orEmpty(),
                    steps = stepsNow,
                    thinking = thinking ?: intentThinking,
                )

                // ── v1.3.11 统一记忆流程:本轮**只推进一条**状态行 ────────────────────
                // 顺序按规格:查找记忆中 → (已找到 / 未找到) → 需要保存则 记忆中 → 记忆已保存。
                // 每一步都由真实执行结果驱动;模型既不产生、也无法影响这些状态。
                if (memoryWarrants) {
                    agentMemoryFlow.begin()
                    publishMemory()
                }

                // 领域上下文(当前歌曲/专辑/歌手 + **播放状态** + **上一轮动作**)——
                // 与记忆分层(见 AgentEngine.contextSections);v1.3.13 起播放状态只在
                // 用户本条消息确实谈播放时才注入(闲聊/评价别人时不带,避免被错接)。
                val domainContext = buildAgentContext(text)

                // v1.3.8(D2): 高频单条指令本地快速路径——命中即零 LLM 往返直接执行
                // (golden path:「打开专辑 → 排成正确顺序」)。已收窄到 album_sort 一条;
                // 其余技能的本地正则消歧删除,语义消歧交给工具 description。
                val fastPlan = com.shiyin.music.data.ai.AgentEngine.fastPathPlan(text)

                // ── ① 检索 ───────────────────────────────────────────────────────
                // 保持既有行为:非 fastPath 一律检索(相关性由门槛裁决);fastPath 不做无意义检索。
                var memoryBlock = ""
                var foundCount = 0
                var recallFailed = false
                if (fastPlan == null) {
                    val recallRes = runCatching { memoryManager.recall(text, 3) }
                    if (recallRes.isFailure) {
                        // 异常是真实状态,值得露出;但只有"本轮本来就该显示记忆"时才打扰用户
                        recallFailed = true
                        android.util.Log.w("Agent", "memory recall failed", recallRes.exceptionOrNull())
                        if (memoryWarrants) {
                            agentMemoryFlow.recallFailed(); publishMemory()
                        }
                    } else {
                        val recalled = recallRes.getOrDefault(emptyList())
                        foundCount = recalled.size
                        if (foundCount > 0) {
                            // 命中就显示(哪怕本轮原本不需要记忆)——这是"她真的用上了记忆"的证据
                            agentMemoryFlow.found(foundCount); publishMemory()
                            android.util.Log.i("Agent", "memory recalled $foundCount: ${recalled.map { it.content.take(20) }}")
                            com.shiyin.music.data.log.UsageLog.event(
                                getApplication(), "Agent", "memory recalled · $foundCount",
                            )
                            memoryBlock = buildString {
                                appendLine("[关于这位用户,你记得的事]")
                                recalled.forEach { appendLine("· ${it.content}") }
                            }.trimEnd()
                        }
                    }
                }

                // ── ② 保存 ───────────────────────────────────────────────────────
                // 显式「记住」走确定性解析(不依赖 LLM);其余按预筛做一次**回复前**提取,
                // 让"第一次介绍自己"也能看到真实的保存结果。
                var saveCands = memoryExplicit?.let { listOf(it) } ?: emptyList()
                if (saveCands.isEmpty() && memoryPreExtract && fastPlan == null) {
                    saveCands = runCatching { extractMemoryCandidates(text, null) }.getOrDefault(emptyList())
                }
                var memorySaveFailed = false
                if (saveCands.isNotEmpty()) {
                    agentMemoryFlow.saving(); publishMemory()
                    // async 挂在 viewModelScope(不是 agentJob):用户点"停止"也不该丢掉这句「记住」
                    val ok = viewModelScope.async(Dispatchers.IO) { rememberAll(saveCands, text) }.await()
                    memorySaveFailed = !ok
                    if (ok) agentMemoryFlow.saved() else agentMemoryFlow.saveFailed()
                    publishMemory()
                    com.shiyin.music.data.log.UsageLog.event(
                        getApplication(), "Agent", "memory save ${if (ok) "ok" else "failed"}",
                    )
                } else if (!recallFailed && foundCount == 0 && memoryWarrants) {
                    // 没保存成、也没命中 → 落回"未找到相关记忆"(仍然是同一条行)
                    agentMemoryFlow.nothingFound(); publishMemory()
                }
                // 保存失败必须让模型知道——否则它会说"好的我记住了"(假状态,用户明确禁止)
                if (memorySaveFailed) {
                    memoryBlock = listOf(
                        memoryBlock,
                        "[系统状态] 用户这条「记住」的请求**没有保存成功**。不要声称已经记住,如实说明没记上。",
                    ).filter { it.isNotBlank() }.joinToString("\n")
                }

                // ── v1.3.12:用户**明确要求**联网 → 系统先把资料搜好 ────────────────
                // "帮我搜一下 X" 是**确定性**信号,不该赌弱模型记不记得调 web_search:
                // 它常常直接凭记忆答。这里先搜好塞进上下文,回答就建立在真实资料上。
                var webBlock = ""
                if (fastPlan == null) {
                    // v1.4.0:「强制联网搜索」由 **tool_choice 强制模型调用联网工具**实现
                    // （见 AgentTools.dispatch），不再拿用户原话直接去搜——那样绕过了模型
                    // 对"该搜什么"的判断。这里只保留用户**明确要求联网**时的预搜。
                    val preQuery = com.shiyin.music.data.ai.WebSearchPolicy.explicitQuery(text)
                    preQuery?.let { q ->
                        if (tavilyApiKey.isNotBlank()) {
                            // v1.4.0: 预搜索也要在**实时步骤面板**里露面，否则用户只看到
                            // "思考中"，不知道系统已经替他搜过（真机反馈：没见到联网搜索的图标）。
                            mainHandler.post {
                                if (turnSealed.get()) return@post
                                steps.upsert(
                                    "web", "联网搜索:$q",
                                    com.shiyin.music.data.ai.AgentEngine.StepStatus.RUNNING,
                                )
                                agentMessages = agentMessages.replaceAt(msgIdx, liveAgentMsg(steps.toList()))
                            }
                            val t0 = System.currentTimeMillis()
                            val ctxText = runCatching {
                                com.shiyin.music.data.ai.TavilyService.search(tavilyApiKey, q, includeAnswer = true)
                            }.getOrNull()
                            val ok = !ctxText.isNullOrBlank()
                            val ms = System.currentTimeMillis() - t0
                            android.util.Log.i("Agent", "web pre-search '$q' ok=$ok (${ms}ms)")
                            com.shiyin.music.data.log.UsageLog.event(
                                getApplication(), "Agent", "web pre-search ${if (ok) "ok" else "empty"}",
                            )
                            processHistory.add(ProcessStep(type = "tool", label = "联网搜索:$q", durationMs = ms))
                            mainHandler.post {
                                if (turnSealed.get()) return@post
                                val st = if (ok) com.shiyin.music.data.ai.AgentEngine.StepStatus.DONE
                                         else com.shiyin.music.data.ai.AgentEngine.StepStatus.FAILED
                                val body = buildString {
                                    append("关键词：").append(q)
                                    if (!ctxText.isNullOrBlank()) append("\n\n检索到：\n").append(ctxText.take(1200))
                                    else append("\n\n（没有返回资料）")
                                }
                                steps.upsert("web", "联网搜索:$q", st, detail = body)
                                agentMessages = agentMessages.replaceAt(msgIdx, liveAgentMsg(steps.toList()))
                            }
                            if (ok) {
                                webBlock = "[联网资料] 用户明确要求联网,系统已替他搜好「$q」。回答以下面资料为准;资料没提的不要编,也不要拿记忆补:\n" +
                                    ctxText!!.take(3000)
                            }
                        }
                    }
                }
                // v1.3.12:把"该不该联网"的判断也**贴着本条消息**说给模型听。
                // 弱模型对一整段通则的遵从度很低,但一句针对当前这句的提示往往管用;
                // 同时也不让它"因为拿到了 web 工具就什么都搜"(观点/感想明确劝住)。
                val webHint = when {
                    webBlock.isNotBlank() -> ""   // 已经搜好了,不用再提示
                    com.shiyin.music.data.ai.WebSearchPolicy.looksTimeSensitive(text) ->
                        "[系统] 这条问题涉及最新/时效信息 —— 先 web_search 核实再回答,不要凭记忆答。"
                    com.shiyin.music.data.ai.WebSearchPolicy.looksOpinion(text) ->
                        "[系统] 这是音乐观点/感想,直接聊即可,不要为它联网。"
                    else -> ""
                }
                // 交给模型的完整动态上下文:领域事实 + 播放状态/上一轮动作 + (可选)预搜资料 + 联网提示
                val agentContext = listOf(domainContext, webBlock, webHint)
                    .filter { it.isNotBlank() }.joinToString("\n")

                var chatReply: String? = null
                var planDraft: com.shiyin.music.data.ai.AgentEngine.IntentPlan? =
                    fastPlan?.let { com.shiyin.music.data.ai.AgentEngine.IntentPlan(it.skillKey, it.args, emptyList()) }
                var clarifyReq: com.shiyin.music.data.ai.AgentTools.ClarifyRequest? = null
                var routeTokens: Pair<Int, Int> = 0 to 0
                var dispatchThinking: String? = null
                var dispatchThinkingMs = 0L

                if (fastPlan != null) {
                    android.util.Log.i("Agent", "fast-path: skill=${fastPlan.skillKey} args=${fastPlan.args}(省一次分发 LLM)")
                } else {
                    val disp = com.shiyin.music.data.ai.AgentTools.dispatch(
                        config = config,
                        tavilyKey = tavilyApiKey,
                        userText = text,
                        contextBlock = agentContext,
                        memoryBlock = memoryBlock,
                        forceSearch = agentForceSearch,
                        history = history,
                        onThinking = { th ->
                            mainHandler.post {
                                if (turnSealed.get()) return@post
                                intentThinking = th
                                // v1.4.0: 把流式思考同步写进「思考」步骤的可展开正文,
                                // 使步骤面板里能展开看到推理过程（不只是"思考中 · Ns"）。
                                steps.upsert(
                                    "intent", "正在思考",
                                    com.shiyin.music.data.ai.AgentEngine.StepStatus.RUNNING,
                                    detail = th,
                                )
                                agentMessages = agentMessages.replaceAt(
                                    msgIdx, liveAgentMsg(steps.toList(), thinking = th),
                                )
                            }
                        },
                        onContent = { full ->
                            mainHandler.post {
                                if (turnSealed.get()) return@post
                                val clean = full.trimStart()
                                streamedReply = clean
                                agentMessages = agentMessages.replaceAt(msgIdx, liveAgentMsg(steps.toList(), text = clean))
                            }
                        },
                        onSearch = { q ->
                            mainHandler.post {
                                if (turnSealed.get()) return@post
                                steps.upsert("web", "联网搜索:$q", com.shiyin.music.data.ai.AgentEngine.StepStatus.RUNNING)
                                agentMessages = agentMessages.replaceAt(msgIdx, liveAgentMsg(steps.toList()))
                                // v1.3.8: 记录 TOOL 状态(搜索工具调用)
                                processHistory.add(ProcessStep(
                                    type = "tool",
                                    label = "正在搜索:$q",
                                    durationMs = 0L, // 搜索完成后再更新耗时
                                ))
                            }
                        },
                        onSearchComplete = { q, success, snippet ->
                            mainHandler.post {
                                if (turnSealed.get()) return@post
                                val status = if (success) com.shiyin.music.data.ai.AgentEngine.StepStatus.DONE
                                             else com.shiyin.music.data.ai.AgentEngine.StepStatus.FAILED
                                // v1.4.0: 展开正文 = 关键词 + 本次检索到的资料（模型据此的判断
                                // 由后续思考增量继续写进**同一个** step，不另起一行）。
                                val body = buildString {
                                    append("关键词：").append(q)
                                    if (snippet.isNotBlank()) append("\n\n检索到：\n").append(snippet)
                                }
                                steps.upsert("web", "联网搜索:$q", status, detail = body)
                                agentMessages = agentMessages.replaceAt(msgIdx, liveAgentMsg(steps.toList()))
                            }
                        },
                    )
                    // 分发调用本身也花 token(不搜也花了)——无条件入账,用量面板才准。
                    if (disp.promptTokens + disp.completionTokens > 0) {
                        viewModelScope.launch(Dispatchers.IO) {
                            com.shiyin.music.data.ai.TokenUsageStore.log(
                                getApplication(), llmActiveProvider, "agent_dispatch",
                                disp.promptTokens, disp.completionTokens, disp.cacheTokens,
                            )
                        }
                    }
                    chatReply = disp.chatReply
                    clarifyReq = disp.clarify
                    routeTokens = disp.promptTokens to disp.completionTokens
                    dispatchThinking = disp.thinking ?: intentThinking
                    dispatchThinkingMs = disp.thinkingMs
                    // v1.3.8: 记录 THINKING 状态(如果有 thinking 内容或耗时 > 0)
                    if ((dispatchThinking?.isNotBlank() == true && dispatchThinking.length > 20) || dispatchThinkingMs > 500) {
                        processHistory.add(ProcessStep(
                            type = "thinking",
                            label = "思考中...",
                            durationMs = dispatchThinkingMs,
                            summary = if (dispatchThinking != null && dispatchThinking.length <= 200) dispatchThinking else null,
                        ))
                    }


                    if (!disp.toolsSupported) {
                        // 供应商不认 tools(HTTP 400/422)→ 回退旧 prompt+JSON 链路,行为与上一版一致
                        android.util.Log.i("Agent", "dispatch: 供应商不支持 tools,回退旧 route 链路")
                        // 领域上下文由 route() 内部自己按 ctx 组装(与 domainContext 同源);
                        // 这里只需把**记忆块**递进去 —— 回退链路也必须拿到同一份记忆。
                        val r = routeOnce(text, history, config, steps, msgIdx, mainHandler, "", memoryBlock) { th, rp ->
                            intentThinking = th; streamedReply = rp
                        }
                        if (r == null) {
                            val err = com.shiyin.music.data.ai.LlmClient.lastError ?: "未知错误"
                            agentMessages = agentMessages.replaceAt(
                                msgIdx,
                                AgentMessage("agent", "LLM 调用失败:$err\n请到右上「设置」检查供应商额度 / Key / 模型名。", steps.toList()),
                            )
                            seal()
                            return@launch
                        }
                        chatReply = r.chatReply
                        clarifyReq = null
                        planDraft = r.skill
                        routeTokens = r.promptTokens to r.completionTokens
                        dispatchThinking = r.thinking ?: intentThinking
                        dispatchThinkingMs = r.thinkingMs
                    } else if (disp.failed) {
                        // v1.4.0: 只给"人话类别 + 下一步动作"，不再把供应商的原始异常串
                        // （含完整地址）摆到对话里。disp.error 已是 UsageLogError 的类别。
                        agentMessages = agentMessages.replaceAt(
                            msgIdx, AgentMessage(
                                "agent",
                                "这轮没能走通：${disp.error ?: "原因未知"}。可以重试，或换一个模型服务商。",
                                steps.toList(),
                            ),
                        )
                        seal()
                        return@launch
                    } else {
                        disp.skill?.let {
                            planDraft = com.shiyin.music.data.ai.AgentEngine.IntentPlan(it.key, it.args, emptyList())
                        }
                    }
                }

                // ── v1.3.12:"要播放"却被路由成"查库" → 纠正一次 ───────────────────────
                // 真机实测:「给我播放藤井风的 prema」被路由到 library_query,**罗列了 8 条检索结果**
                // 当回答(用户:"这显然不应该,我要求播放的是音乐")。库查询的 description 里其实
                // 已经写了"想听就用 playback_control、不要用本工具",但**弱模型不照办** ——
                // 所以这里用纯函数判据确定性地兜一次:工具面保持完整,只加一条贴着本条消息的说明,
                // 让它重新选。纠正后若是播放类技能,就走下面的正常链路(含歧义 → clarify 询问)。
                if (planDraft?.skillKey == "library_query" &&
                    com.shiyin.music.data.ai.PlaybackIntentGuard.classify(text) !=
                    com.shiyin.music.data.ai.PlaybackIntentGuard.Intent.NONE
                ) {
                    android.util.Log.i("Agent", "misroute: 播放指令被路由到 library_query → 纠正一次")
                    com.shiyin.music.data.log.UsageLog.event(getApplication(), "Agent", "misroute play→query corrected")
                    val fixed = com.shiyin.music.data.ai.AgentTools.dispatch(
                        config = config,
                        tavilyKey = tavilyApiKey,
                        userText = text,
                        contextBlock = listOf(
                            agentContext,
                            "[系统] 用户这条是在**要求播放**,不是查库。直接调用 playback_control 播放它;" +
                                "名字在库里同时是单曲和专辑时用 clarify 问用户放哪个。**不要罗列检索结果**。",
                        ).filter { it.isNotBlank() }.joinToString("\n"),
                        memoryBlock = memoryBlock,
                        forceSearch = agentForceSearch,
                        history = history,
                        onThinking = { th ->
                            mainHandler.post {
                                if (turnSealed.get()) return@post
                                intentThinking = th
                            }
                        },
                        onContent = { full ->
                            mainHandler.post {
                                if (turnSealed.get()) return@post
                                streamedReply = full.trimStart()
                            }
                        },
                    )
                    routeTokens = fixed.promptTokens to fixed.completionTokens
                    if (!fixed.thinking.isNullOrBlank()) dispatchThinking = fixed.thinking
                    dispatchThinkingMs = fixed.thinkingMs
                    if (fixed.promptTokens + fixed.completionTokens > 0) {
                        viewModelScope.launch(Dispatchers.IO) {
                            com.shiyin.music.data.ai.TokenUsageStore.log(
                                getApplication(), llmActiveProvider, "agent_dispatch",
                                fixed.promptTokens, fixed.completionTokens, fixed.cacheTokens,
                            )
                        }
                    }
                    val fixedSkill = fixed.skill
                    if (fixedSkill != null && fixedSkill.key in com.shiyin.music.data.ai.AgentEngine.PLAYBACK_EXEC_SKILLS) {
                        planDraft = com.shiyin.music.data.ai.AgentEngine.IntentPlan(fixedSkill.key, fixedSkill.args, emptyList())
                    } else if (!fixed.chatReply.isNullOrBlank()) {
                        // 它宁可用文字讲明白(例如直接问用户),就照它说的走闲聊分支
                        chatReply = fixed.chatReply
                    }
                    // 都没给 → 保持原计划(按查库执行),不硬造行为
                }

                // 2a. 反问(第三态):模型不确定 → 不执行任何技能,把问题交还用户。
                // v1.3.10: 走**专属选择卡片**(独立状态 agentClarify)——不再落一条 agent 气泡、
                // 也不借用入口建议 chip(用户 2026-09-28 定调:三者是三个东西)。
                if (clarifyReq != null) {
                    android.util.Log.i(
                        "Agent",
                        "clarify 交给用户: q=「${clarifyReq.question}」 opts=${clarifyReq.options}",
                    )
                    // 模型若在调用 clarify 前已流出一段正文(如"这个我得问一下"),保留它;
                    // 只有**空占位**才撤掉,免得对话里留一个空气泡。
                    val streamed = (streamedReply ?: agentMessages.getOrNull(msgIdx)?.text).orEmpty()
                    agentMessages = if (streamed.isNotBlank()) {
                        agentMessages.replaceAt(
                            msgIdx,
                            AgentMessage("agent", streamed, null, thinking = dispatchThinking, thinkingMs = dispatchThinkingMs),
                        )
                    } else {
                        agentMessages.removeAt(msgIdx)
                    }
                    agentClarify = clarifyReq
                    seal()
                    refreshAgentTokens()
                    return@launch
                }
                // 2b. 闲聊:正文已由 onContent 流式写入(打字机数据源已在),这里落最终态。
                if (chatReply != null) {
                    agentMessages = agentMessages.replaceAt(
                        msgIdx,
                        AgentMessage("agent", streamedReply ?: chatReply, null, tokens = routeTokens, thinking = dispatchThinking, thinkingMs = dispatchThinkingMs),
                    )
                    seal()
                    refreshAgentTokens()
                    return@launch
                }
                // 2c. 技能。skill 与 chatReply 双空 = 模型无有效输出——按失败透传,不崩。
                val plan = planDraft ?: run {
                    val err = com.shiyin.music.data.ai.LlmClient.lastError ?: "模型无返回"
                    agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "LLM 无有效回复:$err", steps.toList()))
                    seal()
                    return@launch
                }
                android.util.Log.i("Agent", "plan: skill=${plan.skillKey} args=${plan.args}")
                trailAdd("call ${plan.skillKey} ${plan.args}")
                // 2. 技能解析 + 步骤面板
                val skill = com.shiyin.music.data.ai.AgentEngine.skillByKey(plan.skillKey)
                if (skill == null) {
                    agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "没有可执行技能「${plan.skillKey}」。"))
                    seal()
                    return@launch
                }
                // ── v1.3.12:播放执行的**确定性关卡** ──────────────────────────────────
                // 分工(用户定调,一次纠正后的最终口径):
                //   · **模型照常判断、照常调工具** —— 工具面永远完整,判断权在它手里;
                //   · 运行时只管**副作用**:① 名字既是单曲又是专辑时**不猜**,把选择交回用户
                //     (用已有的 clarify 选择卡片);② 纯感想被当成播放命令时拦下;
                //   · **幂等不在这一层** —— "同一首已经在放就别重启"是**技能**的职责(它知道
                //     真实播放状态,由它说那句人话),这里不再替模型生成任何"回复文案";
                //   · 需要用户拿主意时**追问**,不吐兜底句式(用户明确反感"无限兜底")。
                var execArgs = plan.args
                if (skill.key in com.shiyin.music.data.ai.AgentEngine.PLAYBACK_EXEC_SKILLS) {
                    val guardTarget = plan.args["target"]?.takeIf { it.isNotBlank() }
                        ?: plan.args["title"]?.takeIf { it.isNotBlank() }

                    // ① 用户自己说清楚了(「我说的是单曲」/「放整张专辑」)→ 按他说的定死 scope,
                    //    **不再问一遍**(真机:用户已明说单曲,系统却还弹选择卡片 → 读起来像没在听)。
                    //    模型没填 scope 时以**用户原话**为准(这是确定性判据,不指望弱模型转述)。
                    val userScope = com.shiyin.music.data.ai.PlaybackIntentGuard.userScope(text)
                    if (userScope != null) {
                        execArgs = execArgs + ("scope" to userScope)
                    } else if (plan.args["scope"].isNullOrBlank() && guardTarget != null) {
                        // ② 用户没说、名字又**既是单曲又是专辑** → 问用户,不猜。
                        //    真机场景:「给我播放藤井风的 prema」库里既有单曲 prema 又有专辑 Prema,
                        //    代码(或模型)悄悄选一个,用户只能事后纠正。
                        val albumKey = agentCtx.findAlbum(guardTarget)
                        val exactTrack = agentCtx.searchTracks(guardTarget, 8)
                            .any { com.shiyin.music.data.ai.PlaybackIntentGuard.sameTrack(it.title, guardTarget) }
                        if (albumKey != null && exactTrack) {
                            android.util.Log.i("Agent", "playback ambiguous: 「$guardTarget」既是单曲又是专辑 → 问用户")
                            com.shiyin.music.data.log.UsageLog.event(getApplication(), "Agent", "playback ambiguous ask")
                            agentClarify = com.shiyin.music.data.ai.AgentTools.ClarifyRequest(
                                question = "「$guardTarget」在库里既是单曲也是专辑,放哪个?",
                                options = listOf("播放单曲 $guardTarget", "播放整张专辑 $guardTarget"),
                            )
                            val streamed0 = (streamedReply ?: agentMessages.getOrNull(msgIdx)?.text).orEmpty()
                            if (streamed0.isBlank()) agentMessages = agentMessages.removeAt(msgIdx)
                            seal()
                            refreshAgentTokens()
                            return@launch
                        }
                    }

                    // ② 用户明确要求"重来" → 把 restart 注进参数。这是**补全信息**(技能看不到
                    //    用户原话),不是否决模型:让它有能力区分"播放"与"从头再放"。
                    if (com.shiyin.music.data.ai.PlaybackIntentGuard.classify(text) ==
                        com.shiyin.music.data.ai.PlaybackIntentGuard.Intent.RESTART
                    ) {
                        execArgs = execArgs + ("restart" to "true")
                    }

                    // ③ 纯感想/闲聊被当成播放命令 → 拦下。先让模型**用自己的话**回应(工具面完整,
                    //    只加一条 [系统] 说明);它若仍然硬要执行,就把决定权交回用户(**追问**)。
                    if (com.shiyin.music.data.ai.PlaybackIntentGuard.classify(text) ==
                        com.shiyin.music.data.ai.PlaybackIntentGuard.Intent.NONE
                    ) {
                        val note = "[系统] 用户这句话**不是播放指令**(是感想/评论/闲聊)。不要执行任何播放动作,用你自己的话回应他。"
                        android.util.Log.i("Agent", "playback guard: BlockNoIntent · skill=${skill.key} · target=$guardTarget")
                        com.shiyin.music.data.log.UsageLog.event(getApplication(), "Agent", "playback guard BlockNoIntent")
                        val again = com.shiyin.music.data.ai.AgentTools.dispatch(
                            config = config,
                            tavilyKey = tavilyApiKey,
                            userText = text,
                            contextBlock = listOf(agentContext, note)
                                .filter { it.isNotBlank() }.joinToString("\n"),
                            memoryBlock = memoryBlock,
                            forceSearch = agentForceSearch,
                            history = history,
                            onThinking = { th ->
                                mainHandler.post {
                                    if (turnSealed.get()) return@post
                                    intentThinking = th
                                    agentMessages = agentMessages.replaceAt(msgIdx, liveAgentMsg(steps.toList(), thinking = th))
                                }
                            },
                            onContent = { full ->
                                mainHandler.post {
                                    if (turnSealed.get()) return@post
                                    val clean = full.trimStart()
                                    streamedReply = clean
                                    agentMessages = agentMessages.replaceAt(msgIdx, liveAgentMsg(steps.toList(), text = clean))
                                }
                            },
                        )
                        routeTokens = again.promptTokens to again.completionTokens
                        dispatchThinking = again.thinking ?: intentThinking
                        dispatchThinkingMs = again.thinkingMs
                        if (again.promptTokens + again.completionTokens > 0) {
                            viewModelScope.launch(Dispatchers.IO) {
                                com.shiyin.music.data.ai.TokenUsageStore.log(
                                    getApplication(), llmActiveProvider, "agent_dispatch",
                                    again.promptTokens, again.completionTokens, again.cacheTokens,
                                )
                            }
                        }
                        val insisted = again.skill?.key in com.shiyin.music.data.ai.AgentEngine.PLAYBACK_EXEC_SKILLS
                        if (insisted) {
                            // 说了两遍还要执行 → 不再替它硬答(那会显得"无动于衷"),**问用户**。
                            android.util.Log.i("Agent", "playback guard: 模型仍要执行 → 交回用户(clarify)")
                            agentClarify = com.shiyin.music.data.ai.AgentTools.ClarifyRequest(
                                question = "这句要不要当成播放指令?",
                                options = listOf("播放它", "不用放,只是聊聊"),
                            )
                            val streamed1 = streamedReply?.takeIf { it.isNotBlank() }
                                ?: agentMessages.getOrNull(msgIdx)?.text?.takeIf { it.isNotBlank() }
                            if (streamed1 == null) agentMessages = agentMessages.removeAt(msgIdx)
                            else agentMessages = agentMessages.replaceAt(
                                msgIdx,
                                AgentMessage("agent", streamed1, null, thinking = dispatchThinking, thinkingMs = dispatchThinkingMs),
                            )
                            seal()
                            refreshAgentTokens()
                            return@launch
                        }
                        // 模型按说明用了自己的话 → 正常落终态(它说人话,不是运行时模板)
                        agentMessages = agentMessages.replaceAt(
                            msgIdx,
                            AgentMessage(
                                "agent",
                                again.chatReply?.takeIf { it.isNotBlank() }
                                    ?: streamedReply.orEmpty(),
                                null, tokens = routeTokens,
                                thinking = dispatchThinking, thinkingMs = dispatchThinkingMs,
                            ),
                        )
                        seal()
                        refreshAgentTokens()
                        return@launch
                    }
                }
                steps.clear()
                // v1.3.8(P2): 步骤清单由**技能自己声明**(panelSteps),面板行 id == 技能回调
                // 的语义 id——映射表不复存在。行文案也不再用模型生成的 plan.steps(§3 定调),
                // "模型给 2 步、技能有 4 个回调"这类错位从根上消失。
                for (ps in skill.panelSteps(execArgs)) {
                    steps.add(com.shiyin.music.data.ai.AgentEngine.Step(ps.id, ps.label, com.shiyin.music.data.ai.AgentEngine.StepStatus.PENDING))
                }
                agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "", steps.toList(), thinking = intentThinking))
                // v1.3.3b review#B7: lastTokens 原来恒 null(声明后从未赋值),"本次 N tokens"
                // 从不显示——改记路由调用的真实 usage,技能内调用靠 refreshAgentTokens 累计。
                val lastTokens: Pair<Int, Int>? = routeTokens
                val result = skill.execute(
                    agentCtx, execArgs, config, tavilyApiKey,
                ) { id, st ->
                    // v1.3.8(P2): 语义 id 直接就是面板行 id,无需任何映射。万一技能回调了
                    // 未在 panelSteps 里声明的 id(技能改了回调没改声明),落到最后一行——
                    // 保证进度仍可见,且**不追加裸 id 行**污染面板。
                    val effectiveRow = if (steps.any { it.id == id }) id
                        else steps.lastOrNull()?.id ?: id
                    // v1.3.3: 联网搜证没搜到资料 → 行内给"未搜到相关资料"而不是空 ✗
                    // (upsert 自动取的 lastError 已被业务路径清空)。
                    val bizErr = if (st == com.shiyin.music.data.ai.AgentEngine.StepStatus.FAILED && id == "search")
                        "未搜到相关资料" else null
                    // v1.3.3b review#B2: 技能协程在 IO 线程回调——state 写入投递主线程。
                    mainHandler.post {
                        if (turnSealed.get()) return@post
                        steps.upsert(effectiveRow, id, st, explicitError = bizErr)
                        agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage("agent", "", steps.toList(), lastTokens))
                    }
                }
                // v1.3.15:工具返回也记一笔(导出对话时能还原"模型调了什么 → 系统返回了什么")
                trailAdd("result ${skill.key} ${result.summary.lineSequence().firstOrNull().orEmpty().take(160)}")
                // v1.3.12:记下"上一轮做了什么"——下一轮会被摆进 [上一轮动作] 让模型知道
                // 这件事已经成功完成(真机 bug:模型看不到"已经放过",就把感想读成"再放一遍")。
                lastAgentAction = AgentAction(
                    kind = humanActionLabel(skill.key),
                    detail = (plan.args["target"] ?: plan.args["title"] ?: plan.args["album"]
                        ?: plan.args["action"] ?: plan.args["artist"] ?: "").trim(),
                    success = result.success,
                )
                // v1.3.x 使用日志:记"用了哪个技能、成没成",**不记用户的提问与回复内容**。
                com.shiyin.music.data.log.UsageLog.event(
                    getApplication(), "Agent",
                    "技能 ${skill.key} · ${if (result.success) "成功" else "失败"}",
                )
                // 记 tokens(意图理解 + 技能内 LLM 调用累计由 LlmClient 不自动汇总,
                // 这里用一个近似:从 TokenUsageStore 读增量。简化:记录本次为 0 占位,
                // 真实累计靠 refreshAgentTokens 从 DB 读)。
                // v1.3.5: 面板清扫——技能 return 后仍可能有行停在 RUNNING(mainHandler
                // 排队的 post 没到/技能路径漏回调,用户看到的"联网核实一直在滚、
                // 写回永远 ○")。收尾时强制终态:还 RUNNING 的标 DONE(技能都 return 了,
                // 说明流程走完)、没跑过的 PENDING 行从面板移除(不误导"还有事没做")。
                // IO 协程里同步改 steps + 主线程写消息,一次落定。
                //
                // v1.3.14:终态规则收在纯函数里(见 StepStatusFinalizer,有单测)——
                // 成功 → 进行中的行标 DONE;失败 → 标 FAILED 且保证至少一行 FAILED,
                // 但**不覆盖**已被技能/服务标出的真实失败行;PENDING 行移除。
                val finalized = com.shiyin.music.data.ai.finalizeStepStatuses(steps.toList(), result.success)
                steps.clear()
                steps.addAll(finalized)
                // v1.3.3: 最终消息带上思考链(意图理解 + 技能内 LLM 调用,取最全的一条)+ 真实耗时。
                val finalThinking = result.thinking ?: intentThinking
                // v1.3.9: 空正文兜底——技能成功返回但 summary 为空时,绝不让气泡停在
                // 只有步骤的状态(用户看到的"卡住"):如实给出可读的收尾文案。
                val finalText = result.summary.ifBlank {
                    android.util.Log.w("Agent", "技能 ${skill.key} 成功返回但正文为空,使用兜底文案")
                    "处理完了,但这次没有生成可显示的正文。请换个说法或重试一次。"
                }
                // v1.3.8: 最终消息包含完整 processHistory(方案C折叠式)
                agentMessages = agentMessages.replaceAt(msgIdx, AgentMessage(
                    "agent", finalText, steps.toList(), lastTokens,
                    thinking = finalThinking, thinkingMs = dispatchThinkingMs,
                    processHistory = processHistory.toList(),
                ))
                // 终态落钉:此后的中间态回调(迟到的 mainHandler.post)一律作废。
                seal()
                // v1.3.13:技能给了**可点选项**(如"你自己挑一首"的几首候选)→ 直接弹选择卡片。
                // 用户定调:智能助手要让用户**少输入** —— 列了 A/B/C 还让人打字说歌名是很蠢的交互;
                // 点一下就把那首放上。复用既有 clarify 卡片(点选 → 发一条消息 → 正常链路执行)。
                if (result.options.isNotEmpty()) {
                    android.util.Log.i("Agent", "技能 ${skill.key} 给了 ${result.options.size} 个可点选项")
                    agentClarify = com.shiyin.music.data.ai.AgentTools.ClarifyRequest(
                        question = "想听哪一首?",
                        // v1.3.15:上限 5(与推荐卡的候选条数一致);副文案随选项一起给
                        // ("每项有理由"——理由显示在选项下方,点选发出的仍是干净的指令句)。
                        options = result.options.take(5),
                        optionNotes = result.optionNotes.take(5),
                    )
                }
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
                seal()
            } finally {
                agentRunning = false
                // v1.3.9: 一轮结束落库对话（完成/失败/被打断都在此收口）。
                persistAgentConversation()
                // v1.3.10 需求 A21(P2):**回合后记忆提取**(兜底)——独立单用途调用判断这轮有没有
                // 值得长期记住的东西,再交 MemoryManager 的确定性规则裁决落库。
                // 放在 finally:闲聊/技能/clarify/失败四条路径都会跑到。
                // v1.3.11:回复前流程已经处理过的轮次(显式「记住」/ 自我介绍类)不再重复提取,
                // 免得同一轮花两次调用、也不会让状态在回复后又被抽屉里改一次。
                if (memoryExplicit == null && !memoryPreExtract) {
                    extractMemories(text, agentMessages.getOrNull(msgIdx)?.text.orEmpty())
                }
            }
        }
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
        /** v1.3.11:记忆块——回退链路必须与主链路拿到同一份记忆,否则同一句话两个答案。 */
        memoryBlock: String = "",
        onState: (thinking: String?, streamedReply: String?) -> Unit = { _, _ -> },
    ): com.shiyin.music.data.ai.AgentEngine.Route? {
        // v1.3.4: onThinking 传的是增量碎片——live 展开要看完整链,本地累积;
        // null=重试轮开始,清空重攒(重试成功会从头重发全量)。
        var thinkAcc: String? = null
        // v1.3.13:同样"保号更新"——回退链路也要把已有的正文/思考链带上,否则一有中间态
        // 就会把另一块抹掉(与主链路同一个真机 bug)。
        var routeText: String? = null
        fun routeMsg(stepsNow: List<com.shiyin.music.data.ai.AgentEngine.Step>) =
            AgentMessage("agent", routeText.orEmpty(), stepsNow, thinking = thinkAcc)
        return com.shiyin.music.data.ai.AgentEngine.route(
            text, agentCtx, config,
            onStep = { _, st ->
                // 路由阶段不显示"分析意图"步骤行(与思考胶囊两套表达,同时出现动画
                // 不统一);失败才落面板行透传原因。
                if (st == com.shiyin.music.data.ai.AgentEngine.StepStatus.FAILED) {
                    mainHandler.post {
                        steps.upsert("intent", "思考中", st)
                        agentMessages = agentMessages.replaceAt(msgIdx, routeMsg(steps.toList()))
                    }
                }
            },
            history = history,
            webContext = webContext,
            memoryBlock = memoryBlock,
            onThinking = { th ->
                thinkAcc = if (th == null) "" else (thinkAcc ?: "") + th
                mainHandler.post {
                    onState(thinkAcc, null)
                    agentMessages = agentMessages.replaceAt(msgIdx, routeMsg(steps.toList()))
                }
            },
            onContent = { partial ->
                // sensenova 等模型的回复常以 \n\n 开头——不 trim 气泡顶会渲染一整行空白。
                mainHandler.post {
                    val clean = partial.trimStart()
                    routeText = clean
                    onState(null, clean)
                    agentMessages = agentMessages.replaceAt(msgIdx, routeMsg(steps.toList()))
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
        /** v1.4.0: 可展开正文。传 null 表示"本次不改动它"（状态刷新不能把已写的正文冲掉）。 */
        detail: String? = null,
    ) {
        val i = indexOfFirst { it.id == id }
        val err = if (status == com.shiyin.music.data.ai.AgentEngine.StepStatus.FAILED)
            explicitError ?: com.shiyin.music.data.ai.LlmClient.lastError
        else null
        if (i >= 0) {
            val old = this[i]
            set(i, com.shiyin.music.data.ai.AgentEngine.Step(id, old.label, status, err, detail ?: old.detail))
        } else {
            add(com.shiyin.music.data.ai.AgentEngine.Step(id, label, status, err, detail))
        }
    }

    /** 对话消息:替换指定位置的那条(步骤面板逐帧更新用)。 */
    private fun List<AgentMessage>.replaceAt(idx: Int, m: AgentMessage): List<AgentMessage> =
        mapIndexed { i, old -> if (i == idx) m else old }

    /** v1.3.10: 撤掉一条消息(越界安全)。clarify 走独立卡片、不落气泡时用来清掉空占位,
     *  免得对话里留下一个空气泡。 */
    private fun List<AgentMessage>.removeAt(idx: Int): List<AgentMessage> =
        if (idx in indices) toMutableList().apply { removeAt(idx) } else this

    /** 打开/开关 Agent 页(侧边栏/专辑菜单)。agentOpen 公开可写,UI 直接赋值。 */

    // v1.3.2: 专辑菜单的 Agent 入口——不自动执行排序,而是打开对话页打招呼 +
    // 给出针对该专辑的建议回复,用户点建议或自行输入(Agent 不局限于排序一种处理)。
    fun clearAgentSuggestions() { agentSuggestions = emptyList() }

    /**
     * v1.3.10: 用户点选了选择卡片里的某一项——**原样**作为用户消息发出,卡片收起。
     * 不发系统包装文案:对话记录里出现的就应该是用户当时选的那句话,历史才读得通。
     */
    fun chooseAgentClarify(option: String) {
        if (agentClarify == null) return
        agentClarify = null
        sendAgentMessage(option)
    }

    /**
     * v1.3.10: 关闭或跳过卡片(× / 「跳过」)。不发任何内容。
     *
     * 这里**不需要**额外"解除等待"——clarify 那一轮模型已经返回(agentRunning=false),
     * 对话本来就是可继续的;清掉状态就够了,不存在"卡在等待选择"。
     */
    fun dismissAgentClarify() {
        agentClarify = null
    }

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
    // v22: 拆分曲目（合成 id）不参与重复检测 —— 它们共享同一个物理文件，按
    // 「曲名|歌手」分组时会把用户有意拆出来的曲目误判成重复文件。
    fun dupGroups(): List<List<Track>> = lib()
        .filterNot { com.shiyin.music.data.split.SplitIds.isSynthetic(it.id) }
        .groupBy { it.title + "|" + it.artist }
        .values.filter { it.size > 1 }
        .map { g -> g.sortedBy { it.dateAdded } }

    /** v22: 拆分曲目同样排除在"短音频"之外 —— 拆出来的 intro/outro 是专辑的有机
     *  组成，不是待清理的垃圾音频（用户明确：这类曲目要计数，只是不当热度标准）。 */
    fun shortTracks(): List<Track> = lib()
        .filterNot { com.shiyin.music.data.split.SplitIds.isSynthetic(it.id) }
        .filter { it.durationSec in 1..30 }

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

    /**
     * v1.5.0 B2①：触发媒体库核对。遍历范围（库里已知目录 + 
     * [com.shiyin.music.data.ReindexPlanner.CURATED_DIRS]，存储根只扫一层）由 ReindexPlanner 统一规划，
     * 这里不再自己探测「哪些标准目录存在」—— 目录不存在时遍历天然是 no-op。
     */
    private suspend fun forceReindexKnownFolders() {
        val t0 = System.currentTimeMillis()
        android.util.Log.d("ScanTrace", "forceReindexKnownFolders: knownFolders=${knownFolders.size}")
        com.shiyin.music.data.MediaScanner.forceReindex(getApplication(), knownFolders)
        android.util.Log.d("ScanTrace", "forceReindexKnownFolders: returned ${System.currentTimeMillis() - t0}ms")
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
            val tStart = System.currentTimeMillis()
            // v1.5.0 B2①(2)：顺序反转 —— 旧实现先 walkTopDown 遍历全库文件 + 逐个 scanFile，
            // 再查 MediaStore（2026-10-07 真机实测该前置阶段 29.6s：walk 17.2s + scanFile 等待
            // 12.4s），进度环空转几十秒才出现第一首歌。现在先出结果，媒体库核对挪到后台，
            // 核对后签名确实变了才刷新列表。
            val result = MediaScanner.scan(getApplication()) { count, folder ->
                if (count == 1) android.util.Log.d("ScanTrace", "startScan: first MediaStore row at +${System.currentTimeMillis() - tStart}ms")
                withContext(Dispatchers.Main) {
                    scanCount = count
                    scanFolder = folder
                }
            }
            android.util.Log.d("ScanTrace", "startScan: MediaStore scan done +${System.currentTimeMillis() - tStart}ms tracks=${result.size}")
            adoptScanResult(result)
            val l = lib()
            scanResultFolders = l.groupBy { it.folder }.map { (p, ts) -> p to ts.size }
            scanDupGroups = dupGroups().size
            scanShortCount = shortTracks().size
            scanFolder = "整理结果中…"
            kotlinx.coroutines.delay(600)
            obStage = ObStage.Done
            // 后台核对媒体库（真机实测约 20-30s）。UI 此时已可用，只有确实收进了新文件才重查刷新。
            launch(Dispatchers.IO) { reindexThenRefreshIfChanged() }
        }
    }

    /**
     * v1.5.0 B2①：接管一次扫描结果（内存态 / 缓存 / 歌手实体 / 新专辑提醒）。
     * 首扫与「后台核对发现变化后刷新」共用同一条路径，避免两套写法漂移。调用方须在主线程。
     */
    private suspend fun adoptScanResult(result: List<Track>) {
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
        if (!firstScanDone) settingsStore.setFirstScanDone(true)
        resyncQueue()
    }

    /**
     * v1.5.0 B2①：先查后核。在后台强制媒体库重新索引（把 app 之外新增、MediaStore 漏收的
     * 文件收进来），只有签名确实变了才重查一次并刷新列表 —— 没变化就不做多余的全量重查。
     */
    private suspend fun reindexThenRefreshIfChanged() {
        val t0 = System.currentTimeMillis()
        val before = runCatching { com.shiyin.music.data.MediaScanner.signature(getApplication()) }.getOrNull()
        forceReindexKnownFolders()
        val after = runCatching { com.shiyin.music.data.MediaScanner.signature(getApplication()) }.getOrNull()
        android.util.Log.d("ScanTrace", "background reindex done +${System.currentTimeMillis() - t0}ms sig $before -> $after")
        if (before == null || after == null || before == after) return
        val refreshed = com.shiyin.music.data.MediaScanner.scan(getApplication(), paced = false)
        android.util.Log.d("ScanTrace", "background reindex found changes → re-query tracks=${refreshed.size}")
        withContext(Dispatchers.Main) { adoptScanResult(refreshed) }
    }

    fun rescanSilently() {
        viewModelScope.launch {
            // v1.2.0 阶段二：增量扫描。先查 MediaStore 轻量签名（count + max date_added），
            // 与上次相同 → 库无增删 → 复用持久化的 tracksRaw，跳过全量 scan 的逐行 Track 构造。
            // 不同 → 全量 scan + 落盘新缓存 + 更新签名。签名查询只取 2 列不构造 Track，远快于全量。
            val newSig = try { com.shiyin.music.data.MediaScanner.signature(getApplication()) } catch (_: Exception) { "0|0" }
            val cachedSig = trackCache.readSignature()
            // v1.5.0 B2①：签名命中＝库无增删 → 复用缓存，并跳过随后的后台 forceReindex。
            val sigMissed = !(cachedSig != null && cachedSig == newSig)
            val result: List<Track> = if (!sigMissed) {
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
            // v1.5.0 B2①：仅签名 MISS（库确有可能变化）时才做——旧实现每次冷启动无条件
            // walkTopDown 遍历全库文件并逐个 scanFile（等最多 60s），是冷启动的主要后台成本。
            // 签名命中＝库无增删，无需重建 MediaStore 索引。手动兜底：设置→数据管理→
            // 「重新扫描文件」走 startScan()，内含 forceReindexKnownFolders。
            if (sigMissed && knownFolders.isNotEmpty()) {
                android.util.Log.d("ScanTrace", "forceReindex scheduled (sig MISS) folders=${knownFolders.size}")
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
        // v1.5.0 伴唱 §8：切歌复位到原调。按 track id 判定「真的换曲了」，
        // 单曲循环重播同一首不会把用户刚调好的 Key 清掉。
        if (id != lastKeyShiftTrackId) {
            lastKeyShiftTrackId = id
            if (keyShift != 0) keyShift = 0
        }
        // v2: 恢复全局播放速度设置
        player.setPlaybackSpeed(playbackSpeed, retroSpeedMode)
        player.setKeyShift(keyShift)
        appScope.launch { settingsStore.pushRecent(id) }
        // v1.2.1: 计数口径"累计满 30s"。插一条 completed=0 的 play_event,拿回行 id 回填到
        // session,使后续 markCounted/finalize 按主键 id 精确定位(不靠"mediaId 最新行"子查询,
        // 避免同 mediaId 切歌/单曲循环并发 insertPlayEvent 时写错行)。用 appScope:Activity 销毁后
        // player 仍在后台播,DB 写不能随 viewModelScope 死。
        appScope.launch(Dispatchers.IO) {
            try {
                val t = trackById(id) ?: return@launch
                // v22: 两套统计口径的隔离位。「完整专辑」容器 → kind='album'
                // （只计入专辑播放次数）；拆分单曲与普通歌曲 → kind='track'。
                // 两条口径的 mediaId 值域也不相交，双重保证不混淆、不重复计数。
                val kind = if (isAlbumContainer(id))
                    com.shiyin.music.data.split.PlayKind.ALBUM
                else com.shiyin.music.data.split.PlayKind.TRACK
                val rowId = dao.insertPlayEvent(
                    com.shiyin.music.data.db.PlayEventEntity(
                        mediaId = id,
                        playedAt = System.currentTimeMillis(),
                        durationSec = (t.durationMs / 1000).toInt(),
                        completed = false,
                        kind = kind,
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
                // v1.3.4: MediaController 只能在创建它的线程(主线程)上调用。本协程跑在
                // Dispatchers.IO,直调会抛 IllegalStateException 并被下面的 catch 静默吞掉
                // ——封面推送实际从未生效(系统控制台/灵动岛因此一直空白)。切回主线程再推。
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    player.updateArtworkData(bytes, forMediaId = id, artworkUri = artworkUri)
                }
            } catch (e: Exception) {
                // 不静默吞异常:推送失败必须留痕,否则这类"封面没上系统栏"的问题无从排查。
                android.util.Log.w("MediaArt", "cover push failed #$id: ${e.javaClass.simpleName}")
            }
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
        // v22: 同一整轨文件的相邻拆分曲目合并成一个条目 → 播到区间边界无缝续播下一首。
        player.playQueue(collapseSplitRuns(queue, id), id, null)
    }

    /**
     * v1.4.0: 用**给定列表**作播放队列（搜索页用：队列 = 搜索结果，不是整个音乐库）。
     *
     * 原来搜索页调 [play]，而 [play] 的队列是整库/筛选后的整库排序——于是搜一首歌放完，
     * 下一首接到的是主列表里的下一首，跟搜索词八竿子打不着（用户实测反馈）。
     */
    fun playFromList(tracks: List<Track>, startId: Long) {
        if (tracks.isEmpty()) return
        player.playQueue(collapseSplitRuns(tracks, startId), startId, null)
    }

    /** Play a track from a content URI (e.g. from a system VIEW intent). */
    fun playUri(uri: android.net.Uri) {
        val s = uri.toString()
        // Find matching track in the library
        val track = lib().firstOrNull { it.uri.toString() == s || it.dataPath == s }
        if (track != null) {
            play(track.id)
            playerOpen = true
            return
        }
        // v22: 整轨文件被拆分后就不在 lib() 里了（被替换成 N 首拆分单曲）。
        // 从文件管理器 / 其他 App 打开这个文件，语义上就是「播放完整专辑」——
        // 走容器曲目（容器模式：从头播到尾，计专辑播放次数，不触发任何单曲切换）。
        val physical = physicalTracks().firstOrNull { it.uri.toString() == s || it.dataPath == s }
        if (physical != null) {
            val containerId = com.shiyin.music.data.split.SplitIds.containerId(physical.id)
            val container = trackById(containerId)
            if (container != null) {
                player.setShuffle(false)
                player.playQueue(listOf(container), containerId, null)
            } else {
                play(physical.id)
            }
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
        // v22: 拆分专辑有两条播放入口，语义必须分开（需求三/四）：
        //   · 无 startId（专辑页「播放」按钮 / 顶部「完整专辑」行）→ **容器模式**：
        //     队列只放「完整专辑」容器，从文件开头完整播到结尾，不触发任何单曲切换，
        //     计一次「专辑被播放次数」（kind='album'），绝不产生单曲事件。
        //   · 有 startId（点第 N 行拆分单曲）→ **单曲模式**：队列放那首虚拟曲目
        //     （uri 指向整轨文件）并携带方案；播到区间边界无缝切下一首，各自计单曲播放。
        splitAlbumQueue(key, startId)?.let { (queue, start) ->
            player.setShuffle(false)
            player.playQueue(queue, start, "album:$key")
            return
        }
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
        player.playQueue(collapseSplitRuns(queue, realStart), realStart, "album:$key")
    }

    /**
     * v22: 拆分专辑的队列构造。
     *
     * 返回 (队列, 起始 id)；该专辑不适用拆分播放时返回 null，交回常规逻辑。
     *
     * ⚠️ 只在该专辑**完全由同一个整轨文件的拆分曲目组成**时接管。若专辑里混着别的
     * 非拆分曲目（用户先拆了单文件专辑、又往这张专辑迁进了别的歌），容器模式会只播
     * 那个整轨文件、把其他曲目整条漏掉 —— 这种情况交回常规逻辑（[collapseSplitRuns]
     * 仍会把同一文件的连续拆分曲目合并成一条，听感依然连续）。
     */
    private fun splitAlbumQueue(key: String, startId: Long?): Pair<List<Track>, Long>? {
        val ts = albumOrder(key)
        if (ts.isEmpty()) return null
        val srcId = splitSourceOfAlbum(key) ?: return null
        if (ts.any { !isSplitTrack(it.id) }) return null
        // 全部曲目必须来自同一个源文件
        val allSameSource = ts.all { t ->
            val row = splitTracks.firstOrNull { it.id == t.id - com.shiyin.music.data.split.SplitIds.TRACK_BASE }
            row?.sourceMediaId == srcId
        }
        if (!allSameSource) return null
        val plan = splitPlanOf(srcId) ?: return null
        val container = trackById(plan.containerId) ?: return null
        // 无起点（专辑页「播放」/ 顶部「完整专辑」行）→ 容器模式：从头播到尾，
        // 不触发任何单曲切换，计一次「专辑被播放次数」。
        val start = startId?.takeIf { isSplitTrack(it) }
        if (start == null) return listOf(container) to container.id
        // v22 修补(用户反馈⑥"播放列表只能一首"):单曲模式队列放**整组拆分单曲**
        // (从点击那首起、按专辑页顺序到最后一首)——原实现只放一首,播放列表只见
        // 一条、插入队列的歌排在唯一 item 后面且 SplitCarry 无缝切换永不前进到
        // 下一个队列项。现在整组进队列:播放列表完整显示;插到队列的歌正常排队;
        // 拆分顺序 = 队列顺序(albumOrder 已按用户编辑页的顺序),到区间边界时
        // checkVirtualBoundary 的 replaceMediaItem 与队列推进自洽(同一首),无缝
        // 由 alignToSplitStartIfNeeded 对齐起点。起点前的曲目不进队列——用户点的
        // 是从第 N 首开始听,前面几首不属于本次会话。
        val startIdx = ts.indexOfFirst { it.id == start }
        val vt = ts.getOrNull(startIdx) ?: return listOf(container) to container.id
        val queue = ts.drop(startIdx.coerceAtLeast(0))
        return queue to vt.id
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
        player.playQueue(collapseSplitRuns(queue, realStart), realStart, "artist:$name")
    }

    /**
     * v1.3.10 需求 A15:歌手页「热门」行点播——队列必须与**列表展示顺序一致**(热度降序)。
     *
     * 旧行为(用户报的 bug):热门行点击走 [playArtist] → 队列是 [artistQueue](专辑曲序),
     * 于是"点了第 3 首热门,下一首却是专辑里的下一首"——听感就是"不按热门队列播放,乱来"。
     *
     * 排序口径与歌手页展示用**同一个表达式**(heatCountFor 降序),两边不会漂移;
     * 队列 key 用 `artisthot:` 前缀,让 [resyncQueue] 也按热度序重建——否则库一变(扫描/改名)
     * 队列会翻回专辑序,播放中途顺序突变。
     */
    fun playArtistHot(name: String, startId: Long) {
        val tracks = artistQueue(name).sortedByDescending { heatCountFor(it.id) }
        // 与 playArtist 同一套可见性处理:点中的是隐藏曲 → 保留它、滤掉其余隐藏曲
        val tappedHidden = isHidden(startId)
        val queue = if (tappedHidden) tracks.filter { it.id == startId || !isHidden(it.id) }
        else playbackFiltered(tracks)
        if (queue.isEmpty()) return
        val realStart = startId.takeIf { id -> queue.any { it.id == id } } ?: queue.first().id
        player.setShuffle(false)
        player.playQueue(collapseSplitRuns(queue, realStart), realStart, "artisthot:$name")
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
        val start = tracks.random().id
        player.setShuffle(true)
        player.playQueue(collapseSplitRuns(tracks, start), start, queueKey)
    }

    /** v1.2.0 #6: 顺序播放整个歌单（从 startId 起；null=从头播）。歌单详情页"播放"用。 */
    fun playPlaylist(pid: String, startId: Long? = null) {
        val all = playlistTrackList(pid)
        val tappedHidden = startId != null && isHidden(startId)
        val queue = if (tappedHidden) all.filter { it.id == startId || !isHidden(it.id) } else playbackFiltered(all)
        if (queue.isEmpty()) return
        val realStart = startId?.takeIf { id -> queue.any { it.id == id } } ?: queue.first().id
        player.setShuffle(false)
        player.playQueue(collapseSplitRuns(queue, realStart), realStart, "playlist:$pid")
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

    /**
     * v22: 从相册选图设专辑封面 —— **先把图片落盘，再存 file:// 路径**。
     *
     * 修的问题：原来直接把 photo picker 返回的 `content://` URI 存进 coverUri。
     * 系统给这种 URI 的读权限是**临时的** —— 官方 Photo Picker 文档原文：
     * "By default, the system grants your app access to media files **until the device is
     * restarted**"。于是用户重启手机后封面就读不出来了（ArtCache.decodeUri 的
     * loadThumbnail 抛 SecurityException → 回退 openInputStream 也失败 → 返回 null → 裂图）。
     *
     * 落盘后：重启 / 清缓存都不受影响，而且图片文件能随备份一起走。
     * 与歌手写真 [setArtistImageFromFile] 走**同一条既有范式**，不是新造的路。
     *
     * ⚠️ 只**读取**相册里那一张图 + 在 app 私有目录写一份副本：
     * 不修改 / 不移动 / 不删除相册原图，也完全不碰任何音乐文件。
     *
     * 落库仍复用 [saveAlbumCover]，保证写入逻辑只有一份、不会写歪。
     */
    fun setAlbumCoverFromFile(albumId: Long, contentUri: android.net.Uri) {
        if (albumId <= 0) return
        viewModelScope.launch {
            val fileUri: String? = withContext(Dispatchers.IO) {
                runCatching {
                    val ctx = getApplication<android.app.Application>()
                    val dir = java.io.File(ctx.filesDir, "album_cover_override").apply { mkdirs() }
                    // 同一张专辑只保留最新一份副本（旧副本是这次覆盖之前的，已无引用）
                    dir.listFiles()?.filter { it.name.startsWith("album_${albumId}_") }?.forEach { it.delete() }
                    val file = java.io.File(dir, "album_${albumId}_${System.currentTimeMillis()}.jpg")
                    val input = ctx.contentResolver.openInputStream(contentUri)
                        ?: return@runCatching null
                    input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
                    "file://${file.absolutePath}"
                }.getOrNull()
            }
            if (fileUri == null) {
                showToast("封面保存失败", null)
                return@launch
            }
            saveAlbumCover(albumId, fileUri)
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

    /** v1.3.x 反馈:保存到歌单弹窗的「新建歌单」——建完直接把当前歌加进去,
     *  不用先建再回来点。与 createPlaylist() 同命名规则,但带"立即加入"语义。 */
    fun createPlaylistAndAdd(trackId: Long) {
        val now = System.currentTimeMillis()
        val id = "p${now}"
        val name = "新歌单 #${playlists.size + 1}"
        viewModelScope.launch(Dispatchers.IO) {
            dao.upsertPlaylist(com.shiyin.music.data.db.PlaylistEntity(id, name, playlists.size))
            dao.addToPlaylist(com.shiyin.music.data.db.PlaylistTrackEntity(id, trackId, now))
        }
        showToast("已创建「$name」并加入当前歌曲", null)
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
            // Create/update artist entities and song_artist links (v1.5.0 B2④: 事务批量)
            val (kept, names) = com.shiyin.music.data.ArtistWritePlanner.filterPairs(pairs, albumNames)
            if (kept.isEmpty()) return@launch
            try {
                db.withTransaction {
                    val existing = dao.artistsByNames(names).map { it.name }.toHashSet()
                    val missing = names.filter { it !in existing }
                    if (missing.isNotEmpty()) {
                        dao.insertArtistsIgnore(
                            missing.map { com.shiyin.music.data.db.ArtistEntity(name = it, updatedAt = now) }
                        )
                    }
                    val idByName = dao.artistsByNames(names).associate { it.name to it.id }
                    val links = com.shiyin.music.data.ArtistWritePlanner.buildLinks(kept, idByName)
                    if (links.isNotEmpty()) {
                        dao.upsertSongArtists(
                            links.map { (trackId, artistId) ->
                                com.shiyin.music.data.db.SongArtistEntity(trackId, artistId)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ScanTrace", "ensureArtistEntities batch failed: ${e.message}")
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

    /**
     * v22: 挡掉拆分相关的合成 id。
     *
     * 拆分单曲 / 完整专辑容器的 id 是 App 内部合成的，不对应任何 MediaStore 记录 ——
     * 拿它去 `ContentUris.withAppendedId` 会构造出不存在的 URI，轻则 MediaStore 抛异常，
     * 重则让系统删错文件。而且拆分曲目共享同一个物理文件，"删一首"在语义上就说不通：
     * 曲目菜单已不提供该入口（用户定调：不给单曲级删除），这里再兜一道，返回实际
     * 可操作的 id 集合。
     */
    private fun realMediaIdsOnly(ids: List<Long>): List<Long> {
        val real = ids.filterNot { com.shiyin.music.data.split.SplitIds.isSynthetic(it) }
        if (real.size != ids.size) {
            showToast("拆分曲目不能单独删除，请在专辑菜单删除整轨文件", null)
        }
        return real
    }

    fun requestTrash(ids: List<Long>) {
        val real = realMediaIdsOnly(ids)
        if (real.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val pi = MediaStore.createTrashRequest(resolver, real.map(::uriOf), true)
        pendingTrashOp = TrashOp.Trash(real)
        launchTrashIntent?.invoke(pi)
    }

    fun requestRestore(ids: List<Long>) {
        val real = realMediaIdsOnly(ids)
        if (real.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val pi = MediaStore.createTrashRequest(resolver, real.map(::uriOf), false)
        pendingTrashOp = TrashOp.Restore(real)
        launchTrashIntent?.invoke(pi)
    }

    fun requestEmptyTrash() {
        val ids = realMediaIdsOnly(trashMirror.map { it.mediaId })
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
                    closeLyricsInstant()
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

    /**
     * v1.4.0: 主题三档（「系统主题」/ 浅色 / 深色）。落到两个既有键上：
     * 跟随开关 + 浅深选择。选浅/深时**同时关掉跟随**（否则选择不会生效）。
     */
    fun setThemeMode(mode: String) = viewModelScope.launch {
        when (mode) {
            com.shiyin.music.data.ThemeMirror.MODE_SYSTEM ->
                settingsStore.setThemeFollowSystem(true)
            com.shiyin.music.data.ThemeMirror.MODE_DARK -> {
                settingsStore.setThemeFollowSystem(false)
                settingsStore.setDark(true)
            }
            else -> {
                settingsStore.setThemeFollowSystem(false)
                settingsStore.setDark(false)
            }
        }
    }
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

    /**
     * v1.5.0 伴唱 §8：升降 Key。与分离完全解耦（伴奏未就绪也能改），不触发重新分离。
     * 按规格不持久化：App 重启即回原调。
     */
    fun changeKeyShift(semitones: Int) {
        keyShift = com.shiyin.music.playback.KeyShift.clamp(semitones)
        player.setKeyShift(keyShift)
    }

    /**
     * v1.5.0 伴唱按钮点击。分离引擎未接入前：点击 = 进入 Loading（底色动画 + 圆周弧），
     * 再点 = 取消回 Idle —— 先让用户看到效果，且不假装已经分离完成。
     */
    fun toggleKaraoke() {
        karaokeState = if (karaokeState == com.shiyin.music.ui.components.KaraokeButtonState.Idle) com.shiyin.music.ui.components.KaraokeButtonState.Loading else com.shiyin.music.ui.components.KaraokeButtonState.Idle
    }

    /** v1.5.0 右滑固定：该条目当前是否已固定。 */
    fun isLibItemPinned(itemKey: String): Boolean = itemKey in pinnedLibItems

    /** v1.5.0 右滑固定：提交固定 / 取消固定（内存态即时生效，落盘异步）。 */
    fun setLibItemPinned(itemKey: String, pinned: Boolean) {
        if (pinned == isLibItemPinned(itemKey)) return
        pinnedLibItems = if (pinned) pinnedLibItems + itemKey else pinnedLibItems - itemKey
        viewModelScope.launch { settingsStore.setPinnedLibItem(itemKey, pinned) }
    }
    fun setDeepSeekKey(v: String) = viewModelScope.launch { settingsStore.setDeepSeekKey(v) }
    // ── v1.3.x 歌词本设置 toggles ───────────────────────────────────────────
    fun setFuriganaDefaultOn(v: Boolean) = viewModelScope.launch { settingsStore.setFuriganaDefaultOn(v) }
    fun setFuriganaAutoVerify(v: Boolean) = viewModelScope.launch { settingsStore.setFuriganaAutoVerify(v) }
    fun setTranslateAutoForeign(v: Boolean) = viewModelScope.launch { settingsStore.setTranslateAutoForeign(v) }
    fun setTranslateDefaultShow(v: Boolean) = viewModelScope.launch { settingsStore.setTranslateDefaultShow(v) }
    /** A36: 翻译失败提示开关(默认关)。 */
    fun setTranslateErrorToast(v: Boolean) = viewModelScope.launch { settingsStore.setTranslateErrorToast(v) }
    /** v1.3.10: 「翻译按钮显示」档位。 */
    fun setTranslateButtonMode(mode: String) = viewModelScope.launch { settingsStore.setTranslateButtonMode(mode) }
    /** v1.3.10 需求 A26:时间轴校对入口常显开关。 */
    fun setLyricsCalibAlwaysOn(v: Boolean) = viewModelScope.launch { settingsStore.setLyricsCalibAlwaysOn(v) }

    // ── v1.3.12:拾光 Agent 行为偏好 ─────────────────────────────────────────
    fun setAgentThinkingCollapsed(v: Boolean) = viewModelScope.launch { settingsStore.setAgentThinkingCollapsed(v) }

    fun setAgentForceSearch(v: Boolean) = viewModelScope.launch { settingsStore.setAgentForceSearch(v) }

    fun setMemoryAutoExtract(v: Boolean) = viewModelScope.launch { settingsStore.setMemoryAutoExtract(v) }

    fun setMemoryIncludeTransient(v: Boolean) = viewModelScope.launch { settingsStore.setMemoryIncludeTransient(v) }

    fun setAgentDiagEnabled(v: Boolean) = viewModelScope.launch { settingsStore.setAgentDiagEnabled(v) }

    // ── v1.3.14:「Agent 诊断样本」页(入口在拾光 Agent 设置里) ───────────────
    /** 诊断页开关。 */
    var agentDiagOpen by mutableStateOf(false)
    /** 诊断页当前展示的样本(新的在前)。 */
    var agentDiagSamples by mutableStateOf<List<com.shiyin.music.data.log.AgentDiag.Sample>>(emptyList())
        private set

    /** 进诊断页 / 清空后刷新(读文件,放 IO)。 */
    fun refreshAgentDiag() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = com.shiyin.music.data.log.AgentDiag.readAll(getApplication())
            withContext(Dispatchers.Main) { agentDiagSamples = list }
        }
    }

    /** 清空全部诊断样本(用户手动)。 */
    fun clearAgentDiag() {
        viewModelScope.launch(Dispatchers.IO) {
            com.shiyin.music.data.log.AgentDiag.clear(getApplication())
            refreshAgentDiag()
        }
    }

    /** 「复制全部」用:导出为纯文本(时间/技能/阶段/量/模型回复开头)。 */
    fun agentDiagExportText(): String =
        com.shiyin.music.data.log.AgentDiag.exportText(getApplication())

    /** v1.3.x 播放设置 setter(设置·播放页)。 */
    fun setVolumeNormalize(v: Boolean) = viewModelScope.launch { settingsStore.setVolumeNormalize(v) }
    fun setMonoAudio(v: Boolean) = viewModelScope.launch { settingsStore.setMonoAudio(v) }
    fun setAllowConcurrentPlayback(v: Boolean) = viewModelScope.launch { settingsStore.setAllowConcurrentPlayback(v) }
    fun setEqualizerOn(v: Boolean) = viewModelScope.launch { settingsStore.setEqualizerOn(v) }

    /** 均衡器某频段增益(mB)——设置页滑块拖动时调用(乐观更新内存,异步落库)。 */
    fun setEqualizerBand(index: Int, mB: Int) {
        val cur = equalizerBands
        if (index !in cur.indices) return
        val next = cur.toMutableList().also { it[index] = mB }
        equalizerBands = next
        viewModelScope.launch { settingsStore.setEqualizerBands(next) }
    }
    fun setLyricsColorMode(mode: String) = viewModelScope.launch { settingsStore.setLyricsColorMode(mode) }
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
        // v1.3.6 修补:入口日志——用户反馈"点了没返回,连报错都没有"。加一条最先
        // 打的日志,确认函数被调用了、以及带了多少张专辑进来。之后每一步都有
        // progress 日志,看 logcat 能直接定位卡在哪一步。
        progress("开始:共 ${albums.size} 张专辑")
        val config = activeLlmConfig()
        // v1.3.6 修补:config/tavilyKey 的实时值也打出来——"没配置"的判断依赖
        // activeLlmConfig()/tavilyApiKey,真机上如果它们恰好是空,用户看到的
        // 只有"未配置"而没有上下文。把当时的值一并打印,方便排查。
        android.util.Log.i("Agent", "albumDates[$artistName]: config=${if (config != null) "ok" else "null"} tavilyKey=${tavilyApiKey.isNotBlank()}")
        if (config == null) { progress("未配置 LLM(Agent 设置里填 Key + 选模型)"); return@withContext 0 }
        if (tavilyApiKey.isBlank()) { progress("未配置 Tavily 搜索 Key"); return@withContext 0 }

        // 第7轮:已有日期的专辑**不再送 AI**(用户:"GEN/POP VIRUS 明明已有时间
        // 还被重复搜,核对也该排在未标注专辑之后")——只把未标注的专辑交给模型,
        // 已有日期的直接进最终排序。全部都有日期 → 零 LLM 调用直接重排。
        val existingDates = releaseDates()
        val alreadyDated = albums.mapNotNull { a ->
            val d = if (a.second > 0) existingDates[a.second] else null
            if (!d.isNullOrBlank()) a to d else null
        }
        val needDating = albums.filter { a -> a.second <= 0 || existingDates[a.second].isNullOrBlank() }
        if (needDating.isEmpty()) {
            if (alreadyDated.isEmpty()) { progress("本页没有可排序的专辑"); return@withContext 0 }
            progress("全部 ${alreadyDated.size} 张已有日期,直接重排")
            val ordered0 = alreadyDated.sortedByDescending { it.second }.map { it.first }
            saveArtistAlbumOrder(artistName, ordered0.map { it.first })
            progress("")
            return@withContext alreadyDated.size
        }
        if (alreadyDated.isNotEmpty()) progress("${alreadyDated.size} 张已有日期跳过,只查剩余 ${needDating.size} 张…")

        // 1+2. AI 工具调用循环(第7轮重构):把 web_search 工具交给模型自己——
        //    它判断要不要搜、搜什么、搜几次(可分专辑多轮),搜到资料再回答。
        //    旧链路是本地写死"拼一条查询搜一次",搜不到就只能凭模型记忆 →
        //    "明明联网能搜到却答不出"。maxTokens 按专辑数放大(默认 2048 会截断
        //    大专辑表的 JSON,解析整体失败 → "一个日期都没有"的实测根因)。
        val dateTokens = (needDating.size * 48 + 1024).coerceIn(2048, 8192)
        val listBlock = needDating.joinToString("\n") { (_, _, name) -> "· $name" }
        val sysMsg = """
            你是音乐资料核对助手。你可以调用 web_search 联网搜索(可以多次,可以按专辑分别搜)。
            有联网资料时以资料为准;资料没覆盖、你又不确定的专辑,直接不返回那一张,不要编造日期。

            搜索查询词规范(重要):
            - 查询词只包含歌手名 + 专辑名 + "发行日期"或"release date",
              **不要把猜测的年份放进搜索词**(如不要搜"星野源 Gen 2025")——
              把年份放进搜索词会让结果偏向那个年份的版本,导致同名不同版专辑错配;
            - 中文歌手用中文搜,日文歌手用日文搜(如"星野源 Gen アルバム 発売日"),
              英文歌手用英文搜(如"Artist Album release date");
            - 一次搜一张专辑,不要在同一个查询里塞多张专辑名。
        """.trimIndent()
        val userMsg = """
            歌手:$artistName 的本地专辑/EP/单曲清单(名字可能与官方写法略有差异,由你对应):
            $listBlock

            为上面每张作品查出官方发布日期,最后只返回 JSON:
            {"albums":[{"name":"<上面清单里的原样名字>","date":"YYYY-MM-DD"}]}
            规则:date 必须是真实发布日期;只能确定到年就返回 "YYYY"、只能确定到月就返回 "YYYY-MM"
            (不要为凑格式编造月/日);不确定的专辑不返回;不要多余文字。
            **name 字段必须与上面清单里的原样名字完全一致**(逐字照抄),不要改写、翻译或缩写。
        """.trimIndent()
        progress("AI 分析发布时间(可自主联网搜索)…")
        val loop = com.shiyin.music.data.ai.AgentTools.runWebSearchLoop(
            config, tavilyApiKey, sysMsg, userMsg,
            maxTokens = dateTokens, maxSearchRounds = 4,
            onSearch = { q -> progress("联网搜索:$q") },
        )

        // 供应商不认 tools(HTTP 400/422)→ 回退旧链路:本地拼一条查询搜一次塞给模型。
        val respContent: String?
        if (loop.failed) {
            // 失败路径**也要入账**:多轮循环里已经成功跑过的那几轮 token 是真花掉的,
            // 不记会让用量面板系统性偏低(项目硬约束:token 必须入账)。
            if (loop.promptTokens + loop.completionTokens > 0) {
                viewModelScope.launch(Dispatchers.IO) {
                    com.shiyin.music.data.ai.TokenUsageStore.log(
                        getApplication(), llmActiveProvider, "album_dates",
                        loop.promptTokens, loop.completionTokens, loop.cacheTokens,
                    )
                }
            }
            progress("LLM 调用失败:${loop.error}")
            com.shiyin.music.data.ai.LlmClient.clearLastError()
            return@withContext 0
        } else if (!loop.toolsSupported) {
            android.util.Log.i("Agent", "albumDates[$artistName]: 供应商不支持 tools,回退一次性搜索")
            progress("该模型不支持工具调用,回退到单次联网搜索…")
            val query = "$artistName albums discography release dates 专辑 发行日期 年表"
            val tavilyCtx = runCatching {
                com.shiyin.music.data.ai.TavilyService.search(tavilyApiKey, query, includeAnswer = true)
            }.getOrNull()
            if (tavilyCtx.isNullOrBlank()) progress("联网搜索没有返回资料,仅凭 AI 自身知识回答(准确率下降)")
            val ctxBlock = if (tavilyCtx.isNullOrBlank()) "(未搜到网络资料,凭你的知识回答)" else "网络资料:\n$tavilyCtx"
            val fallbackPrompt = "$userMsg\n\n$ctxBlock"
            val resp = com.shiyin.music.data.ai.LlmClient.call(config, listOf("user" to fallbackPrompt), maxTokens = dateTokens)
            if (resp == null) {
                progress("LLM 调用失败:${com.shiyin.music.data.ai.LlmClient.lastError}")
                com.shiyin.music.data.ai.LlmClient.clearLastError()
                return@withContext 0
            }
            com.shiyin.music.data.ai.LlmClient.clearLastError()
            viewModelScope.launch(Dispatchers.IO) {
                com.shiyin.music.data.ai.TokenUsageStore.log(
                    getApplication(), llmActiveProvider, "album_dates", resp.promptTokens, resp.completionTokens, resp.cacheTokens,
                )
            }
            respContent = resp.content
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                com.shiyin.music.data.ai.TokenUsageStore.log(
                    getApplication(), llmActiveProvider, "album_dates", loop.promptTokens, loop.completionTokens, loop.cacheTokens,
                )
            }
            respContent = loop.content
        }
        com.shiyin.music.data.ai.LlmClient.clearLastError()
        if (respContent.isNullOrBlank()) {
            progress("AI 没有返回内容(可重试或换更强模型)")
            return@withContext 0
        }

        // 3. 解析 JSON → 名字→日期(归一化匹配:剥括号/空白,contains 兜底)
        // 第7轮:修 norm 正则——原式末尾多了一个字面 "]",括号注释根本剥不掉
        // ("Gen (Deluxe)" 匹配不上 AI 返回的 "Gen",单测实锤)。
        fun norm(s: String) = s.replace(Regex("[(（\\[【].*?[)）\\]】]"), "").replace(Regex("""\s+"""), "").lowercase()
        // 第7轮:全角符号归一——CJK 语境的模型偶发把 JSON 结构符写成全角
        // (｛｝＂，：和全角数字),logcat 里肉眼不可辨,但 "{" 正则找不到 →
        // 整篇解析为空("模型明明返回了合法 JSON 却报 parse empty"的嫌疑路径)。
        fun String.jsonish(): String = buildString(length) {
            for (ch in this@jsonish) when (ch) {
                '｛' -> append('{'); '｝' -> append('}')
                '“', '”', '＂' -> append('"')
                '，' -> append(','); '：' -> append(':')
                in '０'..'９' -> append('0' + (ch.code - '０'.code))
                else -> append(ch)
            }
        }
        val dateByName = mutableMapOf<String, String>()
        try {
            val obj = com.google.gson.JsonParser.parseString(
                // v1.3.8: 原为 Regex("""\{.*}""")——裸 '}' 在 Android 的 java.util.regex
                // 上编译即抛 PatternSyntaxException(真机实测:PatternSyntaxException
                // near index 5),模型返回的合法 JSON 被整段丢弃 → "联网搜到了却没返回"。
                // 改用配平大括号抠取(见 data/JsonExtraction.kt)。
                com.shiyin.music.data.firstBalancedJsonObject(respContent.jsonish()) ?: ""
            ).asJsonObject
            obj.getAsJsonArray("albums")?.forEach { el ->
                val o = el?.asJsonObject ?: return@forEach
                val n = o.get("name")?.asString ?: return@forEach
                val d = o.get("date")?.asString ?: return@forEach
                // 接受 YYYY / YYYY-MM / YYYY-MM-DD,不足位补 01——模型对老专辑常只知
                // 年份,原实现只认完整日期,把这些条目整条丢弃(无结果的第二根因)。
                val m = Regex("""^(\d{4})(?:-(\d{2}))?(?:-(\d{2}))?""").find(d.trim())
                if (m != null) {
                    val y = m.groupValues[1]
                    val mo = m.groupValues[2].ifEmpty { "01" }
                    val da = m.groupValues[3].ifEmpty { "01" }
                    dateByName[norm(n)] = "$y-$mo-$da"
                }
            }
        } catch (e: Exception) {
            // 诊断留痕:把异常类型+消息打出来——下次 parse empty 能直接看到
            // 是 JSON 非法、结构跑偏还是字段类型不对,不用再猜。
            android.util.Log.w("Agent", "albumDates[$artistName] parse threw: ${e.javaClass.simpleName}: ${e.message}")
        }
        // 诊断留痕:解析一条都没拿到时把模型原始返回打进 logcat——
        // 下次"未能确认任何专辑时间"可直接看是模型没搜到、格式跑偏还是被截断。
        if (dateByName.isEmpty()) {
            // 前 24 字符的码点——全角/不可见字符在 logcat 里看不出来,码点藏不住。
            val head = respContent.take(24).map { "%04X".format(it.code) }.joinToString(" ")
            android.util.Log.w("Agent", "albumDates[$artistName] parse empty, head=[$head] raw=${respContent.take(600)}")
        }

        // 4. 匹配本地专辑 + 写日期 + 按日期生成顺序(v1.3.4: 新→旧——最新发布在最上,
        //    与歌手页作品列表的"栈"直觉一致;无日期保持原相对顺序补尾)
        // 第7轮:dated 先装已有日期的(alreadyDated),新查到的从 needDating 里匹配——
        // 已有日期的专辑不参与 LLM 匹配,不被重复写库。
        progress("写回日期与顺序…")
        val dated = alreadyDated.toMutableList() // (专辑, 日期)
        val undated = mutableListOf<Triple<String, Long, String>>()
        for (a in needDating) {
            val hit = dateByName[a.third.let(::norm)]
                ?: dateByName.entries.firstOrNull { it.key.contains(norm(a.third)) || norm(a.third).contains(it.key) }?.value
            if (hit != null) {
                dated.add(a to hit)
                // v1.3.15: AI 这条路**不**作废手动顺序(invalidateArtistOrder=false)——
                // 它随后会按新日期重写整份顺序;中途作废只会在"AI 跑一半失败"时把用户
                // 原有的拖拽顺序白白清掉。
                if (a.second > 0) saveAlbumReleaseDate(a.second, hit, invalidateArtistOrder = false)
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
            // v1.3.x 鉴权: 用户手动删除过歌词的歌不自动匹配(用户删了=这份匹配不对,
            // 再自动匹配回来是同样的错误歌词反复出现)。triedAuto 挡"本 session 已试过";
            // manualDeletedLyrics 持久化挡"用户明确删除过"——后者优先级更高。
            // ⚠️ 包含性判断必须在 synchronized **块内**执行:原写法
            // `id !in synchronized(x) { x }` 只是借锁取了集合引用,`in` 在锁外跑,
            // 与另一线程的 `synchronized(x){ x += mediaId }`(HashSet 扩容/rehash)
            // 并发 → 可能读到错误结果,极端下在环形桶链上死循环。
            val manuallyDeleted = synchronized(manualDeletedLyrics) { id in manualDeletedLyrics }
            if (autoMatch && track.artist != UNKNOWN_ARTIST && id !in triedAuto && !manuallyDeleted) {
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

    /** v3.0: match lyrics from a specific source.
     *  v1.3.x: 来源菜单改结构化五选项后只有 fetch 型源(LRCLIB/网易云)走这里——未知 id
     *  直接拒绝,不再 coerceAtLeast(0) 静默落到 LRCLIB(旧:任意字符串都被当 LRCLIB 查)。 */
    fun rematchSource(source: String) {
        val track = trackById(player.currentId) ?: return
        // v1.3.10 A29:索引基于**当前可用源**(内置去掉关掉的 + 自定义源)
        val idx = LyricsFetcher.activeSources().indexOf(source)
        if (idx < 0) return
        lySheet = false
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            matchOnline(track, idx, thenNextSource = false, reopenSheet = true)
        }
    }

    /**
     * v1.3.10 需求 A32:歌词卡片「在线匹配歌词」——手动重跑**完整来源链**
     * (从第一个源开始,失败自动降级到下一个),与自动匹配走同一条逻辑。
     * 与 [rematchSource] 的区别:那个只查用户点的那一个源。
     */
    fun rematchAuto() {
        val track = trackById(player.currentId) ?: return
        lySheet = false
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            matchOnline(track, 0, thenNextSource = true, reopenSheet = true)
        }
    }

    /**
     * v1.3.x: Agent 联网搜歌词(歌词来源菜单"agent websearch"项)。取代旧"AI 补全"
     * (rematchWithAI→DeepSeekService 直连——无联网、硬编码的 deepseek-chat 模型名
     * 2026-07 已停用,注定失败,随本版下线)。
     *
     * 与其余来源的秒级响应不同:先 Tavily 搜歌词资料(2-5s)再 LLM 生成(8-40s),全程
     * 10-60s。进度经 [lyProgressText] 两阶段透出(预览卡实时显示+可取消);失败原因经
     * [lyFailReason] 透传。资料里没有带时间戳的版本时接受纯文本歌词(不强制时间戳)。
     */
    fun rematchWithAgentWeb() {
        val track = trackById(player.currentId) ?: return
        lySheet = false
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            lyState = LyState.Searching
            lyFailReason = null
            // 阶段①文案自带时长预期——在等待开始前告诉用户"这要等很久",不是卡死。
            lyProgressText = "联网搜索歌词,约需 10-60 秒…"
            try {
                // 阶段①: Tavily 搜歌词站资料,供 LLM 对照(搜不到也能凭模型知识生成)。
                val webContext = runCatching {
                    com.shiyin.music.data.ai.TavilyService.search(tavilyApiKey, "${track.title} ${track.artist} 歌词")
                }.getOrNull() ?: ""
                // 阶段②: LLM 生成;优先 LRC(带时间戳),资料没时间轴就纯文本——两者都导入。
                lyProgressText = "AI 生成歌词…"
                val config = activeLlmConfig() ?: run {
                    lyFailReason = "未配置大模型(Agent 右上「设置」里填 API Key)"
                    lyState = LyState.Failed
                    return@launch
                }
                val prompt = """
                    请写出歌曲《${track.title}》(歌手:${track.artist})的完整歌词。
                    输出要求:
                    1. 优先输出带时间轴的 LRC 格式,每行形如 [mm:ss.xx]歌词文本;
                       资料里没有精确时间轴时,**用你对歌曲结构的常识估算时间戳**
                       (前奏约 10-30 秒开始唱,每句间隔按歌词长度估),不要直接输出纯文本
                    2. 输出**完整歌词**——从第一句到最后一句,一句不落;
                       某句确实不确定时仍要输出该句(尽力回忆),不要整段省略
                    3. 只输出歌词本身,不要任何解释、前缀或代码围栏
                    ${if (webContext.isBlank()) "(没有搜索到资料,凭你记忆中的完整歌词输出;记忆里确实没有这首时才允许省略)" else "参考资料(以此为准;资料没覆盖的段落凭你的记忆补齐,不要省略):\n$webContext"}
                """.trimIndent()
                // v1.3.x 修补: maxTokens 4096→8192——长歌(百行级)整首生成 4096 会
                // 截断,截断的半首歌词"只有几句"的直接来源之一。
                val resp = com.shiyin.music.data.ai.LlmClient.call(config, listOf("user" to prompt), maxTokens = 8192) ?: run {
                    lyFailReason = com.shiyin.music.data.ai.LlmClient.lastError ?: "LLM 调用失败"
                    lyState = LyState.Failed
                    return@launch
                }
                // token 消耗入账(与 Agent 对话同表,设置页累计可见);IO 里写,不阻塞主流程。
                viewModelScope.launch(Dispatchers.IO) {
                    com.shiyin.music.data.ai.TokenUsageStore.log(
                        getApplication(), llmActiveProvider, "lyrics_agent", resp.promptTokens, resp.completionTokens,
                    )
                }
                val raw = resp.content?.trim()?.takeIf { it.isNotBlank() } ?: run {
                    lyFailReason = "模型没有返回歌词内容"
                    lyState = LyState.Failed
                    return@launch
                }
                // v1.3.x 输出校验①拒绝语:模型拒绝回答("抱歉我无法提供歌词,因版权…")
                // 时整段是一句话,直接当歌词导入就成了"AI 把道歉信显示成歌词"——
                // 识别到拒绝模式按失败处理,原因透传给用户。
                if (LlmLyricsGuard.isRefusal(raw)) {
                    lyFailReason = "模型拒绝了请求(${LlmLyricsGuard.refusalSummary(raw)})"
                    lyState = LyState.Failed
                    return@launch
                }
                // 输出校验②歌词形态:真正歌词是多行短行结构;模型的解释性长段落
                // (无时间戳、行数少、行长)不满足形态——拒绝,不再误导入。
                if (!LlmLyricsGuard.looksLikeLyrics(raw)) {
                    lyFailReason = "模型输出的内容不是歌词形态"
                    lyState = LyState.Failed
                    return@launch
                }
                val parsed = LrcParser.parse(raw)
                if (parsed.isEmpty) {
                    lyFailReason = "模型输出的内容无法解析为歌词"
                    lyState = LyState.Failed
                    return@launch
                }
                currentLyrics = LoadedLyrics(track.id, raw, parsed, "agent websearch", LyricsKind.Online, saved = false, offsetMs = 0)
                lyState = LyState.Idle
                lySheet = true
                autoSaveRecognizedLyrics(track.id, raw, "agent websearch")
            } finally {
                lyProgressText = null
            }
        }
    }

    /** v1.3.x: 取消进行中的歌词匹配(agent websearch 长耗时路径的预览卡「取消」按钮)。 */
    fun cancelLyricsSearch() {
        lyricsJob?.cancel()
        lyState = LyState.Idle
        lyProgressText = null
        lyFailReason = null
    }

    private suspend fun matchOnline(track: Track, sourceIndex: Int, thenNextSource: Boolean, reopenSheet: Boolean = false) {
        lyState = LyState.Searching
        lyFailReason = null
        // v1.3.9 诊断:把 App **实际使用的**曲目元数据打出来——此前排查"某首歌搜不到"
        // 时,只能靠外部工具猜 App 拿到的是什么(MediaStore 与 App 扫描结果可能不同)。
        android.util.Log.i(
            "Lyrics",
            "matchOnline: title=${track.title} | artist=${track.artist} | album=${track.album} | dur=${track.durationSec}s | fromSource=$sourceIndex",
        )
        val sources = LyricsFetcher.activeSources()
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
            android.util.Log.i("Lyrics", "源 $src 返回 ${raw?.length ?: 0} 字")
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
        // v1.3.x: 旧 v2.0 DeepSeek 兜底已下线——硬编码的 deepseek-chat 模型名 2026-07 停用,
        // 该路径注定失败还拖慢级联;联网搜歌词改走 rematchWithAgentWeb(来源菜单手动选)。
        if (found == null) {
            // v5.2 #67: 级联全失败时,从 triedAuto 移除该曲——下次进歌词本能重新自动级联
            // (否则 triedAuto 永久挡住,只能手动切源)。这样"两个都没才显示无资源"成立。
            triedAuto -= track.id
            lyState = LyState.Failed
            com.shiyin.music.data.log.UsageLog.event(getApplication(), "歌词", "自动匹配失败")
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
        com.shiyin.music.data.log.UsageLog.event(getApplication(), "歌词", "匹配成功 · $src · ${parsed.lines.size} 行")
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
        // v1.3.10 需求 A28:**导入即保存**。
        // 用户实测的数据丢失:手动导入一个专辑的歌词后没点"保存",播放下一首 → 导入的歌词全没了。
        // 用户定调:"歌词这种获取了(导入或抓取),除非用户自己又删了,不然就要自动保存"——
        // 人家费劲找来歌词文件/文字,不可能保证停在这一页点一下保存。
        // 落库用 autoSaved=0(与"手动确认保存"同级):清理识别缓存时**保留**用户自己的歌词。
        persistLyricsNow("导入即保存 · $name")
    }

    /**
     * v1.3.10:立即可靠落库当前歌词(autoSaved=0,与手动确认同级)。
     *
     * 顺序刻意与 [autoSaveRecognizedLyrics] 一致:**先写库、写成功才置 saved=true**
     * (旧教训:先置 saved=true 而写入失败被吞 → UI 假成功、切歌照样丢)。
     * 任何异常静默——持久化失败不该影响正在展示的歌词。
     */
    private fun persistLyricsNow(logLabel: String) {
        val cur = currentLyrics ?: return
        val target = cur.mediaId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.upsertLyric(
                    SavedLyricEntity(cur.mediaId, cur.raw, cur.source, cur.offsetMs, System.currentTimeMillis(), autoSaved = 0),
                )
                withContext(Dispatchers.Main) {
                    if (currentLyrics?.mediaId == target) currentLyrics = currentLyrics?.copy(saved = true)
                }
                com.shiyin.music.data.log.UsageLog.event(getApplication(), "歌词", logLabel)
            } catch (_: Exception) { }
        }
    }

    fun bumpLyricsOffset(deltaMs: Long) {
        val cur = currentLyrics ?: return
        currentLyrics = cur.copy(offsetMs = cur.offsetMs + deltaMs, saved = false)
        // v1.3.10 需求 A28:偏移量调整同样属于"用户费力对齐的成果"——一并自动保存,
        // 否则调完切歌就白调(与导入丢失同一类问题)。
        persistLyricsNow("偏移调整即保存 · ${deltaMs}ms")
    }

    /** v2.0: sync a line of unsynced lyrics to the current playback position.
     *  Bug4b fix: when the user's click turns the last un-timestamped line into
     *  a stamped one (i.e. parsed.synced flips false→true), persist immediately
     *  so they don't have to remember to hit "保存". */
    fun syncLyricLine(lineIdx: Int) {
        val cur = currentLyrics ?: return
        // v23: 同 tapSyncTap —— 时间戳用**当前曲目**坐标,不能拿整轨文件的绝对位置。
        val pos = player.displayPositionMs()
        val lines = cur.parsed.lines.toMutableList()
        if (lineIdx < 0 || lineIdx >= lines.size) return
        val newTime = (pos - cur.offsetMs).coerceAtLeast(0)
        lines[lineIdx] = com.shiyin.music.data.lyrics.LyricLine(newTime, lines[lineIdx].text)
        // v1.3.x Bug A 修复:**不排序**——原实现 sortedBy 把整列重排,activeI 变化
        // 触发 animateScrollToItem 滚动到当前行(用户反馈"点击后歌词跳到末尾,
        // 焦点丢失,要手动滚回去再点下一句")。打轴时保持原索引顺序,UI 不重排,
        // 用户连续点下一句不用找回焦点。
        // v1.3.x Bug B 修复:**raw 同步更新**——原实现只改 parsed.lines 没改 raw,
        // 保存时存的是原始 LRC 文本(没有时间戳),下次载入读到的还是未打轴状态。
        // 这里把打轴后的 lines 重新生成 LRC 文本([mm:ss.xx] 行头),raw 同步更新。
        commitLyricLines(lines)
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

    /** v1.1+: 统计自动识别缓存占用。v1.3.x 修正:封面不再按"行数×30KB"估算
     *  (与真实占用偏差大,用户发现显示大小不对)——改为直接统计封面磁盘缓存目录
     *  (filesDir/cover_cache,CoverDiskCache 实际字节,含全部 bucket)+ 自动保存
     *  歌词的真实 UTF-8 字节数。 */
    fun refreshRecognitionCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val autoLyrics = savedLyricsMap.values.filter { it.autoSaved == 1 }
                    .sumOf { it.lyrics.toByteArray(Charsets.UTF_8).size }
                val coverDir = java.io.File(getApplication<Application>().filesDir, "cover_cache")
                val coverBytes = coverDir.listFiles()?.sumOf { it.length() } ?: 0L
                recognitionCacheBytes = autoLyrics + coverBytes
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

    // ── v1.2.0 阶段三：用户数据导出/导入（v22 起范围扩到"用户资产"）────────────

    /**
     * v22: 构建跨设备重解析用的两张"身份证"表（见 `BackupRemap`）。
     *
     * mediaId / albumId 都是**设备本地的自增主键**，换机必变，所以导出时必须附带
     * 与设备无关的标识：文件路径、以及"专辑名|歌手"。
     *
     * 用 `tracksRaw`（物理曲目）而不是 `lib()`：拆分出来的虚拟曲目共享同一个文件，
     * 它们的合成 id 靠 `split_track` 的自增 id 保留，不需要路径映射。
     */
    private fun backupIdMaps(): Pair<Map<String, String>, Map<String, String>> {
        val pathMap = LinkedHashMap<String, String>()
        val albumMap = LinkedHashMap<String, String>()
        for (t in tracksRaw) {
            if (t.dataPath.isNotBlank()) pathMap[t.id.toString()] = t.dataPath
            if (t.albumId > 0 && t.album != NO_ALBUM) {
                // 刻意存"专辑名|歌手"：aid 形式本身就含 id，换机后同样失效
                albumMap[t.albumId.toString()] = "${t.album}|${t.artist}"
            }
        }
        return pathMap to albumMap
    }

    /**
     * 从 JSON 导入用户数据。返回导入统计；失败抛异常由 UI 捕获提示。
     *
     * 这是"JSON 层"的入口（[importBackupZip] 解包后调它）；单独保留是因为
     * `BackupArchive.read` 能识别纯 JSON 旧格式，这条路径正好复用。
     *
     * 注：对应的"只导出 JSON"的旧方法已删除 —— UI 全走 zip（本地图片必须跟着走，
     * 否则换机后是裂图），留着它就是死代码。
     */
    suspend fun importBackup(json: String): String = withContext(Dispatchers.IO) {
        val (pathToId, nameToAlbum) = reverseLookupMaps()
        val stats = backupManager.importWithRemap(json, pathToId, nameToAlbum)
        // 导入后刷新内存态，让 UI 立即反映新数据
        if (!firstScanDone) { /* 未扫完不重载 */ }
        else { rescanSilently() }
        stats.toString()
    }

    // ── v22: 备份包（zip 容器，含本地图片）──────────────────────────────────

    /** 会被打进备份包的本地图片目录（用户手选的歌手写真 / 专辑封面）。 */
    private val localImageDirs = listOf("artist_image_override", "album_cover_override")

    /**
     * 收集本地图片。这些图片是**用户资产**，但数据库里只存了 `file://` 路径 ——
     * 不把文件一起打包，换机恢复后就是一堆裂图。
     */
    private fun collectLocalImages(): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val base = getApplication<android.app.Application>().filesDir
        for (d in localImageDirs) {
            val dir = java.io.File(base, d)
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { f ->
                if (f.isFile) runCatching { out["$d/${f.name}"] = f.readBytes() }
            }
        }
        return out
    }

    /** 把备份包里的图片写回私有目录（文件名与 DB 里的 `file://` 路径一一对应）。 */
    private fun restoreLocalImages(files: Map<String, ByteArray>): Int {
        var n = 0
        val base = getApplication<android.app.Application>().filesDir
        for ((rel, bytes) in files) {
            val safe = com.shiyin.music.data.db.BackupArchive.sanitize(rel) ?: continue
            // 只往已知目录写 —— 备份包可被手工编辑，白名单是必要的闸
            if (safe.substringBefore('/') !in localImageDirs) continue
            runCatching {
                val target = java.io.File(base, safe)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                n++
            }
        }
        return n
    }

    /** v22: 导出为 zip 备份包（`backup.json` + 本地图片）。 */
    suspend fun exportBackupZip(): ByteArray = withContext(Dispatchers.IO) {
        val (paths, albums) = backupIdMaps()
        val json = backupManager.export(paths, albums)
        com.shiyin.music.data.db.BackupArchive.write(json, collectLocalImages())
    }

    /** v22: 从备份包恢复。**自动识别** zip 与纯 JSON 旧格式。 */
    suspend fun importBackupZip(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val archive = com.shiyin.music.data.db.BackupArchive.read(bytes)
        restoreLocalImages(archive.files)
        importBackup(archive.json)
    }

    // ── v22: 恢复前预览 + 快照（防手滑）────────────────────────────────────

    /**
     * 预览一个备份包（**不写库**）：各表条数 + 重解析会丢掉多少行。
     *
     * 原来导入是"选文件 → 直接覆盖写库"，误点一个旧备份就会把当前数据冲掉且不可撤销。
     */
    suspend fun previewBackup(bytes: ByteArray): com.shiyin.music.data.db.BackupPreviewer.Preview =
        withContext(Dispatchers.IO) {
            val archive = com.shiyin.music.data.db.BackupArchive.read(bytes)
            val (pathToId, nameToAlbum) = reverseLookupMaps()
            com.shiyin.music.data.db.BackupPreviewer.preview(
                archive.json, pathToId, nameToAlbum, imageFiles = archive.files.size,
            )
        }

    private val snapshotDirName = "backup_snapshots"
    private val snapshotKeep = 3
    private val autoSnapshotIntervalMs = 24L * 60 * 60 * 1000

    private fun snapshotDir(): java.io.File =
        java.io.File(getApplication<android.app.Application>().filesDir, snapshotDirName)

    /** 当前所有快照（新的在前）。 */
    fun listSnapshots(): List<java.io.File> =
        snapshotDir().listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.name }.orEmpty()

    /** 写一份快照并滚动清理（只留最新 [snapshotKeep] 份）。返回文件名；失败返回 null。 */
    private suspend fun writeSnapshot(prefix: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = snapshotDir().apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.CHINA)
                .format(java.util.Date())
            val name = "$prefix-$stamp.zip"
            java.io.File(dir, name).writeBytes(exportBackupZip())
            dir.listFiles()?.filter { it.isFile }
                ?.sortedByDescending { it.name }?.drop(snapshotKeep)?.forEach { it.delete() }
            name
        }.getOrNull()
    }

    /** 恢复前自动快照 —— 恢复失败或反悔时能撤回来。 */
    suspend fun snapshotBeforeRestore(): String? = writeSnapshot("prerestore")

    /**
     * v22: 启动时的自动快照 —— 距上次超过 24 小时才写一份。
     *
     * 刻意用"启动时机 + 时间戳节流"而不是 WorkManager：不引入新依赖、不需要后台调度，
     * 而备份内容只在用户打开 app 时才可能变化，"下次启动补一份"已经够用。
     */
    fun maybeAutoSnapshot() {
        appScope.launch {
            try {
                if (!firstScanDone) return@launch          // 库还没扫完，快照没意义
                val last = settingsStore.lastAutoSnapshotAt()
                val now = System.currentTimeMillis()
                if (now - last < autoSnapshotIntervalMs) return@launch
                if (writeSnapshot("auto") != null) settingsStore.setLastAutoSnapshotAt(now)
            } catch (_: Exception) { }
        }
    }

    /** 当前库的反查表：路径 → mediaId、"专辑名|歌手" → albumId。 */
    private fun reverseLookupMaps(): Pair<Map<String, Long>, Map<String, Long>> {
        val pathToId = HashMap<String, Long>()
        val nameToAlbum = HashMap<String, Long>()
        for (t in tracksRaw) {
            if (t.dataPath.isNotBlank()) pathToId[t.dataPath] = t.id
            if (t.albumId > 0 && t.album != NO_ALBUM) {
                nameToAlbum["${t.album}|${t.artist}"] = t.albumId
            }
        }
        return pathToId to nameToAlbum
    }

    // ── v22: WebDAV 云备份 ─────────────────────────────────────────────────
    // 选 WebDAV 的理由：零 SDK 依赖（就是 HTTP）、国内可用（坚果云
    // https://dav.jianguoyun.com/dav/）、且与项目已有"用户自配地址+凭据"范式一致。

    var webDavUrl by mutableStateOf(""); private set
    var webDavUser by mutableStateOf(""); private set
    var webDavPassword by mutableStateOf(""); private set
    var cloudBusy by mutableStateOf(false); private set
    var cloudMsg by mutableStateOf<String?>(null); private set
    var cloudFiles by mutableStateOf<List<com.shiyin.music.data.backup.WebDavXml.Entry>>(emptyList()); private set
    /** 云端恢复的待确认状态（下载完成、等用户确认后才写库）。 */
    var cloudPendingName by mutableStateOf<String?>(null); private set
    private var cloudPendingBytes: ByteArray? = null
    var cloudPreview by mutableStateOf<com.shiyin.music.data.db.BackupPreviewer.Preview?>(null); private set

    private fun webDav(): com.shiyin.music.data.backup.WebDavClient? {
        if (webDavUrl.isBlank() || webDavUser.isBlank()) return null
        return com.shiyin.music.data.backup.WebDavClient(
            webDavUrl, webDavUser, webDavPassword, sharedHttpClient,
        )
    }

    /** 打开云备份页时载入已保存的凭据。 */
    fun loadWebDavConfig() {
        appScope.launch {
            val (u, n, p) = settingsStore.webDavConfig()
            webDavUrl = u; webDavUser = n; webDavPassword = p
        }
    }

    fun saveWebDavConfig(url: String, user: String, password: String) {
        webDavUrl = url; webDavUser = user; webDavPassword = password
        appScope.launch { settingsStore.setWebDavConfig(url, user, password) }
    }

    /** 测试连接（顺便把远端文件列出来）。 */
    fun cloudTest() {
        if (cloudBusy) return
        val c = webDav()
        if (c == null) { cloudMsg = "请先填写地址与账号"; return }
        cloudBusy = true; cloudMsg = "正在连接…"
        appScope.launch {
            val err = withContext(Dispatchers.IO) { c.test() }
            if (err == null) {
                val files = withContext(Dispatchers.IO) { c.list() }
                cloudFiles = files
                // 把实际读写目录显示出来 —— 用户能一眼核对"备份到底存到哪"。
                cloudMsg = "连接成功（备份目录：${c.workDir}），远端有 ${files.size} 个备份文件"
            } else {
                cloudMsg = err
            }
            cloudBusy = false
        }
    }

    /** 上传当前备份到云端（文件名带时间戳，便于保留多份）。 */
    fun cloudUpload() {
        if (cloudBusy) return
        val c = webDav() ?: run { cloudMsg = "请先填写地址与账号"; return }
        cloudBusy = true; cloudMsg = "正在打包上传…"
        appScope.launch {
            val name = "pickupmusic-backup-" + java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss", java.util.Locale.CHINA,
            ).format(java.util.Date()) + ".zip"
            val bytes = withContext(Dispatchers.IO) { runCatching { exportBackupZip() }.getOrNull() }
            if (bytes == null) {
                cloudMsg = "打包失败"; cloudBusy = false; return@launch
            }
            val err = withContext(Dispatchers.IO) { c.upload(name, bytes) }
            cloudMsg = err ?: "已上传 $name（${"%.1f".format(bytes.size / 1024.0 / 1024.0)} MB）"
            com.shiyin.music.data.log.UsageLog.event(
                getApplication(), "云备份",
                if (err == null) "上传成功 ${"%.1f".format(bytes.size / 1024.0 / 1024.0)}MB"
                // v1.4.0:日志里**只留错误类别**。WebDAV 的失败文案带远端地址与目录路径
                // (「服务器返回 404：… https://dav.jianguoyun.com/dav/」),而日志会随云备份上移、
                // 也会被用户导出分享 —— 原文不进日志。(UI 文案 cloudMsg 仍给用户看完整的。)
                else "上传失败 · ${com.shiyin.music.data.log.UsageLogError.reasonOf(err)}",
            )
            if (err == null) cloudFiles = withContext(Dispatchers.IO) { c.list() }
            cloudBusy = false
        }
    }

    fun cloudRefresh() {
        if (cloudBusy) return
        val c = webDav() ?: run { cloudMsg = "请先填写地址与账号"; return }
        cloudBusy = true
        appScope.launch {
            cloudFiles = withContext(Dispatchers.IO) { c.list() }
            cloudMsg = if (cloudFiles.isEmpty()) "远端没有找到备份文件" else "已刷新（${cloudFiles.size} 个）"
            cloudBusy = false
        }
    }

    /** 从云端下载并恢复。恢复前会自动存本地快照。 */
    /**
     * v22: 云端恢复第一步 —— 只下载 + 预览，**不写库**。
     *
     * 与本地恢复保持一致：云端恢复的风险其实更高（远端可能是很久以前的备份、
     * 也可能是别人的），更不能"下完直接覆盖"。确认由 [cloudConfirmRestore] 完成。
     */
    fun cloudRequestRestore(name: String) {
        if (cloudBusy) return
        val c = webDav() ?: run { cloudMsg = "请先填写地址与账号"; return }
        cloudBusy = true; cloudMsg = "正在下载 $name …"
        appScope.launch {
            val bytes = withContext(Dispatchers.IO) { c.download(name) }
            if (bytes == null) {
                cloudMsg = "下载失败"
            } else {
                try {
                    cloudPreview = previewBackup(bytes)
                    cloudPendingBytes = bytes
                    cloudPendingName = name
                    cloudMsg = null
                } catch (e: Exception) {
                    cloudMsg = "备份包无法解析：${e.message}"
                }
            }
            cloudBusy = false
        }
    }

    fun cloudCancelRestore() {
        cloudPreview = null
        cloudPendingBytes = null
        cloudPendingName = null
    }

    /** 云端恢复第二步 —— 用户确认后才写库（写前自动存快照）。 */
    fun cloudConfirmRestore() {
        val bytes = cloudPendingBytes ?: return
        if (cloudBusy) return
        cloudBusy = true; cloudMsg = "正在恢复…"
        appScope.launch {
            try {
                snapshotBeforeRestore()
                cloudMsg = "恢复完成：${importBackupZip(bytes)}"
            } catch (e: Exception) {
                cloudMsg = "恢复失败：${e.message}"
            } finally {
                cloudBusy = false
                cloudCancelRestore()
            }
        }
    }

    fun deleteLyrics() {
        val cur = currentLyrics ?: return
        currentLyrics = null
        closeLyricsInstant()
        lyState = LyState.Idle
        triedAuto -= cur.mediaId
        // v1.3.x 鉴权(用户反馈⑧):用户手动删除歌词 = "这份匹配不对"的明确信号——
        // 记入持久化黑名单,**不再自动匹配**,待用户手动获取歌词资源(设置·歌词来源)。
        // 旧实现 `triedAuto -= mediaId` 反而把该曲放回自动匹配队列——用户删了又自动
        // 匹配回来,同样的错误歌词反复出现。
        markManualLyricsDeleted(cur.mediaId)
        // 歌词本体删了，译文也就没意义了——内存态复位，且**真取消**进行中的任务，
        // 否则它跑完会把刚清掉的译文重新写回库里（复活孤儿行）。
        cancelLyricsTranslation()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.deleteLyric(cur.mediaId)
                dao.deleteReadingOverridesForMedia(cur.mediaId)
                dao.deleteExternalEvidenceForMedia(cur.mediaId)
                // v1.3.x 需求6:译文同样按 mediaId 清（含换源前的旧 hash 行）——
                // 与上面两条同属"这首歌的派生数据"，漏掉会留下永不命中的孤儿行。
                dao.deleteLyricsTranslationForMedia(cur.mediaId)
            } catch (_: Exception) { }
        }
    }

    /** v1.3.x 鉴权: 用户手动删除歌词的 mediaId 黑名单(SharedPreferences 持久化,与
     *  searchHistory 同一做法,不引入新依赖)。自动匹配(loadLyricsFor)查这里,命中即跳过。
     *  字段声明在文件前部(triedAuto 旁)——init 块要用,声明顺序必须在 init 之前。 */

    private fun markManualLyricsDeleted(mediaId: Long) {
        synchronized(manualDeletedLyrics) { manualDeletedLyrics += mediaId }
        val prefs = getApplication<Application>().getSharedPreferences("lyrics_blacklist", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("ids", synchronized(manualDeletedLyrics) { manualDeletedLyrics.joinToString(",") }).apply()
    }

    private fun loadManualDeletedLyrics() {
        val prefs = getApplication<Application>().getSharedPreferences("lyrics_blacklist", android.content.Context.MODE_PRIVATE)
        val ids = prefs.getString("ids", "") ?: ""
        synchronized(manualDeletedLyrics) {
            manualDeletedLyrics.clear()
            manualDeletedLyrics.addAll(ids.split(",").mapNotNull { it.toLongOrNull() })
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
            // v1.3.x 用户反馈:"作词：XXX 作曲：XXX"元数据行不该注音——人名注音
            // 经常错且无价值,词典/UtaTen/LLM 三层全部对它禁用(返回全 null 段)。
            if (isLyricsMetadataLine(line.text)) {
                com.shiyin.music.data.lyrics.FuriganaTokenizer.toSegments(
                    line.text, lineOverrides = null, externalEvidence = null,
                    correction = null, jmdict = null,
                ).map { seg -> seg.copy(reading = null) }
            } else {
                com.shiyin.music.data.lyrics.FuriganaTokenizer.toSegments(
                    line.text,
                    lineOverrides = byLine[idx],
                    externalEvidence = extByLine[idx],
                    correction = com.shiyin.music.data.furigana.LyricsReadingOverrides,
                    jmdict = furiganaJmdict,
                )
            }
        }
    }

    /**
     * 歌词元数据行判定:"作词：XXX 作曲：XXX"这类非歌词正文行。用户定调不注音
     * (人名注音常错且无价值)。宽匹配中日英写法:作词/作詞/作曲/编曲/編曲/编词/
     * 作编曲/填词/填詞/歌词/作詞作曲 + :/：/。英文 lyric(s)/words/music by 也算。
     */
    private fun isLyricsMetadataLine(line: String?): Boolean {
        if (line.isNullOrBlank()) return false
        val t = line.trim()
        return METADATA_LINE_RX.containsMatchIn(t)
    }

    // v1.3.x code-review:①提升为常量——此前每次调用都 Regex() 重编译,分词/证据
    // 每行每次重算都调,一次 assist 数百次模式编译纯浪费;②补日文复合署名格式
    // 「作詞・作曲：」「作詞/作曲：」(・与 / 分隔)与 編曲 简繁、作編曲——旧模式
    // ^作詞 后紧跟 \s*[:：] 匹配不到 ・/ 作词的复合行,人名仍被注音(正是本功能
    // 要消灭的现象);③清重复项(作詞 出现两次)。
    // v1.3.x code-review(F4):分隔符改可选 [/:：·・]?——补「作詞作曲：」(无分隔符
    // 拼接,J-pop 极常见格式)。原模式分隔符必填,作詞 后紧跟 作曲(非分隔符)→
    // 内层组 0 次,然后 \s*[:：] 撞 作曲 的 作 失配,整行判非元数据 → 人名仍被注音。
    private val METADATA_LINE_RX = Regex(
        """(?i)^(?:作词|作詞|作曲|编曲|編曲|作編曲|编词|填词|填詞|lyrics?|words?|music)(?:\s*[/:：·・]?\s*(?:作词|作詞|作曲|编曲|編曲|作編曲|编词|填词|填詞|lyrics?|words?|music))*\s*[:：]"""
    )

    // ── v1.3.x: 振假名三层增强(UtaTen 自动对照 + LLM 缺口兜底) ─────────────────
    // 编排:本地词典出结果(照常) → UtaTen 权威对照(冲突/空缺直接采信,覆盖 LLM 行)
    // → 仍空缺的段整曲一次 LLM 兜底(候选约束+位置白名单)。失败矩阵:
    //   · UtaTen 网络失败 → 跳过该层照常走 LLM;负缓存 6h(防离线连环重试)
    //   · UtaTen 未收录 → 负缓存 7d(收录不会瞬变)
    //   · UtaTen 页面无假名 → 绑歌词版本永久跳过(该版本永远不会有)
    //   · UtaTen 解析/对齐失败 → 负缓存 1d(防站改版被连环锤)
    //   · LLM 调用失败 → 不落"完成"标记,下次自动重试(LlmClient 内置 1 次重试已耗尽才到这)
    //   · LLM 输出位置/读法非法 → 逐条白名单过滤,宁弃勿错
    //   · DB 写入失败 → 重试 1 次,仍失败提示且不崩
    // 任一层失败最坏回到现状(增强层只做增量),状态经 [furiganaAssistStatus] 透出,
    // 「重新校验」可带 force 逐层重跑。
    /** 负缓存:key=layer:mediaId:hash, value=过期时刻(now+ttl)。到期前该层跳过重试。 */
    private val furiganaNegCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** in-flight 闸门(key 同负缓存):自动触发/开关抖动/手动重跑并发时,同曲同
     *  时刻只跑一个 assist——否则第二次进来的读不到第一次未落库的行,UTaTen+LLM
     *  全套请求/token 双倍烧(code-review 实锤:toggle off→on 重入,layer 各自
     *  完整重跑并发)。sheet 按钮 enabled=!running 只挡了手动路径,自动路径裸奔。 */
    private val furiganaRunning = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** LLM 兜底触发门槛:应用 UtaTen 后仍空缺的段 ≥3 才值得一次 LLM 往返
     *  (用户拍板默认 3,真机数据出来后再调;force 重新校验不受限)。 */
    private val LLM_GAP_THRESHOLD = 3
    private fun negOk(key: String): Boolean {
        val until = furiganaNegCache[key] ?: return true
        if (System.currentTimeMillis() >= until) { furiganaNegCache.remove(key); return true }
        return false
    }

    /** UI 可见的增强状态(null=无进行中/无结论;""=正在跑;非空=结论文案)。 */
    var furiganaAssistStatus by mutableStateOf<String?>(null); private set

    /** v1.3.x code-review(F7):切歌时清上一首的校验状态——furiganaAssistStatus 是
     *  单字段不按曲隔离,旧值残留在新歌的调整弹层里(尤其新歌非日语不跑 verify、
     *  或新歌 verify 还没跑完时,弹层显示的是上一首的结论)。LyricsScreen 用
     *  LaunchedEffect(ly.mediaId) 调本方法清,ensure 跑完再写新值。 */
    fun clearFuriganaAssistStatus() { furiganaAssistStatus = null }

    // ── v1.3.x 需求6:歌词本翻译 ──────────────────────────────────────────────
    //
    // AI 路径(质量主力):整歌一次调用 + "行号|译文" 协议保证行数一一对应,缺行带语境补齐。
    // 编排逻辑全在 data/translate/LyricsTranslationPipeline(纯逻辑、47 个单测覆盖),
    // 这里只负责:状态归属、取消、token 入账、把失败原因透给 UI。
    // 离线路径(ML Kit 端侧)未接线——APK 体积决策(四 ABI 原生库 +60MiB)后再启。

    /** UI 可见的翻译状态。切歌/换源时 [clearLyricsTranslation]。 */
    val lyricsTranslation = com.shiyin.music.ui.components.LyricsTranslationState()

    private var lyricsTranslationJob: Job? = null

    /** v1.3.x 反馈②:翻译任务代次——cancel 是协作式的,旧任务的回调可能写入新任务
     *  的状态。每次 begin 递增,回调只在代次匹配时写入。 */
    private var lyricsTranslationGeneration = 0

    /**
     * 切歌/换歌词源时复位**显示状态**。
     *
     * **刻意不 cancel 进行中的任务**：译文已经烧掉了 token，让它跑完并落库，
     * 下次回到这首歌直接恢复——中途切歌就把它扔掉等于白花钱（一首歌 15~40 秒，
     * 用户很可能等不及就切走了）。任务收尾时会检查"这首歌是否仍是当前曲"，
     * 不是就不回写显示状态，因此**不会把上一首的译文挂到新歌的行上**。
     *
     * 需要"真取消"的场景请用 [cancelLyricsTranslation]。
     */
    fun clearLyricsTranslation() {
        lyricsTranslation.reset()
    }

    /**
     * 真取消：中止进行中的 LLM 调用并复位。
     *
     * 三个调用点都是"用户明确不要这个结果"：① 进行中再点译按钮 = 取消；
     * ② 删除歌词；③ 删除翻译缓存。这三处若只复位显示而不取消，
     * 任务跑完还会把刚被删掉的译文重新写回库里（复活孤儿行）。
     */
    fun cancelLyricsTranslation() {
        lyricsTranslationJob?.cancel()
        lyricsTranslationJob = null
        lyricsTranslation.reset()
    }

    /**
     * AI 整歌翻译。
     *
     * 前置条件不满足时**不发请求**，直接把原因写进 [lyricsTranslation] 的 error
     * （UI 弹提示，停在初始态）：
     * - 没有歌词 / 找不到当前曲 → 数据问题
     * - 未配置大模型 → 同 agent websearch 的置灰逻辑，提示去设置里填 Key
     *
     * @return true = 已真正发起；false = 被前置条件挡下
     */
    fun translateLyricsWithAi(): Boolean {
        val ly = currentLyrics ?: run {
            lyricsTranslation.error = "当前没有歌词可翻译"
            return false
        }
        val track = trackById(ly.mediaId) ?: run {
            lyricsTranslation.error = "找不到当前歌曲"
            return false
        }
        val config = activeLlmConfig() ?: run {
            lyricsTranslation.error = "未配置大模型——请到 设置 → Agent 里填好 API Key 并选模型"
            return false
        }
        val lines = ly.parsed.lines.map { it.text }
        if (lines.isEmpty()) {
            lyricsTranslation.error = "歌词为空"
            return false
        }

        // 歌词版本哈希:落库键与"换源即失效"的判据,与振假名 evidence 同键法。
        val lyHash = com.shiyin.music.data.furigana.LyricsHash.of(ly.raw)
        lyricsTranslationJob?.cancel()
        lyricsTranslation.begin(com.shiyin.music.ui.components.LyricsTranslationMode.Ai, ly.mediaId, lyHash)

        // v1.3.x 反馈②(反复点 3-4 次才成功):旧任务 cancel 是协作式的——OkHttp
        // execute() 阻塞在 IO 线程,cancel 不立即生效。旧任务的 onProgress/finish
        // 回调会写入**新任务**的状态(mediaId 相同,stillCurrent 判定通过),
        // 旧结果覆盖新结果——用户看到"点了没反应,再点才成功"。generation 计数器:
        // 每次 begin 递增,回调只在 generation 匹配时才写入。
        val myGeneration = ++lyricsTranslationGeneration

        // 失败原因留痕：把 LLM 的 lastError 透到提示文案里。否则"成功率很低"只能看到
        // 一句"调用失败"，无法区分网络 / 401 / 429 / 超时 / 格式不符。
        var lastLlmError: String? = null
        lyricsTranslationJob = viewModelScope.launch {
            val pipeline = com.shiyin.music.data.translate.LyricsTranslationPipeline { messages, maxTokens ->
                com.shiyin.music.data.ai.LlmClient.clearLastError()
                val resp = com.shiyin.music.data.ai.LlmClient.call(
                    config,
                    messages,
                    // 翻译要稳定复现，不要发挥
                    temperature = 0.0,
                    maxTokens = maxTokens,
                    // 整歌翻译虽是后台静默任务，但**不是**轻量调用：整歌一次生成，
                    // 长歌可上千 completion token，60s 对慢供应商/思考型模型会误判超时 → 90s。
                    // retries 用默认 1 次：项目实测最常见的失败是"息屏/切后台后 OkHttp
                    // 复用连接被服务端关掉"（SocketException），重试一次即可恢复；
                    // 早先设 0 会让这类瞬时错误直接判失败——是成功率低的一个直接原因。
                    callSec = 90,
                    retries = 1,
                )
                if (resp == null) lastLlmError = com.shiyin.music.data.ai.LlmClient.lastError
                // v1.3.11:正文空 + 有思考 = 思考型模型把输出预算吃光(见 LlmBudget 实证)。
                // 给一句用户看得懂的原因 —— 否则表现就是"点了转半天,啥也没说就回原样"。
                // 只写人话,不把 finish=length/token 数这类内部细节摆给用户(日志里有)。
                if (resp != null && resp.content.isNullOrBlank() && !resp.thinking.isNullOrBlank()) {
                    lastLlmError = "模型思考占满了输出预算,可重试或换一个非思考模型"
                }
                if (resp != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        com.shiyin.music.data.ai.TokenUsageStore.log(
                            getApplication(), llmActiveProvider, "lyrics_translation",
                            resp.promptTokens, resp.completionTokens, resp.cacheTokens,
                        )
                    }
                }
                resp?.content
            }

            val out = pipeline.translate(track.title, track.artist, lines) { d, t ->
                // pipeline 跑在 viewModelScope(Main) 上，LlmClient 内部自切 IO 后归位，
                // 故此处回调已在主线程——无需 Handler 投递（对比 fetchArtistAlbumDates）。
                // generation 守卫:旧任务(被 cancel 但还在跑)的回调不写入新任务状态。
                if (myGeneration == lyricsTranslationGeneration) {
                    lyricsTranslation.done = d
                    lyricsTranslation.total = t
                }
            }

            // 取消语义：cancelLyricsTranslation() 会 cancel 本 Job → pipeline 内的
            // CancellationException 原样穿透到这里，协程直接结束，不覆盖已复位的状态。
            // generation 守卫:旧任务跑完时若已被新任务取代,不回写 finish。
            if (myGeneration != lyricsTranslationGeneration) return@launch
            val failure = out.failure?.let { msg ->
                val why = lastLlmError
                if (why.isNullOrBlank()) msg else "$msg（$why）"
            } ?: if (out.coverage < 1f) "已译 ${out.byLine.size}/${out.requested.size} 行（其余未返回）" else null
            // 只在"这首歌仍是当前曲"时回写显示状态。切歌走的是 clearLyricsTranslation
            // （不复位 mediaId 之外还保留任务在跑）→ 任务跑完时 mediaId 已被 reset 清空，
            // 这里判定不成立 → 不回写，新歌的状态不被污染。而**落库照旧**（见下）。
            val stillCurrent = lyricsTranslation.mediaId == ly.mediaId
            if (stillCurrent) {
                lyricsTranslation.finish(out.byLine, failure)
                // A36: 失败提示默认不弹(用户嫌太频繁)——设置→歌词本里可自主打开
                if (failure != null && translateErrorToast) showToast(failure, null)
            } else if (failure != null) {
                // v1.3.x 反馈⑤(20s 无声失败):stillCurrent=false 时静默丢弃——
                // 用户只看到"动画转了 20 秒又回到没翻译的状态"。失败原因必须透出,
                // 即使不是当前曲(可能 LyricsScreen 重组导致 mediaId 短暂错位)。
                // A36: 同上,受「翻译失败时提示」开关门控;日志始终留痕。
                if (translateErrorToast) showToast("翻译失败：$failure", null)
                android.util.Log.w("LyricsTranslate", "dropped failure (stillCurrent=false): $failure")
            }
            // v1.3.x 需求6:译完即存——下次打开歌词本自动恢复,不再重复烧 token
            // (与振假名 evidence 同一思路)。只在有成果时写,失败/空结果不落库。
            // **与"是否仍是当前曲"无关**：token 已经花了，结果必须留下。
            if (out.byLine.isNotEmpty()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        dao.upsertLyricsTranslation(
                            com.shiyin.music.data.db.LyricsTranslationEntity(
                                mediaId = ly.mediaId,
                                lyricsHash = lyHash,
                                payload = com.google.gson.Gson().toJson(out.byLine),
                                source = "ai",
                                translatedAt = System.currentTimeMillis(),
                            ),
                        )
                        // ⚠️ 译文落库还不够 —— 必须把**它赖以存在的那份歌词文本**也落库。
                        //
                        // 译文的键是 `LyricsHash.of(ly.raw)`。若这份歌词只是联网匹配来的
                        // 临时结果（没开「自动保存识别资源」时 saved=false，不写库），
                        // 重启后 savedLyricsMap 里没有它 → 重新联网匹配 → 拿到的文本未必
                        // 逐字相同（不同源/多一行元数据/行尾差异）→ **hash 变了 → 按旧 hash
                        // 查不到译文** → 用户看到"翻译好了，退出后又要重新加载一遍"。
                        //
                        // 所以译完即把歌词一并存下（这就是"AI翻译也要自动保存"的落点）。
                        // 用 insert-IfAbsent：已有行一律不动，绝不把用户更新的歌词版本覆盖
                        // 回旧文本（那种情况下译文与新文本本就不匹配，属正确的失效）。
                        dao.insertAutoSavedLyricIfAbsent(
                            ly.mediaId, ly.raw, ly.source, ly.offsetMs, System.currentTimeMillis(),
                        )
                    }
                }.onFailure { android.util.Log.w("LyricsTranslate", "persist failed: ${it.message}") }
                // v1.3.x 使用日志:只记"译了多少行",不记歌词内容与译文。
                com.shiyin.music.data.log.UsageLog.event(
                    getApplication(), "翻译",
                    "成功 ${out.byLine.size}/${out.requested.size} 行 · rounds=${out.rounds}" +
                        // v1.4.0:只记**类别**,不记失败原文(可能是模型输出片段)。
                        // 给用户看的 failure 文案不变(它同时用于 toast)。
                        (if (failure != null) " · ${com.shiyin.music.data.log.UsageLogError.reasonOf(failure)}" else ""),
                )
            }
        }
        return true
    }

    /**
     * v1.3.x 需求6:歌词本进入/切歌时的翻译状态同步（由 LyricsScreen 用
     * `LaunchedEffect(mediaId, lyricsHash)` 触发）。
     *
     * 分两种情况，**关键是不误杀进行中的任务**：
     * - **同曲同版本**（关掉歌词本再打开）→ 原样保留现有状态。进行中的翻译继续跑，
     *   已隐藏的保持隐藏，已显示的保持显示。若在这里无条件 clear，用户切出去再切回来
     *   就会把正在跑的翻译掐死。
     * - **换曲 / 换歌词源**（hash 变）→ 清场，再从持久化恢复（有则直接显示）。
     */
    suspend fun syncLyricsTranslation(ly: LoadedLyrics) {
        val hash = com.shiyin.music.data.furigana.LyricsHash.of(ly.raw)
        val sameSong = lyricsTranslation.mediaId == ly.mediaId && lyricsTranslation.lyricsHash == hash
        if (sameSong) return
        // v1.3.x 反馈⑤(20s 无声失败)+⑥(播放中被吞):两道不清场守卫。
        // 守卫A 翻译进行中:LLM 任务不可打断(token 已花)。
        if (lyricsTranslation.running && lyricsTranslation.mediaId == ly.mediaId) return
        // 守卫B 同曲已有译文:hash 漂移(offset 微调等)不该把已保存的译文清掉再恢复
        // (用户实测"翻译被吞了,几秒后又自己加载起来")。mediaId 必须匹配——切歌时
        // 旧曲译文挂到新曲是绝对不允许的,mediaId 不同照样清场+按新 hash 恢复。
        if (lyricsTranslation.hasData && lyricsTranslation.mediaId == ly.mediaId) return
        // 第7轮:中文歌(用户语言)**不恢复存量译文**。旧版本把"夹几句英语/夹一个「の」的
        // 中文歌"整首译过,库里可能存着这些译文;而语种判定改后「譯」按钮在中文歌上
        // 不再挂载(LyricsScreen),若照常恢复,用户会看到中文歌歌词本上挂着译文、
        // 却**找不到任何关闭入口**(用户明确要求"翻译件不应该出现在繁体中文、简体中文
        // 的歌词本上,帮我去掉")。这里只清显示状态、**不碰 DB**——译文是用户花 token
        // 换来的,留在库里,未来做多语言版本时可直接复用。
        if (!isForeignLyrics(ly.parsed.lines.map { it.text })) {
            clearLyricsTranslation()
            return
        }
        // 第6轮:**先读 DB 再清场**。原顺序 clear→suspend 读库,协程若在读库期间
        // 被 LaunchedEffect 换 key 取消,译文停在"已清空未恢复"的空窗(用户实测
        // "看着看着突然没了")。先读后清,取消只会发生在读库中(旧译文原样保留)。
        val row = withContext(Dispatchers.IO) {
            runCatching { dao.lyricsTranslation(ly.mediaId, hash) }.getOrNull()
        }
        // 第6轮回归修复:读库这几毫秒里自动翻译可能已启动(maybeAutoTranslateForeign
        // 与 sync 是两个并发 LaunchedEffect)——不清场守卫必须在**清场前一刻**复查,
        // 否则 clear 会把刚 begin 的任务状态抹掉(任务还在跑,finish 时 mediaId 已
        // 被 reset → stillCurrent=false → 结果被丢弃,用户看到"自动翻译没反应")。
        if (lyricsTranslation.running && lyricsTranslation.mediaId == ly.mediaId) return
        if (lyricsTranslation.hasData && lyricsTranslation.mediaId == ly.mediaId) return
        clearLyricsTranslation()
        applyLyricsTranslationRow(row, ly, hash)
    }

    /**
     * v1.3.x 需求6:把持久化查询结果应用到显示状态（有则直接显示）。
     *
     * 与振假名 evidence 同键法（mediaId + lyricsHash）：换歌词源/改文本后 hash 变，
     * 旧译文自然不再适用——不会把旧文本的译文挂到新文本上。
     */
    private fun applyLyricsTranslationRow(row: com.shiyin.music.data.db.LyricsTranslationEntity?, ly: LoadedLyrics, hash: String) {
        if (row == null) return
        val map: Map<Int, String> = runCatching {
            com.google.gson.Gson().fromJson<Map<Int, String>>(
                row.payload,
                object : com.google.gson.reflect.TypeToken<Map<Int, String>>() {}.type,
            )
        }.getOrNull() ?: return
        if (map.isEmpty()) return
        // v1.3.x 反馈:恢复受「默认显示译文」开关控制——关时数据照样恢复到内存
        // (再点按钮立刻回来,不重新调 API),只是不自动展开。开时直接显示。
        if (translateDefaultShow) {
            lyricsTranslation.restore(map, row.source, ly.mediaId, hash)
        } else {
            lyricsTranslation.restore(map, row.source, ly.mediaId, hash)
            lyricsTranslation.hide()
        }
    }

    /** 隐藏译文（数据保留在内存与 DB，再点即可立刻回来，不重新调 API）。 */
    fun hideLyricsTranslation() { lyricsTranslation.hide() }

    /** 重新显示已保存的译文（无网络请求）。 */
    fun showLyricsTranslation() { lyricsTranslation.show() }

    /**
     * v1.3.x 需求6:歌词本"进入翻译"的**唯一**入口 —— 先从持久化恢复,再按需自动翻译。
     *
     * ⚠️ 为什么必须合成一个入口：这两步原来在 LyricsScreen 里是两个并发的
     * LaunchedEffect。「恢复」要 await 一次读库(IO,几毫秒),而「自动翻译」只看
     * 内存状态 —— 它会抢在恢复完成前看到 hasData=false 并发起新翻译,随后恢复
     * 结果撞上"翻译进行中"守卫被丢弃。表现就是**明明库里有译文却重翻了一遍**
     * (用户反馈"译好的内容退出后又要重新加载一遍",既慢又白烧 token)。
     * 合成后顺序确定：恢复完成 → 仍然没有译文 → 才自动翻译。
     */
    suspend fun enterLyricsTranslation(ly: LoadedLyrics, autoTranslate: Boolean) {
        syncLyricsTranslation(ly)
        if (!autoTranslate) return
        maybeAutoTranslateForeign(ly)
    }

    /**
     * v1.3.x 反馈:「外语歌自动翻译」入口（设置开关开时由歌词本 LaunchedEffect 触发）。
     *
     * 条件全部满足才发起：有歌词 / 无进行中任务 / 无已保存译文（避免重复触发）/
     * 歌词含外文字母（非纯中文——中文歌词不需要翻译）。静默失败不弹提示
     * （自动任务打扰最小化；失败矩阵与手动触发一致）。
     */
    fun maybeAutoTranslateForeign(ly: LoadedLyrics) {
        if (lyricsTranslation.running || lyricsTranslation.hasData) return
        val lines = ly.parsed.lines.map { it.text }
        if (lines.isEmpty()) return
        if (!isForeignLyrics(lines)) return
        translateLyricsWithAi()
    }

    /**
     * "外语歌词"判定:委托给 LyricsTranslationProtocol.classifyLanguage——
     * 第7轮起按**字母占比**判(汉字占主体的歌=用户语言,不译),不再"有一个
     * 外文字母就算外语"(中文歌夹几句英语被整首翻译,用户实测很难堪)。
     * 按钮显隐(LyricsScreen)与自动翻译共用此判定。多语言版本落地后按用户语言走。
     */
    fun isForeignLyrics(lines: List<String>): Boolean =
        com.shiyin.music.data.translate.LyricsTranslationProtocol.isForeign(lines)

    /**
     * v1.3.10: 歌词本「翻译」按钮是否挂载——按用户选的档位([translateButtonMode])决定:
     *  - never    → 永不显示;
     *  - any      → 出现任何外文(假名/谚文/拉丁字母)就显示;
     *  - majority → 沿用 [isForeignLyrics] 的占比判定(第7轮定的现状阈值,不改变)。
     *
     * 与自动翻译**刻意解耦**:自动翻译只认 [isForeignLyrics](占比),不随按钮档位变——
     * 用户调的是"按钮显不显示",不是"要不要自动烧 token 翻译"。
     */
    fun shouldShowTranslateButton(lines: List<String>): Boolean = when (translateButtonMode) {
        "never" -> false
        "any" -> com.shiyin.music.data.translate.LyricsTranslationProtocol.hasAnyForeign(lines)
        else -> isForeignLyrics(lines)
    }

    /**
     * v1.3.x 需求6「删除翻译缓存」：清掉本曲本版本的已保存译文（内存 + DB）。
     * 只删当前 `(mediaId, lyricsHash)` —— 不牵连其它歌，也不牵连换源前的旧版本。
     */
    fun deleteTranslationCache() {
        val ly = currentLyrics ?: return
        // 真取消：用户明确要删，进行中的任务跑完会把删掉的译文再写回来
        cancelLyricsTranslation()
        viewModelScope.launch {
            val hash = com.shiyin.music.data.furigana.LyricsHash.of(ly.raw)
            withContext(Dispatchers.IO) {
                runCatching { dao.deleteLyricsTranslation(ly.mediaId, hash) }
            }
            showToast("已清除本曲翻译缓存", null)
        }
    }

    /**
     * v1.3.x 需求6「删除振假名缓存」：清掉本曲本版本的**外部 evidence**
     * （UtaTen 等抓来的注音缓存）。**不碰用户手改的 Song Override**——那是用户数据，不是缓存。
     */
    fun deleteFuriganaCache() {
        val ly = currentLyrics ?: return
        viewModelScope.launch {
            val hash = com.shiyin.music.data.furigana.LyricsHash.of(ly.raw)
            withContext(Dispatchers.IO) {
                runCatching { dao.deleteExternalEvidence(ly.mediaId, hash) }
            }
            bumpFuriganaRevision() // 触发 produceState 重算 → 注音回落到本地分词
            showToast("已清除本曲振假名缓存", null)
        }
    }

    /** DB 重试 1 次的写入:失败抛出由调用方归类。 */
    private suspend fun upsertEvidenceRetry(rows: List<com.shiyin.music.data.db.ExternalReadingEvidenceEntity>) {
        try { dao.upsertExternalEvidence(rows) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { dao.upsertExternalEvidence(rows) }
    }

    /**
     * 振假名增强主入口(歌词本开振假名时自动触发;force=true 跳过全部缓存/负缓存)。
     * 三层按序跑,各层独立失败独立降级;结果写入 external_reading_evidence
     * (source="utaten:*"/"llm:*"),管线里低于人工 Song Override。
     */
    fun ensureFuriganaEvidence(force: Boolean = false) {
        val cur = currentLyrics ?: return
        val track = trackById(cur.mediaId) ?: return
        val hash = com.shiyin.music.data.furigana.LyricsHash.of(cur.raw)
        val mediaId = cur.mediaId
        val runKey = "run:$mediaId:$hash"
        // in-flight 闸门:force 手动重跑等自动任务收尾(手动重跑语义=校验,不是
        // 放弃)。同 key 并发第二进来直接退出,无双倍请求。
        if (!furiganaRunning.add(runKey)) {
            if (!force) return
            // force 等在途跑完再走(简单实现:直接放弃本次 force,UI 提示稍后;
            // 等锁会阻塞主线程状态更新,不做)
            furiganaAssistStatus = "校验进行中,请稍候"
            return
        }
        furiganaAssistStatus = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ensureFuriganaEvidenceLocked(cur, track, hash, mediaId, force)
            } finally {
                furiganaRunning.remove(runKey)
            }
        }
    }

    private suspend fun ensureFuriganaEvidenceLocked(
        cur: com.shiyin.music.LoadedLyrics,
        track: com.shiyin.music.data.Track,
        hash: String,
        mediaId: Long,
        force: Boolean,
    ) {
        val localLines = cur.parsed.lines.map { it.text }
        val now = System.currentTimeMillis()
            val existing = try { dao.externalEvidence(mediaId, hash) } catch (_: Exception) { emptyList() }
            val utatenDone = existing.any { it.source.startsWith("utaten") }
            val llmDone = existing.any { it.source.startsWith("llm") }
            val notes = ArrayList<String>(3)
            var wrote = false

            // ── 层1: UtaTen 权威对照 ──
            if (force || !utatenDone) {
                if (force || negOk("utaten:$mediaId:$hash")) {
                    val utatenT0 = android.os.SystemClock.elapsedRealtime()
                    try {
                        val provider = com.shiyin.music.data.furigana.external.UtaTenProvider()
                        val occ = provider.resolve(track.title, track.artist, localLines)
                        // 用户反馈:"作词：XXX 作曲：XXX"这类元数据行不该注音(人名经常
                        // 注错且无价值)——UtaTen 回来的 occurrence 里元数据行整行剔除。
                        val occFiltered = occ!!.filter { !isLyricsMetadataLine(localLines.getOrNull(it.lineIndex)) }
                        val rows = occFiltered.map {
                            com.shiyin.music.data.db.ExternalReadingEvidenceEntity(
                                mediaId = mediaId, lyricsHash = hash,
                                lineIndex = it.lineIndex, charStart = it.charStart, length = it.length,
                                surface = "", reading = it.reading, source = it.source, confidence = 1, fetchedAt = now,
                            )
                        }
                        // REPLACE 主键(mediaId,hash,line,charStart)——UtaTen 行天然覆盖旧 LLM 行
                        upsertEvidenceRetry(rows)
                        furiganaNegCache.remove("utaten:$mediaId:$hash")
                        wrote = true
                        notes.add("UtaTen 对照 ${rows.size} 处")
                        android.util.Log.i("FuriganaAssist", "utaten resolve ${android.os.SystemClock.elapsedRealtime() - utatenT0}ms rows=${rows.size} (filtered ${occ.size - occFiltered.size})")
                    } catch (e: com.shiyin.music.data.furigana.external.UtaTenProvider.ResolveException) {
                        // 负缓存 TTL 按失败原因分档(v1.3.x code-review 对齐口径:
                        // 网络/解析/对齐失败是"暂时性"——6h 后重试,不再 24h;
                        // 文档块 2887 行起的口径以这里为准):未收录 7d(收录不会
                        // 瞬变)、页面无假名绑本歌词版本永久跳过、其余 6h(防离线
                        // 连环重试又不至于一次抖动死一天)。
                        val ttl = when (e.reason) {
                            com.shiyin.music.data.furigana.external.UtaTenProvider.Reason.NO_MATCH -> 7L * 24 * 3600_000L
                            com.shiyin.music.data.furigana.external.UtaTenProvider.Reason.NO_FURIGANA -> Long.MAX_VALUE / 4
                            else -> 6 * 3600_000L
                        }
                        furiganaNegCache["utaten:$mediaId:$hash"] = System.currentTimeMillis() + ttl
                        notes.add("UtaTen: ${e.message?.take(40)}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        furiganaNegCache["utaten:$mediaId:$hash"] = System.currentTimeMillis() + 6 * 3600_000L
                        notes.add("UtaTen: ${t.message?.take(30) ?: "异常"}")
                    }
                } else notes.add("UtaTen: 稍后自动重试")
            } else notes.add("UtaTen 已缓存")

            // ── 层2: LLM 缺口兜底 ──
            val needLlm = force || !llmDone
            if (needLlm) {
                val config = activeLlmConfig()
                // v1.3.x code-review 修复(优先级反转):重读失败时旧代码退回
                // UtaTen 写入前的 existing 快照,UtaTen 刚写的权威行对 gaps 不可见
                // → LLM 把同一主键的权威行 REPLACE 掉(置信 0 覆盖置信 1)。
                // 重读失败 = 本轮放弃 LLM 层(宁可这轮不补,不可反转优先级)。
                val fresh = try { dao.externalEvidence(mediaId, hash) } catch (_: Exception) { null }
                if (fresh == null) {
                    notes.add("LLM 兜底: 证据重读失败,本轮跳过")
                } else {
                // 层1 刚写入/历史已有的 UtaTen 行,先重算分词拿"应用后仍空缺"的段
                val segments = try {
                    val overrides = try { dao.songReadingOverrides(mediaId, hash) } catch (_: Exception) { emptyList() }
                    val byLine = overrides.groupBy { it.lineIndex }.mapValues { (_, rs) -> rs.associate { it.charStart to it.reading } }
                    val extByLine = fresh.groupBy { it.lineIndex }.mapValues { (_, rs) -> rs.associate { it.charStart to (it.length to it.reading) } }
                    localLines.mapIndexed { idx, text ->
                        com.shiyin.music.data.lyrics.FuriganaTokenizer.toSegments(
                            text, lineOverrides = byLine[idx], externalEvidence = extByLine[idx],
                            correction = com.shiyin.music.data.furigana.LyricsReadingOverrides, jmdict = furiganaJmdict,
                        )
                    }
                } catch (_: Exception) { null }
                if (config == null) {
                    notes.add("LLM 兜底: 未配置(设置里填)")
                } else if (segments == null) {
                    notes.add("LLM 兜底: 本地分词失败,跳过")
                } else {
                    // 用户反馈:"作词：XXX 作曲：XXX"元数据行不注音——收集空缺段时
                    // 先按行过滤,元数据行的段不进 LLM(不给模型机会注人名)。
                    val gapsAll = com.shiyin.music.data.furigana.FuriganaLlmAssist.collectGaps(segments) {
                        furiganaJmdict.readings(it) ?: emptyList()
                    }
                    val gaps = gapsAll.filter { !isLyricsMetadataLine(localLines.getOrNull(it.lineIndex)) }
                    if (gaps.isEmpty()) {
                        notes.add("无空缺段,LLM 跳过")
                    } else if (gaps.size < LLM_GAP_THRESHOLD && !force) {
                        // 用户拍板:默认门槛 3——零星 1-2 个空缺段不值一次 LLM 往返
                        // (用户可长按逐个改);force(手动重新校验)不受门槛限制。
                        notes.add("空缺段 ${gaps.size} 处未过门槛($LLM_GAP_THRESHOLD),跳过 LLM")
                    } else {
                        val prompt = com.shiyin.music.data.furigana.FuriganaLlmAssist.buildPrompt(localLines, gaps)
                        // callSec=45:全局 120s readTimeout 给流式聊天,后台静默任务撞上
                        // 挂住的连接就是干等 2 分钟(真机 SocketTimeoutException 实测);
                        // retries=0:兜底层有自己的"下次自动重试",单次快速失败即可。
                        val llmT0 = android.os.SystemClock.elapsedRealtime()
                        val resp = com.shiyin.music.data.ai.LlmClient.call(
                            config, listOf("user" to prompt), maxTokens = 4096,
                            callSec = 45, retries = 0,
                        )
                        android.util.Log.i("FuriganaAssist", "llm call ${android.os.SystemClock.elapsedRealtime() - llmT0}ms gaps=${gaps.size} → ${if (resp != null) "ok" else com.shiyin.music.data.ai.LlmClient.lastError?.take(50)}")
                        // v1.3.10 A19: 这次调用此前**从不入账**——用量面板因此少算振假名这一块,
                        // 是用户"统计不准"的来源之一。成功失败都记(token 已经烧了,解析失败也算)。
                        if (resp != null) {
                            com.shiyin.music.data.ai.TokenUsageStore.log(
                                getApplication(), llmActiveProvider, "furigana_assist",
                                resp.promptTokens, resp.completionTokens, resp.cacheTokens,
                            )
                        }
                        if (resp == null) {
                            // v1.3.x code-review 修复(lastError 串扰):全局单例
                            // lastError 是所有调用方共享的——振假名后台跑期间若
                            // 用户同时用 Agent 聊天,这里读到的是 Agent 那次调用的
                            // 错误。原因归属已不可靠 → 显示通用文案,不给假细节。
                            notes.add("LLM 兜底失败(网络/额度),下次自动重试")
                        } else {
                            val fixes = com.shiyin.music.data.furigana.FuriganaLlmAssist.parseFixes(resp.content ?: "", gaps)
                            if (fixes.isEmpty()) {
                                // v1.3.x code-review 修复(无限重烧):解析零结果与调用失败
                                // 同级——若不加 TTL,每次开歌词本/开关 あ 都整曲 prompt
                                // 重烧一次,零进展。负缓存 1d,force 不受限。
                                if (force) {
                                    notes.add("LLM 无有效补注(${gaps.size} 空缺)")
                                } else {
                                    furiganaNegCache["llm:$mediaId:$hash"] = System.currentTimeMillis() + 24 * 3600_000L
                                    notes.add("LLM 无有效补注(${gaps.size} 空缺),明天自动重试")
                                }
                            } else {
                                // 补注落库前先给"零结果"负缓存清位:成功补注要允许
                                // 之后缺口的自然重试,不带着昨天的失败 TTL。
                                furiganaNegCache.remove("llm:$mediaId:$hash")
                                val rows = fixes.map { f ->
                                    val g = gaps.first { it.lineIndex == f.lineIndex && it.charStart == f.charStart && it.length == f.length }
                                    com.shiyin.music.data.db.ExternalReadingEvidenceEntity(
                                        mediaId = mediaId, lyricsHash = hash,
                                        lineIndex = f.lineIndex, charStart = f.charStart, length = f.length,
                                        surface = g.surface, reading = f.reading,
                                        source = "llm:${config.model.take(20)}", confidence = 0, fetchedAt = now,
                                    )
                                }
                                try {
                                    upsertEvidenceRetry(rows)
                                    agentCtx.logUsage(resp.promptTokens, resp.completionTokens, resp.cacheTokens)
                                    wrote = true
                                    notes.add("LLM 补注 ${rows.size}/${gaps.size} 处")
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    notes.add("结果写入失败(${t.message?.take(20) ?: "DB"}),可点重新校验重试")
                                }
                            }
                        }
                    }
                }
                }
            } else notes.add("LLM 已缓存")

            withContext(Dispatchers.Main) {
                furiganaAssistStatus = if (wrote) {
                    notes.joinToString("；").also { bumpFuriganaRevision() }
                } else notes.joinToString("；").ifBlank { "无更新" }
                // 有写入时 bump 已触发重算;纯失败(无写入)不动现有注音
                // v1.3.x 预组合:预热期(歌词本没开)也会跑 pipeline——成功提示只在
                // 歌词本实际打开时弹,预热期静默入库(开本即用,不无故弹 toast)。
                if (wrote && lyricsOn) showToast("振假名校验：$furiganaAssistStatus", null)
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
        // 清理建议是从首页清理卡直达(不经过设置根),不压栈——返回走 cleanEntry 旁路
        // 直接回来源 tab。清空栈保证此时栈是空的,navFilesBack 不会误弹到别层。
        filesNavStack.clear()
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
        // v1.3.15:不再顺手把 libChip 设成 "pls" —— 用户反馈"点我的喜欢再退出,歌单筛选
        // 被自动选中了"。歌单行在 chip==null 时本来就显示(见 LibraryScreen 的 chip==null
        // 分支),所以这里改选筛选既没必要、又会在退出后留下一个用户没选过的筛选态。
        plId = pid; tab = Tab.Library
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
        playerOpen = false; closeLyricsInstant()
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
        // v1.3.x 误弹修复:原 showToast(...,id) 携带 changeTargetId——toast 右侧
        // 会出现「更改」按钮,点击会打开"保存到歌单"弹窗(SaveToPlaylistSheet)。
        // 加队列后跳歌单选择毫无语义(用户报告"加到播放列表错误弹出也加入歌单
        // 弹窗")。该按钮只该在收藏场景(误触后想改收藏目标)出现;加队列场景去掉。
        showToast("已加入播放队列", null)
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

/**
 * v1.3.9: 对话消息解码 + 净化。
 *
 * Gson 通过 Unsafe 分配对象、**不执行构造器**——JSON 里缺失的字段会被置成 null
 * 打进 Kotlin 的非空属性(旧构建写的行、或后来新增的字段如 processHistory)。
 * UI 一旦读 `msg.processHistory.size` / `msg.text` 就 NPE 崩溃
 * (用户实测:点击历史对话闪退)。这里逐条补齐兜底值再交付 UI。
 *
 * 独立成顶层函数是为了能被 JVM 单测直接覆盖(不需要设备)。
 */
/**
 * v1.3.9 二修:改为 **JsonParser 手动逐字段解析**,彻底脱离 Gson 反射数据绑定。
 * 根因(真机崩溃栈 2026-09-27 12:33 实证):release 包经 R8 后,`List<Step>` 的泛型
 * 信息在反射路径上丢失,Gson 把 steps 元素落成 LinkedTreeMap,迭代时
 * ClassCastException 闪退——`TypeToken` + `-keepattributes Signature` 都没能防住。
 * 手动解析不依赖任何字段名/泛型签名,与项目既有约定一致(proguard-rules.pro 的
 * Gson 注释、UpdateStateCodec 的"刻意不用 fromJson")。
 * 任何解析异常 → 空列表(历史打不开总比闪退强),并落 error 日志。
 */
internal fun decodeAgentMessages(json: String): List<MainViewModel.AgentMessage> = runCatching {
    fun com.google.gson.JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    fun com.google.gson.JsonObject.lng(k: String): Long? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
    arr.mapNotNull { el ->
        if (!el.isJsonObject) return@mapNotNull null
        val o = el.asJsonObject
        MainViewModel.AgentMessage(
            role = o.str("role") ?: "agent",
            text = o.str("text") ?: "",
            steps = o.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { se ->
                if (!se.isJsonObject) return@mapNotNull null
                val so = se.asJsonObject
                com.shiyin.music.data.ai.AgentEngine.Step(
                    id = so.str("id") ?: "",
                    label = so.str("label") ?: "",
                    status = runCatching {
                        com.shiyin.music.data.ai.AgentEngine.StepStatus.valueOf(so.str("status") ?: "")
                    }.getOrDefault(com.shiyin.music.data.ai.AgentEngine.StepStatus.PENDING),
                    error = so.str("error"),
                )
            },
            tokens = o.get("tokens")?.takeIf { it.isJsonObject }?.asJsonObject?.let { t ->
                val f = t.lng("first")?.toInt(); val s = t.lng("second")?.toInt()
                if (f != null && s != null) f to s else null
            },
            thinking = o.str("thinking"),
            thinkingMs = o.lng("thinkingMs") ?: 0L,
            processHistory = o.get("processHistory")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { pe ->
                if (!pe.isJsonObject) return@mapNotNull null
                val po = pe.asJsonObject
                MainViewModel.ProcessStep(
                    type = po.str("type") ?: "thinking",
                    label = po.str("label") ?: "",
                    durationMs = po.lng("durationMs") ?: 0L,
                    summary = po.str("summary"),
                )
            } ?: emptyList(),
        )
    }
}.getOrElse {
    android.util.Log.e("Agent", "decodeAgentMessages 解析失败,返回空列表", it)
    emptyList()
}

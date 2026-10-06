package com.shiyin.music.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shiyin.music.data.split.PlayKind
import com.shiyin.music.data.split.SPLIT_START_UNKNOWN
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_lyrics")
data class SavedLyricEntity(
    @PrimaryKey val mediaId: Long,
    val lyrics: String,
    val source: String,
    val offsetMs: Long,
    val savedAt: Long,
    // v1.1+: autoSaved=1 表示该歌词是「自动识别」匹配后由"自动保存识别结果"开关自动持久化的
    // （清理缓存时清掉，重新识别）；=0 表示用户手动确认保存/导入的（清理缓存保留）。
    val autoSaved: Int = 0,
)

@Entity(tableName = "artist_alias")
data class ArtistAliasEntity(
    @PrimaryKey val fromName: String,
    val toName: String,
)

@Entity(tableName = "album_order")
data class AlbumOrderEntity(
    @PrimaryKey val albumKey: String,
    val orderedIds: String,
)

@Entity(tableName = "ignored_folder")
data class IgnoredFolderEntity(
    @PrimaryKey val path: String,
)

@Entity(tableName = "trashed_track")
data class TrashedTrackEntity(
    @PrimaryKey val mediaId: Long,
    val title: String,
    val artist: String,
    val folder: String,
    val sizeBytes: Long,
    val trashedAt: Long,
)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortIdx: Int,
    // v3.0: nullable. When present, this album's cover is used as the playlist
    // cover instead of the auto-generated 2x2 mosaic. Null = mosaic/auto.
    val coverAlbumId: Long? = null,
)

@Entity(tableName = "playlist_track", primaryKeys = ["playlistId", "mediaId"])
data class PlaylistTrackEntity(
    val playlistId: String,
    val mediaId: Long,
    val addedAt: Long,
)

// ── v2.0 artist schema ──────────────────────────────────────────────────

@Entity(tableName = "album_override")
data class AlbumOverrideEntity(
    @PrimaryKey val mediaId: Long,
    val albumName: String,
    val artistName: String,
)

// v4.3: album-level override — manual edits to an album's name/artist and an
// optional custom cover URI. Distinct from the track-level AlbumOverrideEntity
// (which re-parents a single NO_ALBUM track into an album). This one applies to
// the WHOLE album keyed by MediaStore albumId, so a user can fix scan errors
// the whole album shares (wrong artist, misspelled album name) and pin a true
// album cover so a standout single's embedded art stops overriding it.
// v9: `type` lets the user manually force the album's category (专辑/EP/单曲)
// to override the heuristic in AlbumClassifier — needed when only a few tracks
// of an album were downloaded (heuristic sees 2 tracks → 单曲, user knows it's
// really an 专辑). "" = keep auto-classification. Values: "Album"/"EP"/"Single".
@Entity(tableName = "album_info_override")
data class AlbumInfoOverrideEntity(
    @PrimaryKey val albumId: Long,
    val albumName: String = "",
    val artistName: String = "",
    val coverUri: String = "",
    val type: String = "",
    val updatedAt: Long = 0,
)

// v4.3: single-track manual edits — display-level title/artist/note overrides
// for songs whose scanned metadata is wrong or that the user wants renamed for
// their own organization. Applied per-mediaId in lib() at display time; the
// underlying file tags are never rewritten.
@Entity(tableName = "track_info_override")
data class TrackInfoOverrideEntity(
    @PrimaryKey val mediaId: Long,
    val title: String = "",
    val artist: String = "",
    val note: String = "",
    val hidden: Int = 0,
    val updatedAt: Long = 0,
)

// v4.3: track_album_move — 单曲迁移到正确专辑。Scan errors sometimes isolate a
// single from its real album (wrong albumId), leaving the album short a song.
// This override re-parents the track at display time: lib() swaps albumId so
// the track joins the target album's group/cover/key everywhere (album page,
// artist album rail, stats). Underlying file tags are never rewritten.
@Entity(tableName = "track_album_move")
data class TrackAlbumMoveEntity(
    @PrimaryKey val mediaId: Long,
    val albumId: Long,
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "artist",
    indices = [Index(value = ["name"], unique = true)],
)
data class ArtistEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "name") val name: String,
    val aliases: String = "",
    val avatarUrl: String = "",
    val avatarSource: String = "",
    val updatedAt: Long = 0,
)

// v1.2.0 #6: 歌手写真独立持久层（与 album_art_cache 分离）。
// cache 存自动源结果；override 存用户手选写真（最高优先级，自动源永不覆盖）。
// 旧 artist.avatarUrl 不再作为头图来源（那是专辑封面，非人物照），头图改走本 cache。
@Entity(tableName = "artist_image_cache")
data class ArtistImageCacheEntity(
    @PrimaryKey val name: String,
    val url: String = "",
    val source: String = "",
    val fetchedAt: Long = 0,
    /** >0 且 >now 视为"近期自动源全失败"，TTL 内不重试，避免开页干等。url 非空时忽略。 */
    val failUntilTs: Long = 0,
    // v1.2.0 #6: 图片元数据，供 UI 按比例决定裁剪方式（宽背景/方形肖像/logo），
    // 避免 banner/logo 类图破坏歌手主页头图。width/height=0 表示未探测。
    val width: Int = 0,
    val height: Int = 0,
    val aspectRatio: Float = 0f, // w/h; 0=未知
    val imageType: String = "", // portrait/background/banner/logo/thumb
)

@Entity(tableName = "artist_image_override")
data class ArtistImageOverrideEntity(
    @PrimaryKey val name: String,
    val url: String,
    val chosenAt: Long = 0,
)

// ── v1.3.10 Agent 长期记忆(P1:数据层)────────────────────────────────────────
// Persona 是"拾光是谁",本表是"拾光认识这个用户什么"——两者架构分离,记忆永不写进人格。
// **写入裁决(校验/去重/冲突)与检索(门槛/top-k)全在 data/memory/MemoryRules + MemoryManager
// (纯逻辑、纯 JVM 单测)**;本表只负责存,不做任何业务判断。
@Entity(tableName = "agent_memory", indices = [Index(value = ["id"], unique = true)])
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    /** 稳定业务标识(UUID)——对外引用/更新/删除都用它,不暴露 rowId。 */
    val id: String = "",
    val type: String = "",
    val content: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val importance: Int = 2,
    val source: String = "",
    /** 逗号分隔的检索标签(由内容派生)。 */
    val tags: String = "",
    /** 冲突时旧条目指向新条目;null = 当前有效。 */
    val supersededBy: String? = null,
    /** 预留:语义检索向量。**本阶段恒为 null,只占列**——将来接 embedding 不必改表。 */
    val embedding: String? = null,
)

// v1.3.10: FTS4 检索表。content/tags 存的是**CJK 二元切分展开后**的文本
// (见 MemoryRules.ftsText)——FTS4 默认分词器不切中文,不展开的话"专辑"这类关键词永远搜不到。
// 与主表由**应用层同步**(写/删一起动),刻意不用 `content=` 外部内容表:那需要触发器,
// 而手写触发器 DDL 与 Room 期望的 schema 很难对齐,风险大于收益。
@Entity(tableName = "agent_memory_fts")
@Fts4
data class AgentMemoryFtsEntity(
    // 刻意**不声明 rowid 主键**:FTS4 表的 rowid 是 SQLite 隐式管理的(Room 生成的 DDL 里
    // 并没有 rowid 列)。若声明成普通 Long 主键,应用层每次插入都得自己保证唯一,
    // 否则第二行就撞键——那属于自找麻烦。删除/去重一律按 memoryId 走。
    val memoryId: String = "",
    val content: String = "",
    val tags: String = "",
)

// v1.2.2 Agent: LLM tokens 消耗日志。每次 LLM 调用记一行,供 Agent 页显示本次/累计消耗。
// v1.3.6: 加 cacheTokens(缓存命中 token)——用量面板的"缓存命中"曲线。
@Entity(tableName = "token_usage_log")
data class TokenUsageLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = 0,
    val provider: String = "",
    val skill: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** v1.3.6: 命中缓存的 prompt token 数(OpenAI 兼容 usage.prompt_tokens_details.cached_tokens,
     *  兼容 DeepSeek 的 prompt_cache_hit_tokens;没有 = 0)。 */
    val cacheTokens: Int = 0,
)

/** v1.2.2 Agent: token 累计查询的 POJO(Room 双列 SUM 不能直接映射数组)。 */
data class TokenTotals(val promptTotal: Long = 0, val completionTotal: Long = 0)

/** v1.3.10 A19: 用量面板逐日多指标(输入/输出/缓存命中)。 */
data class DailyMetrics(
    val day: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheTokens: Long = 0,
)

/** v1.3.10 A19: 按功能(skill)归因的用量。用户看得懂"哪个功能在花钱",比一根总量曲线有用。 */
data class SkillUsage(
    val skill: String = "",
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheTokens: Long = 0,
    /** 该功能的 LLM 调用次数。 */
    val calls: Long = 0,
)

@Entity(tableName = "song_artist", primaryKeys = ["mediaId", "artistId"])
data class SongArtistEntity(
    val mediaId: Long,
    val artistId: Int,
)

// v1.3.3b: 歌手页专辑手动排序——key=歌手名,value=专辑 key 逗号串(按用户排的顺序)。
// 有序生效:手动排序 > 发布日期 > 专辑名。AI 排时间本质也是把顺序写进这里(先取
// 日期,再按日期生成顺序串落库,一次完成)。
@Entity(tableName = "artist_album_order")
data class ArtistAlbumOrderEntity(
    @PrimaryKey val artistName: String,
    val albumKeys: String = "",
    val updatedAt: Long = 0,
)

@Entity(tableName = "play_count")
data class PlayCountEntity(
    @PrimaryKey val mediaId: Long,
    val count: Int = 0,
)

@Entity(tableName = "album_art_cache")
data class AlbumArtCacheEntity(
    @PrimaryKey val albumId: Long,
    val url: String = "",
    val source: String = "",
    val fetchedAt: Long = 0,
    // v3.0: extracted dominant colors (ARGB int) so the player/lyrics never
    // re-extract on every open. 0 means "not extracted yet".
    val bgArgb: Int = 0,
    val fgArgb: Int = 0,
    // v4: iTunes releaseDate ("1987-07-21T07:00:00Z") stored for sort-by-newest
    // on the artist's album list. Never promised to be the true original release
    // date — UI must NOT label it as the official release date.
    val releaseDate: String = "",
)

// ── v4: timestamped play events (for 最近播放 + 收听统计) ──────────────────
// play_count is only a running total with no timestamp; it can't answer
// "what did I play in the last 3 months" or "this week's top album/artist/track".
// play_event logs every play attempt with its wall-clock time + track duration
// so the recent-plays feed and weekly aggregates share one source of truth.
// === completed 字段语义(v1.2.1 起,禁止再理解为"播到结尾") ===
// completed=1 = 本次播放 Session 累计真实播放已达 ≥30 秒,已计入一次有效播放。
// completed=0 = 尚未达有效播放门槛(误触/快速跳过/听不满 30s)。
// 这是 热度排序 与 收听统计 的唯一计数口径。最近播放 feed 显示全部事件(含 completed=0)。
// `playedSec` = 本次 Session 的实际累计有效秒数(暂停/继续累加、seek 剔除),供总时长求和。
@Entity(tableName = "play_event")
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val playedAt: Long,    // epoch millis (System.currentTimeMillis)
    val durationSec: Int,  // track total duration at play time (kept for 最近播放 时长显示)
    val playedSec: Int = 0, // v1.2.1: actual accumulated effective play time (pause/resume sums, seeks excluded); feeds 收听统计 总时长
    val completed: Boolean = false,  // v1.2.1: true when accumulated play time reached 30s (a counted play)
    // ── v22: 两套统计口径的数据层隔离位（专辑整轨拆分为单曲）─────────────
    // "track" = 单曲播放会话（含拆分单曲，mediaId 是真实 id 或合成 id）
    // "album" = 「完整专辑」整轨播放会话（mediaId 是容器合成 id）
    // 存量行一律为 "track"，所有既有查询加 `AND kind = 'track'` 后语义逐字不变。
    // 完整专辑播放只计入专辑播放次数，绝不产生任何单曲事件 —— 由 kind + 不相交的
    // id 值域双重保证（见 SplitIds）。
    val kind: String = PlayKind.TRACK,
)

// ── v22: 专辑整轨拆分为单曲 ─────────────────────────────────────────────────
// 一条 album_split = 一个整轨文件被拆分；split_track 每行 = 一首拆分单曲。
// 物理文件零切割：拆分单曲共享同一 uri，各自的曲目身份由合成 mediaId
// （SplitIds.trackId(splitRowId)）承载，因此 playlist_track / play_event /
// saved_lyrics / track_info_override 等按 mediaId 工作的表全部零 schema 改动。
//
// endMs 刻意**不落库**：它恒等于「下一首的 startMs」或「文件末尾」。两个字段分开
// 存会在用户改中间某一首的起点时漂移（经典的双字段不同步 bug），故一律派生。
@Entity(tableName = "album_split")
data class AlbumSplitEntity(
    /** 整轨文件的 MediaStore _ID。 */
    @PrimaryKey val sourceMediaId: Long,
    /** 冗余存绝对路径：MediaStore id 变化（重扫/换机）后兜底重解析。 */
    val sourcePath: String = "",
    /** 0 = 草稿（仍有未定时间点）；1 = 已完成。 */
    val status: Int = STATUS_DRAFT,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    companion object {
        const val STATUS_DRAFT = 0
        const val STATUS_DONE = 1
    }
}

@Entity(
    tableName = "split_track",
    indices = [
        Index(value = ["sourceMediaId"]),
        Index(value = ["sourceMediaId", "ordinal"], unique = true),
    ],
)
data class SplitTrackEntity(
    /** 自增行 id；对外曲目 id = SplitIds.trackId(id)。 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceMediaId: Long,
    /** 1..N，展示序号与排序依据。 */
    val ordinal: Int,
    /** 用户录入的曲名 —— 拆分单曲曲名的唯一来源，绝不被文件内嵌标签覆盖。 */
    val title: String,
    /** 起始毫秒；SPLIT_START_UNKNOWN(-1) = 未定（草稿态）。 */
    val startMs: Long = SPLIT_START_UNKNOWN,
    val updatedAt: Long = 0,
)

// ── v4: "你的更新" — one-shot unread reminder of newly-scanned albums ───────
// On every scan, albums that are brand-new to the library go into this table.
// When the user opens an album's detail page (viewing it), its row is deleted,
// so "你的更新" only ever lists unseen new arrivals — exactly the unread-flag
// lifecycle the requirement describes. Decoupled from album/track tables so we
// don't dirty the core read paths with a viewed-state boolean.
@Entity(tableName = "new_album")
data class NewAlbumEntity(
    @PrimaryKey val albumId: Long,
    val firstSeenAt: Long,  // epoch millis, drives newest-first ordering
)

// v1.1.0: 振假名 Song Override（歌曲专属读法，如当て字 何→なん / 魏→たか）。
// 按「出现位置」存取：PK = (scope, mediaId, lyricsHash, lineIndex, charStart)，
// 使同一 surface 在歌里多次出现、读法不同时可分别设（何 有的なに 有的なん，不归一）。
// 绑 mediaId+lyricsHash，换歌词源/改文本（hash 变）自动失效，不污染其他歌。优先级
// 最高。索引 (mediaId, lyricsHash) 供按曲批量查与删除歌词时级联清理。
@Entity(
    tableName = "reading_override",
    primaryKeys = ["scope", "mediaId", "lyricsHash", "lineIndex", "charStart"],
    indices = [Index(value = ["mediaId", "lyricsHash"])],
)
data class ReadingOverrideEntity(
    val scope: String,        // "GLOBAL" or "SONG"
    val mediaId: Long,        // 0 for GLOBAL
    val lyricsHash: String,   // "" for GLOBAL; SONG 绑歌词 raw 的 hash
    val lineIndex: Int,       // 歌词第几行（0-based）
    val charStart: Int,       // 该 surface 在该行中的起始字符偏移（定位"哪一次出现"）
    val surface: String,      // 原文（展示用，不参与定位）
    val reading: String,
    val updatedAt: Long = 0,
)

// v1.1+: 外部歌词假名 evidence 缓存（UtaTen 等带假名歌词站解析对齐后的 occurrence 读法）。
// 按 (mediaId+lyricsHash+lineIndex+charStart) 定位，与 Song Override 同坐标系但低优先级。
// 联网抓取一次后缓存，后续完全离线。source 记来源，confidence 记可信度（多源一致=高）。
@Entity(
    tableName = "external_reading_evidence",
    primaryKeys = ["mediaId", "lyricsHash", "lineIndex", "charStart"],
    indices = [Index(value = ["mediaId", "lyricsHash"])],
)
data class ExternalReadingEvidenceEntity(
    val mediaId: Long,
    val lyricsHash: String,
    val lineIndex: Int,
    val charStart: Int,
    val length: Int,
    val surface: String,
    val reading: String,
    val source: String,        // provider 名 + matched song（evidence 元数据）
    val confidence: Int,      // 0=低 1=中(单源) 2=高(多源一致)
    val fetchedAt: Long,
)

// v1.3.x 需求6:歌词翻译结果（AI 档）。
// 与振假名 evidence 同一套思路——**译完即存，下次打开自动恢复，不再重复烧 token**。
// 绑 (mediaId + lyricsHash)：换歌词源 / 改歌词文本自动失效，不会把旧译文挂到新文本上。
// payload 是"行号→译文"的 JSON（Gson），整批替换不做行级增量——翻译本身是一次性整歌结果。
@Entity(
    tableName = "lyrics_translation",
    primaryKeys = ["mediaId", "lyricsHash"],
)
data class LyricsTranslationEntity(
    val mediaId: Long,
    val lyricsHash: String,
    /** `Map<Int, String>`（行号 0-based → 译文）的 Gson 序列化。 */
    val payload: String,
    /** 译文来源：`ai`（AI 档）／`offline`（离线档，暂未接线）。角标据此显示。 */
    val source: String,
    val translatedAt: Long,
)

// v1.3.9: Agent 对话持久化——历史按「对话」组织（与 ChatGPT/Claude 一致），
// 不再随进程退出即丢。一行 = 一次对话；消息列表（含步骤/思考链/过程历史）
// 整体 Gson 序列化存 messagesJson（对话体小，不做行级分表）。
@Entity(tableName = "agent_conversation")
data class AgentConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** 1 = 收藏置顶。 */
    val pinned: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    /** Gson 序列化的 List<MainViewModel.AgentMessage>。 */
    val messagesJson: String,
)

@Dao
interface ShiyinDao {
    // Lyrics
    @Query("SELECT * FROM saved_lyrics")
    fun lyricsFlow(): Flow<List<SavedLyricEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLyric(e: SavedLyricEntity)

    // v1.1+: 自动保存专用——只在「无行」或「已有自动行(autoSaved=1)」时写入/更新。
    // 若该 mediaId 已有手动保存(autoSaved=0)，ON CONFLICT 触发但 WHERE autoSaved=1 为假
    // → 不更新，手动行被保留，绝不覆盖用户手动保存。修 code-review 数据丢失 bug。
    @Query("INSERT INTO saved_lyrics(mediaId, lyrics, source, offsetMs, savedAt, autoSaved) VALUES (:mediaId, :lyrics, :source, :offsetMs, :savedAt, 1) ON CONFLICT(mediaId) DO UPDATE SET lyrics=:lyrics, source=:source, offsetMs=:offsetMs, savedAt=:savedAt WHERE autoSaved=1")
    suspend fun upsertAutoSavedLyric(mediaId: Long, lyrics: String, source: String, offsetMs: Long, savedAt: Long)

    // v1.3.x 需求6(翻译持久化修复):只在**该曲还没有任何歌词行**时写入。
    // 用途见 MainViewModel 的翻译落库块——译文绑的是"这份歌词文本"的 hash，
    // 文本不落库 → 重启后重新联网匹配到不同文本 → 译文永远查不到。
    // 用 INSERT OR IGNORE 而**不是** upsert：已有行（无论手动还是更晚的自动匹配）
    // 一律不动，绝不把用户更新的歌词版本覆盖回旧文本。
    @Query("INSERT OR IGNORE INTO saved_lyrics(mediaId, lyrics, source, offsetMs, savedAt, autoSaved) VALUES (:mediaId, :lyrics, :source, :offsetMs, :savedAt, 1)")
    suspend fun insertAutoSavedLyricIfAbsent(mediaId: Long, lyrics: String, source: String, offsetMs: Long, savedAt: Long)

    @Query("DELETE FROM saved_lyrics WHERE mediaId = :mediaId")
    suspend fun deleteLyric(mediaId: Long)

    // Alias
    @Query("SELECT * FROM artist_alias")
    fun aliasFlow(): Flow<List<ArtistAliasEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAliases(list: List<ArtistAliasEntity>)

    @Query("DELETE FROM artist_alias WHERE fromName = :fromName")
    suspend fun deleteAlias(fromName: String)

    // Album order
    @Query("SELECT * FROM album_order")
    fun orderFlow(): Flow<List<AlbumOrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrder(e: AlbumOrderEntity)

    @Query("DELETE FROM album_order WHERE albumKey = :albumKey")
    suspend fun deleteOrder(albumKey: String)

    // Ignored folders
    @Query("SELECT * FROM ignored_folder")
    fun ignoredFlow(): Flow<List<IgnoredFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addIgnored(e: IgnoredFolderEntity)

    @Query("DELETE FROM ignored_folder WHERE path = :path")
    suspend fun removeIgnored(path: String)

    // Trash mirror
    @Query("SELECT * FROM trashed_track ORDER BY trashedAt DESC")
    fun trashFlow(): Flow<List<TrashedTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrashed(list: List<TrashedTrackEntity>)

    @Query("DELETE FROM trashed_track WHERE mediaId IN (:ids)")
    suspend fun removeTrashed(ids: List<Long>)

    @Query("DELETE FROM trashed_track")
    suspend fun clearTrashed()

    // Playlists
    @Query("SELECT * FROM playlist ORDER BY sortIdx")
    fun playlistsFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_track")
    fun playlistTracksFlow(): Flow<List<PlaylistTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToPlaylist(e: PlaylistTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(e: PlaylistEntity)

    @Query("DELETE FROM playlist_track WHERE playlistId = :playlistId AND mediaId = :mediaId")
    suspend fun removeFromPlaylist(playlistId: String, mediaId: Long)

    // ── v2.0 artist ──────────────────────────────────────────────────────
    @Query("SELECT * FROM artist")
    fun artistFlow(): Flow<List<ArtistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtist(e: ArtistEntity)

    /** v1.2.0 阶段三：插入新艺术家并返回自增 id（导入用，需 id 映射回 song_artist）。 */
    @Insert
    suspend fun insertArtistGetId(e: ArtistEntity): Long

    @Query("UPDATE artist SET avatarUrl = :url, avatarSource = :source, updatedAt = :now WHERE name = :name")
    suspend fun updateArtistAvatar(name: String, url: String, source: String, now: Long)

    @Query("SELECT * FROM artist WHERE name = :name")
    suspend fun artistByName(name: String): ArtistEntity?

    // ── v1.2.0 #6 artist image（cache + override，独立于 album_art_cache）──────────
    @Query("SELECT * FROM artist_image_cache WHERE name = :name")
    suspend fun artistImageCache(name: String): ArtistImageCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistImageCache(e: ArtistImageCacheEntity)

    @Query("DELETE FROM artist_image_cache WHERE name = :name")
    suspend fun deleteArtistImageCache(name: String)

    /** v1.2.0 #6: 清掉"失败态"行（url 为空）。resolver 启动时调一次，清掉旧版本
     *  6h 失败 TTL 留下的过期失败态——网络变化后源已可恢复，但旧失败行仍挡着重试。 */
    @Query("DELETE FROM artist_image_cache WHERE url = ''")
    suspend fun clearArtistImageFailures()

    @Query("SELECT * FROM artist_image_override WHERE name = :name")
    suspend fun artistImageOverride(name: String): ArtistImageOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistImageOverride(e: ArtistImageOverrideEntity)

    @Query("DELETE FROM artist_image_override WHERE name = :name")
    suspend fun deleteArtistImageOverride(name: String)

    // ── v1.2.2 Agent: LLM tokens 消耗日志 ───────────────────────────────────
    @Insert
    suspend fun insertTokenUsage(e: TokenUsageLogEntity)

    /** 累计 prompt/completion tokens。 */
    @Query("SELECT COALESCE(SUM(promptTokens),0) AS promptTotal, COALESCE(SUM(completionTokens),0) AS completionTotal FROM token_usage_log")
    suspend fun tokenUsageTotals(): TokenTotals

    /**
     * v1.3.10 A19: 近 N 天逐日多指标(用量面板:输入/输出/缓存命中)。
     *
     * ⚠️ `day = (timestamp + :tzOffsetMs) / 86400000` —— **必须加时区偏移**。原实现是
     * `timestamp / 86400000`(纯 UTC 天序号),注释却写着"本地零点":对 UTC+8 用户,天边界
     * 落在本地 08:00,凌晨 0-8 点的用量被算进"昨天",而"今天"又漏掉这 8 小时 ——
     * 用户看到的"当天/今天"数字与实际不符(用户反馈"准度不够"的来源之一)。
     * 偏移由 TokenUsageWindow 按当前时区算出(中国无夏令时,取今天偏移即可)。
     */
    @Query("SELECT ((timestamp + :tzOffsetMs) / 86400000) AS day, SUM(promptTokens) AS inputTokens, SUM(completionTokens) AS outputTokens, SUM(cacheTokens) AS cacheTokens FROM token_usage_log WHERE timestamp >= :since GROUP BY day ORDER BY day")
    suspend fun tokenUsageMetrics(since: Long, tzOffsetMs: Long): List<DailyMetrics>

    /** v1.3.10 A19: 按功能归因(哪个功能在花钱)。按 token 量降序,便于直接渲染榜单。 */
    @Query("SELECT skill AS skill, SUM(promptTokens) AS inputTokens, SUM(completionTokens) AS outputTokens, SUM(cacheTokens) AS cacheTokens, COUNT(*) AS calls FROM token_usage_log WHERE timestamp >= :since GROUP BY skill ORDER BY SUM(promptTokens + completionTokens) DESC")
    suspend fun tokenUsageBySkill(since: Long): List<SkillUsage>

    /** v1.3.10 A19: 范围内 LLM 调用次数(用于"平均每次"与让总量可被核对)。 */
    @Query("SELECT COUNT(*) FROM token_usage_log WHERE timestamp >= :since")
    suspend fun tokenUsageCalls(since: Long): Long

    // ── v1.2.0 #6 backup：写真 cache + override 导出/导入 ──────────────────────
    /** 导出成功的 cache 行（跳过失败 TTL 行，避免把旧网络的失败态毒到新设备）。 */
    @Query("SELECT * FROM artist_image_cache WHERE url != ''")
    suspend fun allArtistImageCacheSuccess(): List<ArtistImageCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllArtistImageCache(list: List<ArtistImageCacheEntity>)

    @Query("SELECT * FROM artist_image_override")
    suspend fun allArtistImageOverride(): List<ArtistImageOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllArtistImageOverride(list: List<ArtistImageOverrideEntity>)

    // song_artist
    @Query("SELECT * FROM song_artist WHERE mediaId = :mediaId")
    suspend fun artistsForTrack(mediaId: Long): List<SongArtistEntity>

    @Query("SELECT * FROM song_artist WHERE artistId = :artistId")
    fun tracksForArtist(artistId: Int): Flow<List<SongArtistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongArtist(e: SongArtistEntity)

    // ── v1.5.0 扫描性能 B2④：ensureArtistEntities 批量化原语 ────────────────
    // 旧实现逐 (曲目,歌手) 对调 artistByName + upsertSongArtist（3000 首 ≈ 3600 次
    // 单行写 + 逐个查询）。改为：一次 IN 查询取已有歌手 → 批量插入缺失 → 再一次 IN
    // 查询拿 id → 批量写 song_artist，整个过程包在一个事务里（见 MainViewModel）。
    /** 一次 IN 查询取回已有歌手（替代逐个 artistByName）。 */
    @Query("SELECT * FROM artist WHERE name IN (:names)")
    suspend fun artistsByNames(names: List<String>): List<ArtistEntity>

    /** 批量插入歌手；name 唯一索引冲突则忽略（已存在的不覆盖 aliases/avatar）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtistsIgnore(list: List<ArtistEntity>)

    /** 批量写 song_artist 链接。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongArtists(list: List<SongArtistEntity>)

    @Query("DELETE FROM song_artist WHERE mediaId = :mediaId")
    suspend fun clearSongArtist(mediaId: Long)

    // play_count
    // v1.2.1: 热度排序只数"有效播放"(累计听满 30 秒, completed=1)的次数;跳过/误触
    // (completed=0)不计入,与 Spotify 30 秒规则一致。从 play_event 聚合,历史数据可用。
    // v22: 再加 kind='track' —— 「完整专辑」整轨播放(kind='album')只计入专辑播放次数,
    // 绝不能漏进单曲热度。存量行 kind 默认为 'track',语义逐字不变。
    @Query("SELECT mediaId, COUNT(*) AS count FROM play_event WHERE completed = 1 AND kind = 'track' GROUP BY mediaId")
    fun playCountFlow(): Flow<List<PlayCountEntity>>

    @Query("SELECT COUNT(*) FROM play_event WHERE mediaId = :mediaId AND completed = 1 AND kind = 'track'")
    suspend fun playCount(mediaId: Long): Int

    // v22: 专辑级计数口径 —— 与单曲口径在数据层完全分离（kind='album'）。
    // 只统计「播放完整专辑」入口的播放次数，mediaId 是容器合成 id。
    @Query("SELECT mediaId, COUNT(*) AS count FROM play_event WHERE completed = 1 AND kind = 'album' GROUP BY mediaId")
    fun albumPlayCountFlow(): Flow<List<PlayCountEntity>>

    @Query("SELECT COUNT(*) FROM play_event WHERE mediaId = :mediaId AND completed = 1 AND kind = 'album'")
    suspend fun albumPlayCount(mediaId: Long): Int

    // album_override
    @Query("SELECT * FROM album_override")
    fun albumOverrideFlow(): Flow<List<AlbumOverrideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbumOverride(e: AlbumOverrideEntity)

    @Query("DELETE FROM album_override WHERE mediaId = :mediaId")
    suspend fun deleteAlbumOverride(mediaId: Long)

    // v4.3: album_info_override — album-level manual edits (name/artist/cover).
    @Query("SELECT * FROM album_info_override")
    fun albumInfoOverrideFlow(): Flow<List<AlbumInfoOverrideEntity>>

    @Query("SELECT * FROM album_info_override WHERE albumId = :albumId")
    suspend fun albumInfoOverride(albumId: Long): AlbumInfoOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbumInfoOverride(e: AlbumInfoOverrideEntity)

    @Query("DELETE FROM album_info_override WHERE albumId = :albumId")
    suspend fun deleteAlbumInfoOverride(albumId: Long)

    // v4.3: track_info_override — single-track manual edits (title/artist/note).
    @Query("SELECT * FROM track_info_override")
    fun trackInfoOverrideFlow(): Flow<List<TrackInfoOverrideEntity>>

    @Query("SELECT * FROM track_info_override WHERE mediaId = :mediaId")
    suspend fun trackInfoOverride(mediaId: Long): TrackInfoOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackInfoOverride(e: TrackInfoOverrideEntity)

    /** v22: 取消拆分时清理失效合成 id 的残留（否则歌单里留下点不响的幽灵项）。 */
    @Query("DELETE FROM track_info_override WHERE mediaId = :mediaId")
    suspend fun deleteTrackInfoOverride(mediaId: Long)

    /** v22: 把某曲从所有歌单里移除（含内置「我的喜欢」）。 */
    @Query("DELETE FROM playlist_track WHERE mediaId = :mediaId")
    suspend fun removeTrackFromAllPlaylists(mediaId: Long)

    // ── v4.3: track_album_move — 单曲迁移到正确专辑 ────────────────────────
    @Query("SELECT * FROM track_album_move")
    fun trackAlbumMoveFlow(): Flow<List<TrackAlbumMoveEntity>>

    @Query("SELECT * FROM track_album_move WHERE mediaId = :mediaId")
    suspend fun trackAlbumMove(mediaId: Long): TrackAlbumMoveEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackAlbumMove(e: TrackAlbumMoveEntity)

    @Query("DELETE FROM track_album_move WHERE mediaId = :mediaId")
    suspend fun deleteTrackAlbumMove(mediaId: Long)

    // ── v22: 专辑整轨拆分为单曲 ────────────────────────────────────────────
    // 一次拆分方案 = album_split 1 行 + split_track N 行。写入走「整体替换」
    // （先删后插，由 VM 的 db.withTransaction 包住），避免逐行 diff 的复杂度。
    @Query("SELECT * FROM album_split")
    fun albumSplitFlow(): Flow<List<AlbumSplitEntity>>

    @Query("SELECT * FROM album_split")
    suspend fun allAlbumSplits(): List<AlbumSplitEntity>

    @Query("SELECT * FROM album_split WHERE sourceMediaId = :sourceMediaId")
    suspend fun albumSplit(sourceMediaId: Long): AlbumSplitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbumSplit(e: AlbumSplitEntity)

    @Query("DELETE FROM album_split WHERE sourceMediaId = :sourceMediaId")
    suspend fun deleteAlbumSplit(sourceMediaId: Long)

    /** 全部拆分单曲，按 (源文件, 序号) 排序 —— lib() 构建时的唯一数据源。 */
    @Query("SELECT * FROM split_track ORDER BY sourceMediaId, ordinal")
    fun splitTrackFlow(): Flow<List<SplitTrackEntity>>

    @Query("SELECT * FROM split_track ORDER BY sourceMediaId, ordinal")
    suspend fun allSplitTracks(): List<SplitTrackEntity>

    @Query("SELECT * FROM split_track WHERE sourceMediaId = :sourceMediaId ORDER BY ordinal")
    suspend fun splitTracksFor(sourceMediaId: Long): List<SplitTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSplitTracks(list: List<SplitTrackEntity>)

    @Query("DELETE FROM split_track WHERE sourceMediaId = :sourceMediaId")
    suspend fun deleteSplitTracksFor(sourceMediaId: Long)

    /** 单首拆分曲（按合成 id 反查）—— 播放/统计路径需要时用。 */
    @Query("SELECT * FROM split_track WHERE id = :rowId")
    suspend fun splitTrack(rowId: Long): SplitTrackEntity?

    // ── v1.3.3b: 歌手页专辑手动排序 ─────────────────────────────────────────
    @Query("SELECT * FROM artist_album_order")
    suspend fun allArtistAlbumOrders(): List<ArtistAlbumOrderEntity>

    @Query("SELECT * FROM artist_album_order WHERE artistName = :artistName")
    suspend fun artistAlbumOrder(artistName: String): ArtistAlbumOrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtistAlbumOrder(e: ArtistAlbumOrderEntity)

    @Query("DELETE FROM artist_album_order WHERE artistName = :artistName")
    suspend fun deleteArtistAlbumOrder(artistName: String)

    // ── v4.3: rematch support ──────────────────────────────────────────────
    // Drop persisted + in-memory art for an album so the next load re-fetches
    // with the edited album name/artist (iTunes match) or the pinned cover.
    @Query("DELETE FROM album_art_cache WHERE albumId = :albumId")
    suspend fun deleteArtCacheForAlbum(albumId: Long)

    // album_art_cache
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbumArtCache(e: AlbumArtCacheEntity)

    @Query("SELECT * FROM album_art_cache WHERE albumId = :albumId")
    suspend fun albumArtCache(albumId: Long): AlbumArtCacheEntity?

    // v4: store/refresh the iTunes releaseDate for an album without rewriting
    // url/source/colors. Used by the artist album list for sort-by-release-date.
    // v1.3.x 修补(用户反馈⑤"返回了时间,结果也录入不进去"):原 UPDATE 只更新
    // 已有行——专辑封面还没缓存过(album_art_cache 无该 albumId 的行)时 UPDATE
    // 影响行数=0,日期根本没写进去。改用 UPSERT:无行时插入占位行(只含日期),
    // 有行时只更 releaseDate(保留封面/取色)。
    @Query("INSERT INTO album_art_cache(albumId, url, source, fetchedAt, bgArgb, fgArgb, releaseDate) VALUES (:albumId, '', 'ai_date', 0, 0, 0, :iso) ON CONFLICT(albumId) DO UPDATE SET releaseDate = :iso")
    suspend fun updateAlbumReleaseDate(albumId: Long, iso: String)

    // v3.0: bulk-load persisted dominant colors at startup so the player/lyrics
    // pages can tint from the album's mood without re-extracting on first paint.
    @Query("SELECT * FROM album_art_cache")
    suspend fun allArtCache(): List<AlbumArtCacheEntity>

    @Query("SELECT COUNT(*) FROM album_art_cache")
    suspend fun albumArtCacheCount(): Int

    // v3.0: persist extracted dominant colors without rewriting url/source/fetchedAt.
    @Query("UPDATE album_art_cache SET bgArgb = :bg, fgArgb = :fg WHERE albumId = :albumId")
    suspend fun updateAlbumArtColors(albumId: Long, bg: Int, fg: Int)

    // v5: 取色回写——只更 bg/fg/source，保留 url/fetchedAt/releaseDate；无行时
    // 插入占位行（url/releaseDate 空，待 iTunes 匹配回填）。取代旧
    // upsertAlbumArtCache(REPLACE)：后者每次取色都把 iTunes 封面 URL 与发行
    // 日期清空。source 升到 PALETTE_VERSION 让 primeColors 装载、避免每次冷
    // 启都重算（source 不升则 primeColors 按 :v5 过滤会跳过该行 → 无限重算）。
    @Query("INSERT INTO album_art_cache(albumId, url, source, fetchedAt, bgArgb, fgArgb, releaseDate) VALUES (:albumId, '', :source, 0, :bg, :fg, '') ON CONFLICT(albumId) DO UPDATE SET bgArgb = :bg, fgArgb = :fg, source = :source")
    suspend fun upsertColorsPreservingMeta(albumId: Long, bg: Int, fg: Int, source: String)

    // v1.2.1: 一条 play_event = 一次"开始播放"。completed=0 起步;累计有效播放满
    // 30 秒后由 markPlayCounted 置 1(计为一次有效播放),切歌时由 finalizePlayEvent
    // 回填 playedSec(实际累计秒数)。最近播放 feed 显示全部事件(含跳过)。
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlayEvent(e: PlayEventEntity): Long

    /** Recent play events, newest first, capped so the 最近播放 feed never
     *  grows unbounded — the UI only shows the last 3 months anyway. */
    @Query("SELECT * FROM play_event ORDER BY playedAt DESC LIMIT :limit")
    suspend fun recentPlayEvents(limit: Int): List<PlayEventEntity>

    /** All play events within [from..to] (epoch millis, inclusive) for weekly aggregation. */
    @Query("SELECT * FROM play_event WHERE playedAt >= :from AND playedAt <= :to ORDER BY playedAt ASC")
    suspend fun playEventsBetween(from: Long, to: Long): List<PlayEventEntity>

    /** v1.2.1: only counted plays (累计听满 30 秒, completed=1) within [from..to] —
     *  收听统计 热度榜/总时长 只数这些,跳过/误触不计入。 */
    @Query("SELECT * FROM play_event WHERE playedAt >= :from AND playedAt <= :to AND completed = 1 ORDER BY playedAt ASC")
    suspend fun completedPlayEventsBetween(from: Long, to: Long): List<PlayEventEntity>

    /** v1.2.1: 累计有效播放满 30 秒时把该次 play_event(按主键 [rowId] 精确定位)置 completed=1。
     *  旧实现"按 mediaId 最新行"子查询会与并发 insertPlayEvent 竞态写错行(同 mediaId 切歌/单曲循环)。
     *  rowId 由 onTrackStarted 插入时返回、回填到 session,全局唯一,无写错行风险。 */
    @Query("UPDATE play_event SET completed = 1 WHERE id = :rowId AND completed = 0")
    suspend fun markPlayCounted(rowId: Long)

    /** v1.2.1: 切歌/播完时回填该次 play_event(按主键 [rowId])的 playedSec。按行 id 定位,无竞态写错行。 */
    @Query("UPDATE play_event SET playedSec = :playedSec WHERE id = :rowId")
    suspend fun finalizePlayEvent(rowId: Long, playedSec: Int)

    /** Trim play events older than [cutoff] — the 最近播放 page only shows
     *  the last 3 months, so anything older is dead data and is physically
     *  deleted here (see requirement: cleanup is acceptable, no archive needed). */
    @Query("DELETE FROM play_event WHERE playedAt < :cutoff")
    suspend fun trimPlayEventsBefore(cutoff: Long)

    // ── v4: 你的更新 (new_album unread reminders) ──────────────────────────
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addNewAlbums(list: List<NewAlbumEntity>)

    /** All unseen new arrivals, newest first. Drives the 你的更新 page. */
    @Query("SELECT * FROM new_album ORDER BY firstSeenAt DESC")
    fun newAlbumsFlow(): Flow<List<NewAlbumEntity>>

    /** Mark an album as "viewed" — deletes its unread row. One-shot lifecycle. */
    @Query("DELETE FROM new_album WHERE albumId = :albumId")
    suspend fun markAlbumViewed(albumId: Long)

    @Query("SELECT albumId FROM new_album")
    suspend fun allNewAlbumIds(): List<Long>

    /** v5.2 Bug2: clear the "你的更新" residuals (one-shot wipe). Used by the
     *  in-page "清空历史" button to discard new_album rows that were seeded
     *  by earlier-version scans and didn't benefit from the v5.1/v5.2
     *  firstScanDone gate. */
    @Query("DELETE FROM new_album")
    suspend fun clearAllNewAlbums()

    // playlist cover (v3.0): set / clear the chosen album cover for a playlist.
    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String)

    @Query("UPDATE playlist SET coverAlbumId = :albumId WHERE id = :id")
    suspend fun setPlaylistCover(id: String, albumId: Long?)

    @Query("DELETE FROM playlist_track WHERE playlistId = :id")
    suspend fun clearPlaylistTracks(id: String)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    // ── v1.1.0: 振假名 Song Override ───────────────────────────────────────
    /** 某曲某版本的全部 Song Override（按出现位置定位）。 */
    @Query("SELECT * FROM reading_override WHERE scope = 'SONG' AND mediaId = :mediaId AND lyricsHash = :lyricsHash")
    suspend fun songReadingOverrides(mediaId: Long, lyricsHash: String): List<ReadingOverrideEntity>

    /** 全局 Override（稳定词汇；当前主要靠内存小表，此查询预留扩展）。 */
    @Query("SELECT * FROM reading_override WHERE scope = 'GLOBAL'")
    suspend fun globalReadingOverrides(): List<ReadingOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadingOverride(e: ReadingOverrideEntity)

    /** 删除某曲某版本某出现位置的 Song Override（用户在编辑器里清空读法时）。 */
    @Query("DELETE FROM reading_override WHERE scope = 'SONG' AND mediaId = :mediaId AND lyricsHash = :lyricsHash AND lineIndex = :lineIndex AND charStart = :charStart")
    suspend fun deleteReadingOverride(mediaId: Long, lyricsHash: String, lineIndex: Int, charStart: Int)

    /** 删除某曲所有版本的 Song Override（删除歌词时级联清理，避免孤儿）。 */
    @Query("DELETE FROM reading_override WHERE scope = 'SONG' AND mediaId = :mediaId")
    suspend fun deleteReadingOverridesForMedia(mediaId: Long)

    // ── v1.1+: 外部歌词假名 evidence 缓存 ─────────────────────────────────
    /** 某曲某版本的全部外部 evidence（occurrence 读法）。 */
    @Query("SELECT * FROM external_reading_evidence WHERE mediaId = :mediaId AND lyricsHash = :lyricsHash")
    suspend fun externalEvidence(mediaId: Long, lyricsHash: String): List<ExternalReadingEvidenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExternalEvidence(list: List<ExternalReadingEvidenceEntity>)

    @Query("DELETE FROM external_reading_evidence WHERE mediaId = :mediaId AND lyricsHash = :lyricsHash")
    suspend fun deleteExternalEvidence(mediaId: Long, lyricsHash: String)

    @Query("DELETE FROM external_reading_evidence WHERE mediaId = :mediaId")
    suspend fun deleteExternalEvidenceForMedia(mediaId: Long)

    // ── v1.3.x 需求6:歌词翻译结果（AI 档）───────────────────────────────────
    /** 某曲某版本已保存的译文（一行，payload 内含全部行）。 */
    @Query("SELECT * FROM lyrics_translation WHERE mediaId = :mediaId AND lyricsHash = :lyricsHash")
    suspend fun lyricsTranslation(mediaId: Long, lyricsHash: String): LyricsTranslationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLyricsTranslation(row: LyricsTranslationEntity)

    @Query("DELETE FROM lyrics_translation WHERE mediaId = :mediaId AND lyricsHash = :lyricsHash")
    suspend fun deleteLyricsTranslation(mediaId: Long, lyricsHash: String)

    @Query("DELETE FROM lyrics_translation WHERE mediaId = :mediaId")
    suspend fun deleteLyricsTranslationForMedia(mediaId: Long)

    // ── v1.4.0: 歌词 hash 语义变更的**一次性搬键** ──────────────────────────
    // 背景:hash 算法改为"剥掉行首 [mm:ss.xx] 时间戳"(修"打轴后翻译恢复不到")。
    // 于是升级后按**新** hash 查不到升级前存下的注音/译文 —— 用户看到"我的注音/译文不见了"。
    // 迁移 = 把旧 hash 名下的行改写到新 hash 名下;搬完所有读取点(都用 LyricsHash.of)自然命中。
    //
    // 守卫:先查"新 hash 名下是否已经有行"(countRowsUnderHash)。有 = 用户升级后又存取过,
    // 旧行属下堂,直接跳过 —— 避免 UPDATE 撞主键(三张表的 PK 都含 lyricsHash)。
    @Query(
        "SELECT (SELECT COUNT(*) FROM reading_override WHERE scope = 'SONG' AND mediaId = :mediaId AND lyricsHash = :newHash)" +
            " + (SELECT COUNT(*) FROM external_reading_evidence WHERE mediaId = :mediaId AND lyricsHash = :newHash)" +
            " + (SELECT COUNT(*) FROM lyrics_translation WHERE mediaId = :mediaId AND lyricsHash = :newHash)",
    )
    suspend fun countRowsUnderHash(mediaId: Long, newHash: String): Int

    @Query("UPDATE reading_override SET lyricsHash = :newHash WHERE scope = 'SONG' AND mediaId = :mediaId AND lyricsHash = :oldHash")
    suspend fun migrateReadingOverrideHash(mediaId: Long, oldHash: String, newHash: String)

    @Query("UPDATE external_reading_evidence SET lyricsHash = :newHash WHERE mediaId = :mediaId AND lyricsHash = :oldHash")
    suspend fun migrateExternalEvidenceHash(mediaId: Long, oldHash: String, newHash: String)

    @Query("UPDATE lyrics_translation SET lyricsHash = :newHash WHERE mediaId = :mediaId AND lyricsHash = :oldHash")
    suspend fun migrateLyricsTranslationHash(mediaId: Long, oldHash: String, newHash: String)

    // ── v1.3.9: Agent 对话持久化 ──────────────────────────────────────────
    /** 全部对话，收藏置顶 + 最近更新在前。 */
    @Query("SELECT * FROM agent_conversation ORDER BY pinned DESC, updatedAt DESC")
    suspend fun allAgentConversations(): List<AgentConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgentConversation(e: AgentConversationEntity)

    @Query("DELETE FROM agent_conversation WHERE id = :id")
    suspend fun deleteAgentConversation(id: String)

    @Query("UPDATE agent_conversation SET title = :title WHERE id = :id")
    suspend fun renameAgentConversation(id: String, title: String)

    @Query("UPDATE agent_conversation SET pinned = :pinned WHERE id = :id")
    suspend fun setAgentConversationPinned(id: String, pinned: Int)

    // ── v1.3.10: Agent 长期记忆（P1：数据层；裁决逻辑在 data/memory/） ────────
    @Query("SELECT * FROM agent_memory")
    suspend fun agentMemories(): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_memory WHERE id = :id")
    suspend fun agentMemory(id: String): AgentMemoryEntity?

    /** 写一条记忆。
     *  ⚠️ REPLACE 语义:与唯一索引 `index_agent_memory_id` 冲突时,SQLite 会**删旧行再插新行**
     *  —— 即"更新"在物理上是换了一行(rowId 会变)。当前没有任何逻辑依赖 rowId(FTS 行按
     *  memoryId 关联、按 memoryId 删),所以这是有意为之;若将来要用 rowId,须先改这里。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgentMemory(e: AgentMemoryEntity)

    @Query("DELETE FROM agent_memory WHERE id = :id")
    suspend fun deleteAgentMemory(id: String)

    /** FTS 行:content/tags 存的是**二元切分展开后**的文本(见 MemoryRules.ftsText)。 */
    @Insert
    suspend fun insertAgentMemoryFts(e: AgentMemoryFtsEntity)

    @Query("DELETE FROM agent_memory_fts WHERE memoryId = :memoryId")
    suspend fun deleteAgentMemoryFts(memoryId: String)

    /** FTS 预筛:返回命中的 memoryId。matchQuery 必须是 [MemoryRules.ftsMatchQuery] 生成的安全表达式
     *  (FTS 的 MATCH 有自己的语法,裸关键词里的符号会让它报错)。 */
    @Query("SELECT memoryId FROM agent_memory_fts WHERE agent_memory_fts MATCH :matchQuery LIMIT :limit")
    suspend fun searchAgentMemoryFts(matchQuery: String, limit: Int): List<String>

    // ── v1.1+: 自动识别缓存清理 ─────────────────────────────────────────────
    /** 清理「自动识别」持久化的歌词（autoSaved=1）与全部封面缓存。保留：手动保存/导入的
     *  歌词(autoSaved=0)、Song Override(reading_override)、外部假名 evidence、track_info_override。
     *  用户空间不够时一键清空重新识别。返回清理掉的 saved_lyrics 行数。 */
    @Query("DELETE FROM saved_lyrics WHERE autoSaved = 1")
    suspend fun deleteAutoSavedLyrics()

    @Query("DELETE FROM album_art_cache")
    suspend fun clearAlbumArtCache()

    // ── v1.2.0 阶段三：数据导出/导入（不改表结构，只加全表读 + 批量 upsert）──
    // 导出：一次性读全表（suspend，非 Flow）。
    @Query("SELECT * FROM album_override")
    suspend fun allAlbumOverride(): List<AlbumOverrideEntity>
    @Query("SELECT * FROM album_info_override")
    suspend fun allAlbumInfoOverride(): List<AlbumInfoOverrideEntity>
    @Query("SELECT * FROM track_info_override")
    suspend fun allTrackInfoOverride(): List<TrackInfoOverrideEntity>
    @Query("SELECT * FROM track_album_move")
    suspend fun allTrackAlbumMove(): List<TrackAlbumMoveEntity>
    @Query("SELECT * FROM artist")
    suspend fun allArtists(): List<ArtistEntity>
    @Query("SELECT * FROM song_artist")
    suspend fun allSongArtist(): List<SongArtistEntity>
    @Query("SELECT * FROM reading_override")
    suspend fun allReadingOverride(): List<ReadingOverrideEntity>
    @Query("SELECT * FROM external_reading_evidence")
    suspend fun allExternalReadingEvidence(): List<ExternalReadingEvidenceEntity>

    // 导入：批量 upsert（REPLACE，覆盖同主键）。.artist 的 id 是 autGenerate，
    // 跨设备导入会撞自增 id——导入时按 name 去重走 upsertArtist（已有则合并 aliases），
    // 故 artist 不在此批量 upsert，由 BackupImporter 单独处理。
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllAlbumOverride(list: List<AlbumOverrideEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllAlbumInfoOverride(list: List<AlbumInfoOverrideEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllTrackInfoOverride(list: List<TrackInfoOverrideEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllTrackAlbumMove(list: List<TrackAlbumMoveEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllSongArtist(list: List<SongArtistEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllReadingOverride(list: List<ReadingOverrideEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllExternalReadingEvidence(list: List<ExternalReadingEvidenceEntity>)
    // v1.3.x 需求6:翻译结果随备份走——与外部假名 evidence 同一理由(派生数据但
    // 重建要花钱/花流量:译文重译要再烧一次 token)。主键 (mediaId, lyricsHash) 无自增 id,直接 upsert。
    @Query("SELECT * FROM lyrics_translation")
    suspend fun allLyricsTranslations(): List<LyricsTranslationEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllLyricsTranslations(list: List<LyricsTranslationEntity>)

    // v22: 拆分方案随「用户修正数据」一起导出（不可由重新扫描重建）。
    // 导入时 sourceMediaId 可能失效，由 BackupImporter 用 sourcePath 重解析。
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllAlbumSplit(list: List<AlbumSplitEntity>)

    // ── v22: 备份内容扩展 —— 补上原来漏掉的用户资产 ────────────────────────
    // 原来只备"元数据修正"，把用户最在乎的东西全漏了：歌单（含「我的喜欢」）、
    // 手动导入的歌词、手动排序、忽略的文件夹、统计。这些一并纳入。

    @Query("SELECT * FROM playlist")
    suspend fun allPlaylists(): List<PlaylistEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllPlaylists(list: List<PlaylistEntity>)

    @Query("SELECT * FROM playlist_track")
    suspend fun allPlaylistTracks(): List<PlaylistTrackEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllPlaylistTracks(list: List<PlaylistTrackEntity>)

    /** 只导出手动导入/确认过的歌词（`autoSaved = 0`）—— 自动识别的可重新识别，
     *  没必要占备份体积；而手动导入的 lrc / 手改过的歌词**不可能靠重扫找回**。 */
    @Query("SELECT * FROM saved_lyrics WHERE autoSaved = 0")
    suspend fun allManualLyrics(): List<SavedLyricEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllLyrics(list: List<SavedLyricEntity>)

    @Query("SELECT * FROM album_order")
    suspend fun allAlbumOrders(): List<AlbumOrderEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllAlbumOrders(list: List<AlbumOrderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllArtistAlbumOrders(list: List<ArtistAlbumOrderEntity>)

    @Query("SELECT * FROM ignored_folder")
    suspend fun allIgnoredFolders(): List<IgnoredFolderEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllIgnoredFolders(list: List<IgnoredFolderEntity>)

    // 统计两张表的 id 是自增：导入时**清零 id 追加**（而不是 REPLACE 原 id）——
    // 否则跨设备的自增 id 会撞上本机已有行、把别人的统计覆盖掉。
    // 去重由 BackupManager 用 (mediaId, playedAt) / (timestamp) 键在导入前过滤，
    // 保证同一份备份重复导入不会翻倍。
    @Query("SELECT * FROM play_event")
    suspend fun allPlayEvents(): List<PlayEventEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPlayEvents(list: List<PlayEventEntity>)

    /** 导入去重用：本机已有的 (mediaId, playedAt) 组合。 */
    @Query("SELECT mediaId, playedAt FROM play_event")
    suspend fun allPlayEventKeys(): List<PlayEventKey>

    @Query("SELECT * FROM token_usage_log")
    suspend fun allTokenUsage(): List<TokenUsageLogEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllTokenUsage(list: List<TokenUsageLogEntity>)

    /** 导入去重用：本机已有的 timestamp。 */
    @Query("SELECT timestamp FROM token_usage_log")
    suspend fun allTokenUsageTimestamps(): List<Long>
}

/** 导入去重用的投影（play_event 的天然唯一键）。 */
data class PlayEventKey(val mediaId: Long, val playedAt: Long)

@Database(
    entities = [
        SavedLyricEntity::class, ArtistAliasEntity::class, AlbumOrderEntity::class,
        IgnoredFolderEntity::class, TrashedTrackEntity::class,
        PlaylistEntity::class, PlaylistTrackEntity::class,
        ArtistEntity::class, SongArtistEntity::class, PlayCountEntity::class,
        AlbumOverrideEntity::class, AlbumArtCacheEntity::class,
        // v4: timestamped play events + 你的更新 unread reminders
        PlayEventEntity::class, NewAlbumEntity::class,
        // v4.3: album-level manual edits (name/artist + custom cover)
        AlbumInfoOverrideEntity::class,
        // v4.3: single-track manual edits (title/artist/note)
        TrackInfoOverrideEntity::class,
        // v4.3: single-track re-parenting into its real album
        TrackAlbumMoveEntity::class,
        // v1.1.0: 振假名 Song Override（歌曲专属读法/当て字）
        ReadingOverrideEntity::class,
        // v1.1+: 外部歌词假名 evidence 缓存（UtaTen 等对齐后的 occurrence 读法）
        ExternalReadingEvidenceEntity::class,
        // v1.2.0 #6: 歌手写真 cache + 用户手选 override（独立于 album_art_cache）
        ArtistImageCacheEntity::class, ArtistImageOverrideEntity::class,
        // v1.2.2 Agent: LLM tokens 消耗日志
        TokenUsageLogEntity::class,
        // v1.3.3b: 歌手页专辑手动排序
        ArtistAlbumOrderEntity::class,
        // v1.3.x 需求6: 歌词翻译结果（AI 档，译完即存、下次自动恢复）
        LyricsTranslationEntity::class,
        // v22: 专辑整轨拆分为单曲（方案 + 拆分曲目）
        AlbumSplitEntity::class, SplitTrackEntity::class,
        // v1.3.9: Agent 对话持久化（按对话组织的历史）
        AgentConversationEntity::class,
        // v1.3.10: Agent 长期记忆（数据层 + FTS4 检索表）
        AgentMemoryEntity::class, AgentMemoryFtsEntity::class,
    ],
    version = 25,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ShiyinDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS artist (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        aliases TEXT NOT NULL,
                        avatarUrl TEXT NOT NULL,
                        avatarSource TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_artist_name ON artist(name)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS song_artist (
                        mediaId INTEGER NOT NULL,
                        artistId INTEGER NOT NULL,
                        PRIMARY KEY(mediaId, artistId)
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS play_count (
                        mediaId INTEGER NOT NULL,
                        count INTEGER NOT NULL,
                        PRIMARY KEY(mediaId)
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS album_override (
                        mediaId INTEGER NOT NULL,
                        albumName TEXT NOT NULL,
                        artistName TEXT NOT NULL,
                        PRIMARY KEY(mediaId)
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS album_art_cache (
                        albumId INTEGER NOT NULL,
                        url TEXT NOT NULL,
                        source TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(albumId)
                    )
                """)
            }
        }

        // v3.0: add dominant-color columns to album_art_cache (bgArgb/fgArgb)
        // and a nullable playlist cover column. All default to 0/NULL so old
        // rows read as "not yet extracted" / "auto mosaic".
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE album_art_cache ADD COLUMN bgArgb INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE album_art_cache ADD COLUMN fgArgb INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlist ADD COLUMN coverAlbumId INTEGER DEFAULT NULL")
            }
        }

        // v4: timestamped play events + 你的更新 (new_album) + releaseDate on album_art_cache.
        //   - play_event: the first time history ever has per-play timestamps;
        //     enables 最近播放 (last 3 months) and 收听统计 (weekly aggregates).
        //   - new_album: one-shot "newly scanned & not yet viewed" reminders.
        //   - album_art_cache.releaseDate: iTunes store-date for album sort
        //     (UI must NOT label it as the official release date).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE album_art_cache ADD COLUMN releaseDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS play_event (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mediaId INTEGER NOT NULL,
                        playedAt INTEGER NOT NULL,
                        durationSec INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_play_event_playedAt ON play_event(playedAt)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS new_album (
                        albumId INTEGER NOT NULL,
                        firstSeenAt INTEGER NOT NULL,
                        PRIMARY KEY(albumId)
                    )
                """)
            }
        }

        // v5→v1.2.1: 此迁移首次加 `completed` 列。v5 原语义=播到结尾;v1.2.1 起语义改为
        // "累计真实播放≥30秒=已计入有效播放"(不再指播到结尾)。此处仅加列,不改 SQL;
        // 旧存量行 completed=0(迁移时 DEFAULT 0),只在 最近播放 feed 显,不进统计。
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_event ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v6: album_info_override — album-level manual edits (name/artist/cover).
        // Decoupled from track-level album_override so re-parenting a single
        // track and fixing whole-album metadata are independent concerns.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS album_info_override (
                        albumId INTEGER NOT NULL PRIMARY KEY,
                        albumName TEXT NOT NULL DEFAULT '',
                        artistName TEXT NOT NULL DEFAULT '',
                        coverUri TEXT NOT NULL DEFAULT '',
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // v7: track_info_override — single-track display-level edits
        // (title/artist/note). Keyed by mediaId; empty columns mean "keep
        // the scanned value". Underlying file tags are never rewritten.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS track_info_override (
                        mediaId INTEGER NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL DEFAULT '',
                        artist TEXT NOT NULL DEFAULT '',
                        note TEXT NOT NULL DEFAULT '',
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // v8: track_album_move — display-level re-parenting of a single into
        // its real album (fixes scan errors where one song got its own albumId).
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS track_album_move (
                        mediaId INTEGER NOT NULL PRIMARY KEY,
                        albumId INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // v9: album_info_override.type — manual override of an album's category
        // (专辑/EP/单曲). "" keeps the heuristic. Backfills as "" for existing
        // rows so they keep auto-classifying exactly as before.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE album_info_override ADD COLUMN type TEXT NOT NULL DEFAULT ''")
            }
        }

        // v5.2 隐藏曲:加 TrackInfoOverrideEntity.hidden INTEGER NOT NULL DEFAULT 0
        // 列。复用已有的 track_info_override 表(原本是 title/artist/note 的 per-track
        // 覆盖),hidden 跟它们同粒度——一TrackInfoOverride 行存一首曲的所有
        // 手工设置。DEFAULT 0 表示已存在的曲默认"未隐藏",行为不变。
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_info_override ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v1.1.0: 取色算法在 360b90f 重写（聚类合并 + 亮度下限），但旧颜色早已
        // 持久化进 album_art_cache、启动时 primeColors 装进内存缓存，ensureColors
        // 命中缓存就短路、根本不会再走新算法——所以旧库里取色"没变"。此迁移把
        // 所有已缓存的 bgArgb/fgArgb 清零：primeColors 跳过 0/0 的行，下次打开
        // 播放页/歌词页时 ensureColors 会用新算法重新提取并回填。url/source 等
        // 封面缓存字段不动，只清颜色。
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE album_art_cache SET bgArgb = 0, fgArgb = 0")
            }
        }

        // v1.1.0: 振假名 Song Override 表。按出现位置（lineIndex+charStart）定位，使同
        // 一 surface 多次出现可分别设不同读法。绑 mediaId+lyricsHash+lineIndex+charStart。
        // ⚠️ 用 DROP+CREATE 而非 CREATE TABLE IF NOT EXISTS：早期 dev 构建曾用「surface 做
        // 主键」的旧 schema 建过此表，IF NOT EXISTS 对旧表是 no-op，会导致迁移后 schema 与
        // 实体不一致 → Room 校验崩溃（code-review 启动即崩 bug）。DROP 重建保证 schema 正确。
        // 该表仅 dev 构建存在过（正式版从未发布），DROP 不丢生产用户数据。
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS reading_override")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reading_override (
                        scope TEXT NOT NULL,
                        mediaId INTEGER NOT NULL,
                        lyricsHash TEXT NOT NULL,
                        lineIndex INTEGER NOT NULL,
                        charStart INTEGER NOT NULL,
                        surface TEXT NOT NULL,
                        reading TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(scope, mediaId, lyricsHash, lineIndex, charStart)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_override_mediaId_lyricsHash ON reading_override(mediaId, lyricsHash)")
            }
        }

        // v1.1+: 外部歌词假名 evidence 缓存表。UtaTen 等带假名歌词站解析对齐后的
        // occurrence 读法，按 (mediaId+lyricsHash+lineIndex+charStart) 定位。联网一次后离线复用。
        // 同 MIGRATION_11_12：用 DROP+CREATE 防 dev 构建残留旧 schema 导致 Room 校验崩溃。
        // 额外：本迁移也重建 reading_override（DROP+CREATE）——若设备已停在 v12（旧 dev 构建跑过
        // 11→12 建了「surface 主键」的坏表），11_12 的 DROP 不会再触发，须在 12_13 补一次重建，
        // 保证无论设备停在 v11/v12/v13，迁移后两表 schema 都与实体一致。
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS external_reading_evidence")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS external_reading_evidence (
                        mediaId INTEGER NOT NULL,
                        lyricsHash TEXT NOT NULL,
                        lineIndex INTEGER NOT NULL,
                        charStart INTEGER NOT NULL,
                        length INTEGER NOT NULL,
                        surface TEXT NOT NULL,
                        reading TEXT NOT NULL,
                        source TEXT NOT NULL,
                        confidence INTEGER NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        PRIMARY KEY(mediaId, lyricsHash, lineIndex, charStart)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_external_reading_evidence_mediaId_lyricsHash ON external_reading_evidence(mediaId, lyricsHash)")
                // 重建 reading_override 到正确 schema（lineIndex+charStart 主键）
                db.execSQL("DROP TABLE IF EXISTS reading_override")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reading_override (
                        scope TEXT NOT NULL,
                        mediaId INTEGER NOT NULL,
                        lyricsHash TEXT NOT NULL,
                        lineIndex INTEGER NOT NULL,
                        charStart INTEGER NOT NULL,
                        surface TEXT NOT NULL,
                        reading TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(scope, mediaId, lyricsHash, lineIndex, charStart)
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_override_mediaId_lyricsHash ON reading_override(mediaId, lyricsHash)")
            }
        }

        // v1.1+: saved_lyrics 加 autoSaved 列。=1 为自动识别匹配后由"自动保存识别结果"
        // 开关自动持久化（清理缓存时清掉重新识别）；=0 为用户手动确认/导入（保留）。
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_lyrics ADD COLUMN autoSaved INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v1.2.0 #6: 歌手写真独立持久层（与 album_art_cache 分离）。cache 存自动源结果
        // （含失败 TTL，避免对死源反复重试），override 存用户手选写真（最高优先级，自动源
        // 永不覆盖）。旧 artist.avatarUrl 不迁移——那是专辑封面（非人物照），头图改走新
        // cache 重新向 Discogs 等人物源取正面照。见 ArtistImageResolver。
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS artist_image_cache (
                        name TEXT NOT NULL PRIMARY KEY,
                        url TEXT NOT NULL,
                        source TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL,
                        failUntilTs INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS artist_image_override (
                        name TEXT NOT NULL PRIMARY KEY,
                        url TEXT NOT NULL,
                        chosenAt INTEGER NOT NULL
                    )
                """)
            }
        }

        // v1.2.0 #6: artist_image_cache 加图片元数据列（width/height/aspectRatio/imageType），
        // 供 UI 按比例决定裁剪方式，避免宽 banner/logo 类图破坏歌手主页头图。
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artist_image_cache ADD COLUMN width INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE artist_image_cache ADD COLUMN height INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE artist_image_cache ADD COLUMN aspectRatio REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE artist_image_cache ADD COLUMN imageType TEXT NOT NULL DEFAULT ''")
            }
        }

        // v1.2.0 #6: 保证内置「我的喜欢」(p3)在升级安装也存在(原仅 onCreate 新装 seed，
        // 升级用户 DB 早于 seed 无 p3 行→歌单筛选看不到「我的喜欢」)；并把旧名「我最喜爱」
        // 改「我的喜欢」(用户措辞)。AND name='我最喜爱' 保留用户自定义名不被覆盖。
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT OR IGNORE INTO playlist (id, name, sortIdx) VALUES ('p3', '我的喜欢', 0)")
                db.execSQL("UPDATE playlist SET name='我的喜欢' WHERE id='p3' AND name='我最喜爱'")
            }
        }

        // v1.2.1: play_count 计数口径从"开始播放即+1"改为 Spotify 式"累计有效播放满 30
        // 秒才算一次"。新增 playedSec 记录每次播放的实际累计有效秒数(暂停/继续累加、
        // seek 剔除),供 收听统计 总时长准确求和——旧口径用 durationSec(曲目总长)在
        // 新阈值下会把"听满30秒"误算成"听完整首"。completed 语义从"播到结尾"改为
        // "累计≥30秒";历史 completed=1 行(旧=播到结尾)对 >30s 曲目仍成立,保留。
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_event ADD COLUMN playedSec INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v1.2.2 Agent: token_usage_log 表
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS token_usage_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        skill TEXT NOT NULL,
                        promptTokens INTEGER NOT NULL,
                        completionTokens INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // v1.3.3b: 歌手页专辑手动排序表
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS artist_album_order (
                        artistName TEXT PRIMARY KEY NOT NULL,
                        albumKeys TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // v1.3.6: token_usage_log 加 cacheTokens 列(缓存命中 token,用量面板曲线)
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE token_usage_log ADD COLUMN cacheTokens INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v1.3.x 需求6: 歌词翻译结果表。译完即存 → 下次打开歌词本自动恢复,
        // 不再重复调 LLM 烧 token。绑 (mediaId, lyricsHash) 与振假名 evidence 同键法。
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS lyrics_translation (
                        mediaId INTEGER NOT NULL,
                        lyricsHash TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        source TEXT NOT NULL,
                        translatedAt INTEGER NOT NULL,
                        PRIMARY KEY(mediaId, lyricsHash)
                    )
                """.trimIndent())
            }
        }        // v22: 专辑整轨拆分为单曲 —— 两张新表 + play_event 的两套统计口径隔离位。
        //
        // ⚠️ 为什么是 22→23 而不是并进上面的 21→22：
        // 「歌词翻译」（21→22）与「专辑拆分」（本迁移）是**两条并行开发线**，各自都把
        // 版本号提到过 22。如果把它们并成一个 21→22，那么**已经装过翻译那版构建的设备**
        // （DB 已是 v22、只有 lyrics_translation 表）会**跳过迁移** → 启动后一查
        // album_split/split_track 就崩。改成 22→23 后两种情况都覆盖：
        //   · 从 v21（正式版）升级 → 跑 21→22（翻译表）再跑 22→23（本迁移）✓
        //   · 从翻译那版 v22 升级 → 只跑 22→23 ✓
        //
        // 建表 SQL 必须与 Room 由实体推导出的期望 schema 逐字对齐(列类型/NOT NULL/
        // 主键位置/索引名),否则启动时 Room 的 identity 校验会抛 IllegalStateException。
        // 索引名沿用 Room 的 `index_<表>_<列>[_<列>]` 约定。
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `album_split` (
                        `sourceMediaId` INTEGER NOT NULL,
                        `sourcePath` TEXT NOT NULL,
                        `status` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sourceMediaId`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `split_track` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sourceMediaId` INTEGER NOT NULL,
                        `ordinal` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_split_track_sourceMediaId` ON `split_track` (`sourceMediaId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_split_track_sourceMediaId_ordinal` ON `split_track` (`sourceMediaId`, `ordinal`)")
                // 存量行全部落 'track' → 既有查询加 AND kind='track' 后语义逐字不变。
                db.execSQL("ALTER TABLE play_event ADD COLUMN kind TEXT NOT NULL DEFAULT 'track'")
            }
        }

        // v1.3.9: Agent 对话持久化表。历史按「对话」组织（主流 AI 产品同构），
        // 消息列表整体 Gson 存 messagesJson。新建表，存量设备无数据迁移负担。
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS agent_conversation (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        messagesJson TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        // v1.3.10: Agent 长期记忆(P1:数据层)——记忆表 + FTS4 检索表。
        //
        // ⚠️ 本段 DDL **逐字取自 Room 自己生成的 schema**(临时开 exportSchema 编译后从
        // 25.json 抄出)。Room 在打开数据库时会校验实际结构与期望结构是否一致,不一致直接抛异常
        // (且本项目刻意不用 fallbackToDestructiveMigration —— 迁移失败宁可崩也不删用户数据)。
        // 所以这里不要"顺手美化"SQL,不要调整列序/NOT NULL/AUTOINCREMENT。
        // 将来改动这两张表时,同样先导出 schema 再抄。
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_memory` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `importance` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, `tags` TEXT NOT NULL, `supersededBy` TEXT, `embedding` TEXT)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_memory_id` ON `agent_memory` (`id`)")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `agent_memory_fts` USING FTS4(" +
                        "`memoryId` TEXT NOT NULL, `content` TEXT NOT NULL, `tags` TEXT NOT NULL)",
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "shiyin.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25)
                // 不使用 fallbackToDestructiveMigration：迁移失败时宁可崩溃也不要删全库。
                // 之前该选项导致迁移异常时删除所有用户数据（排序、专辑名、播放历史等）。
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("INSERT INTO playlist (id, name, sortIdx) VALUES ('p3', '我的喜欢', 0)")
                    }
                })
                .build()
                .also { instance = it }
        }
    }
}
package com.shiyin.music.data.db

import com.google.gson.Gson
import androidx.room.withTransaction
import com.shiyin.music.data.SettingsStore
import com.shiyin.music.data.db.AlbumOverrideEntity as ALO
import com.shiyin.music.data.db.AlbumInfoOverrideEntity as AIO
import com.shiyin.music.data.db.TrackInfoOverrideEntity as TIO
import com.shiyin.music.data.db.TrackAlbumMoveEntity as TAM
import com.shiyin.music.data.db.ArtistEntity as ART
import com.shiyin.music.data.db.SongArtistEntity as SA
import com.shiyin.music.data.db.ReadingOverrideEntity as RO
import com.shiyin.music.data.db.ExternalReadingEvidenceEntity as ERE
import com.shiyin.music.data.db.LyricsTranslationEntity as LT
import com.shiyin.music.data.db.ArtistImageCacheEntity as AIC
import com.shiyin.music.data.db.ArtistImageOverrideEntity as AIOV
import com.shiyin.music.data.db.AlbumSplitEntity as ASP
import com.shiyin.music.data.db.SplitTrackEntity as STK
import com.shiyin.music.data.db.PlaylistEntity as PL
import com.shiyin.music.data.db.PlaylistTrackEntity as PLT
import com.shiyin.music.data.db.SavedLyricEntity as SL
import com.shiyin.music.data.db.AlbumOrderEntity as AO
import com.shiyin.music.data.db.ArtistAlbumOrderEntity as AAO
import com.shiyin.music.data.db.IgnoredFolderEntity as IF
import com.shiyin.music.data.db.PlayEventEntity as PE
import com.shiyin.music.data.db.TokenUsageLogEntity as TU
import com.shiyin.music.data.db.AgentMemoryEntity as AME

/**
 * 用户数据的导出/导入。
 *
 * === v1.2.0 阶段的定位（"修正数据"）===
 * 当时只导出"用户手动编辑、不可由重新扫描重建的数据"：
 *  - album_override / album_info_override / track_info_override / track_album_move
 *    （专辑名·艺术家·封面·type 修正、单曲 title/artist/note/隐藏、单曲迁专辑）
 *  - artist（歌手合并 aliases + 头像）/ song_artist（曲目-歌手关联）
 *  - reading_override（振假名当て字）/ external_reading_evidence（外部假名 evidence）
 *  - artist_image_override（歌手手选写真，永久用户资产）/ artist_image_cache（自动源命中缓存，
 *    仅导出成功行，避免重抓；跨设备不导出失败 TTL 态以免毒到新网络）
 *  - lyrics_translation（v1.3.x 需求6：歌词翻译结果。虽是派生数据，但**重建要再烧一次
 *    LLM token**——与 external_reading_evidence 同一理由，故随备份走）
 *
 * === v22 的重要修正：这个定位对用户心里的"备份"来说差得太远 ===
 * 原来**没备**的东西恰恰是用户最在乎的：
 *  - **歌单（含内置「我的喜欢」）** —— 用户整理最久的资产
 *  - **手动导入/确认过的歌词**（`autoSaved = 0`）—— 这些**不可能靠"重新识别"找回**
 *  - **专辑内手动曲序** / 歌手页专辑排序 —— 明确的人工整理成果
 *  - **忽略的文件夹** —— 用户配置
 *  - **全部设置与凭证（含 LLM 供应商 / API Key）** —— 换机后要从零再填一遍
 *  - 播放统计 / Token 用量（纯统计，可关）
 *
 * === 跨设备的核心问题：mediaId 不稳定 ===
 * `play_event` / `playlist_track` / `saved_lyrics` / `album_order` 等都按 MediaStore
 * 的 `_ID` 存，而换机 / 重扫后这个 id 必变。**v2 的重解析键（见 BackupRemap）负责
 * 在导入时把旧 id 映射到新 id**，映射不到的条目丢弃并如实报告 —— 没有这一步，
 * 恢复出来的修正会全部挂到错误的歌上。
 */
internal class BackupManager(
    /** v1.4.0：改为接 DB 而非 DAO —— 导入需要 `withTransaction` 把全部写入包成一个原子操作。 */
    private val db: AppDatabase,
    /** 设置也随备份走（v22）。为 null 时跳过设置部分（便于只测数据库的单元测试）。 */
    private val settings: SettingsStore? = null,
) {
    private val dao: ShiyinDao = db.dao()

    companion object {
    /**
     * v1.5.0 A60：按分类裁剪。**做成"生成全量再裁剪"而不是"按需查询"**——
     * 单测可以直接比较两种配置的产物，且与 [categorySizes] 共用同一条路径，
     * 保证"显示的大小"与"实际导出的大小"永远一致。
     */
        fun applyCategories(
            d: BackupData,
            cats: Set<String>,
            /** 视为「凭据」的设置键（由 SettingsStore.BACKUP_SECRET_KEYS 提供）。 */
            secretKeys: Set<String> = emptySet(),
        ): BackupData {
        fun on(id: String) = id in cats
        val normalSettings = d.settings.filterKeys { it !in secretKeys }
        val secretSettings = d.settings.filterKeys { it in secretKeys }
        return d.copy(
            // 曲库整理
            albumOverride = if (on(BackupCategory.EDITS.id)) d.albumOverride else emptyList(),
            albumInfoOverride = if (on(BackupCategory.EDITS.id)) d.albumInfoOverride else emptyList(),
            trackInfoOverride = if (on(BackupCategory.EDITS.id)) d.trackInfoOverride else emptyList(),
            trackAlbumMove = if (on(BackupCategory.EDITS.id)) d.trackAlbumMove else emptyList(),
            artists = if (on(BackupCategory.EDITS.id)) d.artists else emptyList(),
            songArtist = if (on(BackupCategory.EDITS.id)) d.songArtist else emptyList(),
            albumOrders = if (on(BackupCategory.EDITS.id)) d.albumOrders else emptyList(),
            artistAlbumOrders = if (on(BackupCategory.EDITS.id)) d.artistAlbumOrders else emptyList(),
            ignoredFolders = if (on(BackupCategory.EDITS.id)) d.ignoredFolders else emptyList(),
            playlists = if (on(BackupCategory.EDITS.id)) d.playlists else emptyList(),
            playlistTracks = if (on(BackupCategory.EDITS.id)) d.playlistTracks else emptyList(),
            albumSplit = if (on(BackupCategory.EDITS.id)) d.albumSplit else emptyList(),
            splitTrack = if (on(BackupCategory.EDITS.id)) d.splitTrack else emptyList(),
            trashedTracks = if (on(BackupCategory.EDITS.id)) d.trashedTracks else null,
            artistAliases = if (on(BackupCategory.EDITS.id)) d.artistAliases else null,
            // 统计
            playEvents = if (on(BackupCategory.STATS.id)) d.playEvents else emptyList(),
            playCounts = if (on(BackupCategory.STATS.id)) d.playCounts else null,
            tokenUsage = if (on(BackupCategory.STATS.id)) d.tokenUsage else emptyList(),
            newAlbums = if (on(BackupCategory.STATS.id)) d.newAlbums else null,
            // 歌词与注音
            manualLyrics = if (on(BackupCategory.LYRICS.id)) d.manualLyrics else emptyList(),
            lyricsTranslation = if (on(BackupCategory.TRANSLATION.id)) d.lyricsTranslation else null,
            readingOverride = if (on(BackupCategory.READING.id)) d.readingOverride else emptyList(),
            externalReadingEvidence = if (on(BackupCategory.READING.id)) d.externalReadingEvidence else emptyList(),
            // 拾光
            agentConversations = if (on(BackupCategory.CHAT.id)) d.agentConversations else null,
            agentMemory = if (on(BackupCategory.MEMORY.id)) d.agentMemory else null,
            // 封面与写真（链接类）
            albumArtCache = if (on(BackupCategory.SOURCES.id)) d.albumArtCache else null,
            artistImageCache = if (on(BackupCategory.SOURCES.id)) d.artistImageCache else emptyList(),
            artistImageOverride = if (on(BackupCategory.IMAGES.id)) d.artistImageOverride else emptyList(),
            // 设置与凭据
            settings = buildMap {
                if (on(BackupCategory.SETTINGS.id)) putAll(normalSettings)
                if (on(BackupCategory.SECRETS.id)) putAll(secretSettings)
            },
        )
    }


    }

    data class BackupData(
        val version: Int = 2,
        val app: String = "com.shiyin.music",
        val exportedAt: Long,
        val albumOverride: List<ALO>,
        val albumInfoOverride: List<AIO>,
        val trackInfoOverride: List<TIO>,
        val trackAlbumMove: List<TAM>,
        val artists: List<ART>,
        val songArtist: List<SA>,
        val readingOverride: List<RO>,
        val externalReadingEvidence: List<ERE>,
        // v1.2.0 #6: 歌手写真——用户手选 override 是永久资产；cache 成功行随附避免重抓。
        val artistImageCache: List<AIC>,
        val artistImageOverride: List<AIOV>,
        // v1.3.x 需求6: 歌词翻译结果。可空 + 默认 null → 兼容旧备份（Gson 缺字段即 null）。
        val lyricsTranslation: List<LT>? = null,
        // v22: 专辑整轨拆分为单曲。不可由重新扫描重建（用户录入的时间轴），必须随备份走。
        // split_track 连自增 id 一起导出：对外合成 mediaId = TRACK_BASE + id，而收藏/
        // 歌单/歌词都按这个 id 存，id 一变这些引用全断。导入用 REPLACE 保留原 id。
        // 跨设备时 sourceMediaId 必然失效，由 lib() 用 sourcePath 兜底重解析。
        val albumSplit: List<ASP> = emptyList(),
        val splitTrack: List<STK> = emptyList(),

        // ── v22: 备份内容扩展 ──────────────────────────────────────────────
        val playlists: List<PL> = emptyList(),
        val playlistTracks: List<PLT> = emptyList(),
        /** 只含 `autoSaved = 0`（手动导入/确认）的歌词；自动识别的可重新识别，不占体积。 */
        val manualLyrics: List<SL> = emptyList(),
        val albumOrders: List<AO> = emptyList(),
        val artistAlbumOrders: List<AAO> = emptyList(),
        val ignoredFolders: List<IF> = emptyList(),
        val playEvents: List<PE> = emptyList(),
        val tokenUsage: List<TU> = emptyList(),
        /**
         * v1.3.11:拾光的**长期记忆**——用户长期积累的"她认识你什么",换机/恢复后不该丢
         * (丢了就是"新设备上的拾光完全不认识你")。可空 + 默认 null → 兼容旧备份。
         *
         * FTS 检索索引**不随备份走**(它是派生数据):导入时按正文重新生成,见 [import]。
         * 刻意**不备** agent_conversation(聊天记录):本次范围只要记忆,对话留待后续版本。
         */
        val agentMemory: List<AME>? = null,
        /**
         * v1.5.0 A60：**聊天记录**（用户点名"像拾光 agent 的聊天记录也没备份"）。
         * 原来代码注释写着"刻意不备，留待后续版本"。全量备（含 messagesJson）。
         */
        val agentConversations: List<AgentConversationEntity>? = null,
        /** v1.5.0 A60：专辑封面缓存——**重点是其 url/source**（自动获取的来源链接，
         *  恢复后按链接重取即可，不必重新找图）。 */
        val albumArtCache: List<AlbumArtCacheEntity>? = null,
        /** v1.5.0 A60：回收站——不备会让已删的歌在恢复后"复活"。 */
        val trashedTracks: List<TrashedTrackEntity>? = null,
        /** v1.5.0 A60：累计播放次数。 */
        val playCounts: List<PlayCountEntity>? = null,
        /** v1.5.0 A60：新专辑徽标（firstSeenAt）。 */
        val newAlbums: List<NewAlbumEntity>? = null,
        /** v1.5.0 A60：歌手别名合并（用户手工整理）。 */
        val artistAliases: List<ArtistAliasEntity>? = null,
        /** DataStore 白名单设置，值带类型前缀（见 `SettingsValueCodec`）。 */
        val settings: Map<String, String> = emptyMap(),

        // ── v22: 重解析键（跨设备可用的地基，见 BackupRemap）────────────────
        /** `mediaId → 文件绝对路径`。mediaId 是设备本地自增主键，换机必变，
         *  必须靠路径在新设备上重新找回对应的歌。 */
        val mediaIdPaths: Map<String, String> = emptyMap(),
        /** `albumId → "专辑名|歌手"`。**刻意存名字形式**：`aid:NNN` 本身就含 id，
         *  换机后同样失效，不能拿它当跨设备标识。 */
        val albumIdKeys: Map<String, String> = emptyMap(),
    )

    /**
     * 导出全部用户数据为 JSON 字符串。调用方负责写入 SAF/文件。
     *
     * @param mediaIdPaths 当前库的 `mediaId → dataPath`（见 `BackupRemap`）
     * @param albumIdKeys 当前库的 `albumId → "专辑名|歌手"`
     */
    suspend fun export(
        mediaIdPaths: Map<String, String> = emptyMap(),
        albumIdKeys: Map<String, String> = emptyMap(),
        /** v1.5.0 A60：只导出这些分类（null = 全部，保持旧行为）。 */
        categories: Set<String>? = null,
    ): String {
        val data = BackupData(
            exportedAt = System.currentTimeMillis(),
            albumOverride = dao.allAlbumOverride(),
            albumInfoOverride = dao.allAlbumInfoOverride(),
            trackInfoOverride = dao.allTrackInfoOverride(),
            trackAlbumMove = dao.allTrackAlbumMove(),
            artists = dao.allArtists(),
            songArtist = dao.allSongArtist(),
            readingOverride = dao.allReadingOverride(),
            externalReadingEvidence = dao.allExternalReadingEvidence(),
            artistImageCache = dao.allArtistImageCacheSuccess(),
            artistImageOverride = dao.allArtistImageOverride(),
            lyricsTranslation = dao.allLyricsTranslations(),
            albumSplit = dao.allAlbumSplits(),
            splitTrack = dao.allSplitTracks(),
            playlists = dao.allPlaylists(),
            playlistTracks = dao.allPlaylistTracks(),
            manualLyrics = dao.allManualLyrics(),
            albumOrders = dao.allAlbumOrders(),
            artistAlbumOrders = dao.allArtistAlbumOrders(),
            ignoredFolders = dao.allIgnoredFolders(),
            playEvents = dao.allPlayEvents(),
            tokenUsage = dao.allTokenUsage(),
            agentMemory = dao.agentMemories(),
            agentConversations = dao.allAgentConversations(),
            albumArtCache = dao.allArtCache(),
            trashedTracks = dao.allTrashed(),
            playCounts = dao.allPlayCounts(),
            newAlbums = dao.allNewAlbums(),
            artistAliases = dao.allArtistAliases(),
            settings = settings?.exportBackupSettings().orEmpty(),
            mediaIdPaths = mediaIdPaths,
            albumIdKeys = albumIdKeys,
        )
        return Gson().toJson(if (categories == null) data else applyCategories(data, categories))
    }

    /** 实例入口：带上本机 SettingsStore 的密钥键集合（区分"设置"与"凭据"两类）。 */
    internal fun applyCategories(d: BackupData, cats: Set<String>): BackupData =
        applyCategories(d, cats, settings?.BACKUP_SECRET_KEYS ?: emptySet())

    /**
     * v1.5.0 A60：各分类的**实际体积（字节）**——导出界面显示"每项多大、合计多大"，
     * 让用户按自己的取向选择承担哪些。走的是与导出完全相同的裁剪路径，
     * 所以显示值与真实包体一致。
     */
    suspend fun categorySizes(): Map<String, Long> {
        val (paths, albums) = emptyMap<String, String>() to emptyMap<String, String>()
        val out = LinkedHashMap<String, Long>()
        for (c in BackupCategory.entries) {
            val json = export(paths, albums, setOf(c.id))
            out[c.id] = json.toByteArray(Charsets.UTF_8).size.toLong()
        }
        return out
    }

    /**
     * 导入前先做 id 重解析（跨设备关键步骤），再走 [import]。
     *
     * @param pathToMediaId 当前库：文件绝对路径 → mediaId
     * @param nameKeyToAlbumId 当前库：`"专辑名|歌手"` → albumId
     */
    suspend fun importWithRemap(
        json: String,
        pathToMediaId: Map<String, Long>,
        nameKeyToAlbumId: Map<String, Long>,
    ): ImportStats {
        val remap = BackupRemap.apply(json, pathToMediaId, nameKeyToAlbumId)
        return import(remap.json).copy(
            remapSkipped = remap.skipped,
            tracksRemapped = remap.tracksRemapped,
            tracksDropped = remap.tracksDropped,
            albumsRemapped = remap.albumsRemapped,
            albumsDropped = remap.albumsDropped,
        )
    }

    /**
     * 从 JSON 导入。**合并语义**：同主键覆盖，其余保留（不清库）。
     *
     * 调用方应在此之前用 [BackupRemap] 把 mediaId / albumId 换成当前库的 id
     * （否则这些行会挂到错误的歌上）。这里只负责写库与统计。
     */
    suspend fun import(json: String): ImportStats {
        val data = Gson().fromJson(json, BackupData::class.java)
            ?: error("备份文件格式无法解析")
        // v1.4.0：**DB 写入整体放进一个事务**。
        // 原先是一串独立的 upsert（20+ 张表、含"先删 FTS 再重建"这类多步操作），中途失败
        // （撞唯一索引 / 磁盘满 / 内存不足）会留下"前面写进去了、后面没写"的半截状态：
        // UI 弹"导入失败"，用户以为没生效，再导一次 → 重复或半截。现在失败即**整体回滚**。
        val stats = db.withTransaction { importDb(data) }
        // 设置（DataStore）**不在**这个事务域里：放在事务提交**之后**写。
        // 理由：若 DB 部分回滚，至少不会出现"设置已被备份覆盖、数据却没导入"的错位。
        // 这一步失败不回滚已提交的 DB（回不去），只把写出条数记 0，由 ImportStats.settings 体现。
        val settingsWritten = runCatching {
            settings?.importBackupSettings(data.settings.orEmpty()) ?: 0
        }.getOrDefault(0)
        return stats.copy(settings = settingsWritten)
    }

    /** [import] 的 DB 部分：整体在一个事务里执行，失败即整体回滚。 */
    private suspend fun importDb(data: BackupData): ImportStats {
        // 简单表：直接批量 upsert（主键不依赖自增 id）
        dao.upsertAllAlbumOverride(data.albumOverride)
        dao.upsertAllAlbumInfoOverride(data.albumInfoOverride)
        dao.upsertAllTrackInfoOverride(data.trackInfoOverride)
        dao.upsertAllTrackAlbumMove(data.trackAlbumMove)
        dao.upsertAllReadingOverride(data.readingOverride)
        dao.upsertAllExternalReadingEvidence(data.externalReadingEvidence)
        // artist：按 name 合并，建 备份id → 本地id 映射
        val idMap = HashMap<Int, Int>() // backup artistId -> local artistId
        for (a in data.artists) {
            val local = dao.artistByName(a.name)
            val localId = if (local != null) {
                // 合并 aliases（取并集去重）+ 头像（本地空则用备份）
                val mergedAliases = mergeAliases(local.aliases, a.aliases)
                val avatar = local.avatarUrl.ifBlank { a.avatarUrl }
                val avatarSrc = local.avatarSource.ifBlank { a.avatarSource }
                dao.upsertArtist(local.copy(
                    aliases = mergedAliases,
                    avatarUrl = avatar,
                    avatarSource = avatarSrc,
                    updatedAt = maxOf(local.updatedAt, a.updatedAt),
                ))
                local.id
            } else {
                // 新艺术家，插入（id 自增），取返回的 id
                dao.insertArtistGetId(a.copy(id = 0)).toInt()
            }
            idMap[a.id] = localId
        }
        // song_artist：用映射后的 artistId 批量插入
        val remapped = data.songArtist.map { it.copy(artistId = idMap[it.artistId] ?: it.artistId) }
            .filter { idMap[it.artistId] != null } // 仅导入成功映射的
        if (remapped.isNotEmpty()) dao.upsertAllSongArtist(remapped)
        // 歌手写真：override(用户手选) + cache 成功行。PK=name，无自增 id，直接 upsert。
        // orEmpty 兼容旧备份（无这两个字段→null→空，跳过）。
        val imgCache = data.artistImageCache.orEmpty()
        val imgOverride = data.artistImageOverride.orEmpty()
        if (imgCache.isNotEmpty()) dao.upsertAllArtistImageCache(imgCache)
        if (imgOverride.isNotEmpty()) dao.upsertAllArtistImageOverride(imgOverride)
        // v1.3.x 需求6:翻译结果。PK=(mediaId, lyricsHash)，无自增 id，直接 upsert。
        // orEmpty 兼容旧备份（无该字段 → null → 空，跳过）。
        val translations = data.lyricsTranslation.orEmpty()
        if (translations.isNotEmpty()) dao.upsertAllLyricsTranslations(translations)
        // v22: 拆分方案。orEmpty 兼容旧备份（无这两个字段→null→空，跳过）。
        // 先 album_split 再 split_track（方案先行，孤儿行也不会被 lib() 展开）。
        // split_track 保留原自增 id（REPLACE）→ 合成 mediaId 不变 → 收藏/歌单/歌词引用不断。
        val splits = data.albumSplit.orEmpty()
        val splitTracks = data.splitTrack.orEmpty()
        if (splits.isNotEmpty()) dao.upsertAllAlbumSplit(splits)
        if (splitTracks.isNotEmpty()) dao.insertSplitTracks(splitTracks)

        // ── v22: 扩展内容 ────────────────────────────────────────────────
        // 歌单 / 歌单曲目 / 手动歌词 / 排序 / 忽略文件夹：主键不依赖自增 id，直接 upsert。
        val playlists = data.playlists.orEmpty()
        val playlistTracks = data.playlistTracks.orEmpty()
        val manualLyrics = data.manualLyrics.orEmpty()
        val albumOrders = data.albumOrders.orEmpty()
        val artistAlbumOrders = data.artistAlbumOrders.orEmpty()
        val ignoredFolders = data.ignoredFolders.orEmpty()
        if (playlists.isNotEmpty()) dao.upsertAllPlaylists(playlists)
        if (playlistTracks.isNotEmpty()) dao.upsertAllPlaylistTracks(playlistTracks)
        if (manualLyrics.isNotEmpty()) dao.upsertAllLyrics(manualLyrics)
        if (albumOrders.isNotEmpty()) dao.upsertAllAlbumOrders(albumOrders)
        if (artistAlbumOrders.isNotEmpty()) dao.upsertAllArtistAlbumOrders(artistAlbumOrders)
        if (ignoredFolders.isNotEmpty()) dao.insertAllIgnoredFolders(ignoredFolders)

        // 统计两张表的 id 是**本机自增**的：清零 id 追加，避免跨设备的 id 撞上本机已有行
        // 把它覆盖掉。用天然唯一键去重，保证同一份备份重复导入不会翻倍。
        val existingEventKeys = dao.allPlayEventKeys().mapTo(HashSet()) { it.mediaId to it.playedAt }
        val newEvents = data.playEvents.orEmpty()
            .filter { existingEventKeys.add(it.mediaId to it.playedAt) }
            .map { it.copy(id = 0) }
        if (newEvents.isNotEmpty()) dao.insertAllPlayEvents(newEvents)

        val existingTs = dao.allTokenUsageTimestamps().toHashSet()
        val newUsage = data.tokenUsage.orEmpty()
            .filter { existingTs.add(it.timestamp) }
            .map { it.copy(id = 0) }
        if (newUsage.isNotEmpty()) dao.insertAllTokenUsage(newUsage)

        // v1.3.11:长期记忆随备份走。业务标识是 UUID(id)而非自增 rowId → 直接 upsert(REPLACE),
        // 重复导入同一份备份不会翻倍(同 id 覆盖)。
        // FTS 是**派生索引**,刻意不随备份走:逐条先删旧索引行、再按正文重建——否则导入进来的
        // 记忆检索不到,表现为"恢复完备份,你问她我叫什么她还是不知道"(数据在、索引空)。
        val memories = data.agentMemory.orEmpty()
        for (m in memories) {
            dao.upsertAgentMemory(m)
            dao.deleteAgentMemoryFts(m.id)
            // 已被取代的条目不参与检索,不必建索引(与 RoomMemoryStore.syncFts 的语义一致)
            if (m.supersededBy != null) continue
            dao.insertAgentMemoryFts(
                AgentMemoryFtsEntity(
                    memoryId = m.id,
                    content = com.shiyin.music.data.memory.MemoryRules.ftsText(m.content),
                    tags = com.shiyin.music.data.memory.MemoryRules.ftsText(
                        m.tags.split(',').map { it.trim() }.filter { it.isNotBlank() }.joinToString(" "),
                    ),
                ),
            )
        }

        // v1.5.0 A60：补上仍然漏掉的六类（全部"用户劳动成果"，非可重建数据）。
        val conversations = data.agentConversations.orEmpty()
        val artCache = data.albumArtCache.orEmpty()
        val trashed = data.trashedTracks.orEmpty()
        val playCounts = data.playCounts.orEmpty()
        val newAlbums = data.newAlbums.orEmpty()
        val aliases = data.artistAliases.orEmpty()
        if (conversations.isNotEmpty()) dao.upsertAllAgentConversations(conversations)
        if (artCache.isNotEmpty()) dao.upsertAllAlbumArtCache(artCache)
        if (trashed.isNotEmpty()) dao.upsertAllTrashed(trashed)
        if (playCounts.isNotEmpty()) dao.upsertAllPlayCounts(playCounts)
        if (newAlbums.isNotEmpty()) dao.upsertAllNewAlbums(newAlbums)
        if (aliases.isNotEmpty()) dao.upsertAllArtistAliases(aliases)

        return ImportStats(
            agentConversations = conversations.size,
            albumArtCache = artCache.size,
            trashedTracks = trashed.size,
            playCounts = playCounts.size,
            newAlbums = newAlbums.size,
            artistAliases = aliases.size,
            albumOverride = data.albumOverride.size,
            albumInfoOverride = data.albumInfoOverride.size,
            trackInfoOverride = data.trackInfoOverride.size,
            trackAlbumMove = data.trackAlbumMove.size,
            artists = data.artists.size,
            songArtist = remapped.size,
            readingOverride = data.readingOverride.size,
            externalReadingEvidence = data.externalReadingEvidence.size,
            artistImageCache = imgCache.size,
            artistImageOverride = imgOverride.size,
            lyricsTranslation = translations.size,
            albumSplit = splits.size,
            splitTrack = splitTracks.size,
            playlists = playlists.size,
            playlistTracks = playlistTracks.size,
            manualLyrics = manualLyrics.size,
            albumOrders = albumOrders.size,
            artistAlbumOrders = artistAlbumOrders.size,
            ignoredFolders = ignoredFolders.size,
            playEvents = newEvents.size,
            playEventsSkipped = data.playEvents.orEmpty().size - newEvents.size,
            tokenUsage = newUsage.size,
            agentMemory = memories.size,
            // 设置条数由 import() 在事务提交后回填（见那里的注释）
            settings = 0,
        )
    }

    /** 合并两个 aliases 串（逗号分隔），并集去重。空串→空 list。 */
    private fun mergeAliases(a: String, b: String): String {
        val set = (a.split(",").map { it.trim() }.filter { it.isNotEmpty() } +
                   b.split(",").map { it.trim() }.filter { it.isNotEmpty() }).toSet()
        return set.joinToString(",")
    }

    data class ImportStats(
        val albumOverride: Int,
        val albumInfoOverride: Int,
        val trackInfoOverride: Int,
        val trackAlbumMove: Int,
        val artists: Int,
        val songArtist: Int,
        val readingOverride: Int,
        val externalReadingEvidence: Int,
        val artistImageCache: Int,
        val artistImageOverride: Int,
        val lyricsTranslation: Int = 0,
        // v22: 拆分方案 / 拆分曲目
        val albumSplit: Int = 0,
        val splitTrack: Int = 0,
        // v22: 扩展内容
        val playlists: Int = 0,
        val playlistTracks: Int = 0,
        val manualLyrics: Int = 0,
        val albumOrders: Int = 0,
        val artistAlbumOrders: Int = 0,
        val ignoredFolders: Int = 0,
        val playEvents: Int = 0,
        /** 因为本机已有同一条（mediaId + 时间戳）而跳过的播放记录数。 */
        val playEventsSkipped: Int = 0,
        val tokenUsage: Int = 0,
        /** v1.3.11:恢复进来的长期记忆条数(含已被取代的历史条目)。 */
        val agentMemory: Int = 0,
        // v1.5.0 A60:补上的六类
        val agentConversations: Int = 0,
        val albumArtCache: Int = 0,
        val trashedTracks: Int = 0,
        val playCounts: Int = 0,
        val newAlbums: Int = 0,
        val artistAliases: Int = 0,
        val settings: Int = 0,
        // v22: 重解析（跨设备）结果
        /** 备份里没有重解析表（v1 老备份）→ 未做重映射，行为与旧版一致。 */
        val remapSkipped: Boolean = false,
        val tracksRemapped: Int = 0,
        /** **因为在新设备上找不到对应文件而丢弃的行数** —— 必须如实告知用户。 */
        val tracksDropped: Int = 0,
        val albumsRemapped: Int = 0,
        val albumsDropped: Int = 0,
    ) {
        override fun toString() =
            "专辑迁移$albumOverride / 专辑修正$albumInfoOverride / 曲目修正$trackInfoOverride / " +
            "曲目迁专辑$trackAlbumMove / 歌手$artists / 曲目-歌手$songArtist / " +
            "振假名注音$readingOverride / 外部假名$externalReadingEvidence / " +
            "歌手写真缓存$artistImageCache / 歌手写真手选$artistImageOverride / " +
            "歌词翻译$lyricsTranslation / " +
            "整轨拆分$albumSplit / 拆分曲目$splitTrack / " +
            "歌单$playlists / 歌单曲目$playlistTracks / 歌词$manualLyrics / " +
            "专辑曲序$albumOrders / 歌手专辑序$artistAlbumOrders / 忽略文件夹$ignoredFolders / " +
            "播放记录$playEvents" + (if (playEventsSkipped > 0) "(跳过$playEventsSkipped)" else "") + " / " +
            "拾光记忆$agentMemory / " +
            "Token用量$tokenUsage / 设置$settings" +
            (if (tracksDropped > 0 || albumsDropped > 0) " / ⚠找不到文件而跳过：曲目$tracksDropped 专辑$albumsDropped" else "")
    }
}

package com.shiyin.music.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private val keyDark = booleanPreferencesKey("dark_theme")
    /**
     * v1.4.0:**跟随系统**深浅（设置页「系统主题」档）。默认 false。
     *
     * 主题本质仍只有浅/深两态，[keyDark] 就是那个选择；本开关只是决定"这份选择要不要
     * 被系统的深浅覆盖"。所以**老用户不动**：没有这个键 → false → 完全保持原样；
     * 打开后每一帧都用系统当时的值去解析成浅或深（含系统在后台切换时）。
     */
    private val keyThemeFollowSystem = booleanPreferencesKey("theme_follow_system")
    private val keyGapless = booleanPreferencesKey("gapless")
    private val keyAutoMatch = booleanPreferencesKey("auto_match")
    private val keyOnboarded = booleanPreferencesKey("onboarded")
    private val keyRecent = stringPreferencesKey("recent_ids")
    private val keyDeepSeek = stringPreferencesKey("deepseek_api_key")
    /** v1.2.2 Agent: Tavily 联网搜索 API key(独立于 LLM 服务商,用于 Agent 联网搜证)。 */
    private val keyTavilyApiKey = stringPreferencesKey("tavily_api_key")
    /** v1.2.2 Agent: LLM 提供商配置(JSON,见 LlmConfig.encode/decode)+当前激活的 provider key。 */
    private val keyLlmProviders = stringPreferencesKey("llm_providers")
    private val keyLlmActiveProvider = stringPreferencesKey("llm_active_provider")
    /** v5.2 Bug2: true only after the device has finished at least one full
     *  library scan *and* that scan result was absorbed into knownAlbumIds.
     *  Until this flips true, `detectNewAlbums` no-ops (absorbs ids + return),
     *  so覆盖安装升级的第一次扫描不会再把整个老库灌进你的更新. */
    private val keyFirstScanDone = booleanPreferencesKey("first_scan_done")
    // v1.1+: 自动保存识别结果（联网识别匹配成功的歌词/封面默认持久化，默认开）
    private val keyAutoSaveRecognition = booleanPreferencesKey("auto_save_recognition")
    // v2: 播放速度调节（全局，不是每首歌独立）
    private val keyPlaybackSpeed = floatPreferencesKey("playback_speed")
    private val keyRetroSpeedMode = booleanPreferencesKey("retro_speed_mode")
    // v1.2.1: 歌手写真源运行时可配(用户在设置·写真源里填/开关,无需 local.properties)。
    // fanartApiKey/lastfmApiKey 空串=未配(源跳过);disabledSources=用户手动关掉的源 key 集合。
    private val keyFanartApiKey = stringPreferencesKey("fanart_api_key")
    private val keyLastfmApiKey = stringPreferencesKey("lastfm_api_key")
    private val keyDisabledImageSources = stringSetPreferencesKey("disabled_image_sources")
/** v1.5.0 右滑固定：被用户固定的音乐库条目 key（"pl:p3" / "album:<key>" / "artist:<name>" / "song:<id>"）。 */
private val keyPinnedLibItems = stringSetPreferencesKey("pinned_lib_items")
    /** v1.3.2: 自定义写真源(JSON,见 CustomImageSourceCodec)。 */
    private val keyCustomImageSources = stringPreferencesKey("custom_image_sources")
    /** v22: 「检查更新」状态(JSON,见 UpdateStateCodec)——最近一次检查结果 + 已忽略版本。 */
    private val keyUpdateState = stringPreferencesKey("update_state")

    // ── v1.3.x 歌词本设置(设置·歌词本页;写入后 init flow 同步到 VM)────────────
    /** 日语歌默认开振假名标注(0=关,默认 1=开)。 */
    private val keyFuriganaDefaultOn = booleanPreferencesKey("furigana_default_on")
    /** 日语歌自动跑振假名校验(UtaTen+LLM 三层;默认开)。中英文歌永不触发。 */
    private val keyFuriganaAutoVerify = booleanPreferencesKey("furigana_auto_verify")
    /** 歌词本歌词颜色:"default"(跟随封面,现状) / "white" / "black"。 */
    private val keyLyricsColorMode = stringPreferencesKey("lyrics_color_mode")
    /** v1.3.x 歌词本翻译:外语歌进入歌词本时自动触发 AI 翻译(默认 false——AI 永远用户主动)。 */
    private val keyTranslateAutoForeign = booleanPreferencesKey("translate_auto_foreign")
    /** v1.3.10: 翻译按钮显隐三档 "never"/"majority"/"any"(缺省 majority=现状)。 */
    private val keyTranslateButtonMode = stringPreferencesKey("translate_button_mode")
    /** v1.3.10: 时间轴校对入口是否常显(缺省 false = 只有未同步歌词显示)。 */
    private val keyLyricsCalibAlwaysOn = booleanPreferencesKey("lyrics_calib_always_on")
    // ── v1.3.12:拾光 Agent 的两项行为偏好 ────────────────────────────────────
    /** 思考链**默认收起**(只在点开时才看推理过程)。默认 false = 保持"边思考边摊开看"。 */
    private val keyAgentThinkingCollapsed = booleanPreferencesKey("agent_thinking_collapsed")
    /**
     * **强制联网搜索**（用户自选）。默认关。
     *
     * 起因：模型知识面有限时爱"凭记忆猜"（尤其弱模型），猜出来的歌手/专辑/年份是错的，
     * 而用户无从分辨。打开后，凡涉及具体事实或没把握的内容一律先 web_search 再答。
     * 代价是多一次联网与等待，所以交给用户自己权衡，不做默认。
     */
    private val keyAgentForceSearch = booleanPreferencesKey("agent_force_search")
    /** 允许拾光从对话里**自动**记住你的信息(关掉后只有明说「记住…」才落库)。默认开。 */
    private val keyMemoryAutoExtract = booleanPreferencesKey("memory_auto_extract")
    /** 连"情绪与近况"这类一次性的也记(默认不记——见 MemoryRules 的一次性情绪拒表)。默认关。 */
    private val keyMemoryIncludeTransient = booleanPreferencesKey("memory_include_transient")
    /** v1.3.14:是否记录 Agent 诊断样本(含模型回复开头)。默认开——单人使用,需要能排查。 */
    private val keyAgentDiagEnabled = booleanPreferencesKey("agent_diag_enabled")
    /** v1.3.10 A29: 被用户关掉的歌词源名集合(内置源开关)。 */
    private val keyLyricsDisabledSources = stringSetPreferencesKey("lyrics_disabled_sources")
    /** v1.3.10 A29: 用户自定义歌词源列表(JSON,见 CustomLyricsSourceCodec)。 */
    private val keyCustomLyricsSources = stringPreferencesKey("custom_lyrics_sources")
    /** v1.3.x 歌词本翻译:有保存的译文时默认显示(默认 true——译完即存,进来就给看)。 */
    private val keyTranslateDefaultShow = booleanPreferencesKey("translate_default_show")
    /** A36: 翻译失败时弹提示(默认关——用户嫌失败提示太频繁,想安静时就不弹)。 */
    private val keyTranslateErrorToast = booleanPreferencesKey("translate_error_toast")
    /** v1.5.0 伴唱:总开关(默认开)。关闭后隐藏歌词页麦克风入口,设备慢的用户可彻底关掉。 */
    private val keyKaraokeEnabled = booleanPreferencesKey("karaoke_enabled")

    // ── v1.3.x 播放设置(设置·播放页,用户 2026-09-22 指定分组)─────────────────
    /** 音量均衡:平衡所有曲目的播放音量(响度归一)。 */
    private val keyVolumeNormalize = booleanPreferencesKey("volume_normalize")
    /** 单声道音频:左右扬声器播放相同的音频。 */
    private val keyMonoAudio = booleanPreferencesKey("mono_audio")
    /** 允许与其他应用同时播放。 */
    private val keyAllowConcurrent = booleanPreferencesKey("allow_concurrent_playback")
    /** 均衡器开关(5 段 EQ;具体曲线设置页内调)。 */
    private val keyEqualizerOn = booleanPreferencesKey("equalizer_on")
    /** 均衡器各频段增益(mB,逗号分隔;长度=段数)。 */
    private val keyEqualizerBands = stringPreferencesKey("equalizer_bands")

    data class Settings(
        val dark: Boolean,
        /** v1.4.0: 跟随系统深浅（设置页三档里的「系统主题」）。 */
        val themeFollowSystem: Boolean,
        val gapless: Boolean,
        val autoMatch: Boolean,
        val onboarded: Boolean,
        val recentIds: List<Long>,
        val deepseekApiKey: String,
        val tavilyApiKey: String,
        val llmProviders: List<com.shiyin.music.data.ai.LlmProviderConfig>,
        val llmActiveProvider: String,
        val firstScanDone: Boolean,
        val autoSaveRecognition: Boolean,
        val playbackSpeed: Float,
        val retroSpeedMode: Boolean,
        val fanartApiKey: String,
        val lastfmApiKey: String,
        val disabledImageSources: Set<String>,
        /** v1.5.0 右滑固定：被固定的音乐库条目 key 集合。 */
        val pinnedLibItems: Set<String>,
        val customImageSources: List<com.shiyin.music.data.image.CustomImageSourceDef>,
        /** v1.3.x 歌词本:日语歌默认开振假名(默认 true)。 */
        val furiganaDefaultOn: Boolean,
        /** v1.3.x 歌词本:日语歌自动跑振假名校验(默认 true)。 */
        val furiganaAutoVerify: Boolean,
        /** v1.3.x 歌词本:歌词颜色 "default"(跟随封面)/"white"/"black"。 */
        val lyricsColorMode: String,
        /** v1.3.x 歌词本翻译:外语歌进歌词本自动触发翻译(默认 false)。 */
        val translateAutoForeign: Boolean,
        /** v1.3.x 歌词本翻译:有保存译文时默认显示(默认 true)。 */
        val translateDefaultShow: Boolean,
        /** A36: 翻译失败时是否弹提示(默认 false)。 */
        val translateErrorToast: Boolean,
        /** v1.5.0 伴唱总开关(默认 true)。关闭后隐藏歌词页麦克风入口。 */
        val karaokeEnabled: Boolean,
        /** v1.3.10 「翻译按钮显示」三档:"never"/"majority"(默认,现状)/"any"。 */
        val translateButtonMode: String,
        /** v1.3.10: 已同步歌词是否也始终显示时间轴校对入口(默认 false)。 */
        val lyricsCalibAlwaysOn: Boolean,
        // v1.3.12:拾光 Agent 行为偏好(见 key 处的说明)
        val agentThinkingCollapsed: Boolean,
        /** 强制联网搜索：没把握的具体事实一律先搜再答。默认关。 */
        val agentForceSearch: Boolean,
        val memoryAutoExtract: Boolean,
        val memoryIncludeTransient: Boolean,
        /** v1.3.14:记录 Agent 诊断样本(含模型回复开头)。 */
        val agentDiagEnabled: Boolean,
        /** v1.3.10 A29: 被用户关掉的歌词源名(内置源开关;缺省全开)。 */
        val lyricsDisabledSources: Set<String>,
        /** v1.3.10 A29: 用户自定义歌词源。 */
        val customLyricsSources: List<com.shiyin.music.data.lyrics.CustomLyricsSourceDef>,
        /** v1.3.x 播放设置(设置·播放页)。 */
        val volumeNormalize: Boolean,
        val monoAudio: Boolean,
        val allowConcurrentPlayback: Boolean,
        val equalizerOn: Boolean,
        /** 均衡器各频段增益(mB)。 */
        val equalizerBands: List<Int>,
        /** v22: 检查更新状态(最近检查结果 + 已忽略版本)。 */
        val updateState: com.shiyin.music.data.update.UpdateState,
    )

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            dark = p[keyDark] ?: false,
            // v1.4.0: **默认跟随系统** —— 用户装 App 之前手机是什么主题就继续是什么主题，
            // 不把深浅钉死。迁移规则（保老用户不动）：
            //   · 新键有值 → 用新键；
            //   · 新键没有、但老用户**显式设过** dark_theme → 不跟随，保持他选的那个；
            //   · 新键没有、dark_theme 也从没写过（纯新装 / 从未碰过）→ 跟随系统。
            themeFollowSystem = p[keyThemeFollowSystem]
                ?: !p.asMap().containsKey(keyDark),
            gapless = p[keyGapless] ?: true,
            autoMatch = p[keyAutoMatch] ?: true,
            onboarded = p[keyOnboarded] ?: false,
            recentIds = (p[keyRecent] ?: "").split(",").mapNotNull { it.toLongOrNull() },
            deepseekApiKey = p[keyDeepSeek] ?: "",
            tavilyApiKey = p[keyTavilyApiKey] ?: "",
            llmProviders = com.shiyin.music.data.ai.LlmConfig.decode(p[keyLlmProviders] ?: "").ifEmpty { com.shiyin.music.data.ai.LlmConfig.PRESETS },
            llmActiveProvider = p[keyLlmActiveProvider] ?: "deepseek",
            firstScanDone = p[keyFirstScanDone] ?: false,
            autoSaveRecognition = p[keyAutoSaveRecognition] ?: true,
            playbackSpeed = p[keyPlaybackSpeed] ?: 1.0f,
            retroSpeedMode = p[keyRetroSpeedMode] ?: true,
            fanartApiKey = p[keyFanartApiKey] ?: "",
            lastfmApiKey = p[keyLastfmApiKey] ?: "",
            disabledImageSources = p[keyDisabledImageSources] ?: emptySet(),
            pinnedLibItems = p[keyPinnedLibItems] ?: emptySet(),
            customImageSources = com.shiyin.music.data.image.CustomImageSourceCodec.decode(p[keyCustomImageSources] ?: ""),
            furiganaDefaultOn = p[keyFuriganaDefaultOn] ?: true,
            furiganaAutoVerify = p[keyFuriganaAutoVerify] ?: true,
            lyricsColorMode = (p[keyLyricsColorMode] ?: "default").let { if (it in listOf("default", "white", "black")) it else "default" },
            translateAutoForeign = p[keyTranslateAutoForeign] ?: false,
            translateDefaultShow = p[keyTranslateDefaultShow] ?: true,
            translateErrorToast = p[keyTranslateErrorToast] ?: false,
            karaokeEnabled = p[keyKaraokeEnabled] ?: true,
            translateButtonMode = (p[keyTranslateButtonMode] ?: "majority")
                .let { if (it in listOf("never", "majority", "any")) it else "majority" },
            lyricsCalibAlwaysOn = p[keyLyricsCalibAlwaysOn] ?: false,
            // 默认值即"保持现状":思考链照旧展开、自动记忆照旧开、情绪照旧不记
            agentThinkingCollapsed = p[keyAgentThinkingCollapsed] ?: false,
            agentForceSearch = p[keyAgentForceSearch] ?: false,
            memoryAutoExtract = p[keyMemoryAutoExtract] ?: true,
            memoryIncludeTransient = p[keyMemoryIncludeTransient] ?: false,
            agentDiagEnabled = p[keyAgentDiagEnabled] ?: true,
            lyricsDisabledSources = p[keyLyricsDisabledSources] ?: emptySet(),
            customLyricsSources =
                com.shiyin.music.data.lyrics.CustomLyricsSourceCodec.decode(p[keyCustomLyricsSources] ?: ""),
            volumeNormalize = p[keyVolumeNormalize] ?: false,
            monoAudio = p[keyMonoAudio] ?: false,
            allowConcurrentPlayback = p[keyAllowConcurrent] ?: false,
            equalizerOn = p[keyEqualizerOn] ?: false,
            equalizerBands = (p[keyEqualizerBands] ?: "")
                .split(",").mapNotNull { it.trim().toIntOrNull() }
                .takeIf { it.size == 5 } ?: listOf(0, 0, 0, 0, 0),
            updateState = com.shiyin.music.data.update.UpdateStateCodec.decode(p[keyUpdateState] ?: ""),
        )
    }

    suspend fun setDark(v: Boolean) {
        context.dataStore.edit { it[keyDark] = v }
        // 镜像不在这里写：设置流（MainViewModel 的 collector）是**唯一写入方**——
        // 只有它同时知道"跟随系统开关"与"浅深选择"，能算出正确档位。
    }
    suspend fun setGapless(v: Boolean) = context.dataStore.edit { it[keyGapless] = v }

    /** v1.4.0: 跟随系统深浅。**不使用镜像**——"系统"档的首帧本来就该跟系统资源，
     *  不需要提前置位；镜像只服务于「强制浅/深」两档。 */
    suspend fun setThemeFollowSystem(v: Boolean) =
        context.dataStore.edit { it[keyThemeFollowSystem] = v }
    suspend fun setAutoMatch(v: Boolean) = context.dataStore.edit { it[keyAutoMatch] = v }
    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[keyOnboarded] = v }
    suspend fun setDeepSeekKey(v: String) = context.dataStore.edit { it[keyDeepSeek] = v }
    /** v1.2.2 Agent: Tavily 联网搜索 key 设置(照 setFanartApiKey 模式 trim)。 */
    suspend fun setTavilyKey(v: String) = context.dataStore.edit { it[keyTavilyApiKey] = v.trim() }
    /** v1.2.2 Agent: 保存全部 LLM 提供商配置(JSON,见 LlmConfig.encode)。 */
    suspend fun setLlmProviders(providers: List<com.shiyin.music.data.ai.LlmProviderConfig>) =
        context.dataStore.edit { it[keyLlmProviders] = com.shiyin.music.data.ai.LlmConfig.encode(providers) }
    /** v1.2.2 Agent: 设置当前激活的 provider key(对应 LlmProviderConfig.key)。 */
    suspend fun setLlmActiveProvider(key: String) = context.dataStore.edit { it[keyLlmActiveProvider] = key }
    /** v5.2 Bug2: flip after the first scan post-install completes so the
     *  *next* scan onward is the one that starts diffing against
     *  `knownAlbumIds` and seeding `new_album`. */
    suspend fun setFirstScanDone(v: Boolean) = context.dataStore.edit { it[keyFirstScanDone] = v }
    suspend fun setAutoSaveRecognition(v: Boolean) = context.dataStore.edit { it[keyAutoSaveRecognition] = v }
    suspend fun setPlaybackSpeed(v: Float) = context.dataStore.edit { it[keyPlaybackSpeed] = v }
    suspend fun setRetroSpeedMode(v: Boolean) = context.dataStore.edit { it[keyRetroSpeedMode] = v }
    suspend fun setFanartApiKey(v: String) = context.dataStore.edit { it[keyFanartApiKey] = v.trim() }
    suspend fun setLastfmApiKey(v: String) = context.dataStore.edit { it[keyLastfmApiKey] = v.trim() }
    /** v1.2.1: 启用/禁用某写真源(源 key,如 "discogs"/"fanart")。enabled=false→加入禁用集。 */
    suspend fun setImageSourceEnabled(sourceKey: String, enabled: Boolean) = context.dataStore.edit { p ->
        val cur = p[keyDisabledImageSources] ?: emptySet()
        p[keyDisabledImageSources] = if (enabled) cur - sourceKey else cur + sourceKey
    }

    /**
     * v1.5.0 右滑固定（规格 docs/internal/v1.4.1-backlog.md §1）：切换某条目的固定状态。
     * 固定状态本身由 LibraryScreen 的既有「固定优先」排序消费（LibItem.pinned）。
     */
    suspend fun setPinnedLibItem(itemKey: String, pinned: Boolean) = context.dataStore.edit { p ->
        val cur = p[keyPinnedLibItems] ?: emptySet()
        p[keyPinnedLibItems] = if (pinned) cur + itemKey else cur - itemKey
    }

    /** v1.3.2: 保存全部自定义写真源(JSON,见 CustomImageSourceCodec)。 */
    suspend fun setCustomImageSources(v: List<com.shiyin.music.data.image.CustomImageSourceDef>) =
        context.dataStore.edit { it[keyCustomImageSources] = com.shiyin.music.data.image.CustomImageSourceCodec.encode(v) }

    /** v1.3.x 歌词本设置写入(见字段注释;lyricsColorMode 只收 default/white/black)。 */
    suspend fun setFuriganaDefaultOn(v: Boolean) = context.dataStore.edit { it[keyFuriganaDefaultOn] = v }
    suspend fun setFuriganaAutoVerify(v: Boolean) = context.dataStore.edit { it[keyFuriganaAutoVerify] = v }
    suspend fun setTranslateAutoForeign(v: Boolean) = context.dataStore.edit { it[keyTranslateAutoForeign] = v }
    suspend fun setTranslateDefaultShow(v: Boolean) = context.dataStore.edit { it[keyTranslateDefaultShow] = v }
    suspend fun setTranslateErrorToast(v: Boolean) = context.dataStore.edit { it[keyTranslateErrorToast] = v }
    /** v1.5.0 伴唱总开关写入。 */
    suspend fun setKaraokeEnabled(v: Boolean) = context.dataStore.edit { it[keyKaraokeEnabled] = v }
    /** v1.3.10: 翻译按钮显隐档位写入(只收 never/majority/any,其余回 default=majority)。 */
    suspend fun setTranslateButtonMode(mode: String) =
        context.dataStore.edit { it[keyTranslateButtonMode] = if (mode in listOf("never", "any")) mode else "majority" }

    /** v1.3.10: 时间轴校对入口常显开关(已同步歌词是否也显示校对入口)。 */
    suspend fun setLyricsCalibAlwaysOn(v: Boolean) =
        context.dataStore.edit { it[keyLyricsCalibAlwaysOn] = v }

    // ── v1.3.12:拾光 Agent 行为偏好 ──────────────────────────────────────────
    suspend fun setAgentThinkingCollapsed(v: Boolean) =
        context.dataStore.edit { it[keyAgentThinkingCollapsed] = v }

    suspend fun setAgentForceSearch(v: Boolean) =
        context.dataStore.edit { it[keyAgentForceSearch] = v }

    suspend fun setMemoryAutoExtract(v: Boolean) =
        context.dataStore.edit { it[keyMemoryAutoExtract] = v }

    suspend fun setMemoryIncludeTransient(v: Boolean) =
        context.dataStore.edit { it[keyMemoryIncludeTransient] = v }

    suspend fun setAgentDiagEnabled(v: Boolean) =
        context.dataStore.edit { it[keyAgentDiagEnabled] = v }

    /** v1.3.10 A29: 开关某个歌词源(enabled=false → 记入禁用集)。 */
    suspend fun setLyricsSourceEnabled(source: String, enabled: Boolean) = context.dataStore.edit { p ->
        val cur = p[keyLyricsDisabledSources] ?: emptySet()
        p[keyLyricsDisabledSources] = if (enabled) cur - source else cur + source
    }

    /** v1.3.10 A29: 保存自定义歌词源列表(JSON)。 */
    suspend fun setCustomLyricsSources(list: List<com.shiyin.music.data.lyrics.CustomLyricsSourceDef>) =
        context.dataStore.edit {
            it[keyCustomLyricsSources] = com.shiyin.music.data.lyrics.CustomLyricsSourceCodec.encode(list)
        }

    /** v1.3.x 播放设置写入。 */
    suspend fun setVolumeNormalize(v: Boolean) = context.dataStore.edit { it[keyVolumeNormalize] = v }
    suspend fun setMonoAudio(v: Boolean) = context.dataStore.edit { it[keyMonoAudio] = v }
    suspend fun setAllowConcurrentPlayback(v: Boolean) = context.dataStore.edit { it[keyAllowConcurrent] = v }
    suspend fun setEqualizerOn(v: Boolean) = context.dataStore.edit { it[keyEqualizerOn] = v }
    suspend fun setEqualizerBands(v: List<Int>) =
        context.dataStore.edit { it[keyEqualizerBands] = v.joinToString(",") }
    suspend fun setLyricsColorMode(mode: String) =
        context.dataStore.edit { it[keyLyricsColorMode] = if (mode in listOf("white", "black")) mode else "default" }

    /** v22: 保存检查更新状态(JSON)。 */
    suspend fun setUpdateState(v: com.shiyin.music.data.update.UpdateState) =
        context.dataStore.edit { it[keyUpdateState] = com.shiyin.music.data.update.UpdateStateCodec.encode(v) }

    // ── v22: 备份快照的节流时间戳（**刻意不进备份白名单** —— 它是本机运行态）──
    private val keyLastAutoSnapshotAt = longPreferencesKey("last_auto_snapshot_at")

    suspend fun lastAutoSnapshotAt(): Long = context.dataStore.data.first()[keyLastAutoSnapshotAt] ?: 0L

    suspend fun setLastAutoSnapshotAt(v: Long) =
        context.dataStore.edit { it[keyLastAutoSnapshotAt] = v }

    // v22: WebDAV 云备份凭据。**刻意不进备份白名单** ——
    // 备份包里带着云盘账号密码，等于"备份泄露 = 云盘被接管"。
    private val keyWebDavUrl = stringPreferencesKey("webdav_url")
    private val keyWebDavUser = stringPreferencesKey("webdav_user")
    private val keyWebDavPassword = stringPreferencesKey("webdav_password")

    suspend fun webDavConfig(): Triple<String, String, String> {
        val p = context.dataStore.data.first()
        return Triple(p[keyWebDavUrl] ?: "", p[keyWebDavUser] ?: "", p[keyWebDavPassword] ?: "")
    }

    suspend fun setWebDavConfig(url: String, user: String, password: String) =
        context.dataStore.edit {
            it[keyWebDavUrl] = url.trim()
            it[keyWebDavUser] = user.trim()
            it[keyWebDavPassword] = password
        }

    // ── v22: 设置随备份走 ────────────────────────────────────────────────────
    // 原来备份完全不含设置 —— 用户花最多时间配的 LLM 供应商 / API Key / 写真源
    // 换机后要从零再填一遍，这是最容易被忽略、但用户最痛的缺口。

    /**
     * 可备份的设置白名单：**key → 期望类型**（类型见 [SettingsValueCodec] 的前缀）。
     *
     * 刻意排除的运行态：
     *  - `onboarded` / `first_scan_done` —— "本机已完成初始化"的标记，带到新机会让
     *    新机**跳过首次扫描引导**，反而像坏了
     *  - `recent_ids` —— 最近播放的本地 mediaId，跨设备无意义
     *  - `update_state` —— 检查更新缓存，新机自己会查
     *  - `webdav_*` / `last_auto_snapshot_at` —— 云盘凭据与本机运行态
     *
     * ⚠️ **为什么必须带期望类型**：DataStore 的 `Preferences.Key<T>` 的 equals
     * **只看名字**（`Key<String>("dark_theme") == Key<Boolean>("dark_theme")`）。
     * 一旦写进类型不符的值，之后 `prefs[booleanPreferencesKey("dark_theme")]` 会在取值时
     * 抛 ClassCastException —— 而且是在 settings flow 里抛，**整条设置同步链路挂掉**。
     * 备份文件可被手工编辑、也可能来自未来版本，所以类型不符一律跳过。
     */
    private val backupKeyTypes: Map<String, Char> = mapOf(
        "dark_theme" to SettingsValueCodec.BOOL,
        "theme_follow_system" to SettingsValueCodec.BOOL,
        "gapless" to SettingsValueCodec.BOOL,
        "auto_match" to SettingsValueCodec.BOOL,
        "auto_save_recognition" to SettingsValueCodec.BOOL,
        "playback_speed" to SettingsValueCodec.FLOAT,
        "retro_speed_mode" to SettingsValueCodec.BOOL,
        "furigana_default_on" to SettingsValueCodec.BOOL,
        "furigana_auto_verify" to SettingsValueCodec.BOOL,
        "lyrics_color_mode" to SettingsValueCodec.STR,
        "deepseek_api_key" to SettingsValueCodec.STR,
        "tavily_api_key" to SettingsValueCodec.STR,
        "llm_providers" to SettingsValueCodec.STR,
        "llm_active_provider" to SettingsValueCodec.STR,
        "fanart_api_key" to SettingsValueCodec.STR,
        "lastfm_api_key" to SettingsValueCodec.STR,
        "disabled_image_sources" to SettingsValueCodec.STR_SET,
        "custom_image_sources" to SettingsValueCodec.STR,
        // v1.3.12:拾光 Agent 行为偏好(用户偏好,换机后应恢复)
        "agent_thinking_collapsed" to SettingsValueCodec.BOOL,
        "agent_force_search" to SettingsValueCodec.BOOL,
        "memory_auto_extract" to SettingsValueCodec.BOOL,
        "memory_include_transient" to SettingsValueCodec.BOOL,
        "agent_diag_enabled" to SettingsValueCodec.BOOL,
    )

    /** 导出白名单设置（值已编码成"类型前缀 + 原文"，见 [SettingsValueCodec]）。 */
    suspend fun exportBackupSettings(): Map<String, String> {
        val all = context.dataStore.data.first().asMap()
        val out = LinkedHashMap<String, String>()
        for (name in backupKeyTypes.keys) {
            val v = all.entries.firstOrNull { it.key.name == name }?.value ?: continue
            SettingsValueCodec.encode(v)?.let { out[name] = it }
        }
        return out
    }

    /**
     * 导入白名单设置。只写备份里**确实存在**的 key，其余保持本机不动（合并语义）。
     * 返回成功写入的条数。
     *
     * 两道闸：**不在白名单**的 key 一律拒绝（防任意 key 注入）；**类型不符**的也拒绝
     * （防把 Key 的类型写歪导致 settings flow 抛 ClassCastException）。
     */
    suspend fun importBackupSettings(values: Map<String, String>): Int {
        var n = 0
        context.dataStore.edit { prefs ->
            for ((name, encoded) in values) {
                val expected = backupKeyTypes[name] ?: continue
                if (SettingsValueCodec.typeOf(encoded) != expected) continue
                // v1.4.0：**密钥类设置仅在本机为空时才写入**。
                // 导入别人的备份（或自己那份但已换过密钥）时，原实现无条件覆盖 → 用户配好的
                // Key 被静默换掉，而"Agent 突然用不了"极难联想到导入这一步。反过来完全不写也有代价
                // （换机后密钥没了），所以只让"本机为空"这一种情况接受备份值：换机（新机为空）照旧恢复，
                // 本机已配置的一律不动。
                if (!shouldWriteBackupValue(name, localBlank = prefs[stringPreferencesKey(name)].isNullOrBlank())) continue
                when (expected) {
                    SettingsValueCodec.BOOL -> SettingsValueCodec.boolOf(encoded)?.let {
                        prefs[booleanPreferencesKey(name)] = it; n++
                    }
                    SettingsValueCodec.STR -> {
                        prefs[stringPreferencesKey(name)] = SettingsValueCodec.stringOf(encoded); n++
                    }
                    SettingsValueCodec.FLOAT -> SettingsValueCodec.floatOf(encoded)?.let {
                        prefs[floatPreferencesKey(name)] = it; n++
                    }
                    SettingsValueCodec.INT -> SettingsValueCodec.intOf(encoded)?.let {
                        prefs[intPreferencesKey(name)] = it; n++
                    }
                    SettingsValueCodec.LONG -> SettingsValueCodec.longOf(encoded)?.let {
                        prefs[longPreferencesKey(name)] = it; n++
                    }
                    SettingsValueCodec.STR_SET -> {
                        prefs[stringSetPreferencesKey(name)] = SettingsValueCodec.setOf(encoded); n++
                    }
                    else -> Unit
                }
            }
        }
        return n
    }

    suspend fun pushRecent(id: Long) = context.dataStore.edit { p ->
        val cur = (p[keyRecent] ?: "").split(",").mapNotNull { it.toLongOrNull() }
        val next = (listOf(id) + cur.filter { it != id }).take(12)
        p[keyRecent] = next.joinToString(",")
    }
}

/**
 * v1.4.0：备份里**含密钥**的设置项 —— 这些采用"仅本机为空时才写入"（见 [SettingsStore.importBackupSettings]）。
 *
 * `llm_providers` 也在内：它是供应商列表的 JSON，每项都带 apiKey。
 */
internal val SECRET_BACKUP_KEYS = setOf(
    "deepseek_api_key",
    "tavily_api_key",
    "fanart_api_key",
    "lastfm_api_key",
    "llm_providers",
)

/** 该设置项这次要不要从备份写入：非密钥项照旧；密钥项**只在本机为空时**接受备份值。 */
internal fun shouldWriteBackupValue(name: String, localBlank: Boolean): Boolean =
    name !in SECRET_BACKUP_KEYS || localBlank

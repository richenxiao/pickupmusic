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

    data class Settings(
        val dark: Boolean,
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
        val customImageSources: List<com.shiyin.music.data.image.CustomImageSourceDef>,
        /** v1.3.x 歌词本:日语歌默认开振假名(默认 true)。 */
        val furiganaDefaultOn: Boolean,
        /** v1.3.x 歌词本:日语歌自动跑振假名校验(默认 true)。 */
        val furiganaAutoVerify: Boolean,
        /** v1.3.x 歌词本:歌词颜色 "default"(跟随封面)/"white"/"black"。 */
        val lyricsColorMode: String,
        /** v22: 检查更新状态(最近检查结果 + 已忽略版本)。 */
        val updateState: com.shiyin.music.data.update.UpdateState,
    )

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            dark = p[keyDark] ?: false,
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
            customImageSources = com.shiyin.music.data.image.CustomImageSourceCodec.decode(p[keyCustomImageSources] ?: ""),
            furiganaDefaultOn = p[keyFuriganaDefaultOn] ?: true,
            furiganaAutoVerify = p[keyFuriganaAutoVerify] ?: true,
            lyricsColorMode = (p[keyLyricsColorMode] ?: "default").let { if (it in listOf("default", "white", "black")) it else "default" },
            updateState = com.shiyin.music.data.update.UpdateStateCodec.decode(p[keyUpdateState] ?: ""),
        )
    }

    suspend fun setDark(v: Boolean) = context.dataStore.edit { it[keyDark] = v }
    suspend fun setGapless(v: Boolean) = context.dataStore.edit { it[keyGapless] = v }
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

    /** v1.3.2: 保存全部自定义写真源(JSON,见 CustomImageSourceCodec)。 */
    suspend fun setCustomImageSources(v: List<com.shiyin.music.data.image.CustomImageSourceDef>) =
        context.dataStore.edit { it[keyCustomImageSources] = com.shiyin.music.data.image.CustomImageSourceCodec.encode(v) }

    /** v1.3.x 歌词本设置写入(见字段注释;lyricsColorMode 只收 default/white/black)。 */
    suspend fun setFuriganaDefaultOn(v: Boolean) = context.dataStore.edit { it[keyFuriganaDefaultOn] = v }
    suspend fun setFuriganaAutoVerify(v: Boolean) = context.dataStore.edit { it[keyFuriganaAutoVerify] = v }
    suspend fun setLyricsColorMode(mode: String) =
        context.dataStore.edit { it[keyLyricsColorMode] = if (mode in listOf("white", "black")) mode else "default" }

    /** v22: 保存检查更新状态(JSON)。 */
    suspend fun setUpdateState(v: com.shiyin.music.data.update.UpdateState) =
        context.dataStore.edit { it[keyUpdateState] = com.shiyin.music.data.update.UpdateStateCodec.encode(v) }

    // ── v22: 设置随备份走 ────────────────────────────────────────────────────
    // 原来备份完全不含设置 —— 用户花最多时间配的 LLM 供应商 / API Key / 写真源
    // 换机后要从零再填一遍，这是最容易被忽略、但用户最痛的缺口。

    /**
     * 可备份的设置白名单：**只含用户配置与凭证**。
     *
     * 刻意排除的运行态：
     *  - `onboarded` / `first_scan_done` —— "本机已完成初始化"的标记，带到新机会让
     *    新机**跳过首次扫描引导**，反而像坏了
     *  - `recent_ids` —— 最近播放的本地 mediaId，跨设备无意义
     *  - `update_state` —— 检查更新缓存，新机自己会查
     */
    private val backupKeyNames = listOf(
        "dark_theme", "gapless", "auto_match", "auto_save_recognition",
        "playback_speed", "retro_speed_mode",
        "furigana_default_on", "furigana_auto_verify", "lyrics_color_mode",
        "deepseek_api_key", "tavily_api_key", "llm_providers", "llm_active_provider",
        "fanart_api_key", "lastfm_api_key", "disabled_image_sources", "custom_image_sources",
    )

    /** 导出白名单设置（值已编码成"类型前缀 + 原文"，见 [SettingsValueCodec]）。 */
    suspend fun exportBackupSettings(): Map<String, String> {
        val all = context.dataStore.data.first().asMap()
        val out = LinkedHashMap<String, String>()
        for (name in backupKeyNames) {
            val v = all.entries.firstOrNull { it.key.name == name }?.value ?: continue
            SettingsValueCodec.encode(v)?.let { out[name] = it }
        }
        return out
    }

    /**
     * 导入白名单设置。只写备份里**确实存在**的 key，其余保持本机不动（合并语义）。
     * 返回成功写入的条数。
     *
     * 白名单外的 key 一律拒绝 —— 备份文件是可以被手工编辑的，不加这道闸就等于
     * "任意 key 注入"。
     */
    suspend fun importBackupSettings(values: Map<String, String>): Int {
        var n = 0
        context.dataStore.edit { prefs ->
            for ((name, encoded) in values) {
                if (name !in backupKeyNames) continue
                when (SettingsValueCodec.typeOf(encoded)) {
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
                    else -> Unit   // 未知类型：跳过，不写坏
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

package com.shiyin.music.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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

    suspend fun pushRecent(id: Long) = context.dataStore.edit { p ->
        val cur = (p[keyRecent] ?: "").split(",").mapNotNull { it.toLongOrNull() }
        val next = (listOf(id) + cur.filter { it != id }).take(12)
        p[keyRecent] = next.joinToString(",")
    }
}

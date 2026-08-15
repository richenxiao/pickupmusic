package com.shiyin.music.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
    /** v5.2 Bug2: true only after the device has finished at least one full
     *  library scan *and* that scan result was absorbed into knownAlbumIds.
     *  Until this flips true, `detectNewAlbums` no-ops (absorbs ids + return),
     *  so覆盖安装升级的第一次扫描不会再把整个老库灌进你的更新. */
    private val keyFirstScanDone = booleanPreferencesKey("first_scan_done")

    data class Settings(
        val dark: Boolean,
        val gapless: Boolean,
        val autoMatch: Boolean,
        val onboarded: Boolean,
        val recentIds: List<Long>,
        val deepseekApiKey: String,
        val firstScanDone: Boolean,
    )

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            dark = p[keyDark] ?: false,
            gapless = p[keyGapless] ?: true,
            autoMatch = p[keyAutoMatch] ?: true,
            onboarded = p[keyOnboarded] ?: false,
            recentIds = (p[keyRecent] ?: "").split(",").mapNotNull { it.toLongOrNull() },
            deepseekApiKey = p[keyDeepSeek] ?: "",
            firstScanDone = p[keyFirstScanDone] ?: false,
        )
    }

    suspend fun setDark(v: Boolean) = context.dataStore.edit { it[keyDark] = v }
    suspend fun setGapless(v: Boolean) = context.dataStore.edit { it[keyGapless] = v }
    suspend fun setAutoMatch(v: Boolean) = context.dataStore.edit { it[keyAutoMatch] = v }
    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[keyOnboarded] = v }
    suspend fun setDeepSeekKey(v: String) = context.dataStore.edit { it[keyDeepSeek] = v }
    /** v5.2 Bug2: flip after the first scan post-install completes so the
     *  *next* scan onward is the one that starts diffing against
     *  `knownAlbumIds` and seeding `new_album`. */
    suspend fun setFirstScanDone(v: Boolean) = context.dataStore.edit { it[keyFirstScanDone] = v }

    suspend fun pushRecent(id: Long) = context.dataStore.edit { p ->
        val cur = (p[keyRecent] ?: "").split(",").mapNotNull { it.toLongOrNull() }
        val next = (listOf(id) + cur.filter { it != id }).take(12)
        p[keyRecent] = next.joinToString(",")
    }
}

package com.shiyin.music.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0：导入备份时**密钥类设置只在本机为空时才写入**。
 *
 * 为什么值得钉住：原来是无条件覆盖 —— 导入别人的备份（或自己那份但已换过密钥）会把本机配好的
 * Key 静默换掉，而"Agent 突然用不了""写真源突然失效"极难联想到"前几天导入过一份备份"。
 * 反过来完全不写也有代价（换机后密钥没了），所以规则是"本机为空才接受备份值"：
 * 换机（新机为空）照旧恢复，本机已配置的一律不动。
 */
class SettingsBackupSecretsTest {

    @Test
    fun `密钥项在本机已配置时不接受备份值`() {
        for (k in SECRET_BACKUP_KEYS) {
            assertFalse("本机已配置时不该被备份覆盖：$k", shouldWriteBackupValue(k, localBlank = false))
        }
    }

    @Test
    fun `密钥项在本机为空时接受备份值 - 保住换机场景`() {
        for (k in SECRET_BACKUP_KEYS) {
            assertTrue("换机后新机为空，应当恢复：$k", shouldWriteBackupValue(k, localBlank = true))
        }
    }

    @Test
    fun `非密钥项照旧无条件合并 - 行为偏好等要能随备份恢复`() {
        val normal = listOf(
            "auto_match", "playback_speed", "furigana_default_on", "lyrics_color_mode",
            "agent_thinking_collapsed", "memory_auto_extract", "agent_diag_enabled",
        )
        for (k in normal) {
            assertTrue("$k 不是密钥项，应照旧写入", shouldWriteBackupValue(k, localBlank = false))
            assertTrue(shouldWriteBackupValue(k, localBlank = true))
        }
    }

    @Test
    fun `五个含密钥的设置项都在保护名单里 - 防以后加 Key 时漏掉`() {
        // 白名单里的密钥项：4 个显式 api_key + llm_providers（供应商列表 JSON，每项带 apiKey）
        val expected = setOf(
            "deepseek_api_key", "tavily_api_key", "fanart_api_key", "lastfm_api_key", "llm_providers",
        )
        assertTrue("保护名单必须覆盖全部含密钥的设置项", SECRET_BACKUP_KEYS.containsAll(expected))
    }
}

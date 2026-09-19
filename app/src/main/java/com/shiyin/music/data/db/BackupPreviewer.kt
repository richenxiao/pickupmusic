package com.shiyin.music.data.db

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * v22: 恢复前的**预览**（纯逻辑，可单测）。
 *
 * 原来导入是"选文件 → 直接覆盖写库"，没有任何提示。误点一个旧备份就会把当前数据
 * 冲掉，而且不可撤销。预览解决两件事：
 *  1. 让用户看清"这次到底会恢复什么"（各表条数）
 *  2. **提前告知会有多少条因为找不到对应文件而被跳过** —— 换机场景下这是必然发生的，
 *     必须说清楚，而不是让用户以为"全恢复了"
 */
object BackupPreviewer {

    /** 表字段名 → 中文标签。用于预览文案；未知字段原样显示字段名。 */
    private val LABELS = mapOf(
        "albumOverride" to "专辑迁移",
        "albumInfoOverride" to "专辑修正",
        "trackInfoOverride" to "曲目修正",
        "trackAlbumMove" to "曲目迁专辑",
        "artists" to "歌手",
        "songArtist" to "曲目-歌手",
        "readingOverride" to "振假名注音",
        "externalReadingEvidence" to "外部假名",
        "artistImageCache" to "歌手写真缓存",
        "artistImageOverride" to "歌手写真手选",
        "albumSplit" to "整轨拆分",
        "splitTrack" to "拆分曲目",
        "playlists" to "歌单",
        "playlistTracks" to "歌单曲目",
        "manualLyrics" to "歌词",
        "albumOrders" to "专辑曲序",
        "artistAlbumOrders" to "歌手专辑序",
        "ignoredFolders" to "忽略文件夹",
        "playEvents" to "播放记录",
        "tokenUsage" to "Token 用量",
    )

    /** 非"表"的顶层字段，预览时不列。 */
    private val NON_TABLE = setOf(
        "version", "app", "exportedAt", "settings",
        BackupRemap.KEY_MEDIA_PATHS, BackupRemap.KEY_ALBUM_KEYS,
    )

    data class Preview(
        val version: Int,
        val exportedAt: Long,
        /** 表字段名 → 条数（只含非零项，按 LABELS 顺序）。 */
        val counts: LinkedHashMap<String, Int>,
        /** 设置项条数。 */
        val settingsCount: Int,
        /** 重解析后会因为找不到文件/专辑而丢弃的行数。 */
        val tracksDropped: Int,
        val albumsDropped: Int,
        /** 备份里没有重解析表（v1 老备份）→ 不会做重映射。 */
        val remapSkipped: Boolean,
        /** 带过来的本地图片文件数。 */
        val imageFiles: Int,
    ) {
        val totalRows: Int get() = counts.values.sum()
        val isEmpty: Boolean get() = totalRows == 0 && settingsCount == 0 && imageFiles == 0
        val hasLoss: Boolean get() = tracksDropped > 0 || albumsDropped > 0

        /** 人类可读的一行摘要，例如「歌单 3 · 歌单曲目 42 · 设置 21 项」。 */
        fun summary(): String {
            val parts = ArrayList<String>()
            for ((k, v) in counts) {
                if (v > 0) parts += "${LABELS[k] ?: k} $v"
            }
            if (settingsCount > 0) parts += "设置 $settingsCount 项"
            if (imageFiles > 0) parts += "图片 $imageFiles 个"
            return if (parts.isEmpty()) "（空备份）" else parts.joinToString(" · ")
        }

        /** 需要提醒用户的损失说明；没有损失返回 null。 */
        fun lossNote(): String? {
            if (!hasLoss) return null
            val parts = ArrayList<String>()
            if (tracksDropped > 0) parts += "曲目相关 $tracksDropped 条"
            if (albumsDropped > 0) parts += "专辑相关 $albumsDropped 条"
            return "有 ${parts.joinToString("、")}在本机找不到对应文件/专辑，将被跳过"
        }
    }

    fun preview(
        json: String,
        pathToMediaId: Map<String, Long>,
        nameKeyToAlbumId: Map<String, Long>,
        imageFiles: Int = 0,
    ): Preview {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: Exception) {
            return Preview(0, 0, LinkedHashMap(), 0, 0, 0, true, imageFiles)
        }
        val counts = LinkedHashMap<String, Int>()
        // 先按 LABELS 的顺序列（保证预览文案稳定），再补上未知的数组字段
        for (key in LABELS.keys) {
            val n = (root.get(key) as? JsonArray)?.size() ?: 0
            if (n > 0) counts[key] = n
        }
        for ((name, el) in root.entrySet()) {
            if (name in LABELS || name in NON_TABLE) continue
            val n = (el as? JsonArray)?.size() ?: 0
            if (n > 0) counts[name] = n
        }
        val settingsCount = (root.get("settings") as? JsonObject)?.size() ?: 0
        val remap = BackupRemap.apply(json, pathToMediaId, nameKeyToAlbumId)
        return Preview(
            version = root.get("version")?.let { runCatching { it.asInt }.getOrNull() } ?: 1,
            exportedAt = root.get("exportedAt")?.let { runCatching { it.asLong }.getOrNull() } ?: 0L,
            counts = counts,
            settingsCount = settingsCount,
            tracksDropped = remap.tracksDropped,
            albumsDropped = remap.albumsDropped,
            remapSkipped = remap.skipped,
            imageFiles = imageFiles,
        )
    }
}

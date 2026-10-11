package com.shiyin.music.data.db

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * v1.5.0 A60：备份**分类裁剪**的正确性。
 *
 * 为什么单测：`applyCategories` 把 11 个分类映射到 30+ 个字段，纯靠肉眼极易把某个
 * 分类的字段写错（写错的表现是"用户关掉了『翻译』但包里还有译文"——体积白花、
 * 也可能把不想带出去的东西带出去）。这里逐分类断言"只保留该分类该有的东西"。
 */
class BackupCategoriesTest {

    private fun full() = BackupManager.BackupData(
        exportedAt = 0L,
        albumOverride = listOf(AlbumOverrideEntity(mediaId = 1, albumName = "甲专辑", artistName = "甲")),
        albumInfoOverride = listOf(AlbumInfoOverrideEntity(albumId = 1)),
        trackInfoOverride = listOf(TrackInfoOverrideEntity(mediaId = 1)),
        trackAlbumMove = listOf(TrackAlbumMoveEntity(mediaId = 1, albumId = 2)),
        artists = listOf(ArtistEntity(name = "甲")),
        songArtist = listOf(SongArtistEntity(mediaId = 1, artistId = 1)),
        readingOverride = listOf(
            ReadingOverrideEntity(scope = "GLOBAL", mediaId = 0, lyricsHash = "", lineIndex = 0, charStart = 0, surface = "何", reading = "なん"),
        ),
        externalReadingEvidence = listOf(
            ExternalReadingEvidenceEntity(
                mediaId = 1, lyricsHash = "h", lineIndex = 0, charStart = 0, length = 1,
                surface = "何", reading = "なん", source = "test", confidence = 1, fetchedAt = 0,
            ),
        ),
        artistImageCache = listOf(ArtistImageCacheEntity(name = "甲", url = "https://x/1.jpg")),
        artistImageOverride = listOf(ArtistImageOverrideEntity(name = "甲", url = "file:///y.jpg")),
        lyricsTranslation = listOf(
            LyricsTranslationEntity(mediaId = 1, lyricsHash = "h", payload = "[]", source = "llm", translatedAt = 0),
        ),
        manualLyrics = listOf(
            SavedLyricEntity(mediaId = 1, lyrics = "[00:00]a", source = "import", offsetMs = 0, savedAt = 0, autoSaved = 0),
        ),
        playlists = listOf(PlaylistEntity(id = "fav", name = "我的喜欢", sortIdx = 0)),
        playlistTracks = listOf(PlaylistTrackEntity(playlistId = "fav", mediaId = 1, addedAt = 0)),
        albumOrders = listOf(AlbumOrderEntity(albumKey = "a", orderedIds = "1,2")),
        artistAlbumOrders = listOf(ArtistAlbumOrderEntity(artistName = "甲", albumKeys = "a")),
        ignoredFolders = listOf(IgnoredFolderEntity(path = "/x")),
        playEvents = listOf(PlayEventEntity(mediaId = 1, playedAt = 1, durationSec = 200, playedSec = 40, completed = true)),
        tokenUsage = listOf(TokenUsageLogEntity(timestamp = 1, promptTokens = 1, completionTokens = 1)),
        agentMemory = listOf(AgentMemoryEntity(id = "m1", content = "用户喜欢爵士")),
        agentConversations = listOf(
            AgentConversationEntity(id = "c1", title = "聊天", createdAt = 0, updatedAt = 1, messagesJson = "[]"),
        ),
        albumArtCache = listOf(AlbumArtCacheEntity(albumId = 1, url = "https://x/c.jpg", source = "itunes")),
        trashedTracks = listOf(
            TrashedTrackEntity(mediaId = 9, title = "删掉的歌", artist = "甲", folder = "/x", sizeBytes = 1, trashedAt = 0),
        ),
        playCounts = listOf(PlayCountEntity(mediaId = 1, count = 7)),
        newAlbums = listOf(NewAlbumEntity(albumId = 1, firstSeenAt = 0)),
        artistAliases = listOf(ArtistAliasEntity(fromName = "甲 feat. 乙", toName = "甲")),
        settings = mapOf("gapless" to "b:1", "deepseek_api_key" to "s:sk-x", "webdav_password" to "s:pw"),
    )

    /** 关掉的分类字段是 **null**（不是空列表）——断言用这个。 */
    private fun <T> off(l: List<T>?): Boolean = l == null || l.isEmpty()

    /** 只留一个分类时，该分类该有的字段必须还在、且其它分类的字段必须清空。 */
    @Test
    fun editsOnlyKeepsLibraryEdits() {
        val d = BackupManager.applyCategories(full(), setOf(BackupCategory.EDITS.id))
        assertTrue(d.playlists.isNotEmpty())
        assertTrue(d.trashedTracks!!.isNotEmpty())
        assertTrue(d.artistAliases!!.isNotEmpty())
        assertTrue(d.albumOrders.isNotEmpty())
        // 其它分类清空
        assertTrue(d.manualLyrics.isEmpty())
        assertTrue(off(d.lyricsTranslation))
        assertTrue(d.readingOverride.isEmpty())
        assertTrue(off(d.agentConversations))
        assertTrue(off(d.agentMemory))
        assertTrue(off(d.playCounts))
        assertTrue(d.settings.isEmpty())
    }

    @Test
    fun lyricsTranslationReadingAreIndependent() {
        val onlyLyrics = BackupManager.applyCategories(full(), setOf(BackupCategory.LYRICS.id))
        assertTrue(onlyLyrics.manualLyrics.isNotEmpty())
        assertTrue(off(onlyLyrics.lyricsTranslation))

        val onlyTrans = BackupManager.applyCategories(full(), setOf(BackupCategory.TRANSLATION.id))
        assertTrue(onlyTrans.lyricsTranslation!!.isNotEmpty())
        assertTrue(onlyTrans.manualLyrics.isEmpty())

        val onlyReading = BackupManager.applyCategories(full(), setOf(BackupCategory.READING.id))
        assertTrue(onlyReading.readingOverride.isNotEmpty())
        assertTrue(onlyReading.externalReadingEvidence.isNotEmpty())
        assertTrue(onlyReading.manualLyrics.isEmpty())
    }

    @Test
    fun agentChatAndMemoryAreSeparate() {
        val chat = BackupManager.applyCategories(full(), setOf(BackupCategory.CHAT.id))
        assertTrue(chat.agentConversations!!.isNotEmpty())
        assertTrue(off(chat.agentMemory))

        val mem = BackupManager.applyCategories(full(), setOf(BackupCategory.MEMORY.id))
        assertTrue(mem.agentMemory!!.isNotEmpty())
        assertTrue(off(mem.agentConversations))
    }

    @Test
    fun imagesAndSourceLinksSplit() {
        val imgs = BackupManager.applyCategories(full(), setOf(BackupCategory.IMAGES.id))
        assertTrue(imgs.artistImageOverride.isNotEmpty())   // 手动图（本体另由 VM 打进 zip）
        assertTrue(off(imgs.albumArtCache))          // 链接不跟着图片走
        assertTrue(imgs.artistImageCache.isEmpty())

        val src = BackupManager.applyCategories(full(), setOf(BackupCategory.SOURCES.id))
        assertTrue(src.albumArtCache!!.isNotEmpty())
        assertTrue(src.artistImageCache.isNotEmpty())
        assertTrue(src.artistImageOverride.isEmpty())
    }

    @Test
    fun secretsAreSplitFromNormalSettings() {
        val secrets = setOf("deepseek_api_key", "webdav_password")

        // 只勾「应用设置」→ 只有非密钥项（gapless）
        val normal = BackupManager.applyCategories(full(), setOf(BackupCategory.SETTINGS.id), secrets)
        assertEquals(setOf("gapless"), normal.settings.keys)

        // 只勾「API Key 与凭据」→ 只有密钥项
        val sec = BackupManager.applyCategories(full(), setOf(BackupCategory.SECRETS.id), secrets)
        assertEquals(setOf("deepseek_api_key", "webdav_password"), sec.settings.keys)

        // 两类都勾 → 全都在
        val both = BackupManager.applyCategories(
            full(),
            setOf(BackupCategory.SETTINGS.id, BackupCategory.SECRETS.id),
            secrets,
        )
        assertEquals(3, both.settings.size)
    }

    @Test
    fun emptySelectionKeepsNothingButMeta() {
        val d = BackupManager.applyCategories(full(), emptySet())
        assertTrue(d.playlists.isEmpty())
        assertTrue(d.manualLyrics.isEmpty())
        assertTrue(off(d.agentConversations))
        assertTrue(d.settings.isEmpty())
    }
}

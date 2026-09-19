package com.shiyin.music.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v22: 备份归档（zip 容器）的单测。
 *
 * 关键性质：
 *  1. JSON + 图片文件必须能无损往返（UTF-8，含中文）
 *  2. **必须兼容纯 JSON 旧格式** —— 老备份文件不能因为格式升级就读不了
 *  3. **zip-slip 必须被挡住** —— 备份包可被手工编辑，条目名里的 `../` 能写出私有目录之外
 */
class BackupArchiveTest {

    @Test fun roundTripWithFiles() {
        val json = """{"version":2,"note":"中文内容 ✓"}"""
        val files = mapOf(
            "artist_image_override/某人_1.jpg" to byteArrayOf(1, 2, 3),
            "album_cover_override/album_9_1.jpg" to byteArrayOf(4, 5),
        )
        val a = BackupArchive.read(BackupArchive.write(json, files))
        assertTrue(a.isZip)
        assertEquals(json, a.json)
        assertEquals(2, a.files.size)
        assertEquals(listOf<Byte>(1, 2, 3), a.files["artist_image_override/某人_1.jpg"]!!.toList())
    }

    @Test fun roundTripWithoutFiles() {
        val a = BackupArchive.read(BackupArchive.write("""{"version":2}""", emptyMap()))
        assertTrue(a.isZip)
        assertEquals("""{"version":2}""", a.json)
        assertTrue(a.files.isEmpty())
    }

    /** 老格式（直接是 JSON 字节）必须照常能读 —— 否则升级后用户的老备份全废。 */
    @Test fun plainJsonIsAccepted() {
        val raw = """{"version":1,"trackInfoOverride":[{"mediaId":1}]}""".toByteArray(Charsets.UTF_8)
        val a = BackupArchive.read(raw)
        assertFalse(a.isZip)
        assertEquals(raw.toString(Charsets.UTF_8), a.json)
        assertTrue(a.files.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun zipWithoutBackupJsonThrows() {
        // 一个只有图片、没有 backup.json 的包
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("files/x.jpg"))
            z.write(byteArrayOf(1))
            z.closeEntry()
        }
        BackupArchive.read(bos.toByteArray())
    }

    // ── zip-slip 防护 ──────────────────────────────────────────────────────
    @Test fun sanitizeRejectsUnsafeNames() {
        assertNull(BackupArchive.sanitize(""))
        assertNull(BackupArchive.sanitize("/etc/passwd"))
        assertNull(BackupArchive.sanitize("\\windows\\x"))
        assertNull(BackupArchive.sanitize("../evil.jpg"))
        assertNull(BackupArchive.sanitize("a/../../evil.jpg"))
        assertNull(BackupArchive.sanitize("C:/x.jpg"))
        assertNull(BackupArchive.sanitize("a/./b.jpg"))
        assertEquals("a/b.jpg", BackupArchive.sanitize("a/b.jpg"))
        assertEquals("a/b.jpg", BackupArchive.sanitize("a//b.jpg"))
        assertEquals("x.jpg", BackupArchive.sanitize("x.jpg"))
    }

    /** 手工构造一个带 `../` 条目的恶意包：该条目必须被丢掉，其余正常读入。 */
    @Test fun zipSlipEntriesAreDropped() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(BackupArchive.ENTRY_JSON))
            z.write("""{"version":2}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("files/../../evil.jpg"))
            z.write(byteArrayOf(9))
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("files/ok.jpg"))
            z.write(byteArrayOf(8))
            z.closeEntry()
        }
        val a = BackupArchive.read(bos.toByteArray())
        assertEquals(1, a.files.size)
        assertTrue(a.files.containsKey("ok.jpg"))
        assertTrue("恶意条目必须被丢弃", a.files.keys.none { it.contains("..") })
    }

    /** 包内目录项（以 / 结尾）应被忽略，不当成文件。 */
    @Test fun directoryEntriesIgnored() {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(BackupArchive.ENTRY_JSON))
            z.write("""{"version":2}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("files/"))
            z.closeEntry()
        }
        val a = BackupArchive.read(bos.toByteArray())
        assertTrue(a.files.isEmpty())
    }
}

/**
 * v22: 恢复前预览的单测。
 *
 * 重点是**提前把损失说清楚** —— 换机场景下必然有记录找不到对应文件，
 * 用户必须在点"恢复"之前就知道会跳过多少条。
 */
class BackupPreviewerTest {

    private val pathToId = mapOf("/m/a.flac" to 9001L)
    private val nameToAlbum = mapOf("专辑A|歌手X" to 7001L)

    private fun backup(): String = """
        {
          "version":2,
          "exportedAt":1700000000000,
          "mediaIdPaths":{"101":"/m/a.flac","102":"/m/gone.flac"},
          "albumIdKeys":{"201":"专辑A|歌手X"},
          "trackInfoOverride":[{"mediaId":101},{"mediaId":102}],
          "playlists":[{"id":"p1","name":"我的歌单","sortIdx":0}],
          "playlistTracks":[{"playlistId":"p1","mediaId":101},{"playlistId":"p1","mediaId":102}],
          "manualLyrics":[{"mediaId":101,"lyrics":"..."}],
          "settings":{"dark_theme":"btrue","llm_providers":"s{}"}
        }
    """.trimIndent()

    @Test fun countsRowsAndSettings() {
        val p = BackupPreviewer.preview(backup(), pathToId, nameToAlbum)
        assertEquals(2, p.version)
        assertEquals(1700000000000L, p.exportedAt)
        assertEquals(2, p.counts["trackInfoOverride"])
        assertEquals(1, p.counts["playlists"])
        assertEquals(2, p.counts["playlistTracks"])
        assertEquals(1, p.counts["manualLyrics"])
        assertEquals(2, p.settingsCount)
        // 没有的表明明是 0，不该出现在列表里
        assertFalse(p.counts.containsKey("tokenUsage"))
    }

    /** 找不到文件的记录要**提前报出来**。 */
    @Test fun reportsDropsBeforeRestoring() {
        val p = BackupPreviewer.preview(backup(), pathToId, nameToAlbum)
        // trackInfoOverride 里 102 找不到；playlistTracks 里 102 也找不到
        assertTrue(p.tracksDropped >= 2)
        assertTrue(p.hasLoss)
        assertTrue(p.lossNote()!!.contains("找不到"))
    }

    @Test fun noLossWhenEverythingResolves() {
        val json = """{"version":2,"mediaIdPaths":{"101":"/m/a.flac"},"albumIdKeys":{},
            "trackInfoOverride":[{"mediaId":101}]}"""
        val p = BackupPreviewer.preview(json, pathToId, nameToAlbum)
        assertEquals(0, p.tracksDropped)
        assertFalse(p.hasLoss)
        assertNull(p.lossNote())
    }

    @Test fun v1BackupIsMarkedAsRemapSkipped() {
        val p = BackupPreviewer.preview("""{"version":1,"trackInfoOverride":[{"mediaId":101}]}""", pathToId, nameToAlbum)
        assertTrue(p.remapSkipped)
        assertEquals(1, p.version)
        assertEquals(0, p.tracksDropped)
    }

    @Test fun summaryIsHumanReadable() {
        val p = BackupPreviewer.preview(backup(), pathToId, nameToAlbum)
        val s = p.summary()
        assertTrue("应含中文标签：$s", s.contains("歌单"))
        assertTrue("应含设置项数：$s", s.contains("设置 2 项"))
    }

    @Test fun emptyBackupIsEmpty() {
        val p = BackupPreviewer.preview("""{"version":2}""", pathToId, nameToAlbum)
        assertTrue(p.isEmpty)
        assertEquals("（空备份）", p.summary())
    }

    @Test fun garbageJsonYieldsEmptyPreview() {
        val p = BackupPreviewer.preview("not json", pathToId, nameToAlbum)
        assertTrue(p.isEmpty)
        assertEquals(0, p.totalRows)
    }

    @Test fun imageFilesCounted() {
        val p = BackupPreviewer.preview("""{"version":2}""", pathToId, nameToAlbum, imageFiles = 3)
        assertEquals(3, p.imageFiles)
        assertFalse(p.isEmpty)
        assertTrue(p.summary().contains("图片 3 个"))
    }
}

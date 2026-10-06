package com.shiyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.5.0 B2①-2：forceReindex 只对 MediaStore 尚未收录的文件调 scanFile。
 *
 * 背景（2026-10-07 真机取证）：设置→「重新扫描文件」时，
 * walk 出 4090 个音频文件、全部丢给 MediaStoreConnection.scanFile，
 * 其中 2066 个早已在 MediaStore 里 —— 这 12s 的等待纯属重复劳动。
 */
class ReindexPlannerTest {

    @Test
    fun `已收录的文件不再送扫_只返回未收录的`() {
        val fs = listOf("/Music/a.flac", "/Music/b.flac", "/Download/c.mp3")
        val indexed = setOf("/Music/a.flac", "/Download/c.mp3")
        assertEquals(listOf("/Music/b.flac"), ReindexPlanner.filesNeedingReindex(fs, indexed))
    }

    @Test
    fun `全部已收录时返回空表_不触发任何 scanFile`() {
        val fs = listOf("/Music/a.flac", "/Music/b.flac")
        assertEquals(emptyList<String>(), ReindexPlanner.filesNeedingReindex(fs, fs.toSet()))
    }

    @Test
    fun `MediaStore 为空时全部都要送扫_且保持文件系统遍历顺序`() {
        val fs = listOf("/Music/z.flac", "/Music/a.flac", "/Download/m.mp3")
        assertEquals(fs, ReindexPlanner.filesNeedingReindex(fs, emptySet()))
    }

    @Test
    fun `空输入返回空表`() {
        assertEquals(emptyList<String>(), ReindexPlanner.filesNeedingReindex(emptyList(), setOf("/Music/a.flac")))
    }

    // ── pruneNestedFolders ────────────────────────────────────────────────
    // 真机取证：17 个 knownFolders 里有嵌套项，walkTopDown 走出 4090 条记录，
    // 实际只有 2066 个文件 —— 同一批文件被走了两遍。

    @Test
    fun `嵌套子目录被父目录吸收`() {
        val folders = setOf("/Music", "/Music/Album A", "/Music/Album B", "/Download")
        assertEquals(setOf("/Music", "/Download"), ReindexPlanner.pruneNestedFolders(folders))
    }

    @Test
    fun `同级目录互不吸收，全部保留`() {
        val folders = setOf("/Music/A", "/Music/B", "/Download")
        assertEquals(folders, ReindexPlanner.pruneNestedFolders(folders))
    }

    @Test
    fun `前缀相同但不是子目录的必须保留`() {
        // "/Music2" 不是 "/Music" 的子目录，纯 startsWith 会误删
        assertEquals(setOf("/Music", "/Music2"), ReindexPlanner.pruneNestedFolders(setOf("/Music", "/Music2")))
    }

    @Test
    fun `三层嵌套只留最外层`() {
        val folders = setOf("/Music", "/Music/Album", "/Music/Album/CD1", "/Music/Album/CD2")
        assertEquals(setOf("/Music"), ReindexPlanner.pruneNestedFolders(folders))
    }

    @Test
    fun `空集合返回空集合`() {
        assertEquals(emptySet<String>(), ReindexPlanner.pruneNestedFolders(emptySet()))
    }

    @Test
    fun `存储根目录吸收所有子目录`() {
        // 真机取证：有 10 首歌直接躺在存储根下 → Track.folder == "/"。
        // "/" 会 walkTopDown 整个内部存储，而其余目录都是它的子目录 → 同一批文件走两遍
        // （实测 4112 条记录对应 2077 个文件）。首版用 startsWith("/" + "/") = "//" 判定，
        // 对 "/" 永远不成立，所以没吸收掉。
        val folders = setOf("/", "/Music", "/Download/netease/cloudmusic/Music", "/害")
        assertEquals(setOf("/"), ReindexPlanner.pruneNestedFolders(folders))
    }

    @Test
    fun `只有根目录时原样保留`() {
        assertEquals(setOf("/"), ReindexPlanner.pruneNestedFolders(setOf("/")))
    }
}

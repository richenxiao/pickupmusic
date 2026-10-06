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
}

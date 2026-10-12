package com.shiyin.music.data.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.5.0 P1：伴奏块缓存。
 * 重点验三条硬约束：原子写入（崩溃后读不到半写块）、按帧随机读、缺块可重试。
 */
class StemCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun cache() = StemCache(File(tmp.root, "pickup/accompaniment-cache"))

    private fun shorts(n: Int, seed: Int): ShortArray =
        ShortArray(n) { ((it * 31 + seed) % 20000 - 10000).toShort() }

    @Test
    fun writeThenReadBackExactly() {
        val c = cache()
        val dir = c.variantDir(1L, "mdx_9482", "1", "abc123")
        val gen = 8
        c.writeChunk(dir, 0, shorts(gen * 2, 1))
        c.writeChunk(dir, 1, shorts(gen * 2, 2))
        c.writeManifest(dir, "mdx_9482", "1", "abc123", gen, totalChunks = 2, complete = true, lastAccess = 1L)

        val got = c.readFrames(dir, startFrame = 0, frames = 2 * gen)
        assertNotNull(got)
        val expect = shorts(gen * 2, 1) + shorts(gen * 2, 2)
        assertEquals(expect.size, got!!.size)
        for (i in expect.indices) assertEquals("样本 $i 不一致", expect[i], got[i])
    }

    /** 跨块边界的随机读（帧号不从块首开始）。 */
    @Test
    fun randomAccessAcrossChunks() {
        val c = cache()
        val dir = c.variantDir(2L, "m", "1", "fp")
        val gen = 4
        for (i in 0 until 3) c.writeChunk(dir, i, shorts(gen * 2, i + 10))
        c.writeManifest(dir, "m", "1", "fp", gen, 3, complete = true, lastAccess = 0)

        val got = c.readFrames(dir, startFrame = 3, frames = 4)!!
        val all = shorts(gen * 2, 10) + shorts(gen * 2, 11) + shorts(gen * 2, 12)
        for (i in 0 until 4) {
            assertEquals(all[(3 + i) * 2], got[i * 2])
            assertEquals(all[(3 + i) * 2 + 1], got[i * 2 + 1])
        }
    }

    /** 缺块 → 返回 null（调用方按未就绪处理，绝不返回半截数据）。 */
    @Test
    fun missingChunkYieldsNull() {
        val c = cache()
        val dir = c.variantDir(3L, "m", "1", "fp")
        c.writeChunk(dir, 0, shorts(8, 1))
        c.writeManifest(dir, "m", "1", "fp", 4, 3, complete = false, lastAccess = 0)
        assertNull("只有第 0 块，读第 1 块必须失败", c.readFrames(dir, 4, 4))
        assertEquals(1, c.firstMissingChunk(dir, 3))
        assertEquals(1, c.doneChunks(dir))
    }

    /** 半写块（.tmp）绝不被读：只有 rename 完成才算存在。 */
    @Test
    fun tempFileIsNeverRead() {
        val c = cache()
        val dir = c.variantDir(4L, "m", "1", "fp")
        dir.mkdirs()
        File(dir, "c00001.tmp").writeBytes(ByteArray(64))   // 模拟崩溃留下的半写块
        c.writeManifest(dir, "m", "1", "fp", 4, 2, complete = false, lastAccess = 0)
        assertTrue(!c.hasChunk(dir, 1))
        assertNull(c.readFrames(dir, 4, 4))
        assertEquals(1, c.purgeTempFiles())                 // 对账时清掉
        assertTrue(!File(dir, "c00001.tmp").exists())
    }

    @Test
    fun manifestRoundTripAndCompleteGate() {
        val c = cache()
        val dir = c.variantDir(5L, "mdx_9482", "2", "fp9")
        c.writeChunk(dir, 0, shorts(8, 1))
        // 还没标 complete → 不能当命中
        c.writeManifest(dir, "mdx_9482", "2", "fp9", 4, 1, complete = false, lastAccess = 7L)
        assertNull(c.findComplete(5L, "mdx_9482", "2", "fp9"))
        val m = c.readManifest(dir)!!
        assertEquals(4, m.genSize); assertEquals(1, m.totalChunks); assertEquals(7L, m.lastAccess)

        c.writeManifest(dir, "mdx_9482", "2", "fp9", 4, 1, complete = true, lastAccess = 9L)
        assertNotNull(c.findComplete(5L, "mdx_9482", "2", "fp9"))
    }

    /** 指纹不同 = 不同变体，互不覆盖（同一首歌换了音频或换了引擎版本）。 */
    @Test
    fun fingerprintAndEngineVersionSeparateVariants() {
        val c = cache()
        val a = c.variantDir(6L, "m", "1", "aaaa")
        val b = c.variantDir(6L, "m", "1", "bbbb")
        val a2 = c.variantDir(6L, "m", "1", "aaaa", engineVersion = 2)
        assertTrue(a.path != b.path && a.path != a2.path)
    }

    @Test
    fun usageAndCachedSongsAndDelete() {
        val c = cache()
        for (song in listOf(7L, 8L)) {
            val dir = c.variantDir(song, "m", "1", "fp")
            c.writeChunk(dir, 0, shorts(100, 1))
            c.writeManifest(dir, "m", "1", "fp", 50, 1, complete = true, lastAccess = song)
        }
        assertTrue(c.usageBytes() > 0)
        val songs = c.cachedSongs()
        assertEquals(setOf(7L, 8L), songs.map { it.songId }.toSet())
        assertTrue(songs.all { it.complete })

        assertTrue(c.deleteSong(7L))
        assertEquals(listOf(8L), c.cachedSongs().map { it.songId })
        assertTrue(c.deleteModel(8L, "m"))
        assertTrue(c.cachedSongs().isEmpty())
    }
}

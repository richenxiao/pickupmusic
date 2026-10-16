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
 *
 * v1.5.0 块 2：身份由 **songId** 改为 **物理文件维度**（[StemCache.SourceKey]）——
 * 拆分单曲的各段共用同一个整轨文件，必须认成同一份产出。
 */
class StemCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun cache() = StemCache(File(tmp.root, "pickup/accompaniment-cache"))

    /** 物理文件身份：指纹 + 采样率 + 声道 + 总帧数。测试里只用指纹区分不同"文件"。 */
    private fun key(fp: String, frames: Int = 1000) = StemCache.SourceKey(fp, 44100, 2, frames)

    private fun shorts(n: Int, seed: Int): ShortArray =
        ShortArray(n) { ((it * 31 + seed) % 20000 - 10000).toShort() }

    @Test
    fun writeThenReadBackExactly() {
        val c = cache()
        val k = key("abc123")
        val dir = c.variantDir(k, "mdx_9482", "1")
        val gen = 8
        c.writeChunk(dir, 0, shorts(gen * 2, 1))
        c.writeChunk(dir, 1, shorts(gen * 2, 2))
        c.writeManifest(dir, "mdx_9482", "1", k.fingerprint, gen, totalChunks = 2, complete = true, lastAccess = 1L)

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
        val k = key("fp2")
        val dir = c.variantDir(k, "m", "1")
        val gen = 4
        for (i in 0 until 3) c.writeChunk(dir, i, shorts(gen * 2, i + 10))
        c.writeManifest(dir, "m", "1", k.fingerprint, gen, 3, complete = true, lastAccess = 0)

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
        val k = key("fp3")
        val dir = c.variantDir(k, "m", "1")
        c.writeChunk(dir, 0, shorts(8, 1))
        c.writeManifest(dir, "m", "1", k.fingerprint, 4, 3, complete = false, lastAccess = 0)
        assertNull("只有第 0 块，读第 1 块必须失败", c.readFrames(dir, 4, 4))
        assertEquals(1, c.firstMissingChunk(dir, 3))
        assertEquals(1, c.doneChunks(dir))
    }

    /** 半写块（.tmp）绝不被读：只有 rename 完成才算存在。 */
    @Test
    fun tempFileIsNeverRead() {
        val c = cache()
        val k = key("fp4")
        val dir = c.variantDir(k, "m", "1")
        dir.mkdirs()
        File(dir, "c00001.tmp").writeBytes(ByteArray(64))   // 模拟崩溃留下的半写块
        c.writeManifest(dir, "m", "1", k.fingerprint, 4, 2, complete = false, lastAccess = 0)
        assertTrue(!c.hasChunk(dir, 1))
        assertNull(c.readFrames(dir, 4, 4))
        assertEquals(1, c.purgeTempFiles())                 // 对账时清掉
        assertTrue(!File(dir, "c00001.tmp").exists())
    }

    @Test
    fun manifestRoundTripAndCompleteGate() {
        val c = cache()
        val k = key("fp9")
        val dir = c.variantDir(k, "mdx_9482", "2")
        c.writeChunk(dir, 0, shorts(8, 1))
        // 还没标 complete → 不能当命中
        c.writeManifest(dir, "mdx_9482", "2", k.fingerprint, 4, 1, complete = false, lastAccess = 7L)
        assertNull(c.findComplete(k, "mdx_9482", "2"))
        val m = c.readManifest(dir)!!
        assertEquals(4, m.genSize); assertEquals(1, m.totalChunks); assertEquals(7L, m.lastAccess)

        c.writeManifest(dir, "mdx_9482", "2", k.fingerprint, 4, 1, complete = true, lastAccess = 9L)
        assertNotNull(c.findComplete(k, "mdx_9482", "2"))
    }

    /** 指纹不同 = 不同变体，互不覆盖（同一首歌换了音频或换了引擎版本）。 */
    @Test
    fun fingerprintAndEngineVersionSeparateVariants() {
        val c = cache()
        val a = c.variantDir(key("aaaa"), "m", "1")
        val b = c.variantDir(key("bbbb"), "m", "1")
        // 引擎版本必须与"当前"不同，才验得到"版本变了就换目录"（当前 = ENGINE_VERSION，P0-1 起为 2）
        val a2 = c.variantDir(key("aaaa"), "m", "1", engineVersion = StemCache.ENGINE_VERSION + 1)
        assertTrue(a.path != b.path && a.path != a2.path)
    }

    /** 拆分单曲：同一个物理文件的**不同片段**必须落到**同一个目录**（否则同一份产出会被算 N 遍）。 */
    @Test
    fun sameFileDifferentSongIdsShareOneCacheDir() {
        val c = cache()
        val physical = key("samefile")
        val d1 = c.variantDir(physical, "mdx_9482", "1")
        val d2 = c.variantDir(physical, "mdx_9482", "1")
        assertEquals(d1.path, d2.path)
    }

    @Test
    fun usageAndCachedSongsAndDelete() {
        val c = cache()
        val keys = listOf(key("s7"), key("s8"))
        keys.forEachIndexed { i, k ->
            val dir = c.variantDir(k, "m", "1")
            c.writeChunk(dir, 0, shorts(100, 1))
            c.writeManifest(dir, "m", "1", k.fingerprint, 50, 1, complete = true, lastAccess = (i + 7).toLong())
        }
        assertTrue(c.usageBytes() > 0)
        val songs = c.cachedSongs()
        assertEquals(
            "身份现在是目录名（物理文件维度），不再是 songId",
            keys.map { it.dirName }.toSet(), songs.map { it.keyDirName }.toSet(),
        )
        assertTrue(songs.all { it.complete })

        assertTrue(c.deleteSource(keys[0]))
        assertEquals(listOf(keys[1].dirName), c.cachedSongs().map { it.keyDirName })
        assertTrue(c.deleteModel(keys[1], "m"))
        assertTrue(c.cachedSongs().isEmpty())
    }
}

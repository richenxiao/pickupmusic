package com.shiyin.music.data.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 块 3 修复：**不经播放器的往返测试**（用户 2026-10-14 要求）。
 *
 * 为什么必须有：零拍闸门（`seed`：把原曲本身当伴奏写进缓存 ⇒ 人声档必须恒为 0）一旦失败，
 * 分不清是「播放器 → 处理器的帧号换算」错还是「缓存写读本身」错。这个测试把后者**单独钉死**：
 * 用同一个 [StemCache] 写进去、用**同一个读取函数** [StemCache.readFrames] 读回来，逐采样相等。
 * 它不过，就不必去查播放链路。
 *
 * 与 [StemCacheTest] 的区别：那边只读**整块对齐**的位置；这里专门覆盖
 * ① 非对齐起点；② 跨块；③ **跨进最后那个短块**；④ 极值样本（-32768/32767）；
 * ⑤ 越界 / 缺块请求**返回 null 而不是抛异常**（短块配 `readFully` 曾会 EOFException，
 * 被混音路径 catch 成"退回透传" ⇒ 歌曲末尾几秒伴奏静默消失）。
 */
class StemCacheRoundTripTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 每块 8 帧；总 20 帧 ⇒ 3 块，最后一块只有 4 帧（**短块**，专门用来抓"块里有 genSize 帧"的错误假设）。 */
    private val gen = 8
    private val totalFrames = 20

    /** 帧号编进样本值：L = 帧号，R = −帧号−1（含负数与零，能抓"漏一帧 / 错一帧 / 声道串位"）。 */
    private fun sampleOf(frame: Int): ShortArray =
        shortArrayOf(frame.toShort(), (-frame - 1).toShort())

    private fun seed(): Pair<StemCache, File> {
        val c = StemCache(File(tmp.root, "pickup/accompaniment-cache"))
        val k = StemCache.SourceKey("rt-fp", 44100, 2, totalFrames)
        val dir = c.variantDir(k, "mdx_9482", "1")
        val totalChunks = (totalFrames + gen - 1) / gen
        for (ci in 0 until totalChunks) {
            val from = ci * gen
            val cnt = minOf(gen, totalFrames - from)
            val buf = ShortArray(cnt * 2)
            for (i in 0 until cnt) {
                val s = sampleOf(from + i)
                buf[i * 2] = s[0]
                buf[i * 2 + 1] = s[1]
            }
            c.writeChunk(dir, ci, buf)
        }
        c.writeManifest(
            dir, "mdx_9482", "1", k.fingerprint, gen, totalChunks,
            complete = true, lastAccess = 1L,
        )
        return c to dir
    }

    /** 从 [start] 读 [frames] 帧，断言与"帧号编码"逐采样相等。 */
    private fun assertReadEquals(c: StemCache, dir: File, start: Int, frames: Int) {
        val got = c.readFrames(dir, start.toLong(), frames)
        assertNotNull("start=$start frames=$frames 应当读得到", got)
        assertEquals(frames * 2, got!!.size)
        for (i in 0 until frames) {
            val s = sampleOf(start + i)
            val f = start + i
            assertEquals("帧 $f 左声道", s[0], got[i * 2])
            assertEquals("帧 $f 右声道", s[1], got[i * 2 + 1])
        }
    }

    @Test fun 往返_块内对齐读() = memo { c, d -> assertReadEquals(c, d, 3, 5) }

    @Test fun 往返_非对齐起点_块内() = memo { c, d -> assertReadEquals(c, d, 1, 6) }

    @Test fun 往返_跨块边界() = memo { c, d -> assertReadEquals(c, d, 6, 6) }

    @Test fun 往返_跨进最后短块() = memo { c, d -> assertReadEquals(c, d, 15, 5) }

    @Test fun 往返_起点落在短块内() = memo { c, d -> assertReadEquals(c, d, 18, 2) }

    @Test fun 往返_整轨全读() = memo { c, d -> assertReadEquals(c, d, 0, totalFrames) }

    private fun memo(block: (StemCache, File) -> Unit) {
        val (c, dir) = seed()
        block(c, dir)
    }

    @Test fun 极值样本原样回来() {
        val c = StemCache(File(tmp.root, "pickup/accompaniment-cache"))
        val k = StemCache.SourceKey("extreme", 44100, 2, gen)
        val dir = c.variantDir(k, "m", "1")
        val buf = shortArrayOf(
            Short.MIN_VALUE, Short.MAX_VALUE, 0, -1, 1, Short.MIN_VALUE, Short.MAX_VALUE, 0,
            -32768, 32767, -2, 2, 5, -5, 100, -100,
        )
        c.writeChunk(dir, 0, buf)
        c.writeManifest(dir, "m", "1", k.fingerprint, gen, 1, complete = true, lastAccess = 1L)
        val got = c.readFrames(dir, 0L, gen)
        assertNotNull(got)
        for (i in buf.indices) assertEquals("样本 $i", buf[i], got!![i])
    }

    @Test fun 越界请求返回null而不是抛异常() {
        val (c, dir) = seed()
        assertNull("起点正好在末尾", c.readFrames(dir, totalFrames.toLong(), 4))
        assertNull("起点远超末尾", c.readFrames(dir, 1000L, 4))
        assertNull("请求跨过末尾（20 帧之后没有数据）", c.readFrames(dir, 18L, 8))
        assertNull("负起点", c.readFrames(dir, -1L, 4))
        assertNull("零帧", c.readFrames(dir, 0L, 0))
    }

    @Test fun 缺块返回null() {
        val (c, dir) = seed()
        File(dir, "c00001.pcm").delete()
        assertNull("需要第 1 块 ⇒ 未命中", c.readFrames(dir, 0L, 12))
    }
}

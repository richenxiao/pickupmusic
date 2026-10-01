package com.shiyin.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写真/封面解码降采样的回归测试。
 *
 * 真机闪退(2026-10-01):搜某歌手的写真时崩在
 * `Canvas: trying to draw too large(201326592bytes) bitmap` —— 即 8192×6144×4。
 * 根因是远端图**按原尺寸解码**后交给 Compose 画,超过 Canvas 单张位图上限(100MB)。
 * 所以这里锁三件事:大图必须降采样、小图不许放大、任何输入都不许超长边/超字节上限。
 */
class BitmapSamplingTest {

    /** 解码后的实际尺寸(按 2 的幂采样)。 */
    private fun decoded(w: Int, h: Int, targetPx: Int): Pair<Int, Int> {
        val s = bitmapSampleSize(w, h, targetPx)
        return (w / s).coerceAtLeast(1) to (h / s).coerceAtLeast(1)
    }

    private fun bytes(w: Int, h: Int, targetPx: Int): Long {
        val (dw, dh) = decoded(w, h, targetPx)
        return dw.toLong() * dh.toLong() * 4
    }

    @Test
    fun `超大扫描件按目标尺寸降采样 - 不再撞 Canvas 上限`() {
        // 真机上崩的那张:8192×6144,原尺寸 = 192MB
        val (w, h) = decoded(8192, 6144, targetPx = 216)
        assertTrue("长边应被压到目标尺寸量级(实际 ${w}x$h)", w <= 2 * 216)
        assertTrue("字节数应远低于 100MB 上限(实际 ${bytes(8192, 6144, 216)})", bytes(8192, 6144, 216) < 100L * 1024 * 1024)
        assertTrue("但也不该小到糊(实际 $w)", w >= 216)
    }

    @Test
    fun `小图不放大 - 采样系数保持 1`() {
        assertEquals(1, bitmapSampleSize(200, 200, targetPx = 216))
        assertEquals(1, bitmapSampleSize(432, 300, targetPx = 216))
    }

    @Test
    fun `即使调用方要 4K 大图也不超过长边上限`() {
        // 大图背景(歌手页 hero)会要很大的 target,仍必须封顶
        val (w, h) = decoded(16384, 12288, targetPx = 4000)
        assertTrue("长边须 ≤ $MAX_DECODE_EDGE(实际 $w)", w <= MAX_DECODE_EDGE)
        assertTrue("字节数须 < 100MB(实际 ${bytes(16384, 12288, 4000)})", bytes(16384, 12288, 4000) < 100L * 1024 * 1024)
        assertTrue("4K 请求仍应拿到够大的图(实际 $w)", w >= 2048)
        assertEquals("长宽比不该被破坏(实际 ${w}x$h)", 16384f / 12288f, w.toFloat() / h, 0.01f)
    }

    @Test
    fun `任意荒唐输入都不越界`() {
        val cases = listOf(
            Triple(30000, 30000, 216),   // 900MP 怪物图
            Triple(100000, 100, 216),    // 极扁
            Triple(100, 100000, 1200),   // 极高
            Triple(8192, 6144, 1200),    // 真机那张 + hero 尺寸
        )
        for ((w, h, t) in cases) {
            val (dw, dh) = decoded(w, h, t)
            assertTrue("$w×$h@$t → ${dw}x$dh 长边越界", maxOf(dw, dh) <= minOf(2 * t, MAX_DECODE_EDGE))
            assertTrue("$w×$h@$t → ${dw}x$dh 字节越界", bytes(w, h, t) < 100L * 1024 * 1024)
        }
    }

    @Test
    fun `非法尺寸返回 1 而不是崩溃或 0`() {
        assertEquals(1, bitmapSampleSize(0, 0, targetPx = 216))
        assertEquals(1, bitmapSampleSize(-5, 100, targetPx = 216))
        assertEquals(1, bitmapSampleSize(100, 100, targetPx = 0))
    }
}

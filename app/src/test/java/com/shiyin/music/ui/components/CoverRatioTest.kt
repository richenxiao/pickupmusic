package com.shiyin.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CoverRatio]：封面显示层比例规则（任务 A，2026-10-24）。 */
class CoverRatioTest {

    // ── ratioOf ──
    @Test
    fun `正常位图返回宽高比`() {
        assertEquals(1f, CoverRatio.ratioOf(1000, 1000)!!, 0.001f)
        assertEquals(2f, CoverRatio.ratioOf(2000, 1000)!!, 0.001f)
        assertEquals(0.5f, CoverRatio.ratioOf(1000, 2000)!!, 0.001f)
    }

    @Test
    fun `无效尺寸返回 null`() {
        assertNull(CoverRatio.ratioOf(0, 100))
        assertNull(CoverRatio.ratioOf(100, 0))
        assertNull(CoverRatio.ratioOf(-1, -1))
    }

    // ── 规则 2：方形槽内 Crop/Fit 判定 ──
    @Test
    fun `近似方形走Crop`() {
        assertTrue(CoverRatio.shouldCropInSquare(1f))
        assertTrue(CoverRatio.shouldCropInSquare(0.95f))
        assertTrue(CoverRatio.shouldCropInSquare(1.08f))
        assertTrue(CoverRatio.shouldCropInSquare(0.9f))
        assertTrue(CoverRatio.shouldCropInSquare(1.1f))
    }

    @Test
    fun `非方形走Fit填充`() {
        assertFalse(CoverRatio.shouldCropInSquare(0.89f))
        assertFalse(CoverRatio.shouldCropInSquare(1.11f))
        assertFalse(CoverRatio.shouldCropInSquare(2f))
        assertFalse(CoverRatio.shouldCropInSquare(0.5f))
    }

    @Test
    fun `null比例按方形处理`() {
        assertTrue(CoverRatio.shouldCropInSquare(null))
    }

    // ── 规则 3：原始比例槽钳制 ──
    @Test
    fun `范围内原样保留`() {
        assertEquals(1f, CoverRatio.slotRatio(1f)!!, 0.001f)
        assertEquals(0.75f, CoverRatio.slotRatio(0.75f)!!, 0.001f)
        assertEquals(1.5f, CoverRatio.slotRatio(1.5f)!!, 0.001f)
        assertEquals(0.5f, CoverRatio.slotRatio(0.5f)!!, 0.001f)
        assertEquals(2f, CoverRatio.slotRatio(2f)!!, 0.001f)
    }

    @Test
    fun `超出钳到边界`() {
        assertEquals(0.5f, CoverRatio.slotRatio(0.33f)!!, 0.001f) // 1:3 极端竖版
        assertEquals(2f, CoverRatio.slotRatio(3f)!!, 0.001f)      // 3:1 极端横版
    }

    @Test
    fun `null返回null`() {
        assertNull(CoverRatio.slotRatio(null))
    }
}

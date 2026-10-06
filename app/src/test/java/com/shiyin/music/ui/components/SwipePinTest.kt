package com.shiyin.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.5.0 右滑固定（规格：docs/internal/v1.4.1-backlog.md §1）。 */
class SwipePinTest {

    private val screen = 1000f
    private val maxReveal = SwipePin.maxRevealPx(screen)   // 450

    @Test
    fun `最大 reveal 是屏宽的 45%`() {
        assertEquals(450f, maxReveal, 1e-3f)
    }

    @Test
    fun `未固定条目向右拖_比例线性增长且封顶 1`() {
        assertEquals(0f, SwipePin.revealFromPinDrag(0f, maxReveal), 1e-3f)
        assertEquals(0.5f, SwipePin.revealFromPinDrag(225f, maxReveal), 1e-3f)
        assertEquals(1f, SwipePin.revealFromPinDrag(450f, maxReveal), 1e-3f)
        // 规格：不允许继续无限向右拖
        assertEquals(1f, SwipePin.revealFromPinDrag(9999f, maxReveal), 1e-3f)
    }

    @Test
    fun `已固定条目向左拖_比例从 1 收回 0`() {
        assertEquals(1f, SwipePin.revealFromUnpinDrag(0f, maxReveal), 1e-3f)
        assertEquals(0.5f, SwipePin.revealFromUnpinDrag(-225f, maxReveal), 1e-3f)
        assertEquals(0f, SwipePin.revealFromUnpinDrag(-450f, maxReveal), 1e-3f)
        assertEquals(0f, SwipePin.revealFromUnpinDrag(-9999f, maxReveal), 1e-3f)
    }

    @Test
    fun `松手阈值_未固定方向`() {
        assertFalse(SwipePin.shouldPin(0.49f))
        assertTrue(SwipePin.shouldPin(0.5f))
        assertTrue(SwipePin.shouldPin(1f))
    }

    @Test
    fun `松手阈值_取消固定方向`() {
        assertFalse(SwipePin.shouldUnpin(0.51f))
        assertTrue(SwipePin.shouldUnpin(0.5f))
        assertTrue(SwipePin.shouldUnpin(0f))
    }

    @Test
    fun `固定后停在最大 reveal_未固定完全收回`() {
        assertEquals(1f, SwipePin.restingReveal(true), 1e-3f)
        assertEquals(0f, SwipePin.restingReveal(false), 1e-3f)
    }

    @Test
    fun `取消固定进度用于灰色插值`() {
        assertEquals(0f, SwipePin.unpinProgress(1f), 1e-3f)
        assertEquals(0.5f, SwipePin.unpinProgress(0.5f), 1e-3f)
        assertEquals(1f, SwipePin.unpinProgress(0f), 1e-3f)
    }

    @Test
    fun `横向意图判定_明显横向才算_纵滑交给列表`() {
        val slop = 20f
        assertTrue(SwipePin.isHorizontalIntent(60f, 5f, slop))
        assertFalse("纵向为主时不能抢手势", SwipePin.isHorizontalIntent(25f, 40f, slop))
        assertFalse("未过 touchSlop 不算", SwipePin.isHorizontalIntent(15f, 0f, slop))
    }

    @Test
    fun `屏宽为 0 时不崩且退化为未固定`() {
        assertEquals(0f, SwipePin.revealFromPinDrag(100f, 0f), 1e-3f)
        assertEquals(1f, SwipePin.revealFromUnpinDrag(-100f, 0f), 1e-3f)
    }
}

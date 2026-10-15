package com.shiyin.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 右滑固定 / 取消固定 —— 纯逻辑测试。
 *
 * 真源：用户 2026-10-15 对官方录屏 `固定.gif` 的逐帧核对规格。这些用例**把规格里的
 * 数字钉住**（操作区宽度范围、阈值 40%），这样以后谁把常量改歪都会在这里失败，
 * 而不是等真机上用手感发现——固定功能已经因为"悄悄改口径"返工过多次。
 */
class SwipePinTest {

    private val w = SwipePin.actionWidthPx(1f)   // density=1 时数值 = dp

    // ── 规格常量本身 ────────────────────────────────────────────────────────

    @Test
    fun `操作区宽度在规格范围内`() {
        // 规格 A.2 / 尺寸一节：ACTION_WIDTH = 88–96dp
        assertTrue("ACTION_WIDTH_DP 应在 88..96，实际 ${SwipePin.ACTION_WIDTH_DP}",
            SwipePin.ACTION_WIDTH_DP in 88f..96f)
    }

    @Test
    fun `提交阈值是操作区宽度的40%`() {
        // 规格：THRESHOLD = 0.4 × ACTION_WIDTH
        assertEquals(0.4f, SwipePin.COMMIT_THRESHOLD, 0.0001f)
        assertFalse(SwipePin.shouldCommit(w * 0.4f - 1f, w))
        assertTrue(SwipePin.shouldCommit(w * 0.4f, w))          // 达到即提交
        assertTrue(SwipePin.shouldCommit(w * 0.6f, w))
    }

    @Test
    fun `图钉尺寸约24dp`() {
        assertTrue(SwipePin.PIN_ICON_DP in 20f..26f)
    }

    // ── 拖动 → 露出宽度 ─────────────────────────────────────────────────────

    @Test
    fun `向右拖_露出宽度跟手`() {
        assertEquals(0f, SwipePin.revealPxFromDrag(0f, w), 0.001f)
        assertEquals(30f, SwipePin.revealPxFromDrag(30f, w), 0.001f)
        assertEquals(w, SwipePin.revealPxFromDrag(w, w), 0.001f)
    }

    @Test
    fun `不允许拖过操作区宽度`() {
        assertEquals(w, SwipePin.revealPxFromDrag(9999f, w), 0.001f)
    }

    @Test
    fun `向左拖_钳到0`() {
        assertEquals(0f, SwipePin.revealPxFromDrag(-100f, w), 0.001f)
    }

    @Test
    fun `操作区宽度为0时不崩且不提交`() {
        assertEquals(0f, SwipePin.revealPxFromDrag(50f, 0f), 0.001f)
        assertFalse(SwipePin.shouldCommit(0f, 0f))
        assertFalse(SwipePin.shouldCommit(50f, -1f))
    }

    // ── 静止位置 ────────────────────────────────────────────────────────────

    @Test
    fun `固定后弹回正常位置_不永久偏移`() {
        assertEquals(0f, SwipePin.restingReveal())
    }

    // ── 手势仲裁（横 vs 竖） ────────────────────────────────────────────────

    @Test
    fun `明显横向才算横向意图`() {
        assertTrue(SwipePin.isHorizontalIntent(50f, 10f, 20f))
    }

    @Test
    fun `纵向位移大_不算横向意图`() {
        assertFalse(SwipePin.isHorizontalIntent(30f, 50f, 20f))
    }

    @Test
    fun `横向位移未过slop_不算横向意图`() {
        assertFalse(SwipePin.isHorizontalIntent(15f, 5f, 20f))
    }
}

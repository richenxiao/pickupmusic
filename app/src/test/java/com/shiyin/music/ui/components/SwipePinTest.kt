package com.shiyin.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 右滑固定 / 取消固定 —— 纯逻辑测试（重做版）。
 *
 * 重做的核心修正：固定/取消固定后条目**弹回正常位置**（restingReveal 恒为 0），
 * 固定状态靠"排序置顶 + 小图标"体现，不靠永久右偏移。
 * 旧版（18f0e7e）让固定条目永久停在 45% 屏宽的 reveal 位 → 用户实测"UI错乱"。
 */
class SwipePinTest {

    // ── 拖动 → reveal 映射 ────────────────────────────────────────────────

    @Test
    fun `向右拖_reveal从0到1`() {
        val maxPx = 400f
        assertEquals(0f, SwipePin.revealFromDrag(0f, maxPx))
        assertEquals(0.5f, SwipePin.revealFromDrag(200f, maxPx), 0.001f)
        assertEquals(1f, SwipePin.revealFromDrag(400f, maxPx), 0.001f)
    }

    @Test
    fun `不允许拖过最大reveal`() {
        val maxPx = 400f
        assertEquals(1f, SwipePin.revealFromDrag(999f, maxPx), 0.001f)
    }

    @Test
    fun `向左拖_reveal钳到0`() {
        val maxPx = 400f
        assertEquals(0f, SwipePin.revealFromDrag(-100f, maxPx), 0.001f)
    }

    // ── 松手提交阈值 ────────────────────────────────────────────────────────

    @Test
    fun `松手过阈值_提交`() {
        assertTrue(SwipePin.shouldCommit(0.5f))
        assertTrue(SwipePin.shouldCommit(0.8f))
        assertTrue(SwipePin.shouldCommit(1f))
    }

    @Test
    fun `松手未过阈值_不提交`() {
        assertFalse(SwipePin.shouldCommit(0f))
        assertFalse(SwipePin.shouldCommit(0.3f))
        assertFalse(SwipePin.shouldCommit(0.49f))
    }

    // ── 静止位置：始终弹回 0（重做的核心修正） ────────────────────────────────

    @Test
    fun `固定后弹回正常位置_不永久偏移`() {
        assertEquals(0f, SwipePin.restingReveal())
    }

    @Test
    fun `取消固定后也弹回正常位置`() {
        assertEquals(0f, SwipePin.restingReveal())
    }

    // ── 颜色插值进度 ────────────────────────────────────────────────────────

    @Test
    fun `进度0_颜色接近暗色`() {
        // reveal=0 时 bg 应为暗色（几乎看不到操作色）
        assertEquals(0f, SwipePin.colorProgress(0f), 0.001f)
    }

    @Test
    fun `进度1_颜色到达最亮`() {
        assertEquals(1f, SwipePin.colorProgress(1f), 0.001f)
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

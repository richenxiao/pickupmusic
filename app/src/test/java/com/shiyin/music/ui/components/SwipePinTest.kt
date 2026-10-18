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

    private val w = SwipePin.actionWidthPx(400f)   // 行宽 400 → 操作区 180

    // ── 规格常量本身 ────────────────────────────────────────────────────────

    @Test
    fun `操作区宽度是行宽的45%`() {
        // 用户 2026-10-16：「拉到页面宽度的一半」→ 再更正为「旧规格的宽度还行」⇒ 行宽 45%
        assertEquals(0.45f, SwipePin.ACTION_WIDTH_FRACTION, 0.0001f)
        assertEquals(180f, SwipePin.actionWidthPx(400f), 0.001f)
        // 行宽未知时不得漏色、也不得提交
        assertEquals(0f, SwipePin.actionWidthPx(0f), 0.001f)
        assertEquals(0f, SwipePin.actionWidthPx(-5f), 0.001f)
        assertFalse(SwipePin.shouldCommit(10f, SwipePin.actionWidthPx(0f)))
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

    // ── 防误触（用户 2026-10-18："固定应该只能左右滑动，现在太容易误触"）──────

    @Test
    fun `锁定位移提到两倍slop`() {
        assertEquals(2.0f, SwipePin.LOCK_SLOP_FACTOR, 0.001f)
        assertFalse("刚过 1 倍 slop 不该锁（旧版就是这里误触）", SwipePin.isHorizontalIntent(25f, 0f, 20f))
        assertTrue("过 2 倍 slop 才锁", SwipePin.isHorizontalIntent(45f, 0f, 20f))
    }

    @Test
    fun `斜划不再算横向意图_按角度口径`() {
        // 用户 2026-10-18：「|dx| ≥ 2.75×|dy|（约 20°）才算横向固定手势」。
        // 1/2.75 = 0.3636 ⇒ atan = 20.0°，所以判据等价于"偏离水平 ≤ 20°"。
        assertEquals(2.75f, SwipePin.LOCK_ASPECT, 0.001f)

        // 以固定半径 r 构造各角度的位移（r 足够大，保证过 slop 闸门）
        val r = 200f
        val slop = 20f
        fun at(deg: Double): Pair<Float, Float> {
            val rad = Math.toRadians(deg)
            return (r * Math.cos(rad)).toFloat() to (r * Math.sin(rad)).toFloat()
        }

        val (dx10, dy10) = at(10.0)
        assertTrue("偏离水平 10° 应当算横滑", SwipePin.isHorizontalIntent(dx10, dy10, slop))

        listOf(20.0, 30.0, 45.0).forEach { deg ->
            val (dx, dy) = at(deg)
            assertFalse(
                "偏离水平 $deg° 不该触发固定（用户实测斜滑误触就是这一类）",
                SwipePin.isHorizontalIntent(dx, dy, slop),
            )
        }
        // 45° 是最典型的误触姿势
        assertFalse(SwipePin.isHorizontalIntent(141f, 141f, slop))
    }
}

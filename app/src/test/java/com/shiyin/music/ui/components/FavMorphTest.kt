package com.shiyin.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v22: 收藏按钮「加号 ⟶ 勾号」变形几何的单测。
 *
 * 这里测的不是"好不好看"（那要上眼看），而是三条**可判定**的性质：
 *  1. 首末帧必须与项目现有图标**逐字重合** —— 否则点一下会先跳一格
 *  2. 两条线的内侧端点必须在终点**汇到同一个折点** —— 这是"加号弯成勾"的关键
 *  3. 插值必须**连续**（采样无跳变）—— 否则动画会闪
 */
class FavMorphTest {

    private val eps = 1e-4f

    private fun assertSeg(s: FavMorph.Seg, x1: Float, y1: Float, x2: Float, y2: Float) {
        assertEquals("x1", x1, s.x1, eps)
        assertEquals("y1", y1, s.y1, eps)
        assertEquals("x2", x2, s.x2, eps)
        assertEquals("y2", y2, s.y2, eps)
    }

    // ── 1. 首帧 = 现有加号（Lucide.CirclePlus 的 M8 12h8 / M12 8v8）──────────
    @Test fun p0_isExactlyThePlusIcon() {
        val segs = FavMorph.segments(0f)
        assertEquals(2, segs.size)
        assertSeg(segs[0], 8f, 12f, 16f, 12f)   // 横线
        assertSeg(segs[1], 12f, 8f, 12f, 16f)   // 竖线
    }

    /** 加号状态两条线交于圆心 (12,12)。 */
    @Test fun p0_segmentsCrossAtCenter() {
        val (h, v) = FavMorph.segments(0f)
        // 横线过 y=12，竖线过 x=12 → 交点 (12,12)
        assertEquals(12f, h.y1, eps)
        assertEquals(12f, h.y2, eps)
        assertEquals(12f, v.x1, eps)
        assertEquals(12f, v.x2, eps)
    }

    // ── 2. 末帧 = 现有勾号（Lucide.CheckSmall 的 M16.5 8.5 10.5 15 7.5 12）──
    @Test fun p1_isExactlyTheCheckIcon() {
        val segs = FavMorph.segments(1f)
        // 左臂：(7.5,12) → (10.5,15)
        assertSeg(segs[0], 7.5f, 12f, FavMorph.VERTEX_X, FavMorph.VERTEX_Y)
        // 右臂：(16.5,8.5) → (10.5,15)
        assertSeg(segs[1], 16.5f, 8.5f, FavMorph.VERTEX_X, FavMorph.VERTEX_Y)
    }

    /** 关键性质：终点处两条线**共享折点**，所以是一个闭合的 ✓ 而不是两条散线。 */
    @Test fun p1_bothSegmentsMeetAtTheVertex() {
        val (a, b) = FavMorph.segments(1f)
        assertEquals(FavMorph.VERTEX_X, a.x2, eps)
        assertEquals(FavMorph.VERTEX_Y, a.y2, eps)
        assertEquals(FavMorph.VERTEX_X, b.x2, eps)
        assertEquals(FavMorph.VERTEX_Y, b.y2, eps)
    }

    /** 左臂在终点处变短、右臂变长 —— 记录这个方向，便于日后对照需求措辞。 */
    @Test fun p1_leftArmShrinks_rightArmGrows() {
        val (h0, v0) = FavMorph.segments(0f)
        val lenH0 = hypot((h0.x2 - h0.x1).toDouble(), (h0.y2 - h0.y1).toDouble())
        val lenV0 = hypot((v0.x2 - v0.x1).toDouble(), (v0.y2 - v0.y1).toDouble())
        assertEquals(8.0, lenH0, 1e-3)   // 加号两笔各长 8
        assertEquals(8.0, lenV0, 1e-3)

        val (h1, v1) = FavMorph.segments(1f)
        val lenH1 = hypot((h1.x2 - h1.x1).toDouble(), (h1.y2 - h1.y1).toDouble())
        val lenV1 = hypot((v1.x2 - v1.x1).toDouble(), (v1.y2 - v1.y1).toDouble())
        assertTrue("横线应变短（$lenH0 → $lenH1）", lenH1 < lenH0)
        assertTrue("竖线应变长（$lenV0 → $lenV1）", lenV1 > lenV0)
    }

    // ── 3. 连续性与夹紧 ────────────────────────────────────────────────────
    @Test fun midpointIsHalfwayForEveryEndpoint() {
        val (h0, v0) = FavMorph.segments(0f)
        val (h1, v1) = FavMorph.segments(1f)
        val (hm, vm) = FavMorph.segments(0.5f)
        for ((a, b, m) in listOf(
            Quad(h0.x1, h1.x1, hm.x1), Quad(h0.y1, h1.y1, hm.y1),
            Quad(h0.x2, h1.x2, hm.x2), Quad(h0.y2, h1.y2, hm.y2),
            Quad(v0.x1, v1.x1, vm.x1), Quad(v0.y1, v1.y1, vm.y1),
            Quad(v0.x2, v1.x2, vm.x2), Quad(v0.y2, v1.y2, vm.y2),
        )) {
            assertEquals((a + b) / 2f, m, eps)
        }
    }

    /** 逐点采样，相邻进度之间任何端点都不许跳变（否则动画会闪）。 */
    @Test fun interpolationIsContinuous() {
        var prev = FavMorph.segments(0f)
        val steps = 200
        for (i in 1..steps) {
            val cur = FavMorph.segments(i / steps.toFloat())
            for (k in 0..1) {
                val dx = abs(cur[k].x1 - prev[k].x1) + abs(cur[k].y1 - prev[k].y1) +
                    abs(cur[k].x2 - prev[k].x2) + abs(cur[k].y2 - prev[k].y2)
                assertTrue("第 $i 步第 $k 条线跳变过大：$dx", dx < 0.2f)
            }
            prev = cur
        }
    }

    /** 进度越界要夹紧，不能外插（否则快速连点时会画出跑到圈外的线）。 */
    @Test fun progressIsClamped() {
        val below = FavMorph.segments(-1f)
        val at0 = FavMorph.segments(0f)
        assertEquals(at0[0].x1, below[0].x1, eps)
        assertEquals(at0[1].y2, below[1].y2, eps)

        val above = FavMorph.segments(3f)
        val at1 = FavMorph.segments(1f)
        assertEquals(at1[0].x2, above[0].x2, eps)
        assertEquals(at1[1].x1, above[1].x1, eps)
    }

    /** 端点始终落在 24×24 viewBox 内（含 2.5 线宽的一半），不会溢出画布。 */
    @Test fun allEndpointsStayInsideViewport() {
        var i = 0
        while (i <= 100) {
            for (s in FavMorph.segments(i / 100f)) {
                for (v in listOf(s.x1, s.x2, s.y1, s.y2)) {
                    assertTrue("坐标越界：$v", v >= 0f && v <= 24f)
                }
            }
            i++
        }
    }

    private data class Quad(val a: Float, val b: Float, val m: Float)
}

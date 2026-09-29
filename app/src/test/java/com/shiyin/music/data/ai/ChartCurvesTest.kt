package com.shiyin.music.data.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.3.10 需求 A19:趋势图过渡动画的插值数学单测。
 *
 * 这层错了不会崩,只会让曲线**画歪**(幅度不对/端点偏移)——肉眼很难判定,
 * 所以逐条钉死。重点:空/单点/长度不一致这些边界,以及"首尾必须对齐"这条性质
 * (否则切换范围时曲线会在左右两端跳)。
 */
class ChartCurvesTest {

    private val eps = 0.0001f

    // ── resampleTo ────────────────────────────────────────────────────────

    @Test
    fun `点数相同等于原样`() {
        val v = listOf(0f, 1f, 2f)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f), resampleTo(v, 3), eps)
    }

    @Test
    fun `升采样线性铺开`() {
        // 2 → 5:等距插值
        assertArrayEquals(
            floatArrayOf(0f, 2.5f, 5f, 7.5f, 10f),
            resampleTo(listOf(0f, 10f), 5),
            eps,
        )
    }

    @Test
    fun `降采样保留首尾与峰值位置`() {
        // 5 → 3:取到 0 / 2 / 4
        assertArrayEquals(
            floatArrayOf(0f, 2f, 4f),
            resampleTo(listOf(0f, 1f, 2f, 3f, 4f), 3),
            eps,
        )
    }

    @Test
    fun `首尾必须对齐`() {
        // 性质:两端点不得因重采样而偏移(否则切换范围时曲线两端会跳)。
        // n=1 不可能"首尾都对齐",单独在下面测。
        for (n in listOf(2, 3, 7, 48, 100)) {
            val v = listOf(3f, 9f, 1f, 7f)
            val out = resampleTo(v, n)
            assertEquals("n=$n 首点", v.first(), out.first(), eps)
            assertEquals("n=$n 末点", v.last(), out.last(), eps)
        }
    }

    @Test
    fun `请求1点取首值且不能是 NaN`() {
        // 曾经的 bug:i/(n-1) 在 n=1 时是 0/0 → NaN,整条线画不出来。
        val out = resampleTo(listOf(3f, 9f, 1f, 7f), 1)
        assertEquals(1, out.size)
        assertEquals(3f, out[0], eps)
        assertEquals("不能是 NaN", false, out[0].isNaN())
    }

    @Test
    fun `空列表返回全零而不是抛异常`() {
        val out = resampleTo(emptyList(), 5)
        assertEquals(5, out.size)
        assertArrayEquals(FloatArray(5), out, eps)
    }

    @Test
    fun `单点拉平成常数线 - 当天只有一个数据点不能画成零`() {
        // 「当天」范围只有 1 个点:必须平铺成常数线,否则图上什么也看不到
        assertArrayEquals(floatArrayOf(7f, 7f, 7f, 7f), resampleTo(listOf(7f), 4), eps)
    }

    @Test
    fun `非法长度返回空数组`() {
        assertEquals(0, resampleTo(listOf(1f), 0).size)
        assertEquals(0, resampleTo(listOf(1f), -3).size)
    }

    @Test
    fun `单点请求长度1`() {
        assertArrayEquals(floatArrayOf(5f), resampleTo(listOf(5f), 1), eps)
    }

    // ── blendCurves ───────────────────────────────────────────────────────

    private fun curves(vararg v: Float): ChartCurves {
        val l = v.toList()
        return ChartCurves(
            resampleTo(l, 4), resampleTo(l, 4), resampleTo(l, 4), resampleTo(l, 4),
        )
    }

    @Test
    fun `t 为 0 或 1 时取端点`() {
        val a = curves(0f, 0f, 0f, 0f)
        val b = curves(10f, 10f, 10f, 10f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), blendCurves(a, b, 0f).inp, eps)
        assertArrayEquals(floatArrayOf(10f, 10f, 10f, 10f), blendCurves(a, b, 1f).inp, eps)
    }

    @Test
    fun `t 为一半时取中点`() {
        val a = curves(0f, 0f, 0f, 0f)
        val b = curves(10f, 10f, 10f, 10f)
        val m = blendCurves(a, b, 0.5f)
        assertArrayEquals(floatArrayOf(5f, 5f, 5f, 5f), m.inp, eps)
        // 四条曲线都要插值(漏一条会看到某条线硬切)
        assertArrayEquals(floatArrayOf(5f, 5f, 5f, 5f), m.out, eps)
        assertArrayEquals(floatArrayOf(5f, 5f, 5f, 5f), m.cache, eps)
        assertArrayEquals(floatArrayOf(5f, 5f, 5f, 5f), m.cost, eps)
    }

    @Test
    fun `长度不一致取较短者而不是崩`() {
        val a = ChartCurves(FloatArray(4), FloatArray(4), FloatArray(4), FloatArray(4))
        val b = ChartCurves(FloatArray(3), FloatArray(3), FloatArray(3), FloatArray(3))
        assertEquals(3, blendCurves(a, b, 0.5f).inp.size)
    }

    @Test
    fun `插值不越界 - 结果始终在两端之间`() {
        val a = curves(1f, 1f, 1f, 1f)
        val b = curves(5f, 5f, 5f, 5f)
        for (t in listOf(0f, 0.1f, 0.37f, 0.5f, 0.9f, 1f)) {
            for (v in blendCurves(a, b, t).inp) {
                assertEquals("t=$t 应在 [1,5] 内", true, v >= 1f - eps && v <= 5f + eps)
            }
        }
    }

    @Test
    fun `来回切换时以当前画面为起点 - 中途再插值仍是连续的`() {
        // 模拟快速连点范围:动画跑到一半又换数据。
        // 可视曲线 = blend(from, to, t);新的起点应取这条可视曲线,而不是旧的 to。
        val from = curves(0f, 0f, 0f, 0f)
        val toOld = curves(10f, 10f, 10f, 10f)
        val mid = blendCurves(from, toOld, 0.5f)          // 正在显示:5
        val toNew = curves(2f, 2f, 2f, 2f)
        // 新动画 t=0 时必须还是 5(不能跳回 0 或 10)
        val atSwitch = blendCurves(mid, toNew, 0f)
        assertArrayEquals(floatArrayOf(5f, 5f, 5f, 5f), atSwitch.inp, eps)
        // 跑完后到新目标
        assertArrayEquals(floatArrayOf(2f, 2f, 2f, 2f), blendCurves(mid, toNew, 1f).inp, eps)
    }
}

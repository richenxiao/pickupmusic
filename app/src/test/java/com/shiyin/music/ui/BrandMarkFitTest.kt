package com.shiyin.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧边栏品牌区**单行**保证的 JVM 单测（用户 2026-10-20 要求）。
 *
 * ### 这个测试能证什么、不能证什么 —— 请连同结论一起看
 * 项目里**没有** Robolectric / `compose-ui-test` 依赖（只有 junit 4 + onnxruntime），
 * 用户明确要求"没有就不要新增依赖"，所以**拿不到真正的 `lineCount`**。
 *
 * 退而求其次，这里断言的是**几何不变式**：只要缩放后的排版宽度 ≤ 可用宽度，
 * 那么 `maxLines = 1` + `softWrap = false` 的 `Text` 就不可能有第二行。
 * 自然宽度用一个**显式的模型**代入（真实宽度由 `rememberTextMeasurer` 在运行期量），
 * 所以本测试验证的是 [brandMarkScale] 的**契约**，不是真机排版结果 —— 真机仍需肉眼验收。
 *
 * 需要 `lineCount` 级别的断言时，得先引入 Robolectric 或 `compose-ui-test`（未引入）。
 */
class BrandMarkFitTest {

    /** 抽屉 195dp、左右各 20dp 内边距 ⇒ 品牌区可用 155dp（用户给的验收尺寸）。 */
    private val drawerDp = 195f
    private val padDp = 20f
    private val availDp = drawerDp - padDp * 2

    /**
     * 两段按原字号排版后的自然宽度（px）的**模型**。
     *
     *  · 「拾音」= 2 个汉字，每字 1em ⇒ 2 × 26sp
     *  · 「PickUpMusic」= 11 个拉丁字符，平均约 0.5em ⇒ 5.5 × 15.5sp
     *  · 间距 2dp
     *
     * 字体度量随 fontScale 线性放大（sp → px 的唯一变量）。
     */
    private fun naturalPx(density: Float, fontScale: Float): Int {
        val cn = 2.0f * BrandMarkFitTest.CN_SP * density * fontScale
        val en = 5.5f * BrandMarkFitTest.EN_SP * density * fontScale
        val gap = 2.0f * density
        return (cn + en + gap).toInt()
    }

    /**
     * 品牌区的可用宽度（px）。
     *
     * ⚠️ **只乘 density，不乘 fontScale** —— 可用宽度是 dp 量出来的布局尺寸，
     * `fontScale` 只影响 sp → px（也就是文字自然宽），不影响容器宽度。
     * 这正是"字变大、抽屉没变宽"时会把品牌挤出去的原因。
     */
    private fun availPx(density: Float): Int = (availDp * density).toInt()

    /** 用户点名的三个 fontScale × 两种密度：缩放后必须装进可用宽度（⇒ 单行）。 */
    @Test
    fun `用户点名的三种 fontScale 下都保证单行`() {
        for (density in listOf(1.0f, 2.75f)) {
            for (fontScale in listOf(0.85f, 1.0f, 1.3f)) {
                val avail = availPx(density)
                val natural = naturalPx(density, fontScale)
                val scale = brandMarkScale(avail, natural)
                assertTrue(
                    "density=$density fontScale=$fontScale: scale=$scale 必须 ∈ (0,1]",
                    scale > 0f && scale <= 1f,
                )
                assertTrue(
                    "density=$density fontScale=$fontScale: 缩放后 ${natural * scale} > 可用 $avail —— 会折行",
                    natural * scale <= avail,
                )
            }
        }
    }

    /** 装得下时**不缩放**（不放大）。 */
    @Test
    fun `宽度富余时保持原尺寸`() {
        assertEquals(1f, brandMarkScale(availPx = 1000, naturalPx = 400), 0f)
    }

    /** 装不下时按同一比例整体缩，26 : 15.5 的字号比例不变。 */
    @Test
    fun `缩放后中英字号比例不变`() {
        val scale = brandMarkScale(availPx = 300, naturalPx = 400)
        assertEquals(0.75f, scale, 1e-6f)
        val ratio = (BrandMarkFitTest.CN_SP * scale) / (BrandMarkFitTest.EN_SP * scale)
        assertEquals(BrandMarkFitTest.CN_SP / BrandMarkFitTest.EN_SP, ratio, 1e-6f)
    }

    /** 用户要求"低于 0.75 也不许换行，继续缩小"—— 所以**不设下限**，更窄也要装得下。 */
    @Test
    fun `极窄抽屉下继续缩小而不折行`() {
        val narrowAvail = (100 * 2.75f).toInt()
        val natural = naturalPx(2.75f, 1f)
        val scale = brandMarkScale(narrowAvail, natural)
        assertTrue("必须缩到 0.75 以下", scale < 0.75f)
        assertTrue(scale > 0f)
        assertTrue(natural * scale <= narrowAvail)
    }

    /** 无约束（不可测宽）时按原尺寸，不因除零炸掉。 */
    @Test
    fun `可用宽度未知时按原尺寸`() {
        assertEquals(1f, brandMarkScale(availPx = 0, naturalPx = 400), 0f)
        assertEquals(1f, brandMarkScale(availPx = -1, naturalPx = 400), 0f)
        assertEquals(1f, brandMarkScale(availPx = 400, naturalPx = 0), 0f)
    }

    private companion object {
        const val CN_SP = 26f
        const val EN_SP = 15.5f
    }
}

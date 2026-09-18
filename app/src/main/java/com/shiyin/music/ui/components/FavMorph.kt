package com.shiyin.music.ui.components

/**
 * v22: 收藏按钮「加号 ⟶ 勾号」连续变形的**几何**（纯逻辑，可单测）。
 *
 * 坐标一律用项目图标自己的 **24×24 viewBox**，与 `Lucide.CirclePlus` / `Lucide.CheckSmall`
 * 的定义逐字对齐 —— 这样动画首帧与末帧和现状**完全重合**，不会出现"点一下先跳一格"。
 *
 * 起点（加号，取自 Lucide.CirclePlus）：
 *   横线 (8,12) → (16,12)
 *   竖线 (12,8) → (12,16)
 * 终点（勾号，取自 Lucide.CheckSmall 的折线 `M16.5 8.5 10.5 15 7.5 12`）：
 *   左边短臂 (7.5,12) → (10.5,15)
 *   右边长臂 (10.5,15) → (16.5,8.5)
 *
 * 映射方式：**横线变左臂、竖线变右臂**，两条线各自插值两个端点，同一个进度 p 驱动。
 * 关键在于两条线的"内侧端点"会**一起汇到勾号的折点 (10.5,15)** ——
 * 于是加号的交叉点自然张开成勾号的折点，视觉上就是"加号自己弯成了勾"，
 * 而不是两条线各走各的。
 *
 * ⚠️ 需求文字把两条臂说成"左边长斜臂 / 右边短臂"，但 Lucide `CheckSmall` 的实际几何
 * 恰好相反（左臂 4.24 长、右臂 8.85 长）。这里**以几何为准**：左右归属与需求一致
 * （横线→左臂、竖线→右臂），长短以真实折线为准，这样动画末帧能精确落在现有勾号上，
 * 不会出现"点完之后图标跟以前不一样"。
 */
object FavMorph {

    /** 一条线段（24×24 viewBox 坐标）。 */
    data class Seg(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    /** 勾号的折点 —— 两条线的内侧端点最终都在这里汇合。 */
    const val VERTEX_X = 10.5f
    const val VERTEX_Y = 15f

    /** 圆半径与线宽（同样按 24 viewBox，与 CirclePlus 的 r=10 / width=2.5 对齐）。 */
    const val CIRCLE_R = 10f
    const val STROKE_W = 2.5f

    /**
     * 按进度 [p] 取两条线的端点。`p = 0` 是标准加号，`p = 1` 是标准勾号，
     * 中间是连续变形（p 会被夹到 0..1）。
     */
    fun segments(p: Float): List<Seg> {
        val t = p.coerceIn(0f, 1f)
        return listOf(
            // 横线 → 勾号左边短臂：左端点几乎不动（8→7.5），右端点向左下扫到折点（16,12 → 10.5,15）
            Seg(lerp(8f, 7.5f, t), lerp(12f, 12f, t), lerp(16f, VERTEX_X, t), lerp(12f, VERTEX_Y, t)),
            // 竖线 → 勾号右边长臂：下端向左上收到折点（12,16 → 10.5,15），上端向右上抬起（12,8 → 16.5,8.5）
            Seg(lerp(12f, 16.5f, t), lerp(8f, 8.5f, t), lerp(12f, VERTEX_X, t), lerp(16f, VERTEX_Y, t)),
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

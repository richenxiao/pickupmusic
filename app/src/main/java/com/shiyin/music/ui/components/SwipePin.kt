package com.shiyin.music.ui.components

/**
 * v1.5.0「右滑固定 / 取消固定」的**纯逻辑**部分（可 JVM 单测）。
 *
 * 规格唯一真源：用户 2026-10-15 对官方录屏 `固定.gif` 的**逐帧核对规格**。
 * **2026-10-15 改口径（相对旧版 docs/internal/v1.4.1-backlog.md §1）**：
 * · 操作区宽度 = **固定 ACTION_WIDTH_DP（88–96dp ≈ 屏宽 22–25%）**，不再是"屏宽 45%"；
 * · 松手提交阈值 = **操作区宽度的 40%**（旧版 50%）；
 * · 操作区是**平色**（未固定绿 #1DB954 / 已固定灰 #535353），**不随拖动渐变** ——
 *   规格明确"行底色固定前后不变、无整行变色"，所以 `colorProgress` 已删除；
 * · 静止位置恒为 0：固定状态靠"排序置顶 + 标题前的小图钉"体现，不靠永久偏移。
 * · 提交判据只看**位置**，不看速度（"快速轻扫未达阈值 → 只回弹，不触发"）。
 */
object SwipePin {

    /** 操作区宽度（dp）。规格 88–96dp，取上限贴近 GIF 满展开的观感。 */
    const val ACTION_WIDTH_DP = 96f

    /** 松手提交阈值：占操作区宽度的比例（规格 0.4）。 */
    const val COMMIT_THRESHOLD = 0.4f

    /** 图钉图标尺寸（dp）。规格 ~24dp。 */
    const val PIN_ICON_DP = 24f

    /** 操作区宽度（px）。 */
    fun actionWidthPx(densityScale: Float): Float = ACTION_WIDTH_DP * densityScale

    /**
     * 向右拖 → 已露出宽度（px），**夹在 [0, actionWidthPx]**。
     * 负的 drag（左滑）钳到 0：规格只定义了右滑。
     */
    fun revealPxFromDrag(dragPx: Float, actionWidthPx: Float): Float =
        dragPx.coerceIn(0f, if (actionWidthPx < 0f) 0f else actionWidthPx)

    /** 松手：是否应提交（过了阈值就提交 pin/unpin）。 */
    fun shouldCommit(revealPx: Float, actionWidthPx: Float): Boolean =
        actionWidthPx > 0f && revealPx >= actionWidthPx * COMMIT_THRESHOLD

    /** 静止时的 reveal：**始终回到 0**。 */
    fun restingReveal(): Float = 0f

    /**
     * 横向手势仲裁：位移是否已明显偏向横向。
     *
     * 规格 A.1："一旦横向位移超过 touchSlop 就锁定横向手势，列表不再上下滚动"——
     * 所以判据是"过 slop **且** 横向分量大于纵向"，二者缺一不可（否则会跟
     * LazyColumn 的纵向滚动抢手势）。
     */
    fun isHorizontalIntent(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
        kotlin.math.abs(dx) > touchSlopPx && kotlin.math.abs(dx) > kotlin.math.abs(dy)
}

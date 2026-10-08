package com.shiyin.music.ui.components

/**
 * v1.5.0「右滑固定 / 取消固定」的**纯逻辑**部分（可 JVM 单测）。**重做版**。
 *
 * 规格唯一真源：`docs/internal/v1.4.1-backlog.md` §1（用户原话）。关键约定：
 * · 最大 reveal = 屏幕宽度 45%（用户截图实测 420/931 ≈ 45%），不允许无限右拖；
 * · 拖多少露多少、颜色随 progress 连续插值（不是两帧切换）；
 * · 松手过阈值即提交，否则 spring 回弹；
 * · **重做修正（用户 2026-10-07 "UI错乱"）**：固定/取消后条目**弹回正常位置**
 *   （restingReveal 恒为 0）。固定状态靠"排序置顶 + 小图标"体现，不靠永久右偏移。
 */
object SwipePin {

    /** 最大 reveal 占屏宽的比例。 */
    const val MAX_REVEAL_FRACTION = 0.45f

    /** 松手提交阈值（相对最大 reveal 的比例）。 */
    const val COMMIT_THRESHOLD = 0.5f

    fun maxRevealPx(screenWidthPx: Float): Float = screenWidthPx * MAX_REVEAL_FRACTION

    /** 向右拖 → reveal 比例 0..1（pin 和 unpin 都是向右拖）。 */
    fun revealFromDrag(dragPx: Float, maxRevealPx: Float): Float =
        if (maxRevealPx <= 0f) 0f else (dragPx / maxRevealPx).coerceIn(0f, 1f)

    /** 松手：是否应提交（过了阈值就提交 pin/unpin）。 */
    fun shouldCommit(reveal: Float): Boolean = reveal >= COMMIT_THRESHOLD

    /** 静止时的 reveal：**始终回到 0**。固定状态由排序置顶+图标体现，不靠永久偏移。 */
    fun restingReveal(): Float = 0f

    /** 颜色插值进度：reveal → 0..1，用于颜色/图标的连续插值。 */
    fun colorProgress(reveal: Float): Float = reveal.coerceIn(0f, 1f)

    /** 横向手势仲裁：位移是否已明显偏向横向（避免与 LazyColumn 纵向滚动抢手势）。 */
    fun isHorizontalIntent(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
        kotlin.math.abs(dx) > touchSlopPx && kotlin.math.abs(dx) > kotlin.math.abs(dy)
}

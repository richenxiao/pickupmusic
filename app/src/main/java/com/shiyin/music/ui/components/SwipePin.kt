package com.shiyin.music.ui.components

/**
 * v1.5.0「右滑固定 / 取消固定」的**纯逻辑**部分（可 JVM 单测）。
 *
 * 规格唯一真源：`docs/internal/v1.4.1-backlog.md` §1（用户原话）。关键约定：
 * · 最大 reveal = 屏幕宽度 45%（用户截图实测 420/931 ≈ 45%），不允许无限右拖；
 * · 拖多少露多少、颜色随 progress 连续插值（不是两帧切换）；
 * · 松手过阈值即提交，否则 spring 回弹；
 * · **用户 2026-10-07 拍板方案 A**：固定后停在最大 reveal（一直露绿底+白图钉），
 *   取消固定是**向左拖回**（原话「再次向右拖」在「停在最大 reveal」下没有余量，已澄清）。
 */
object SwipePin {

    /** 最大 reveal 占屏宽的比例。规格给的可用区间是 0.40~0.45，取截图实测值。 */
    const val MAX_REVEAL_FRACTION = 0.45f

    /** 松手提交阈值（相对最大 reveal 的比例）。 */
    const val COMMIT_THRESHOLD = 0.5f

    fun maxRevealPx(screenWidthPx: Float): Float = screenWidthPx * MAX_REVEAL_FRACTION

    /** 未固定条目：向右拖 → reveal 比例 0..1。 */
    fun revealFromPinDrag(dragPx: Float, maxRevealPx: Float): Float =
        if (maxRevealPx <= 0f) 0f else (dragPx / maxRevealPx).coerceIn(0f, 1f)

    /** 已固定条目：向左拖（dragPx 为负）→ reveal 比例从 1 收回 0。 */
    fun revealFromUnpinDrag(dragPx: Float, maxRevealPx: Float): Float =
        if (maxRevealPx <= 0f) 1f else (1f + dragPx / maxRevealPx).coerceIn(0f, 1f)

    /** 未固定条目松手：是否应提交为「已固定」。 */
    fun shouldPin(reveal: Float): Boolean = reveal >= COMMIT_THRESHOLD

    /** 已固定条目松手：是否应提交为「未固定」（向左收回超过阈值）。 */
    fun shouldUnpin(reveal: Float): Boolean = reveal <= 1f - COMMIT_THRESHOLD

    /** 静止时的 reveal（spring 目标）。固定 = 停在最大 reveal；未固定 = 完全收回。 */
    fun restingReveal(pinned: Boolean): Float = if (pinned) 1f else 0f

    /** 取消固定方向上的进度：0 = 仍停在最大 reveal，1 = 已完全收回。用于灰色插值。 */
    fun unpinProgress(reveal: Float): Float = (1f - reveal).coerceIn(0f, 1f)

    /** 横向手势仲裁：位移是否已明显偏向横向（避免与 LazyColumn 纵向滚动抢手势）。 */
    fun isHorizontalIntent(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
        kotlin.math.abs(dx) > touchSlopPx && kotlin.math.abs(dx) > kotlin.math.abs(dy)
}

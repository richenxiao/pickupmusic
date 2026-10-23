package com.shiyin.music.ui.components

/**
 * v1.5.0「右滑固定 / 取消固定」的**纯逻辑**部分（可 JVM 单测）。
 *
 * 规格唯一真源：定稿 对官方录屏 `固定.gif` 的**逐帧核对规格**。
 * **2026-10-15 改口径（相对旧版 右滑固定定稿规格）**：
 * · 操作区宽度 = **固定 ACTION_WIDTH_DP（88–96dp ≈ 屏宽 22–25%）**，不再是"屏宽 45%"；
 * · 松手提交阈值 = **操作区宽度的 40%**（旧版 50%）；
 * · 操作区是**平色**（未固定绿 #1DB954 / 已固定灰 #535353），**不随拖动渐变** ——
 *   规格明确"行底色固定前后不变、无整行变色"，所以 `colorProgress` 已删除；
 * · 静止位置恒为 0：固定状态靠"排序置顶 + 标题前的小图钉"体现，不靠永久偏移。
 * · 提交判据只看**位置**，不看速度（"快速轻扫未达阈值 → 只回弹，不触发"）。
 */
object SwipePin {

    /**
     * 操作区（最大露出）宽度 = **行宽的 50%**。
     *
     * 定稿更正：原型 GIF 能拉到**页面宽度的一半**；随后又说"旧规格的宽度还行"
     * ⇒ 取**行宽的 45%**（就是旧规格 `MAX_REVEAL_FRACTION = 0.45` 的值）。
     * 改为按**行自身宽度**取一半（比"屏宽一半"更贴实际边距；满铺行的观感与 GIF 一致）。
     */
    const val ACTION_WIDTH_FRACTION = 0.45f

    /**
     * 松手提交阈值：占操作区宽度的比例 —— **0.4**。
     *
     * 定稿 把数值交给我定，要求是"只拉一点点不要触发，防手滑"。
     * 操作区是半屏，0.4 ⇒ 实际要拖到约**屏宽的 20%**（≈80dp，约 1cm 多）才提交：
     * 小抖动/手滑不会误触发，真要固定也不费劲。**这是唯一需要调的旋钮。**
     */
    const val COMMIT_THRESHOLD = 0.4f

    /** 图钉图标尺寸（dp）。规格 ~24dp。 */
    const val PIN_ICON_DP = 24f

    /** 操作区宽度（px）：**行宽的一半**。行宽未知（0 或负）时返回 0（此时不提交、不漏色）。 */
    fun actionWidthPx(rowWidthPx: Float): Float =
        if (rowWidthPx > 0f) rowWidthPx * ACTION_WIDTH_FRACTION else 0f

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
     * 横向锁定前需要的位移 = **touchSlop × 本系数**。
     *
     * 定稿："固定应该只能左右滑动，现在太容易误触了"。
     * 原来只要求"过 1 倍 touchSlop"（≈8dp），手指稍微一斜就锁上了 ⇒ 提到 2 倍。
     */
    const val LOCK_SLOP_FACTOR = 2.0f

    /**
     * 横向分量至少要是纵向分量的**这个倍数**，才算"明显是横滑"。
     *
     * 演进（都在同一天，真机实测推动）：
     *  · 原判据只要求 `|dx| > |dy|` —— 45° 斜划也算，误触主因；
     *  · `1.5` 倍（≈偏离水平 34°）—— 真机实测"**斜着滑还是会触发**"；
     *  · **`2.75` 倍（≈偏离水平 20°）** —— 定稿 明确给的值：
     *    "|dx| ≥ 2.75×|dy|（约 20°）才算横向固定手势"。
     * `1/2.75 = 0.3636` ⇒ `atan(0.3636) = 20.0°` ✓ 与用户口径一致。
     */
    const val LOCK_ASPECT = 2.75f

    /**
     * 横向手势仲裁：位移是否已**明显**偏向横向（定稿 收紧到 ~20°）。
     *
     * 两条缺一不可：
     *  1. `|dx| > touchSlop × [LOCK_SLOP_FACTOR]` —— 位移得够大，不是手指抖动；
     *  2. `|dx| > |dy| × [LOCK_ASPECT]` —— 得**明显**横着划（≥2.75 倍 ≈ 20° 以内）。
     * 不满足就**不消费**；调用方（[SwipeToPin]）会把**整条手势**锁成纵向，之后不再回头。
     */
    fun isHorizontalIntent(dx: Float, dy: Float, touchSlopPx: Float): Boolean {
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        return ax > touchSlopPx * LOCK_SLOP_FACTOR && ax > ay * LOCK_ASPECT
    }
}

/**
 * 手势方向判定日志（**仅 debug 编译**，release 里整段被跳过）。
 *
 * 定稿："加调试日志（仅 debug）记录每次手势的 dx、dy、判定结果，便于校准"。
 * 一次手势**只打一条**（在落锤那一刻），不会刷屏。
 */
internal object SwipePinLog {
    fun decided(dx: Float, dy: Float, slop: Float, horizontal: Boolean) {
        if (!com.shiyin.music.BuildConfig.DEBUG) return
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        val ratio = if (ay > 0.01f) ax / ay else Float.MAX_VALUE
        android.util.Log.d(
            "SwipePin",
            "方向判定 dx=${"%.1f".format(dx)} dy=${"%.1f".format(dy)} slop=${"%.1f".format(slop)} " +
                "|dx|/|dy|=${"%.2f".format(ratio)}（需 ≥ ${SwipePin.LOCK_ASPECT}，且 |dx| 需 > " +
                "${"%.1f".format(slop * SwipePin.LOCK_SLOP_FACTOR)}）" +
                " ⇒ ${if (horizontal) "横向固定" else "纵向滚动（本条手势已锁定纵向）"}",
        )
    }
}

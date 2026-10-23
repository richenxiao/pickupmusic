package com.shiyin.music.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * **按下反馈（不是涟漪）**：按下时轻微缩小 + 压暗，松手弹回。
 *
 * 为什么不用 ripple：歌词页那几个按钮**没有底色**，涟漪会变成"一个孤零零的圆圈"，
 * 已明确否掉（定稿）（`LyricsScreen.kt` 里那几处 `indication = null` 的注释就是那次决定）。
 * 但"完全没有任何反馈"也不对 —— 定稿 要求"点击效果要做好"。
 * 无底色按钮在原生 iOS / Material 里通用的做法就是**缩放 + 透明度**：能感知到"按到了"，
 * 又不会多出一个视觉块。
 *
 * 用法（把原来丢掉的那个 `MutableInteractionSource` 传进来即可）：
 * ```
 * val src = remember { MutableInteractionSource() }
 * Box(
 *     Modifier
 *         .size(38.dp)
 *         .pressFeedback(src)              // ← 放在 clickable 之前或之后都可以（只是图形层）
 *         .clickable(interactionSource = src, indication = null) { ... },
 * )
 * ```
 *
 * @param scale 按下时的缩放（默认 0.90 —— 再小会显得"跳"，再大感觉不到）
 * @param alpha 按下时的透明度（默认 0.65）
 */
@Composable
fun Modifier.pressFeedback(
    interactionSource: InteractionSource,
    scale: Float = PRESS_SCALE,
    alpha: Float = PRESS_ALPHA,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    // 按下要"立刻跟手"（短 tween），回弹要"有弹性"（spring）—— 两个方向用不同曲线，
    // 这是手感的关键：反过来的话按下会发黏。
    val s by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = if (pressed) {
            tween(durationMillis = 70)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "pressScale",
    )
    val a by animateFloatAsState(
        targetValue = if (pressed) alpha else 1f,
        animationSpec = tween(durationMillis = if (pressed) 70 else 110),
        label = "pressAlpha",
    )
    return this.graphicsLayer {
        scaleX = s
        scaleY = s
        this.alpha = a
    }
}

/** 按下缩放与透明度 —— 想整体调手感就改这两个常数。 */
const val PRESS_SCALE = 0.90f
const val PRESS_ALPHA = 0.65f

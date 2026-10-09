package com.shiyin.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.5.0 伴唱按钮的视觉状态（规格 docs/temp/pickupmusic-karaoke-dev-spec.md §3.2）。
 */
enum class KaraokeButtonState { Idle, Loading, Active }

/**
 * **圆角六边形**（设计图定稿：上下两边水平、左右为顶点、四角圆润）。
 *
 * 第三轮修正（用户 2026-10-09：「六边形没做圆角」「大小比例以翻译按钮展开态为准」）：
 * · 圆角用**解析式**：六条直边 + 六个顶点处 quadratic 贝塞尔圆角（不是移动平均平滑——
 *   那会把整条边磨圆，观感是"胖水滴"）。
 * · 形变：顶点圆角半径 r 从 min(w,h)/2（=整圆）连续收敛到设计图的 ~18% 视觉——
 *   Loading 中的圆是 r=半宽 的极限形态，全程平滑。
 * · 大小**不放大**：六边形外接半径 = 圆半径（38dp 方框内切），与「あ」「譯」
 *   （38dp 圆 → 38dp 圆角方块 cornerR=11dp）同一基准，视觉等大。
 */
private class MorphHexagonShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val rFull = minOf(w, h) / 2f          // 圆形半径 = 顶点圆角半径
        val rCornerHex = minOf(w, h) * 0.17f  // 六边形顶点圆角（设计图 ≈ 6.5dp @ 38dp）
        val rCorner = rFull + (rCornerHex - rFull) * progress
        // 六个顶点（角度 0,60,…,300 → 左右顶点、上下平边，与设计图一致）
        val vx = FloatArray(6) { cx + rFull * cos(Math.PI * it / 3.0).toFloat() }
        val vy = FloatArray(6) { cy + rFull * sin(Math.PI * it / 3.0).toFloat() }
        val path = Path()
        for (i in 0 until 6) {
            val prev = (i + 5) % 6
            val next = (i + 1) % 6
            // 边 prev→i 的入切点 = 顶点 i 沿该边方向后退 rCorner
            fun ux(a: Int, b: Int): Float {
                val dx = vx[b] - vx[a]; val dy = vy[b] - vy[a]
                val len = kotlin.math.hypot(dx, dy); return dx / len
            }
            fun uy(a: Int, b: Int): Float {
                val dx = vx[b] - vx[a]; val dy = vy[b] - vy[a]
                val len = kotlin.math.hypot(dx, dy); return dy / len
            }
            val inX = vx[i] - ux(prev, i) * rCorner
            val inY = vy[i] - uy(prev, i) * rCorner
            val outX = vx[i] + ux(i, next) * rCorner
            val outY = vy[i] + uy(i, next) * rCorner
            if (i == 0) path.moveTo(inX, inY) else path.lineTo(inX, inY)
            // 顶点圆角（quadratic：控制点=顶点本身，两端=切点）
            path.quadraticBezierTo(vx[i], vy[i], outX, outY)
            // 直边到下一个顶点的入切点
            val nextInX = vx[next] - ux(i, next) * rCorner
            val nextInY = vy[next] - uy(i, next) * rCorner
            path.lineTo(nextInX, nextInY)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * v1.5.0 歌词页「伴唱」麦克风按钮。
 *
 * 第三轮修正（用户 2026-10-09）：
 * · 颜色随歌词本封面取色（artFg/safeBg），同「あ」「譯」；
 * · 加载弧**照搬「譯」**（headDeg/tailDeg 单调前进 + drawBehind）；
 * · 图标回到设计图那支话筒（胶囊头/环带/握柄/按键/高光），45° 斜置；
 * · 六边形做**真圆角**（顶点贝塞尔），大小与翻译键展开态视觉等大。
 */
@Composable
fun KaraokeMicButton(
    state: KaraokeButtonState,
    lyricForeground: Color,   // = artFg
    lyricBackground: Color,   // = safeBg
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state == KaraokeButtonState.Active
    val isLoading = state == KaraokeButtonState.Loading

    val targetBg = if (state == KaraokeButtonState.Idle) Color.Transparent else lyricForeground
    val bg by animateColorAsState(targetBg, tween(260), label = "karaokeBg")
    val morph by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "karaokeMorph",
    )
    val shape = remember(morph) { MorphHexagonShape(morph) }

    // 加载弧：照搬「譯」按钮——headDeg 匀速 1568ms/圈，tailDeg 只前进不倒退，
    // sweep 10°↔290° ease-in-out 半程往返；drawBehind 里读值（draw 期，不重组）。
    val headDeg = remember { mutableFloatStateOf(0f) }
    val tailDeg = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isLoading) {
        if (!isLoading) return@LaunchedEffect
        headDeg.floatValue = 0f
        tailDeg.floatValue = 0f
        val t0 = withFrameNanos { it }
        var last = t0
        while (true) {
            withFrameNanos { now ->
                val dtMs = (now - last) / 1_000_000f
                last = now
                headDeg.floatValue += dtMs * 360f / 1568f
                val elapsedMs = (now - t0) / 1_000_000f
                val half = (elapsedMs % 2666f) / 1333f
                val u = if (half <= 1f) half else 2f - half
                val eased = FastOutSlowInEasing.transform(u)
                val sweepTarget = 10f + eased * 280f
                val targetTail = headDeg.floatValue - sweepTarget
                if (targetTail > tailDeg.floatValue) tailDeg.floatValue = targetTail
            }
        }
    }

    val iconColor = if (state == KaraokeButtonState.Idle) lyricForeground else lyricBackground

    Box(
        modifier
            .size(38.dp)
            .clip(shape)
            .background(bg)
            .then(
                if (isLoading) Modifier.drawBehind {
                    val sw = 2.dp.toPx()
                    val inset = sw / 2f
                    val arcSize = Size(size.width - sw, size.height - sw)
                    val topLeft = Offset(inset, inset)
                    drawArc(
                        color = lyricBackground.copy(alpha = 0.25f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(sw),
                    )
                    drawArc(
                        color = lyricBackground,
                        startAngle = tailDeg.floatValue,
                        sweepAngle = (headDeg.floatValue - tailDeg.floatValue).coerceIn(0f, 360f),
                        useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(width = sw, cap = StrokeCap.Round),
                    )
                } else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        DesignMicIcon(iconColor, 20.dp, Modifier.rotate(45f))
    }
}

/**
 * 设计图那支手持话筒（斜置 45°）：胶囊头 + 一道环带把头和柄分开 + 渐收握柄 +
 * 柄上椭圆按键 + 头内高光。单 Path + **EvenOdd** 挖孔——环带/按键/高光是真"洞"，
 * 露出按钮底色（未激活态=歌词本底，无突兀填充）。
 * 造型基准 = docs/temp/pickupmusic-karaoke-button-design.png（用户 2026-10-09 重发的样图）。
 */
@Composable
private fun DesignMicIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun px(f: Float) = w * f
        fun py(f: Float) = h * f
        val headL = px(0.30f); val headR = px(0.70f)
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            // 话筒头（胶囊）
            addRoundRect(
                RoundRect(
                    Rect(headL, py(0.04f), headR, py(0.50f)),
                    CornerRadius(px(0.20f), py(0.23f)),
                ),
            )
            // 握柄（梯形渐收 + 底部圆角）
            addRoundRect(
                RoundRect(
                    Rect(px(0.36f), py(0.50f), px(0.64f), py(0.96f)),
                    CornerRadius(px(0.14f), py(0.10f)),
                ),
            )
            // ── 孔（EvenOdd 挖空，露出底色）──
            // 环带：头柄交界处横条
            addRect(Rect(headL, py(0.40f), headR, py(0.47f)))
            // 柄上椭圆按键
            addRoundRect(
                RoundRect(
                    Rect(px(0.44f), py(0.62f), px(0.56f), py(0.74f)),
                    CornerRadius(px(0.06f), py(0.06f)),
                ),
            )
            // 头内高光（设计图头部左上那道浅色弧）
            addOval(Rect(px(0.37f), py(0.12f), px(0.47f), py(0.28f)))
        }
        drawPath(path, color)
    }
}

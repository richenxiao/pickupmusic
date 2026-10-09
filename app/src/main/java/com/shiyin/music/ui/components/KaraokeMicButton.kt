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

/**
 * v1.5.0 伴唱按钮的视觉状态（规格 docs/temp/pickupmusic-karaoke-dev-spec.md §3.2）。
 */
enum class KaraokeButtonState { Idle, Loading, Active }

/**
 * 圆形 → 圆角六边形的形变 Shape（0 = 圆形，1 = 六边形）。
 * **不放大**（circumradius = 1.0，顶点贴方框边）——放大到 1.1547 会让左右顶点超出
 * 38dp 方框被父容器裁切，导致六条边看起来不等长（用户 2026-10-09 实测）。
 */
private class MorphHexagonShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(cx, cy)
        val n = 96
        val path = Path()
        for (i in 0 until n) {
            val a = (i.toFloat() / n) * 2f * Math.PI.toFloat()
            val circleR = 1f
            val hexR = hexUnitRadius(a)
            val rr = (circleR + (hexR - circleR) * progress) * r
            val x = cx + rr * Math.cos(a.toDouble()).toFloat()
            val y = cy + rr * Math.sin(a.toDouble()).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** 单位正六边形（顶点在 0°/60°/…/300°）在角度 [a] 处的边界半径（circumradius = 1.0）。 */
private fun hexUnitRadius(a: Float): Float {
    val apothem = Math.cos(Math.PI / 6.0).toFloat()   // 边心距 = 0.866
    val seg = (Math.PI / 3.0).toFloat()                // 60°
    val m = Math.round((a - seg / 2f) / seg).toFloat() * seg + seg / 2f
    val d = a - m
    return apothem / Math.cos(d.toDouble()).toFloat().coerceAtLeast(0.35f)
}

/**
 * v1.5.0 歌词页「伴唱」麦克风按钮。
 *
 * **第三轮修正（用户 2026-10-09）**：
 * · 颜色**随歌词本封面取色**（artFg/safeBg），同「あ」「譯」——不再用固定白/黑；
 * · 加载弧**照搬「譯」按钮的实现**（headDeg/tailDeg 各自单调前进，drawBehind 画，draw 期读值）；
 * · 图标改成**最简单的圆筒麦克风**（一个竖向胶囊），不再手绘复杂话筒；
 * · 六边形**不放大**（circumradius=1.0，顶点贴方框边），六条边等长；
 * · 图标恒 45° 斜置。
 */
@Composable
fun KaraokeMicButton(
    state: KaraokeButtonState,
    lyricForeground: Color,   // = artFg
    lyricBackground: Color,   // = safeBg（歌词本底色，用于激活态的图标/弧色）
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state == KaraokeButtonState.Active
    val isLoading = state == KaraokeButtonState.Loading

    // 背景色：Idle=透明，Loading/Active=artFg（同「譯」）
    val targetBg = if (state == KaraokeButtonState.Idle) Color.Transparent else lyricForeground
    val bg by animateColorAsState(targetBg, tween(260), label = "karaokeBg")

    // 形变：Loading→Active 时圆形→六边形（同「譯」的圆→方，只是形状不同）
    val morph by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "karaokeMorph",
    )
    val shape = remember(morph) { MorphHexagonShape(morph) }

    // 加载弧：**照搬「譯」按钮**——headDeg 匀速 1568ms/圈，tailDeg 只前进不倒退，
    // sweep 10°↔290° ease-in-out 1333ms 半程往返。drawBehind 里读值（draw 期，不重组）。
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

    // 图标颜色：Idle=artFg，Loading/Active=safeBg（同「譯」的「譯」字颜色）
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
                    // 背景环
                    drawArc(
                        color = lyricBackground.copy(alpha = 0.25f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(sw),
                    )
                    // 进度弧（head−tail，只前进不倒退）
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
        // 最简单的圆筒麦克风：一个竖向胶囊（圆角矩形，圆角=宽度一半）
        SimpleMicIcon(iconColor, 21.dp, Modifier.rotate(45f))
    }
}

/** 最简单的圆筒麦克风图标：一个竖向胶囊。 */
@Composable
private fun SimpleMicIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.30f, h * 0.12f),
            size = Size(w * 0.40f, h * 0.76f),
            cornerRadius = CornerRadius(w * 0.20f, w * 0.20f),
        )
    }
}

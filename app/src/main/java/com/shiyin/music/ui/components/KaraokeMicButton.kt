package com.shiyin.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.shiyin.music.ui.icons.Lucide
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.5.0 伴唱按钮的视觉状态（规格 docs/temp/pickupmusic-karaoke-dev-spec.md §3.2）。
 * 分离引擎尚未接入前只会停在 Idle / Loading；Active 由引擎回写。
 */
enum class KaraokeButtonState { Idle, Loading, Active }

/**
 * 圆形 → 圆角六边形 的**形变** Shape（progress：0 = 圆形，1 = 圆角六边形）。
 *
 * 设计图定稿外形：上下两边水平、左右为顶点的**圆角**六边形
 * （docs/temp/pickupmusic-karaoke-button-design.png）。
 * 做法：按角度同时采样"圆"与"六边形"两条边界再线性插值 —— 这样形变是连续的，
 * 而不是两帧切换。六边形半径用解析式 r(a) = apothem / cos(a − 最近边法线角)，
 * 再对采样半径做一次小窗口平滑得到**圆角**（直边与设计图不符）。
 */
private class MorphHexagonShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(cx, cy)
        val n = 96
        val radii = FloatArray(n)
        for (i in 0 until n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat()
            val circleR = 1f
            val hexR = hexUnitRadius(a) * 0.985f
            radii[i] = circleR + (hexR - circleR) * progress
        }
        // 圆角：对半径序列做小窗口移动平均（窗口随 progress 一起淡入，
        // 圆形时不做平滑——圆本身已经处处光滑）
        val w = 3
        val smoothed = FloatArray(n) { i ->
            if (progress <= 0.01f) radii[i]
            else {
                var s = 0f
                for (k in -w..w) s += radii[((i + k) % n + n) % n]
                s / (2 * w + 1)
            }
        }
        val path = Path()
        for (i in 0 until n) {
            val a = (i.toFloat() / n) * 2f * PI.toFloat()
            val rr = smoothed[i] * r
            val x = cx + rr * cos(a)
            val y = cy + rr * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** 单位正六边形（顶点在 0°/60°/…/300°）在角度 [a] 处的边界半径。 */
private fun hexUnitRadius(a: Float): Float {
    val apothem = cos(PI / 6.0).toFloat()   // 边心距 / 外接半径
    val seg = (PI / 3.0).toFloat()          // 60°
    // 边法线在 30° + k·60°
    val m = (Math.round((a - seg / 2f) / seg).toFloat()) * seg + seg / 2f
    val d = a - m
    return apothem / cos(d).coerceAtLeast(0.35f)
}

/**
 * v1.5.0 歌词页「伴唱」麦克风按钮（先做视觉效果，分离引擎随后接入）。
 *
 * 用户 2026-10-07 修正规格：
 * · 未点击 = **没有背景色**（歌词本默认底色），图标悬浮；
 * · 点击后背景**动画**过渡到与歌词颜色对应的底色（白词→白底、黑词→黑底），节奏照「譯」；
 * · 转圈同「譯」按钮的**圆周加载弧**；
 * · **加载完成后形状演变成圆角六边形**（设计图），用 [MorphHexagonShape] 连续形变；
 * · 麦克风按设计图**斜置约 45°**（话筒头右上、握柄左下）。
 *
 * 尺寸 38dp 与「あ」「譯」一致；**位置由调用方按规则摆放**（见 LyricsScreen：
 * 有「あ」时在「あ」右侧、无「あ」时可放最左），调用方负责在另一侧补等宽空位保持播放键居中。
 */
@Composable
fun KaraokeMicButton(
    state: KaraokeButtonState,
    lyricForeground: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 歌词是白字 → 按钮底色用白；歌词是黑字（浅色主题）→ 用黑。判据沿用歌词前景的亮度。
    val lyricsAreLight = lyricForeground.luminance() > 0.5f
    val solidBg = if (lyricsAreLight) Color.White else Color.Black
    val onSolid = if (lyricsAreLight) Color.Black else Color.White
    val targetBg = if (state == KaraokeButtonState.Idle) Color.Transparent else solidBg
    val bg by animateColorAsState(targetBg, tween(260), label = "karaokeBg")
    val arcColor = if (state == KaraokeButtonState.Idle) lyricForeground else onSolid

    // 加载完成 → 圆形演变成圆角六边形（照「譯」按钮"形状演变"的节奏）
    val morph by animateFloatAsState(
        targetValue = if (state == KaraokeButtonState.Active) 1f else 0f,
        animationSpec = tween(340, easing = FastOutSlowInEasing),
        label = "karaokeMorph",
    )
    val shape = remember(morph) { MorphHexagonShape(morph) }

    // 圆周加载弧：与「譯」按钮同一套观感（匀速旋转 + 弧长伸缩）
    val spin = rememberInfiniteTransition(label = "karaokeSpin")
    val rot by spin.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "rot",
    )
    val sweep by spin.animateFloat(
        20f, 280f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweep",
    )

    Box(
        modifier
            .size(38.dp)
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        OIcon(
            Lucide.Mic, 18.dp,
            if (state == KaraokeButtonState.Idle) lyricForeground else onSolid,
            // 设计图：麦克风斜置约 45°（头右上、柄左下）。随形变一起转，避免"先斜后圆"的割裂。
            modifier = Modifier.rotate(morph * 45f),
        )
        if (state == KaraokeButtonState.Loading) {
            Canvas(Modifier.size(38.dp).rotate(rot)) {
                val inset = 1.5.dp.toPx()
                drawArc(
                    color = arcColor,
                    startAngle = 0f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    style = Stroke(width = 1.8.dp.toPx()),
                )
            }
        }
    }
}

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
            // 六边形按**边心距**对齐圆半径（×1/cos30°≈1.1547）：否则六边形的平边只到
            // 0.866R，成形后**看起来比同行的圆形按钮小一圈**（用户 2026-10-09 指出）。
            // 放大后平边与圆轮廓齐平，左右顶点略微出血（行内有间距，不会压到邻钮）。
            val hexR = hexUnitRadius(a) * 1.1547f
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
 * 设计图那支**手持动圈话筒**（docs/temp/pickupmusic-karaoke-button-design.png）：
 * 胶囊话筒头 + 一道**环带**把头和柄分开 + 渐收的握柄 + 柄上一个椭圆按键 + 头内高光。
 *
 * **不用 Lucide 的立式话筒**（那是"圆头+U 形支架"，与设计图不是同一支）。
 * 用一个 Path + **EvenOdd** 挖孔：环带/按键/高光都是真"洞"，露出按钮底色，
 * 因此在"未激活=无底色"时也不会出现一块突兀的填充色。
 * 画在 [size] 方框里、竖直朝上，斜置由调用方负责。
 */
@Composable
private fun DesignMicIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun px(f: Float) = w * f
        fun py(f: Float) = h * f
        val headLeft = px(0.28f); val headRight = px(0.72f)
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            // 话筒头（胶囊：上圆下圆）
            addRoundRect(
                RoundRect(
                    Rect(headLeft, py(0.05f), headRight, py(0.52f)),
                    CornerRadius(px(0.22f), py(0.23f)),
                ),
            )
            // 握柄（比头窄，底部圆收）
            addRoundRect(
                RoundRect(
                    Rect(px(0.37f), py(0.46f), px(0.63f), py(0.95f)),
                    CornerRadius(px(0.13f), py(0.14f)),
                ),
            )
            // ── 以下为"洞"（EvenOdd 挖空，露出底色）──
            // 环带：只取头宽范围，避免在轮廓外画出实心横条
            addRect(Rect(headLeft, py(0.38f), headRight, py(0.455f)))
            // 柄上的椭圆按键
            addRoundRect(
                RoundRect(
                    Rect(px(0.45f), py(0.60f), px(0.55f), py(0.71f)),
                    CornerRadius(px(0.05f), py(0.055f)),
                ),
            )
            // 头内高光弧（设计图头部左上那道浅色弧）
            addOval(Rect(px(0.37f), py(0.14f), px(0.48f), py(0.30f)))
        }
        drawPath(path, color)
    }
}

/**
 * v1.5.0 歌词页「伴唱」麦克风按钮（先做视觉效果，分离引擎随后接入）。
 *
 * 用户 2026-10-07 修正规格：
 * · 未点击 = **没有背景色**（歌词本默认底色），图标悬浮；
 * · 点击后背景**动画**过渡到与歌词颜色对应的底色（白词→白底、黑词→黑底），节奏照「譯」；
 * · 转圈同「譯」按钮的**圆周加载弧**；
 * · **加载完成后形状演变成圆角六边形**（设计图），用 [MorphHexagonShape] 连续形变；
 * · 图标用 [DesignMicIcon]（设计图那支手持话筒），**恒斜置 45°**（头右上、柄左下）。
 *
 * 尺寸 38dp 与「あ」「譯」一致；**位置由调用方按规则摆放**（见 LyricsScreen）。
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
        DesignMicIcon(
            color = if (state == KaraokeButtonState.Idle) lyricForeground else onSolid,
            size = 21.dp,
            // 设计图：话筒斜置 45°（头右上、柄左下）。恒斜置——形变只发生在按钮形状上。
            modifier = Modifier.rotate(45f),
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

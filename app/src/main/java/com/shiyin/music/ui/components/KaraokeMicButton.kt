package com.shiyin.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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

/**
 * v1.5.0 伴唱按钮的视觉状态（规格 docs/temp/pickupmusic-karaoke-dev-spec.md §3.2）。
 * 分离引擎尚未接入前只会停在 Idle / Loading；Active 由引擎回写。
 */
enum class KaraokeButtonState { Idle, Loading, Active }

/** 设计图定稿的外形：上下两边水平、左右为顶点的圆角六边形（见 sameDir karaoke-button-design.png）。
 *  这里是直边近似（圆角留待与设计图逐像素对齐时再补 arcTo）；颜色不写死，随状态。 */
private val HexagonShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.25f, 0f)
            lineTo(w * 0.75f, 0f)
            lineTo(w, h * 0.5f)
            lineTo(w * 0.75f, h)
            lineTo(w * 0.25f, h)
            lineTo(0f, h * 0.5f)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * v1.5.0 歌词页「伴唱」麦克风按钮（先做视觉效果，分离引擎随后接入）。
 *
 * 用户 2026-10-07 的口述规格：
 * · 未点击 = **没有背景色**（就是歌词本默认底色），图标悬浮；
 * · 点击后背景**动画**过渡到与歌词颜色对应的底色 —— 白词→白底、黑词→黑底，
 *   动画节奏照「譯」翻译按钮；
 * · 转圈动画同「譯」按钮的**圆周加载弧**；
 * · 与翻译按钮的区别：**分离完成后变成六边形**（设计图那个形状）。
 *
 * 尺寸 38dp 与「あ」「譯」一致；调用方负责在另一侧补等宽空位以保持播放键居中。
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

    // 圆周加载弧：与「譯」按钮同一套观感（匀速旋转 + 弧长在 20°↔280° 之间伸缩）
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
            .clip(if (state == KaraokeButtonState.Active) HexagonShape else CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        OIcon(Lucide.Mic, 18.dp, if (state == KaraokeButtonState.Idle) lyricForeground else onSolid)
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

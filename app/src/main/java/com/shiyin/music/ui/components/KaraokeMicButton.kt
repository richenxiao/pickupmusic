package com.shiyin.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * v1.5.0 伴唱按钮的视觉状态（规格 docs/temp/pickupmusic-karaoke-dev-spec.md §3.2）。
 */
enum class KaraokeButtonState { Idle, Loading, Active }



// ── 六边形：**从用户设计图矢量化**得到的极角→半径表（180 档，每 2°）──
// 归一化：形状"半高"= 0.5（即上下平边落在 ±0.5，等价于 38dp 方框的上下边缘），
// 左右顶点 ≈ ±0.547（即外伸约 1.77dp）。
// 用户 2026-10-11 指出设计里的六边形**不是正六边形**（宽高比 1.086 ≠ 1.155），
// 故不再用"正六边形 + 圆角系数"近似，改为直接采用该图的轮廓。
private val HEX_RADII = floatArrayOf(0.5429f,0.5420f,0.5372f,0.5332f,0.5296f,0.5221f,0.5154f,0.5096f,0.5044f,0.5000f,0.4962f,0.4931f,0.4907f,0.4888f,0.4876f,0.4869f,0.4868f,0.4874f,0.4885f,0.4903f,0.4926f,0.4956f,0.4992f,0.5035f,0.5085f,0.5143f,0.5208f,0.5281f,0.5357f,0.5407f,0.5434f,0.5449f,0.5452f,0.5424f,0.5393f,0.5321f,0.5257f,0.5201f,0.5153f,0.5112f,0.5077f,0.5049f,0.5028f,0.5012f,0.5003f,0.5000f,0.5003f,0.5012f,0.5028f,0.5049f,0.5077f,0.5112f,0.5153f,0.5201f,0.5257f,0.5321f,0.5393f,0.5427f,0.5452f,0.5465f,0.5447f,0.5420f,0.5365f,0.5302f,0.5226f,0.5159f,0.5099f,0.5047f,0.5002f,0.4964f,0.4932f,0.4907f,0.4888f,0.4875f,0.4868f,0.4867f,0.4871f,0.4882f,0.4899f,0.4922f,0.4951f,0.4987f,0.5029f,0.5078f,0.5135f,0.5199f,0.5271f,0.5352f,0.5386f,0.5423f,0.5429f,0.5432f,0.5405f,0.5380f,0.5316f,0.5255f,0.5186f,0.5125f,0.5072f,0.5025f,0.4986f,0.4953f,0.4927f,0.4907f,0.4893f,0.4885f,0.4883f,0.4887f,0.4897f,0.4913f,0.4935f,0.4963f,0.4998f,0.5039f,0.5088f,0.5143f,0.5207f,0.5278f,0.5344f,0.5392f,0.5424f,0.5443f,0.5436f,0.5419f,0.5380f,0.5321f,0.5257f,0.5201f,0.5153f,0.5112f,0.5077f,0.5049f,0.5028f,0.5012f,0.5003f,0.5000f,0.5003f,0.5012f,0.5028f,0.5049f,0.5077f,0.5112f,0.5153f,0.5201f,0.5257f,0.5321f,0.5380f,0.5402f,0.5430f,0.5449f,0.5434f,0.5407f,0.5359f,0.5295f,0.5222f,0.5156f,0.5099f,0.5048f,0.5005f,0.4969f,0.4939f,0.4915f,0.4897f,0.4886f,0.4880f,0.4881f,0.4887f,0.4899f,0.4918f,0.4942f,0.4973f,0.5011f,0.5055f,0.5107f,0.5165f,0.5232f,0.5307f,0.5362f,0.5394f,0.5432f)

/**
 * 六边形路径：**形状来自用户设计图**（极角→半径表），Loading 态是正圆。
 *
 * [cornerFrac]：0 = 正圆（半径 = 方框半高，与「あ」的 38dp 圆一致）；1 = 设计图那枚六边形。
 * 中间值按半径线性插值 —— 因为两张形状都是"星形"（对中心可见），半径插值天然连续，
 * 不会像"极角解析式 + 分扇区分支"那样在扇区边界上留下波浪/凹口。
 *
 * 注：设计里的六边形**不是正六边形**（宽高比 1.086，正六边形必然是 1.155），
 * 所以不再用"正六边形 + 圆角系数"去逼近 —— 那些近似怎么做都与原图对不上。
 */
private fun hexagonPath(w: Float, h: Float, cornerFrac: Float): Path {
    val cx = w / 2f
    val cy = h / 2f
    val rc = minOf(w, h) / 2f              // 圆半径（= 方框半高）
    val n = HEX_RADII.size
    val p = Path()
    for (i in 0 until n) {
        val th = 2.0 * Math.PI * i / n
        val rHex = HEX_RADII[i] * 2f * rc   // 表里"半高=0.5" → 换算成 px
        val r = rc + (rHex - rc) * cornerFrac
        val x = cx + r * cos(th).toFloat()
        val y = cy + r * sin(th).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/**
 * v1.5.0 歌词页「伴唱」麦克风按钮。
 *
 * · 颜色随歌词本封面取色（artFg/safeBg），同「あ」「譯」；
 * · 加载弧**照搬「譯」**（headDeg/tailDeg 单调前进 + drawBehind）；
 * · 图标照样图手绘（大圆头 / 白环 / 锥形柄 / 椭圆按键 / 底部插头），45° 斜置；
 * · Loading = 正圆（与「あ」一致）→ 加载完形变成设计图那枚六边形（形状取自该图像素轮廓）。
 */
@Composable
fun KaraokeMicButton(
    state: KaraokeButtonState,
    lyricForeground: Color,   // = artFg
    lyricBackground: Color,   // = safeBg
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 当前曲目的格式不被支持时置灰（块 4 的格式闸门）。
     * ⚠️ **仍然可点** —— 点了会给出原因（"暂只支持 FLAC 与 MP3"），
     * 而不是变成一个"按了没反应"的坏按钮。
     */
    dimmed: Boolean = false,
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

    // 加载弧：照搬「譯」按钮——headDeg 匀速 1568ms/圈，tailDeg 只前进不倒退。
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

    val micSrc = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(38.dp)
            .graphicsLayer { alpha = if (dimmed) 0.35f else 1f }
            .pressFeedback(micSrc)
            .clickable(
                interactionSource = micSrc,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 形状底层：用 Canvas 画（**不加 clip**）——六边形顶点会伸出 38dp 方框约 1.9dp，
        // 加 clip(shape) 会把顶点裁掉（那正是上一轮"六条边不等长"的原因）。
        Canvas(Modifier.matchParentSize()) {
            drawPath(hexagonPath(size.width, size.height, morph), bg)
        }
        if (isLoading) {
            // 圆周加载弧（38dp 正圆上，与「譯」同款；draw 期读值不重组）
            Canvas(Modifier.matchParentSize()) {
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
            }
        }
        // 话筒图标：**从用户提供的样图矢量化得到**（边界跟踪 + Douglas-Peucker 简化），
        // 形状与样图逐像素一致；斜置 45° 已经烘进坐标，不要再 rotate。
        Canvas(Modifier.size(MIC_ICON_DP)) {
            drawMic(size.width, size.height, iconColor, bg)
        }
    }
}

// 图标尺寸：与「あ」「譯」的字形（18sp ≈ 18dp）同量级——用户 2026-10-11「比例太大，
// 参考片假名和译文的字号，缩小一点」。话筒是斜置图形，其对角线跨度即此值。
private val MIC_ICON_DP = 19.dp

// ── 以下四组坐标由样图矢量化得到（归一化到 0..1，已含 45° 斜置）──────────────
// 来源：用户 2026-10-10 提供的话筒样图；工具：Moore 边界跟踪 + Douglas-Peucker(ε≈1.2px)。
// 三个 outer = 头 / 柄 / 底部插头（样图里它们是**分离的三块**，之间的白环是开口的，
// 不是洞）；一个 hole = 柄上的椭圆按键。

/** 话筒头（大圆，23 点）。 */
private val MIC_HEAD = listOf(
    0.7185f to 0.0000f, 0.8111f to 0.0074f, 0.8741f to 0.0333f, 0.9111f to 0.0593f,
    0.9407f to 0.0889f, 0.9778f to 0.1481f, 1.0000f to 0.2407f, 0.9889f to 0.3296f,
    0.9593f to 0.3926f, 0.9333f to 0.4259f, 0.8593f to 0.4815f, 0.8111f to 0.5000f,
    0.7667f to 0.5074f, 0.7407f to 0.5074f, 0.6444f to 0.4444f, 0.5630f to 0.3593f,
    0.5222f to 0.2963f, 0.4926f to 0.2333f, 0.5074f to 0.1630f, 0.5481f to 0.0926f,
    0.6111f to 0.0370f, 0.6556f to 0.0148f, 0.7185f to 0.0000f,
)

/** 握柄（锥形，16 点）。 */
private val MIC_BODY = listOf(
    0.4444f to 0.2926f, 0.4778f to 0.3556f, 0.5296f to 0.4259f, 0.5963f to 0.4926f,
    0.6963f to 0.5630f, 0.5407f to 0.6963f, 0.3111f to 0.8741f, 0.2741f to 0.8963f,
    0.2556f to 0.9000f, 0.2074f to 0.8926f, 0.1630f to 0.8593f, 0.1074f to 0.7926f,
    0.1000f to 0.7704f, 0.1037f to 0.7222f, 0.2481f to 0.5259f, 0.4444f to 0.2926f,
)

/** 底部插头（10 点）。 */
private val MIC_PLUG = listOf(
    0.0630f to 0.8000f, 0.1111f to 0.8704f, 0.1556f to 0.9111f, 0.2000f to 0.9370f,
    0.1111f to 1.0000f, 0.0519f to 1.0000f, 0.0074f to 0.9630f, 0.0000f to 0.9444f,
    0.0000f to 0.8852f, 0.0630f to 0.8000f,
)

/** 柄上的椭圆按键（洞，挖出按钮底色，13 点）。 */
private val MIC_BUTTON = listOf(
    0.4333f to 0.5111f, 0.4630f to 0.5111f, 0.4852f to 0.5296f, 0.4889f to 0.5630f,
    0.4815f to 0.5778f, 0.3926f to 0.6667f, 0.3741f to 0.6741f, 0.3481f to 0.6704f,
    0.3259f to 0.6444f, 0.3259f to 0.6185f, 0.3333f to 0.6037f, 0.4185f to 0.5185f,
    0.4333f to 0.5111f,
)

/** 话筒：三块实心 + 一个挖空。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMic(
    w: Float,
    h: Float,
    icon: Color,
    bg: Color,
) {
    val s = minOf(w, h)
    fun poly(list: List<Pair<Float, Float>>): Path {
        val p = Path()
        list.forEachIndexed { i, (x, y) ->
            if (i == 0) p.moveTo(x * s, y * s) else p.lineTo(x * s, y * s)
        }
        p.close()
        return p
    }
    drawPath(poly(MIC_HEAD), icon)
    drawPath(poly(MIC_BODY), icon)
    drawPath(poly(MIC_PLUG), icon)
    drawPath(poly(MIC_BUTTON), bg)
}

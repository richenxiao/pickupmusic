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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * v1.5.0 伴唱按钮的视觉状态（规格 docs/temp/pickupmusic-karaoke-dev-spec.md §3.2）。
 */
enum class KaraokeButtonState { Idle, Loading, Active }

/** 六边形外接半径 / 圆半径 的比值（1/cos30°）——让六边形的**平边**与圆边缘齐平。 */
private const val HEX_RATIO = 1.1547f

/** 顶点圆角半径（占外接半径比例）。用户要「偏硬朗、贴近样图」→ 取 10%。
 *  取到 0.866 时六边形退化成整圆（Loading 态用）。 */
private const val CORNER_ROUND = 0.10f

/**
 * **圆角六边形**路径（精确圆弧，不是半径采样）。
 *
 * 第五轮重做（用户 2026-10-10：「形状还是不对」+ 我自查）：上一版用"极角→半径"采样，
 * 在 60° 扇区**边界**上圆弧/直边两个分支判定不一致 → 边上出现波浪、顶部一个凹口。
 * 现在改为**按段构造**：6 条直边 + 6 段三次贝塞尔圆弧，段与段**在两个切点处天然相切**
 *（切点距离 d = 圆角/√3，贝塞尔控制点偏移 kb = 0.3573·圆角）→ 数学上不可能有接缝。
 *
 * 尺寸（回应用户"三个按钮一样大"）：外接半径 R = 圆半径 × 1.1547 保持**固定**，
 * 只让顶点圆角从 0.866·R（此时六个圆角弧恰好拼成半径=R/1.1547 的**整圆**，
 * 与「あ」的 38dp 圆完全一致）连续收敛到 0.10·R（硬朗六边形）。
 * 结果：Loading = 正圆；Active = 平边与圆边缘齐平的圆角六边形（顶点向外多出约 1.9dp）。
 */
private fun hexagonPath(w: Float, h: Float, cornerFrac: Float): Path {
    val cx = w / 2f
    val cy = h / 2f
    val circleR = minOf(w, h) / 2f
    val r = circleR * HEX_RATIO                 // 六边形外接半径（固定）
    val fillet = r * (0.866f + (CORNER_ROUND - 0.866f) * cornerFrac)
    val n = 6
    val vx = FloatArray(n)
    val vy = FloatArray(n)
    for (i in 0 until n) {
        val a = Math.PI * i / 3.0
        vx[i] = cx + r * cos(a).toFloat()
        vy[i] = cy + r * sin(a).toFloat()
    }
    val d = (fillet / 1.7320508f).coerceAtMost(r / 2f)   // 切点沿边距顶点
    val kb = 0.3573f * fillet                             // 三次贝塞尔控制点偏移
    fun ux(a: Int, b: Int): Float = (vx[b] - vx[a]) / hypot(vx[b] - vx[a], vy[b] - vy[a])
    fun uy(a: Int, b: Int): Float = (vy[b] - vy[a]) / hypot(vx[b] - vx[a], vy[b] - vy[a])
    val p = Path()
    for (i in 0 until n) {
        val prev = (i + 5) % n
        val next = (i + 1) % n
        val inX = vx[i] - ux(prev, i) * d
        val inY = vy[i] - uy(prev, i) * d
        val outX = vx[i] + ux(i, next) * d
        val outY = vy[i] + uy(i, next) * d
        if (i == 0) p.moveTo(inX, inY) else p.lineTo(inX, inY)
        p.cubicTo(
            inX + ux(prev, i) * kb, inY + uy(prev, i) * kb,
            outX - ux(i, next) * kb, outY - uy(i, next) * kb,
            outX, outY,
        )
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
 * · Loading = 正圆（与「あ」一致）→ 加载完形变成圆角六边形。
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

    Box(
        modifier
            .size(38.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
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

private val MIC_ICON_DP = 23.dp

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

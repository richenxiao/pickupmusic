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
 * 第四轮修正（用户 2026-10-10：「现在这个没那么圆润了，边边角角还需要修复」）：
 * 上一版用 quadratic 贝塞尔、控制点落在**顶点本身** → 曲线贴近尖角，看着还是尖的。
 * 现在改成**精确圆角**：六个顶点各用一个与两条邻边相切的**圆弧**（射线与圆求交解析求半径），
 * 并把整条边界表达成"极角 → 半径"的连续函数，再在**半径域**从圆形（常数半径 R）
 * 平滑插值到圆角六边形 —— 全程半径 ≤ R，既不会被 38dp 方框裁掉，也没有任何突变。
 */
private class MorphHexagonShape(private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) / 2f
        val path = Path()
        if (progress <= 0.002f) {
            // 纯圆（Loading 态与「譯」按钮的圆完全一致）
            path.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
            return Outline.Generic(path)
        }
        val steps = 192
        for (i in 0 until steps) {
            val a = (i.toFloat() / steps) * 2f * Math.PI.toFloat()
            val hexR = roundedHexRadius(a, r) * r
            val rr = r + (hexR - r) * progress
            val x = cx + rr * cos(a)
            val y = cy + rr * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** 单位圆角六边形（外接半径 1）在极角 [a] 处的半径。 */
private fun roundedHexRadius(a: Float, r: Float): Float {
    val sector = (Math.PI / 3).toFloat()          // 60°
    val apothem = 0.8660254f                      // cos30°（单位外接半径的边心距）
    val fillet = 0.22f                            // 顶点圆角半径（占外接半径比例；越大越圆润）
    val k = Math.round(a / sector).toFloat()
    val theta = k * sector
    var dlt = a - theta
    while (dlt > Math.PI.toFloat()) dlt -= 2f * Math.PI.toFloat()
    while (dlt < -Math.PI.toFloat()) dlt += 2f * Math.PI.toFloat()
    fun angleOf(x: Float, y: Float): Float = kotlin.math.atan2(y, x)
    // 顶点及其两邻点（单位外接半径）
    val vx = cos(theta); val vy = sin(theta)
    val px = cos(theta - sector); val py = sin(theta - sector)
    val nx = cos(theta + sector); val ny = sin(theta + sector)
    fun unit(ax: Float, ay: Float, bx: Float, by: Float): Pair<Float, Float> {
        val dx = bx - ax; val dy = by - ay
        val l = kotlin.math.hypot(dx, dy)
        return dx / l to dy / l
    }
    val (upx, upy) = unit(px, py, vx, vy)         // 入边方向
    val (unx, uny) = unit(vx, vy, nx, ny)         // 出边方向
    val d = fillet / 1.7320508f                   // 切点沿边距顶点（tan60°）
    // 切点极角（相对 theta 的偏移）
    val p1x = vx - upx * d; val p1y = vy - upy * d
    val p2x = vx + unx * d; val p2y = vy + uny * d
    var dl1 = angleOf(p1x, p1y) - theta
    var dl2 = angleOf(p2x, p2y) - theta
    if (dl1 > 0) dl1 -= 2f * Math.PI.toFloat()
    if (dl2 < 0) dl2 += 2f * Math.PI.toFloat()
    return if (dlt in dl1..dl2) {
        // 圆角段：射线（极角 a）与圆角圆求交，取外侧根
        val cDist = 1f - fillet / 0.8660254f      // 圆角圆心到原点距离
        val ccx = cDist * cos(theta); val ccy = cDist * sin(theta)
        val ux = cos(a); val uy = sin(a)
        val b = ux * ccx + uy * ccy
        val disc = b * b - (ccx * ccx + ccy * ccy) + fillet * fillet
        if (disc <= 0f) apothem / cos(dlt).coerceAtLeast(0.3f)
        else b + kotlin.math.sqrt(disc)
    } else {
        // 直边段：最近边法线在 theta ± 30°
        val phi = theta + (if (dlt > 0f) sector / 2f else -sector / 2f)
        apothem / cos(a - phi).coerceAtLeast(0.3f)
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
        // 头部比例调自然（用户 2026-10-10）：头占高 ~45%、宽占 ~52%（旧版头太窄显得
        // 头小身长）；柄从头部宽度渐收到 ~60%。
        val headL = px(0.24f); val headR = px(0.76f)
        val headTop = py(0.02f); val headBot = py(0.46f)
        val headW = headR - headL
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            // 话筒头（胶囊：圆角 = 宽的一半 → 上下端都是半圆，更圆润）
            addRoundRect(
                RoundRect(
                    Rect(headL, headTop, headR, headBot),
                    CornerRadius(headW / 2f, headW / 2f),
                ),
            )
            // 握柄（从头宽 0.64 渐收到 0.42，底部圆角）—— 用 Path 直边 + quadratic 底部圆角
            val gripTopL = px(0.34f); val gripTopR = px(0.66f)
            val gripBotL = px(0.40f); val gripBotR = px(0.60f)
            val gripTop = py(0.48f); val gripBot = py(0.97f)
            val grip = Path().apply {
                moveTo(gripTopL, gripTop)
                lineTo(gripTopR, gripTop)
                lineTo(gripBotR, gripBot - py(0.03f))
                quadraticBezierTo(gripBotR, gripBot, px(0.50f), gripBot)
                quadraticBezierTo(gripBotL, gripBot, gripBotL, gripBot - py(0.03f))
                lineTo(gripTopL, gripTop)
                close()
            }
            addPath(grip)
            // ── 孔（EvenOdd 挖空，露出底色）──
            // 环带：头柄交界处横条
            addRect(Rect(headL - px(0.02f), py(0.36f), headR + px(0.02f), py(0.45f)))
            // 柄上椭圆按键
            addOval(Rect(px(0.435f), py(0.60f), px(0.565f), py(0.75f)))
            // 头内高光（左上竖向浅色带，胶囊头上的反光）
            addOval(Rect(px(0.33f), py(0.10f), px(0.43f), py(0.26f)))
        }
        drawPath(path, color)
    }
}

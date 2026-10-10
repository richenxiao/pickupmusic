package com.shiyin.music.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shiyin.music.ui.theme.LocalOrganic

// 规格（v1.4.1-backlog §1）点名要「绿色」与「灰色」两套插值，因此这里是固定色，不随主题取。
private val PinGreenDim = Color(0xFF16301F)
private val PinGreenBright = Color(0xFF35C46A)
private val UnpinGrayDim = Color(0xFF2E2E2E)
private val UnpinGrayBright = Color(0xFF9A9A9A)

/** 拖动锚点：Rest=收起位(0)，Trigger=触发位(45%屏宽)。 */
private enum class PinAnchor { Rest, Trigger }

/**
 * v1.5.0 右滑固定 / 取消固定 —— 可复用的列表条目包装。
 *
 * **第五轮（用户 2026-10-09：拉长绿条有时卡住不回弹 / 图钉缺针刺）**：
 * 1. **卡住不回弹**：上一版监听 `settledValue` 后手动 `animateTo(Rest)` 弹回——一旦
 *    条目因固定而重排（animateItem）打断那次动画，绿条就永久停在 Trigger。现改用官方
 *    的 `confirmValueChange`：**直接拒绝**停留在 Trigger（返回 false），由 AnchoredDraggable
 *    自己弹回当前锚点，没有任何手动动画可供打断。
 * 2. **图钉加针刺**：Lucide 的 PinFilled 没有针，改为自绘 [PinGlyph]（圆帽 + 收窄 + 尖端针刺）。
 */
@Composable
fun SwipeToPin(
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    /** 永久固定的条目（如「我的喜欢」）传 false：不可滑动，也不显示固定标识。 */
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val c = LocalOrganic.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val maxRevealPx = remember(screenWidthPx) { SwipePin.maxRevealPx(screenWidthPx) }
    val pinSizePx = with(density) { 20.dp.toPx() }
    val curPinned by rememberUpdatedState(pinned)
    val curOnPinnedChange by rememberUpdatedState(onPinnedChange)

    val dragState = remember(maxRevealPx) {
        AnchoredDraggableState(
            initialValue = PinAnchor.Rest,
            anchors = DraggableAnchors {
                PinAnchor.Rest at 0f
                PinAnchor.Trigger at maxRevealPx
            },
            positionalThreshold = { total -> total * SwipePin.COMMIT_THRESHOLD },
            velocityThreshold = { with(density) { 125.dp.toPx() } },
            snapAnimationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            decayAnimationSpec = exponentialDecay(),
            // 拖过阈值要"提交"但**不允许停在 Trigger**：提交后返回 false，
            // 官方状态机自己把绿条弹回 Rest（不再手动 animateTo → 不会被重排打断而卡住）。
            confirmValueChange = { target ->
                if (target == PinAnchor.Trigger) {
                    curOnPinnedChange(!curPinned)
                    false
                } else true
            },
        )
    }

    Box(modifier) {
        // ── 底层：操作区（绿/灰条 + 图钉）。draw/layer phase 读 offset，不重组 content。──
        Box(Modifier.matchParentSize().clip(shape)) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    val rp = dragState.requireOffset()
                    if (rp <= 0.5f) return@drawBehind
                    val p = (rp / maxRevealPx).coerceIn(0f, 1f)
                    val bg = if (!curPinned) lerp(PinGreenDim, PinGreenBright, p)
                    else lerp(UnpinGrayDim, UnpinGrayBright, p)
                    drawRect(bg, topLeft = Offset.Zero, size = Size(rp, size.height))
                },
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val rp = dragState.requireOffset()
                        translationX = (rp - pinSizePx) / 2f
                        alpha = if (rp > 0.5f) 1f else 0f
                    },
                contentAlignment = Alignment.Center,
            ) { PinIcon(curPinned, dragState, maxRevealPx) }
        }
        // ── 前景：内容块。★ background 在 graphicsLayer **之后**（layer 内），平移后
        //    左侧露出绿条；若放在前面，底色不跟平移、永远盖住绿条。
        Box(
            Modifier
                .then(if (enabled) Modifier.anchoredDraggable(dragState, Orientation.Horizontal) else Modifier)
                .graphicsLayer { translationX = dragState.requireOffset() }
                .background(c.bg),
        ) { content() }
        // 固定标识角标：排序置顶 + 小图钉，让用户看出哪些已固定。
        if (enabled && pinned) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .graphicsLayer { alpha = if (dragState.requireOffset() < 0.5f) 1f else 0f }
            ) {
                Box(
                    Modifier.size(15.dp).clip(CircleShape).background(PinGreenBright),
                    contentAlignment = Alignment.Center,
                ) { PinGlyph(Color.White, 9.dp) }
            }
        }
    }
}

/** 图钉图标：单独读拖动进度 → 只这个小组件重组，不影响 content()。 */
@Composable
private fun PinIcon(
    pinned: Boolean,
    dragState: AnchoredDraggableState<PinAnchor>,
    maxRevealPx: Float,
) {
    val p = (dragState.requireOffset() / maxRevealPx).coerceIn(0f, 1f)
    val iconTint = if (!pinned) lerp(PinGreenBright.copy(alpha = 0.35f), Color.White, p)
    else lerp(UnpinGrayBright, Color.White, p)
    PinGlyph(iconTint, 20.dp)
}

/**
 * **带针刺的图钉**（用户 2026-10-09：「图钉按钮没有针刺」）。
 * Lucide 的 PinFilled 只有圆头没有针，这里自绘：圆帽 + 收窄的颈部 + 尖端针刺。
 */
@Composable
private fun PinGlyph(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // 圆帽
        drawCircle(color = color, radius = w * 0.27f, center = Offset(w * 0.5f, h * 0.30f))
        // 颈部（梯形收窄）
        val neck = Path().apply {
            moveTo(w * 0.36f, h * 0.42f)
            lineTo(w * 0.64f, h * 0.42f)
            lineTo(w * 0.57f, h * 0.70f)
            lineTo(w * 0.43f, h * 0.70f)
            close()
        }
        drawPath(neck, color)
        // 针刺（尖三角）
        val needle = Path().apply {
            moveTo(w * 0.43f, h * 0.66f)
            lineTo(w * 0.57f, h * 0.66f)
            lineTo(w * 0.50f, h * 1.0f)
            close()
        }
        drawPath(needle, color)
    }
}

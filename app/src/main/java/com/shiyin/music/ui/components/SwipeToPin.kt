package com.shiyin.music.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic
import kotlinx.coroutines.launch

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
 * **第四轮（用户 2026-10-09 二次实测：拖动仍剧烈抖动 + 绿色不见）**：
 * 根因两个——
 * 1. 绿色不见：上一版 `.background(c.bg).graphicsLayer{...}` 顺序反了——background 画在
 *    **layer 外面**不跟着平移，永远盖住底下的绿条。background 必须在 graphicsLayer **之后**
 *    （= layer 内部），平移时左侧才露出绿条。
 * 2. 仍然抖动：手写 awaitEachGesture 仲裁与 LazyColumn 的 scrollable 抢事件。
 *    弃用自写手势，改用 **Compose 官方 [AnchoredDraggableState]**（Material3
 *    SwipeToDismissBox 同款基础设施）——与纵向列表的手势互斥是官方处理过的，
 *    松手 settle/threshold/动画全部内置。
 *
 * 行为：向右拖过半程松手 → settle 到 Trigger → 回调 toggle → 弹回 Rest；
 * 不足半程 → settle 回 Rest（绿条收回）。固定/取消后条目由列表 animateItem 平滑重排。
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
    val scope = rememberCoroutineScope()
    val pinSizePx = with(density) { 20.dp.toPx() }
    val curPinned by rememberUpdatedState(pinned)
    val curOnPinnedChange by rememberUpdatedState(onPinnedChange)

    // 官方拖动状态：两锚点 0 ↔ 45%屏宽；过半程(positionalThreshold)松手 settle 到 Trigger。
    // 本版 Compose 的 AnchoredDraggableState 构造：anchors 走构造参数（此签名），
    // snap 用 spring，decay 用 exponentialDecay（anchoredDraggable 水平拖动不 decay 衰减，
    // settle 全走 snapAnimationSpec）。
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
            decayAnimationSpec = androidx.compose.animation.core.exponentialDecay(),
        )
    }
    // settle 到 Trigger（拖过阈值松手）→ 提交 toggle → 弹回 Rest
    LaunchedEffect(dragState) {
        snapshotFlow { dragState.settledValue }.collect {
            if (it == PinAnchor.Trigger) {
                curOnPinnedChange(!curPinned)
                scope.launch { dragState.animateTo(PinAnchor.Rest) }
            }
        }
    }

    Box(modifier) {
        // ── 底层：操作区（绿/灰条 + 图钉）。全部 draw/layer phase 读 offset，不重组 content。──
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
        //    左侧露出绿条；若放在前面，底色不跟平移、永远盖住绿条（上轮"绿色不见了"的根因）。
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
                ) { OIcon(Lucide.PinFilled, 9.dp, Color.White) }
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
    OIcon(Lucide.PinFilled, 20.dp, iconTint)
}

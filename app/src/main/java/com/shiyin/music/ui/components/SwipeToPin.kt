package com.shiyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
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

/**
 * v1.5.0 右滑固定 / 取消固定 —— 可复用的列表条目包装（**不要在各列表里复制手势代码**）。
 *
 * 第二轮修正（用户 2026-10-07/09 实测）：
 * · **内容块变色**：旧实现内容块没有不透明底，左下的绿色操作层透出来，看起来"内容也变色"。
 *   现在内容块刷上页面底色（不透明），绿条只在**它底下、左侧被揭开的那一条**显示。
 * · **疯狂抖动**：旧实现每个拖动事件都 `scope.launch { snapTo }`（协程竞争）+ `IntOffset(roundToInt)`
 *   （亚像素取整跳变）+ 背景条用 `Modifier.width(reveal)`（每帧改布局）。现在：
 *   拖动值用 `mutableFloatStateOf` **同步**更新（不开协程）；位移走 `graphicsLayer` 浮点平移
 *   （亚像素，不跳）；背景条用 `drawBehind` 画（**不参与布局**）。松手才起一次 spring。
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

    // 单一真相源：拖动中同步更新，松手由一次性 spring 写回——没有协程竞争、没有取整跳变。
    var reveal by remember { mutableFloatStateOf(0f) }
    val revealPx = reveal * maxRevealPx
    val progress = SwipePin.colorProgress(reveal)

    // 未固定 → 绿色系；已固定 → 灰色系（"再拖一次取消固定"）。颜色随进度连续插值。
    val bg = if (!pinned) lerp(PinGreenDim, PinGreenBright, progress)
    else lerp(UnpinGrayDim, UnpinGrayBright, progress)
    val iconTint = if (!pinned) lerp(PinGreenBright.copy(alpha = 0.35f), Color.White, progress)
    else lerp(UnpinGrayBright, Color.White, progress)

    Box(modifier) {
        // 背景操作区：**draw-only**。色条只画 [0, revealPx]；图钉用浮点平移居中在色条内。
        if (revealPx > 0.5f) {
            Box(Modifier.matchParentSize().clip(shape)) {
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind { drawRect(bg, topLeft = Offset.Zero, size = Size(revealPx, size.height)) },
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        // 浮点平移居中在已露出的色条里，不触发重布局
                        .graphicsLayer { translationX = (revealPx - pinSizePx) / 2f },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.PinFilled, 20.dp, iconTint) }
            }
        }
        // 内容块：不透明页面底 + 浮点平移。不透明才能挡住底下的色条（否则"内容变色"）。
        Box(
            Modifier
                .graphicsLayer { translationX = revealPx }
                .background(c.bg)
                .pointerInput(pinned, maxRevealPx, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var isDragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            val dx = ch.position.x - down.position.x
                            val dy = ch.position.y - down.position.y
                            if (!isDragging) {
                                // 纵向意图优先：不消费、冒泡给 LazyColumn 滚动
                                if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) >= kotlin.math.abs(dx)) break
                                if (SwipePin.isHorizontalIntent(dx, dy, slop)) isDragging = true
                            }
                            if (isDragging) {
                                ch.consume()
                                reveal = SwipePin.revealFromDrag(dx, maxRevealPx)
                            }
                            if (!ch.pressed) break
                        }
                        // 松手：过阈值提交（切换固定），随后 spring 弹回 0
                        if (isDragging && SwipePin.shouldCommit(reveal)) onPinnedChange(!pinned)
                        val from = reveal
                        scope.launch {
                            Animatable(from).animateTo(
                                SwipePin.restingReveal(),
                                spring(stiffness = Spring.StiffnessMediumLow),
                            ) { reveal = value }
                        }
                    }
                },
        ) { content() }
        // 固定标识角标：固定状态不再靠"永久右偏"表达，用一个小图钉让用户看出哪些已固定
        //（它们同时被排序置顶，见 buildLibItems）。
        if (enabled && pinned && revealPx < 0.5f) {
            Box(Modifier.align(Alignment.TopStart).padding(2.dp)) {
                Box(
                    Modifier.size(15.dp).clip(CircleShape).background(PinGreenBright),
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.PinFilled, 9.dp, Color.White) }
            }
        }
    }
}

package com.shiyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shiyin.music.ui.icons.Lucide
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// 规格（v1.4.1-backlog §1）点名要「绿色」与「灰色」两套插值，因此这里是固定色，不随主题取。
private val PinGreenDim = Color(0xFF16301F)
private val PinGreenBright = Color(0xFF35C46A)
private val UnpinGrayDim = Color(0xFF2E2E2E)
private val UnpinGrayBright = Color(0xFF9A9A9A)

/**
 * v1.5.0 右滑固定 / 取消固定 —— 可复用的列表条目包装（**不要在各列表里复制手势代码**）。
 * **重做版**：修掉"固定后条目永久停在最大 reveal 导致 UI 错乱"。
 *
 * 规格：docs/internal/v1.4.1-backlog.md §1。要点：
 * · 条目跟随手指向右移动，左侧露出操作区，**拖多少露多少**；
 * · 颜色与图钉随 drag progress **连续插值**（不是两帧切换）；未固定=绿、已固定=灰；
 * · 松手过阈值即提交（置顶/取消置顶），**然后 spring 弹回正常位置**；
 * · 最大 reveal = 屏宽 45%，不允许无限右拖；
 * · 横向 drag 与 LazyColumn 纵向滚动的手势竞争：用 awaitEachGesture 显式仲裁
 *   （纵向意图确立后不消费、冒泡给列表滚动）。
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
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val maxRevealPx = remember(screenWidthPx) { SwipePin.maxRevealPx(screenWidthPx) }
    val scope = rememberCoroutineScope()

    val reveal = remember { Animatable(0f) }
    val revealPx = reveal.value * maxRevealPx
    val progress = SwipePin.colorProgress(reveal.value)

    // 未固定 → 绿色系（拖得越多越亮）；已固定 → 灰色系（表示"再拖一次取消固定"）
    val bg = if (!pinned) lerp(PinGreenDim, PinGreenBright, progress)
    else lerp(UnpinGrayDim, UnpinGrayBright, progress)
    val iconTint = if (!pinned) lerp(PinGreenBright.copy(alpha = 0.35f), Color.White, progress)
    else lerp(UnpinGrayBright, Color.White, progress)

    Box(modifier) {
        // 背景层：只铺「已露出」的那一条宽度，图钉居中其中
        if (revealPx > 0.5f) {
            Box(
                Modifier.matchParentSize().clip(shape).background(bg),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier.width(with(density) { revealPx.toDp() }).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.PinFilled, 20.dp, iconTint) }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(revealPx.roundToInt(), 0) }
                .pointerInput(pinned, maxRevealPx, enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            val dx = ch.position.x - down.position.x
                            val dy = ch.position.y - down.position.y
                            if (!dragging) {
                                // 纵向意图优先：不消费、冒泡给 LazyColumn 滚动
                                if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) >= kotlin.math.abs(dx)) break
                                if (SwipePin.isHorizontalIntent(dx, dy, slop)) dragging = true
                            }
                            if (dragging) {
                                ch.consume()
                                scope.launch { reveal.snapTo(SwipePin.revealFromDrag(dx, maxRevealPx)) }
                            }
                            if (!ch.pressed) break
                        }
                        // 松手结算：过阈值提交（切换固定），随后**一律弹回正常位置**
                        if (dragging && SwipePin.shouldCommit(reveal.value)) {
                            onPinnedChange(!pinned)
                        }
                        scope.launch {
                            reveal.animateTo(
                                SwipePin.restingReveal(),
                                spring(stiffness = Spring.StiffnessMediumLow),
                            )
                        }
                    }
                },
        ) { content() }
        // 固定标识：不再靠"永久右偏移"表达固定状态后，用一个小图钉角标让用户看出
        // 哪些条目已固定（它们同时被排序置顶，见 buildLibItems）。
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

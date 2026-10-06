package com.shiyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
private val UnpinGrayDark = Color(0xFF3A3A3A)
private val UnpinGrayLight = Color(0xFF8C8C8C)

/**
 * v1.5.0 右滑固定 / 取消固定 —— 可复用的列表条目包装（**不要在各列表里复制手势代码**）。
 *
 * 规格：docs/internal/v1.4.1-backlog.md §1。要点：
 * · 条目跟随手指向右移动，左侧露出操作区，**拖多少露多少**；
 * · 颜色与图钉随 drag progress **连续插值**（不是两帧切换）；
 * · 松手过阈值即提交，否则 spring 回弹；最大 reveal = 屏宽 45%，不允许无限右拖；
 * · 用户 2026-10-07 拍板方案 A：固定后**停在最大 reveal**，取消固定是**向左拖回**；
 * · 横向 drag 与 LazyColumn 纵向滚动的手势竞争交给 detectHorizontalDragGestures（只认水平）。
 */
@Composable
fun SwipeToPin(
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val maxRevealPx = remember(screenWidthPx) { SwipePin.maxRevealPx(screenWidthPx) }
    val scope = rememberCoroutineScope()

    val reveal = remember { Animatable(SwipePin.restingReveal(pinned)) }
    // 提交后父层回写 pinned → 这里 spring 收敛到新的静止位（固定停在最大 reveal / 取消后收回 0）
    LaunchedEffect(pinned) {
        reveal.animateTo(SwipePin.restingReveal(pinned), spring(stiffness = Spring.StiffnessMediumLow))
    }

    val revealPx = reveal.value * maxRevealPx
    val bg = if (!pinned) {
        lerp(PinGreenDim, PinGreenBright, reveal.value)
    } else {
        lerp(UnpinGrayDark, UnpinGrayLight, SwipePin.unpinProgress(reveal.value))
    }
    val iconTint = if (!pinned) {
        lerp(PinGreenBright.copy(alpha = 0.35f), Color.White, reveal.value)
    } else {
        lerp(Color.White, UnpinGrayLight, SwipePin.unpinProgress(reveal.value))
    }

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
                .pointerInput(pinned, maxRevealPx) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f },
                        onDragCancel = {
                            scope.launch { reveal.animateTo(SwipePin.restingReveal(pinned), spring()) }
                        },
                        onDragEnd = {
                            if (!pinned) {
                                if (SwipePin.shouldPin(reveal.value)) onPinnedChange(true)
                                else scope.launch { reveal.animateTo(0f, spring()) }
                            } else {
                                if (SwipePin.shouldUnpin(reveal.value)) onPinnedChange(false)
                                else scope.launch { reveal.animateTo(1f, spring()) }
                            }
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        acc += dragAmount
                        val target = if (pinned) SwipePin.revealFromUnpinDrag(acc, maxRevealPx)
                        else SwipePin.revealFromPinDrag(acc, maxRevealPx)
                        scope.launch { reveal.snapTo(target) }
                    }
                },
        ) { content() }
    }
}

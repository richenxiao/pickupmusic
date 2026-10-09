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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
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
 * v1.5.0 右滑固定 / 取消固定 —— 可复用的列表条目包装。
 *
 * **第三轮修抖动（用户 2026-10-09「拖动内容时还是会剧烈抖动」）**：
 * 旧实现（即使第二轮）仍把 `reveal` 读在**函数体**里 → 每个拖动事件触发**整行重组**，
 * content()（封面/标题/歌手等）跟着重组 → 卡顿抖动。
 *
 * 现在的修法：`reveal` **只在 deferred lambda 里读**（graphicsLayer block、drawBehind block），
 * 函数体不读 → 拖动时只触发 draw phase，`content()` 不重组。
 * pin 图标颜色需要随 progress 变 → 单独抽 `PinIcon`，它自己读 state、自己重组（很小，不影响 content）。
 */
@Composable
fun SwipeToPin(
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val c = LocalOrganic.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val maxRevealPx = remember(screenWidthPx) { SwipePin.maxRevealPx(screenWidthPx) }
    val scope = rememberCoroutineScope()
    val pinSizePx = with(density) { 20.dp.toPx() }

    // ★ 唯一真相源：拖动中同步写（pointerInput 里），松手由一次性 spring 写回。
    // ★ 函数体不读 reveal.floatValue → content() 不重组。
    val reveal = remember { mutableFloatStateOf(0f) }

    Box(modifier) {
        // 背景操作区：draw-only。drawBehind lambda 里读 reveal → 只触发 draw phase。
        Box(Modifier.matchParentSize().clip(shape)) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val rp = reveal.floatValue * maxRevealPx
                        if (rp <= 0.5f) return@drawBehind
                        val p = reveal.floatValue.coerceIn(0f, 1f)
                        val bg = if (!pinned) lerp(PinGreenDim, PinGreenBright, p)
                        else lerp(UnpinGrayDim, UnpinGrayBright, p)
                        drawRect(bg, topLeft = Offset.Zero, size = Size(rp, size.height))
                    },
            )
            // 图钉：graphicsLayer lambda 读 reveal（draw phase），不触发重组
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val rp = reveal.floatValue * maxRevealPx
                        translationX = (rp - pinSizePx) / 2f
                        alpha = if (rp > 0.5f) 1f else 0f
                    },
                contentAlignment = Alignment.Center,
            ) { PinIcon(pinned, reveal) }
        }
        // 内容块：不透明页面底 + graphicsLayer lambda 平移 → 拖动时不重组
        Box(
            Modifier
                .background(c.bg)
                .graphicsLayer { translationX = reveal.floatValue * maxRevealPx }
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
                                if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) >= kotlin.math.abs(dx)) break
                                if (SwipePin.isHorizontalIntent(dx, dy, slop)) isDragging = true
                            }
                            if (isDragging) {
                                ch.consume()
                                reveal.floatValue = SwipePin.revealFromDrag(dx, maxRevealPx)
                            }
                            if (!ch.pressed) break
                        }
                        if (isDragging && SwipePin.shouldCommit(reveal.floatValue)) onPinnedChange(!pinned)
                        val from = reveal.floatValue
                        scope.launch {
                            Animatable(from).animateTo(
                                SwipePin.restingReveal(),
                                spring(stiffness = Spring.StiffnessMediumLow),
                            ) { reveal.floatValue = value }
                        }
                    }
                },
        ) { content() }
        // 固定标识角标：alpha 在 graphicsLayer lambda 里读 reveal → 不触发重组
        if (enabled && pinned) {
            Box(
                Modifier.align(Alignment.TopStart).padding(2.dp)
                    .graphicsLayer { alpha = if (reveal.floatValue * maxRevealPx < 0.5f) 1f else 0f }
            ) {
                Box(
                    Modifier.size(15.dp).clip(CircleShape).background(PinGreenBright),
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.PinFilled, 9.dp, Color.White) }
            }
        }
    }
}

/** 图钉图标：单独读 reveal state → 只这个小组件重组，不影响 content()。 */
@Composable
private fun PinIcon(pinned: Boolean, reveal: androidx.compose.runtime.MutableFloatState) {
    val r = reveal.floatValue.coerceIn(0f, 1f)
    val iconTint = if (!pinned) lerp(PinGreenBright.copy(alpha = 0.35f), Color.White, r)
    else lerp(UnpinGrayBright, Color.White, r)
    OIcon(Lucide.PinFilled, 20.dp, iconTint)
}

package com.shiyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shiyin.music.ui.theme.LocalOrganic
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

// 用户 2026-10-15 规格定死的两个平色（**不随主题、不随拖动渐变**）：
// 未固定=Spotify 绿，已固定=灰（表示"再拖一次取消固定"）。
private val PinGreen = Color(0xFF1DB954)
private val UnpinGray = Color(0xFF535353)

/**
 * v1.5.0 右滑固定 / 取消固定 —— 可复用的列表条目包装（**手势/动画只有这一份实现**）。
 *
 * 规格真源：用户 2026-10-15 对官方录屏逐帧核对的规格（`固定.gif`）。要点的实现位置：
 * · 行内容跟手平移、横向过 slop 后锁定并 `consume`（列表不再纵向滚动）→ 手势体里；
 * · 左侧操作区按露出宽度裁切、平色、图钉居中于**已露出区域** → 下面两层 Box；
 * · 松手过阈值提交、否则 spring 回弹（阻尼 0.6） → 手势体收尾；
 * · 固定状态**不在这里画**：小图钉由行自己画在**标题左侧**（规格要求），本组件不管。
 *
 * ### 刻意避开的四个"抖动/闪烁成因"（都是本项目真机上踩过的）
 * 1. **不在每个拖动事件里 `scope.launch { snapTo }`** —— 多个协程并发写同一个动画值会
 *    互相竞争、把跟手变成跳格。这里拖动值是一个**普通 `Float` 状态**，手势体**直接赋值**
 *    （不挂起、不起协程）；只有松手那一次回弹才起**一个** job（并记住它，新拖动立刻取消）。
 *    ⚠️ 之所以不能"在 `awaitEachGesture` 里直接 await `Animatable.snapTo`"：该块是
 *    **受限挂起作用域**，只能调用它自己的挂起函数（编译期就报错，已踩）。
 * 2. **不用 `IntOffset(roundToInt)` 平移** —— 亚像素取整会一格一格跳。这里用
 *    `graphicsLayer { translationX = ... }`（Float）。
 * 3. **不在 composable 体里读拖动值** —— 那会让整行每帧重组（含 `content()`）。
 *    所有读取都在 `graphicsLayer{}` / `drawBehind{}` 的**延迟 lambda** 里。
 * 4. **不用 `AnchoredDraggable`** —— 它的 `confirmValueChange` 在阈值附近来回越线会被反复
 *    回调；副作用写 `onPinnedChange(!pinned)` 时第二次会提交**相反**值 ⇒ 钉↔取消反复翻转
 *    （颜色灰绿交替 + 列表反复重排）。这里手写手势 + **一次手势锁存起点状态、松手只提交一次**。
 */
@Composable
fun SwipeToPin(
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    /** 永久固定的条目（如「我的喜欢」）传 false：不可滑动。 */
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val c = LocalOrganic.current
    val actionWidthPx = SwipePin.actionWidthPx(density.density)
    val iconPx = with(density) { SwipePin.PIN_ICON_DP.dp.toPx() }
    val iconDp = SwipePin.PIN_ICON_DP.dp

    // 露出宽度（px）：手势写、绘制读；**不在 composable 体里读**（见类注释第 3 条）。
    val reveal = remember { mutableFloatStateOf(0f) }
    // 松手回弹动画的 job：一次手势只起一个；新拖动立刻取消旧的，避免两个写入者抢同一个值。
    var releaseJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val curPinned by rememberUpdatedState(pinned)
    val curOnPinnedChange by rememberUpdatedState(onPinnedChange)

    Box(modifier) {
        // ── 底层：操作区（按露出宽度裁切）+ 图钉 ──
        Box(Modifier.matchParentSize().clip(shape)) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    val rp = reveal.floatValue
                    if (rp <= 0.5f) return@drawBehind
                    // 平色：不随 progress 渐变（规格"无整行变色"）
                    drawRect(
                        color = if (curPinned) UnpinGray else PinGreen,
                        topLeft = Offset.Zero,
                        size = Size(rp, size.height),
                    )
                },
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val rp = reveal.floatValue
                        // "随露出宽度居中"（用户 2026-10-15 选定）：图标中心落在 rp/2
                        // ⇒ 半露出时看到半个钉，满露出时正好居中于操作区。
                        translationX = (rp - iconPx) / 2f
                        alpha = if (rp > 0.5f) 1f else 0f
                    },
                contentAlignment = Alignment.Center,
            ) { PinGlyph(Color.White, iconDp) }
        }

        // ── 前景：行内容（Float 平移，0 延迟、不取整）──
        // background 必须在 graphicsLayer **之后**（= 画在 layer 内），底色才会跟着平移、
        // 把左侧露出的操作区让出来；放在前面的话底色不跟平移、永远盖住操作区。
        Box(
            Modifier
                .then(
                    if (enabled) {
                        Modifier.pointerInput(curPinned, actionWidthPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val slop = viewConfiguration.touchSlop
                                var dragging = false
                                // 一次手势**锁存起点状态**：无论松手前发生什么，只按这一个基准取反提交一次
                                //（避免"反复提交/反复重排"，那正是灰绿交替闪烁的来源）。
                                var baseAtStart: Boolean? = null
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val dx = ch.position.x - down.position.x
                                    val dy = ch.position.y - down.position.y
                                    if (!dragging) {
                                        // 纵向意图优先：不消费、冒泡给 LazyColumn 滚动
                                        if (abs(dy) > slop && abs(dy) >= abs(dx)) break
                                        if (SwipePin.isHorizontalIntent(dx, dy, slop)) {
                                            dragging = true
                                            baseAtStart = curPinned
                                            releaseJob?.cancel()   // 新拖动接管，停掉上次回弹
                                            releaseJob = null
                                        }
                                    }
                                    if (dragging) {
                                        ch.consume()           // 锁定横向：列表不再纵向滚动
                                        // 直接赋值（非挂起）：严格跟手、零延迟、无协程竞争
                                        reveal.floatValue =
                                            SwipePin.revealPxFromDrag(dx, actionWidthPx)
                                    }
                                    if (!ch.pressed) break
                                }
                                if (dragging) {
                                    if (SwipePin.shouldCommit(reveal.floatValue, actionWidthPx)) {
                                        curOnPinnedChange(!(baseAtStart ?: curPinned))
                                    }
                                    // 一律回弹到 0（固定与否都回到正常位置）：单 job + 阻尼 0.6（允许轻微过冲）
                                    val from = reveal.floatValue
                                    releaseJob = scope.launch {
                                        val anim = Animatable(from)
                                        anim.animateTo(
                                            SwipePin.restingReveal(),
                                            spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
                                        ) { reveal.floatValue = value }
                                    }
                                }
                            }
                        }
                    } else Modifier
                )
                .graphicsLayer { translationX = reveal.floatValue }
                .background(c.bg),
        ) { content() }
    }
}

/**
 * 列表行**标题左侧**的「已固定」小图钉（规格：绿色实心、约 14–16dp、距标题 4–8dp）。
 *
 * 由**行自己**调用：`SwipeToPin` 不画这个标记——它不知道标题在哪（规格要求贴在标题左边，
 * 不是角标）。放在本文件是为了让 `ui/components`（TrackRow）与 `ui/screens`（LibraryScreen）
 * 共用同一个图形定义，不会两处各画一个不一样的钉。
 */
@Composable
fun PinnedMark(modifier: Modifier = Modifier) {
    PinGlyph(PinGreen, 15.dp, modifier)
}

/**
 * **图钉**（用户 10-09「没有针刺」→ 自绘；10-12「像气球」→ 改胶囊+帽沿；
 * 10-12「把中间那一横条去掉」→ 定稿：**上宽下窄的梯形帽 + 一根细针**，无任何横向元素）。
 * 白色实心，用在操作区中央。
 */
@Composable
private fun PinGlyph(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // 帽：上宽下窄的梯形（锥度本身就是识别特征）
        val head = Path().apply {
            moveTo(w * 0.44f, h * 0.52f)
            lineTo(w * 0.25f, h * 0.19f)
            lineTo(w * 0.75f, h * 0.19f)
            lineTo(w * 0.56f, h * 0.52f)
            close()
        }
        drawPath(head, color)
        // 针：从帽的下缘收窄到尖端
        val needle = Path().apply {
            moveTo(w * 0.455f, h * 0.50f)
            lineTo(w * 0.545f, h * 0.50f)
            lineTo(w * 0.50f, h * 1.0f)
            close()
        }
        drawPath(needle, color)
    }
}

package com.shiyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shiyin.music.R
import com.shiyin.music.ui.theme.LocalOrganic
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

// 用户 2026-10-15 规格定死的两个平色（**不随主题、不随拖动渐变**）：
// 未固定=Spotify 绿，已固定=灰（表示"再拖一次取消固定"）。
private val PinGreen = Color(0xFF1DB954)
private val UnpinGray = Color(0xFF535353)

/** 取消（松手未达阈值）时操作区淡出的时长。用户 2026-10-16 选的形态：**边缩边淡出**。 */
private const val CANCEL_FADE_MS = 180

/**
 * v1.5.0 右滑固定 / 取消固定 —— 可复用的列表条目包装（**手势/动画只有这一份实现**）。
 *
 * 规格真源：用户 2026-10-15 对官方录屏逐帧核对的规格（`固定.gif`）+ 2026-10-16 的四条更正。
 *
 * ### 观感（按规格 + 2026-10-16 更正）
 * · 行内容 `translationX` 严格跟手；横向过 touchSlop 即锁定并 `consume`（列表不再纵向滚动）。
 * · 左侧露出操作区，宽度=当前位移，**上限 = 行宽 45%**（2026-10-16：原型能拉到约半屏，
 *   随后用户确认"旧规格的宽度还行" ⇒ 回到 45%）。平色背景。
 * · 图钉**始终位于"已露出区域"的中央**（2026-10-15 选定）⇒ 拖动时随之外移，满露出时居中于操作区。
 * · 松手过阈值=提交；**未过阈值=取消**：操作区**边缩边淡出**（2026-10-16 选定）。
 * · 固定状态**不在这里画**：小图钉由行自己画在**第二行（类型行）开头**，本组件不管。
 *
 * ### 刻意避开的四个"抖动/闪烁成因"（都是本项目真机上踩过的）
 * 1. **不在每个拖动事件里 `scope.launch { snapTo }`** —— 拖动值是一个**普通 Float 状态**，
 *    手势体**直接赋值**（不挂起、不起协程）；只有松手那一次回弹才起**一个** job（记住它，
 *    新拖动立刻取消）。⚠️ 之所以不能"在 `awaitEachGesture` 里直接 await `Animatable.snapTo`"：
 *    该块是**受限挂起作用域**（编译期就报错，已踩）。
 * 2. **不用 `IntOffset(roundToInt)` 平移** —— 亚像素取整会一格一格跳。这里用 Float。
 * 3. **不在 composable 体里读拖动值** —— 那会让整行每帧重组（含 `content()`）。
 *    所有读取都在 `graphicsLayer{}` / `drawBehind{}` 的**延迟 lambda** 里。
 * 4. **不用 `AnchoredDraggable`** —— 它的 `confirmValueChange` 在阈值附近反复回调 ⇒
 *    钉↔取消反复翻转（颜色灰绿交替 + 列表反复重排）。这里手写手势 +
 *    **一次手势锁存起点状态、松手只提交一次**。
 */
@Composable
fun SwipeToPin(
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    /** 永久固定的条目传 false：不可滑动。 */
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val c = LocalOrganic.current
    val iconPx = with(density) { SwipePin.PIN_ICON_DP.dp.toPx() }
    val iconDp = SwipePin.PIN_ICON_DP.dp

    // 露出宽度（px）：手势写、绘制读；**不在 composable 体里读**（见类注释第 3 条）。
    val reveal = remember { mutableFloatStateOf(0f) }
    // 取消时的淡出系数（1=不透明）。提交时保持 1（随位移缩回即可），取消时 1→0 边缩边淡。
    val cancelFade = remember { mutableFloatStateOf(1f) }
    // 松手回弹动画的 job：一次手势只起一个；新拖动立刻取消旧的，避免两个写入者抢同一个值。
    var releaseJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val curPinned by rememberUpdatedState(pinned)
    val curOnPinnedChange by rememberUpdatedState(onPinnedChange)

    // 操作区宽度 = **行自身宽度 × 45%**（用户 2026-10-16：旧规格的宽度还行）。
    // 用 BoxWithConstraints 取"这一行的实际宽度"，比拿屏宽更贴边距。
    BoxWithConstraints(modifier) {
        val actionWidthPx = SwipePin.actionWidthPx(with(density) { maxWidth.toPx() })

        // ── 底层：操作区（按露出宽度裁切）+ 图钉 ──
        Box(Modifier.matchParentSize().clip(shape)) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    val rp = reveal.floatValue.coerceAtLeast(0f)   // 弹簧过冲不画反向
                    if (rp <= 0.5f) return@drawBehind
                    // 平色：不随 progress 渐变（规格"无整行变色"）；取消时整体淡出
                    drawRect(
                        color = (if (curPinned) UnpinGray else PinGreen).copy(alpha = cancelFade.floatValue),
                        topLeft = Offset.Zero,
                        size = Size(rp.coerceAtMost(size.width), size.height),
                    )
                },
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val rp = reveal.floatValue.coerceAtLeast(0f)
                        // "随露出宽度居中"（用户 2026-10-15 选定）：图标中心落在 rp/2
                        translationX = (rp - iconPx) / 2f
                        alpha = if (rp > 0.5f) cancelFade.floatValue else 0f
                    },
                contentAlignment = Alignment.Center,
            ) { PinImage(Color.White, iconDp) }
        }

        // ── 前景：行内容（Float 平移，0 延迟、不取整）──
        // background 必须在 graphicsLayer **之后**（= 画在 layer 内），底色才会跟着平移、
        // 把左侧露出的操作区让出来；放在前面的话底色不跟平移、永远盖住操作区。
        Box(
            Modifier
                .then(
                    if (enabled) {
                        Modifier.pointerInput(curPinned) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val slop = viewConfiguration.touchSlop
                                var dragging = false
                                // 一次手势**锁存起点状态**：无论松手前发生什么，只按这一个基准取反提交一次
                                //（避免"反复提交/反复重排"，那正是灰绿交替闪烁的来源）。
                                var baseAtStart: Boolean? = null
                                // ⚠️ **方向只判一次**（用户 2026-10-18）：
                                // "触摸超过 slop 的那一刻，用累计位移判断方向……否则本次手势整个过程
                                //  锁定为纵向，之后再横向移动也不得触发"。
                                // 旧实现只在"纵向占优"时才 break，所以先小幅度斜一下、之后横着拉，
                                // 仍会被判成横向 —— 这就是"斜着滑还是会触发"的来源。
                                var decided = false
                                // 2026-10-23 用户："除了少部分能滑动固定，其他滑动都没反应了"。
                                // 根因：原来**一过 slop 就落锤** —— 而向下滑时手指通常先产生
                                // 小的 dy（重力弧线），dx 还没长起来就被判成纵向、整条手势作废。
                                // 现在改成**等方向明确才落锤**（三条判据任一命中才决定）：
                                //  ① 明显横（|dx| > slop 且 |dx| ≥ 2.75|dy|）→ 横向固定；
                                //  ② 明显纵（|dy| > slop 且 |dy| ≥ 2.75|dx|）→ 锁纵向；
                                //  ③ 位移已经不小（> 2.5×slop）仍分不出方向 → 按滚动处理。
                                // 既不再"斜着也触发"（判据 ① 未松），也不再"大多数点不动"。
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val dx = ch.position.x - down.position.x
                                    val dy = ch.position.y - down.position.y
                                    if (!dragging && !decided) {
                                        val ax = abs(dx); val ay = abs(dy)
                                        val horiz = SwipePin.isHorizontalIntent(dx, dy, slop)
                                        val vert = ay > slop && ay >= ax * SwipePin.LOCK_ASPECT
                                        val ambiguous = (ax > slop * 2.5f || ay > slop * 2.5f)
                                        if (horiz) {
                                            decided = true
                                            dragging = true
                                            baseAtStart = curPinned
                                            cancelFade.floatValue = 1f   // 上一次取消的淡出别影响到这次
                                            releaseJob?.cancel()         // 新拖动接管，停掉上次回弹
                                            releaseJob = null
                                        } else if (vert || ambiguous) {
                                            decided = true
                                            break
                                        }
                                    }
                                    SwipePinLog.decided(dx, dy, slop, dragging)
                                    if (dragging) {
                                        ch.consume()           // 锁定横向：列表不再纵向滚动
                                        // 直接赋值（非挂起）：严格跟手、零延迟、无协程竞争
                                        reveal.floatValue =
                                            SwipePin.revealPxFromDrag(dx, actionWidthPx)
                                    }
                                    if (!ch.pressed) break
                                }
                                if (dragging) {
                                    val commit = SwipePin.shouldCommit(reveal.floatValue, actionWidthPx)
                                    if (commit) {
                                        curOnPinnedChange(!(baseAtStart ?: curPinned))
                                    }
                                    // 提交：操作区随位移缩回（不淡出）；取消：**边缩边淡出**
                                    if (!commit) {
                                        scope.launch {
                                            val a = Animatable(1f)
                                            a.animateTo(0f, tween(CANCEL_FADE_MS)) { cancelFade.floatValue = value }
                                        }
                                    }
                                    val from = reveal.floatValue.coerceAtLeast(0f)
                                    releaseJob = scope.launch {
                                        val anim = Animatable(from)
                                        anim.animateTo(
                                            SwipePin.restingReveal(),
                                            spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
                                        ) { reveal.floatValue = value.coerceAtLeast(0f) }
                                    }
                                }
                            }
                        }
                    } else Modifier
                )
                // 过冲钳到 0：否则弹簧会短暂把内容推到左边（看起来像整行"跳一下"）
                .graphicsLayer { translationX = reveal.floatValue.coerceAtLeast(0f) }
                .background(c.bg),
        ) { content() }
    }
}

/**
 * 列表行**第二行（类型行）开头**的「已固定」小图钉（用户 2026-10-16："图钉就是放在第二行的
 * 第一个位置"，如 `📌 专辑*周杰伦`）。绿色实心、15dp、右侧留 6dp。
 *
 * 由**行自己**调用：`SwipeToPin` 不画这个标记——它不知道行里的文字布局。
 * 放在本文件是为了让 `ui/components`（TrackRow）与 `ui/screens`（LibraryScreen）共用同一个
 * 图形定义，不会两处各画一个不一样的钉。
 */
@Composable
fun PinnedMark(modifier: Modifier = Modifier) {
    PinImage(PinGreen, 15.dp, modifier)
}

/**
 * **图钉**（用户 2026-10-16 换成他提供的 SVG 图标）。
 *
 * 走 **VectorDrawable**（`res/drawable/ic_pin_mark.xml`）：`pathData` 与用户给的 SVG
 * **逐字照搬**——手抄 60 多段曲线必错，而 VectorDrawable 用的就是同一套 SVG 路径语法，
 * 零转写风险。填充色写死白，按需 tint：操作区里=白，标题前的小钉=绿 `#1DB954`。
 */
@Composable
private fun PinImage(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_pin_mark),
        contentDescription = null,
        colorFilter = ColorFilter.tint(color),
        modifier = modifier.size(size),
    )
}

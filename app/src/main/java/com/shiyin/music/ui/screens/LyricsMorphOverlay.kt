package com.shiyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.shiyin.music.MainViewModel
import com.shiyin.music.ui.components.LyricsMorphSpec
import com.shiyin.music.ui.components.LyricsMorphState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * morph overlay · 方案A(等比窗口缩放,用户 2026-09 拍板)。
 *
 * 隐喻:手机里应用窗口拉大/拉小——窗口和内容作为整体等比缩放,内容全程
 * 完整可见(只是变小),不再渐进遮挡、不再非等比压扁文字。
 *
 * 结构(progress p: 0=卡片,1=全屏;全部视觉属性是 p 的纯函数,双向对称):
 *   主段 p∈[0.35,1]:窗口+内容共用同一条等比变换——s: 0.35→1,中心从
 *     卡片中心 lerp 到屏幕中心。内容 alpha=1 原样跟随缩放。锚行跟踪由
 *     等比缩放天然达成:预滚把当前行放在屏幕中心附近,围绕中心的等比
 *     缩放使它始终位于缩小窗口的视觉中心,收起时随窗口落到卡片位置。
 *   末段 p∈[0,0.35]:内容继续等比收缩(s 0.35→0.15,中心钉在卡片中心)
 *     并淡出(p 0.35→0.15)——不跟窗口的非等比形变,文字不被压扁;纯色
 *     窗口非等比形变落到卡片矩形(几何不可能性:全屏 2.2:1 vs 卡片
 *     0.33:1,宽高比差 6.7 倍,等比物理上无法精确落卡,末段形变是唯一
 *     落点方式);落卡处与真卡片 C3 交叉淡化(窗口 alpha∈[0,0.08] 渐隐,
 *     PlayerScreen 卡片 alpha∈[0,0.05] 渐显,无空帧)。
 *
 * 状态:progress(VM 常驻 Animatable)唯一连续真源;lyricsOn 纯组合门。
 * 组合零订阅 progress(帧率优化):唯一门是 snapshotFlow 一次性翻转
 * listReady(p>0.10,早于内容淡入窗 0.15,预滚发生在 alpha=0 区间内),
 * 此后每帧只剩两块 graphicsLayer 延迟读重录,重组次数 0。
 * 首帧 null bounds:窗口 alpha=0 不可见(现象①根因防复发)。
 */
@Composable
fun LyricsMorphOverlay(vm: MainViewModel, morph: LyricsMorphState) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // open() 在 overlay 首次组合时由 LaunchedEffect 启动(suspend → animateTo 跑完
    // 才返回;收起收尾由 VM.collapseLyrics 在 collapse() 返回后做 caller-side)。
    LaunchedEffect(Unit) { morph.open(vm.lyricsCardBounds) }
    // 屏高写到 morph 状态(onDrag/collapse 的 px→progress 换算共用除数)。
    morph.screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp *
        androidx.compose.ui.platform.LocalDensity.current.density

    val cornerBase = with(density) { 28.dp.toPx() }
    val track = vm.trackById(vm.player.currentId)
    // 帧率优化(重组源定位):原 rememberCoverPalette 每次打开 overlay 都起
    // produceState 轮询(异步加载+取色);PlayerScreen 已对同一 track 算过并
    // 写入 ArtCache 同步缓存——直接读同一缓存条目,与播放页背景严格同色,
    // 组合期零协程、零轮询。极端时序(缓存未就绪)回退静态调色板。
    val artBg = com.shiyin.music.ui.components.ArtCache.colorFor(track?.albumId ?: -1L)?.let {
        androidx.compose.ui.graphics.Color(it.first)
    } ?: com.shiyin.music.ui.components.coverPalette(track?.paletteIndex ?: 0).first

    // 帧率优化(组合零订阅):snapshotFlow 一次性门——progress 首次越过 0.10
    // 时组合 LyricsScreen(早于内容淡入窗 0.15,首组合开销落在 alpha=0 区间),
    // 之后整个 overlay 组合不再订阅 progress,逐帧动画只剩 graphicsLayer
    // 延迟读重录(窗口层/内容层),每帧重组次数 0。
    var listReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { morph.progress.value > 0.10f }
            .filter { it }
            .first()
        listReady = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // ── P3-① 指针闸门(§3.5 主防御;bug③ 修复:整条手势流持续判定;
                //      用户严格规则:仅顶部起手可收起)──
                // 播放 sheet 的 anchoredDraggable 会对"sheet 正文任意位置"的下拉 1:1
                // 跟手(nestedScroll pre 派发它先看到)。必须在指针层拦截:过 slop 后
                // 逐帧判主轴。用户规则:只有"到手时列表已在顶 或 起手在 chrome 区
                // (顶栏/底栏)"的下拉才允许收起;列表中部起手永不转收起。向上/水平
                // 不消费正常派发。Initial pass 父先于子收事件,此处消费能拦在
                // LazyColumn/sheet 之前。chrome 区由 LyricsScreen 经 VM 透传判定。
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                    )
                    var dragging = false
                    var blocking = false
                    var pullDown = false
                    var lastY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!dragging && !blocking) {
                            if (!pullDown) {
                                val dy = change.position.y - down.position.y
                                if (kotlin.math.abs(dy) > viewConfiguration.touchSlop) {
                                    if (dy <= 0f) break
                                    pullDown = true
                                    val inChrome = vm.lyricsListTopPx != null && (change.position.y < vm.lyricsListTopPx!! || change.position.y > vm.lyricsListBottomPx!!)
                                    val atTop = vm.lyricsListAtTop
                                    if (inChrome || atTop) dragging = true
                                }
                            } else if (vm.lyricsListAtTop) {
                                blocking = true
                            }
                            lastY = change.position.y
                        }
                        if (dragging) {
                            morph.onDrag(change.position.y - lastY)
                            lastY = change.position.y
                            change.consume()
                        } else if (blocking) {
                            if (change.position.y < lastY) {
                                blocking = false
                            } else {
                                change.consume()
                            }
                            lastY = change.position.y
                        }
                        if (!event.changes.any { it.pressed }) {
                            if (dragging) {
                                val velocity = tracker.calculateVelocity().y
                                val dd = (1f - morph.progress.value) * (morph.cardBounds?.top ?: 1f)
                                val thresholdPx = 120.dp.toPx()
                                if (dd > thresholdPx || velocity > 2200f) {
                                    vm.collapseLyrics(velocity)
                                } else {
                                    scope.launch { morph.progress.animateTo(1f, LyricsMorphSpec.collapseSpring) }
                                }
                            }
                            break
                        }
                    }
                }
            }
    ) {
        // ── 窗口层:纯色圆角矩形 ──
        // 主段与内容层共用同一条等比变换(被内容完全覆盖,提供圆角裁剪);
        // 末段内容淡出后独自非等比形变落卡(方案A唯一非等比段,纯色无文字)。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 死层修复:progress 先读(建图层状态依赖,动画每帧重录)。
                    val pp = morph.progress.value
                    val b: Rect? = morph.cardBounds
                    if (b == null) {
                        // null bounds(首帧 open() 未执行/Activity 重建):不可见。
                        alpha = 0f
                        return@graphicsLayer
                    }
                    // C3: 落点交叉淡化——p∈[0,0.08] alpha 0→1,与 PlayerScreen
                    // 卡片内容(其自身 alpha∈[0,0.05] 渐显)交叉,无空帧。
                    alpha = fracRange(pp, 0f, 0.08f)
                    if (pp >= MAIN_END) {
                        // 主段:等比缩放 s=S_EQ→1,中心 卡片中心→屏幕中心。
                        val t = fracRange(pp, MAIN_END, 1f)
                        val s = lerp01(S_EQ, 1f, t)
                        val cx = lerp01(b.left + b.width / 2f, size.width / 2f, t)
                        val cy = lerp01(b.top + b.height / 2f, size.height / 2f, t)
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                        scaleX = s
                        scaleY = s
                        translationX = cx - size.width / 2f
                        translationY = cy - size.height / 2f
                        shape = RoundedCornerShape(cornerBase * s)
                    } else {
                        // 末段:矩形自卡片 lerp 到主段终点矩形(等比 S_EQ、同中心),
                        // 两侧在 p=0.35 处尺寸/圆角严格连续。
                        val ccx = b.left + b.width / 2f
                        val ccy = b.top + b.height / 2f
                        val mw = size.width * S_EQ
                        val mh = size.height * S_EQ
                        val t2 = fracRange(pp, 0f, MAIN_END)
                        val left = lerp01(b.left, ccx - mw / 2f, t2)
                        val top = lerp01(b.top, ccy - mh / 2f, t2)
                        val right = lerp01(b.right, ccx + mw / 2f, t2)
                        val bottom = lerp01(b.bottom, ccy + mh / 2f, t2)
                        val sEff = lerp01(1f, S_EQ, t2)
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                        scaleX = (right - left) / size.width
                        scaleY = (bottom - top) / size.height
                        translationX = (left + right) / 2f - size.width / 2f
                        translationY = (top + bottom) / 2f - size.height / 2f
                        shape = RoundedCornerShape(cornerBase * sEff)
                    }
                    clip = true
                }
                .background(artBg)
        )

        // ── 内容层:真 LyricsScreen,与窗口同条等比变换整体缩放 ──
        // 主段共用窗口的 s/中心(作为一个整体动);末段内容继续等比收缩
        // (S_EQ→S_END,中心钉卡片中心)并淡出——不跟窗口非等比形变,
        // 文字任何时刻只被等比缩放,永不压扁。裁剪由自身圆角 shape 承担
        // (缩放后的自身边界即窗口边界),不再 clipRect 渐进遮挡。
        if (listReady) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val pp = morph.progress.value
                        val b: Rect? = morph.cardBounds
                        if (b == null) {
                            // null bounds 兜底分支(open() 已 snap 到 1):全屏
                            // 原样渲染,与重建场景期望一致。
                            alpha = 1f
                            return@graphicsLayer
                        }
                        // 内容淡入/淡出:展开 p 0.15→0.35 淡入,收起反向淡出。
                        alpha = fracRange(pp, 0.15f, 0.35f)
                        val s: Float
                        val cx: Float
                        val cy: Float
                        if (pp >= MAIN_END) {
                            // 主段:与窗口层完全同一条等比公式。
                            val t = fracRange(pp, MAIN_END, 1f)
                            s = lerp01(S_EQ, 1f, t)
                            cx = lerp01(b.left + b.width / 2f, size.width / 2f, t)
                            cy = lerp01(b.top + b.height / 2f, size.height / 2f, t)
                        } else {
                            // 末段:继续等比收缩 S_EQ→S_END,中心钉卡片中心。
                            val t2 = fracRange(pp, 0f, MAIN_END)
                            s = lerp01(S_END, S_EQ, t2)
                            cx = b.left + b.width / 2f
                            cy = b.top + b.height / 2f
                        }
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                        scaleX = s
                        scaleY = s
                        translationX = cx - size.width / 2f
                        translationY = cy - size.height / 2f
                        shape = RoundedCornerShape(cornerBase * s)
                        clip = true
                    }
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    LyricsScreen(vm)
                }
            }
        }
    }
}

/** 方案A 常量(用户拍板 2026-09):主段等比区间终点/等比最小比例/末段内容收缩终点。 */
private const val MAIN_END = 0.35f
private const val S_EQ = 0.35f
private const val S_END = 0.15f

/** t 在 [a,b] 内的归一化进度(区间外 clamp 0/1)。 */
private fun fracRange(t: Float, a: Float, b: Float): Float =
    ((t - a) / (b - a).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

private fun lerp01(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
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
 * v2 架构收敛后的 morph overlay(状态收敛 commit 1 + 内容解耦 commit 2)。
 *
 * 状态(状态收敛):progress(VM 常驻 Animatable)是唯一连续真源;lyricsOn 是
 * 纯组合门。phase/镜像/回调注入全删。视觉属性全部是 progress 的纯函数。
 *
 * 内容(内容解耦):
 *   窗口层——纯色圆角矩形,graphicsLayer 做非等比 scale/translate/圆角/裁剪。
 *     零内容,只承载背景色块的几何形变。文字永不进入此层的变换子树。
 *   内容层——真 LyricsScreen,全屏原生布局、不缩放。做透明度交叉淡化
 *     (fracRange(p,0.40,0.65)) + 锚行位移跟踪(translationY 使当前行随窗口
 *     从卡片中心升到屏幕中心)+ drawWithContent clipRect 裁剪到窗口当前矩形。
 *   两层是兄弟(非父子),内容不被窗口缩放——结构性消除"压缩感"。
 *   预览副本层(PreviewSnapshot/19→26sp 字号插值)全删——小内容由 PlayerScreen
 *   的真卡片(C3 alpha 交叉淡化)承担,不存在中段"结构切换"(现象②)。
 *   首帧 null bounds:alpha=0(不可见),不再 early-return 默认值(=全屏可见=
 *   现象①根因)。LyricsScreen 组合延迟到 p>0.35(淡入窗前),消除组合期闪现。
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
    // 组合期零协程、零轮询。极端时序(缓存未就绪)回退静态调色板,窗口层
    // 背景色在 p<0.08 淡入窗内肉眼不可分辨差异。
    val artBg = com.shiyin.music.ui.components.ArtCache.colorFor(track?.albumId ?: -1L)?.let {
        androidx.compose.ui.graphics.Color(it.first)
    } ?: com.shiyin.music.ui.components.coverPalette(track?.paletteIndex ?: 0).first

    // 帧率优化(唯一组合级订阅消除)+ 突发挪位:原 p>0.35 门把 LyricsScreen
    // 首组合开销(LazyColumn+26sp 测量+振假名 pipeline 起步)落在动画中段
    // =实测 3 个 Slow UI 帧之一。改起始组合:突发落在窗口还贴卡片(p≈0)、
    // 内容 alpha=0 的不可见区,读作"首帧略迟"而非"中段卡顿"。组合零订阅
    // progress 不变,逐帧动画只剩 graphicsLayer 延迟读重录。
    var listReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { morph.progress.value > 0f }
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
        // ── 窗口层:纯色圆角矩形,graphicsLayer 做几何形变 ──
        // 零内容:只承载 artBg 背景色块 + 圆角裁剪。文字永不进入此变换子树。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 死层修复:progress 先读(建图层状态依赖,动画每帧重录)。
                    val pp = morph.progress.value
                    val b: Rect? = morph.cardBounds
                    if (b == null) {
                        // null bounds(首帧 open() 未执行/Activity 重建):
                        // alpha=0 = 不可见。不再 early-return 默认值(=恒等+alpha=1
                        // =全屏可见=现象①"小字号文字顶部闪现"根因)。
                        alpha = 0f
                        return@graphicsLayer
                    }
                    // C3: 容器落点交叉淡化——p∈[0,0.08] alpha 0→1。收起末段容器
                    // 渐透明,PlayerScreen 卡片内容同步渐显,交叉淡化无空帧。
                    alpha = fracRange(pp, 0f, 0.08f)
                    // 59fffa2:缩放原点钉图层左上 (0,0),与 translation 补偿公式对齐。
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    scaleX = lerp01(b.width / size.width, 1f, pp)
                    scaleY = lerp01(b.height / size.height, 1f, pp)
                    translationX = lerp01(b.left, 0f, pp)
                    translationY = lerp01(b.top, 0f, pp)
                    shape = RoundedCornerShape(lerp01(cornerBase, 0f, pp))
                    clip = true
                }
                .background(artBg)
        )

        // ── 内容层:真 LyricsScreen,全屏原生布局、不缩放 ──
        // 兄弟层(非窗口子层),永不继承窗口的 scale 变换——结构性消除"压缩感"。
        // 透明度交叉淡化 + 锚行位移跟踪 + clipRect 裁剪到窗口当前矩形。
        if (listReady) {
            Box(
                Modifier
                    .fillMaxSize()
                    // clipRect 在 graphicsLayer 外层:先裁剪(屏坐标)后位移生效。
                    // 裁剪到窗口当前 on-screen 矩形(与窗口层同一条 lerp 公式)。
                    .drawWithContent {
                        val b = morph.cardBounds
                        if (b != null) {
                            val pp = morph.progress.value
                            clipRect(
                                left = lerp01(b.left, 0f, pp),
                                top = lerp01(b.top, 0f, pp),
                                right = lerp01(b.right, size.width, pp),
                                bottom = lerp01(b.bottom, size.height, pp),
                            ) {
                                this@drawWithContent.drawContent()
                            }
                        } else {
                            drawContent()
                        }
                    }
                    .graphicsLayer {
                        val pp = morph.progress.value
                        // 内容淡入/淡出:展开 0.40→0.65 淡入,收起反向淡出。
                        alpha = fracRange(pp, 0.40f, 0.65f)
                        // 锚行跟踪:当前播放行随窗口从卡片中心升到屏幕中心。
                        // translationY 使列表内容整体位移,activeI 始终在窗口垂直中心。
                        val b = morph.cardBounds
                        if (b != null) {
                            val cardCenterY = b.top + b.height / 2f
                            val screenCenterY = size.height / 2f
                            translationY = lerp01(cardCenterY - screenCenterY, 0f, pp)
                        }
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

/** t 在 [a,b] 内的归一化进度(区间外 clamp 0/1)。 */
private fun fracRange(t: Float, a: Float, b: Float): Float =
    ((t - a) / (b - a).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

private fun lerp01(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

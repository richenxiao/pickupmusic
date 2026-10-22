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
 *
 * v1.3.x 预组合(帧率收尾):组合门从纯 lyricsOn 扩为 lyricsOn||预热。预热态
 *   (interactive=false)提前挂载同一实例——隐形+整页零命中测试(触摸穿透),
 *   首组合/测量/预滚/取色/振假名 pipeline 全部在静止播放页完成;点卡翻
 *   interactive=true 即刻开动画,打开帧零突发。预热没赶上走现场组合 fallback。
 */
@Composable
fun LyricsMorphOverlay(vm: MainViewModel, morph: LyricsMorphState, interactive: Boolean = true) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // v1.3.x 预组合(morph 帧率收尾):overlay 可在预热期(interactive=false)提前
    // 挂载——隐形(progress=0 时两层 alpha 本来就是 0)+ 可穿透(interactive=false
    // 时整页零命中测试,LyricsScreen 的全部 pointerInput/clickable 条件不挂)。
    // tap 翻 interactive=true 才启动 open。预热挂载把首组合/测量/预滚/取色/
    // 振假名 pipeline 全搬到静止播放页,打开帧零突发 → 预热过的挂载不再要
    // withFrameNanos 1 帧延迟(frame 0 即动画首帧);全新挂载(预热没赶上的
    // fallback)保留 1 帧隔离突发,行为与旧版一致。
    val wasPrewarm = remember { !interactive }
    LaunchedEffect(interactive) {
        if (!interactive) return@LaunchedEffect
        if (!wasPrewarm) androidx.compose.runtime.withFrameNanos { }
        morph.open(vm.lyricsCardBounds)
    }
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
    // v1.3.x:窗口层色与 LyricsScreen 背景同源(都是 raw 封面色)——歌词颜色档
    // 只变文字不变背景(用户明确),故窗口层不需要任何档位调整,展开中段无跳变。
    val artBg = com.shiyin.music.ui.components.ArtCache.colorFor(track?.albumId ?: -1L)?.let {
        androidx.compose.ui.graphics.Color(it.first)
    } ?: com.shiyin.music.ui.components.coverPalette(track?.paletteIndex ?: 0).first

    // 帧率优化(组合零订阅)+ 突发隔离:listReady 立即 true(无 snapshotFlow
    // 门),LyricsScreen 在 overlay 首组合(frame 0)就组合——突发落在窗口
    // alpha=0 不可见、动画(withFrameNanos 延 1 帧)未启动的独立帧,读作 tap
    // 延迟而非动画中段卡顿。原 p>0.35 门把突发落在动画 35% 处=实测中段
    // Slow UI 帧;改起始+延迟开动画让突发与动画不抢帧。组合零订阅 progress
    // 不变(无 progress 读点),逐帧动画只剩 graphicsLayer 延迟读重录。
    var listReady by remember { mutableStateOf(true) }

    Box(
        Modifier
            .fillMaxSize()
            // v1.3.x 预组合:闸门只在 interactive(歌词本打开)时挂载——预热期整层
            // 零命中测试,触摸穿透到播放页(卡片点照常打开、播放键/进度条不受影响)。
            .then(if (interactive) Modifier.pointerInput(Unit) {
                // v23:诊断打点(PullGate 日志 / 逐行 Press 收集器)已全部撤掉——
                // 下拉误触的根因已由实机 logcat 定案(**不是**闸门内部,是播放页
                // ModalBottomSheet 自身的 settleToDismiss → onDismissRequest),
                // 修复打在 AppRoot 的 confirmValueChange 上,与闸门无关。
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                    )
                    // 2026-10-22：手势当下**直接问**歌词列表（闭包读 LazyListState），
                    // 不再用异步快照布尔 —— 旧实现快速滑动/长按拖动时可能拿到旧值，
                    // 用户报"在歌词中间/最后几句也能拖几次触发下拉"。
                    val downAtTop = vm.lyricsAtTopNow?.invoke() ?: vm.lyricsListAtTop
                    var dragging = false
                    var pullDown = false
                    var lastY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val dyRaw = change.position.y - down.position.y
                        if (!dragging) {
                            if (!pullDown) {
                                if (kotlin.math.abs(dyRaw) > viewConfiguration.touchSlop) {
                                    if (dyRaw <= 0f) break
                                    pullDown = true
                                    // 越过 slop 时再问一次：起手在顶、但手势过程中列表
                                    // 被跟行/惯性带离顶部 → 本次不当下拉处理（防误触）。
                                    val stillAtTop = vm.lyricsAtTopNow?.invoke() ?: downAtTop
                                    if (downAtTop && stillAtTop) dragging = true
                                }
                            }
                            lastY = change.position.y
                        }
                        if (dragging) {
                            morph.onDrag(change.position.y - lastY)
                            lastY = change.position.y
                            change.consume()
                        }
                        if (!event.changes.any { it.pressed }) {
                            if (dragging) {
                                val velocity = tracker.calculateVelocity().y
                                val D = (morph.cardBounds?.top ?: morph.screenH * 0.2f)
                                    .coerceAtLeast(morph.screenH * 0.2f)
                                val dd = (1f - morph.progress.value) * D
                                val thresholdPx = 120.dp.toPx()
                                if (dd > thresholdPx || velocity > 2200f) {
                                    vm.collapseLyrics(velocity)
                                } else {
                                    scope.launch { morph.progress.animateTo(1f, LyricsMorphSpec.collapseSpring) }
                                }
                            } else if (morph.progress.value < 0.999f) {
                                scope.launch { morph.progress.animateTo(1f, LyricsMorphSpec.collapseSpring) }
                            }
                            break
                        }
                    }
                }
            } else Modifier)
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
                    // 需求1(2026-09-26 morph 内容呈现优化):内容层加等比 scale,
                    // 以窗口宽度为基准,让内容"填满窗口"而非"被裁剪遮挡"。
                    // clipRect 保留作为极端保险(p<0.65 时高度超出被裁),但主观感
                    // 是"内容跟着窗口缩小"。transformOrigin 中心缩放,与 translationY
                    // 锚行跟踪独立叠加(先 scale 内部缩、后 translationY 容器移)。
                    .graphicsLayer {
                        val pp = morph.progress.value
                        val b = morph.cardBounds
                        if (b != null) {
                            // 缩放:以窗口当前宽度为基准等比
                            val windowW = lerp01(b.width, size.width, pp)
                            val s = windowW / size.width  // 内容宽度缩放到等于窗口宽度
                            scaleX = s
                            scaleY = s
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
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
                    LyricsScreen(vm, interactive)
                }
            }
        }
    }
}

/** t 在 [a,b] 内的归一化进度(区间外 clamp 0/1)。 */
private fun fracRange(t: Float, a: Float, b: Float): Float =
    ((t - a) / (b - a).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

private fun lerp01(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

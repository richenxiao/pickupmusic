package com.shiyin.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.MainViewModel
import com.shiyin.music.ui.components.LyricsCardExpandPill
import com.shiyin.music.ui.components.LyricsCardHeaderRow
import com.shiyin.music.ui.components.LyricsCardPreview
import com.shiyin.music.ui.components.LyricsMorphSpec
import com.shiyin.music.ui.components.LyricsMorphState
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.rememberCoverPalette
import kotlinx.coroutines.launch

/**
 * v1.3.x morph 过渡 P1+P2(设计文档 §3.1/§3.3/§3.4):歌词本 morph overlay。
 *
 * P1:容器形变——全屏布局一次,graphicsLayer 延迟读 progress/cardBounds 四边
 *      线性插值(p=0 与卡片矩形重合/p=1 全屏),圆角 shape 可动画,背景同源取色。
 * P2:内容连续性(§3.4)——
 *   ①点击瞬间冻结快照(lines/peekStart/activeI/sourceLabel):动画期间预览不随
 *     播放推进变化,避免字在放大中途被换;
 *   ②分层交叉淡化(全部是 progress 的分段窗口函数,非独立动画):
 *     · 预览副本层:LyricsCardPreview 共享组件(P0 抽出),字号 19→26sp/行高
 *       28→36sp 组合层插值(每帧只重组 4 个 Text),PREVIEW_FADE_OUT 窗口淡出;
 *     · 列表层:真 LyricsScreen,alpha LIST_FADE_IN 窗口 0→1,进入动画前
 *       预滚到锚行位置;
 *     · chrome 层:顶栏/底栏由列表层携带,与列表同步淡入(歌词本自身的
 *       顶栏在 LyricsScreen 内,整体随列表层淡化)。
 *   ③锚行对齐:预览当前行与列表预滚后该行 item 的 Y 对齐(差值 scrollBy
 *      校正),交叉淡化区间内锚行视觉位移 ≤8dp(慢动作录屏验收)。
 *   ④播放页淡出:由 AppRoot 侧 PlayerScreen 的 graphicsLayer 做(P2 在
 *      LyricsScreen 挂 alpha 由 progress 驱动)——见 [playerDimModifier]。
 */
@Composable
fun LyricsMorphOverlay(vm: MainViewModel, morph: LyricsMorphState) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ── P2-① 快照冻结(§3.4-2):open 时冻结预览参数,动画期间不随播放变化 ──
    data class PreviewSnapshot(
        val lines: List<String>,
        val activeIndex: Int,
        val peekStart: Int,
        val sourceLabel: String?,
        val fgAlphaActive: Float,
    )
    val ly = vm.currentLyrics
    var snapshot by remember {
        mutableStateOf<PreviewSnapshot?>(null)
    }

    LaunchedEffect(Unit) {
        // 冻结再 open——快照给交叉淡化的预览副本层用
        val lines = ly?.parsed?.lines?.map { it.text } ?: emptyList()
        val durMs = if (vm.player.durationMs > 0) vm.player.durationMs else (vm.trackById(vm.player.currentId)?.durationMs ?: 0)
        val activeI = ly?.parsed?.activeIndex(vm.player.positionMs, durMs, ly.offsetMs) ?: -1
        val peekStart = (activeI - 1).coerceIn(0, (lines.size - 4).coerceAtLeast(0))
        snapshot = PreviewSnapshot(lines, activeI, peekStart, ly?.sourceLabel, 1f)
        morph.open(vm.lyricsCardBounds)
    }
    // ── 销毁:收起完成(§3.6-4 phase 门)──
    LaunchedEffect(Unit) {
        snapshotFlow { morph.phase == LyricsMorphState.Phase.Closing && !morph.isRunning && morph.progress.value == 0f }
            .collect { done -> if (done) { vm.lyricsOn = false; vm.lySheet = false } }
    }
    // P3:守卫(LyricsScreen nestedScroll)喂拖拽共用 morph.onDrag——
    // 屏高从 LocalConfiguration 取(onDrag 内部另有卡顶/屏高20%下限保护)。
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp *
        androidx.compose.ui.platform.LocalDensity.current.density
    SideEffect {
        vm.lyricsExitRequest = { scope.launch { morph.collapse(vm.lyricsCardBounds) } }
        vm.lyricsMorphDrag = { delta -> morph.onDrag(delta, screenH) }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (vm.lyricsExitRequest != null) vm.lyricsExitRequest = null
            if (vm.lyricsMorphDrag != null) vm.lyricsMorphDrag = null
            // P4 边界:progress 透传值必须随卸载归零——否则下次打开歌词本时
            // LyricsScreen 跟行暂停判定(0.001..0.949 区间)读到残留值会误判
            // "morph 进行中"而永久停跳跟行。
            vm.lyricsMorphProgress.floatValue = 0f
        }
    }

    val cornerBase = with(density) { 28.dp.toPx() }
    val track = vm.trackById(vm.player.currentId)
    val palette = rememberCoverPalette(track)
    val artBg = palette.first
    val artFg = palette.second
    val dimC = artFg.copy(alpha = 0.55f)
    val btnBg = artFg.copy(alpha = 0.14f)

    // ── P2-② 分层透明度(progress 纯函数,非独立动画)──
    val p = morph.progress.value
    // P2(§3.3-5):progress 透传给 PlayerScreen 内容淡出(它 graphicsLayer 延迟读)
    vm.lyricsMorphProgress.floatValue = p
    val previewAlpha = 1f - fracRange(p, LyricsMorphSpec.PREVIEW_FADE_OUT_START, LyricsMorphSpec.PREVIEW_FADE_OUT_END)
    val listAlpha = fracRange(p, LyricsMorphSpec.LIST_FADE_IN_START, LyricsMorphSpec.LIST_FADE_IN_END)

    // ── P2-③ 锚行预滚:列表层首次组合前滚到 activeI 对应位置(与跟行同公式),
    //    差值校正在 LyricsScreen 内 LaunchedEffect(listAlpha) 做(它持有 listState)──

    Box(
        Modifier
            .fillMaxSize()
            // ── P3-① 指针闸门(§3.5 主防御,必须做)──
            // 播放 sheet 的 anchoredDraggable 会对"sheet 正文任意位置"的下拉 1:1 跟手
            // (§1.2 结构性行为),nestedScroll pre 派发它先看到——靠 nestedScroll 防不
            // 住。必须在指针层拦截:过 touch slop 后判主轴,垂直向下且(列表到顶或
            // 起点在 chrome 区)→ 从该事件起消费整条手势流,事件根本到不了
            // LazyColumn 和 sheet 的 drag 检测;其余(向上/水平/列表未到顶向下)不
            // 消费,正常派发。chrome 区=顶栏/底栏(非列表区),由 LyricsScreen 经
            // VM 透传"该 y 是否 chrome"判定——简单版:y 在列表区外即 chrome。
            .pointerInput(Unit) {
                // 速度追踪:松手甩动判定用(velocityTracker 在 awaitEachGesture 内
                // 跨事件累计,松手时 calculateVelocity)
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var lastY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!dragging) {
                            // 过 slop 前不消费(down/移动都放行,点击/长按/滚动不受扰)
                            val dy = change.position.y - down.position.y
                            if (kotlin.math.abs(dy) > viewConfiguration.touchSlop) {
                                val isDown = dy > 0f
                                val inChrome = vm.lyricsListTopPx != null && (lastY < vm.lyricsListTopPx!! || lastY > vm.lyricsListBottomPx!!)
                                val atTop = vm.lyricsListAtTop
                                if (isDown && (inChrome || atTop)) dragging = true else break
                            }
                            lastY = change.position.y
                        }
                        if (dragging) {
                            // 整条手势流全消费:列表/sheet 都收不到
                            morph.onDrag(change.position.y - lastY, size.height.toFloat())
                            lastY = change.position.y
                            change.consume()
                        }
                        if (!event.changes.any { it.pressed }) {
                            // 松手:拖拽距离/速度判定(§3.5-3)
                            if (dragging) {
                                val velocity = tracker.calculateVelocity().y
                                val dd = (1f - morph.progress.value) * (morph.cardBounds?.top ?: 1f)
                                val thresholdPx = 120.dp.toPx()
                                if (dd > thresholdPx || velocity > 2200f) {
                                    scope.launch { morph.collapse(vm.lyricsCardBounds, velocity) }
                                } else {
                                    scope.launch { morph.progress.animateTo(1f, com.shiyin.music.ui.components.LyricsMorphSpec.collapseSpring) }
                                }
                            }
                            break
                        }
                    }
                }
            }
            .graphicsLayer {
                val b: Rect = morph.cardBounds ?: return@graphicsLayer
                val pp = morph.progress.value
                // bug② 修复:缩放原点钉在图层左上 (0,0)。translation 补偿公式
                // (卡片左上角位置)按左上锚推导,而 Compose graphicsLayer 默认
                // transformOrigin 是图层中心 (0.5,0.5)——不钉左上,收起时内容
                // 围绕屏幕中心"瘪缩"、平移补偿与缩放错位错切,且收完一帧与卡片
                // 不重合(设计文档 §3.3 的重合前提是左上原点)。
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                scaleX = lerp01(b.width / size.width, 1f, pp)
                scaleY = lerp01(b.height / size.height, 1f, pp)
                translationX = lerp01(b.left, 0f, pp)
                translationY = lerp01(b.top, 0f, pp)
                shape = RoundedCornerShape(lerp01(cornerBase, 0f, pp))
                clip = true
            }
            .background(artBg),
    ) {
        // ── 预览副本层(起点态=卡片,与卡片共享 LyricsCardPreview 组件)──
        // 结构与真卡片逐层一致:同内边距(20/18dp)/头行/预览/胶囊。字号+行高按
        // progress 组合层插值(每帧只重组 4 个 Text,§3.4-3 允许),起点 19/28sp
        // = 卡片,终点 26/36sp = 歌词本——交叉淡化到列表层后整层淡出。
        val snap = snapshot
        if (snap != null && snap.lines.isNotEmpty()) {
            val fontSp = lerp01(19f, 26f, p)
            val lineHSp = lerp01(28f, 36f, p)
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .graphicsLayer { alpha = previewAlpha },
            ) {
                LyricsCardHeaderRow(sourceLabel = snap.sourceLabel, dimC = dimC)
                LyricsCardPreview(
                    lines = snap.lines,
                    activeIndex = snap.activeIndex,
                    peekStart = snap.peekStart,
                    fg = artFg,
                    fontSize = fontSp.sp,
                    lineHeight = lineHSp.sp,
                )
                LyricsCardExpandPill(btnBg = btnBg, fg = artFg)
            }
        }
        // ── 列表层:真 LyricsScreen,LIST_FADE_IN 窗口淡入 ──
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = listAlpha },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                LyricsScreen(vm)
            }
        }
    }
}

/** t 在 [a,b] 内的归一化进度(区间外 clamp 0/1)。 */
private fun fracRange(t: Float, a: Float, b: Float): Float =
    ((t - a) / (b - a).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

private fun lerp01(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

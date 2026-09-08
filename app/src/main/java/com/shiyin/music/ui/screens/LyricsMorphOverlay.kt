package com.shiyin.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.shiyin.music.MainViewModel
import com.shiyin.music.ui.components.LyricsMorphState
import com.shiyin.music.ui.components.rememberCoverPalette

/**
 * v1.3.x morph 过渡 P1(设计文档 §3.1/§3.3):歌词本 morph overlay 宿主——
 * 挂在播放 sheet 内容 Box(同窗口,后组居上),lyricsOn=true 时组合。
 *
 * 容器形变(设计文档 §3.3,性能关键——一次布局,变换驱动):
 *  - 内容以全屏尺寸布局一次(LyricsScreen 恒定终点布局);
 *  - graphicsLayer 块内延迟读 progress/cardBounds 计算 scaleX/Y+translation
 *    (不触发重组);四条边随 p 线性移动,p=0 与卡片矩形逐像素重合、p=1 全屏
 *    (边缘线性自检公式见设计文档 §3.3-3);
 *  - 圆角 graphicsLayer{shape}(draw 属性,可动画),不走 shape 重排;
 *  - 背景 artBg 与卡片/歌词本同源取色(两端同色,无色相过渡需求)。
 *
 * p=0 图层与卡片重合但会盖住真卡片——收起完成即整体卸载(lyricsOn=false),
 * 卸载时机由 AppRoot 的销毁条件(phase==Closing && !isRunning && progress==0)
 * 驱动,见设计文档 §3.6-4。
 */
@Composable
fun LyricsMorphOverlay(vm: MainViewModel, morph: LyricsMorphState) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 展开:lyricsOn 置 true(点击卡片)即起——卡片 bounds 用最新上报值
    LaunchedEffect(Unit) {
        morph.open(vm.lyricsCardBounds)
    }
    // 销毁:收起完成(§3.6-4 phase 门防 open() 起点误判)
    LaunchedEffect(Unit) {
        snapshotFlow { morph.phase == LyricsMorphState.Phase.Closing && !morph.isRunning && morph.progress.value == 0f }
            .collect { done -> if (done) { vm.lyricsOn = false; vm.lySheet = false } }
    }
    // 退出回调重注入(物理返回键/左上返回/下拉共用,§3.6-3)
    androidx.compose.runtime.SideEffect {
        vm.lyricsExitRequest = {
            scope.launch { morph.collapse(vm.lyricsCardBounds) }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (vm.lyricsExitRequest != null) vm.lyricsExitRequest = null }
    }

    val cornerBase = with(density) { 28.dp.toPx() }
    // 背景与卡片/歌词本同源取色(rememberCoverPalette——同一 track 同一色,
    // 设计文档 §4 偏差4:两端本就同色,无色相过渡需求)
    val track = vm.trackById(vm.player.currentId)
    val artBg = com.shiyin.music.ui.components.rememberCoverPalette(track).first
    val fallbackBg = androidx.compose.ui.graphics.Color(0xFF2A2A2E)
    val bg = if (track != null) artBg else fallbackBg
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // §3.3-3 边缘线性:p=0 与卡片矩形逐像素重合,p=1 全屏
                val b: Rect = morph.cardBounds ?: return@graphicsLayer
                val p = morph.progress.value
                scaleX = lerp01(b.width / size.width, 1f, p)
                scaleY = lerp01(b.height / size.height, 1f, p)
                translationX = lerp01(b.left, 0f, p)
                translationY = lerp01(b.top, 0f, p)
                shape = RoundedCornerShape(lerp01(cornerBase, 0f, p))
                clip = true
            }
            .background(bg),
    ) {
        // 内容:全屏尺寸布局一次;系统栏避让由这层继承原 Dialog 包装层职责
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

private fun lerp01(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

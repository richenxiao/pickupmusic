package com.shiyin.music.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow as boundsInWindowExt
import androidx.compose.ui.platform.LocalContext
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.LyState
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.formatDuration
import com.shiyin.music.ui.components.LyricsMorphSpec
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.coverPalette
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.rememberAlbumArt
import com.shiyin.music.ui.components.rememberCoverPalette
import com.shiyin.music.ui.components.shadowLg
import com.shiyin.music.ui.components.shadowMd
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.Caprasimo
import com.shiyin.music.ui.theme.LocalOrganic
import androidx.media3.common.Player as XPlayer
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    // v5.2 Bug1: Activity context for SystemOutputSwitcherDialogController.showDialog —
    // must be captured in the composable body (the clickable lambda isn't a composable
    // scope), and must be the Activity context (LocalContext in Compose = the host
    // Activity), not a Service/Application context. showDialog fires system intents.
    val ctx = LocalContext.current
    val track = vm.trackById(vm.player.currentId) ?: return
    // v3.0: real album-art tint (Spotify-style). Falls back to the fixed
    // palette until/in case there's no cover; cross-fades to the album's actual
    // colors once the bitmap is loaded, with a short tween so the transition
    // never snaps. This replaces the always-on green/orange default block — the
    // page now adopts the cover's mood, and drops the fixed background entirely.
    val raw = rememberCoverPalette(track)
    val artBg by androidx.compose.animation.animateColorAsState(raw.first, androidx.compose.animation.core.tween(320), label = "pbg")
    val artFg by androidx.compose.animation.animateColorAsState(raw.second, androidx.compose.animation.core.tween(320), label = "pfg")
    val dimC = artFg.copy(alpha = 0.55f)
    val btnBg = artFg.copy(alpha = 0.14f)

    LaunchedEffect(vm.player.currentId, vm.playerOpen) {
        android.util.Log.d("LyricsLoadDebug", "LaunchedEffect fired currentId=${vm.player.currentId} playerOpen=${vm.playerOpen}")
        if (vm.playerOpen) vm.loadLyricsFor(track.id)
    }

    // v22: 显示坐标 —— 拆分模式下进度条/时间要显示"当前这首"的区间，而不是整轨文件
    // （45 分钟）。非拆分模式下 displayDurationMs() 就是 player.durationMs，行为不变。
    val durMs = if (vm.player.displayDurationMs() > 0) vm.player.displayDurationMs() else track.durationMs
    // v1.3.x 帧率:pct 读 positionMs,原写在函数体顶层→整播放页每 300ms 重组
    // (morph 动画期间叠加=Slow UI 帧)。移进下方 Column 内容层(进度条消费点),
    // 重组作用域收窄到滚动内容 lambda;cover/transport 等可跳过子节点不重跑。

    // v5.2 Bug6: the pull-down-to-dismiss gesture is now owned by the
    // ModalBottomSheet wrapper (AppRoot) via Material3's Swipeable
    // NestedScrollConnection. This PlayerScreen no longer needs its own
    // dragOffset / dismissThreshold / detectVerticalDragGestures — removing
    // the hand-rolled gesture arbitration that previously caused "drag area
    // only at the top" + "jumps after threshold instead of follow finger".
    // The sheet body's own verticalScroll now flows through the standard
    // Modifier.nestedScroll connection Material3 wires up, so swiping up
    // scrolls the lyrics and swiping down (from the top) dismisses the sheet.
    //
    // v5.2 hotfix: TWO nested verticalScrolls on the same scrollState was the
    // regression that made the player sheet unresponsive — the outer Column
    // had a .verticalScroll(scrollState) AND the inner content Column also
    // had one, sharing the same `scrollState`. Compose does not support that
    // (the outer scroll swallows all pointer events before they reach the
    // inner one, leaving the inner list visually frozen and the sheet unable
    // to transition). Removed the outer scroll so only the inner content
    // Column scrolls — matching the v5.1 structure.
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            // v1.3.x morph P2(§3.3-5):morph 展开/收起期间播放页内容淡出
            // 1→0.3(只淡出不滑出,§4 偏差5)。graphicsLayer 块内延迟读 VM 透传的
            // progress,不触发重组;progress==0(歌词本关)恒为 alpha 1。
            .graphicsLayer {
                val mp = vm.morph.progress.value
                alpha = 1f - 0.7f * ((mp / LyricsMorphSpec.PLAYER_DIM_END).coerceIn(0f, 1f))
            }
            .background(c.bg)
            // v5.2: sheet 的 contentWindowInsets 已置零，系统栏由这里处理。background 在前
            // →背景连续填充到屏幕边缘（含状态栏/导航栏区域），不再有 sheet 单独留的底部
            // 背景空白带；padding 只把内容从系统栏移开，不产生单独覆盖层。
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            // v1.3.x 头行收紧:18→6——状态栏 inset 之外还留一截空白,用户反馈
            // "整体往上提一点,更紧凑一些"。只动这一处:专辑封面等其余组件
            // 位置不改(第一轮误改封面 padding 后按用户要求精确回滚 16/22)。
            // 头行 Row fillMaxWidth 无独立左右 padding,左右边缘天然与外层
            // 24dp 的内容区对齐。
            .padding(top = 6.dp),
    ) {
        // top bar — back / spacer / menu. v5.2 Bug6: no drag-handle strip
        // above this row; the row itself is also the visual top of the sheet
        // (the ModalBottomSheet dragHandle=null slot is empty).
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.surface)
                    .clickable { vm.playerOpen = false; vm.sleepMenu = false },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.ChevronDown, 19.dp, c.text) }
            Text(
                "·",
                style = body(12.5f, FontWeight.Normal, c.n400).copy(letterSpacing = 1.5.sp),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.surface)
                    .clickable { vm.pMenuView = "root" },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.MoreVertical, 18.dp, c.text) }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scrollState),
        ) {
            // cover — fill width, no background decoration
            // v3.0: removed the square aspectRatio container + ContentScale.Fit
            // that left gaps showing the page bg behind the cover. Now the image
            // fills the width directly with no background behind it.
            val art = rememberAlbumArt(track, 480.dp)
            if (art != null) {
                Image(
                    art, null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 22.dp)
                        .shadowLg(RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { vm.goAlbumOf(track.id, highlight = false) },
                    contentScale = ContentScale.FillWidth,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(top = 16.dp, bottom = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .shadowLg(RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(240.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = 60.dp, y = (-76).dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f))
                        )
                        Box(
                            Modifier
                                .size(170.dp)
                                .align(Alignment.BottomStart)
                                .offset(x = (-42).dp, y = 52.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.10f))
                        )
                        Text(
                            track.initial,
                            fontFamily = Caprasimo,
                            style = body(110f, FontWeight.Normal, artFg).copy(fontFamily = Caprasimo),
                        )
                        Text(
                            track.album,
                            style = body(12f, FontWeight.Bold, artFg.copy(alpha = 0.75f)).copy(letterSpacing = 1.sp),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 18.dp, bottom = 14.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // title block (v1.1: title -> album w/ highlight, artist -> artist page; v1.7: circle fav)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        track.title,
                        style = heading(24),
                        maxLines = 1,
                        // v5.2 #66: 长歌名跑马灯——停 6s 后循环滚出未显示部分（火车过隧道）。
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.goAlbumOf(track.id, highlight = true) }
                            .basicMarquee(initialDelayMillis = 6000, repeatDelayMillis = 6000),
                    )
                    Text(
                        track.artist,
                        style = body(14.5f, FontWeight.Normal, c.n600),
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { vm.goArtistOf(track.id) }
                            .basicMarquee(initialDelayMillis = 6000, repeatDelayMillis = 6000),
                    )
                }
                // v22: 收藏按钮的「加号变勾号」动画需要一个 interactionSource 才能拿到按下态
                // （按下轻微缩小、松手弹回）。同一个 source 同时交给点击处理与 FavIcon。
                val favSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            interactionSource = favSrc,
                            // 传 LocalIndication 保持原有的涟漪反馈不变 ——
                            // 这个重载里 indication 是必填参数（没有默认值）。
                            indication = androidx.compose.foundation.LocalIndication.current,
                            onClick = { vm.toggleFav(track.id) },
                            onLongClick = { vm.saveSheetFor = track.id },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    com.shiyin.music.ui.components.FavIcon(
                        vm.isFav(track.id), 26.dp, c.n500, interactionSource = favSrc,
                    )
                }
            }

            // progress
            // v5.3: 进度条支持拖动 scrub。原实现只有 detectTapGestures(点按跳转)，
            // 按住拖动滑块无任何响应。用 awaitEachGesture 统一处理 按下→拖动→抬起:
            // 按下/拖动期间把 scrubFraction 覆盖到手指位置(让滑块实时跟手),抬起时
            // commit 一次 seekToFraction。scrub 期间忽略播放器 ticker 写回的
            // positionMs,避免滑块被每帧拉回播放位置。
            var scrubFraction by remember { mutableStateOf<Float?>(null) }
            val pct = if (durMs > 0) (vm.player.displayPositionMs().toFloat() / durMs).coerceIn(0f, 1f) else 0f
            val shownPct = scrubFraction ?: pct
            val shownPosMs = (scrubFraction?.let { it * durMs } ?: vm.player.displayPositionMs()).toLong()
            Box(
                Modifier
                    .fillMaxWidth()
                    // 第6轮:触摸范围=条本身显示范围(6dp)。原 12dp 隐形触区误触
                    // (用户实测)。滑块 15dp 用 requiredSize 强制保留原尺寸——
                    // 注意 `Modifier.size()` 是 enforceIncoming=true,会被这里的
                    // 6dp 高度约束压成 15×6 的扁椭圆(Box 的"不裁剪"只管绘制、
                    // 不改约束),必须用 requiredSize 才能真溢出。
                    .height(6.dp)  // v1.3.3: 22dp→12dp;第6轮: 12dp→6dp(=条高)
                    .pointerInput(durMs) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull()?.let { change ->
                                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                                    scrubFraction = f
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            // 抬手:提交一次 seek 并结束 scrub,bar 回到播放器真实位置。
                            // v22: 用显示坐标提交 —— 拆分模式下把"当前曲目内的比例"映射
                            // 回整轨文件坐标，否则拖进度条会跳到错误的位置。
                            scrubFraction?.let { vm.player.seekToDisplayFraction(it) }
                            scrubFraction = null
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c.n200)
                )
                Box(
                    Modifier
                        .fillMaxWidth(shownPct)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c.accent)
                )
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .offset(x = (maxWidth - 15.dp) * shownPct)
                            // requiredSize: 绕过父级 6dp 高度约束,保住 15dp 圆形滑块
                            // (size() 会被约束压成 15×6 扁椭圆)。命中测试沿祖先链,
                            // 溢出部分仍可被上面的 pointerInput 命中,触区不受影响。
                            .requiredSize(15.dp)
                            .clip(CircleShape)
                            .background(c.accent)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(shownPosMs / 1000), style = body(12f, FontWeight.Normal, c.n600))
                Text(formatDuration(durMs / 1000), style = body(12f, FontWeight.Normal, c.n600))
            }

            // transport controls
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable { vm.player.toggleShuffle() },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.Shuffle, 19.dp, if (vm.player.shuffleFlag) c.a700 else c.n500) }
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .clickable { vm.player.prev() },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.SkipBack, 26.dp, c.text) }
                Box(
                    Modifier
                        .size(74.dp)
                        .shadowMd(CircleShape)
                        .clip(CircleShape)
                        .background(c.accent)
                        .clickable { vm.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    OIcon(
                        if (vm.player.isPlaying) Lucide.Pause else Lucide.Play,
                        28.dp, Color.White,
                        modifier = if (vm.player.isPlaying) Modifier else Modifier.offset(x = 2.dp),
                    )
                }
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .clickable { vm.player.next() },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.SkipForward, 26.dp, c.text) }
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .clickable { vm.player.cycleRepeat() },
                    contentAlignment = Alignment.Center,
                ) {
                    OIcon(
                        Lucide.Repeat, 19.dp,
                        if (vm.player.repeatMode != XPlayer.REPEAT_MODE_OFF) c.a700 else c.n500,
                    )
                    if (vm.player.repeatMode == XPlayer.REPEAT_MODE_ONE) {
                        Text(
                            "1",
                            style = body(9f, FontWeight.ExtraBold, c.a700),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }

            // v1.5 Spotify-style bottom row: playback device (left) / queue (right)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // v1.2.0 #9: 不再显示当前设备状态——切换后状态刷新不可靠
                // （getAudioDevicesForAttributes 在部分 ROM 返回候选而非活跃设备，
                // prefer-非手机 会一直指 BT；延迟重读也救不回来）。改为恒显 Speaker
                // 图标 + 调起系统 Output Switcher（与 MiniPlayer 一致），设备状态由
                // 系统弹框自身展示。
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { SystemOutputSwitcherDialogController.showDialog(ctx) }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OIcon(Lucide.Speaker, 18.dp, c.n600)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { vm.qSheetOpen = !vm.qSheetOpen; vm.qEdit = false }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.ListQueue, 18.dp, c.n600) }
            }

            LyricsPeekCard(vm, artBg, artFg, dimC, btnBg)
        }
    }
}

@Composable
private fun LyricsPeekCard(vm: MainViewModel, artBg: Color, artFg: Color, dimC: Color, btnBg: Color) {
    val c = LocalOrganic.current
    val track = vm.trackById(vm.player.currentId) ?: return
    val ly = vm.currentLyrics
    val lines = ly?.parsed?.lines ?: emptyList()
    val context = LocalContext.current
    val pickTarget = androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "导入.lrc"
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (text != null) vm.importLrcContent(name, text, pickTarget.longValue)
            } catch (_: Exception) {
            }
        }
    }
    val launchPicker = {
        pickTarget.longValue = track.id
        lrcPicker.launch(arrayOf("*/*"))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 28.dp)
            .shadowMd(RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(artBg)
            // C3: 歌词本打开期间隐藏卡片内容(系统启动动画隐喻:app 打开后图标位置
            // 变空,直到动画回到位置才显示图标)。alpha 是 progress 的连续纯函数(非
            // 二值开关),与 overlay 容器 alpha(fracRange(pp,0,0.08))交叉淡化,消除
            // "同帧切换"风险。mp=0 alpha=1(可见); mp≥0.05 alpha=0(隐藏)。两个 alpha
            // 之和恒>0.4,无空帧闪烁。收起中途打断(弹回)时 alpha 跟 progress 自动回退。
            // 放在 .background 之后:背景(artBg)不受 alpha 影响,卡片矩形始终在位
            // 作占位;只内容(HeaderRow/Preview/Pill)被淡化。
            .graphicsLayer {
                alpha = 1f - (vm.morph.progress.value / 0.05f).coerceIn(0f, 1f)
            }
            // v1.3.x morph P0(设计文档 §3.3-2):卡片 window 坐标 Rect 持续上报——
            // morph overlay 展开起点/收起终点取卡片实时位置(播放页滚动、切歌后
            // bounds 变化都实时跟随)。P0 只上报无消费者,行为零变化。
            .onGloballyPositioned { coords ->
                vm.lyricsCardBounds = coords.boundsInWindowExt()
            }
            // v1.3.x morph P4(§2.6 连点防抖):已开着(含 morph 进行中)不重复置位
            .clickable { if (lines.isNotEmpty() && !vm.lyricsOn) vm.lyricsOn = true }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        com.shiyin.music.ui.components.LyricsCardHeaderRow(
            sourceLabel = if (ly != null) ly.sourceLabel else null,
            dimC = dimC,
        )
        if (lines.isNotEmpty() && ly != null) {
            val durMs = if (vm.player.durationMs > 0) vm.player.durationMs else track.durationMs
            val activeI = ly.parsed.activeIndex(vm.player.positionMs, durMs, ly.offsetMs)
            val peekStart = (activeI - 1).coerceIn(0, (lines.size - 4).coerceAtLeast(0))
            com.shiyin.music.ui.components.LyricsCardPreview(
                lines = lines.map { it.text },
                activeIndex = activeI,
                peekStart = peekStart,
                fg = artFg,
                fontSize = 19.sp,
                lineHeight = 28.sp,
            )
            com.shiyin.music.ui.components.LyricsCardExpandPill(btnBg = btnBg, fg = artFg)
        } else {
            when (vm.lyState) {
                LyState.Searching -> {
                    Row(
                        Modifier.padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val t = rememberInfiniteTransition(label = "lspin")
                        val angle by t.animateFloat(
                            0f, 360f,
                            infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
                            label = "langle",
                        )
                        androidx.compose.foundation.Canvas(Modifier.size(26.dp).rotate(angle)) {
                            val sw = 3.dp.toPx()
                            drawCircle(color = btnBg, style = Stroke(sw), radius = (size.minDimension - sw) / 2)
                            drawArc(
                                color = artFg, startAngle = -90f, sweepAngle = 90f, useCenter = false,
                                style = Stroke(sw, cap = StrokeCap.Round),
                                topLeft = Offset(sw / 2, sw / 2),
                                size = Size(size.width - sw, size.height - sw),
                            )
                        }
                        Column {
                            // v1.3.x: agent websearch 两阶段实时进度(联网搜索→AI 生成);
                            // 普通匹配 progressText 为 null → 默认文案。
                            Text(vm.lyProgressText ?: "正在匹配歌词…", style = body(14f, FontWeight.ExtraBold, artFg))
                            Text(
                                "${track.title} · ${track.artist} · ${formatDuration(track.durationSec)}",
                                style = body(11.5f, FontWeight.Normal, dimC),
                                modifier = Modifier.padding(top = 2.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // v1.3.x: 长耗时匹配(agent websearch,10-60 秒)提供取消——
                        // 等待期间随时止损,不让用户干等。
                        if (vm.lyProgressText != null) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(btnBg)
                                    .clickable { vm.cancelLyricsSearch() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) { Text("取消", style = body(12.5f, FontWeight.Bold, artFg)) }
                        }
                    }
                }
                LyState.Failed -> {
                    // v1.3.x: 匹配失败原因透传(agent websearch 的 HTTP 429/网络异常/未配置等);
                    // 普通匹配无原因 → 默认"未找到"文案 + 录音类提示。
                    Text(vm.lyFailReason ?: "未找到匹配的歌词", style = body(16f, FontWeight.ExtraBold, artFg), modifier = Modifier.padding(top = 2.dp))
                    if (vm.lyFailReason == null) {
                        Text(
                            "录音、语音类文件通常没有歌词条目，可手动导入 .lrc",
                            style = body(12.5f, FontWeight.Normal, dimC).copy(lineHeight = 18.sp),
                        )
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(btnBg)
                                .clickable { vm.lyricsSourcePicker = true }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) { Text("切换歌词来源", style = body(12.5f, FontWeight.Bold, artFg)) }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(btnBg)
                                .clickable { launchPicker() }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) { Text("导入 .lrc 文件", style = body(12.5f, FontWeight.Bold, artFg)) }
                    }
                }
                LyState.Idle -> {
                    Text("这首歌暂无歌词", style = body(16f, FontWeight.ExtraBold, artFg), modifier = Modifier.padding(top = 2.dp))
                    Text(
                        "按「歌名 + 歌手 + 时长」在线匹配，或导入同名 .lrc 文件",
                        style = body(12.5f, FontWeight.Normal, dimC).copy(lineHeight = 18.sp),
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(artFg)
                                .clickable { vm.matchLyricsManually() }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) { Text("在线匹配歌词", style = body(12.5f, FontWeight.Bold, artBg)) }
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(btnBg)
                                .clickable { launchPicker() }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) { Text("导入 .lrc", style = body(12.5f, FontWeight.Bold, artFg)) }
                    }
                }
            }
        }
    }
}

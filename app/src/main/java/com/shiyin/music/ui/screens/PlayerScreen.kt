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

    val durMs = if (vm.player.durationMs > 0) vm.player.durationMs else track.durationMs
    val pct = if (durMs > 0) (vm.player.positionMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

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
            .background(c.bg)
            // v5.2: sheet 的 contentWindowInsets 已置零，系统栏由这里处理。background 在前
            // →背景连续填充到屏幕边缘（含状态栏/导航栏区域），不再有 sheet 单独留的底部
            // 背景空白带；padding 只把内容从系统栏移开，不产生单独覆盖层。
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 18.dp),
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
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { vm.toggleFav(track.id) },
                    contentAlignment = Alignment.Center,
                ) { com.shiyin.music.ui.components.FavIcon(vm.isFav(track.id), 26.dp, c.n500) }
            }

            // progress
            // v5.3: 进度条支持拖动 scrub。原实现只有 detectTapGestures(点按跳转)，
            // 按住拖动滑块无任何响应。用 awaitEachGesture 统一处理 按下→拖动→抬起:
            // 按下/拖动期间把 scrubFraction 覆盖到手指位置(让滑块实时跟手),抬起时
            // commit 一次 seekToFraction。scrub 期间忽略播放器 ticker 写回的
            // positionMs,避免滑块被每帧拉回播放位置。
            var scrubFraction by remember { mutableStateOf<Float?>(null) }
            val shownPct = scrubFraction ?: pct
            val shownPosMs = (scrubFraction?.let { it * durMs } ?: vm.player.positionMs).toLong()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
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
                            scrubFraction?.let { vm.player.seekToFraction(it) }
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
                            .size(15.dp)
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
                val activeKind = vm.activeDeviceKind()
                val onRemote = activeKind != "phone"
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        // v5.2 Bug1: 主路径——直接调起 Android 系统媒体输出选择器
                        // (SystemOutputSwitcherDialogController)。系统弹框列出全部真实
                        // 输出设备并由系统执行真实音频路由，兼容 ColorOS 等 OEM；
                        // 不再走 app 层 setPreferredDevice（在定制 ROM 上不生效），
                        // 也不再套我们自己的设备 Sheet。状态展示（图标/设备名）仍由
                        // DeviceRouter 提供。
                        .clickable { SystemOutputSwitcherDialogController.showDialog(ctx) }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Icon tracks the actual active output: phone / wired headset /
                    // headphone / bluetooth device / TV.
                    OIcon(
                        when (activeKind) {
                            "headphone" -> Lucide.Headphones
                            "bluetooth" -> Lucide.Bluetooth
                            "tv" -> Lucide.Tv
                            else -> Lucide.Speaker
                        },
                        18.dp, if (onRemote) c.s700 else c.n600,
                    )
                    if (onRemote) {
                        Text(vm.curDeviceName(), style = body(12f, FontWeight.Bold, c.s700), maxLines = 1)
                    }
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
            .clickable { if (lines.isNotEmpty()) vm.lyricsOn = true }
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("歌词", style = body(11.5f, FontWeight.ExtraBold, dimC).copy(letterSpacing = 1.8.sp))
            if (ly != null) {
                Text(ly.sourceLabel, style = body(11f, FontWeight.Bold, dimC))
            }
        }
        if (lines.isNotEmpty() && ly != null) {
            val durMs = if (vm.player.durationMs > 0) vm.player.durationMs else track.durationMs
            val activeI = ly.parsed.activeIndex(vm.player.positionMs, durMs, ly.offsetMs)
            val peekStart = (activeI - 1).coerceIn(0, (lines.size - 4).coerceAtLeast(0))
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lines.drop(peekStart).take(4).forEachIndexed { j, line ->
                    val isActive = peekStart + j == activeI
                    Text(
                        line.text,
                        style = body(19f, FontWeight.ExtraBold, if (isActive) artFg else artFg.copy(alpha = 0.42f))
                            .copy(lineHeight = 28.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(btnBg)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("展开歌词", style = body(12.5f, FontWeight.Bold, artFg)) }
            }
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
                            Text("正在匹配歌词…", style = body(14f, FontWeight.ExtraBold, artFg))
                            Text(
                                "${track.title} · ${track.artist} · ${formatDuration(track.durationSec)}",
                                style = body(11.5f, FontWeight.Normal, dimC),
                                modifier = Modifier.padding(top = 2.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                LyState.Failed -> {
                    Text("未找到匹配的歌词", style = body(16f, FontWeight.ExtraBold, artFg), modifier = Modifier.padding(top = 2.dp))
                    Text(
                        "录音、语音类文件通常没有歌词条目，可手动导入 .lrc",
                        style = body(12.5f, FontWeight.Normal, dimC).copy(lineHeight = 18.sp),
                    )
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

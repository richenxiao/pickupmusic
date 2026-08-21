package com.shiyin.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.formatDuration
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.RubySegment
import com.shiyin.music.ui.components.RubyText
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.coverPalette
import com.shiyin.music.ui.components.rememberCoverPalette
import com.shiyin.music.ui.components.shadowMd
import com.shiyin.music.ui.icons.Lucide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LyricsScreen(vm: MainViewModel) {
    val track = vm.trackById(vm.player.currentId) ?: return
    val ly = vm.currentLyrics ?: return
    // v3.3: adaptive-contrast tint. The extractor returns the cover's main
    // colour as `bg` *and* an `fg` chosen so white-on-dark or dark-on-light
    // whichever keeps ≥4.5:1 — light covers keep their light bg and use dark
    // text instead of being force-darkened. This block:
    //   1. adopts bg/fg from the cover mood, not the old fixed pastel block;
    //   2. renders the active line in `fg`, inactive lines dimmer;
    //   3. falls back to a dark neutral until/unless extraction resolves.
    val raw = rememberCoverPalette(track)
    val fallbackPair = coverPalette(track.paletteIndex)
    val isResolved = raw != fallbackPair
    val artBg = if (isResolved) raw.first else Color(0xFF2A2A2E)
    val LyricText = if (isResolved) raw.second else Color.White
    androidx.compose.runtime.LaunchedEffect(track.id, isResolved) {
        android.util.Log.d("PaletteTrace", "LyricsScreen track=${track.id} albumId=${track.albumId} resolved=$isResolved artBg=$artBg fg=$LyricText")
    }
    val dimC = LyricText.copy(alpha = 0.55f)
    val artFg = LyricText // fg end (white on dark / dark-grey on light) — paint everywhere
    val btnBg = LyricText.copy(alpha = 0.14f)
    val inactiveC = LyricText.copy(alpha = 0.34f) // clearly dimmer than the active line
    val safeBg = artBg // bg colour, named for backward-compat with the button/dialog uses below
    val lines = ly.parsed.lines
    val durMs = if (vm.player.durationMs > 0) vm.player.durationMs else track.durationMs
    val activeI = ly.parsed.activeIndex(vm.player.positionMs, durMs, ly.offsetMs)
    val unsynced = !ly.parsed.synced

    // v1.1.0: 振假名（furigana）。仅对日文歌词生效——以「是否存在平/片假名」
    // 判定（中文无假名，可区分）。开关默认关闭，只影响本全屏页，不影响播放页
    // 迷你预览条。分词在后台线程 eagerly 进行（Kuromoji 冷启动 ~0.5s），开关
    // 切换不重复分词，只切换渲染分支。
    val hasKana = remember(lines) {
        lines.any { line -> line.text.any { ch -> ch.code in 0x3040..0x30FF } }
    }
    var furiganaOn by remember { mutableStateOf(false) }
    // V1.1: 跑准确率流水线（Song Override>纠错表>JMdict>Kuromoji>No Reading）。
    // key 含 furiganaRevision，保存/删除 Song Override 后 bump 即重算该曲。
    val segmentsByLine by produceState<List<List<RubySegment>>?>(initialValue = null, ly, vm.furiganaRevision) {
        if (!hasKana) { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.Default) {
            vm.furiganaSegmentsFor(ly)
        }
    }
    // V1.1: 长按编辑 Song Override 入口（最小可用）
    var pickerLine by remember { mutableStateOf<Int?>(null) }
    // (lineIndex, segment, currentReading)：按出现位置存，同 surface 多次出现可分别设
    var editing by remember { mutableStateOf<Triple<Int, RubySegment, String>?>(null) }

    Box(Modifier
        .fillMaxSize()
        .background(safeBg)
    ) {
    Column {
        // Top bar — three-zone layout (back | title/artist centered | settings+close)
        // mirroring the player's top row so the visual hierarchy matches the
        // page behind. The lyric page previously had nothing on the left
        // (only settings+close on the right), which left the top corner feeling
        // empty — re-adding the centered title + artist fixes that without
        // crowding the lyric body.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(btnBg)
                    .clickable { vm.lyricsOn = false; vm.lySheet = false },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.ChevronDown, 19.dp, artFg) }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    track.title,
                    style = body(15f, FontWeight.ExtraBold, artFg),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    track.artist,
                    style = body(12f, FontWeight.SemiBold, dimC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ly.canAdjust) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(btnBg)
                        .clickable { vm.lySheet = !vm.lySheet },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.Sliders, 16.dp, artFg) }
            }
        }

        // lyric lines, auto-centered on the active line
        val listState = rememberLazyListState()
        LaunchedEffect(activeI) {
            if (activeI >= 0) {
                val viewport = listState.layoutInfo.viewportSize.height
                listState.animateScrollToItem(activeI, scrollOffset = -(viewport / 2 - 60))
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.85f to Color.Black,
                            1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .padding(horizontal = 22.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 150.dp),
        ) {
            itemsIndexed(lines) { i, line ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = {
                                val target = line.timeMs?.let { (it - ly.offsetMs).coerceAtLeast(0) }
                                    ?: (i.toLong() * durMs / lines.size.coerceAtLeast(1))
                                vm.player.seekToMs(target)
                            },
                            onLongClick = {
                                // V1.1: 长按带振假名的行 → 打开该行注音段选择器（编辑 Song Override）
                                if (furiganaOn && hasKana && segmentsByLine != null) pickerLine = i
                            },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val lineColor = if (i <= activeI) artFg else inactiveC
                    val rubySegs = segmentsByLine?.getOrElse(i) { null }
                    if (furiganaOn && hasKana && rubySegs != null) {
                        RubyText(
                            text = line.text,
                            segments = rubySegs,
                            style = body(26f, FontWeight.ExtraBold, lineColor)
                                .copy(lineHeight = 36.sp),
                            color = lineColor,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            line.text,
                            style = body(26f, FontWeight.ExtraBold, lineColor)
                                .copy(lineHeight = 36.sp),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // v2.0: per-line sync button for unsynced lyrics
                    if (unsynced) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(btnBg)
                                .clickable { vm.syncLyricLine(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            OIcon(Lucide.Clock, 14.dp, dimC)
                        }
                    }
                }
            }
        }

        // bottom bar: slim progress + time row + play/pause.
        // Order per v4.3 spec: progress bar on top, time row hugging it just
        // below (not at the very bottom), play/pause centered between the time
        // row and the screen bottom.
        Column(
            Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val pct = if (durMs > 0) (vm.player.positionMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
            // v5.3: 进度条支持拖动 scrub(与 PlayerScreen 同一逻辑)。原只有
            // detectTapGestures 点按跳转,按住拖动无响应。按下/拖动期间 scrubFraction
            // 覆盖到手指位置,抬手时 commit 一次 seek,期间忽略 ticker 写回。
            var scrubFraction by remember { mutableStateOf<Float?>(null) }
            val shownPct = scrubFraction ?: pct
            val shownPosMs = (scrubFraction?.let { it * durMs } ?: vm.player.positionMs).toLong()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
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
                            scrubFraction?.let { vm.player.seekToFraction(it) }
                            scrubFraction = null
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(btnBg)
                )
                Box(
                    Modifier
                        .fillMaxWidth(shownPct)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(artFg)
                )
            }
            // v4.3: time numbers hug the progress bar directly beneath it.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatDuration(shownPosMs / 1000),
                    style = body(11.5f, FontWeight.Bold, dimC),
                    modifier = Modifier.width(44.dp),
                )
                Text(
                    formatDuration(durMs / 1000),
                    style = body(11.5f, FontWeight.Bold, dimC),
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.End,
                )
            }
            // v1.1.0: 播放键同行左侧放「あ」振假名开关（仅日文歌词显示）。
            // 右侧放等宽占位平衡，使播放键始终居中。开关只影响本全屏页渲染。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasKana) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (furiganaOn) artFg else btnBg)
                            .clickable { furiganaOn = !furiganaOn },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "あ",
                            style = body(18f, FontWeight.ExtraBold, if (furiganaOn) safeBg else artFg),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // play/pause centered between the time row and the screen bottom.
                Box(
                    Modifier
                        .size(58.dp)
                        .shadowMd(CircleShape)
                        .clip(CircleShape)
                        .background(artFg)
                        .clickable { vm.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    OIcon(
                        if (vm.player.isPlaying) Lucide.Pause else Lucide.Play,
                        22.dp, safeBg,
                        modifier = if (vm.player.isPlaying) Modifier else Modifier.padding(start = 3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                // 右侧平衡占位，与左侧「あ」等宽，保证播放键视觉居中
                if (hasKana) { Spacer(Modifier.size(38.dp)) }
            }
            // breathing room so the play button sits above the bottom edge.
            Spacer(Modifier.height(4.dp))
        }
    }
    // Bug4c: render the adjust sheet as an overlay ON TOP of the lyrics
    // column. It slides down from above with a tween, full-screen scrim
    // catches outside taps to dismiss.
    if (vm.lySheet && ly.canAdjust) {
        LyricsAdjustSheet(
            onDismiss = { vm.lySheet = false },
            artFg = artFg,
            dimC = dimC,
            btnBg = btnBg,
            safeBg = safeBg,
            ly = ly,
            deepseekKey = vm.deepseekApiKey,
            onBumpOffset = { vm.bumpLyricsOffset(it) },
            onOpenSourcePicker = { vm.lyricsSourcePicker = true },
            onRematchAI = { vm.rematchWithAI() },
            onFetchExternalReading = { vm.fetchExternalReadingEvidence() },
            externalFetchStatus = vm.externalFetchStatus,
            onSave = { vm.saveLyrics() },
            onDelete = { vm.deleteLyrics() },
        )
    }
    // V1.1: 长按编辑 Song Override —— 行注音段选择器
    // 对话框用与歌词页同源的暗色 colorScheme（封面取色 safeBg/artFg），避免 Material3
    // 默认亮色方案在暗色歌词页上"亮得刺眼"。
    val dialogScheme = androidx.compose.material3.darkColorScheme(
        surface = safeBg, background = safeBg,
        surfaceContainer = safeBg, surfaceContainerHigh = safeBg,
        onSurface = artFg, onBackground = artFg,
        onSurfaceVariant = dimC, surfaceVariant = btnBg,
        primary = artFg, onPrimary = safeBg, outline = btnBg,
    )
    pickerLine?.let { idx ->
        val segs = segmentsByLine?.getOrNull(idx) ?: emptyList()
        val editable = segs.filter { it.surface.any { ch -> ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0xF900..0xFAFF } }
        androidx.compose.material3.MaterialTheme(colorScheme = dialogScheme) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pickerLine = null },
                title = { Text("选择要修正的词", style = body(14f, FontWeight.ExtraBold, artFg)) },
                text = {
                    if (editable.isEmpty()) {
                        Text("这行没有可注音的汉字。", style = body(12f, FontWeight.Normal, dimC))
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            editable.forEach { seg ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        editing = Triple(idx, seg, seg.reading ?: "")
                                        pickerLine = null
                                    }.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(seg.surface, style = body(15f, FontWeight.ExtraBold, artFg))
                                    Text(
                                        seg.reading?.ifEmpty { null } ?: "无注音",
                                        style = body(12f, FontWeight.Normal, if (seg.reading.isNullOrEmpty()) dimC else artFg),
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { pickerLine = null }) { Text("取消") }
                },
            )
        }
    }
    // V1.1: 编辑器 —— 原文 + 读音输入 + 保存/删除（仅当前曲生效，换源/改文本自动失效）
    // editing = (lineIndex, segment, currentReading)：按"出现位置"存，同一 surface 多次
    // 出现可分别设不同读法（解决「何」在歌里有的なに有的なん、不能归一的问题）。
    editing?.let { (lineIdx, seg, current) ->
        val surface = seg.surface
        val charStart = seg.startOffset
        var input by remember(surface, current) { mutableStateOf(current) }
        androidx.compose.material3.MaterialTheme(colorScheme = dialogScheme) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("修正读法", style = body(14f, FontWeight.ExtraBold, artFg)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("原文：$surface", style = body(13f, FontWeight.Bold, dimC))
                        androidx.compose.material3.OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("读音（平假名，留空清除）") },
                            singleLine = true,
                            textStyle = body(15f, FontWeight.Normal, artFg),
                        )
                        Text(
                            "仅对当前这首歌的此处生效（第${lineIdx + 1}行），换歌词源/改文本自动失效。",
                            style = body(10.5f, FontWeight.Normal, dimC).copy(lineHeight = 14.sp),
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        vm.saveReadingOverrideAt(lineIdx, charStart, surface, input); editing = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { editing = null }) { Text("取消") }
                },
            )
        }
    }
    // v3.0: lyrics import dialog moved to AppRoot (LyricsImportDialog) so it is
    // reachable from the player menu as well as the fullscreen lyrics view —
    // previously the menu set lyricsImportDialog=true but only LyricsScreen
    // rendered the dialog, so clicking "导入歌词文本" from the player did
    // nothing until the user manually opened LyricsScreen first.
    }
}

@Composable
private fun SheetPill(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) { Text(text, style = body(12f, FontWeight.ExtraBold, fg), maxLines = 1) }
}

/** Bug4c: fullscreen overlay for the lyrics adjust panel. A scrim catches
 *  outside taps to dismiss; the panel itself slides down from above with a
 *  tween so the appearance feels anchored at the Sliders button, not a flash.
 *  Anchored at top-center, well below the top bar so it doesn't cover the
 *  Sliders/Close buttons (user can still tap Close to leave lyrics entirely). */
@Composable
private fun LyricsAdjustSheet(
    onDismiss: () -> Unit,
    artFg: Color,
    dimC: Color,
    btnBg: Color,
    safeBg: Color,
    ly: com.shiyin.music.LoadedLyrics,
    deepseekKey: String,
    onBumpOffset: (Long) -> Unit,
    onOpenSourcePicker: () -> Unit,
    onRematchAI: () -> Unit,
    onFetchExternalReading: () -> Unit,
    externalFetchStatus: String?,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    // v5.2 #76: 面板用固定高对比配色,不再依赖封面取色 artFg/safeBg——取色失败时
    // artFg 落到深灰、在半透明黑底(0.78)上几乎看不见("黑漆漆看不清")。改固定白字
    // + 半透明白按钮 + 深底,任何取色结果都可读。
    val panelFg = Color.White
    val panelDim = Color.White.copy(alpha = 0.55f)
    val panelBtnBg = Color.White.copy(alpha = 0.14f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        // v5.2 Bug4: bubble-style sheet anchored right under the Sliders button
        // (top-right of the lyrics page). No drop shadow (the old shadow was
        // the ugly piece) — instead the panel is a solid dark surface with a
        // triangular pointer drawn at its top edge to visually connect it to
        // the button, like a chat bubble. The slide-in tween mirrors the
        // player's queue sheet for a consistent "pop in" feel.
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(animationSpec = tween(260, easing = androidx.compose.animation.core.EaseOutBack)) { -it / 3 } + fadeIn(animationSpec = tween(180)),
            exit = slideOutVertically(animationSpec = tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 4 } + fadeOut(animationSpec = tween(120)),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 76.dp)
                .fillMaxWidth()
                .clickable(enabled = false) { /* swallow clicks so they don't dismiss */ },
        ) {
            Column(
                Modifier
                    // v5.2 Bug4: draw the bubble pointer (triangle) at the top
                    // edge, right-aligned so it points up at the Sliders
                    // button. The pointer is part of the panel background so
                    // the rounded-corner clip doesn't cut it.
                    .drawWithContent {
                        val pointerW = 18.dp.toPx()
                        val pointerH = 9.dp.toPx()
                        val pointerX = size.width - 32.dp.toPx() - pointerW
                        drawContent()
                        drawRect(
                            color = Color.Black.copy(alpha = 0.78f),
                            topLeft = androidx.compose.ui.geometry.Offset(pointerX, 0f),
                            size = androidx.compose.ui.geometry.Size(pointerW, 0.5.dp.toPx()),
                        )
                        // triangle pointer
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(pointerX, 0f)
                            lineTo(pointerX + pointerW, 0f)
                            lineTo(pointerX + pointerW / 2f, pointerH)
                            close()
                        }
                        drawPath(path = path, color = Color.Black.copy(alpha = 0.78f))
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 17.dp, vertical = 15.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("时间微调", style = body(12.5f, FontWeight.ExtraBold, panelFg))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SheetPill("−0.5s", panelBtnBg, panelFg) { onBumpOffset(-500) }
                        val off = ly.offsetMs / 1000.0
                        Text(
                            (if (off > 0) "+" else "") + "%.1f 秒".format(off),
                            style = body(12.5f, FontWeight.ExtraBold, panelFg),
                            modifier = Modifier.width(54.dp),
                            textAlign = TextAlign.Center,
                        )
                        SheetPill("+0.5s", panelBtnBg, panelFg) { onBumpOffset(500) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "匹配来源 · ${ly.source}",
                        style = body(12.5f, FontWeight.ExtraBold, panelFg),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    SheetPill("切换来源", panelBtnBg, panelFg) { onOpenSourcePicker() }
                }
                if (deepseekKey.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(999.dp))
                                .background(panelFg)
                                .clickable { onRematchAI() }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("AI 补全", style = body(13f, FontWeight.ExtraBold, Color.Black))
                        }
                    }
                }
                // v1.1+: 外部假名校验（实验）——Phase 2: 按钮有明确执行反馈。
                Row(Modifier.fillMaxWidth()) {
                    val status = externalFetchStatus
                    val label = when {
                        status == null -> "外部假名校验（实验）"
                        status == "loading" -> "搜索中…"
                        else -> status
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(999.dp))
                            .background(panelBtnBg)
                            .clickable { if (status != "loading") onFetchExternalReading() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = body(12.5f, FontWeight.ExtraBold, panelFg))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(panelFg)
                            .clickable { onSave() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (ly.saved) "已保存到本地 ✓" else "确认并保存到本地",
                            style = body(13f, FontWeight.ExtraBold, Color.Black),
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(panelBtnBg)
                            .clickable { onDelete() }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("删除歌词", style = body(13f, FontWeight.ExtraBold, panelFg)) }
                }
                Text(
                    "保存后写入本地歌词库，下次打开自动加载，无需重新匹配。",
                    style = body(11f, FontWeight.Normal, panelDim).copy(lineHeight = 16.sp),
                )
            }
        }
    }
}

// ── v3.3 note ───────────────────────────────────────────────────────────────
// Contrast is guaranteed inside `PaletteExtractor` (it returns an fg that
// clears WCAG 4.5:1 against its bg, adapting to dark-on-light or light-on-dark
// as the cover demands). The old per-screen `ensureContrast`/luminance helpers
// here were removed with v3.3 — the page now just trusts (bg, fg) from the
// extractor and paints with them directly.


package com.shiyin.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.coverPalette
import com.shiyin.music.ui.components.rememberCoverPalette
import com.shiyin.music.ui.components.shadowMd
import com.shiyin.music.ui.icons.Lucide

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
    val dimC = LyricText.copy(alpha = 0.55f)
    val artFg = LyricText // fg end (white on dark / dark-grey on light) — paint everywhere
    val btnBg = LyricText.copy(alpha = 0.14f)
    val inactiveC = LyricText.copy(alpha = 0.34f) // clearly dimmer than the active line
    val safeBg = artBg // bg colour, named for backward-compat with the button/dialog uses below
    val lines = ly.parsed.lines
    val durMs = if (vm.player.durationMs > 0) vm.player.durationMs else track.durationMs
    val activeI = ly.parsed.activeIndex(vm.player.positionMs, durMs, ly.offsetMs)
    val unsynced = !ly.parsed.synced

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
                        .clickable {
                            val target = line.timeMs?.let { (it - ly.offsetMs).coerceAtLeast(0) }
                                ?: (i.toLong() * durMs / lines.size.coerceAtLeast(1))
                            vm.player.seekToMs(target)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        line.text,
                        style = body(26f, FontWeight.ExtraBold, if (i <= activeI) artFg else inactiveC)
                            .copy(lineHeight = 36.sp),
                        modifier = Modifier.weight(1f),
                    )
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
            onSave = { vm.saveLyrics() },
            onDelete = { vm.deleteLyrics() },
        )
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


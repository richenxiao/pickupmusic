package com.shiyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.split.SPLIT_START_UNKNOWN
import com.shiyin.music.data.split.SplitIds
import com.shiyin.music.data.split.SplitListParser
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.PillButton
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.rememberAlbumArt
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic

/**
 * v22: 「专辑整轨拆分为单曲」录入界面 —— 两步流程。
 *
 * 第一步：粘贴带序号的歌曲列表（可带时间戳，可不带）。
 * 第二步：时间轴校准 —— 顶部迷你播放器 + 可拖动进度条（覆盖**整轨文件总时长**）+
 *        已定起点插旗；下方逐行「设为当前时间」/ 手动输入，再点即覆盖更新。
 *
 * 交互范式直接沿用歌词手动打轴（`syncLyricLine` + LyricsScreen 每行的 Clock 按钮）：
 * 同一套"边听边点"的打点方式，用户不需要学新东西。
 */
@Composable
fun SplitEditorScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    val src = vm.splitEditFor ?: return
    val srcTrack = vm.physicalTracks().firstOrNull { it.id == src }
        ?: vm.trackById(src)
        ?: vm.trackById(SplitIds.containerId(src))
    val fileDurMs = srcTrack?.durationMs ?: 0L

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackButton {
                if (vm.splitEditStep == 1 && vm.splitRows.isNotEmpty()) vm.splitBackToPaste()
                else vm.splitCancel()
            }
            Text("专辑整轨拆分为单曲", style = heading(20), modifier = Modifier.weight(1f))
        }
        if (vm.splitEditStep == 0) {
            PasteStep(vm)
        } else {
            TimelineStep(vm, srcTrack, fileDurMs)
        }
    }
}

// ── 第一步：粘贴歌曲列表 ────────────────────────────────────────────────────

@Composable
private fun PasteStep(vm: MainViewModel) {
    val c = LocalOrganic.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "一行一首，可带时间点（如 03:05 曲名、[03:05] 曲名、曲名 03:05）。" +
                "不带时间点的曲目留到下一步边听边补。第一首固定从 0:00:00 开始。",
            style = body(12.5f, FontWeight.Normal, c.n600),
        )
        OutlinedTextField(
            value = vm.splitPasteText,
            onValueChange = { vm.splitPasteText = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = {
                Text(
                    "1. intro\n2. 黑\n3. 自由式\n4. 03:42 未命名",
                    style = body(13f, FontWeight.Normal, c.n500),
                )
            },
            textStyle = body(13f, FontWeight.Normal, c.text),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = c.text, unfocusedTextColor = c.text,
                cursorColor = c.accent, focusedBorderColor = c.accent, unfocusedBorderColor = c.n400,
            ),
        )
        if (vm.splitPasteError.isNotBlank()) {
            Text(vm.splitPasteError, style = body(12.5f, FontWeight.Medium, c.a700))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                "取消", onClick = { vm.splitCancel() },
                bg = null, textColor = c.text, borderColor = c.divider,
            )
            PillButton("下一步", onClick = { vm.splitParse() }, bg = c.accent, textColor = Color.White)
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ── 第二步：时间轴校准 ──────────────────────────────────────────────────────

@Composable
private fun TimelineStep(
    vm: MainViewModel,
    srcTrack: com.shiyin.music.data.Track?,
    fileDurMs: Long,
) {
    val c = LocalOrganic.current
    val rows = vm.splitRows
    val unknown = rows.count { it.startMs == SPLIT_START_UNKNOWN }

    Column(Modifier.fillMaxSize()) {
        // 迷你播放器：封面 + 专辑名/歌手 + 可拖动进度条（覆盖整轨文件总时长）
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val art = rememberAlbumArt(srcTrack, 56.dp)
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(c.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) {
                        androidx.compose.foundation.Image(
                            art, null, modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        OIcon(Lucide.Disc, 24.dp, c.n500)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        srcTrack?.album?.takeIf { it.isNotBlank() && it != com.shiyin.music.data.NO_ALBUM }
                            ?: srcTrack?.title ?: "整轨文件",
                        style = body(15f, FontWeight.SemiBold, c.text),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        srcTrack?.artist ?: "",
                        style = body(12f, FontWeight.Normal, c.n600),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(c.surface)
                        .clickable { vm.player.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    OIcon(if (vm.player.isPlaying) Lucide.Pause else Lucide.Play, 18.dp, c.text)
                }
            }
            ScrubBarWithFlags(
                positionMs = vm.player.positionMs,
                durationMs = fileDurMs,
                flags = rows.mapNotNull { r ->
                    if (r.startMs == SPLIT_START_UNKNOWN) null else r.ordinal to r.startMs
                },
                onSeekFraction = { vm.player.seekToFraction(it) },
            )
            Text(
                "${SplitListParser.format(vm.player.positionMs)} / ${SplitListParser.format(fileDurMs)}",
                style = body(11.5f, FontWeight.Normal, c.n600),
            )
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { it.ordinal }) { row ->
                SplitRow(
                    vm = vm,
                    ordinal = row.ordinal,
                    title = row.title,
                    startMs = row.startMs,
                    conflict = row.ordinal in vm.splitConflicts,
                )
            }
            item {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (unknown > 0) "还有 $unknown 首未设置时间点，未设置的部分不会出现在专辑里"
                    else "全部 ${rows.size} 首已设置时间点",
                    style = body(12f, FontWeight.Normal, if (unknown > 0) c.a700 else c.n600),
                )
                if (vm.splitConflicts.isNotEmpty()) {
                    Text(
                        "有 ${vm.splitConflicts.size} 首的时间点不是递增的（已标红），请修正后再完成",
                        style = body(12f, FontWeight.Medium, c.a700),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PillButton(
                        "保存草稿", onClick = { vm.splitSave(done = false) },
                        bg = null, textColor = c.text, borderColor = c.divider,
                    )
                    PillButton(
                        "完成", onClick = { vm.splitSave(done = true) },
                        bg = c.accent, textColor = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * 进度条 + 已定起点插旗。
 *
 * 插旗的意义：整轨专辑没有现成分轨信息时，用户需要一眼看出"这条完整时间轴上哪些位置
 * 已经被标记为分割点、还有哪些曲目尚未标记"。
 */
@Composable
private fun ScrubBarWithFlags(
    positionMs: Long,
    durationMs: Long,
    flags: List<Pair<Int, Long>>,
    onSeekFraction: (Float) -> Unit,
) {
    val c = LocalOrganic.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    val pct = scrub ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(durationMs) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.firstOrNull()?.let { ch ->
                            scrub = (ch.position.x / size.width).coerceIn(0f, 1f)
                            ch.consume()
                        }
                    } while (ev.changes.any { it.pressed })
                    scrub?.let { onSeekFraction(it) }
                    scrub = null
                }
            },
    ) {
        // 轨道
        Box(
            Modifier.fillMaxWidth().height(3.dp).align(Alignment.CenterStart)
                .clip(RoundedCornerShape(999.dp)).background(c.n400),
        )
        // 已播部分
        Box(
            Modifier.fillMaxWidth(pct).height(3.dp).align(Alignment.CenterStart)
                .clip(RoundedCornerShape(999.dp)).background(c.accent),
        )
        // 插旗：每个已定起点一根竖线 + 序号
        if (durationMs > 0) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val w = maxWidth
                for ((ordinal, ms) in flags) {
                    val f = (ms.toFloat() / durationMs).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .offset(x = w * f - 1.dp)
                            .width(2.dp)
                            .height(16.dp)
                            .align(Alignment.CenterStart)
                            .background(c.a700),
                    )
                    if (ordinal <= 99) {
                        Text(
                            "$ordinal",
                            style = body(8f, FontWeight.Bold, c.a700),
                            modifier = Modifier.offset(x = w * f + 3.dp).align(Alignment.CenterStart),
                        )
                    }
                }
            }
        }
        // 播放头
        Box(
            Modifier.fillMaxWidth(pct).height(1.dp).align(Alignment.CenterStart),
        ) {
            Box(
                Modifier.align(Alignment.CenterEnd).size(12.dp).clip(CircleShape)
                    .background(c.accent).border(2.dp, c.bg, CircleShape),
            )
        }
    }
}

@Composable
private fun SplitRow(
    vm: MainViewModel,
    ordinal: Int,
    title: String,
    startMs: Long,
    conflict: Boolean,
) {
    val c = LocalOrganic.current
    val locked = vm.splitRowLocked(ordinal)
    var editing by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("$ordinal", style = body(12f, FontWeight.Bold, c.n500), modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = body(14f, FontWeight.Medium, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val label = if (startMs == SPLIT_START_UNKNOWN) "未设置" else SplitListParser.format(startMs)
            Text(
                if (locked) "$label · 固定" else label,
                style = body(11.5f, FontWeight.Normal, if (conflict) c.a700 else c.n600),
            )
        }
        // 手动输入时间（第一首固定 0:00:00，不给编辑入口）
        if (!locked) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c.bg).clickable { editing = true },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.Pencil, 14.dp, c.n600) }
            // 「设为当前时间」—— 与歌词打轴的 Clock 按钮同一范式
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c.accent)
                    .clickable { vm.splitSetStartNow(ordinal) },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.Clock, 14.dp, Color.White) }
        }
    }

    if (editing) {
        var txt by remember(ordinal) {
            mutableStateOf(if (startMs == SPLIT_START_UNKNOWN) "" else SplitListParser.format(startMs))
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = c.surface,
            title = { Text("第 $ordinal 首的起始时间", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("支持 h:mm:ss / m:ss / 纯秒数。留空表示仍未定。", style = body(12f, FontWeight.Normal, c.n600))
                    OutlinedTextField(
                        value = txt, onValueChange = { txt = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = body(14f, FontWeight.Normal, c.text),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, unfocusedTextColor = c.text,
                            cursorColor = c.accent, focusedBorderColor = c.accent, unfocusedBorderColor = c.n400,
                        ),
                    )
                }
            },
            confirmButton = {
                com.shiyin.music.ui.components.DialogActionsRow(
                    listOf(
                        com.shiyin.music.ui.components.DialogAction("取消") { editing = false },
                        com.shiyin.music.ui.components.DialogAction("清空") {
                            vm.splitSetStart(ordinal, null); editing = false
                        },
                        com.shiyin.music.ui.components.DialogAction("确定", emphasized = true) {
                            val ms = SplitListParser.parseUserTime(txt)
                            if (txt.isBlank()) vm.splitSetStart(ordinal, null)
                            else if (ms != null) vm.splitSetStart(ordinal, ms)
                            editing = false
                        },
                    )
                )
            },
        )
    }
}

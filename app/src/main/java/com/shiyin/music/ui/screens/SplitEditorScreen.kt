package com.shiyin.music.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.heightIn
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
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
            // v22 修补: 右上角加号键——编辑页随时补录漏粘贴的曲目(用户反馈:
            // 漏了后面几首便导致后面不知道绑定哪些歌曲)。
            if (vm.splitEditStep == 1) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(c.surface)
                        .clickable { vm.splitAppendRow() },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.ListPlus, 18.dp, c.text) }
            }
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
                "没填完或漏了曲目都不用担心——进入下一步后随时可用右上角 + 号补录、拖动排序。",
            style = body(12.5f, FontWeight.Normal, c.n600),
        )
        OutlinedTextField(
            value = vm.splitPasteText,
            onValueChange = { vm.splitPasteText = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = {
                // v22 修补:通用引导文案(用户反馈——原 placeholder 写死了某张专辑的
                // 真实曲名 intro/黑/自由式,读作"要填的就是这几首"。换成格式示例,
                // 曲名用占位词,不暗示任何特定专辑)。
                Text(
                    "1. 曲名一\n2. 曲名二\n3. 03:42 曲名三\n4. 0:04:10 曲名四",
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

        // v22 修补(用户反馈②): 曲目列表支持**长按拖拽排序**——复用专辑页的
        // sh.calvin.reorderable 同一套库,行右侧 GripLines 手柄长按拖动。
        // 拆分顺序以编辑页为准(专辑页已禁排序),所以这里就是唯一的排序真源。
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(lazyListState = listState) { from, to ->
            val fromRow = rows.getOrNull(from.index)
            val toRow = rows.getOrNull(to.index)
            if (fromRow != null && toRow != null) vm.splitMoveRow(fromRow.ordinal, toRow.ordinal)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { it.ordinal }) { row ->
                ReorderableItem(reorderState, key = row.ordinal) {
                    SplitRow(
                        vm = vm,
                        ordinal = row.ordinal,
                        title = row.title,
                        startMs = row.startMs,
                        conflict = row.ordinal in vm.splitConflicts,
                        reorderScope = this,
                        rowCount = rows.size,
                    )
                }
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
 *
 * v22 修补(用户反馈④): 拖动时**条加高 + 滑块放大**——细节控制(50 分钟整轨上
 * 3dp 的条几乎无法精细定位);用 animateFloatAsState 过渡(松手弹回),不是硬切。
 * 触区仍是整个条区域(打轴需要大触区,与播放页 6dp 精简条不同场景)。
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
    // 拖动放大:0=静止(条 3dp/滑块 12dp),1=拖动中(条 7dp/滑块 20dp)。
    // animateFloatAsState 带 tween 过渡——按下放大、松手弹回都是平滑的。
    val interact by animateFloatAsState(
        targetValue = if (scrub != null) 1f else 0f,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "scrubInteract",
    )
    val trackH = androidx.compose.ui.unit.lerp(3.dp, 7.dp, interact)
    val thumbSize = androidx.compose.ui.unit.lerp(12.dp, 20.dp, interact)
    val pct = scrub ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
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
            Modifier.fillMaxWidth().height(trackH).align(Alignment.CenterStart)
                .clip(RoundedCornerShape(999.dp)).background(c.n400),
        )
        // 已播部分
        Box(
            Modifier.fillMaxWidth(pct).height(trackH).align(Alignment.CenterStart)
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
                Modifier.align(Alignment.CenterEnd)
                    .requiredSize(thumbSize)
                    .clip(CircleShape)
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
    reorderScope: sh.calvin.reorderable.ReorderableCollectionItemScope? = null,
    rowCount: Int = 0,
) {
    val c = LocalOrganic.current
    val locked = vm.splitRowLocked(ordinal)
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("$ordinal", style = body(12f, FontWeight.Bold, c.n500), modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "未命名" },
                style = body(14f, FontWeight.Medium, c.text),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { renaming = true },
            )
            val label = if (startMs == SPLIT_START_UNKNOWN) "未设置" else SplitListParser.format(startMs)
            Text(
                if (locked) "$label · 固定" else label,
                style = body(11.5f, FontWeight.Normal, if (conflict) c.a700 else c.n600),
            )
        }
        if (!locked) {
            // v22 修补: 删行——补录加错了/不要某行(与加号键配套)。
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c.bg).clickable {
                    vm.splitRemoveRow(ordinal)
                },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.Trash, 14.dp, c.n500) }
            // 手动输入时间（第一首固定 0:00:00，不给编辑入口）
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
        reorderScope?.let { scope ->
            with(scope) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(c.surface)
                        .longPressDraggableHandle(),
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.GripLines, 16.dp, c.n500) }
            }
        }
    }

    if (renaming) {
        var txt by remember(ordinal) { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = c.surface,
            title = { Text("曲名", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                OutlinedTextField(
                    value = txt, onValueChange = { txt = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = body(14f, FontWeight.Normal, c.text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, unfocusedTextColor = c.text,
                        cursorColor = c.accent, focusedBorderColor = c.accent, unfocusedBorderColor = c.n400,
                    ),
                )
            },
            confirmButton = {
                com.shiyin.music.ui.components.DialogActionsRow(
                    listOf(
                        com.shiyin.music.ui.components.DialogAction("取消") { renaming = false },
                        com.shiyin.music.ui.components.DialogAction("确定", emphasized = true) {
                            vm.splitRenameRow(ordinal, txt); renaming = false
                        },
                    )
                )
            },
        )
    }

    if (editing) {
        TimeEditDialog(
            ordinal = ordinal,
            title = title,
            startMs = startMs,
            onDismiss = { editing = false },
            vm = vm,
        )
    }
}

/**
 * v22 修补: 时间编辑弹窗重做(用户反馈⑤)。
 *
 * 三种设时方式并存:
 *  1. **粘贴时间**——从别处拷来的 m:ss / h:mm:ss 直接粘进来(顶部粘贴框,自动解析预览);
 *  2. **闹钟式时分秒下拉**——h/m/s 三列单选(复用 DateDropdown 同语言),选中后
 *     与本行曲目联动显示(不能全是 0:0:0 很费劲);
 *  3. 清空=回到未定。
 */
@Composable
private fun TimeEditDialog(
    ordinal: Int,
    title: String,
    startMs: Long,
    onDismiss: () -> Unit,
    vm: MainViewModel,
) {
    val c = LocalOrganic.current
    // 当前值拆成 h/m/s(未定时全 0,下拉里显示"未设置"由 0 值承担)
    val known = startMs != SPLIT_START_UNKNOWN
    val totalSec = if (known) startMs / 1000 else 0L
    var h by remember(ordinal) { mutableStateOf((totalSec / 3600).toInt()) }
    var m by remember(ordinal) { mutableStateOf(((totalSec % 3600) / 60).toInt()) }
    var s by remember(ordinal) { mutableStateOf((totalSec % 60).toInt()) }
    var pasteText by remember(ordinal) { mutableStateOf("") }
    val pasteMs = SplitListParser.parseUserTime(pasteText)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(
                "第 $ordinal 首「${title.ifBlank { "未命名" }}」的起始时间",
                style = body(15f, FontWeight.ExtraBold, c.text),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 闹钟式时分秒三列下拉——选中即与本行联动显示
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeWheel("时", h, 0..5, { h = it }, c)
                    TimeWheel("分", m, 0..59, { m = it }, c)
                    TimeWheel("秒", s, 0..59, { s = it }, c)
                }
                Text(
                    "当前选择：$h:${"%02d".format(m)}:${"%02d".format(s)}",
                    style = body(12.5f, FontWeight.SemiBold, c.accent),
                )
                // 粘贴时间——从别处拷来直接粘,自动解析
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("或直接粘贴时间（如 3:05 / 0:04:10）", style = body(12f, FontWeight.Normal, c.n500))
                    },
                    textStyle = body(14f, FontWeight.Normal, c.text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, unfocusedTextColor = c.text,
                        cursorColor = c.accent, focusedBorderColor = c.accent, unfocusedBorderColor = c.n400,
                    ),
                )
                if (pasteText.isNotBlank()) {
                    Text(
                        if (pasteMs != null) "识别为 ${SplitListParser.format(pasteMs)}"
                        else "无法识别，支持 h:mm:ss / m:ss / 纯秒数",
                        style = body(12f, FontWeight.Normal, if (pasteMs != null) c.accent else c.a700),
                    )
                }
                Text("清空后该行回到未定，可稍后用「设为当前时间」补。", style = body(11.5f, FontWeight.Normal, c.n600))
            }
        },
        confirmButton = {
            com.shiyin.music.ui.components.DialogActionsRow(
                listOf(
                    com.shiyin.music.ui.components.DialogAction("取消") { onDismiss() },
                    com.shiyin.music.ui.components.DialogAction("清空") {
                        vm.splitSetStart(ordinal, null); onDismiss()
                    },
                    com.shiyin.music.ui.components.DialogAction("确定", emphasized = true) {
                        when {
                            // 粘贴框有内容且能解析 → 优先用粘贴值
                            pasteText.isNotBlank() && pasteMs != null -> vm.splitSetStart(ordinal, pasteMs)
                            // 粘贴框有内容但解析失败 → 不动,弹窗关掉(错误提示已在正文)
                            pasteText.isNotBlank() -> {}
                            // 时分秒全 0 且原本就未定 → 视为未动(0:0:0 不是有效起点)
                            !known && h == 0 && m == 0 && s == 0 -> {}
                            else -> vm.splitSetStart(ordinal, (h * 3600 + m * 60 + s) * 1000L)
                        }
                        onDismiss()
                    },
                )
            )
        },
    )
}

/** v22 修补: 单列时间下拉(时/分/秒共用)——与 DateDropdown 同语言的小号版本。 */
@Composable
private fun TimeWheel(
    label: String,
    value: Int,
    range: IntRange,
    onSelect: (Int) -> Unit,
    c: com.shiyin.music.ui.theme.OrganicColors,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(c.surface)
                .border(1.dp, c.divider, RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(label, style = body(11f, FontWeight.Normal, c.n500))
            Text(
                "%02d".format(value),
                style = body(13f, FontWeight.SemiBold, c.text),
            )
            OIcon(Lucide.ChevronDown, 12.dp, c.n400)
        }
        if (open) {
            val options = range.toList()
            val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = (options.indexOf(value) - 5).coerceAtLeast(0),
            )
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { open = false },
                containerColor = c.surface,
                title = { Text("选择$label", style = body(14f, FontWeight.ExtraBold, c.text)) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    ) {
                        items(options.size) { i ->
                            val v = options[i]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelect(v); open = false }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "%02d".format(v),
                                    style = body(13.5f, if (v == value) FontWeight.ExtraBold else FontWeight.Normal, if (v == value) c.accent else c.text),
                                    modifier = Modifier.weight(1f),
                                )
                                if (v == value) OIcon(Lucide.Check, 15.dp, c.accent)
                            }
                        }
                    }
                },
                confirmButton = {
                    com.shiyin.music.ui.components.DialogActionsRow(listOf(com.shiyin.music.ui.components.DialogAction("取消") { open = false }))
                },
            )
        }
    }
}

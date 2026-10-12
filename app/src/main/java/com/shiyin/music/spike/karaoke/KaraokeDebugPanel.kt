package com.shiyin.music.spike.karaoke

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.Track
import com.shiyin.music.playback.KaraokeAudioSpike
import com.shiyin.music.ui.components.ThreeStopSegmentedControl
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.clickableNoRipple
import com.shiyin.music.ui.components.shadowSm
import com.shiyin.music.ui.theme.LocalOrganic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shiyin.music.data.karaoke.AudioDecoder
import com.shiyin.music.data.karaoke.MdxModel
import com.shiyin.music.data.karaoke.MdxSeparator
import com.shiyin.music.data.karaoke.OrtBackend

/**
 * P0 Spike 阶段 B 调试面板（规格 §12 要求：选歌 → 选模型/线程 → 跑 → 看耗时 → 导出 WAV）。
 * 位置：设置→歌词本 底部（spike 分支临时常显）。
 */
@Composable
fun KaraokeDebugPanel(vm: MainViewModel) {
    val c = LocalOrganic.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var track by remember { mutableStateOf<Track?>(null) }
    var model by remember { mutableStateOf(MdxModel.INST_HQ_3) }
    var threads by remember { mutableStateOf(4) }
    var backend by remember { mutableStateOf(OrtBackend.CPU) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var report by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var exportCount by remember { mutableStateOf<Int?>(null) }
    var exportRefresh by remember { mutableStateOf(0) }
    var mixMode by remember { mutableStateOf(KaraokeAudioSpike.mode(context)) }

    // 导出目录的文件数（走 MediaStore 查询，别在主线程上做）
    LaunchedEffect(exportRefresh) {
        exportCount = withContext(Dispatchers.IO) { DebugExport.clear(context, delete = false) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .shadowSm(RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(c.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── 选歌 ──
        Text(
            "选歌：" + (track?.let { "${it.title} — ${it.artist}" } ?: "点这里从曲库选一首"),
            style = body(14f, color = c.text),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.n100)
                .clickableNoRipple(enabled = !running) { pickerOpen = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )

        // ── 模型 ──
        MdxModel.entries.forEach { m ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableNoRipple(enabled = !running) { model = m },
            ) {
                RadioButton(selected = model == m, onClick = { if (!running) model = m })
                Text(m.displayName, style = body(13f, color = c.text))
            }
        }

        // ── 推理后端 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("后端", style = body(13f, color = c.n600))
            Spacer(Modifier.width(10.dp))
            OrtBackend.entries.forEach { b ->
                Text(
                    b.label,
                    style = body(12f, color = if (backend == b) c.surface else c.text),
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (backend == b) c.text else c.n100)
                        .clickableNoRipple(enabled = !running) { backend = b }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // ── 线程数 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("线程", style = body(13f, color = c.n600))
            Spacer(Modifier.width(10.dp))
            listOf(2, 4, 6).forEach { n ->
                Text(
                    "$n",
                    style = body(13f, color = if (threads == n) c.surface else c.text),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (threads == n) c.text else c.n100)
                        .clickableNoRipple(enabled = !running) { threads = n }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // ── 运行 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 未选歌时按钮**看起来**就该是不可点的（旧版灰都不灰，点了没反应像坏了）
            Text(
                if (running) "运行中…（可取消）" else "开始分离并导出 WAV",
                style = body(14f, color = if (track == null && !running) c.n600 else c.surface),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (track == null && !running) c.n100 else c.text)
                    .clickableNoRipple {
                        if (track == null) { status = "先选上面那行挑一首歌"; return@clickableNoRipple }
                        val t = track!!
                        if (running) {
                            job?.cancel(); running = false; status = "已取消"
                        } else {
                            running = true; report = null; progress = 0f; status = "准备中…"
                            job = scope.launch {
                                try {
                                    val r = KaraokeStemRunner.run(
                                        context, t, model, threads, backend,
                                        onStatus = { status = it },
                                        onProgress = { text, frac ->
                                            progress = frac
                                            status = text
                                            android.util.Log.i("KaraokeP0", "进度 $text")
                                        },
                                    )
                                    report = buildString {
                                        appendLine("模型 ${r.model.displayName} · 后端 ${r.backend.label} · 线程 $threads")
                                        appendLine("音频 ${"%.1f".format(r.durationSec)}s · 共 ${r.chunkTimesMs.size} 块")
                                        appendLine("解码 ${r.decodeMs}ms · 模型加载 ${r.modelLoadMs}ms")
                                        appendLine("首块可用 ${r.firstChunkMs}ms · 分离总 ${r.totalMs}ms")
                                        appendLine("RTF ${"%.2f".format(r.rtf)}（推理累计 ${r.inferMsTotal}ms）")
                                        appendLine()
                                        // 能否赶上播放：每块音频 ${chunkAudioSec}s，单块处理超过它就是追不上
                                        appendLine("每块耗时（ms）：" + r.chunkTimesMs.joinToString(" "))
                                        appendLine(
                                            "最小/平均/最大 = ${r.chunkTimesMs.minOrNull() ?: 0}/" +
                                                "${r.avgChunkMs}/${r.maxChunkMs}ms（每块音频 " +
                                                "${"%.1f".format(r.chunkAudioSec)}s = ${(r.chunkAudioSec * 1000).toInt()}ms）",
                                        )
                                        appendLine(
                                            if (r.overBudgetChunks == 0)
                                                "✅ 每块都低于预算——能一路赶上播放"
                                            else
                                                "⚠️ 有 ${r.overBudgetChunks} 块超过预算——这些位置伴奏会追不上播放",
                                        )
                                        appendLine()
                                        appendLine("Java 堆 ${r.heapBeforeMb} → ${r.heapAfterMb}MB · native 峰值 ${r.nativeHeapPeakMb}MB")
                                        r.tempBeforeC?.let { b ->
                                            appendLine("电池温度 ${b}℃ → ${r.tempAfterC ?: b}℃")
                                        }
                                        appendLine("导出：${DebugExport.DIR}/（已隔离，不进曲库；对照表 _map.tsv）")
                                        appendLine(r.accName ?: "（伴奏写入失败）")
                                        append(r.vocName ?: "（人声写入失败）")
                                        appendLine()
                                        appendLine("⚠️ 用完请按上面的「清空」删掉导出文件——会同时删掉媒体库里的记录，否则系统音乐 App 仍会看到它们。")
                                    }
                                    android.util.Log.i("KaraokeP0", "报告 " + report)
                                    android.util.Log.i(
                                        "KaraokeP0",
                                        "RESULT model=${r.model.name} backend=${r.backend.name} "
                                            + "threads=$threads dur=${"%.1f".format(r.durationSec)}s "
                                            + "decode=${r.decodeMs}ms load=${r.modelLoadMs}ms firstChunk=${r.firstChunkMs}ms "
                                            + "total=${r.totalMs}ms rtf=${"%.2f".format(r.rtf)} avgChunk=${r.avgChunkMs}ms "
                                            + "maxChunk=${r.maxChunkMs}ms overBudget=${r.overBudgetChunks} "
                                            + "chunks=${r.chunkTimesMs.size}",
                                    )
                                    // v1.5.0 A60：**写进应用自己的文件**（外部 files 目录），
                                    // 不再只打系统日志——logcat 会滚掉、还得 adb 在线才能读。
                                    // 路径：Android/data/com.shiyin.music/files/karaoke_runs.log
                                    // 同时写进**应用使用日志**（设置→日志 可见、可导出分享）——
                                    // 用户在手机上就能自查跑了几轮、各自多快，不必等 adb。
                                    com.shiyin.music.data.log.UsageLog.event(
                                        context, "伴唱分离",
                                        "${r.model.name}/${r.backend.name}/t$threads " +
                                            "音频${"%.1f".format(r.durationSec)}s " +
                                            "首块${r.firstChunkMs}ms 总${r.totalMs}ms " +
                                            "RTF${"%.2f".format(r.rtf)} 平均块${r.avgChunkMs}ms " +
                                            "超预算${r.overBudgetChunks}/${r.chunkTimesMs.size}",
                                    )
                                    runCatching {
                                        val dir = context.getExternalFilesDir(null)
                                        java.io.File(dir, "karaoke_runs.log").appendText(
                                            java.text.SimpleDateFormat(
                                                "MM-dd HH:mm:ss", java.util.Locale.CHINA,
                                            ).format(java.util.Date()) +
                                                " model=${r.model.name} backend=${r.backend.name} threads=$threads" +
                                                " dur=${"%.1f".format(r.durationSec)}s" +
                                                " decode=${r.decodeMs}ms load=${r.modelLoadMs}ms" +
                                                " firstChunk=${r.firstChunkMs}ms total=${r.totalMs}ms" +
                                                " rtf=${"%.2f".format(r.rtf)} avgChunk=${r.avgChunkMs}ms" +
                                                " maxChunk=${r.maxChunkMs}ms overBudget=${r.overBudgetChunks}" +
                                                " chunks=${r.chunkTimesMs.size}" + "\n",
                                        )
                                    }
                                } catch (t2: Throwable) {
                                    report = "❌ ${t2.javaClass.simpleName}: ${t2.message}"
                                } finally {
                                    running = false; status = null
                                    exportRefresh++
                                }
                            }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            status?.let {
                Spacer(Modifier.width(10.dp))
                Text(it, style = body(12f, color = c.n600))
            }
        }

        if (running) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── P2 spike：音频管线档位（把"管线能不能插处理器"和"覆写本身有问题"分开归因）──
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("音频管线档位（改档后需杀掉 App 重进才生效）", style = body(12f, color = c.n600))
            ThreeStopSegmentedControl(
                labels = listOf("基线", "只换factory", "＋透传"),
                selectedIndex = mixMode,
                onSelect = { i ->
                    mixMode = i
                    if (!KaraokeAudioSpike.setMode(context, i)) status = "档位写入失败"
                },
            )
            Text(KaraokeAudioSpike.describe(mixMode), style = body(11f, color = c.n600))
        }

        // ── 导出目录（隔离在 Download/PickUpMusic-debug/，不碰 Music/）──
        if (DebugExport.ENABLED) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "导出 ${DebugExport.DIR}/ · " +
                        (exportCount?.let { "$it 个文件" } ?: "统计中…"),
                    style = body(11f, color = c.n600),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "清空",
                    style = body(12f, color = if (exportCount == 0) c.n600 else c.text),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.n100)
                        .clickableNoRipple(enabled = !running && (exportCount ?: 0) > 0) {
                            scope.launch {
                                val n = withContext(Dispatchers.IO) { DebugExport.clear(context, delete = true) }
                                status = "已清空 $n 个导出文件"
                                exportRefresh++
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        report?.let {
            Text(
                it,
                style = body(11f, color = c.n600).copy(fontFamily = FontFamily.Monospace),
            )
        }
    }

    if (pickerOpen) {
        TrackPickerDialog(
            tracks = vm.tracksRaw,
            onPick = { track = it; pickerOpen = false },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun TrackPickerDialog(
    tracks: List<Track>,
    onPick: (Track) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalOrganic.current
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, tracks) {
        if (query.isBlank()) tracks.take(200)
        else tracks.filter {
            it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
        }.take(200)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("选一首歌（曲库共 ${tracks.size} 首，最多显示 200 条）", style = body(14f, color = c.text)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜歌名或歌手") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(filtered, key = { it.id }) { t ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(t) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(t.title, style = body(13f, color = c.text))
                            Text(
                                "${t.artist} · ${"%.1f".format(t.durationMs / 1000f / 60f)} 分",
                                style = body(11f, color = c.n600),
                            )
                        }
                    }
                }
            }
        },
    )
}

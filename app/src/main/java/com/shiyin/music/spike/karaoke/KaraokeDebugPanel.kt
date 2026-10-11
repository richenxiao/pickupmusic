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
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.clickableNoRipple
import com.shiyin.music.ui.components.shadowSm
import com.shiyin.music.ui.theme.LocalOrganic
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var report by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

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
            Text(
                if (running) "运行中…（可取消）" else "开始分离并导出 WAV",
                style = body(14f, color = c.surface),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.text)
                    .clickableNoRipple(enabled = track != null) {
                        val t = track ?: return@clickableNoRipple
                        if (running) {
                            job?.cancel(); running = false; status = "已取消"
                        } else {
                            running = true; report = null; progress = 0f; status = "准备中…"
                            job = scope.launch {
                                try {
                                    val r = KaraokeStemRunner.run(
                                        context, t, model, threads,
                                        onStatus = { status = it },
                                        onProgress = { p, frac ->
                                            progress = frac
                                            status = if (p.chunkTotal > 0)
                                                "块 ${p.chunkDone}/${p.chunkTotal}（首块 ${p.firstChunkMs}ms）"
                                            else "解码 ${(frac * 100).toInt()}%"
                                        },
                                    )
                                    report = buildString {
                                        appendLine("模型 ${r.model.displayName} · 线程 $threads")
                                        appendLine("音频 ${"%.1f".format(r.durationSec)}s")
                                        appendLine("解码 ${r.decodeMs}ms · 模型加载 ${r.modelLoadMs}ms")
                                        appendLine("首块可用 ${r.firstChunkMs}ms · 分离总 ${r.totalMs}ms")
                                        appendLine("RTF ${"%.2f".format(r.rtf)}（推理累计 ${r.inferMsTotal}ms）")
                                        appendLine("内存 PSS ${r.pssBeforeMb}→${r.pssAfterMb}MB")
                                        r.tempBeforeC?.let { b ->
                                            appendLine("电池温度 ${b}℃→${r.tempAfterC ?: b}℃")
                                        }
                                        appendLine("导出：Music/PickUpMusic/")
                                        append(r.outUri?.lastPathSegment ?: "（伴奏写入失败）")
                                        appendLine()
                                        append(r.vocUri?.lastPathSegment ?: "（人声写入失败）")
                                    }
                                } catch (t2: Throwable) {
                                    report = "❌ ${t2.javaClass.simpleName}: ${t2.message}"
                                } finally {
                                    running = false; status = null
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

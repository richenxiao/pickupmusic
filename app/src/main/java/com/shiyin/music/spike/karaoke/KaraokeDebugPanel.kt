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
    /** 步骤 3 混音档位（原曲/伴奏/人声）与占位伴奏源。 */
    var mixSlot by remember { mutableStateOf(com.shiyin.music.playback.KaraokeMixControl.mode) }
    var mixSrcKind by remember { mutableStateOf(com.shiyin.music.playback.KaraokeMixControl.sourceKind) }
    var probeMode by remember { mutableStateOf(KaraokeAudioSpike.probeMode(context)) }
    /** 最近一次通过面板播放的本地测试文件（`file://` URI + 名字）——扫描计划行与解码导出都用它。 */
    var spikeUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var spikeName by remember { mutableStateOf("") }
    var fixtureFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var e2eMsg by remember { mutableStateOf<String?>(null) }

    // 测试文件目录（app 私有外部目录下 fixtures/，不经 MediaStore）
    val fixtureDir = remember { java.io.File(context.getExternalFilesDir(null), "fixtures") }
    LaunchedEffect(fixtureDir, exportRefresh) {
        fixtureFiles = withContext(Dispatchers.IO) {
            fixtureDir.listFiles()
                ?.filter { it.isFile && it.name.substringAfterLast('.', "") in setOf("wav", "flac", "mp3", "m4a") }
                ?.sortedBy { it.name } ?: emptyList()
        }
    }

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
            Text("伴唱音频处理（开发用 · 改完要重进 App）", style = body(12f, color = c.n600))
            ThreeStopSegmentedControl(
                labels = listOf("不启用", "只换输出", "透传", "时间戳", "混音"),
                selectedIndex = mixMode,
                onSelect = { i ->
                    mixMode = i
                    if (!KaraokeAudioSpike.setMode(context, i)) status = "档位写入失败"
                },
            )
            Text(KaraokeAudioSpike.describe(mixMode), style = body(11f, color = c.n600))

            // ── 步骤 3：混音档位（原曲 / 伴奏 / 人声）——**立即生效**，改档位要重进 App ──
            Text(
                "混音档位（立即生效；需先把上面的档位切到「混音」并重进 App）",
                style = body(12f, color = c.n600),
            )
            ThreeStopSegmentedControl(
                labels = listOf("原曲", "伴奏", "人声"),
                selectedIndex = mixSlot,
                onSelect = { i ->
                    mixSlot = i
                    com.shiyin.music.playback.KaraokeMixControl.mode = i
                    status = "混音档位 → ${com.shiyin.music.playback.KaraokeMixControl.label(i)}"
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("占位伴奏源", style = body(11f, color = c.n600))
                Spacer(Modifier.width(8.dp))
                listOf(0, 1, 2).forEach { k ->
                    Text(
                        when (k) {
                            0 -> "原曲本身"
                            1 -> "220Hz"
                            else -> "静音"
                        },
                        style = body(11f, color = if (mixSrcKind == k) c.surface else c.text),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (mixSrcKind == k) c.text else c.n100)
                            .clickableNoRipple {
                                mixSrcKind = k
                                com.shiyin.music.playback.KaraokeMixControl.sourceKind = k
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                com.shiyin.music.playback.KaraokeMixControl.sourceLabel(mixSrcKind) +
                    " · 淡化 ${com.shiyin.music.playback.KaraokeMixControl.rampMs}ms",
                style = body(11f, color = c.n600),
            )

            Text("探针模式（开发用 · 立即生效）", style = body(12f, color = c.n600))
            ThreeStopSegmentedControl(
                labels = listOf("关闭", "帧号真值", "咔哒", "采 PCM"),
                selectedIndex = probeMode,
                onSelect = { i ->
                    probeMode = i
                    if (!KaraokeAudioSpike.setProbeMode(context, i)) status = "探针写入失败"
                },
            )
            Text(KaraokeAudioSpike.describeProbe(probeMode), style = body(11f, color = c.n600))
        }

        // ── 测试文件（fixtures/ 目录，不经曲库）──
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "测试文件（${fixtureDir.absolutePath}）" +
                    if (fixtureFiles.isEmpty()) " —— 空" else "",
                style = body(11f, color = c.n600),
            )
            fixtureFiles.forEach { f ->
                val u = android.net.Uri.fromFile(f)
                val isCurrent = spikeUri == u
                Text(
                    (if (isCurrent) "▶ " else "· ") + f.name + "  ${f.length() / 1024 / 1024}MB",
                    style = body(12f, color = if (isCurrent) c.text else c.n600),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.n100)
                        .clickableNoRipple(enabled = !vm.probeSweepRunning) {
                            spikeUri = u
                            spikeName = f.name
                            vm.player.playSpikeFile(u, f.name)
                            status = "正在播放 ${f.name}"
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // ── P2 spike 步骤 2c：**全自动扫描套件**（用户时间有限，一个按钮跑完所有测试文件）──
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "全自动扫描：自动挨个载入 fixtures/ 里的测试文件、自动切探针模式、" +
                    "每个扫 75 个随机位置（含暂停/恢复）",
                style = body(11f, color = c.n600),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        vm.autoSweepRunning -> "运行中 ${vm.probeSweepDone}/${vm.probeSweepTotal} · ${vm.autoSweepStatus}"
                        vm.autoSweepStatus.isNotBlank() -> vm.autoSweepStatus
                        else -> "未开始（全程不用操作，约 6–8 分钟）"
                    },
                    style = body(12f, color = c.n600),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "停止",
                    style = body(12f, color = if (!vm.autoSweepRunning && !vm.probeSweepRunning) c.n500 else c.text),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.n100)
                        .clickableNoRipple(enabled = vm.autoSweepRunning || vm.probeSweepRunning) {
                            vm.stopSweep()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "开始全自动",
                    style = body(12f, color = if (vm.autoSweepRunning || vm.probeSweepRunning) c.n500 else c.surface),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (vm.autoSweepRunning || vm.probeSweepRunning) c.n100 else c.text)
                        .clickableNoRipple(enabled = !vm.autoSweepRunning && !vm.probeSweepRunning) {
                            vm.autoSweepAll(75, 300L)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vm.probeSweepRunning) "单文件扫描中 ${vm.probeSweepDone}/${vm.probeSweepTotal}…"
                    else "只扫当前这一个（先在上面点一个测试文件）",
                    style = body(11f, color = c.n600),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "开始扫",
                    style = body(12f, color = if (vm.probeSweepRunning || vm.autoSweepRunning) c.n500 else c.text),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.n100)
                        .clickableNoRipple(enabled = !vm.probeSweepRunning && !vm.autoSweepRunning) {
                            vm.probeSweep(75, 300L, spikeName)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // ── 步骤 2c：端到端对齐（播放链路 PCM ⇄ 分离流水线解码 PCM，互相关测零点偏差）──
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("端到端对齐（同一首真实歌曲，两条解码路径）", style = body(12f, color = c.n600))
            Text(
                "① 采播放链路 PCM（探针模式=采 PCM，先把歌放起来）",
                style = body(12f, color = c.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.n100)
                    .clickableNoRipple { vm.player.spikeCaptureFromStart(); status = "已回到开头并开始采集" }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                "② 导出分离流水线解码 PCM（同一首）",
                style = body(12f, color = c.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.n100)
                    .clickableNoRipple(enabled = !running) {
                        val uri = spikeUri ?: vm.player.currentId?.let { id ->
                            vm.tracksRaw.firstOrNull { it.id == id }?.uri
                        }
                        if (uri == null) { e2eMsg = "先点上面一个测试文件，或先放一首库里的歌"; return@clickableNoRipple }
                        running = true; e2eMsg = "解码中…"
                        scope.launch {
                            try {
                                val out = java.io.File(context.getExternalFilesDir(null), "e2e_decoder.pcm")
                                val info = withContext(Dispatchers.IO) {
                                    AudioDecoder.decodeToPcm16File(context, uri, out)
                                }
                                withContext(Dispatchers.IO) {
                                    java.io.File(context.getExternalFilesDir(null), "e2e_decoder.txt").writeText(
                                        "sampleRate=${info.srcRate}\nchannels=2\nencoding=PCM_16BIT\n" +
                                            "frames=${info.frames}\nsource=$uri\n",
                                    )
                                }
                                e2eMsg = "已导出 ${info.frames} 帧 @ ${info.srcRate}Hz（e2e_decoder.pcm）"
                            } catch (t2: Throwable) {
                                e2eMsg = "❌ ${t2.javaClass.simpleName}: ${t2.message}"
                            } finally {
                                running = false
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            e2eMsg?.let { Text(it, style = body(11f, color = c.n600)) }
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

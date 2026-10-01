package com.shiyin.music.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.shiyin.music.FilesView
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.Track
import com.shiyin.music.data.formatDuration
import com.shiyin.music.data.formatSizeMb
import com.shiyin.music.ui.components.EqBars
import com.shiyin.music.ui.components.DialogAction
import com.shiyin.music.ui.components.DialogActionsRow
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.OrganicSwitch
import com.shiyin.music.ui.components.PasswordField
import com.shiyin.music.ui.components.PillButton
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.clickableNoRipple
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.shadowMd
import com.shiyin.music.ui.components.shadowSm
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.Caprasimo
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.roundToInt
import com.shiyin.music.ui.theme.LocalOrganic

@Composable
fun SettingsHost(
    vm: MainViewModel,
    view: FilesView,
    // v1.3.x 第4项:各设置页共用的滚动状态表(在 AppRoot 里 remember,换路由不销毁)。
    // 按页面名分键,各页互不共享。
    scrollStates: MutableMap<String, ScrollState> = remember { mutableMapOf() },
) {
    // view 来自 AnimatedContent 的 settings:* key 快照(见 AppRoot),不是实时 vm.filesView
    // ——转场期间旧层持有的旧 view 不随 vm.filesView 一起重组,各自渲染各自页面。
    when (view) {
        FilesView.Root -> SettingsRoot(vm, scrollStates)
        FilesView.Clean -> CleanScreen(vm)
        FilesView.Trash -> CleanScreen(vm)   // v1.3.x: 回收站并入清理建议页,旧入口重定向
        FilesView.Folders -> FoldersScreen(vm)
        FilesView.FolderContent -> FolderContentScreen(vm)
        FilesView.Ignored -> FoldersScreen(vm) // v1.3.x: 音频文件夹+已忽略归入同一"文件夹管理"页
        FilesView.Devices -> DevicesScreen(vm)
        FilesView.About -> AboutScreen(vm)
        FilesView.Merges -> MergesScreen(vm)
        FilesView.ImageSources -> ImageSourcesScreen(vm)
        FilesView.LyricsBook -> LyricsBookScreen(vm, scrollStates)
        FilesView.LyricsSources -> LyricsSourcesScreen(vm, scrollStates)
        FilesView.CloudBackup -> CloudBackupScreen(vm, scrollStates)
        FilesView.PlaySettings -> PlaySettingsScreen(vm, scrollStates)
        FilesView.Equalizer -> EqualizerScreen(vm, scrollStates)
        FilesView.Logs -> LogsScreen(vm, scrollStates)
        FilesView.DataManage -> DataManageScreen(vm, scrollStates)
        FilesView.FilesManage -> FilesManageScreen(vm, scrollStates)
    }
}

/**
 * v22: WebDAV 云备份页。
 *
 * 为什么是 WebDAV：零 SDK 依赖（就是 HTTP）、国内可用（坚果云）、
 * 且与本项目"用户自配地址 + 凭据"的既有范式一致（写真源就是这么做的）。
 *
 * 凭据只存 DataStore，**刻意不进备份文件** —— 否则备份包泄露等于云盘账号泄露。
 *
 * v22 第3版重做(用户两轮打回:①"杂乱无比,按钮乱摆,小字标注乱来不严谨";
 * ②"说是云备份结果都在扯坚果云,装了坚果云APP却无法联动"):
 *  - 布局严格对齐设置子页体系(眉题 + 28dp 圆角卡片 + SettingRow 间距);
 *  - 文案通用化:WebDAV 是公开协议,任何兼容网盘/服务器都能用,坚果云只是
 *    其中一个例子;并明确写出**不需要安装网盘 APP**(WebDAV 走服务器地址
 *    直连,与手机上装没装 APP 无关——用户误解的直接来源);
 *  - 操作归位:配置卡里只有输入框和「保存凭据」;「测试连接」「备份到云端」
 *    是操作卡的两个同级按钮;状态信息单独成行,不跟按钮挤。
 */
@Composable
private fun CloudBackupScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    // 进页时载入已保存的凭据（一次性）
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadWebDavConfig() }

    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    // 凭据异步载入后回填输入框（只填一次，避免覆盖用户正在输入的内容）
    androidx.compose.runtime.LaunchedEffect(vm.webDavUrl, vm.webDavUser, vm.webDavPassword) {
        if (!loaded && (vm.webDavUrl.isNotEmpty() || vm.webDavUser.isNotEmpty())) {
            url = vm.webDavUrl; user = vm.webDavUser; pass = vm.webDavPassword; loaded = true
        }
    }
    val tfColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
        focusedBorderColor = c.accent, unfocusedBorderColor = c.n400,
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollStateOf(scrollStates, "cloudbackup"))
            .padding(horizontal = 20.dp).padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader(
            "云备份",
            help = "关于云备份" to
                "任何兼容 WebDAV 协议的网盘或服务器都可以存放备份（坚果云、Nextcloud、群晖 NAS 等）。" +
                "备份通过服务器地址直连传输，不需要安装对应的网盘 APP。\n\n" +
                "坚果云地址固定填 https://dav.jianguoyun.com/dav/（只填主机名会自动补 /dav/）。" +
                "备份会存进这个地址下的 PickUpMusic 文件夹，没有会自动创建；想自己指定位置，" +
                "就写成 …/dav/文件夹名/（该文件夹需已存在）。\n\n" +
                "坚果云等网盘要求在网页端生成「应用密码」，不要填登录密码。" +
                "「测试连接」会真的写一个临时文件再删掉，通过即代表备份能成。",
        ) { vm.navFilesBack() }

        // ── 服务器配置 ──────────────────────────────────────────────────
        SectionLabel("服务器")
        Column(
            Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)).background(c.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("服务器地址", style = body(12f, FontWeight.Normal, c.n600)) },
                placeholder = { Text("例如 https://dav.jianguoyun.com/dav/", style = body(13f, FontWeight.Normal, c.n500)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = body(13f, FontWeight.Normal, c.text), colors = tfColors,
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("账号", style = body(12f, FontWeight.Normal, c.n600)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = body(13f, FontWeight.Normal, c.text), colors = tfColors,
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("密码 / 应用密码", style = body(12f, FontWeight.Normal, c.n600)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = body(13f, FontWeight.Normal, c.text),
                visualTransformation = if (passVisible) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable { passVisible = !passVisible },
                        contentAlignment = Alignment.Center,
                    ) { OIcon(if (passVisible) Lucide.EyeOff else Lucide.Eye, 16.dp, c.n500) }
                },
                colors = tfColors,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(
                    "保存凭据", onClick = { vm.saveWebDavConfig(url, user, pass) },
                    bg = c.accent, textColor = Color.White,
                )
            }
        }

        // ── 操作 ───────────────────────────────────────────────────────
        SectionLabel("备份")
        Column(
            Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)).background(c.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    if (vm.cloudBusy) "处理中…" else "测试连接",
                    onClick = { vm.cloudTest() },
                    bg = null, textColor = c.text, borderColor = c.divider,
                )
                PillButton(
                    "备份到云端",
                    onClick = { vm.cloudUpload() },
                    bg = c.accent, textColor = Color.White,
                )
            }
            vm.cloudMsg?.let {
                Text(it, style = body(12f, FontWeight.Normal, c.a700).copy(lineHeight = 18.sp))
            }
        }

        // ── 云端备份列表 ────────────────────────────────────────────────
        if (vm.cloudFiles.isNotEmpty()) {
            SectionLabel("云端备份 · ${vm.cloudFiles.size} 个")
            Column(
                Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(28.dp))
                    .clip(RoundedCornerShape(28.dp)).background(c.surface)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                vm.cloudFiles.sortedByDescending { it.name }.forEachIndexed { idx, f ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !vm.cloudBusy) { vm.cloudRequestRestore(f.name) }
                            .padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = body(13.5f, FontWeight.SemiBold, c.text),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (f.size > 0) {
                                Text(
                                    "%.1f MB".format(f.size / 1024.0 / 1024.0),
                                    style = body(11f, FontWeight.Normal, c.n500),
                                )
                            }
                        }
                        Text("恢复", style = body(12.5f, FontWeight.Bold, c.accent))
                    }
                    if (idx < vm.cloudFiles.size - 1) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                    }
                }
            }
        }
        Box(Modifier.height(114.dp))
    }

    // v22: 云端恢复也要**先预览再写库** —— 与本地恢复同一道闸。
    // 云端风险其实更高（远端可能是很久以前的备份、也可能是别人的），更不能下完直接覆盖。
    vm.cloudPreview?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!vm.cloudBusy) vm.cloudCancelRestore() },
            containerColor = c.surface,
            title = {
                Text(
                    "从云端恢复「${vm.cloudPendingName ?: ""}」？",
                    style = body(15f, FontWeight.ExtraBold, c.text),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.summary(), style = body(12f, FontWeight.Normal, c.n700).copy(lineHeight = 18.sp))
                    p.lossNote()?.let {
                        Text("⚠ $it", style = body(11.5f, FontWeight.Bold, c.a700).copy(lineHeight = 17.sp))
                    }
                    Text(
                        "合并恢复：同主键覆盖，其余保留。写库前会自动存一份本地快照。",
                        style = body(11f, FontWeight.Normal, c.n600),
                    )
                }
            },
            confirmButton = {
                com.shiyin.music.ui.components.DialogActionsRow(
                    listOf(
                        com.shiyin.music.ui.components.DialogAction("取消") {
                            if (!vm.cloudBusy) vm.cloudCancelRestore()
                        },
                        com.shiyin.music.ui.components.DialogAction("恢复", emphasized = true) {
                            vm.cloudConfirmRestore()
                        },
                    )
                )
            },
        )
    }
}

/**
 * v1.3.x: 使用日志页（设置 → 数据管理 → 日志）。
 *
 * 给**开发排查**用：用户遇到"点了没反应/结果不对/备份失败"时，让他把这一页的内容
 * 分享出来，就不必再靠来回猜。因此这一页的设计重点是"能看懂 + 能一键发出去"，
 * 不是给用户日常看的，不做花哨呈现。
 *
 * 内容的边界写在数据层（[com.shiyin.music.data.log.UsageLog]）：只记"做了什么、
 * 结果如何、数量多少"，**不记歌词文本/搜索词/密钥**——这个文件是要被分享出去的。
 */
@Composable
private fun LogsScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var crashCount by remember { mutableStateOf(0) }
    var bytes by remember { mutableStateOf(0L) }
    var msg by remember { mutableStateOf<String?>(null) }
    // 实际落盘的日志文件清单——用户明确要求"打开就有可以看到的文件"，
    // 所以除了内容，也把文件本身列出来（文件名 + 大小 + 行数）。
    var files by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var crashFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }

    fun reload() {
        com.shiyin.music.data.log.UsageLog.prune(context)
        text = com.shiyin.music.data.log.UsageLog.readAll(context)
        files = com.shiyin.music.data.log.UsageLog.files(context)
        crashFiles = com.shiyin.music.data.log.UsageLog.crashFiles(context)
        crashCount = crashFiles.size
        bytes = com.shiyin.music.data.log.UsageLog.sizeBytes(context)
    }
    // 实时刷新：这一页开着时每 1.5s 重读一次。日志是**追加写**的小文件，重读代价
    // 可忽略；原来只在进页面时读一次，用户必须退出重进才能看到新记录——用户明确
    // 要求"实时展示"，这里就不能是一次性快照。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            reload()
            kotlinx.coroutines.delay(1500)
        }
    }

    fun share() {
        try {
            val dir = java.io.File(context.filesDir, "usage_logs").apply { mkdirs() }
            val f = java.io.File(
                dir,
                "pickupmusic-log-" + java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmm", java.util.Locale.CHINA,
                ).format(java.util.Date()) + ".txt",
            )
            val body = buildString {
                append("拾音 使用日志\n")
                append("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})\n")
                append("版本：v${vm.currentVersion}\n\n")
                append(text.ifBlank { "(没有使用记录)" })
                val crashes = com.shiyin.music.data.log.UsageLog.crashFiles(context)
                if (crashes.isNotEmpty()) {
                    append("\n\n=== 崩溃报告（${crashes.size} 份）===\n")
                    crashes.forEach { cf ->
                        append("\n--- ${cf.name} ---\n")
                        runCatching { append(cf.readText()) }
                    }
                }
            }
            f.writeText(body)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.coverprovider", f,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(send, "分享日志"))
            msg = "已导出 ${"%.0f".format(body.toByteArray().size / 1024.0)} KB"
        } catch (e: Exception) {
            msg = "分享失败：${e.message}"
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scrollStateOf(scrollStates, "logs"))
            .padding(horizontal = 20.dp).padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader(
            "日志",
            help = "日志记了什么" to
                "记录本机的使用情况（启动、扫描、歌词匹配、AI 翻译、备份等的结果），" +
                "用于遇到问题时定位原因。只记「做了什么」，不记录歌词内容、搜索词、账号与密钥。\n\n" +
                "按天分文件，只保留最近 ${com.shiyin.music.data.log.UsageLog.KEEP_DAYS} 天" +
                "；崩溃报告同样按此保留期清理。",
        ) { vm.navFilesBack() }

        SectionLabel("说明")
        Column(
            Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)).background(c.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "当前占用 ${"%.0f".format(bytes / 1024.0)} KB" +
                    if (crashCount > 0) " · $crashCount 份崩溃报告" else "",
                style = body(12f, FontWeight.Normal, c.n600),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(
                    "分享日志", onClick = { share() },
                    bg = c.accent, textColor = Color.White,
                )
                PillButton(
                    "清空", onClick = {
                        com.shiyin.music.data.log.UsageLog.clear(context)
                        reload(); msg = "已清空使用日志"
                    },
                    bg = null, textColor = c.text, borderColor = c.divider,
                )
            }
            msg?.let { Text(it, style = body(12f, FontWeight.Normal, c.a700)) }
        }

        // 文件清单放在内容之前——用户原话"打开就有可以看到的文件了才对"：
        // 先看到有哪些文件（按天一个 + 崩溃报告），再看内容。
        SectionLabel("日志文件")
        Column(
            Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)).background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            if (files.isEmpty() && crashFiles.isEmpty()) {
                Text(
                    "还没有日志文件。首次启动就会生成一份（usage-日期.log）。",
                    style = body(12.5f, FontWeight.Normal, c.n600).copy(lineHeight = 18.sp),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            files.forEachIndexed { i, f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OIcon(Lucide.History, 17.dp, c.n700)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        f.name,
                        style = body(13f, FontWeight.SemiBold, c.text),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${"%.1f".format(f.length() / 1024.0)} KB",
                        style = body(12f, FontWeight.Normal, c.n600),
                    )
                }
                if (i < files.size - 1 || crashFiles.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                }
            }
            crashFiles.forEachIndexed { i, f ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OIcon(Lucide.CircleInfo, 17.dp, c.a700)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        f.name,
                        style = body(13f, FontWeight.SemiBold, c.text),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${"%.1f".format(f.length() / 1024.0)} KB",
                        style = body(12f, FontWeight.Normal, c.n600),
                    )
                }
                if (i < crashFiles.size - 1) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                }
            }
        }

        SectionLabel("最近记录")
        Column(
            Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)).background(c.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (text.isBlank()) {
                Text(
                    "还没有记录。这一页会随着使用自动积累。",
                    style = body(12.5f, FontWeight.Normal, c.n600).copy(lineHeight = 18.sp),
                )
            } else {
                Text(
                    "实时刷新中 · 最新在最上面",
                    style = body(11f, FontWeight.Normal, c.n500),
                )
                Spacer(Modifier.height(8.dp))
                // 最新在上：日志是追加写的，正序看的话新记录永远落在可视区之外，
                // "实时"就白做了。分享出去的那份仍是正序（按时间线读）。
                // 整段一次性渲染（单 Text，不是每行一个 composable）——日志可能有上千行，
                // 逐行组合会明显拖慢这一页的滚动。
                Text(
                    text.lines().reversed().joinToString("\n"),
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = c.n700,
                        lineHeight = 16.sp,
                    ),
                )
            }
        }
        Box(Modifier.height(114.dp))
    }
}

@Composable
private fun SectionLabel(text: String, color: Color? = null) {
    val c = LocalOrganic.current
    // v1.3.x 设计打磨:眉题化——12.5 ExtraBold + 1.2sp 字距。小而疏的标签只做
    // 分组节奏标记,视觉重量全部留给卡片与页面标题(Caprasimo 只出现在标题,
    // 角色分工干净)。色保 n700:12.5sp 小字下 n600 对比度不足。
    // 需要与正文拉开区分的页面(如拾光记忆的分组)可传 color 走主题强调色。
    Text(
        text,
        style = body(12.5f, FontWeight.ExtraBold, color ?: c.n700).copy(letterSpacing = 1.2.sp),
    )
}

@Composable
private fun SubPageHeader(title: String, help: Pair<String, String>? = null, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BackButton(onBack)
        Text(title, style = heading(24), modifier = Modifier.weight(1f))
        if (help != null) HelpInfoButton(help.first, help.second)
    }
}

/**
 * A37/A38: 「？」式说明按钮 —— 页面正文不再堆成段小字，必要的解释点开再看。
 * 只在该说的话确实「不看就可能用错」时才挂；自明的一律不写。
 */
@Composable
private fun HelpInfoButton(title: String, text: String) {
    val c = LocalOrganic.current
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier.size(28.dp).clip(CircleShape).clickableNoRipple { open = true },
        contentAlignment = Alignment.Center,
    ) { OIcon(Lucide.CircleInfo, 17.dp, c.n500) }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            containerColor = c.surface,
            title = { Text(title, style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = { Text(text, style = body(12.5f, FontWeight.Normal, c.n700).copy(lineHeight = 19.sp)) },
            confirmButton = { DialogActionsRow(listOf(DialogAction("知道了") { open = false })) },
        )
    }
}

/**
 * v23: 设置页行骨架 —— 所有设置行的**唯一**排版来源。
 * 统一 padding(vertical=13.dp)、图标位 19dp、行内间距 13dp、副标题 12.5sp + top 2dp。
 * 抽出来的原因:手写行(识别缓存 11.5sp、备份与恢复 11sp,且都没有 top padding)与
 * SettingRow/CardEntryRow 各自为政,同类型行高度不一致(用户反馈"各个功能占据的
 * 高度都不统一")。此后新增设置行一律走这里,不要再手写 Row。
 */
@Composable
private fun SettingsRowScaffold(
    title: String,
    sub: String? = null,
    subColor: Color? = null,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    divider: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalOrganic.current
    Row(
        Modifier
            .fillMaxWidth()
            // v1.3.10 需求 A20:整行点击去涟漪(用户不喜欢灰块反馈)
            .then(if (onClick != null) Modifier.clickableNoRipple(onClick = onClick) else Modifier)
            // padding 在前、heightIn 在后：56dp 是"带开关行的实际高度"(开关 30dp +
            // 上下 padding 各 13dp)。普通行只有一行标题(21dp)，原本只有 47dp、比开关
            // 行矮——heightIn 把它抬到同一高度，内容垂直居中，行高节奏统一(第5项)。
            .padding(vertical = 13.dp)
            .heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // 图标并入标题这一行(与标题同行居中)，副标题缩进 32dp 与标题左缘对齐
            // (19 图标 + 13 间距)。这样"图标对齐名字"在单行/双行、有无控件下都成立。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                leading()
                Text(title, style = body(15f, FontWeight.SemiBold, c.text))
            }
            // 副标题只在这一行确实有短话要说时出现，且必须短到一行放得下(见设置页定稿)。
            if (sub != null) {
                Text(
                    sub,
                    style = body(12.5f, FontWeight.Normal, subColor ?: c.n600),
                    modifier = Modifier.padding(top = 2.dp, start = 32.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.align(Alignment.CenterVertically)) { trailing() }
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}

@Composable
private fun CardEntryRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    sub: String?,
    divider: Boolean,
    trailingText: String? = null,
    onClick: () -> Unit,
) {
    val c = LocalOrganic.current
    SettingsRowScaffold(
        title = title,
        sub = sub,
        leading = { OIcon(icon, 19.dp, iconTint) },
        divider = divider,
        onClick = onClick,
        trailing = {
            // 尾注文字 + 箭头必须**横排**——骨架把 trailing 包进了一个 Box
            // （Box 会叠放子项），直接并列两个 composable 会叠在一起（用户实测：
            // 睡眠/云备份/日志的尾注文字和箭头重叠）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (trailingText != null) {
                    Text(trailingText, style = body(12.5f, FontWeight.Normal, c.n600), maxLines = 1)
                }
                OIcon(Lucide.ChevronRight, 18.dp, c.n500)
            }
        },
    )
}

/**
 * v1.3.x 第4项：取（或建）某个设置页共用的滚动状态。
 *
 * 状态表本身在 AppRoot 里 `remember`（在 AnimatedContent 之上），所以换路由把
 * 这个页面销毁再回来时，ScrollState 实例还在、scrollOffset 还在——这就是
 * "从列表较下方进入子页，返回后仍停在原位置"的实现。键按页面名区分，
 * 各页各一份，不共享。
 */
private fun scrollStateOf(states: MutableMap<String, ScrollState>, key: String): ScrollState =
    states.getOrPut(key) { ScrollState(0) }

// ── settings root (v2.0: 文件管理 card ← 已忽略/重新扫描, 通用 cleaned) ──────
@Composable
private fun SettingsRoot(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    // v1.1+: 进入设置页时统计识别缓存占用
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshRecognitionCacheSize() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateOf(scrollStates, "root"))
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton { vm.settingsOpen = false; vm.sel = emptyMap() }
            Text("设置", style = heading(24))
        }

        // ── 通用:全局项(深色 / 睡眠) ────────────────────────────────────────
        // 用户定稿:这一组要有「通用」眉题——没有眉题的裸卡片会让它看起来不像一个分区。
        SectionLabel("通用")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            SettingRow(Lucide.Moon, "深色模式", null, divider = true) {
                OrganicSwitch(vm.darkTheme) { vm.setDark(!vm.darkTheme) }
            }
            // v1.3.x: 睡眠定时可编辑——整行点击弹窗(时长档位/本曲结束/关闭),
            // 复用播放菜单同套 PlayerController 接口;不再只读展示。
            var sleepEditOpen by remember { mutableStateOf(false) }
            CardEntryRow(
                Lucide.Clock, c.n700, "睡眠定时",
                null, divider = false, trailingText = vm.player.sleepStatusText(),
            ) { sleepEditOpen = true }
            if (sleepEditOpen) SleepTimerEditDialog(vm) { sleepEditOpen = false }
        }

        // ── 资源管理:文件 / 识别 / 写真 / 歌词 / 歌手 ────────────────────────
        SectionLabel("资源管理")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
                        // v1.3.x 第3项:「文件管理」是一个**真正的二级入口**——点进去
            // 才是它的四项（自动保存识别资源/文件夹管理/清理建议/重新识别），
            // 不再平铺在一级页里（此前平铺导致一级界面太乱、层级错位）。
            CardEntryRow(
                Lucide.Folder, c.n700, "文件管理",
                null,
                divider = true,
            ) { vm.navFilesTo(FilesView.FilesManage) }
            CardEntryRow(
                Lucide.User, c.n700, "写真源",
                null,
                divider = true,
            ) { vm.navFilesTo(FilesView.ImageSources) }
            // v1.3.x 图标审计:歌词本入口用 Library(书本=歌词本,未占用);振假名默认
            // 行保留 Captions(注音语义),两处不再撞图标。
            CardEntryRow(
                Lucide.Library, c.n700, "歌词本",
                null,
                divider = true,
            ) { vm.navFilesTo(FilesView.LyricsBook) }
            CardEntryRow(
                Lucide.Users, c.n700, "歌手合并",
                null,
                divider = false,
            ) { vm.navFilesTo(FilesView.Merges) }
        }

        // v1.3.11:「拾光记忆」已移到「拾光 Agent 设置」里(用户定调:它属于 Agent 域,
        // 不该混在文件/资源设置里)。这里不再保留第二个入口,避免同一页两处可达。

        // v1.3.10 需求 A24:「播放」与「数据管理」**拆成两张卡**。
        // 此前两项共用一张卡(理由是"不为只有一个子项的类别建独立栏目标题"),但用户
        // 明确觉得"两个不相关的组件连在一起"很怪 → 各占一张卡、各一行,视觉上断开。
        // 仍然不给它们加栏目标题(避免空壳分类,与原决定的意图一致)。
        // v1.3.10(二改):用户反馈"两张卡太大了,应该和其他选项一样大小"——单行卡原来
        // 沿用了多行卡的 6dp 上下留白,单行时显胖。压到 2dp,与其它单行行高节奏一致。
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 2.dp),
        ) {
            CardEntryRow(
                Lucide.PlayOutline, c.n700, "播放",
                null,
                divider = false,
            ) { vm.navFilesTo(FilesView.PlaySettings) }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 2.dp),
        ) {
            CardEntryRow(
                Lucide.Database, c.n700, "数据管理",
                null, divider = false,
            ) { vm.navFilesTo(FilesView.DataManage) }
        }

        // v1.5: about entry
        Row(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .clickable { vm.navFilesTo(FilesView.About) }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OIcon(Lucide.CircleInfo, 19.dp, c.n700)
            Text("关于我们", style = body(15f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
            // v22: 版本号改读 BuildConfig（原来写死 "拾音 v1.3.0"，bump 版本后这里不会跟着变）；
            // 同时把「发现更新」透出来，不用进关于页也能看到。
            val aboutHasUpdate = vm.updateAvailable()
            if (aboutHasUpdate) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(c.a700))
            }
            Text(
                if (aboutHasUpdate) "v${vm.currentVersion} · 发现更新" else "拾音 v${vm.currentVersion}",
                style = body(12.5f, if (aboutHasUpdate) FontWeight.Bold else FontWeight.Normal,
                    if (aboutHasUpdate) c.a700 else c.n600),
            )
            OIcon(Lucide.ChevronRight, 18.dp, c.n500)
        }

        Box(Modifier.height(114.dp))
    }

}

/**
 * v1.3.x 第3项:「文件管理」二级页 —— 一级界面只剩一个入口，四项集中在这里。
 *
 * 内容 = 自动保存识别资源（保留"AI 翻译结果始终保存"备注）+ 文件夹管理 +
 * 清理建议 + 重新扫描文件。此前它们平铺在一级页导致层级错位、一级界面太乱。
 */
@Composable
private fun FilesManageScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateOf(scrollStates, "filesmanage"))
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader("文件管理") { vm.navFilesBack() }
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            // 「识别资源」= 联网识别到的歌词/封面。副标题只留**用户不知道的那一条**：
            // AI 翻译结果不受这个开关影响、总是保存（token 是用户明确花掉的）。
            SettingRow(
                Lucide.Download, "自动保存识别资源",
                "AI 翻译结果始终保存",
                divider = true,
            ) {
                OrganicSwitch(vm.autoSaveRecognition) { vm.setAutoSaveRecognition(!vm.autoSaveRecognition) }
            }
            CardEntryRow(
                Lucide.Folder, c.n700, "文件夹管理",
                null,
                divider = true,
            ) { vm.navFilesTo(FilesView.Folders) }
            CardEntryRow(
                // v22 缝补:原用 Brush(画笔)表示"清理",语义不搭——清理建议页里主要是
                // 重复文件/短音频/回收站,Trash(垃圾桶)才对得上。
                Lucide.Trash, c.a700, "清理建议",
                null,
                divider = true,
            ) { vm.openClean() }
            // 名字直接说清动作（用户："如果功能过于模糊，如「重新识别」，那就结合备注
            // 提示小字改为「重新扫描文件」即可"）——改名之后就不需要那行小字了。
            CardEntryRow(
                Lucide.RotateCcw, c.n700, "重新扫描文件",
                null,
                divider = false,
            ) { vm.rescanWithAnimation() }
        }
        Box(Modifier.height(114.dp))
    }
}

/**
 * v1.3.x: 「数据管理」子页 —— 从设置一级界面整体搬下来（用户："数据管理也要跟
 * 播放一样，是点进去才有更多设置的"）。
 *
 * 内容与一级界面时期逐字一致：识别缓存（占用 + 清理）、备份与恢复（导出/恢复 +
 * 状态行 + 本地快照）、云备份入口、日志入口。备份相关的状态与启动器跟着一起搬
 * ——它们只服务这一页，留在一级界面只会让那个 Composable 继续背着一堆用不到的变量。
 */
@Composable
private fun DataManageScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v1.2.0 阶段三：备份/恢复的提示文本（导出成功/导入统计/失败）
    var backupMsg by remember { mutableStateOf<String?>(null) }
    // v22: 恢复前的预览与确认（原来选完文件直接覆盖写库，误点一个旧备份就冲掉当前数据）
    var pendingRestore by remember { mutableStateOf<ByteArray?>(null) }
    var pendingPreview by remember { mutableStateOf<com.shiyin.music.data.db.BackupPreviewer.Preview?>(null) }
    var restoreBusy by remember { mutableStateOf(false) }
    // v22: 最近一次导出的文件，用于「分享」
    var lastExport by remember { mutableStateOf<java.io.File?>(null) }

    fun shareFile(file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.coverprovider", file,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(send, "分享备份"))
        } catch (e: Exception) {
            backupMsg = "分享失败：${e.message}"
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = vm.exportBackupZip()
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                // v22: 备份里**含 API Key / 供应商配置**（用户定调：要进，否则换机最痛），
                // 所以导出后必须明确提醒一句"含密钥"，让用户知道这个文件不能随便发。
                backupMsg = "已备份（${"%.1f".format(bytes.size / 1024.0 / 1024.0)} MB）· " +
                    "含密钥，请妥善保管"
                lastExport = null
            } catch (e: Exception) { backupMsg = "备份失败：${e.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取备份文件")
                // v22: 先预览、不写库 —— 让用户看清会恢复什么、会跳过多少条
                pendingPreview = vm.previewBackup(bytes)
                pendingRestore = bytes
                backupMsg = null
            } catch (e: Exception) { backupMsg = "无法读取备份：${e.message}" }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateOf(scrollStates, "datamanage"))
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader("数据管理") { vm.navFilesBack() }

        // ── 缓存 ──────────────────────────────────────────────────────────
        SectionLabel("缓存")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            SettingsRowScaffold(
                title = "识别缓存",
                // v1.3.x:识别缓存用 Database(存储/数据库语义),与文件夹管理(Folder)去重
                leading = { OIcon(Lucide.Database, 19.dp, c.n700) },
                divider = false,
                // 占用大小是"要不要清"的唯一判断依据，所以留——但走右侧尾注（与标题
                // 同一行），不再占一整行副标题；"不含人工修正"那半句是自证型说明，删。
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            formatCacheSize(vm.recognitionCacheBytes),
                            style = body(12.5f, FontWeight.Normal, c.n600),
                        )
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(c.n200)
                                .clickable { vm.clearRecognitionCache() }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) { Text("清理缓存", style = body(12.5f, FontWeight.Bold, c.text)) }
                    }
                },
            )
        }

        // ── 本地备份 ──────────────────────────────────────────────────────
        SectionLabel("本地备份")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            // v22: 备份范围从"修正数据"扩到**用户资产** —— 歌单（含「我的喜欢」）、
            // 手动导入的歌词、手动曲序、忽略的文件夹、全部设置与 API Key 都进了备份包。
            // 包格式升级为 zip（本地手选的歌手写真/专辑封面要跟着走，否则换机后是裂图）。
            SettingsRowScaffold(
                title = "备份与恢复",
                // 默认那行"歌单 · 歌词 · 排序 · 设置与密钥 · 本地图片"是**二级界面的
                // 内容摘要**，按规矩不在入口行重复；状态信息改到行下方（见下）。
                leading = { OIcon(Lucide.Shield, 19.dp, c.n700) },
                trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // v1.3.x UI 一致性:三个动作改用项目统一的**描边胶囊按钮**
                    // (PillButton bg=null+borderColor)——原 c.n100 实心浅块在米色卡片上
                    // 读作"白按钮",与新设计语言(云备份页/各处次要按钮)不一致。
                    if (lastExport != null) {
                        PillButton(
                            "分享", onClick = { lastExport?.let { shareFile(it) } },
                            bg = null, textColor = c.text, borderColor = c.divider,
                            fontSize = 12.5f, padH = 14.dp, padV = 7.dp,
                        )
                    }
                    PillButton(
                        "备份",
                        onClick = {
                            backupMsg = null
                            exportLauncher.launch(
                                "pickupmusic-backup-" + java.text.SimpleDateFormat(
                                    "yyyyMMdd-HHmm", java.util.Locale.CHINA,
                                ).format(java.util.Date()) + ".zip"
                            )
                        },
                        bg = null, textColor = c.text, borderColor = c.divider,
                        fontSize = 12.5f, padH = 14.dp, padV = 7.dp,
                    )
                    PillButton(
                        "恢复",
                        onClick = {
                            backupMsg = null
                            // v22: 不过滤 MIME —— 有些文件来源会把 .zip 报成
                            // application/octet-stream，按 application/zip 过滤会让用户
                            // **在选择器里看不到自己的备份文件**。反正读入端
                            // （BackupArchive.read）本来就自动识别 zip / 纯 JSON。
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        bg = null, textColor = c.text, borderColor = c.divider,
                        fontSize = 12.5f, padH = 14.dp, padV = 7.dp,
                    )
                }
                },
            )
            // 状态信息**单独占一行**：这一行右侧并排三个胶囊按钮，副标题位置只剩很窄
            // 一条，"已备份 12.3 MB · 含密钥，请妥善保管"这种必然被省略号截断——而
            // "含密钥"恰恰是最不能被截断的一句。放到整行宽度下就没有截断问题。
            (if (restoreBusy) "正在恢复…" else backupMsg)?.let { msg ->
                Text(
                    msg,
                    style = body(11.5f, FontWeight.Normal, if (backupMsg != null) c.a700 else c.n600),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
            }
            // v22: 本地自动快照（每次启动超过 24h 自动写一份，滚动保留 3 份）——
            // 防手滑/防误操作，与"云备份"互补。
            val snaps = vm.listSnapshots()
            if (snaps.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "本地快照（最多保留 3 份）",
                        style = body(11f, FontWeight.Normal, c.n600),
                    )
                    for (f in snaps) {
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.n200)
                                .clickable {
                                    scope.launch {
                                        try {
                                            pendingPreview = vm.previewBackup(f.readBytes())
                                            pendingRestore = f.readBytes()
                                        } catch (e: Exception) { backupMsg = "快照读取失败：${e.message}" }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                f.name.removeSuffix(".zip") +
                                    "（${"%.1f".format(f.length() / 1024.0 / 1024.0)} MB）",
                                style = body(11.5f, FontWeight.Normal, c.text),
                                modifier = Modifier.weight(1f),
                            )
                            Text("恢复", style = body(11.5f, FontWeight.Bold, c.accent))
                        }
                    }
                }
            }
        }

        // ── 云端与记录 ────────────────────────────────────────────────────
        SectionLabel("云端与记录")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            // v22: 云备份入口（WebDAV：坚果云 / Nextcloud / 群晖）。
            // 图标用 ArrowUpDown（上传/下载），与「备份与恢复」的 Shield 区分开。
            // "有没有配置"是一眼要看的状态 → 走右侧尾注，不占一整行小字。
            CardEntryRow(
                Lucide.ArrowUpDown, c.n700, "云备份（WebDAV）",
                null, divider = true,
                trailingText = if (vm.webDavUrl.isBlank()) "未配置" else "已配置",
            ) { vm.navFilesTo(FilesView.CloudBackup) }
            // v1.3.x: 使用日志（开发排查用）—— 记录本机使用事件，按天分文件。
            // 只记"发生了什么"（页面/技能/识别/备份），不记歌词内容与密钥。
            CardEntryRow(
                Lucide.History, c.n700, "日志",
                null, divider = false,
                trailingText = "保留 ${com.shiyin.music.data.log.UsageLog.KEEP_DAYS} 天",
            ) { vm.navFilesTo(FilesView.Logs) }
        }

        Box(Modifier.height(114.dp))
    }

    // v22: 恢复前确认 —— 先说清"会恢复什么、会跳过多少条"，确认后才写库；
    // 写库前还会自动存一份快照（恢复失败或反悔都能撤回）。
    pendingPreview?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!restoreBusy) { pendingPreview = null; pendingRestore = null } },
            containerColor = c.surface,
            title = { Text("恢复备份？", style = body(16f, FontWeight.ExtraBold, c.text)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        p.summary(),
                        style = body(12f, FontWeight.Normal, c.n700).copy(lineHeight = 18.sp),
                    )
                    p.lossNote()?.let {
                        Text("⚠ $it", style = body(11.5f, FontWeight.Bold, c.a700).copy(lineHeight = 17.sp))
                    }
                    if (p.remapSkipped) {
                        Text(
                            "这是旧版备份（不含文件索引），将按原样恢复。",
                            style = body(11f, FontWeight.Normal, c.n600),
                        )
                    }
                    Text(
                        "合并恢复：同主键覆盖，其余保留。写库前会自动存一份本地快照。",
                        style = body(11f, FontWeight.Normal, c.n600),
                    )
                }
            },
            confirmButton = {
                com.shiyin.music.ui.components.DialogActionsRow(
                    listOf(
                        com.shiyin.music.ui.components.DialogAction("取消") {
                            if (!restoreBusy) { pendingPreview = null; pendingRestore = null }
                        },
                        com.shiyin.music.ui.components.DialogAction("恢复", emphasized = true) {
                            val bytes = pendingRestore
                            if (bytes != null && !restoreBusy) {
                                restoreBusy = true
                                scope.launch {
                                    try {
                                        vm.snapshotBeforeRestore()
                                        backupMsg = "恢复完成：${vm.importBackupZip(bytes)}"
                                    } catch (e: Exception) {
                                        backupMsg = "恢复失败：${e.message}"
                                    } finally {
                                        restoreBusy = false
                                        pendingPreview = null
                                        pendingRestore = null
                                    }
                                }
                            }
                        },
                    )
                )
            },
        )
    }
}

/** v1.1+: 格式化识别缓存占用。null=未统计 → "—"。 */
private fun formatCacheSize(bytes: Long?): String {
    val b = bytes ?: return "—"
    if (b < 1024) return "${b}B"
    if (b < 1024 * 1024) return "%.1f KB".format(b / 1024.0)
    return "%.1f MB".format(b / (1024.0 * 1024.0))
}

/**
 * v1.3.x: 设置页睡眠定时编辑弹窗——档位选择(15/30/60 分)、本曲结束后、
 * 关闭。复用播放菜单同一套 PlayerController 接口,两处改的是同一状态。
 */
@Composable
private fun SleepTimerEditDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = LocalOrganic.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("睡眠定时", style = heading(18)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { vm.player.setSleepEndOfTrack(); onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("本曲结束后暂停", style = body(14.5f, FontWeight.Normal, c.text), modifier = Modifier.weight(1f))
                    if (vm.player.sleepMode == 2) OIcon(Lucide.CheckBold, 16.dp, c.accent)
                }
                for (min in listOf(15, 30, 60)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { vm.player.setSleepTimer(min); onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$min 分钟后停止", style = body(14.5f, FontWeight.Normal, c.text), modifier = Modifier.weight(1f))
                        if (vm.player.sleepChosenMin == min && vm.player.sleepLeftSec > 0) {
                            OIcon(Lucide.CheckBold, 16.dp, c.accent)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { vm.player.setSleepTimer(0); onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("关闭定时", style = body(14.5f, FontWeight.Normal, c.a700), modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            DialogActionsRow(listOf(DialogAction("关闭") { onDismiss() }))
        },
    )
}

// ── v1.3.x 歌词本设置子页(振假名默认开关/自动校验/歌词颜色) ──────────────────
// 三个设置都只影响全屏歌词本页:振假名两项仅对日语歌生效(hasKana 判定,
// 中英文歌开关不可见、永不触发联网校验);歌词颜色 default=跟随封面取色。
@Composable
private fun LyricsBookScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateOf(scrollStates, "lyricsbook"))
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader("歌词本") { vm.navFilesBack() }

        // 用户定稿：这一页按**歌词 / 翻译**两块分开（"一个设置两栏区分歌词、翻译两个
        // 板块，这样视觉上才不会挤在一起"）。此前六行挤在一张卡里，振假名与翻译两组
        // 语义完全不同的开关混排，读起来没有边界。
        SectionLabel("歌词")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            // v1.3.x:图标语义贴合——自动匹配歌词用 Captions(字幕/歌词,从设置主页
            // 挪过来:用户指定放歌词本页最上面);振假名默认开用"ア"(片假名,furigana
            // 本体,与歌词页 あ 开关联动但设置页用片假名区分);自动校验换 BadgeCheck
            // (徽章+勾=已验证;原 Check 单勾语义太弱,用户两轮反馈仍觉不符)。
            // 单行布局:图标与文字同行居中,不再错位。
            // 名字已自证——"无歌词时自动在线获取，确认后保存到本地"那行小字既不完整
            // 也不必要（用户点名它"长长一段根本显示不完"）。
            SettingRow(Lucide.Captions, "自动匹配歌词", null, divider = true) {
                OrganicSwitch(vm.autoMatch) { vm.setAutoMatch(!vm.autoMatch) }
            }
            // v1.3.10 需求 A29:歌词源管理入口(用户指定位置:就在「自动匹配歌词」下面)
            SettingsRowScaffold(
                title = "歌词源管理",
                leading = { OIcon(Lucide.ListMusic, 19.dp, c.n700) },
                divider = true,
                trailing = { OIcon(Lucide.ChevronRight, 16.dp, c.n400) },
                onClick = { vm.navFilesTo(FilesView.LyricsSources) },
            )
            SettingRowGlyph(glyph = "ア", "日语歌默认开振假名", divider = true) {
                OrganicSwitch(vm.furiganaDefaultOn) { vm.setFuriganaDefaultOn(!vm.furiganaDefaultOn) }
            }
            SettingRow(Lucide.BadgeCheck, "日语歌自动校验振假名", null, divider = true) {
                OrganicSwitch(vm.furiganaAutoVerify) { vm.setFuriganaAutoVerify(!vm.furiganaAutoVerify) }
            }
            // 歌词颜色三档:默认(跟随封面)/白色/黑色。
            // v1.3.x 设计打磨:文字 pill 换色样 swatch——选颜色的控件直接展示颜色
            // 本身(白=白圆、黑=黑圆、跟随封面=黑白双色对拼),选中态 accent 描边环。
            // 色样自带白/黑底描边(n300)保证在任何卡面(浅/深主题)上都看得见。
            SettingsRowScaffold(
                title = "歌词颜色",
                // v1.3.x 图标审计:歌词颜色用 CircleFilled(色样圆,行内已有三色样
                // 呼应;原 Brush 与清理建议重复)。清理建议保留 Brush(清洁刷)。
                leading = { OIcon(Lucide.CircleFilled, 19.dp, c.n700) },
                // v1.3.x 行高统一:这里原来是手写 Row——横竖都照抄了骨架的数值
                // (13dp/19dp/15sp),但没有骨架的"副标题占位行",于是同一张卡里
                // 比上下邻居矮一整行(用户反复反馈的高度不齐)。改走骨架。
                divider = true,
                trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for ((mode, label, showWhite) in listOf(
                        Triple("default", "跟随封面", false),
                        Triple("white", "白色", true),
                        Triple("black", "黑色", false),
                    )) {
                        val active = vm.lyricsColorMode == mode
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.setLyricsColorMode(mode) }.padding(2.dp),
                        ) {
                            val content: @Composable () -> Unit = if (mode == "default") {
                                // 跟随封面:左白右黑对拼,示意"文字色随封面明暗切换"
                                {
                                    Row(Modifier.fillMaxSize()) {
                                        Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
                                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1A1A1E)))
                                    }
                                }
                            } else {
                                { Box(Modifier.fillMaxSize().background(if (showWhite) Color.White else Color(0xFF1A1A1E))) }
                            }
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .border(2.5.dp, if (active) c.accent else c.n300, CircleShape)
                                    .padding(2.dp)
                                    .clip(CircleShape),
                            ) { content() }
                            Text(label, style = body(11f, if (active) FontWeight.ExtraBold else FontWeight.Normal, if (active) c.text else c.n600))
                        }
                    }
                }
                },
            )
            // v1.3.10 需求 A26:时间轴校对入口常显。
            // 默认行为:只有**未同步**(无时间戳、需要人工打轴)的歌词显示校对入口;
            // 开了这个开关后,已同步的歌词也常显(校准未必一次到位,方便反复微调)。
            SettingRow(
                Lucide.Crosshair, "始终显示时间轴校对",
                "已同步的歌词也能随时进入校对", divider = false,
            ) {
                OrganicSwitch(vm.lyricsCalibAlwaysOn) { vm.setLyricsCalibAlwaysOn(!vm.lyricsCalibAlwaysOn) }
            }
        }
        // 外语自动翻译:进入歌词本时外语歌自动触发 AI 翻译(默认关——AI 永远用户主动
        // 触发,不静默烧 token)。默认显示译文:有保存译文时进页面直接给看(译完即存
        // 的恢复逻辑,默认开)。图标区分(两个不能一模一样):自动翻译=Languages
        // (双语对照),显示译文=Captions(字幕)。
        SectionLabel("翻译")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            // v1.3.10:「翻译按钮显示」三档(不显示/外文较多时显示/检测到外文就显示)。
            // 按用户定稿:标题 + 副标题 + 下方**三段式离散滑动**控件——不是三个按钮堆叠。
            TranslateButtonModeRow(vm)
            SettingRow(Lucide.Languages, "外语歌自动翻译", null, divider = true) {
                OrganicSwitch(vm.translateAutoForeign) { vm.setTranslateAutoForeign(!vm.translateAutoForeign) }
            }
            SettingRow(Lucide.Captions, "默认显示译文", null, divider = true) {
                OrganicSwitch(vm.translateDefaultShow) { vm.setTranslateDefaultShow(!vm.translateDefaultShow) }
            }
            // A36: 失败提示默认关——用户嫌"翻译失败"toast 太频繁,想安静时就不弹。
            SettingRow(Lucide.Bell, "翻译失败时提示", null, divider = false) {
                OrganicSwitch(vm.translateErrorToast) { vm.setTranslateErrorToast(!vm.translateErrorToast) }
            }
        }
        Box(Modifier.height(114.dp))
    }
}

// ── v1.3.x 播放设置子页(用户 2026-09-22 指定分组)────────────────────────────
// 六项:无缝播放(从设置主页搬来)/音量均衡/单声道音频/允许与其他应用同时播放/均衡器。
// 均衡器是 5 段 EQ 开关;具体曲线走系统 Equalizer(需要播放器持有 audioSessionId)。
@Composable
private fun PlaySettingsScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateOf(scrollStates, "playsettings"))
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader("播放") { vm.navFilesBack() }
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            // 名字就是行业通行说法，不需要那行"专辑连播时曲目间不留空隙"的解释；
            // 本卡其余行都没有副标题，单独一行带小字会让卡片内高度参差。
            SettingRow(Lucide.Infinity, "无缝播放", null, divider = true) {
                OrganicSwitch(vm.gapless) { vm.setGapless(!vm.gapless) }
            }
            SettingRow(Lucide.Volume2, "音量均衡", null, divider = true) {
                OrganicSwitch(vm.volumeNormalize) { vm.setVolumeNormalize(!vm.volumeNormalize) }
            }
            // 单声道**再次下线**(2026-09-23 实测):官方 ChannelMixingAudioProcessor +
            // 与 media3 默认实现逐行一致的 buildAudioSink 覆写,装机后依然"播不了"。
            // 两次实测同一结论——这条 ExoPlayer 管线上任何 buildAudioSink 覆写都会
            // 让音频渲染失效。用户规矩:宁可缺,不能给一个"开了播不了"的开关。
            // 详见 PlaybackService 顶部的撤回说明。
            SettingRow(Lucide.Layers, "允许与其他应用同时播放", null, divider = true) {
                OrganicSwitch(vm.allowConcurrentPlayback) { vm.setAllowConcurrentPlayback(!vm.allowConcurrentPlayback) }
            }
            CardEntryRow(
                Lucide.SlidersVertical, c.n700, "均衡器", null,
                divider = false,
            ) { vm.navFilesTo(FilesView.Equalizer) }
        }
        Box(Modifier.height(114.dp))
    }
}

// ── v1.3.x 均衡器子页(用户反馈"均衡器不是开关,是多频段调节")──────────────────
// 5 段 EQ:每段一条滑杆调增益(±15 dB),顶部总开关。增益单位毫贝(mB),
// 经 AUDIO_SETTINGS 命令下发到服务进程的 android.media.audiofx.Equalizer。
private val EQ_BAND_LABELS = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")

@Composable
private fun EqualizerScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateOf(scrollStates, "equalizer"))
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader("均衡器") { vm.navFilesBack() }
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            SettingRow(Lucide.SlidersVertical, "启用均衡器", null, divider = true) {
                OrganicSwitch(vm.equalizerOn) { vm.setEqualizerOn(!vm.equalizerOn) }
            }
            // 5 段增益滑杆:每段一行(标签 + 滑杆 + 当前 dB)。
            for (i in EQ_BAND_LABELS.indices) {
                val stored = vm.equalizerBands.getOrElse(i) { 0 }
                // v23:拖动期间只改**本地** state,松手(onValueChangeFinished)才提交一次。
                // 原实现把 setEqualizerBand 挂在 onValueChange 上——那是连续回调(约
                // 60-120 次/秒),每次都写一遍 DataStore、并因设置流发射再发一条跨进程
                // AUDIO_SETTINGS,服务端每帧重设 5 个频段 + 重配 LoudnessEnhancer。
                // 一次拖动 = 上百次磁盘写 + 上百次 IPC,拖起来明显卡。
                var drag by remember(stored) { mutableStateOf<Float?>(null) }
                val mB = (drag ?: stored.toFloat()).toInt()
                Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(EQ_BAND_LABELS[i], style = body(12.5f, FontWeight.SemiBold, c.text), modifier = Modifier.width(56.dp))
                        androidx.compose.material3.Slider(
                            value = drag ?: stored.toFloat(),
                            onValueChange = { drag = it },
                            onValueChangeFinished = {
                                drag?.let { vm.setEqualizerBand(i, it.toInt()) }
                                drag = null
                            },
                            valueRange = -1500f..1500f,
                            enabled = vm.equalizerOn,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = c.accent,
                                activeTrackColor = c.accent,
                                inactiveTrackColor = c.n200,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (mB == 0) "0 dB" else "%+.1f dB".format(mB / 100f),
                            style = body(11f, FontWeight.Normal, c.n600),
                            modifier = Modifier.width(56.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
        Box(Modifier.height(114.dp))
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    divider: Boolean,
    trailing: @Composable () -> Unit,
) {
    val c = LocalOrganic.current
    SettingsRowScaffold(
        title = title,
        sub = sub,
        leading = { OIcon(icon, 19.dp, c.n700) },
        trailing = trailing,
        divider = divider,
    )
}

/** [SettingRow] 的字符变体:图标位渲染文字字符(如振假名"ア"),与图标行视觉等重。 */
@Composable
private fun SettingRowGlyph(
    glyph: String,
    title: String,
    divider: Boolean,
    trailing: @Composable () -> Unit,
) {
    val c = LocalOrganic.current
    SettingsRowScaffold(
        title = title,
        leading = {
            Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
                Text(glyph, style = body(17f, FontWeight.ExtraBold, c.n700))
            }
        },
        trailing = trailing,
        divider = divider,
    )
}

// ── v1.4 audio folders subpage · v1.3.x 合并已忽略 = 「文件夹管理」页 ────────
// 音频文件夹与已忽略的文件夹归入同一页(两段),不再分两个子页来回跳。
@Composable
private fun FoldersScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader(
            "文件夹管理",
            help = "忽略与删除" to
                "忽略的文件夹不再出现在音乐库，可随时在这里恢复；删除的文件先进入回收站（见清理建议页）。",
        ) { vm.navFilesBack() }

        // ── 段1: 音频文件夹 ──
        SectionLabel("音频文件夹 · ${vm.foldersMap().size}")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((path, ts) in vm.foldersMap()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .shadowSm(RoundedCornerShape(28.dp))
                        .clip(RoundedCornerShape(28.dp))
                        .background(c.surface)
                        .clickable { vm.folderKey = path; vm.navFilesTo(FilesView.FolderContent) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.n200),
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.Folder, 20.dp, c.n700) }
                    Column(Modifier.weight(1f)) {
                        Text(path, style = body(15f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${ts.size} 个文件 · ${formatSizeMb(ts.sumOf { it.sizeBytes })}",
                            style = body(12.5f, FontWeight.Normal, c.n600),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { vm.ignoreFolder(path) },
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.EyeOff, 17.dp, c.n500) }
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { vm.requestTrash(ts.map { it.id }) },
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.Trash, 17.dp, c.n500) }
                }
            }
        }

        // ── 段2: 已忽略的文件夹 ──
        SectionLabel("已忽略 · ${vm.ignored.size}")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vm.ignored.isEmpty()) {
                // 空态用全 app 统一模式(圆+图标+引导),合并页时降级成的单行文字
                // 在设计打磨轮恢复。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(c.n200),
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.EyeOff, 26.dp, c.n500) }
                    Text(
                        "暂无忽略的文件夹\n在上方列表点眼睛图标可忽略它",
                        style = body(13f, FontWeight.Normal, c.n600).copy(lineHeight = 20.sp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            for (path in vm.ignored) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .shadowSm(RoundedCornerShape(28.dp))
                        .clip(RoundedCornerShape(28.dp))
                        .background(c.surface)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.n200),
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.EyeOff, 19.dp, c.n600) }
                    Column(Modifier.weight(1f)) {
                        Text(path, style = body(15f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "已忽略",
                            style = body(12.5f, FontWeight.Normal, c.n500),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    PillButton(
                        "恢复扫描",
                        onClick = { vm.restoreFolder(path) },
                        bg = null, textColor = c.text, borderColor = c.divider,
                        fontSize = 12.5f, padH = 14.dp, padV = 7.dp,
                    )
                }
            }
        }
        Box(Modifier.height(114.dp))
    }
}

// ── v2.0: folder content browsing (songs inside a folder) ──────────────
@Composable
private fun FolderContentScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    val folder = vm.folderKey
    if (folder == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.navFilesBack() }
        return
    }
    val tracks = vm.foldersMap()[folder].orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SubPageHeader(folder) { vm.folderKey = null; vm.navFilesBack() }
        }
        item {
            Text(
                "${tracks.size} 个文件 · ${formatSizeMb(tracks.sumOf { it.sizeBytes })}",
                style = body(13f, FontWeight.Normal, c.n600),
            )
        }
        if (tracks.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(c.n200), contentAlignment = Alignment.Center) {
                        OIcon(Lucide.Folder, 30.dp, c.n500)
                    }
                    Text("文件夹为空", style = body(14f, FontWeight.Normal, c.n600))
                }
            }
        } else {
            items(tracks, key = { it.id }) { t ->
                com.shiyin.music.ui.components.TrackRow(
                    track = t,
                    isCurrent = t.id == vm.player.currentId,
                    isPlaying = vm.player.isPlaying,
                    subtitle = com.shiyin.music.ui.components.trackSubtitle(t),
                    onClick = { vm.play(t.id) },
                    onLongClick = { vm.trackMenuFor = t.id },
                    coverSize = 42.dp,
                    coverRadius = 13.dp,
                    trailing = { com.shiyin.music.ui.screens.TrackMenuButton(vm, t) },
                    isHiddenTrack = vm.isHidden(t.id),
                )
            }
        }
    }
}

// ── v2.0 playback devices subpage (real routing via DeviceRouter) ──────────
@Composable
private fun DevicesScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    val devices = vm.deviceRouter.availableDevices
    val activeId = vm.deviceRouter.activeDeviceId
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader("播放设备") { vm.navFilesBack() }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (d in devices) {
                val active = d.id == activeId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .shadowSm(RoundedCornerShape(28.dp))
                        .clip(RoundedCornerShape(28.dp))
                        .background(c.surface)
                        .border(2.dp, if (active) c.accent else Color.Transparent, RoundedCornerShape(28.dp))
                        .clickable {
                            // v5.2 Bug1: real in-app routing via the custom
                            // SessionCommand. Pass null address for non-BT sinks
                            // so the service clears the preferred device.
                            val addr = d.address.takeIf { it.isNotBlank() }
                            vm.selectDevice(addr)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.n200),
                        contentAlignment = Alignment.Center,
                    ) {
                        val icon = when (d.kind) {
                            "headphone" -> Lucide.Headphones
                            "bluetooth" -> Lucide.Bluetooth
                            "tv" -> Lucide.Tv
                            else -> Lucide.Smartphone
                        }
                        OIcon(icon, 20.dp, if (active) c.a700 else c.n700)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = body(15f, FontWeight.SemiBold, if (active) c.a700 else c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(d.sub, style = body(12.5f, FontWeight.Normal, c.n600), modifier = Modifier.padding(top = 2.dp))
                    }
                    if (active) EqBars(vm.player.isPlaying)
                }
            }
        }
        Box(Modifier.height(114.dp))
    }
}

// ── v1.8 merges subpage ────────────────────────────────────────────────────
@Composable
private fun MergesScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader(
            "歌手合并",
            help = "合并影响什么" to "合并只影响显示归属，不改动文件；撤销后立即还原。",
        ) { vm.navFilesBack() }
        if (vm.aliases.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(c.n200),
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.Users, 30.dp, c.n500) }
                Text(
                    "暂无合并记录。\n在歌手页点「管理归属」，可把被识别成多个名字的同一歌手合并。",
                    style = body(14f, FontWeight.Normal, c.n600).copy(lineHeight = 22.sp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((from, to) in vm.aliases) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.surface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$from → $to",
                            style = body(14f, FontWeight.SemiBold, c.text),
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        PillButton(
                            "撤销",
                            onClick = { vm.unmergeArtist(from) },
                            bg = null, textColor = c.text, borderColor = c.divider,
                            fontSize = 12.5f, padH = 14.dp, padV = 7.dp,
                        )
                    }
                }
            }
        }
        Box(Modifier.height(114.dp))
    }
}

// ── v1.5/v1.8 about subpage ────────────────────────────────────────────────
@Composable
private fun AboutScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    var supportSheetOpen by remember { mutableStateOf(false) }
    var updateDialogOpen by remember { mutableStateOf(false) }
    // v22: 进页检查一次更新（6 小时内查过则直接用缓存，不打网络）
    LaunchedEffect(Unit) { vm.checkForUpdate() }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        SubPageHeader("关于我们") { vm.navFilesBack() }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .shadowMd(CircleShape)
                    .clip(CircleShape)
                    .background(c.a300),
                contentAlignment = Alignment.Center,
            ) {
                // v4.3: Logo 放大到与外层 96dp 圆形容器同尺寸并裁切为圆，
                // 填满原区域，避免边缘露出主题色背景空白。
                Image(
                    painter = painterResource(com.shiyin.music.R.drawable.pickupmusic),
                    contentDescription = "拾音",
                    modifier = Modifier.size(96.dp).clip(CircleShape),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("拾音", style = heading(30))
                Text("PickUpMusic", style = body(13f, FontWeight.Bold, c.n600).copy(letterSpacing = 1.5.sp))
            }
            // v22: 版本号改成可点的「检查更新」入口。有更新时整块变强调色 + 「发现更新」，
            // 点开弹层看更新内容并下载；无更新时点一下手动强制检查。
            val hasUpdate = vm.updateAvailable()
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (hasUpdate) c.accent.copy(alpha = 0.12f) else c.surface)
                    .clickable {
                        if (hasUpdate) updateDialogOpen = true else vm.checkForUpdate(force = true)
                    }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "v${vm.currentVersion}",
                    style = body(12f, if (hasUpdate) FontWeight.Bold else FontWeight.Normal, if (hasUpdate) c.a700 else c.n800),
                )
                Text(
                    when {
                        vm.updateChecking -> "检查中…"
                        hasUpdate -> "发现更新"
                        else -> "检查更新"
                    },
                    style = body(11.5f, if (hasUpdate) FontWeight.Bold else FontWeight.Normal, if (hasUpdate) c.a700 else c.n600),
                )
                if (hasUpdate) OIcon(Lucide.ChevronRight, 13.dp, c.a700)
            }
            // v22 删减:去掉「完全本地运行 · 不上传任何数据」整行(用户定调 —— 这句话本身
            // 不准确，且不想在关于页放这类宣称)，以及振假名的功能描述行。
            // 仅保留 JMdict 的**最小归属声明**并移到页面最底部：
            // 振假名词表是从 JMdict 派生的 CC BY-SA 4.0 数据，
            // tools/jmdict-build/LICENSE-derivative.md 明确写了"应用内「关于」页亦须显示
            // JMdict/EDRDG 归属与许可链接"——删掉即许可违约，故只降级不删除。
            // v3.3: 每次更新附上更新内容清单，方便用户对照功能变化
            // v3.3: 每次更新附上更新内容清单，方便用户对照功能变化
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.surface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("更新内容", style = body(13f, FontWeight.ExtraBold, c.text).copy(letterSpacing = 1.sp))
                // 规则:同大版本(v1.2.x)各修订版日志都保留;进入下一大版本(v1.3.x)时移除前一版本日志。
                // 编写规范:用户视角,不写内部实现/类名/字段;分类 ✨新功能/🚀优化体验/🐛问题修复;
                // 某类无内容不强行加;版本号不带日期。
                data class VerChange(
                    val version: String,
                    val newFeatures: List<String> = emptyList(),
                    val improvements: List<String> = emptyList(),
                    val fixes: List<String> = emptyList(),
                )
                val versions = listOf(
                    VerChange(
                        version = "v1.3.0",
                        newFeatures = listOf(
                            "新增 Agent 对话页：用自然语言让 AI 帮你整理音乐库。支持联网搜证后自动排序专辑曲目、修正识别错的歌手名、为当前歌曲找歌词，每步执行进度可见，写回前需你确认。",
                            "Agent 支持批量改歌名：说「把专辑里每首歌的XX后缀去掉」「繁体转简体」「片假名换成汉字官方名」，AI 逐首分析列出改动清单后写回；也可以直接点名「把 A 改成 B」，改完逐行对照验收。",
                            "Agent 会主动检索本地音乐库：问「库里有没有这首歌」「我常听哪些」不再靠 AI 瞎编，答案基于你的真实曲库和播放次数。",
                            "Agent 支持多家大模型：DeepSeek、OpenRouter、智谱、通义预设一键切换，也可自定义任意 OpenAI 兼容服务商；设置页可查看 Token 用量趋势（输入 / 输出 / 缓存命中 / 成本曲线）。",
                            "歌手写真可在设置里手动配置来源：填入 Fanart / Last.fm 的 API Key 可获得更多写真，也可单独开关某个来源。",
                        ),
                        improvements = listOf(
                            "选择歌手写真时，候选图边找边显示，不再空等全部来源；搜索进度可见，不会再像卡住。",
                            "专辑封面搜索支持多地区：日语、中文歌曲用原名即可匹配到对应地区商店的封面，不再需要英文名。",
                            "已获取的专辑封面本地持久化保存：断网也能立即显示，不再每次冷启动都重新解码或下载。",
                            "歌手页打开速度提升，封面加载更稳，减少了重复请求导致的卡顿。",
                            "Agent 回复像逐字打字一样流式呈现；AI 思考过程收进可展开的思考面板，思考中亮橙色呼吸灯，完成后自动折叠。",
                            "歌词本切到下一首时立即从顶部开始跟随，不再停留上一首的最后位置。",
                            "专辑页日期前缀按真实类型显示（专辑 / EP / 单曲 / 合集 · 日期），不再一律写「专辑」。",
                        ),
                        fixes = listOf(
                            "修复 Agent 修正歌手名时，因未把歌曲编号交给 AI 导致永远修正不成功的问题。",
                            "修复 Agent 改歌名「提示成功但实际没改」的问题（改名的目标专辑与新旧歌名现在如实交给 AI）。",
                            "修复部分 AI 模型把答案写在思考里导致改名无结果、却显示完成的问题；拿不准的歌名保持原样不动，不再瞎改。",
                            "修复 Agent 设置页打开闪退、提示「未配置大模型」但配置其实存在的问题。",
                            "修复更换封面来源或选择自定义封面后，旧封面仍缓存不清、不刷新的问题。",
                            "修复部分情况下播放次数统计不准确的问题：误触、快速跳过不再被计入播放次数和热度排序（累计真实播放满 30 秒才算一次有效播放）。",
                            "修复退出应用或切到后台后，正在播放的歌曲时长未被正确记录的问题。",
                            "修复单曲循环时播放计数可能错乱、同一首歌重复计数异常的问题。",
                            "修复歌手主页名字在内容较少的歌手上会突然瞬移到顶栏的问题，现在滚动到顶平滑到位。",
                            "修复部分歌手名（含全角逗号等标点）无法正确识别为多位歌手的问题。",
                            "修复音乐库「专辑」筛选仍混入单曲的问题。",
                            "修复从专辑封面生成歌手写真时偶尔提示「无可用封面」的问题。",
                            "修复专辑信息编辑里发布时间的年份列表固定到 2030 年的问题，现在跟随当前年份。",
                        ),
                    ),
                )
                for (ver in versions) {
                    Text(
                        ver.version,
                        style = body(12.5f, FontWeight.Bold, c.text).copy(letterSpacing = 0.5.sp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    val sections = listOf(
                        "✨ 新功能" to ver.newFeatures,
                        "🚀 优化体验" to ver.improvements,
                        "🐛 问题修复" to ver.fixes,
                    )
                    for ((label, items) in sections) {
                        if (items.isEmpty()) continue
                        Text(label, style = body(11.5f, FontWeight.Bold, c.n600).copy(letterSpacing = 0.5.sp), modifier = Modifier.padding(top = 4.dp))
                        for (ch in items) {
                            // 悬挂缩进:圆点单独一列,正文换行后与首字对齐
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("·", style = body(12f, FontWeight.Bold, c.n700))
                                Text(ch, style = body(12f, FontWeight.Normal, c.n700).copy(lineHeight = 17.sp), modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        // v1.2.0: 开发者小栏——头像+名字+「请开发者喝杯咖啡吧」,点开赞赏页
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface)
                .clickable { supportSheetOpen = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painter = painterResource(com.shiyin.music.R.drawable.dev_avatar),
                    contentDescription = "开发者头像",
                    modifier = Modifier.size(52.dp).clip(CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text("御晨晓", style = body(15f, FontWeight.ExtraBold, c.text))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OIcon(Lucide.Heart, 14.dp, c.a700)
                        Text("请开发者喝杯咖啡吧", style = body(12.5f, FontWeight.Bold, c.a700))
                    }
                }
            }
        }
        // v22: JMdict 归属声明（最小化、置于页尾）。振假名词表派生自 JMdict（CC BY-SA 4.0），
        // 许可要求随包分发时保留归属；点击可打开许可页查看完整条款。
        Text(
            "振假名词表数据来源：JMdict（© EDRDG / Jim Breen），CC BY-SA 4.0",
            style = body(10.5f, FontWeight.Normal, c.n500),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 24.dp, end = 24.dp)
                .clickable { vm.openExternalUrl("https://www.edrdg.org/edrdg/licence.html") },
        )
        Box(Modifier.height(114.dp))
        }
        // 赞赏页(整页):提示语 + 微信/支付宝赞赏码(各占整行,放大;点击保存到相册)
        if (supportSheetOpen) {
            Column(
                Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 22.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SubPageHeader("赞赏开发者") { supportSheetOpen = false }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(
                        painter = painterResource(com.shiyin.music.R.drawable.support_wechat),
                        contentDescription = "微信赞赏码（点击保存到相册）",
                        modifier = Modifier.fillMaxWidth(0.8f).clip(RoundedCornerShape(16.dp)).background(c.n100).clickable { vm.saveSupportImage(com.shiyin.music.R.drawable.support_wechat, "拾音_微信赞赏码") },
                    )
                    Text("微信赞赏", style = body(14f, FontWeight.Bold, c.n700))
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(
                        painter = painterResource(com.shiyin.music.R.drawable.support_alipay),
                        contentDescription = "支付宝赞赏码（点击保存到相册）",
                        modifier = Modifier.fillMaxWidth(0.8f).clip(RoundedCornerShape(16.dp)).background(c.n100).clickable { vm.saveSupportImage(com.shiyin.music.R.drawable.support_alipay, "拾音_支付宝赞赏码") },
                    )
                    Text("支付宝赞赏", style = body(14f, FontWeight.Bold, c.n700))
                }
                Box(Modifier.height(40.dp))
            }
        }
        // v22: 发现更新的弹层 —— 版本号 + 更新内容 + 下载 / 忽略 / 稍后。
        // 「下载新版本」走系统浏览器/下载器（ACTION_VIEW）：不需要任何新权限，
        // 也不会出现"应用内下载后静默安装"那种需要 REQUEST_INSTALL_PACKAGES 的重活。
        if (updateDialogOpen) {
            val notes = vm.updateState.notes.trim()
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { updateDialogOpen = false },
                containerColor = c.surface,
                title = {
                    Text("发现新版本 v${vm.updateState.latestVersion}", style = body(16f, FontWeight.ExtraBold, c.text))
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("当前版本 v${vm.currentVersion}", style = body(12f, FontWeight.Normal, c.n600))
                        if (notes.isNotBlank()) {
                            Text("更新内容", style = body(12.5f, FontWeight.ExtraBold, c.text))
                            Text(
                                notes,
                                style = body(12f, FontWeight.Normal, c.n700).copy(lineHeight = 18.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        Text(
                            if (vm.updateState.apkUrl.isBlank())
                                "将打开 Release 页面，下载最新 APK 后按系统提示安装"
                            else "将下载最新 APK，完成后按系统提示安装",
                            style = body(11.5f, FontWeight.Normal, c.n600),
                        )
                    }
                },
                confirmButton = {
                    com.shiyin.music.ui.components.DialogActionsRow(
                        listOf(
                            com.shiyin.music.ui.components.DialogAction("稍后") { updateDialogOpen = false },
                            com.shiyin.music.ui.components.DialogAction("忽略此版本") {
                                vm.dismissUpdate(); updateDialogOpen = false
                            },
                            com.shiyin.music.ui.components.DialogAction("下载新版本", emphasized = true) {
                                vm.openUpdateDownload(); updateDialogOpen = false
                            },
                        )
                    )
                },
            )
        }
    }
}

// ── clean suggestions · v1.3.x 回收站并入页顶 ───────────────────────────────
// 页序:回收站(顶) → 重复文件 → 超短音频 → 移入回收站按钮。
@Composable
private fun CleanScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    val dups = vm.dupGroups().flatten()
    val shorts = vm.shortTracks()
    val selIds = vm.sel.filterValues { it }.keys
    val lib = vm.lib().associateBy { it.id }
    val selSize = selIds.sumOf { lib[it]?.sizeBytes ?: 0 }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            // 清理建议从设置「资源管理」进来时是正常下钻(返回走栈);从首页清理卡
            // 直达时是 cleanEntry(栈空)——按钮返回与系统返回一致:关设置回来源 tab。
            SubPageHeader("清理建议") {
                vm.sel = emptyMap()
                if (vm.cleanEntry) {
                    vm.cleanEntry = false
                    vm.settingsOpen = false
                    vm.restoreTabIfDrillFullyClosed()
                } else vm.navFilesBack()
            }
        }
        // ── 回收站段(并入;原独立 TrashScreen)──
        item { SectionLabel("回收站 · ${vm.trashMirror.size} 个文件") }
        if (vm.trashMirror.isEmpty()) {
            item {
                Text(
                    "回收站是空的。删除的文件会先进入回收站，可随时恢复或彻底清空。",
                    style = body(12.5f, FontWeight.Normal, c.n500).copy(lineHeight = 18.sp),
                )
            }
        } else {
            items(vm.trashMirror, key = { "t${it.mediaId}" }) { e ->
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(c.n200),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                e.title.firstOrNull()?.uppercase() ?: "♪",
                                fontFamily = Caprasimo,
                                style = body(17f, FontWeight.Normal, c.n600).copy(fontFamily = Caprasimo),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.title,
                                style = body(14.5f, FontWeight.SemiBold, c.n600).copy(textDecoration = TextDecoration.LineThrough),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${e.folder} · ${formatSizeMb(e.sizeBytes)}",
                                style = body(12.5f, FontWeight.Normal, c.n500),
                                modifier = Modifier.padding(top = 2.dp),
                                maxLines = 1,
                            )
                        }
                        PillButton(
                            "恢复",
                            onClick = { vm.requestRestore(listOf(e.mediaId)) },
                            bg = null, textColor = c.text, borderColor = c.divider,
                            fontSize = 12.5f, padH = 14.dp, padV = 7.dp,
                            icon = Lucide.RotateCcw, iconSize = 13.dp,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                }
            }
            item {
                PillButton(
                    "彻底清空回收站（释放 ${formatSizeMb(vm.trashMirror.sumOf { it.sizeBytes })}）",
                    onClick = { vm.requestEmptyTrash() },
                    modifier = Modifier.fillMaxWidth(),
                    bg = null, textColor = c.a700, fontSize = 14f, padV = 13.dp,
                )
            }
        }
        // ── 重复文件段 ──
        item { SectionLabel("重复文件") }
        items(dups, key = { "d${it.id}" }) { t -> CheckRow(vm, t, "${t.artist} · ${t.folder}") }
        item { SectionLabel("超短音频（30 秒以内）") }
        items(shorts, key = { "s${it.id}" }) { t -> CheckRow(vm, t, "${formatDuration(t.durationSec)} · ${t.folder}") }
        item {
            PillButton(
                "移入回收站（${selIds.size} 项 · ${formatSizeMb(selSize)}）",
                onClick = { if (selIds.isNotEmpty()) vm.requestTrash(selIds.toList()) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                fontSize = 15f, padV = 14.dp,
            )
        }
    }
}

@Composable
private fun CheckRow(vm: MainViewModel, t: Track, sub: String) {
    val c = LocalOrganic.current
    val checked = vm.sel[t.id] == true
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { vm.toggleSel(t.id) }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (checked) c.accent else Color.Transparent)
                    .border(2.dp, if (checked) c.accent else c.n400, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) OIcon(Lucide.CheckBold, 12.dp, Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(t.title, style = body(14.5f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = body(12.5f, FontWeight.Normal, c.n600), modifier = Modifier.padding(top = 2.dp), maxLines = 1)
            }
            Text(formatSizeMb(t.sizeBytes), style = body(12f, FontWeight.Normal, c.n500))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

// ── trash ──────────────────────────────────────────────────────────────────
// v1.3.x: 独立回收站子页删除——回收站段并入 CleanScreen 页顶(用户拍板合并)。

// ── v1.2.1 写真源配置子页 · v1.3.2 重排:两段式(内置源 / 自定义源)。
//      需 Key 的源(Fanart/Last.fm)与用户自建 URL 源统一归入「自定义源」(置底),
//      Key 密文填写,保存按钮只在有未保存修改时出现。 ──
@Composable
private fun ImageSourcesScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    var keyDialogFor by remember { mutableStateOf<String?>(null) }  // "fanart" / "lastfm"
    var addDialogOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader("写真源") { vm.navFilesBack() }

        // ── 内置源(无需 Key 的;Fanart/Last.fm 移入下方「自定义源」)──
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("内置源", style = body(13f, FontWeight.ExtraBold, c.text).copy(letterSpacing = 0.5.sp))
            val labels = com.shiyin.music.data.image.ArtistImageSources.sourceLabels
            val order = com.shiyin.music.data.image.ArtistImageSources.sources.map { it.key }
                .filter { it != "fanart" && it != "lastfm" }
            for (key in order) {
                val enabled = key !in vm.disabledImageSources
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(labels[key] ?: key, style = body(14f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
                    OrganicSwitch(enabled) { vm.setImageSourceEnabled(key, !enabled) }
                }
            }
        }

        // ── 自定义源:需 Key 的内置源 + 用户自建 URL 源,配置好即同内置源一样开关 ──
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("自定义源", style = body(13f, FontWeight.ExtraBold, c.text).copy(letterSpacing = 0.5.sp), modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(c.accent).clickable { addDialogOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    OIcon(Lucide.Plus, 15.dp, Color.White)
                }
            }
            // ① 需 Key 的内置源(Fanart / Last.fm):填好 Key 即生效,可开关
            val keyedSources = listOf(
                Triple("fanart", com.shiyin.music.data.image.ArtistImageSources.sourceLabels["fanart"] ?: "Fanart.tv", vm.fanartApiKey),
                Triple("lastfm", com.shiyin.music.data.image.ArtistImageSources.sourceLabels["lastfm"] ?: "Last.fm", vm.lastfmApiKey),
            )
            for ((key, label, savedKey) in keyedSources) {
                val enabled = key !in vm.disabledImageSources
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, style = body(14f, FontWeight.SemiBold, c.text))
                        if (savedKey.isNotBlank()) {
                            Text("已配置", style = body(10f, FontWeight.Bold, c.s700))
                        }
                    }
                    // v1.3.x: 纯文字"修改键"按钮换成笔图标(与自定义源的删除图标同一
                    // 设计语言:30dp 圆形触区+描边图标);点击弹原来的 Key 填写窗。
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).clickable { keyDialogFor = key },
                        contentAlignment = Alignment.Center,
                    ) {
                        OIcon(Lucide.Pencil, 15.dp, if (savedKey.isBlank()) c.n500 else c.accent)
                    }
                    OrganicSwitch(enabled) { vm.setImageSourceEnabled(key, !enabled) }
                }
            }

            // ② 用户自建的 URL 源
            for (def in vm.customImageSources) {
                val key = "custom-${def.id}"
                val enabled = key !in vm.disabledImageSources
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(def.name, style = body(14f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
                    Box(Modifier.size(30.dp).clip(CircleShape).clickable { vm.removeCustomImageSource(def.id) }, contentAlignment = Alignment.Center) {
                        OIcon(Lucide.Trash, 15.dp, c.n500)
                    }
                    OrganicSwitch(enabled) { vm.setImageSourceEnabled(key, !enabled) }
                }
            }
        }
    }
    when (keyDialogFor) {
        "fanart" -> SourceKeyDialog("Fanart.tv API Key", vm.fanartApiKey, onDismiss = { keyDialogFor = null }) {
            vm.setFanartApiKey(it); keyDialogFor = null
        }
        "lastfm" -> SourceKeyDialog("Last.fm API Key", vm.lastfmApiKey, onDismiss = { keyDialogFor = null }) {
            vm.setLastfmApiKey(it); keyDialogFor = null
        }
    }
    if (addDialogOpen) AddCustomSourceDialog(vm) { addDialogOpen = false }
}

/**
 * v1.3.2: 填 API Key 的弹窗(密文 + 眼睛切换)。保存按钮只在有未保存修改时出现——
 * 已保存/未改动时不显示,不常态占据界面。
 */
@Composable
private fun SourceKeyDialog(title: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = LocalOrganic.current
    var input by remember(current) { mutableStateOf(current) }
    val dirty = input.trim() != current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, style = heading(16)) },
        text = {
            PasswordField(
                value = input, onValueChange = { input = it },
                label = if (current.isBlank()) "API Key" else "已配置 · 输入新值覆盖,留空保存则清除",
                placeholder = "API Key",
            )
        },
        confirmButton = {
            val acts = mutableListOf(DialogAction(if (dirty) "取消" else "关闭") { onDismiss() })
            if (dirty) acts.add(DialogAction("保存", emphasized = true) { onSave(input.trim()) })
            DialogActionsRow(acts)
        },
    )
}

/** v1.3.2: 添加自定义写真源的弹窗(名称 + URL + 可选 Key)。URL 里 {name} 为歌手名占位。 */
@Composable
private fun AddCustomSourceDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = LocalOrganic.current
    var name by remember { mutableStateOf("") }
    var tpl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    val valid = tpl.trim().startsWith("http") && tpl.contains("{name}")
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("添加自定义源", style = heading(18)) },
        text = {
            // v1.3.x: 描述+三输入框较高,加滚动上限防按钮被挤出可视区。
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "URL 里用 {name} 表示歌手名(自动 URL 编码)。响应为 JSON 时自动提取其中的图片链接;URL 以 .jpg/.png 等图片扩展名结尾则视为直连图片。需要鉴权的 API 可填 Key:URL 里写 {key} 表示 Key 位置,不写则自动以 Bearer 头附带。",
                    style = body(11.5f, FontWeight.Normal, c.n600).copy(lineHeight = 16.sp),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称", style = body(12f, FontWeight.Normal, c.n600)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
                        focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
                    ),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = tpl, onValueChange = { tpl = it },
                    label = { Text("URL", style = body(12f, FontWeight.Normal, c.n600)) },
                    placeholder = { Text("https://api.example.com/img?artist={name}", style = body(12f, FontWeight.Normal, c.n500)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
                        focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
                    ),
                )
                PasswordField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = "API Key(可选)", placeholder = "该 API 需要鉴权时再填",
                )
            }
        },
        confirmButton = {
            DialogActionsRow(
                listOf(
                    DialogAction("取消") { onDismiss() },
                    DialogAction("添加", emphasized = true, enabled = valid) {
                        vm.addCustomImageSource(name, tpl, apiKey)
                        onDismiss()
                    },
                )
            )
        },
    )
}

// ── v2.0 DeepSeek API key input dialog ─────────────────────────────────
@Composable
private fun DeepSeekKeyDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = LocalOrganic.current
    var input by remember { mutableStateOf(vm.deepseekApiKey) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("DeepSeek API Key", style = com.shiyin.music.ui.components.heading(20)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "输入你的 DeepSeek API Key 以启用 AI 歌词搜索和封面优化。\n可在 deepseek.com 获取。",
                    style = body(13f, FontWeight.Normal, c.n600).copy(lineHeight = 20.sp),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("sk-...", style = body(14f, FontWeight.Normal, c.n500)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text,
                        unfocusedTextColor = c.text,
                        cursorColor = c.accent,
                        focusedBorderColor = c.accent,
                        unfocusedBorderColor = c.divider,
                    ),
                )
            }
        },
        confirmButton = {
            DialogActionsRow(
                listOf(
                    DialogAction("取消") { onDismiss() },
                    DialogAction("保存", emphasized = true) {
                        vm.setDeepSeekKey(input.trim())
                        onDismiss()
                    },
                )
            )
        },
    )
}

/**
 * v1.3.10:「翻译按钮显示」三档行——标题 + 副标题 + 下方**三段式离散**滑动控件。
 * 按用户定稿:不做三个独立按钮堆叠,也不用可停在任意位置的连续 Slider。
 */
@Composable
private fun TranslateButtonModeRow(vm: MainViewModel) {
    val c = LocalOrganic.current
    val modes = listOf(
        "never" to "不显示",
        "majority" to "外文较多时显示",
        "any" to "检测到外文就显示",
    )
    val idx = modes.indexOfFirst { it.first == vm.translateButtonMode }.coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            // 与「外语歌自动翻译」的 Languages 图标区分:这一条管的是"按钮显不显示"→眼睛。
            OIcon(Lucide.Eye, 19.dp, c.n700)
            Text("翻译按钮显示", style = body(15f, FontWeight.SemiBold, c.text))
        }
        Spacer(Modifier.height(10.dp))
        ThreeStopSegmentedControl(
            labels = modes.map { it.second },
            selectedIndex = idx,
            onSelect = { vm.setTranslateButtonMode(modes[it].first) },
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}

/**
 * v1.3.10: 三段式**离散**选择控件——只能停在三档,不存在"停在两档之间"的中间态。
 *
 * 交互:点击某段 → 选中该档;横向拖动 → 指示器跟手,松手吸附到最近档位。
 * 指示器(thumb)在档位间用动画平滑过渡(视觉像滑块在滑动),语义上是三选一。
 *
 * 为什么不用 Material3 Slider:它的轨道/刻度是连续数值模型,档位标签还得另画一层;
 * 本控件直接以"段"为单位,和其它设置行里的自绘控件同一套语言。
 */
@Composable
private fun ThreeStopSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val c = LocalOrganic.current
    val n = labels.size
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.n200),
    ) {
        val segWDp = maxWidth / n
        val segPx = constraints.maxWidth.toFloat() / n
        // 拖动中的指示器左缘(null = 未拖动,指示器由动画控制)。
        var dragX by remember { mutableStateOf<Float?>(null) }
        val thumb = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        // 非拖动时:平滑滑到选中档——"看起来像滑块在滑动"就是这一段。
        LaunchedEffect(selectedIndex, segPx) {
            if (dragX == null) thumb.animateTo(selectedIndex * segPx, tween(190))
        }
        // 指示器:写在 graphicsLayer 里延迟读值,拖动逐帧只重画这一层、零重组。
        Box(
            Modifier
                .graphicsLayer { translationX = dragX ?: thumb.value }
                .width(segWDp)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(9.dp))
                // v1.3.10 需求 A18:选中指示器用**实心 accent**——与各"开"状态控件
                // (OrganicSwitch 开态、播放主键)同一套语言。旧版是 accent 0.20 淡橙,
                // 用户反馈"和其他打开状态的按钮颜色不一致"。
                .background(c.accent),
        )
        Row(
            Modifier
                .fillMaxSize()
                .pointerInput(n) {
                    val seg = size.width.toFloat() / n
                    detectHorizontalDragGestures(
                        onDragStart = { off ->
                            dragX = (off.x - seg / 2f).coerceIn(0f, size.width - seg)
                        },
                        onDragEnd = {
                            val i = (((dragX ?: 0f) + seg / 2f) / seg).roundToInt().coerceIn(0, n - 1)
                            // 先把指示器钉在手指位置再交回动画,否则松手瞬间会先弹回旧档再滑。
                            scope.launch {
                                thumb.snapTo(dragX ?: thumb.value)
                                dragX = null
                                onSelect(i)
                            }
                        },
                        onDragCancel = { dragX = null },
                    ) { change, delta ->
                        if (change.positionChanged()) {
                            dragX = ((dragX ?: 0f) + delta).coerceIn(0f, size.width.toFloat() - seg)
                            change.consume()
                        }
                    }
                },
        ) {
            labels.forEachIndexed { i, label ->
                val active = i == selectedIndex
                Box(
                    // v1.3.10 需求 A20:分段本身去涟漪——按下态由指示器/描边承担,不要灰块
                    Modifier.weight(1f).fillMaxHeight().clickableNoRipple { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = body(
                            10.5f,
                            if (active) FontWeight.Bold else FontWeight.Normal,
                            // 选中项文字用 c.bg(与"实心 accent 底上的文字"同惯例,见播放主键),
                            // 保证在实心橙底上可读;未选中仍用次级灰。
                            if (active) c.bg else c.n600,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// ── v1.3.10 需求 P3:「拾光记忆」管理 ────────────────────────────────────────

/** 记忆类型 → 中文标签(分组筛选用)。与 data 层的 MemoryType 白名单一一对应。 */
/**
 * v1.3.12:「拾光记忆」—— **列表页 ↔ 详情页** 两级(参考 Claude memories 的**信息架构**,
 * 落成 Android 原生页面:桌面 Hover → 常驻图标;桌面 Modal → 独立详情页)。
 *
 * 列表结构:
 * ```
 * ←  拾光记忆
 *     拾光会记住一些关于你的信息,让之后的对话更自然。
 *     你
 *       个人信息  用户叫御晨晓
 *                 你告诉我的 · 2026/09/29          ✎  🗑
 *     ──────────────────────────────────────────────
 *     主题
 *       音乐      用户喜欢《Pretender》 …
 * ```
 *
 * 刻意不做(用户定调):不做卡片 / 彩色分类块、不加搜索·筛选·排序、不新增分类;
 * **空分组直接不显示**(没有"关系"就不出现"关系·暂无");类型与来源全部翻成中文,
 * 页面上不出现 `personal` / `explicit` 这类库内取值。
 */
@Composable
internal fun MemoryScreen(vm: MainViewModel, onBack: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.ensureMemoriesLoaded() }
    // 详情页用 **id** 定位而不是持有快照:保存/删除后列表刷新,详情读到的仍是最新状态。
    var detailId by remember { mutableStateOf<String?>(null) }
    var detailEditing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<com.shiyin.music.data.memory.Memory?>(null) }
    var adding by remember { mutableStateOf(false) }

    val detail = detailId?.let { id -> vm.memories.firstOrNull { it.id == id } }
    if (detail != null) {
        MemoryDetailScreen(
            memory = detail,
            startEditing = detailEditing,
            onBack = { detailId = null; detailEditing = false },
            onSave = { vm.updateMemory(detail.id, it) },
            onDelete = { pendingDelete = detail },
        )
    } else {
        MemoryListScreen(
            vm = vm,
            onBack = onBack,
            onOpen = { m -> detailId = m.id; detailEditing = false },
            onEdit = { m -> detailId = m.id; detailEditing = true },
            onDelete = { m -> pendingDelete = m },
            onAdd = { adding = true },
        )
    }

    // 删除一律走确认(用户定调:不允许点一下就没了)
    pendingDelete?.let { mem ->
        MemoryDeleteDialog(
            content = mem.content,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                vm.forgetMemory(mem.id)
                if (detailId == mem.id) { detailId = null; detailEditing = false }
                pendingDelete = null
            },
        )
    }
    if (adding) {
        AddMemoryDialog(
            onDismiss = { adding = false },
            onAdd = { content, type -> vm.addMemory(content, type); adding = false },
        )
    }
}

/**
 * 列表页:标题 + 两句说明 + 分组(Section Header / Row / Divider)。
 *
 * 滚动结构:`Column { 固定标题; LazyColumn(weight(1f)) }`——标题钉住、内容自己滚,
 * 不会出现"外层 Column + 内层列表"两层滚动打架。**不硬编码状态栏高度**:
 * 顶部 inset 由承载本页的容器统一处理(见 AgentScreen 里 agentMemoryScreenOpen 的 Dialog)。
 */
@Composable
private fun MemoryListScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpen: (com.shiyin.music.data.memory.Memory) -> Unit,
    onEdit: (com.shiyin.music.data.memory.Memory) -> Unit,
    onDelete: (com.shiyin.music.data.memory.Memory) -> Unit,
    onAdd: () -> Unit,
) {
    val c = LocalOrganic.current
    val memories = vm.memories
    val groups = com.shiyin.music.data.memory.MemoryPresentation.group(memories)
    Column(Modifier.fillMaxSize()) {
        // v1.3.13(用户反馈"点进去感觉内容被抬上去了"):全站设置子页的页头顶距是 22dp
        // (见 SettingsPages 的 screenPadding),这里原来只有左右、**没有上边距** —— 返回键
        // 紧贴状态栏,整页比别的子页高一截。补上同值,页头与内容共用 20dp 左基线。
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
            SubPageHeader("拾光记忆") { onBack() }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
        ) {
            // v1.3.13:两个开关(参考成熟产品的记忆设置:上面是"要不要记"的策略,下面是记了什么)。
            // 名字已自证,不再各挂一行小字说明(用户:"功能名就能表达完的内容偏偏还要加小字")。
            item(key = "switches") {
                Column(
                    Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp)).background(c.surface)
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                ) {
                    MemoryToggleRow(
                        title = "从对话中生成记忆",
                        checked = vm.memoryAutoExtract,
                        onToggle = { vm.setMemoryAutoExtract(!vm.memoryAutoExtract) },
                    )
                    MemoryRowDivider()
                    MemoryToggleRow(
                        title = "允许生成敏感性的记忆",
                        checked = vm.memoryIncludeTransient,
                        onToggle = { vm.setMemoryIncludeTransient(!vm.memoryIncludeTransient) },
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text("还没有拾光记忆", style = body(15f, FontWeight.SemiBold, c.text))
                    Text(
                        "在聊天里直接告诉拾光就行(例如「记住我喜欢 aiko」),也可以用下面的按钮自己加一条。",
                        style = body(13f, FontWeight.Normal, c.n600).copy(lineHeight = 19.sp),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else {
                for ((section, rows) in groups) {
                    item(key = "sec-${section.title}") {
                        // v1.3.13(用户反馈"你 和下面的 TAG 都是橙色时,看不清重点"):
                        // 分组标题 = **亮色结构标**(主文字色 + 更重字重 + 字距),行内 TAG = 橙色色片。
                        // 两者一个管结构、一个管分类,颜色与形状都不同,重点一眼可辨。
                        Column(Modifier.padding(top = 16.dp, bottom = 6.dp)) {
                            Text(
                                section.title,
                                style = body(13f, FontWeight.ExtraBold, c.text).copy(letterSpacing = 1.4.sp),
                            )
                        }
                    }
                    items(rows, key = { it.id }) { m ->
                        MemoryRowView(
                            memory = m,
                            onOpen = { onOpen(m) },
                            onEdit = { onEdit(m) },
                            onDelete = { onDelete(m) },
                        )
                        MemoryRowDivider()
                    }
                }
            }
            item(key = "add") {
                Spacer(Modifier.height(16.dp))
                PillButton("添加一条", onClick = onAdd)
            }
        }
    }
}

/** Row 之间的分隔线(§:用 Divider 区分,而不是把每条做成卡片)。 */
@Composable
private fun MemoryRowDivider() {
    val c = LocalOrganic.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}

/** 记忆页的策略开关行(标题 + 右侧开关)。 */
@Composable
private fun MemoryToggleRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    val c = LocalOrganic.current
    Row(
        Modifier.fillMaxWidth().clickableNoRipple(onClick = onToggle).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = body(14f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
        OrganicSwitch(checked, onToggle)
    }
}

/**
 * 一条记忆(Row):
 *
 * ```
 * 个人信息   用户叫御晨晓                        ✎  🗑
 *            你告诉我的 · 2026/09/29
 * ```
 *
 * 信息层级(用户定调):**内容**是最主要的视觉信息,分类次之,来源/时间最弱。
 * 整行可点(进详情)。编辑/删除**常驻**——Android 没有 Hover,不能藏在长按或点击之后。
 */
@Composable
private fun MemoryRowView(
    memory: com.shiyin.music.data.memory.Memory,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalOrganic.current
    val P = com.shiyin.music.data.memory.MemoryPresentation
    Row(
        Modifier.fillMaxWidth().clickableNoRipple(onClick = onOpen).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // v1.3.13:类型标签走**主题强调色的小色片**——整页原本"从标签到分栏全是黑",
                // 没有区分度。色片做分类标识,内容仍是主视觉。
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(c.a200)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(P.typeLabel(memory.type), style = body(11f, FontWeight.Bold, c.a700))
                }
                Text(
                    memory.content,
                    style = body(14f, FontWeight.SemiBold, c.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${P.sourceLabel(memory.source)} · ${P.formatDate(memory.updatedAt)}",
                style = body(11.5f, FontWeight.Normal, c.n500),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        MemoryIconAction(Lucide.Pencil, onEdit)
        MemoryIconAction(Lucide.Trash, onDelete)
    }
}

/** 列表行 / 详情页用的**小图标键**(不用文字按钮、不用大按钮)。 */
@Composable
private fun MemoryIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val c = LocalOrganic.current
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { OIcon(icon, 17.dp, c.n500) }
}

/**
 * 详情页(独立页面,不是弹窗——桌面 Modal → Android 独立 Screen)。
 *
 * ```
 * ←  个人信息                      ✎  🗑
 *    最后更新
 *    2026年9月29日
 *    记忆内容
 *    用户叫御晨晓。
 *    来源
 *    你告诉我的
 * ```
 *
 * 编辑是**同一页面的另一个状态**(点 ✎ 进入 → 右上角变 ✓ 保存),不是突然弹一个大输入框。
 * 不存在 Summary / Details 这类库里没有的字段 —— 详情主体就是记忆内容本身。
 */
@Composable
private fun MemoryDetailScreen(
    memory: com.shiyin.music.data.memory.Memory,
    startEditing: Boolean,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalOrganic.current
    val P = com.shiyin.music.data.memory.MemoryPresentation
    var editing by remember { mutableStateOf(startEditing) }
    var draft by remember { mutableStateOf(memory.content) }

    Column(Modifier.fillMaxSize()) {
        Row(
            // v1.3.13:与正文同一条左基线(20dp);顶距 22dp 与全站子页一致(原来没有上边距)
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 编辑态下返回 = 放弃本次修改(不做"未保存就丢"的额外确认——改动只在草稿里)
            BackButton { if (editing) editing = false else onBack() }
            Text(
                P.typeLabel(memory.type),
                style = heading(22).copy(color = c.a700),
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                MemoryIconAction(Lucide.Check) { onSave(draft); editing = false }
            } else {
                MemoryIconAction(Lucide.Pencil) { draft = memory.content; editing = true }
                MemoryIconAction(Lucide.Trash, onDelete)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 40.dp),
        ) {
            // v1.3.12(用户反馈):**永远显示完整详情**(最后更新 / 记忆内容 / 来源 / 类型)。
            // 之前"编辑态"把元信息整块藏掉 → 从 ✎ 进来只看到一块内容、返回再点才看到完整页面,
            // 分两步走很别扭。现在编辑只是把「记忆内容」那一块**就地**换成输入框,其余照常显示。
            SectionLabel("最后更新", color = c.a700)
            Spacer(Modifier.height(6.dp))
            Text(P.formatDateLong(memory.updatedAt), style = body(14f, FontWeight.Normal, c.text))

            Spacer(Modifier.height(22.dp))
            SectionLabel("记忆内容", color = c.a700)
            Spacer(Modifier.height(6.dp))
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = body(14f, FontWeight.Normal, c.text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
                        focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text("改完点右上角的 ✓ 保存。", style = body(11.5f, FontWeight.Normal, c.n500))
            } else {
                Text(
                    memory.content,
                    style = body(15f, FontWeight.Normal, c.text).copy(lineHeight = 23.sp),
                )
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("来源", color = c.a700)
            Spacer(Modifier.height(6.dp))
            Text(P.sourceLabel(memory.source), style = body(14f, FontWeight.Normal, c.text))

            Spacer(Modifier.height(22.dp))
            SectionLabel("类型", color = c.a700)
            Spacer(Modifier.height(6.dp))
            Text(P.typeLabel(memory.type), style = body(14f, FontWeight.Normal, c.text))
        }
    }
}

/** 删除确认(列表页与详情页共用;`取消` 在左、`删除` 在右)。 */
@Composable
private fun MemoryDeleteDialog(content: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = LocalOrganic.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("删除这条记忆?", style = body(16f, FontWeight.ExtraBold, c.text))
            Text(
                content,
                style = body(13f, FontWeight.Normal, c.n600),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text("删除后拾光就不再记得它,不能撤销。", style = body(12f, FontWeight.Normal, c.n500))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消", style = body(13f, FontWeight.Bold, c.n600)) }
                TextButton(onClick = onConfirm) { Text("删除", style = body(13f, FontWeight.Bold, c.a700)) }
            }
        }
    }
}
/** 记忆分组筛选 chip(选中 = 实心 accent,与全站选中态同语言)。 */
@Composable
private fun MemoryChip(text: String, active: Boolean, onClick: () -> Unit) {
    val c = LocalOrganic.current
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (active) c.accent else c.divider)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = body(12f, if (active) FontWeight.Bold else FontWeight.Normal, if (active) c.bg else c.n700),
        )
    }
}

/** 手加一条记忆:内容 + 类型。空内容不给提交。 */
@Composable
private fun AddMemoryDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    val c = LocalOrganic.current
    var content by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("preference") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("添加一条记忆", style = body(16f, FontWeight.ExtraBold, c.text))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("例如:喜欢在雨天听 City Pop", style = body(12f, FontWeight.Normal, c.n500))
                },
                textStyle = body(13.5f, FontWeight.Normal, c.text),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
                ),
            )
            Text("属于哪类", style = body(12.5f, FontWeight.Bold, c.text))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 类型清单与中文标签来自 MemoryPresentation(单一真源,页面与列表同一套说法)
                com.shiyin.music.data.memory.MemoryPresentation.ALL_TYPES.forEach { t ->
                    MemoryChip(
                        com.shiyin.music.data.memory.MemoryPresentation.typeLabel(t),
                        type == t,
                    ) { type = t }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消", style = body(13f, FontWeight.Bold, c.n600)) }
                TextButton(
                    onClick = { if (content.isNotBlank()) onAdd(content.trim(), type) },
                ) {
                    Text(
                        "添加",
                        style = body(13f, FontWeight.Bold, if (content.isBlank()) c.n500 else c.a700),
                    )
                }
            }
        }
    }
}

// ── v1.3.10 需求 A29:歌词源管理 ─────────────────────────────────────────────

/**
 * 「歌词源管理」——把歌词来源从"写死的几个"变成**用户可管**:
 *  · 内置源(LRCLIB / 网易云音乐 / LrcShare)可单独开关(关掉的不再参与自动匹配与来源菜单);
 *  · 可以添加**自己的歌词 API**(URL 模板 + JSON 路径 + 可选请求头)。
 *
 * 设计要点(用户原话:"不能说用户到时候要填什么参数找不到能填写的地方"):
 * 表单里**每个字段都有中文标签 + 一行说明 + 示例**,照着任何"GET 返回 JSON"的歌词 API
 * 文档都能填完即可用,不需要看代码。
 */
@Composable
private fun LyricsSourcesScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    var editing by remember { mutableStateOf<com.shiyin.music.data.lyrics.CustomLyricsSourceDef?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(scrollStateOf(scrollStates, "lyricssources"))
            .padding(horizontal = 20.dp).padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader(
            "歌词源管理",
            help = "歌词源怎么工作" to
                "自动匹配与「歌词来源」菜单会按列表顺序依次尝试。关掉的源不再参与，也不出现在菜单里。",
        ) { vm.navFilesBack() }
        SectionLabel("内置歌词源")
        Column(
            Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                .background(c.surface).padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            val builtins = com.shiyin.music.data.lyrics.LyricsFetcher.SOURCES
            builtins.forEachIndexed { i, src ->
                SettingRow(Lucide.Music, src, null, divider = i != builtins.lastIndex) {
                    val on = src !in vm.lyricsDisabledSources
                    OrganicSwitch(on) { vm.setLyricsSourceEnabled(src, !on) }
                }
            }
        }
        SectionLabel("自定义歌词源")
        Column(
            Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                .background(c.surface).padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            vm.customLyricsSources.forEachIndexed { i, def ->
                SettingsRowScaffold(
                    title = def.name,
                    sub = def.searchUrl,
                    leading = { OIcon(Lucide.Music, 19.dp, c.n700) },
                    divider = true,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).clickableNoRipple { editing = def },
                                contentAlignment = Alignment.Center,
                            ) { OIcon(Lucide.Pencil, 16.dp, c.n500) }
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).clickableNoRipple { vm.removeCustomLyricsSource(def.id) },
                                contentAlignment = Alignment.Center,
                            ) { OIcon(Lucide.Trash, 16.dp, c.n500) }
                        }
                    },
                )
            }
            if (vm.customLyricsSources.isEmpty()) {
                Text(
                    "还没有自定义歌词源",
                    style = body(12.5f, FontWeight.Normal, c.n600),
                    modifier = Modifier.padding(vertical = 13.dp),
                )
            }
            // 「添加」作为卡片内最后一行(不再页尾悬浮一颗药丸)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickableNoRipple { adding = true }
                    .padding(vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Box(
                    Modifier.size(19.dp),
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.Plus, 17.dp, c.accent) }
                Text("添加自定义歌词源", style = body(15f, FontWeight.SemiBold, c.accent))
            }
        }
        Box(Modifier.height(114.dp))
    }
    if (adding || editing != null) {
        CustomLyricsSourceDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { def -> vm.saveCustomLyricsSource(def); adding = false; editing = null },
        )
    }
}

/** 表单字段:标签 + 说明占位符(用户要求"参数要有地方填")。 */
@Composable
private fun LyricSourceField(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    val c = LocalOrganic.current
    Text(label, style = body(12.5f, FontWeight.Bold, c.text), modifier = Modifier.padding(top = 10.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(hint, style = body(11.5f, FontWeight.Normal, c.n500)) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 13.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
            focusedTextColor = c.text, unfocusedTextColor = c.text,
        ),
    )
}

@Composable
private fun CustomLyricsSourceDialog(
    initial: com.shiyin.music.data.lyrics.CustomLyricsSourceDef?,
    onDismiss: () -> Unit,
    onSave: (com.shiyin.music.data.lyrics.CustomLyricsSourceDef) -> Unit,
) {
    val c = LocalOrganic.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var searchUrl by remember { mutableStateOf(initial?.searchUrl ?: "") }
    var idPath by remember { mutableStateOf(initial?.idJsonPath ?: "data.items[0].id") }
    var lyricUrl by remember { mutableStateOf(initial?.lyricUrl ?: "") }
    var lyricPath by remember { mutableStateOf(initial?.lyricJsonPath ?: "data.lrc") }
    var headers by remember { mutableStateOf(initial?.headers ?: "") }
    // 保存前最小校验:把"填错了自己也不知道"变成明确提示
    val missing = buildList {
        if (name.isBlank()) add("名称")
        if (!searchUrl.contains("{title}")) add("搜索地址(必须含 {title})")
        if (idPath.isBlank()) add("取歌曲 id 的路径")
        if (!lyricUrl.contains("{id}")) add("歌词地址(必须含 {id})")
        if (lyricPath.isBlank()) add("取歌词的路径")
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(c.surface)
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (initial == null) "添加自定义歌词源" else "编辑自定义歌词源",
                    style = heading(18),
                    modifier = Modifier.weight(1f),
                )
                // A37: 六行「填写说明」从页面空白处收进这里——填表时顺手点开看
                HelpInfoButton(
                    "字段怎么填",
                    "名称：显示在「歌词来源」菜单里的名字。\n\n" +
                        "搜索地址：你的接口地址，用 {title} {artist} 作占位符（会自动做 URL 编码）。\n\n" +
                        "取歌曲 id 的路径：从搜索响应里指出歌曲 id 在哪，例如 data.items[0].id。\n\n" +
                        "歌词地址：用 {id} 作占位符，例如 https://api.example.com/lyric/{id}。\n\n" +
                        "取歌词的路径：例如 data.lrc 或 lyric。\n\n" +
                        "请求头：需要鉴权时一行一个（如 Authorization: Bearer xxx）；不需要就留空。",
                )
            }
            LyricSourceField("名称", "显示在来源菜单里的名字", name) { name = it }
            LyricSourceField("搜索地址", "如 https://api.x.com/search?q={title}&a={artist}", searchUrl) { searchUrl = it }
            LyricSourceField("取歌曲 id 的路径", "如 data.items[0].id", idPath) { idPath = it }
            LyricSourceField("歌词地址", "如 https://api.x.com/lyric/{id}", lyricUrl) { lyricUrl = it }
            LyricSourceField("取歌词的路径", "如 data.lrc 或 lyric", lyricPath) { lyricPath = it }
            LyricSourceField("请求头(可选)", "一行一个,如 Authorization: Bearer xxx", headers) { headers = it }
            if (missing.isNotEmpty()) {
                Text(
                    "还缺:" + missing.joinToString("、"),
                    style = body(11.5f, FontWeight.Normal, c.n600),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            DialogActionsRow(
                listOf(
                    DialogAction("取消") { onDismiss() },
                    DialogAction("保存", emphasized = true) {
                        if (missing.isEmpty()) {
                            onSave(
                                com.shiyin.music.data.lyrics.CustomLyricsSourceDef(
                                    id = initial?.id ?: "custom-" + System.currentTimeMillis(),
                                    name = name.trim(),
                                    searchUrl = searchUrl.trim(),
                                    idJsonPath = idPath.trim(),
                                    lyricUrl = lyricUrl.trim(),
                                    lyricJsonPath = lyricPath.trim(),
                                    headers = headers.trim(),
                                ),
                            )
                        }
                    },
                ),
            )
        }
    }
}

// ── v1.3.14:「Agent 诊断样本」页 ────────────────────────────────────────────

/**
 * 「Agent 诊断样本」—— 排查 Agent 时的**唯一可用手段**。
 *
 * 背景(logcat 在本机被系统抑制、UsageLog 只记成败且不记内容):出了"模型没按格式回"
 * 这类问题,没有任何东西可看。这里把"卡在哪一步、命中多少、模型回了什么开头"摆出来,
 * 并提供「复制全部」—— 用户可直接粘贴给开发窗口。
 *
 * 刻意做**最简**:列表 + 复制全部 + 开关 + 一句提示。不做详情页/筛选(用户定调)。
 */
@Composable
internal fun AgentDiagScreen(vm: MainViewModel, onBack: () -> Unit) {
    val c = LocalOrganic.current
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshAgentDiag() }
    val samples = vm.agentDiagSamples
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
            SubPageHeader("Agent 诊断样本") { onBack() }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        ) {
            item(key = "warn") {
                // 隐私提示必须显眼:样本里有模型回复原文(=对话内容)
                Text(
                    "样本含对话内容(模型回复开头),分享给他人前请先检查。",
                    style = body(12.5f, FontWeight.Normal, c.n600).copy(lineHeight = 18.sp),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            item(key = "switch") {
                Column(
                    Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp)).background(c.surface)
                        .padding(horizontal = 16.dp),
                ) {
                    MemoryToggleRow(
                        title = "记录诊断样本",
                        checked = vm.agentDiagEnabled,
                        onToggle = { vm.setAgentDiagEnabled(!vm.agentDiagEnabled) },
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            item(key = "dump") {
                // v1.3.15:「导出对话」——把完整现场(文字/思考链/工具调用/样本)写成文件。
                // 为什么放这里:诊断样本只有每阶段 500 字的回复开头,深度排查需要完整对话;
                // 而 release 包读不到内部存储,所以由 App 写到外部私有目录供 adb pull。
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                Column(
                    Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp)).background(c.surface)
                        .padding(16.dp),
                ) {
                    Text("导出对话现场", style = body(14.5f, FontWeight.Bold, c.text))
                    Text(
                        "把本次会话的文字、思考链、工具调用一起写成文件;含对话内容,分享给他人前请先检查。",
                        style = body(12f, FontWeight.Normal, c.n600).copy(lineHeight = 17.sp),
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    PillButton("导出到文件", onClick = { vm.exportAgentChatDump() })
                    vm.agentDumpPath?.let { p ->
                        Text(
                            p,
                            style = body(11f, FontWeight.Normal, c.n500),
                            modifier = Modifier.padding(top = 8.dp).clickableNoRipple {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(p))
                                vm.showToast("路径已复制", null)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (samples.isEmpty()) {
                item(key = "empty") {
                    Text("还没有样本。", style = body(14f, FontWeight.SemiBold, c.text))
                    Text(
                        "触发一次 Agent 任务(例如让它排一次曲序)后回来看。",
                        style = body(12.5f, FontWeight.Normal, c.n600),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } else {
                item(key = "listlabel") {
                    Column(Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                        Text("最近 " + samples.size + " 条", style = body(13f, FontWeight.ExtraBold, c.text))
                    }
                }
                items(samples, key = { it.tsMs.toString() + it.stage }) { s ->
                    AgentDiagRow(s)
                    MemoryRowDivider()
                }
            }
            item(key = "actions") {
                Spacer(Modifier.height(14.dp))
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("复制全部", onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(vm.agentDiagExportText()))
                        vm.showToast("已复制", null)
                    })
                    PillButton("清空", onClick = { vm.clearAgentDiag() })
                }
            }
        }
    }
}

/** 一条诊断样本(紧凑两行 + 可选的模型回复开头)。 */
@Composable
private fun AgentDiagRow(s: com.shiyin.music.data.log.AgentDiag.Sample) {
    val c = LocalOrganic.current
    val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
        .format(java.util.Date(s.tsMs))
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(time, style = body(11.5f, FontWeight.Normal, c.n500))
            Text(s.skill, style = body(13f, FontWeight.Bold, c.text))
            Text(s.stage, style = body(11.5f, FontWeight.Normal, c.n600))
            Text(
                if (s.ok) "OK" else "FAIL",
                style = body(11.5f, FontWeight.Bold, if (s.ok) c.n700 else c.n400),
            )
        }
        if (s.counts.isNotEmpty()) {
            Text(
                s.counts.entries.joinToString(", ") { "${it.key}=${it.value}" },
                style = body(11.5f, FontWeight.Normal, c.n600),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        s.errorCode?.let {
            Text("error: $it", style = body(11.5f, FontWeight.Normal, c.n400), modifier = Modifier.padding(top = 3.dp))
        }
        s.replyHead?.let {
            Text(
                "模型回复开头: " + it,
                style = body(11.5f, FontWeight.Normal, c.n500).copy(lineHeight = 16.sp),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

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
import androidx.compose.ui.focus.onFocusChanged
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
import com.shiyin.music.ui.components.ThreeStopSegmentedControl
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
        FilesView.ArtistManage -> ArtistManageScreen(vm, scrollStates)
        FilesView.HiddenArtists -> HiddenArtistsScreen(vm, scrollStates)
        FilesView.LyricsBook -> LyricsBookScreen(vm, scrollStates)
        FilesView.LyricsSources -> LyricsSourcesScreen(vm, scrollStates)
        FilesView.CloudBackup -> CloudBackupScreen(vm, scrollStates)
        FilesView.PlaySettings -> PlaySettingsScreen(vm, scrollStates)
        FilesView.KaraokeSettings -> KaraokeSettingsScreen(vm, scrollStates)
        FilesView.Equalizer -> EqualizerScreen(vm, scrollStates)
        FilesView.Logs -> LogsScreen(vm, scrollStates)
        FilesView.DataManage -> DataManageScreen(vm, scrollStates)
        FilesView.FilesManage -> FilesManageScreen(vm, scrollStates)
        FilesView.Artists -> ArtistsScreen(vm, scrollStates)
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
/** 坚果云 WebDAV 固定地址（预设档位「坚果云」回填用）。 */
private const val JIANGUO_WEBDAV = "https://dav.jianguoyun.com/dav/"

@Composable
private fun CloudBackupScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    // 进页时载入已保存的凭据（一次性）
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadWebDavConfig() }
    // v1.5.0 A61：**进页面就自动拉一次云端备份列表**。
    // 原来只有点"测试连接"/"备份到云端"之后列表才有内容，而列表为空时整块（含每行的
    // 「恢复」）不渲染 —— 用户根本看不到"从云端恢复"的入口，只能自己去翻文件夹。
    androidx.compose.runtime.LaunchedEffect(vm.webDavUrl, vm.webDavUser, vm.webDavPassword) {
        if (vm.webDavUrl.isNotBlank() && vm.webDavUser.isNotBlank() &&
            vm.cloudFiles.isEmpty() && !vm.cloudBusy
        ) {
            vm.cloudRefresh()
        }
    }

    var url by remember { mutableStateOf(JIANGUO_WEBDAV) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    // 2026-10-22 用户："做成下拉单选框，默认选坚果云，也可新增自定义渠道"。 */
    var selectedProfile by remember { mutableStateOf("坚果云") }
    var showAddProfile by remember { mutableStateOf(false) }
    // 删除自定义渠道的目标（弹窗与下拉菜单都要用 ⇒ 提到函数级作用域）
    var pendingDeleteProfile by remember { mutableStateOf<String?>(null) }
    // 凭据异步载入后回填输入框（只填一次，避免覆盖用户正在输入的内容）
    androidx.compose.runtime.LaunchedEffect(vm.webDavUrl, vm.webDavUser, vm.webDavPassword) {
        if (!loaded && (vm.webDavUrl.isNotEmpty() || vm.webDavUser.isNotEmpty())) {
            url = vm.webDavUrl.ifBlank { JIANGUO_WEBDAV }
            user = vm.webDavUser; pass = vm.webDavPassword; loaded = true
            selectedProfile = when {
                vm.webDavProfiles.any { it.url == vm.webDavUrl } ->
                    vm.webDavProfiles.first { it.url == vm.webDavUrl }.name
                vm.webDavUrl.isBlank() || vm.webDavUrl.contains("dav.jianguoyun.com") -> "坚果云"
                else -> "自定义"
            }
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
                "「测试连接」会真的写一个临时文件再删掉，通过即代表备份能成。\n\n" +
                "备份文件包含 API Key，明文存储，请勿放在公开位置。",
        ) { vm.navFilesBack() }

        // ── 服务器配置 ──────────────────────────────────────────────────
        SectionLabel("服务器")
        Column(
            Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp)).background(c.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 2026-10-22（用户两轮澄清后的定稿）：渠道选择器做成**与输入框同规格的长条**
            // （同高、同圆角边框），点开下拉选 坚果云 / 已保存的渠道 / 新增自定义。
            // 选「新增自定义」→ 长条就地变成**渠道名称输入框**（下面的地址/账号/密码不动，
            // 由「保存凭据」一并把这条渠道存下来）；反悔就再点右侧箭头、选回坚果云。
            var dropOpen by remember { mutableStateOf(false) }
            var namingNew by remember { mutableStateOf(false) }
            var newName by remember { mutableStateOf("") }
            val menuShape = RoundedCornerShape(4.dp)   // 与 OutlinedTextField 默认形状一致

            @Composable
            fun channelMenu() {
                androidx.compose.material3.DropdownMenu(
                    expanded = dropOpen,
                    onDismissRequest = { dropOpen = false },
                    containerColor = c.surface,
                    // 与长条同宽（下拉默认按内容宽度收窄，需求要求"跟这个长条一样长"）
                    modifier = Modifier.fillMaxWidth(0.82f),
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("坚果云", style = body(13f, FontWeight.Normal, c.text)) },
                        onClick = {
                            selectedProfile = "坚果云"
                            url = JIANGUO_WEBDAV
                            namingNew = false; newName = ""
                            dropOpen = false
                        },
                    )
                    vm.webDavProfiles.forEach { prof ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(prof.name, style = body(13f, FontWeight.Normal, c.text)) },
                            trailingIcon = {
                                // 2026-10-22 用户："云备份自己设置的渠道也要允许删除啊"
                                // ⇒ 自定义渠道右侧加一个删除钮（点了走二次确认）。
                                Box(
                                    Modifier.size(32.dp).clip(CircleShape)
                                        .clickableNoRipple { pendingDeleteProfile = prof.name },
                                    contentAlignment = Alignment.Center,
                                ) { OIcon(Lucide.Trash, 15.dp, c.n500) }
                            },
                            onClick = {
                                selectedProfile = prof.name
                                url = prof.url; user = prof.user; pass = prof.password
                                namingNew = false; newName = ""
                                dropOpen = false
                            },
                        )
                    }
                    androidx.compose.material3.DropdownMenuItem(
                        // 用户："不需要省略号，同时也不需要加橙色，正常灰色就行"
                        text = { Text("＋ 新增自定义", style = body(13f, FontWeight.Normal, c.text)) },
                        onClick = {
                            namingNew = true; newName = ""
                            // 定稿："自定义的时候不要默认填入坚果云的网站"
                            // —— 只清掉坚果云的默认地址（用户自己填过的地址不动）。
                            if (url == JIANGUO_WEBDAV) url = ""
                            dropOpen = false
                        },
                    )
                }
            }

            Box(Modifier.fillMaxWidth()) {
                if (namingNew) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("渠道名称", style = body(12f, FontWeight.Normal, c.n600)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = body(13f, FontWeight.Normal, c.text), colors = tfColors,
                        trailingIcon = {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).clickable { dropOpen = true },
                                contentAlignment = Alignment.Center,
                            ) { OIcon(Lucide.ChevronDown, 18.dp, c.n500) }
                        },
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().height(56.dp)
                            .clip(menuShape)
                            .border(1.dp, c.n400, menuShape)
                            .clickable { dropOpen = true }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selectedProfile,
                            style = body(13f, FontWeight.Normal, c.text),
                            modifier = Modifier.weight(1f),
                        )
                        OIcon(Lucide.ChevronDown, 18.dp, c.n500)
                    }
                }
                channelMenu()
            }
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
                    "保存凭据",
                    onClick = {
                        // 正在新增渠道：把这条渠道（名称 + 当前地址/账号/密码）一并存下来
                        if (namingNew) {
                            if (newName.isBlank()) {
                                vm.showToast("请先填写渠道名称", null)
                            } else {
                                vm.saveWebDavProfile(newName, url, user, pass)
                                selectedProfile = newName.trim()
                                namingNew = false
                            }
                        }
                        vm.saveWebDavConfig(url, user, pass)
                    },
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
            // 2026-10-23 用户："云端备份不要一起排列，而是按日期：2026年X月X日 → 下面挂
            // 当天那几个备份" ⇒ 按**文件名里的日期**（pickupmusic-backup-YYYYMMDD-HHmmss）
            // 分组，日期作小标题、当天文件缩进列出，各自一个「恢复」。
            val dateOf: (com.shiyin.music.data.backup.WebDavXml.Entry) -> String = { e ->
                val m = Regex("""(\d{4})(\d{2})(\d{2})""").find(e.name)
                if (m != null) "${m.groupValues[1]}年${m.groupValues[2].trimStart('0')}月${m.groupValues[3].trimStart('0')}日"
                else "更早"
            }
            val grouped = vm.cloudFiles
                .sortedByDescending { it.name }
                .groupBy(dateOf)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((date, files) in grouped) {
                    Column(
                        Modifier.fillMaxWidth().shadowSm(RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp)).background(c.surface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(date, style = body(14.5f, FontWeight.ExtraBold, c.text))
                        for ((idx, f) in files.withIndex()) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = !vm.cloudBusy) { vm.cloudRequestRestore(f.name) }
                                    .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "— ${f.name}",
                                        style = body(13f, FontWeight.Normal, c.text),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    if (f.size > 0) {
                                        Text(
                                            "%.1f MB".format(f.size / 1024.0 / 1024.0),
                                            style = body(11f, FontWeight.Normal, c.n500),
                                        )
                                    }
                                }
                                Text("恢复", style = body(12.5f, FontWeight.Bold, c.accent))
                            }
                            if (idx < files.lastIndex) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                            }
                        }
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

    // 删除自定义渠道（二次确认；只删这条渠道记录，不影响已保存的当前凭据）
    pendingDeleteProfile?.let { name ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDeleteProfile = null },
            containerColor = c.surface,
            title = { Text("删除渠道", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                Text(
                    "将删除「$name」这条云备份渠道。当前已保存的账号密码不受影响。",
                    style = body(12.5f, FontWeight.Normal, c.n700).copy(lineHeight = 19.sp),
                )
            },
            confirmButton = {
                com.shiyin.music.ui.components.DialogActionsRow(
                    listOf(
                        com.shiyin.music.ui.components.DialogAction("取消") { pendingDeleteProfile = null },
                        com.shiyin.music.ui.components.DialogAction("删除", emphasized = true) {
                            vm.deleteWebDavProfile(name)
                            if (selectedProfile == name) selectedProfile = "坚果云"
                            pendingDeleteProfile = null
                        },
                    ),
                    emphasizedColor = DANGER_RED,
                )
            },
        )
    }

    // （「新增自定义」不再弹表单：在配置卡里就地填渠道名称 + 原有地址/账号/密码，
    //   由「保存凭据」一并保存 —— 定稿 澄清的口径。）
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
    // 实际落盘的日志文件清单——需求方明确要求"打开就有可以看到的文件"，
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
    // 可忽略；原来只在进页面时读一次，用户必须退出重进才能看到新记录——需求方明确
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

        // 文件清单放在内容之前——需求原话"打开就有可以看到的文件了才对"：
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
private fun SubPageHeader(
    title: String,
    help: Pair<String, String>? = null,
    badge: (@Composable () -> Unit)? = null,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BackButton(onBack)
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = heading(24))
            badge?.invoke()
        }
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
 * SettingRow/CardEntryRow 各自为政,同类型行高度不一致(实测反馈"各个功能占据的
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
    badge: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalOrganic.current
    Row(
        Modifier
            .fillMaxWidth()
            // v1.3.10 需求 A20:整行点击去涟漪(用户不喜欢灰块反馈)
            .then(if (onClick != null) Modifier.clickableNoRipple(onClick = onClick) else Modifier)
            // 2026-10-22 用户："设置项统一行高到约 64～68dp" —— 原 13dp 内边距 + 56dp 下限
            // 实际约 82dp，单行入口（播放/数据管理）显得空、和"关于我们"（47dp）又不齐。
            // 现在统一 10dp + 44dp 下限 ⇒ 总高 ≈64dp，全站设置行（含开关行、入口行）一致。
            .padding(vertical = 10.dp)
            .heightIn(min = 44.dp),
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
                badge?.invoke()
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
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = LocalOrganic.current
    SettingsRowScaffold(
        title = title,
        sub = sub,
        leading = { OIcon(icon, 19.dp, iconTint) },
        divider = divider,
        badge = badge,
        onClick = onClick,
        trailing = {
            // 尾注文字 + 箭头必须**横排**——骨架把 trailing 包进了一个 Box
            // （Box 会叠放子项），直接并列两个 composable 会叠在一起（真机实测：
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
            // v1.4.0: 主题三档（系统主题 / 浅色 / 深色）——替换原来的「深色模式」单个开关。
            // 「系统主题」= 读手机当时的深浅再切过去（本质仍只有浅/深两态，由系统值决定）。
            // 老用户落点不变：没有「跟随」键 → 走原来的 dark_theme（浅/深）。
            // 上下各 13dp —— 与 SettingsRowScaffold 的行内边距一致，否则底下那条分界线
            // 会贴住分段控件（真机反馈："这条线几乎就是贴着系统主题"）。
            Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    // 图标必须与三段的手机/太阳/月亮**都不同**（用户多次强调图标不要重复），
                    // 且不能是"扫把"——用官方 palette（主题/外观语义）。
                    OIcon(Lucide.Palette, 19.dp, c.n700)
                    Text("系统主题", style = body(15f, FontWeight.SemiBold, c.text))
                }
                Spacer(Modifier.height(10.dp))
                ThreeStopSegmentedControl(
                    labels = listOf("系统主题", "浅色", "深色"),
                    selectedIndex = when {
                        vm.themeFollowSystem -> 0
                        vm.darkTheme -> 2
                        else -> 1
                    },
                    icons = listOf(Lucide.Smartphone, Lucide.Sun, Lucide.Moon),
                    onSelect = { i ->
                        vm.setThemeMode(
                            when (i) {
                                0 -> com.shiyin.music.data.ThemeMirror.MODE_SYSTEM
                                2 -> com.shiyin.music.data.ThemeMirror.MODE_DARK
                                else -> com.shiyin.music.data.ThemeMirror.MODE_LIGHT
                            },
                        )
                    },
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            // v1.3.x: 睡眠定时可编辑——整行点击弹窗(时长档位/本曲结束/关闭),
            // 复用播放菜单同套 PlayerController 接口;不再只读展示。
            var sleepEditOpen by remember { mutableStateOf(false) }
            // ⚠️ 2026-10-18：原来这里还有一行「歌手栏目」——已收进「资源管理 · 歌手管理」
            // （用户："把当前歌手合并改为歌手管理，然后里面集成歌手栏目、歌手合并、歌手写真源，
            // 免得设置太难看"）。三处只留一个入口，避免同一页多点可达。
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
            // v1.3.x 图标审计:歌词本入口用 Library(书本=歌词本,未占用);振假名默认
            // 行保留 Captions(注音语义),两处不再撞图标。
            CardEntryRow(
                // 2026-10-21 图标审查：原为 `Library`（书架）→ `book-text`（翻开的书）。
                Lucide.BookText, c.n700, "歌词本",
                null,
                divider = true,
            ) { vm.navFilesTo(FilesView.LyricsBook) }
            // 2026-10-18：原来这里的两行「写真源」「歌手合并」已并入「歌手管理」子页
            // 2026-10-19 用户："这个应该是**歌手管理**，而不是隐藏客串歌手……这是一个统一的
            //   归纳功能。然后**不要有**显示隐藏了多少、已开启什么" ⇒ 入口行只有名字，无尾注。
            CardEntryRow(
                // ⚠️ 图标不能撞：这一行原来用 `Users`，而里面「**歌手合并**」那个按钮也是 `Users`
                // —— 定稿 直接指出"歌手管理的图标跟歌手合并一样，我说了不要雷同图标"。
                // 中间一度换成 `Layers`，但那是"多层收纳"，**不表达歌手**（定稿：
                // "图标要表达歌手/艺人，伴唱已经占用了麦克风，歌手管理不要再用麦克风"）。
                // 现在用**新增**的 `UsersRound`（一群歌手），与同页其余入口零重复。
                Lucide.UsersRound, c.n700, "歌手管理",
                null,
                divider = false,
                trailingText = null,
            ) { vm.navFilesTo(FilesView.ArtistManage) }
        }

        // v1.3.11:「拾光记忆」已移到「拾光 Agent 设置」里(定调:它属于 Agent 域,
        // 不该混在文件/资源设置里)。这里不再保留第二个入口,避免同一页两处可达。

        // v1.3.10 需求 A24:「播放」与「数据管理」**拆成两张卡**。
        // 此前两项共用一张卡(理由是"不为只有一个子项的类别建独立栏目标题"),但用户
        // 明确觉得"两个不相关的组件连在一起"很怪 → 各占一张卡、各一行,视觉上断开。
        // 仍然不给它们加栏目标题(避免空壳分类,与原决定的意图一致)。
        // v1.3.10(二改):实测反馈"两张卡太大了,应该和其他选项一样大小"——单行卡原来
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
                // 2026-10-22：与设置行统一高度（此前只有 47dp，比其它入口矮一截）。
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .heightIn(min = 44.dp),
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
            // AI 翻译结果不受这个开关影响、总是保存（token 是需求方明确花掉的）。
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
    // v1.5.0 A60：备份内容分类（二级：父类分组 + 子项开关），默认全选。
    var backupCats by remember { mutableStateOf(com.shiyin.music.data.db.BackupCategory.ALL) }
    var catSizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    // 2026-10-22：本页「缓存」新增"伴唱缓存"一行 —— 顺手刷新它的占用读数
    //（与播放-伴唱页同一份数据；那边进页也会刷）。
    var pendingClearStems by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        catSizes = vm.backupCategorySizes()
        vm.refreshKaraokeSettings()
    }
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
                val bytes = vm.exportBackupZip(backupCats)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                // v22: 备份里**含 API Key / 供应商配置**（定调：要进，否则换机最痛），
                // 所以导出后必须明确提醒一句"含密钥"，让用户知道这个文件不能随便发。
                backupMsg = "已备份（${"%.1f".format(bytes.size / 1024.0 / 1024.0)} MB）· " +
                    "含 API Key（明文），请勿放在公开位置"
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
                divider = true,
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
                        ) { Text("清理", style = body(12.5f, FontWeight.Bold, c.text)) }
                    }
                },
            )
            // 2026-10-22 用户："数据管理中的缓存下面加入伴唱缓存的删除，不然用户可能
            // 忘记了，因为伴唱的缓存在播放-伴唱那里" ⇒ 这里再放一个入口（同一份
            // 体积读数与清空逻辑，点清空仍走二次确认）。
            SettingsRowScaffold(
                title = "伴唱缓存",
                leading = { OIcon(Lucide.MicVocal, 19.dp, c.n700) },
                divider = false,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            com.shiyin.music.data.karaoke.MdxModel.humanSize(vm.stemCacheBytes),
                            style = body(12.5f, FontWeight.Normal, c.n600),
                        )
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(c.n200)
                                .clickable(enabled = vm.stemCacheBytes > 0) { pendingClearStems = true }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) { Text("清理", style = body(12.5f, FontWeight.Bold, c.text)) }
                    }
                },
            )
        }

        // 清空伴唱缓存：与播放-伴唱页同一套二次确认（不可逆，防误触）
        if (pendingClearStems) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingClearStems = false },
                containerColor = c.surface,
                title = { Text("清空伴奏缓存", style = body(15f, FontWeight.ExtraBold, c.text)) },
                text = {
                    Text(
                        "将清空全部已分离好的伴奏（当前占用 " +
                            "${com.shiyin.music.data.karaoke.MdxModel.humanSize(vm.stemCacheBytes)}）。" +
                            "清空后再听需要重新分离。",
                        style = body(12.5f, FontWeight.Normal, c.n700).copy(lineHeight = 19.sp),
                    )
                },
                confirmButton = {
                    DialogActionsRow(
                        listOf(
                            DialogAction("取消") { pendingClearStems = false },
                            DialogAction("清空", emphasized = true) {
                                vm.clearStemCache(); pendingClearStems = false
                            },
                        ),
                        emphasizedColor = DANGER_RED,
                    )
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
            // ── v1.5.0 A60：备份内容分类（拍板：每项一个开关 + 实时大小，
            //    体积由用户自己权衡；相关项归父类，如"拾光 Agent → 聊天记录/记忆"）──
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                for (group in com.shiyin.music.data.db.BackupGroup.entries) {
                    val items = com.shiyin.music.data.db.BackupCategory.entries.filter { it.group == group }
                    if (items.isEmpty()) continue
                    // 2026-10-23 用户：分组栏名（曲库整理/歌词与注音/拾光 Agent…）用主题
                    // 强调色，与下面的功能子项（c.text 主文字色）区分开——原来同用灰
                    // 字（n600），栏名与子项在视觉上一片、分不出层级。
                    Text(
                        group.title,
                        style = body(11.5f, FontWeight.ExtraBold, c.a700).copy(letterSpacing = 1.sp),
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                    for (cat in items) {
                        val on = cat.id in backupCats
                        val size = catSizes[cat.id]
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(cat.label, style = body(13.5f, FontWeight.Normal, c.text), modifier = Modifier.weight(1f))
                            Text(
                                size?.let { fmtBytes(it) } ?: "…",
                                style = body(11.5f, FontWeight.Normal, c.n600),
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            OrganicSwitch(on) {
                                backupCats = if (on) backupCats - cat.id else backupCats + cat.id
                            }
                        }
                    }
                }
                val total = catSizes.filterKeys { it in backupCats }.values.sum()
                Text(
                    "合计 " + fmtBytes(total),
                    style = body(12f, FontWeight.SemiBold, if (com.shiyin.music.data.db.BackupCategory.SECRETS.id in backupCats) c.a700 else c.n600),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
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
/** v1.5.0 A60：备份各项体积显示（B/KB/MB，保留一位小数）。 */
private fun fmtBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

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
            // 2026-10-21 图标审查：原为 `Captions`（字幕），与本页「默认显示译文」**撞图标**
            // ⇒ 换 `scan-text`（扫描框+文字＝"扫描匹配歌词"）。
            SettingRow(Lucide.ScanText, "自动匹配歌词", null, divider = true) {
                OrganicSwitch(vm.autoMatch) { vm.setAutoMatch(!vm.autoMatch) }
            }
            // v1.3.10 需求 A29:歌词源管理入口(用户指定位置:就在「自动匹配歌词」下面)
            SettingsRowScaffold(
                title = "歌词源管理",
                // 2026-10-21 图标审查：原为 `ListMusic`（歌单架，不是"源"）→ `database`（数据源）。
                leading = { OIcon(Lucide.Database, 19.dp, c.n700) },
                divider = true,
                trailing = { OIcon(Lucide.ChevronRight, 16.dp, c.n400) },
                onClick = { vm.navFilesTo(FilesView.LyricsSources) },
            )
            // 2026-10-22 用户："图标改为平假名的 A，与歌词本按钮保持一致"
            // ⇒ "ア"（片假名）→ "あ"（平假名，与歌词本那枚「あ」按钮同字）。
            SettingRowGlyph(glyph = "あ", "日语歌默认开振假名", divider = true) {
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
                "已同步的歌词也能随时进入校对", divider = true,
            ) {
                OrganicSwitch(vm.lyricsCalibAlwaysOn) { vm.setLyricsCalibAlwaysOn(!vm.lyricsCalibAlwaysOn) }
            }
            // v1.5.0 伴唱总开关（规格 §10）。默认开；关闭后歌词页不显示麦克风入口，
            // 设备慢的用户可彻底关掉。
            // ⚠️⚠️ 这里**推翻了 2026-10-17 的一次定稿**：原来写着"图标用音符，**不用那个残缺的
            //   麦克风**（验收指出）"。本轮图标审查里用户对这一行选了 `mic-vocal`。
            //   判断：当时反对的是 `Lucide.Mic` **那个图形的形状**（后来歌词页的麦克风按钮
            //   是照样图手绘的 `DesignMicIcon`），不是反对"话筒"这个语义；`mic-vocal` 是另一张图。
            //   若理解错、或你看过觉得不该出现话筒，说一声单独回退这一行（本行改动独立成提交）。
            SettingRow(Lucide.MicVocal, "启用伴唱功能", null, divider = true) {
                OrganicSwitch(vm.karaokeEnabled) { vm.setKaraokeEnabled(!vm.karaokeEnabled) }
            }
            // A73（定稿）：分轨默认模型。**只有 9482 内置**（A57），其余两项
            // 2026-10-17：原来这里有一段"分轨模型"三选一（当时另两个没内置、置灰不可选）。
            // 现在三个模型都能从「设置 · 播放 · 伴唱」下载并选择，那段既过时又与那边重复，
            // 按需求要求"歌词本只留一个伴唱按钮"→ 配置统一收进伴唱设置页，这里删掉。
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
            // 2026-10-21 用户："设置中外语自动翻译和默认显示译文的图标一样，给他们做点区分，
            // 建议更改自动翻译的，现在**没有体现出自动的特点**"
            // ⇒ 自动翻译改 `Sparkles`（✨＝自动/智能；项目里 0 引用、不新增图标），
            //    「默认显示译文」保留 `Languages`（＝译文用哪种语言）。
            // 2026-10-22 用户："外语歌自动翻译的图标还是与功能名字无关，重选一个"
            // ⇒ `Languages`（语言/翻译＝功能本身）；「默认显示译文」改 `Eye`（显示/隐藏），
            //   两条不再共用图标、各自语义直给。
            SettingRow(Lucide.Languages, "外语歌自动翻译", null, divider = true) {
                OrganicSwitch(vm.translateAutoForeign) { vm.setTranslateAutoForeign(!vm.translateAutoForeign) }
            }
            SettingRow(Lucide.Eye, "默认显示译文", null, divider = true) {
                OrganicSwitch(vm.translateDefaultShow) { vm.setTranslateDefaultShow(!vm.translateDefaultShow) }
            }
            // A36: 失败提示默认关——用户嫌"翻译失败"toast 太频繁,想安静时就不弹。
            SettingRow(Lucide.Bell, "翻译失败时提示", null, divider = true) {
                OrganicSwitch(vm.translateErrorToast) { vm.setTranslateErrorToast(!vm.translateErrorToast) }
            }
            // v1.5.0 A56: 制作人行（作词/作曲/编曲/XX 演奏 等）跳过翻译。默认开。
            // 2026-10-21 图标审查第 2 轮：原为 `User`（人像，与"跳过翻译"无关）→ 快进（跳过）。
            SettingRow(Lucide.SkipForwardOutline, "歌词制作人行略过翻译", null, divider = false) {
                OrganicSwitch(vm.skipCreditsTranslation) { vm.setSkipCreditsTranslation(!vm.skipCreditsTranslation) }
            }
        }
        // 2026-10-19 用户："歌词本设置中的**伴唱相关测试内容**还用吗？不用全部给我删了"。
        // 原来这里挂着 P0 Spike 的两个调试面板（`伴唱 P0 调试` 分区 + 探针卡片 + 调试面板）——
        // 那是 spike 期的临时入口，正式版不该出现，已连文件一起删。
        // 分离/探针的**命令行驱动**（`karaoke-cmd`）不受影响，仍在 MainViewModel 里。
        Box(Modifier.height(114.dp))
    }
}

// ── v1.3.x 播放设置子页(分组定稿)────────────────────────────
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
            // v1.5.0: 非方形内嵌封面会被系统 loadThumbnail 裁成方图（如横版专辑）
            // ——开启后大图按原始比例完整显示，不再裁两侧。
            SettingRow(Lucide.Photo, "按原始比例显示封面", "关闭：统一方形；开启：长方形封面按原始比例完整显示", divider = true) {
                OrganicSwitch(vm.coverOriginalRatio) { vm.setCoverOriginalRatio(!vm.coverOriginalRatio) }
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
                divider = true,
            ) { vm.navFilesTo(FilesView.Equalizer) }
            // 2026-10-17：伴唱的"正式设置"入口（模型下载/选择/默认/删除 + 缓存策略）。
            // 歌词本那边只留一个麦克风按钮，配置一律收进这里。
            CardEntryRow(
                // 2026-10-21 图标审查：原为 `Music`（音符）→ `mic-vocal`（话筒），与“伴唱”语义一致。
                Lucide.MicVocal, c.n700, "伴唱", null,
                divider = false,
                // v1.5.0: 伴唱仍在打磨（卡死问题在查），标 BETA 管理预期。
                badge = { com.shiyin.music.ui.components.BetaTag() },
            ) { vm.navFilesTo(FilesView.KaraokeSettings) }
        }
        Box(Modifier.height(114.dp))
    }
}

// ── v1.5.0 设置 · 播放 · 伴唱（2026-10-17 需求要求出"正式版"）─────────────────
/** 本页用的别名：`RetentionMode` 全名太长，而本地 `val M = 枚举类` 在 Kotlin 里不成立。 */
private typealias StemRet = com.shiyin.music.data.karaoke.RetentionMode
// 歌词本那边**只留一个麦克风按钮**，模型/缓存这些配置全部收到这里（需求原话）。
// 模型铭牌（人声/伴奏、高频截至、相对耗时）是**从参数算出来的**，不是文案：
//  · 高频截至 = (dim_f−1) × 44100 / n_fft —— 超过它的频段人声估计恒为 0，
//    也就意味着这段原曲会原封不动留在伴奏里（齿音/镲片顶端就是这么漏出来的）；
//  · 相对耗时 = n_fft × dim_f（以 9482 = 1.00× 为基准）。
@Composable
private fun KaraokeSettingsScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshKaraokeSettings() }
    val models = com.shiyin.music.data.karaoke.MdxModel.values()
    // 二次确认的目标（定稿：删除/清空这类不可逆操作一律要确认，防误触）
    var pendingDelete by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.shiyin.music.data.karaoke.MdxModel?>(null)
    }
    var pendingClear by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateOf(scrollStates, "karaokesettings"))
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader(
            "伴唱",
            help = "伴唱怎么用" to
                "点伴唱后会把歌拆成「人声」和「伴奏」两轨，你可以只听伴奏来跟唱。\n\n" +
                "第一次拆某首歌要等一会儿（拆好的部分会存起来，同一首再听就很快）。\n\n" +
                "模型决定效果：默认的 9482 最快；想要更干净的人声消除，可以下载 Inst_HQ_3。\n\n" +
                "拆出来的伴奏占空间，下面的保存策略会自动清理旧的。",
            badge = { com.shiyin.music.ui.components.BetaTag() },
        ) { vm.navFilesBack() }

        SectionLabel("模型")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(vertical = 6.dp),
        ) {
            // 下载渠道：真机实测"下到 6% 又回到 1%"，要能自己选走哪条线
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("下载渠道", style = body(13.5f, FontWeight.SemiBold, c.text))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.shiyin.music.data.karaoke.DownloadChannel.entries.forEach { ch ->
                        KaraokeChip(
                            ch.label,
                            vm.karaokeDownloadChannel == ch,
                            c,
                        ) { vm.chooseKaraokeDownloadChannel(ch) }
                    }
                }
            }
            Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(c.divider))
            models.forEachIndexed { i, m ->
                val installed = m.id in vm.karaokeModelsInstalled
                val broken = m.id in vm.karaokeModelsBroken
                val isDefault = vm.karaokeModelId == m.id
                val downloading = vm.karaokeDownloadId == m.id
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.displayName,
                            style = body(14f, FontWeight.SemiBold, c.text),
                            modifier = Modifier.weight(1f),
                        )
                        // 状态标签**与模型名同一行、靠右**（定稿：别挤占下面那一行）
                        Text(
                            when {
                                downloading -> "下载中 ${(vm.karaokeDownloadPct * 100).toInt()}%"
                                broken -> "文件损坏"
                                installed -> if (m.bundled) "已内置" else "已下载"
                                else -> "未下载"
                            },
                            style = body(11.5f, FontWeight.Normal, if (broken) c.a700 else c.n500),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KaraokeBadge(m.stemLabel, c)
                        KaraokeBadge("高频 ${"%.1f".format(m.maxHz / 1000.0)}kHz", c)
                        KaraokeBadge("耗时 ${"%.1f".format(m.relativeCost)}×", c)
                        KaraokeBadge(com.shiyin.music.data.karaoke.MdxModel.humanSize(m.sizeBytes), c)
                    }
                    if (downloading && vm.karaokeDownloadHost.isNotEmpty()) {
                        Text(
                            "来源 ${vm.karaokeDownloadHost}",
                            style = body(11f, FontWeight.Normal, c.n500),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (downloading) {
                        // 自己画的**确定态**进度条：Material3 那个默认走的是"不确定态"动画，
                        // 两头一起动，用户看了会以为坏了。
                        Box(
                            Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(c.n100),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(vm.karaokeDownloadPct.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .background(c.accent),
                            )
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 定稿：**两态按钮** —— 未默认=不显眼的灰白「设为默认」；
                        // 已是默认=橙色「默认」且不可再点。右上角那个「默认」名牌已去掉。
                        DefaultModelButton(isDefault) { vm.setKaraokeModelId(m.id) }
                        if (!installed || broken) {
                            KaraokeAction(if (broken) "重新下载" else "下载", vm.karaokeDownloadId == null) {
                                vm.downloadKaraokeModel(m.id)
                            }
                        }
                        if (installed || broken) {
                            // 删除统一**红色** + 二次确认（定稿：要有警惕性、防误触）
                            KaraokeAction("删除", vm.karaokeDownloadId == null, danger = true) {
                                pendingDelete = m
                            }
                        }
                    }
                }
                if (i != models.lastIndex) {
                    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(c.divider))
                }
            }
        }

        SectionLabel("缓存")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            // ── 保存策略：**两行独立上限**（定稿 重构）──────────────────
            // 2026-10-20 用户：**删掉下面那行小字**（"两项同时生效…"整段不要）。
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text("保存策略", style = body(13.5f, FontWeight.SemiBold, c.text))
                // 第 1 行：按曲目 / 第 2 行：按大小
                // 2026-10-20 用户改的标签：保存 → **最多保存曲目**；最多占用 → **最多占用空间**。
                // 2026-10-20 用户："按曲目这行必须用同一套组件、同一种交互：数字输入框 + 预设下拉框。
                // 不要自己另写一套。" ⇒ 复用同一个 RetentionRow，只是把 presets 从 null 换成列表。
                // ⚠️ 预设值 0/5/10/20/50/100 是**我拟定的**（需求原话没给具体值）；
                //    默认 10 与 `MainViewModel.stemRetentionCount` 的初值一致。
                RetentionRow(
                    label = "最多保存曲目",
                    value = vm.stemRetentionCount,
                    unit = "首",
                    onCommit = { vm.setStemRetentionLimits(it, vm.stemRetentionMb) },
                    presets = listOf(
                        0 to "不限", 5 to "5 首", 10 to "10 首",
                        20 to "20 首", 50 to "50 首", 100 to "100 首",
                    ),
                )
                RetentionRow(
                    label = "最多占用空间",
                    value = vm.stemRetentionMb,
                    unit = "MB",
                    onCommit = { vm.setStemRetentionLimits(vm.stemRetentionCount, it) },
                    presets = listOf(
                        200 to "200 MB", 500 to "500 MB", 1024 to "1 GB",
                        2048 to "2 GB", 5120 to "5 GB", 0 to "不限制",
                    ),
                )
                // 已占用 + 清空（清空要二次确认）
                Row(
                    Modifier.padding(top = 14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已占用 ${com.shiyin.music.data.karaoke.MdxModel.humanSize(vm.stemCacheBytes)}",
                        style = body(13f, FontWeight.SemiBold, c.n600),
                        modifier = Modifier.weight(1f),
                    )
                    KaraokeAction("清空", vm.stemCacheBytes > 0, danger = true) { pendingClear = true }
                }
            }
        }
        Box(Modifier.height(114.dp))

        // ── 二次确认（定稿：删除/清空一律要确认，防误触）──────────────
        pendingDelete?.let { m ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingDelete = null },
                containerColor = c.surface,
                title = { Text("删除模型", style = body(15f, FontWeight.ExtraBold, c.text)) },
                text = {
                    Text(
                        "将删除「${m.displayName}」，释放 ${com.shiyin.music.data.karaoke.MdxModel.humanSize(m.sizeBytes)}。" +
                            "下次要用需要重新下载。",
                        style = body(12.5f, FontWeight.Normal, c.n700).copy(lineHeight = 19.sp),
                    )
                },
                confirmButton = {
                    // 2026-10-20 用户："删除提醒的弹出选择【删除】也没有做醒目红色字体"
                    // —— `DialogAction` 默认走 secondary 灰；标成 emphasized 并把强调色换成危险红。
                    DialogActionsRow(
                        listOf(
                            DialogAction("取消") { pendingDelete = null },
                            DialogAction("删除", emphasized = true) {
                                vm.deleteKaraokeModel(m.id); pendingDelete = null
                            },
                        ),
                        emphasizedColor = DANGER_RED,
                    )
                },
            )
        }
        if (pendingClear) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingClear = false },
                containerColor = c.surface,
                title = { Text("清空伴奏缓存", style = body(15f, FontWeight.ExtraBold, c.text)) },
                text = {
                    Text(
                        "将清空全部已分离好的伴奏（当前占用 " +
                            "${com.shiyin.music.data.karaoke.MdxModel.humanSize(vm.stemCacheBytes)}）。" +
                            "清空后再听需要重新分离。",
                        style = body(12.5f, FontWeight.Normal, c.n700).copy(lineHeight = 19.sp),
                    )
                },
                confirmButton = {
                    DialogActionsRow(
                        listOf(
                            DialogAction("取消") { pendingClear = false },
                            DialogAction("清空", emphasized = true) {
                                vm.clearStemCache(); pendingClear = false
                            },
                        ),
                        emphasizedColor = DANGER_RED,
                    )
                },
            )
        }
    }
}

/** 伴唱页的小铭牌。 */
@Composable
private fun KaraokeBadge(text: String, c: com.shiyin.music.ui.theme.OrganicColors) {
    Text(
        text,
        style = body(10.5f, FontWeight.SemiBold, c.n700),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            // 2026-10-20 用户："浅色模式下很多按钮铭牌**全是纯白色**" —— n100 在浅色下是近白
            // （#F9F4ED）贴在奶油色卡片上等于没有底；换 n300（暖灰）两套主题都成立。
            .background(c.n300)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/**
 * **删除类操作的红**（定稿："删除键要警惕一点，改成红色"）。
 *
 * ⚠️ 2026-10-19 修正：原来用 `c.a700`，而主题里的 `a700` 是**橙色系** ——
 * 深色主题下更是浅橘 `#FFC6A5`，看着像"次要操作"，一点都不像危险。
 *
 * ⚠️ 2026-10-20 实测反馈："按钮的红色又**太过头**了，亮的和主题不搭" ——
 * 从 `#D2453A`（正红）调成**砖红** `#A94A38`：饱和度与明度都收进暖色系，与 accent `#C67139` 同族。
 */
private val DANGER_RED = androidx.compose.ui.graphics.Color(0xFFA94A38)

/** 伴唱页的小操作按钮（文字按钮）。[danger]=删除类，用**红**以示警惕。 */
@Composable
private fun KaraokeAction(
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val c = LocalOrganic.current
    val fg = when {
        !enabled -> c.n500
        danger -> DANGER_RED
        else -> c.accent
    }
    Text(
        label,
        style = body(12.5f, FontWeight.SemiBold, fg),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (danger && enabled) DANGER_RED.copy(alpha = 0.13f) else c.n300)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * 「设为默认 / 默认」**两态按钮**（定稿）：
 * 未默认 = 不显眼的灰白底 + "设为默认"（可点）；已是默认 = 橙底白字 "默认"（不可再点）。
 * 原来的做法是"默认"另挂一个右上角名牌 —— 已去掉，状态就由这个按钮自己表达。
 */
@Composable
private fun DefaultModelButton(isDefault: Boolean, onClick: () -> Unit) {
    val c = LocalOrganic.current
    Text(
        if (isDefault) "默认" else "设为默认",
        style = body(12.5f, FontWeight.SemiBold, if (isDefault) c.bg else c.n600),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isDefault) c.accent else c.n300)
            .border(1.dp, if (isDefault) androidx.compose.ui.graphics.Color.Transparent else c.n400, RoundedCornerShape(10.dp))
            .then(if (isDefault) Modifier else Modifier.clickable { onClick() })
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/**
 * 缓存策略的**一行**：标签在左，**控件在右**，且数字输入与预设下拉**同在一个框里**
 * （定稿："下拉框和单选框是一体的，在框的右侧""输入框要在右侧，不要那么大，
 * 与字体行平齐"）。
 */
@Composable
private fun RetentionRow(
    label: String,
    value: Int,
    unit: String,
    onCommit: (Int) -> Unit,
    presets: List<Pair<Int, String>>?,
) {
    val c = LocalOrganic.current
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = body(13.5f, FontWeight.Normal, c.text), modifier = Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                // 2026-10-20 用户："浅色模式下很多按钮铭牌全是纯白色的，和主题不符"
                // —— n100 在浅色下是近白（#F9F4ED），换成 n300（暖灰），两套主题都与卡片底分得开。
                .background(c.n300)
                .border(1.dp, c.n400, RoundedCornerShape(10.dp))
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RetentionNumberField(value, unit, onCommit)
            if (presets != null) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.n400))
                RetentionPresetDropdown(value, presets, onPick = onCommit)
            }
        }
    }
}

/**
 * 数字输入：**窄、与同一行的字体等高等齐**（用户嫌原来的 `OutlinedTextField` "那么大"）。
 * 留空 = 该项不限制（内部按 0 处理），空态显示灰色的"不限"。
 */
@Composable
private fun RetentionNumberField(value: Int, unit: String, onCommit: (Int) -> Unit) {
    val c = LocalOrganic.current
    var draft by androidx.compose.runtime.remember(value) {
        androidx.compose.runtime.mutableStateOf(if (value <= 0) "" else value.toString())
    }
    // ⚠️ **只在输入完成时提交**（回车 / 移开焦点），不能每敲一个字符提交一次：
    // 占用空间这类上限是**破坏性**的（一提交就按新上限淘汰缓存），逐字符提交意味着
    // 敲 "500" 会先按 5MB、50MB 淘汰两轮 —— 真机表现就是"改个上限缓存全没了"。
    fun commit() {
        val v = if (draft.isEmpty()) 0 else draft.toIntOrNull() ?: return
        if (v != value) onCommit(v)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicTextField(
            value = draft,
            onValueChange = { s -> draft = s.filter { it.isDigit() }.take(7) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
            textStyle = body(13.5f, FontWeight.SemiBold, c.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier
                .width(54.dp)
                .padding(start = 12.dp)
                .onFocusChanged { st -> if (!st.isFocused) commit() },            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (draft.isEmpty()) {
                        Text("不限", style = body(13.5f, FontWeight.Normal, c.n500))
                    }
                    inner()
                }
            },
        )
        Text(
            unit,
            style = body(12f, FontWeight.Normal, c.n600),
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
        )
    }
}

/**
 * 预设下拉：**贴在同一个框的右半边**（用户："下拉框和单选框是一体的，在框的右侧"）。
 *
 * ⚠️ `containerColor = c.surface` 是主题适配所必需的 —— 不传的话 Material3 默认给**全白**底，
 * 深色主题下弹出来一整块白（真机实测到并指正）。
 * 这条与项目里 `ArtistAlbumList` 的下拉框用法一致。
 */
@Composable
private fun RetentionPresetDropdown(current: Int, presets: List<Pair<Int, String>>, onPick: (Int) -> Unit) {
    val c = LocalOrganic.current
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        // 2026-10-20 用户："下拉框**不要**加一个'预设'，而是（点击/手写填入的区域）
        //   +（▾，**最右边的一小块**，下拉选预设）" ⇒ 触发区只剩一个小箭头，
        //   左边整块都是可以手写填入的输入区。
        Box(
            Modifier.fillMaxHeight().clickable { open = true }.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) { OIcon(Lucide.ChevronDown, 15.dp, c.n700) }
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = c.surface,
        ) {
            presets.forEach { (mb, label) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, style = body(13f, FontWeight.Normal, c.text)) },
                    onClick = { open = false; onPick(mb) },
                )
            }
        }
    }
}

/**
 * **可选项胶囊**（下载渠道等"三选一"）。
 *
 * ⚠️ 2026-10-19 验收指出"单选框主题适配没做"。真因：未选中态原本用 `n100` 填底，
 * 而**深色主题下 `n100`(#2E2B25) 与卡片底 `surface`(#2B2620) 几乎同色** ⇒ 胶囊边界看不见，
 * 看着就像一堆散落的文字。改成：
 *  · 未选中 = `n100` 底 **+ `n400` 描边**（描边在两套主题下都与卡片底有明显对比）；
 *  · 选中 = 实心 accent，文字用 `c.bg`（与原生三档滑块/播放主键同一个"实心橙底上的文字"惯例）。
 */
@Composable
private fun KaraokeChip(label: String, selected: Boolean, c: com.shiyin.music.ui.theme.OrganicColors, onClick: () -> Unit) {
    Text(
        label,
        style = body(12f, FontWeight.SemiBold, if (selected) c.bg else c.n600),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.accent else c.n300)
            .border(
                1.dp,
                if (selected) androidx.compose.ui.graphics.Color.Transparent else c.n400,
                RoundedCornerShape(10.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

// ── v1.3.x 均衡器子页(实测反馈"均衡器不是开关,是多频段调节")──────────────────
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
            // 2026-10-22 用户："默认开启振假名的平假名 A 显示不全（底部）修复他"。
            // 原因：19dp 方框 + 17sp 的 CJK 字形，行框上下还带着字体的额外 ascent/descent
            // ⇒ 底部被裁。按歌词本那枚「あ」的同款做法：Trim.Both 剥掉额外量，
            // 并把方框加高到 22dp、字号略收，保证整个假名完整可见且与图标行等高。
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Text(
                    glyph,
                    style = body(16f, FontWeight.ExtraBold, c.n700).copy(
                        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
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
                    onClick = { vm.playFromList(tracks, t.id) },
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

// ── v1.5.0 设置 · 歌手管理（定稿 要求合并）────────────────────────
/**
 * **歌手管理**：把散在设置各处的三个歌手相关子页收进一处。
 *
 * 需求原话："把当前歌手合并改为歌手管理，然后里面集成歌手栏目、歌手合并、歌手写真源等
 * 与歌手相关的，免得设置太难看"。
 *
 * 纯**导航收拢**：三个子页原样搬进来，逻辑一个没动；一级页由原来的三行变成一行。
 */
@Composable
private fun ArtistManageScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateOf(scrollStates, "artistmanage"))
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubPageHeader(
            "歌手管理",
            help = "这里管什么" to
                "隐藏客串歌手 —— 只在别人歌里客串的歌手，默认不在歌手栏占位置。\n\n" +
                "歌手合并 —— 同一个人被识别成好几个名字时，把它们并成一个。\n\n" +
                "写真源 —— 歌手页的图片从哪几个来源找。",
        ) { vm.navFilesBack() }

        // 定稿：**三个大按钮**。上面那三行「隐藏客串歌手 / 歌手合并 / 写真源」
        // 小标题全部去掉 —— 它们本来就是同一级的三个功能，各自点进去就行。
        ArtistManageButton(Lucide.UserRoundX, "隐藏客串歌手") { vm.navFilesTo(FilesView.HiddenArtists) }
        ArtistManageButton(Lucide.Merge, "歌手合并") { vm.navFilesTo(FilesView.Merges) }
        ArtistManageButton(Lucide.Photo, "写真源") { vm.navFilesTo(FilesView.ImageSources) }

        Box(Modifier.height(114.dp))
    }
}

/**
 * 歌手管理页上的一个**大按钮**（整卡可点）。
 *
 * ⚠️ 2026-10-19 用户："三个项被你缩小太多了，保留之前的大小" —— 所以这里**不再自己写行布局**，
 * 直接用全 App 统一的 [SettingsRowScaffold]（卡片 + 行 = 与"歌手合并/写真源"原来一模一样的高度）。
 * 变的是"上面不再有小标题"，不是"行变小了"。
 *
 * ⚠️ **2026-10-20 修：左右内边距漏了**。用户："三项的图标位置贴近边界，几乎超出界限，
 * 没有参考其他页面。"
 *
 * 根因（读代码得出，非推测）：[SettingsRowScaffold] **自身不带左右内边距** —— 它的排版只有
 * `padding(vertical = 13.dp)` + `heightIn(min = 56.dp)`，横向留白一律由**父卡片**提供。
 *  · 参照页（设置主页的入口行 `CardEntryRow`）：卡片 `padding(horizontal = 16.dp, vertical = 6.dp)`
 *    ⇒ 页面 `padding(horizontal = 20.dp)` + 卡片 `16.dp` = 图标左边界 **x = 36dp**
 *  · 本页原来只有 `padding(vertical = 6.dp)` ⇒ 图标左边界 **x = 20dp**，比参照页少 16dp
 *    —— 正是"贴近边界"的成因。
 *
 * ⇒ 补上 `horizontal = 16.dp`，与参照页逐像素对齐（图标 36dp / 标题 68dp）。
 */
@Composable
private fun ArtistManageButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxWidth()
            .shadowSm(RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(c.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        SettingsRowScaffold(
            title = label,
            sub = null,
            leading = { OIcon(icon, 19.dp, c.n700) },
            divider = false,
            onClick = onClick,
            trailing = { OIcon(Lucide.ChevronRight, 18.dp, c.n500) },
        )
    }
}

/**
 * **隐藏客串歌手**（定稿 定稿的交互）。
 *
 * 关系要一眼看懂（原话）："关闭了隐藏客串歌手，那所有的也跟着关闭；打开了，所有跟着打开，
 * 用户就很清楚的知道是什么关系。然后就可以关掉特定的一些他不想隐藏的歌手"。
 *
 * ⇒ 所以每一位的开关语义是「**隐藏这位**」：
 *   · 总开关【开】⇒ 下面全部跟着【开】（都隐藏），点进去把想留的那几位**关掉**；
 *   · 总开关【关】⇒ 全部显示，下面的开关也全部跟着关。
 *
 * 需求方明确要求**不写任何解释文字**（"判定为客串 30 位"这类都不要）。
 */
@Composable
private fun HiddenArtistsScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateOf(scrollStates, "hiddenartists"))
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader("隐藏客串歌手") { vm.navFilesBack() }

        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            // 2026-10-21 图标审查：原为 `EyeOff`（闭眼＝「隐藏」通用语义）→ `user-round-x`，
            // 与歌手管理页/歌手栏目那两处**统一**（同一功能三处曾用三个不同图标）。
            SettingRow(Lucide.UserRoundX, "隐藏客串歌手", null, divider = false) {
                OrganicSwitch(vm.hideFeatureArtists) { vm.setHideFeatureArtists(!vm.hideFeatureArtists) }
            }
            // 逐个列出；开关 = "隐藏这位"。**不写任何解释**（需求要求）。
            vm.featureArtistsForManage().forEach { name ->
                Box(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(c.divider),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = body(14f, FontWeight.Normal, c.text), modifier = Modifier.weight(1f))
                    OrganicSwitch(vm.featureArtistHidden(name)) {
                        vm.setFeatureArtistHidden(name, !vm.featureArtistHidden(name))
                    }
                }
            }
            Box(Modifier.height(6.dp))
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
            // 2026-10-22 需求要求按**目标名分组**展示（原先是平铺的 "A → B"）：
            //   周杰伦
            //      — JAYCHOU      (撤销)
            //      — JAY CHOU     (撤销)
            // 每个别名各自一个"撤销"，撤销互不影响。
            val grouped = vm.aliases.entries.groupBy({ it.value }, { it.key })
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((target, froms) in grouped) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(c.surface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(target, style = body(15f, FontWeight.ExtraBold, c.text))
                        for (from in froms.sorted()) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "— $from",
                                    style = body(13.5f, FontWeight.Normal, c.n600),
                                    modifier = Modifier.weight(1f).padding(start = 10.dp),
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
            // v22 删减:去掉「完全本地运行 · 不上传任何数据」整行(定调 —— 这句话本身
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
                /** 一个模块分组：模块名 + 该模块下逐行说明（模块名单独一行，子项缩进）。 */
                data class VerGroup(val title: String, val items: List<String>)
                data class VerChange(
                    val version: String,
                    val newFeatures: List<VerGroup> = emptyList(),
                    val improvements: List<VerGroup> = emptyList(),
                    val upgradeNotes: List<String> = emptyList(),
                    val fixes: List<String> = emptyList(),
                )
                val versions = listOf(
                    VerChange(
                        version = "v1.5.0",
                        newFeatures = listOf(
                            VerGroup(
                                "伴唱（BETA）",
                                listOf(
                                    "把人声与伴奏分开，三档切换：人声 / 原曲 / 伴奏",
                                    "三种分离模型可选：9482 内置、速度最快；Inst_HQ_3 直接输出伴奏、保留更多高频；Voc_FT 另一套人声模型",
                                    "模型可下载、可本地导入、可删除；分轨缓存可查看占用并清理",
                                ),
                            ),
                            VerGroup(
                                "封面",
                                listOf("长方形封面不再被裁切，可按原始比例完整显示（设置 → 播放）"),
                            ),
                            VerGroup("右滑固定", listOf("常用专辑、歌手、歌单右滑置顶")),
                            VerGroup(
                                "播放控制",
                                listOf(
                                    "通知栏与锁屏播放卡新增收藏按钮，与 App 内同步",
                                    "升降 Key（BETA）：最多 ±6 半音，点一下走 1 个半音、长按走 0.5",
                                ),
                            ),
                            VerGroup("曲库整理", listOf("歌手管理：合并、隐藏客串歌手、写真源集中一处")),
                            VerGroup("歌词", listOf("歌词源可开关、可删除，一键恢复官方源")),
                            VerGroup("备份", listOf("备份覆盖全部用户数据，云端备份可按日期直接恢复")),
                            VerGroup(
                                "拾光 Agent",
                                listOf(
                                    "专辑曲序与发行日期更准确（接入 MusicBrainz）",
                                    "新增记忆管理页",
                                ),
                            ),
                        ),
                        improvements = listOf(
                            VerGroup(
                                "文件扫描",
                                listOf("重新扫描速度大幅提升：先出列表、再后台核对，且只核对音乐目录"),
                            ),
                            VerGroup("搜索", listOf("高亮更精准，精确标题优先")),
                            VerGroup("播放页", listOf("进度条手感更顺，纵向滑动不再误触")),
                            VerGroup("启动", listOf("首页骨架屏与封面预载，冷启动一次成型，不再闪空")),
                            VerGroup("歌词", listOf("多源并行匹配，兼容繁简差异；制作人名单行自动跳过翻译")),
                            VerGroup("翻译", listOf("失败自动重试，不再需要反复点")),
                            VerGroup("设置", listOf("行高统一，视觉更整齐")),
                        ),
                        fixes = listOf(
                            "文件扫描：文件名带 (Remix) 等标注时，标题被吞掉",
                            "曲库整理：单曲迁移到专辑后，库里显示的专辑名没跟着更新",
                            "曲库整理：更换专辑封面没有反应",
                            "曲库整理：专辑内部分歌曲的封面识别失败",
                            "歌词：同专辑部分歌曲识别不到歌词",
                            "歌词本：顶部下拉容易误触；单曲循环时歌词停在末尾；重进后当前行没有居中",
                            "右滑固定：误触、抖动、卡住",
                            "专辑：折叠时误伤同名不同版本",
                            "伴唱：升降 Key 换曲漏声、伴奏串歌、取消后重算；导出伴奏 WAV 有噪声",
                            "歌手：合并对复合艺人无效",
                            "迷你播放条：切歌时封面闪烁",
                        ),
                    ),
                    VerChange(
                        version = "v1.4.0",
                        newFeatures = listOf(
                            VerGroup(
                                "整轨拆分",
                                listOf(
                                    "按曲目清单把整轨专辑拆成单曲",
                                    "拆出的单曲可单独收藏、加入歌单、查看歌词",
                                ),
                            ),
                            VerGroup(
                                "拾光 Agent",
                                listOf(
                                    "长期记忆：偏好与近况跨对话保留，可开关",
                                    "推荐选曲：按场景或歌手推荐，附理由",
                                    "专辑曲序：按网络曲目资料整理",
                                    "强制联网搜索：可自选，遇到没把握的事实先上网查再答",
                                    "技能：放歌、播放控制、播放模式、睡眠定时、收听统计、保存歌词、曲库检索、歌曲故事、发现音乐",
                                    "对话：历史回看、导出文件",
                                ),
                            ),
                            VerGroup(
                                "歌词",
                                listOf(
                                    "歌词翻译：外文歌词译为中文，与原文逐行对照",
                                    "歌词来源：新增自定义来源、Agent 联网搜索",
                                    "歌词本设置：振假名默认开关、自动校验、配色",
                                    "日语注音：增加外部来源与模型校验",
                                ),
                            ),
                            VerGroup("搜索", listOf("支持假名与罗马字")),
                            VerGroup(
                                "备份与更新",
                                listOf(
                                    "备份：zip 归档（含本地图片）、恢复前预览、本地自动快照、WebDAV",
                                    "检查更新：设置页查看新版本与更新说明",
                                ),
                            ),
                        ),
                        improvements = listOf(
                            VerGroup(
                                "迷你播放条",
                                listOf("左右滑动切歌", "下一首封面预加载"),
                            ),
                            VerGroup(
                                "歌词本",
                                listOf(
                                    "展开、收起改为跟手过渡",
                                    "顶部下拉退出",
                                ),
                            ),
                            VerGroup(
                                "播放页与设置",
                                listOf(
                                    "播放页菜单精简，睡眠定时、播放速度移至一级",
                                    "设置页重新归类，睡眠定时可编辑",
                                ),
                            ),
                            VerGroup(
                                "专辑与歌手",
                                listOf(
                                    "多歌手选择弹窗显示写真",
                                    "相册选图先落盘，重启后不再失效",
                                ),
                            ),
                            VerGroup(
                                "其他",
                                listOf(
                                    "收藏按钮：加号过渡为对勾",
                                    "侧边栏：「拾光 Agent」移至「设置」之前",
                                ),
                            ),
                        ),
                        upgradeNotes = listOf(
                            "备份文件包含 API Key，明文存储，包括 WebDAV 备份，请勿放在公开位置。",
                            "系统云备份不再上传 API Key、设置、崩溃与使用日志、Agent 诊断与对话导出；" +
                                "还原后需重新填写 API Key 和设置（应用自带备份与换机迁移不受影响）。",
                            "导入备份时，本机已有的 API Key 不会被覆盖。",
                            "歌词页的「AI 补全」按钮已移除，改由「更换歌词来源」里的拾光 Agent 联网搜索承担。",
                        ),
                        // 🐛修复栏：只收在 V1.3.0 上复现确认过的条目（格式「位置：现象」），
                        // 确认前留空 —— 空列表不会渲染该小节。
                        fixes = emptyList(),
                    ),
                )
                for (ver in versions) {
                    Text(
                        ver.version,
                        style = body(12.5f, FontWeight.Bold, c.text).copy(letterSpacing = 0.5.sp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    // 分组小节：模块名单独一行，子项缩进一级
                    val grouped = listOf(
                        "✨ 新增" to ver.newFeatures,
                        "🚀 优化" to ver.improvements,
                    )
                    for ((label, groups) in grouped) {
                        if (groups.isEmpty()) continue
                        Text(label, style = body(11.5f, FontWeight.Bold, c.n600).copy(letterSpacing = 0.5.sp), modifier = Modifier.padding(top = 4.dp))
                        for (g in groups) {
                            Text(
                                g.title,
                                style = body(12f, FontWeight.Bold, c.text),
                                modifier = Modifier.padding(top = 3.dp, start = 2.dp),
                            )
                            for (ch in g.items) {
                                Row(
                                    Modifier.padding(start = 10.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("·", style = body(12f, FontWeight.Bold, c.n700))
                                    Text(ch, style = body(12f, FontWeight.Normal, c.n700).copy(lineHeight = 17.sp), modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    // 平铺小节：升级须知 / 修复
                    val plain = listOf(
                        "⚠️ 升级须知" to ver.upgradeNotes,
                        "🐛 修复" to ver.fixes,
                    )
                    for ((label, items) in plain) {
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
                        // 2026-10-21 图标审查：原为 `Heart`（心），改为 `coffee` —— 字面就是“喝杯咖啡”。
                        OIcon(Lucide.Coffee, 14.dp, c.a700)
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
                    // v1.5.0: 正文是 GitHub Release 的 markdown 源码 —— 交给 MarkdownLite
                    // 渲染（原先原样贴进 Text，"##"/"**" 记号会一起显示，观感很乱）。
                    // 同时删掉两行没信息量的小字（"当前版本 vX.X.X"/"将下载最新 APK…"），
                    // 把高度让给正文（260dp → 420dp）。
                    Column {
                        if (notes.isNotBlank()) {
                            com.shiyin.music.ui.components.MarkdownLiteText(
                                notes,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
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
// v1.3.x: 独立回收站子页删除——回收站段并入 CleanScreen 页顶(拍板合并)。

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
 * 刻意不做(定调):不做卡片 / 彩色分类块、不加搜索·筛选·排序、不新增分类;
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

    // 删除一律走确认(定调:不允许点一下就没了)
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
        // v1.3.13(实测反馈"点进去感觉内容被抬上去了"):全站设置子页的页头顶距是 22dp
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
                        // v1.3.13(实测反馈"你 和下面的 TAG 都是橙色时,看不清重点"):
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
 * 信息层级(定调):**内容**是最主要的视觉信息,分类次之,来源/时间最弱。
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
            // v1.3.12(实测反馈):**永远显示完整详情**(最后更新 / 记忆内容 / 来源 / 类型)。
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
 * 设计要点(需求原话:"不能说用户到时候要填什么参数找不到能填写的地方"):
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
            // 2026-10-22 ⑤（用户）：内置源行用**品牌标**（res/drawable/ic_lyrics_*.xml）。
            // 三个均为官方/用户提供的原色 SVG 转 VectorDrawable，保留品牌色、不随主题染色（Color.Unspecified）。
            // 名字→图的映射集中在这里，加新源只改这一处。
            val brandIcon: Map<String, Int> = mapOf(
                "LRCLIB" to com.shiyin.music.R.drawable.ic_lyrics_lrclib,
                "网易云音乐" to com.shiyin.music.R.drawable.ic_lyrics_netease,
                "LrcShare" to com.shiyin.music.R.drawable.ic_lyrics_lrcshare,
            )
            // 2026-10-21 ⑥（用户）："歌词源管理也要提供删除的功能，对官方提供的资源同时提供恢复的方法。
            // 界面按钮要和谐不冲突。" ⇒ 每行右侧加**删除**圆钮（与下方自定义源的铅笔/垃圾桶同语言）；
            // 删除 = 计入禁用集（与开关同一存储，持久化、全链路生效）。有删除项时出现一行「恢复官方源」。
            var pendingDeleteBuiltin by remember { mutableStateOf<String?>(null) }
            builtins.forEachIndexed { i, src ->
                val deleted = src in vm.lyricsDisabledSources
                SettingsRowScaffold(
                    title = src,
                    sub = if (deleted) "已删除" else null,
                    subColor = if (deleted) c.a700 else null,
                    leading = {
                        val res = brandIcon[src]
                        if (res != null) {
                            OIcon(painterResource(res), 19.dp, Color.Unspecified)
                        } else {
                            OIcon(Lucide.Boxes, 19.dp, if (deleted) c.n400 else c.n700)
                        }
                    },
                    divider = i != builtins.lastIndex || vm.lyricsDisabledSources.isNotEmpty(),
                    trailing = {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).clickableNoRipple { pendingDeleteBuiltin = src },
                            contentAlignment = Alignment.Center,
                        ) { OIcon(Lucide.Trash, 16.dp, c.n500) }
                    },
                )
            }
            // 「恢复官方源」：只在**确实删过**内置源时出现（没有删除项时不画空按钮）。
            if (vm.lyricsDisabledSources.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickableNoRipple { vm.restoreBuiltInLyricsSources() }
                        .padding(vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
                        OIcon(Lucide.RotateCcw, 17.dp, c.accent)
                    }
                    Text(
                        "恢复官方源（${vm.lyricsDisabledSources.size} 个已删除）",
                        style = body(15f, FontWeight.SemiBold, c.accent),
                    )
                }
            }
            // 删除确认（与清空缓存/删除模型同一套 DialogAction 红色强调惯例）
            pendingDeleteBuiltin?.let { name ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { pendingDeleteBuiltin = null },
                    containerColor = c.surface,
                    title = { Text("删除「$name」？", style = body(15f, FontWeight.ExtraBold, c.text)) },
                    text = {
                        Text(
                            "删除后不再参与自动匹配，也不出现在「歌词来源」菜单里；可在下方「恢复官方源」一键恢复。",
                            style = body(13f, FontWeight.Normal, c.n600).copy(lineHeight = 19.sp),
                        )
                    },
                    confirmButton = {
                        DialogActionsRow(
                            listOf(
                                DialogAction("取消") { pendingDeleteBuiltin = null },
                                DialogAction("删除", emphasized = true) {
                                    vm.setLyricsSourceEnabled(name, false)
                                    pendingDeleteBuiltin = null
                                },
                            ),
                            emphasizedColor = DANGER_RED,
                        )
                    },
                )
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
                    // 2026-10-21 图标审查第 2 轮：原为 `Music`（与上面「内置源」撞）→ 文件+齿轮（可自己配）。
                    leading = { OIcon(Lucide.FileCog, 19.dp, c.n700) },
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

/** 表单字段:标签 + 说明占位符(需求要求"参数要有地方填")。 */
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
 * 刻意做**最简**:列表 + 复制全部 + 开关 + 一句提示。不做详情页/筛选(定调)。
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

// ── v1.5.0 A62：歌手栏目（客串歌手不占歌手栏位）──────────────────────────────
// 只影响「歌手栏要不要给他一个位置」；他仍可从歌曲页点进去、搜索也照常能搜到。
// 判定依据见 data/artist/ArtistRole：没当过主歌手、也不在专辑作者里 → 客串。
@Composable
private fun ArtistsScreen(vm: MainViewModel, scrollStates: MutableMap<String, ScrollState>) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollStateOf(scrollStates, "artists"))
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader("歌手栏目") { vm.navFilesBack() }

        SectionLabel("歌手列表")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            SettingRow(
                // 2026-10-21 图标审查：同一功能「隐藏客串歌手」此前**三处三图标**
                // （这里 `User` / 歌手管理页 `UserRoundX` / 隐藏客串歌手页 `EyeOff`）——
                // 用户选定 `user-round-x`，本提交把三处统一。
                Lucide.UserRoundX, "隐藏客串歌手", null, divider = false,
            ) {
                OrganicSwitch(vm.hideFeatureArtists) { vm.setHideFeatureArtists(!vm.hideFeatureArtists) }
            }
        }

        // 实时数字（同"已忽略 · N 个"的既有写法：信息，不是说明文字）
        SectionLabel("客串歌手 · ${vm.featureArtistCount()} 位")
        Box(Modifier.height(114.dp))
    }
}

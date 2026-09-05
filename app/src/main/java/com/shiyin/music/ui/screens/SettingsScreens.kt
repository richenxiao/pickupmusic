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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.OrganicSwitch
import com.shiyin.music.ui.components.PasswordField
import com.shiyin.music.ui.components.PillButton
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.shadowMd
import com.shiyin.music.ui.components.shadowSm
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.Caprasimo
import com.shiyin.music.ui.theme.LocalOrganic

@Composable
fun SettingsHost(vm: MainViewModel) {
    when (vm.filesView) {
        FilesView.Root -> SettingsRoot(vm)
        FilesView.Clean -> CleanScreen(vm)
        FilesView.Trash -> TrashScreen(vm)
        FilesView.Folders -> FoldersScreen(vm)
        FilesView.FolderContent -> FolderContentScreen(vm)
        FilesView.Ignored -> IgnoredFoldersScreen(vm)
        FilesView.Devices -> DevicesScreen(vm)
        FilesView.About -> AboutScreen(vm)
        FilesView.Merges -> MergesScreen(vm)
        FilesView.ImageSources -> ImageSourcesScreen(vm)
    }
}

@Composable
private fun SectionLabel(text: String) {
    val c = LocalOrganic.current
    Text(text, style = body(14f, FontWeight.Bold, c.n700))
}

@Composable
private fun SubPageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BackButton(onBack)
        Text(title, style = heading(24))
    }
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
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OIcon(icon, 19.dp, iconTint)
        Column(Modifier.weight(1f)) {
            Text(title, style = body(15f, FontWeight.SemiBold, c.text))
            if (sub != null) {
                Text(sub, style = body(12.5f, FontWeight.Normal, c.n600), modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailingText != null) {
            Text(trailingText, style = body(12.5f, FontWeight.Normal, c.n600), maxLines = 1)
        }
        OIcon(Lucide.ChevronRight, 18.dp, c.n500)
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}

// ── settings root (v2.0: 文件管理 card ← 已忽略/重新扫描, 通用 cleaned) ──────
@Composable
private fun SettingsRoot(vm: MainViewModel) {
    val c = LocalOrganic.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v1.1+: 进入设置页时统计识别缓存占用
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshRecognitionCacheSize() }
    // v1.2.0 阶段三：备份/恢复的提示文本（导出成功/导入统计/失败）
    var backupMsg by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = vm.exportBackup()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                backupMsg = "已备份全部修正数据"
            } catch (e: Exception) { backupMsg = "备份失败：${e.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("无法读取备份文件")
                val stats = vm.importBackup(json)
                backupMsg = "恢复完成：$stats"
            } catch (e: Exception) { backupMsg = "恢复失败：${e.message}" }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton { vm.settingsOpen = false; vm.sel = emptyMap() }
            Text("设置", style = heading(24))
        }

        SectionLabel("文件管理")
        Column(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            CardEntryRow(
                Lucide.Brush, c.a700, "清理建议",
                "${vm.cleanCount()} 个可清理 · ${formatSizeMb(vm.cleanSizeBytes())}",
                divider = true,
            ) { vm.openClean() }
            CardEntryRow(
                Lucide.Trash, c.n700, "回收站",
                "${vm.trashMirror.size} 个文件",
                divider = true,
            ) { vm.filesView = FilesView.Trash }
            CardEntryRow(
                Lucide.Folder, c.n700, "音频文件夹",
                "${vm.foldersMap().size} 个文件夹 · 忽略或删除整个文件夹",
                divider = true,
            ) { vm.filesView = FilesView.Folders }
            // v2.0: 已忽略 → card entry inside 文件管理 (no longer standalone)
            CardEntryRow(
                Lucide.EyeOff, c.n700, "已忽略的文件夹",
                if (vm.ignored.isEmpty()) "暂无" else "${vm.ignored.size} 个文件夹",
                divider = true,
            ) { vm.filesView = FilesView.Ignored }
            // v2.0: 重新扫描 moved from 通用 into 文件管理
            CardEntryRow(
                Lucide.RotateCcw, c.n700, "重新扫描音频",
                "再次扫描全部文件夹",
                divider = true,
            ) { vm.rescanWithAnimation() }
            CardEntryRow(
                Lucide.Users, c.n700, "歌手合并",
                if (vm.aliases.isEmpty()) "暂无合并" else "${vm.aliases.size} 条记录",
                divider = false,
            ) { vm.filesView = FilesView.Merges }
        }

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
            SettingRow(Lucide.Infinity, "无缝播放", "专辑连播时曲目间不留空隙", divider = true) {
                OrganicSwitch(vm.gapless) { vm.setGapless(!vm.gapless) }
            }
            SettingRow(Lucide.Mic, "自动匹配歌词", "无歌词时自动在线获取，确认后保存到本地", divider = true) {
                OrganicSwitch(vm.autoMatch) { vm.setAutoMatch(!vm.autoMatch) }
            }
            // v1.1+: 自动保存识别结果（默认开）+ 缓存占用 + 清理按钮
            SettingRow(Lucide.Download, "自动保存识别结果", "联网识别成功的歌词/封面默认持久化，关闭则仅临时展示", divider = true) {
                OrganicSwitch(vm.autoSaveRecognition) { vm.setAutoSaveRecognition(!vm.autoSaveRecognition) }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OIcon(Lucide.Folder, 19.dp, c.n700)
                Column(Modifier.weight(1f)) {
                    Text("识别缓存", style = body(15f, FontWeight.SemiBold, c.text))
                    Text(
                        "已缓存歌词/封面 ${formatCacheSize(vm.recognitionCacheBytes)}（不含人工修正）",
                        style = body(11.5f, FontWeight.Normal, c.n600),
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.n100)
                        .clickable { vm.clearRecognitionCache() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Text("清理缓存", style = body(12.5f, FontWeight.Bold, c.text)) }
            }
            // v1.2.0 阶段三：修正数据备份/恢复。导出含专辑/曲目/歌手/振假名等
            // 全部人工修正，导入覆盖同主键。换机/重装前备份，避免重新扫描后丢失修正。
            Row(
                Modifier.fillMaxWidth().padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OIcon(Lucide.Shield, 19.dp, c.n700)
                Column(Modifier.weight(1f)) {
                    Text("备份我的修正数据", style = body(15f, FontWeight.SemiBold, c.text))
                    Text(
                        "专辑名·艺术家·封面·隐藏·迁专辑·歌手合并·振假名注音",
                        style = body(11.5f, FontWeight.Normal, c.n600),
                    )
                    if (backupMsg != null) {
                        Text(backupMsg!!, style = body(11f, FontWeight.Normal, c.a700))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.n100)
                            .clickable {
                                backupMsg = null
                                exportLauncher.launch("pickupmusic-backup.json")
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) { Text("备份", style = body(12.5f, FontWeight.Bold, c.text)) }
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.n100)
                            .clickable {
                                backupMsg = null
                                importLauncher.launch(arrayOf("application/json"))
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) { Text("恢复", style = body(12.5f, FontWeight.Bold, c.text)) }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OIcon(Lucide.Clock, 19.dp, c.n700)
                Text("睡眠定时", style = body(15f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.n100)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        vm.player.sleepStatusText(),
                        style = body(12.5f, FontWeight.Normal, c.n800),
                    )
                }
            }
        }

        // v1.2.1: 写真源配置(API key + 源开关)入口
        Row(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .clickable { vm.filesView = FilesView.ImageSources }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OIcon(Lucide.User, 19.dp, c.n700)
            Text("写真源", style = body(15f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
            Text("配置 · ${vm.disabledImageSources.size} 关", style = body(12.5f, FontWeight.Normal, c.n600))
            OIcon(Lucide.ChevronRight, 18.dp, c.n500)
        }

        // v1.5: about entry
        Row(
            Modifier
                .fillMaxWidth()
                .shadowSm(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .clickable { vm.filesView = FilesView.About }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OIcon(Lucide.CircleInfo, 19.dp, c.n700)
            Text("关于我们", style = body(15f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
            Text("拾音 v1.3.0", style = body(12.5f, FontWeight.Normal, c.n600))
            OIcon(Lucide.ChevronRight, 18.dp, c.n500)
        }

        Box(Modifier.height(114.dp))
    }
}

/** v1.1+: 格式化识别缓存占用。null=未统计 → "—"。 */
private fun formatCacheSize(bytes: Long?): String {
    val b = bytes ?: return "—"
    if (b < 1024) return "${b}B"
    if (b < 1024 * 1024) return "%.1f KB".format(b / 1024.0)
    return "%.1f MB".format(b / (1024.0 * 1024.0))
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OIcon(icon, 19.dp, c.n700)
        Column(Modifier.weight(1f)) {
            Text(title, style = body(15f, FontWeight.SemiBold, c.text))
            if (sub != null) {
                Text(sub, style = body(12.5f, FontWeight.Normal, c.n600), modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing()
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}

// ── v1.4 audio folders subpage ─────────────────────────────────────────────
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
        SubPageHeader("音频文件夹") { vm.filesView = FilesView.Root }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((path, ts) in vm.foldersMap()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .shadowSm(RoundedCornerShape(28.dp))
                        .clip(RoundedCornerShape(28.dp))
                        .background(c.surface)
                        .clickable { vm.folderKey = path; vm.filesView = FilesView.FolderContent }
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
        Text(
            "忽略的文件夹不再出现在音乐库，可随时恢复；删除的文件先进入回收站。",
            style = body(12.5f, FontWeight.Normal, c.n500).copy(lineHeight = 18.sp),
        )
        Box(Modifier.height(114.dp))
    }
}

// ── v2.0: ignored folders subpage (replaces the old standalone section) ─────
@Composable
private fun IgnoredFoldersScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SubPageHeader("已忽略的文件夹") { vm.filesView = FilesView.Root }
        if (vm.ignored.isEmpty()) {
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
                ) { OIcon(Lucide.EyeOff, 30.dp, c.n500) }
                Text(
                    "暂无忽略的文件夹。\n在「音频文件夹」页点击眼睛图标可忽略它。",
                    style = body(14f, FontWeight.Normal, c.n600).copy(lineHeight = 22.sp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        }
        Text(
            "恢复后文件夹里的音频会重新出现在音乐库中。",
            style = body(12.5f, FontWeight.Normal, c.n500).copy(lineHeight = 18.sp),
        )
        Box(Modifier.height(114.dp))
    }
}

// ── v2.0: folder content browsing (songs inside a folder) ──────────────
@Composable
private fun FolderContentScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    val folder = vm.folderKey
    if (folder == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.filesView = FilesView.Folders }
        return
    }
    val tracks = vm.foldersMap()[folder].orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SubPageHeader(folder) { vm.folderKey = null; vm.filesView = FilesView.Folders }
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
        SubPageHeader("播放设备") { vm.filesView = FilesView.Root }
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
        Text(
            "v5.2：点击设备即可切换至该输出。已选蓝牙设备的地址会被记下，下次该设备重连时自动切回。",
            style = body(12.5f, FontWeight.Normal, c.n500).copy(lineHeight = 18.sp),
        )
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
        SubPageHeader("歌手合并") { vm.filesView = FilesView.Root }
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
        Text(
            "合并只影响显示归属，不改动文件；撤销后立即还原。",
            style = body(12.5f, FontWeight.Normal, c.n500).copy(lineHeight = 18.sp),
        )
        Box(Modifier.height(114.dp))
    }
}

// ── v1.5/v1.8 about subpage ────────────────────────────────────────────────
@Composable
private fun AboutScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    var supportSheetOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        SubPageHeader("关于我们") { vm.filesView = FilesView.Root }
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
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.n100)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) { Text("v1.3.0", style = body(12f, FontWeight.Normal, c.n800)) }
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OIcon(Lucide.Shield, 14.dp, c.s700)
                Text("完全本地运行 · 不上传任何数据", style = body(12.5f, FontWeight.Bold, c.s700))
            }
            // v3.3: 每次更新附上更新内容清单，方便用户对照功能变化
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.n100)
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
                .background(c.n100)
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
    }
}

// ── clean suggestions ──────────────────────────────────────────────────────
@Composable
private fun CleanScreen(vm: MainViewModel) {
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
            SubPageHeader("清理建议") { vm.filesView = FilesView.Root; vm.sel = emptyMap() }
        }
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
@Composable
private fun TrashScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    val rows = vm.trashMirror
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SubPageHeader("回收站") { vm.filesView = FilesView.Root }
        }
        if (rows.isEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(c.n200),
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.Trash, 30.dp, c.n500) }
                    Text("回收站是空的", style = body(14f, FontWeight.Normal, c.n600))
                }
            }
        } else {
            items(rows, key = { it.mediaId }) { e ->
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
                    "彻底清空回收站（释放 ${formatSizeMb(rows.sumOf { it.sizeBytes })}）",
                    onClick = { vm.requestEmptyTrash() },
                    modifier = Modifier.fillMaxWidth(),
                    bg = null, textColor = c.a700, fontSize = 14f, padV = 13.dp,
                )
            }
        }
    }
}

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
        SubPageHeader("写真源") { vm.filesView = FilesView.Root }

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
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(labels[key] ?: key, style = body(14f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = enabled,
                        onCheckedChange = { vm.setImageSourceEnabled(key, it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.accent),
                    )
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
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, style = body(14f, FontWeight.SemiBold, c.text))
                        if (savedKey.isNotBlank()) {
                            Text("已配置", style = body(10f, FontWeight.Bold, c.s700))
                        }
                    }
                    PillButton(
                        "修改键", onClick = { keyDialogFor = key },
                        bg = null, textColor = c.accent, borderColor = c.divider,
                        fontSize = 12f, padH = 12.dp, padV = 6.dp,
                    )
                    androidx.compose.material3.Switch(
                        checked = enabled,
                        onCheckedChange = { vm.setImageSourceEnabled(key, it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.accent),
                    )
                }
            }

            // ② 用户自建的 URL 源
            for (def in vm.customImageSources) {
                val key = "custom-${def.id}"
                val enabled = key !in vm.disabledImageSources
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(def.name, style = body(14f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
                    Box(Modifier.size(30.dp).clip(CircleShape).clickable { vm.removeCustomImageSource(def.id) }, contentAlignment = Alignment.Center) {
                        OIcon(Lucide.Trash, 15.dp, c.n500)
                    }
                    androidx.compose.material3.Switch(
                        checked = enabled,
                        onCheckedChange = { vm.setImageSourceEnabled(key, it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = c.accent),
                        modifier = Modifier.padding(start = 6.dp),
                    )
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
            if (dirty) {
                androidx.compose.material3.TextButton(onClick = { onSave(input.trim()) }) {
                    Text("保存", style = body(14f, FontWeight.Bold, c.accent))
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(if (dirty) "取消" else "关闭", style = body(14f, FontWeight.Normal, c.n600))
            }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            androidx.compose.material3.TextButton(
                enabled = valid,
                onClick = { vm.addCustomImageSource(name, tpl, apiKey); onDismiss() },
            ) { Text("添加", style = body(14f, FontWeight.Bold, if (valid) c.accent else c.n500)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消", style = body(14f, FontWeight.Normal, c.n600))
            }
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
            androidx.compose.material3.TextButton(onClick = {
                vm.setDeepSeekKey(input.trim())
                onDismiss()
            }) {
                Text("保存", style = body(14f, FontWeight.Bold, c.accent))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消", style = body(14f, FontWeight.Normal, c.n600))
            }
        },
    )
}

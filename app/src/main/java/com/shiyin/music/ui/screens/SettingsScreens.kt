package com.shiyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.FilesView
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.Track
import com.shiyin.music.data.formatDuration
import com.shiyin.music.data.formatSizeMb
import com.shiyin.music.ui.components.EqBars
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.OrganicSwitch
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
            Text("拾音 v5.2", style = body(12.5f, FontWeight.Normal, c.n600))
            OIcon(Lucide.ChevronRight, 18.dp, c.n500)
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
            ) { Text("v5.2", style = body(12f, FontWeight.Normal, c.n800)) }
            // v3.3 build timestamp — 编译时写入，用于验证版本
            val buildTime = "2026-08-13"
            Text(
                "build $buildTime",
                style = body(10f, FontWeight.Normal, c.n400),
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "识别手机里的每一段声音，\n帮你留下想听的，清走不要的。",
                style = body(14f, FontWeight.Normal, c.n600).copy(lineHeight = 22.sp),
                textAlign = TextAlign.Center,
            )
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
                val changes = listOf(
                    "v5.2 隐藏歌曲：歌曲三点菜单新增「隐藏此曲」，隐藏后变灰、整张/整库/随机播放自动跳过，但单曲点击仍能正常播放。",
                    "v5.2 播放设备切换改用系统级媒体输出选择器：播放页点设备按钮直接弹出 Android 系统输出切换器（手机扬声器/蓝牙耳机/蓝牙音箱等），由系统完成真实音频路由，兼容 ColorOS 等定制系统；不再走应用内自建设备列表。",
                    "v5.2 播放页二级菜单修复：三点菜单、播放队列、歌词本之前打不开（被播放页弹层压住、歌词本渲染到播放页后面），现已正确盖在播放页之上、可正常打开。",
                    "v5.2 歌词本歌名/歌手名改为居中显示。",
                    "v4.3 搜索升级：模糊搜索支持繁简互通、空格灵活与错字容错（如 weekend 也能找到 weeknd），命中词在结果中高亮。",
                    "v4.3 播放页下拉关闭重做：像两张纸重叠的效果，下拉跟手丝滑，松手自然滑出。",
                    "v4.3 侧边栏改为并列布局（侧边栏｜首页），不再遮挡首页内容。",
                    "v4.3 单曲信息编辑新增「迁移专辑」：误识别成独立单曲的歌可以归入正确的专辑。",
                    "v4.3 专辑与单曲信息支持手动修改（名称/歌手/封面）。",
                    "v4.3 收听统计修复歌手次数显示换行问题。",
                    "v4.3 歌手页专辑栏恢复横向滑动，不再被截断。",
                    "v4.3 播放专辑自动整张循环：播完最后一首自动回到第一首。",
                    "v4.3 播放设备弹层修复蓝牙音箱不显示：列表实时刷新，连接后立刻出现；首次使用会请求蓝牙权限。",
                    "v3.3 歌词本自适应反色：浅色专辑保持原色背景配深色文字，暗色专辑配白字，封面色调不再被强制压暗。",
                    "v3.3 桌面图标缩小到安全区内，不再被启动器圆角裁掉。",
                    "v3.2 歌词背景改取专辑封面主体色，不再偏向角落暗色。",
                    "v3.1 歌词本动态取色重做 + 播放进度条下时间数字紧贴进度条两端。",
                    "v3.0 播放页/歌词页跟随专辑封面取色，设备选择器改为上滑面板。",
                )
                for (ch in changes) {
                    Text("· $ch", style = body(12f, FontWeight.Normal, c.n700).copy(lineHeight = 17.sp))
                }
            }
        }
        Box(Modifier.height(114.dp))
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

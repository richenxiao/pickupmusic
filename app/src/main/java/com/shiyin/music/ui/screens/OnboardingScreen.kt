package com.shiyin.music.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.shiyin.music.MainViewModel
import com.shiyin.music.ObStage
import com.shiyin.music.ui.components.CircleButton
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.PillButton
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.shadowMd
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    AnimatedContent(
        targetState = vm.obStage,
        transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) },
        label = "ob",
    ) { stage ->
        when (stage) {
            ObStage.Perm -> PermStep(vm)
            ObStage.Scan -> ScanStep(vm)
            ObStage.Done -> DoneStep(vm)
        }
    }
}

@Composable
fun PermStep(vm: MainViewModel) {
    val c = LocalOrganic.current
    val context = LocalContext.current
    var denied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val audioGranted = result[Manifest.permission.READ_MEDIA_AUDIO] == true ||
            result[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        if (audioGranted) vm.onPermissionGranted() else denied = true
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier
                .size(96.dp)
                .shadowMd(CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // 与 launcher 同款图标(pickupmusic.png),与新图标统一,不再用旧 brand mark。
            Image(
                painter = painterResource(com.shiyin.music.R.drawable.pickupmusic),
                contentDescription = "拾音",
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("拾音", style = heading(40))
            Text(
                "识别手机里的每一段声音，帮你留下想听的，清走不要的。",
                style = body(16f, FontWeight.Normal, c.n600).copy(lineHeight = 25.sp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(c.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PermRow(
                iconBg = c.s200, iconTint = c.s800, icon = Lucide.Folder,
                title = "访问设备音频", sub = "扫描本机音乐、录音与下载文件夹，仅在本地读取",
            )
            PermRow(
                iconBg = c.a200, iconTint = c.a800, icon = Lucide.Trash,
                title = "管理不需要的文件", sub = "删除、忽略文件夹，删除的文件先进回收站",
            )
        }
        PillButton(
            "允许访问并开始扫描",
            onClick = {
                val perms = buildList {
                    if (Build.VERSION.SDK_INT >= 33) {
                        add(Manifest.permission.READ_MEDIA_AUDIO)
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    // v4.3: minSdk 31 — BLUETOOTH_CONNECT 从 Android 12 起就是
                    // 运行时权限，不请求就读不到蓝牙音箱的设备名。
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                else Manifest.permission.READ_EXTERNAL_STORAGE
                when {
                    ContextCompat.checkSelfPermission(context, audioPerm) == PackageManager.PERMISSION_GRANTED ->
                        vm.onPermissionGranted()
                    denied -> {
                        // A second refusal usually means "don't ask again":
                        // hand off to the system App-Info page.
                        try {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null),
                                )
                            )
                        } catch (_: Exception) {
                        }
                    }
                    else -> launcher.launch(perms.toTypedArray())
                }
            },
            modifier = Modifier.fillMaxWidth(),
            fontSize = 16f, padV = 15.dp,
        )
        if (denied) {
            Text(
                "未获授权将无法扫描本机音频。再次点击按钮可前往系统设置，授予「音乐和音频」权限。",
                style = body(12.5f, FontWeight.Normal, c.a700).copy(lineHeight = 18.sp),
            )
        }
    }
}

@Composable
private fun PermRow(iconBg: Color, iconTint: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) {
    val c = LocalOrganic.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) { OIcon(icon, 18.dp, iconTint) }
        Column(Modifier.weight(1f)) {
            Text(title, style = body(14.5f, FontWeight.SemiBold, c.text))
            Text(sub, style = body(13f, FontWeight.Normal, c.n600).copy(lineHeight = 19.sp))
        }
    }
}

@Composable
private fun ScanStep(vm: MainViewModel) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 26.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val t = rememberInfiniteTransition(label = "spin")
        val angle by t.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
            label = "angle",
        )
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val strokeW = 6.dp.toPx()
                drawCircle(color = c.n200, style = Stroke(strokeW), radius = (size.minDimension - strokeW) / 2)
                drawArc(
                    color = c.accent,
                    startAngle = angle - 90f,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    topLeft = Offset(strokeW / 2, strokeW / 2),
                    size = Size(size.width - strokeW, size.height - strokeW),
                )
            }
            Text("${vm.scanCount}", style = heading(44))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("正在扫描音频文件…", style = body(17f, FontWeight.SemiBold, c.text))
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.n100)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(vm.scanFolder.ifEmpty { "…" }, style = body(13f, FontWeight.Normal, c.n800))
            }
        }
    }
}

@Composable
private fun DoneStep(vm: MainViewModel) {
    val c = LocalOrganic.current
    val t = rememberInfiniteTransition(label = "pulse")
    val scale by t.animateFloat(
        1f, 1.06f,
        infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "scale",
    )
    val folders = vm.scanResultFolders
    val total = folders.sumOf { it.second }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier
                .size(84.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(c.s300),
            contentAlignment = Alignment.Center,
        ) { OIcon(Lucide.Check, 38.dp, c.s900) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("扫描完成", style = heading(32))
            Text(
                buildAnnotatedString {
                    append("共找到 ")
                    withStyle(SpanStyle(color = c.a700, fontWeight = FontWeight.Bold)) { append("$total") }
                    append(" 个音频文件，来自 ${folders.size} 个文件夹")
                },
                style = body(15f, FontWeight.Normal, c.n600),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((path, count) in folders) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.surface)
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OIcon(Lucide.Folder, 18.dp, c.n600)
                    Text(path, style = body(14.5f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(c.s100)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text("$count 个", style = body(12.5f, FontWeight.Normal, c.s800))
                    }
                }
            }
        }
        if (vm.scanDupGroups > 0 || vm.scanShortCount > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.a100)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OIcon(Lucide.Info, 17.dp, c.a700)
                Text(
                    "发现 ${vm.scanDupGroups} 组重复文件和 ${vm.scanShortCount} 段超短音频，可在「设置 · 文件管理」一键清理",
                    style = body(13.5f, FontWeight.Normal, c.a800).copy(lineHeight = 19.sp),
                )
            }
        }
        PillButton(
            "进入音乐库",
            onClick = { vm.finishScan() },
            modifier = Modifier.fillMaxWidth(),
            fontSize = 16f, padV = 15.dp,
        )
        Spacer(Modifier.height(4.dp))
    }
}

package com.shiyin.music.spike.karaoke

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.clickableNoRipple
import com.shiyin.music.ui.components.shadowSm
import com.shiyin.music.ui.theme.LocalOrganic
import kotlinx.coroutines.launch

/**
 * P0 Spike 调试卡片（规格 §12/P0 任务书：隐藏调试入口）。
 * spike 分支期间常显在 设置→歌词本 底部（release 包也可见——用户手机装的是 release
 * 签名包，debug 包装上不去；分支合并前移除或改回仅 debug 可见）。
 */
@Composable
fun KaraokeP0ProbeCard() {
    val c = LocalOrganic.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .shadowSm(RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(c.surface)
            .padding(16.dp),
    ) {
        Text(
            if (running) "正在运行（9482 单块推理）…" else "运行 9482 单块推理探针",
            style = body(14f, color = c.text),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(c.n100)
                .clickableNoRipple(enabled = !running) {
                    running = true
                    report = null
                    scope.launch {
                        report = KaraokeP0Probe.runChunk(context)
                        running = false
                    }
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        report?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = body(11f, color = c.n600).copy(fontFamily = FontFamily.Monospace))
        }
    }
}

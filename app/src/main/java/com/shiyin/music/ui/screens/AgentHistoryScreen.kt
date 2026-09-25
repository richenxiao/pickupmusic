package com.shiyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shiyin.music.MainViewModel
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic

/**
 * v1.3.8 Agent 对话历史页(Agent 页右上「历史」入口)。
 *
 * ⚠️ 这是**最简壳**:对话记录目前只活在内存里(MainViewModel.agentMessages 是单条
 * 会话、进程结束即失),所以这里列出的是"当前这次会话里用户说过的每一轮"。
 * 真正的多会话持久化(存库、按时间倒序列出、点进去复看)是另一个独立任务——
 * 本轮只做到"入口按钮点得进来、页面结构立得住",不假装历史已经做好了。
 *
 * 与 AgentSettingsDialog 同构:全屏 Dialog,自绘顶栏,不动 AppRoot 的路由。
 */
@Composable
internal fun AgentHistoryDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = LocalOrganic.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            // 顶栏:与 Agent 页/设置页同一套(38dp 圆形返回键 + 标题)
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(c.surface).clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.ChevronLeft, 20.dp, c.text) }
                Text("对话历史", style = heading(20), modifier = Modifier.weight(1f))
            }

            // 只列用户轮次(Agent 的回复是结果,不是"问过的历史")。
            val turns = vm.agentMessages.filter { it.role == "user" }
            if (turns.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有历史对话", style = body(13f, FontWeight.Normal, c.n500))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(turns) { i, msg ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.surface)
                                // 点一条 = 回到对话页看那一轮(本轮不做定位跳转,先保证不迷路)。
                                .clickable { onDismiss() }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                msg.text,
                                style = body(13.5f, FontWeight.Normal, c.text),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("第 ${i + 1} 轮", style = body(10.5f, FontWeight.Normal, c.n500))
                        }
                    }
                }
            }
        }
    }
}

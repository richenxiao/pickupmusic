package com.shiyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * v1.3.9 Agent 对话历史页(Agent 页右上「历史」入口)。
 *
 * 历史按「对话」组织(与 ChatGPT/Claude 一致)——一行 = 一次对话,收藏置顶、
 * 最近更新在前。每条支持:打开(消息装回对话页 + 最后一条用户消息进输入框,
 * 发送即覆盖重发)、重命名、收藏/取消收藏、删除(二次确认)。
 *
 * 与 AgentSettingsDialog 同构:全屏 Dialog,自绘顶栏,不动 AppRoot 的路由。
 */
@Composable
internal fun AgentHistoryDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = LocalOrganic.current
    // 打开时刷新列表
    LaunchedEffect(Unit) { vm.loadAgentConversations() }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }

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

            val list = vm.agentConversations
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有历史对话", style = body(13f, FontWeight.Normal, c.n500))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.id }) { conv ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.surface)
                                // 点开 = 装回对话页继续(覆盖重发语义见 VM.openAgentConversation)
                                .clickable { vm.openAgentConversation(conv.id) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    conv.title,
                                    style = body(13.5f, FontWeight.Bold, c.text),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
                                        .format(java.util.Date(conv.updatedAt)),
                                    style = body(10.5f, FontWeight.Normal, c.n500),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            // 收藏
                            Box(
                                Modifier.size(32.dp).clip(CircleShape)
                                    .clickable { vm.toggleAgentConversationPinned(conv.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                OIcon(
                                    if (conv.pinned == 1) Lucide.PinFilled else Lucide.Pin,
                                    15.dp,
                                    if (conv.pinned == 1) c.accent else c.n500,
                                )
                            }
                            // 重命名
                            Box(
                                Modifier.size(32.dp).clip(CircleShape)
                                    .clickable { renameFor = conv.id },
                                contentAlignment = Alignment.Center,
                            ) { OIcon(Lucide.Pencil, 14.dp, c.n500) }
                            // 删除(二次确认)
                            Box(
                                Modifier.size(32.dp).clip(CircleShape)
                                    .clickable { deleteFor = conv.id },
                                contentAlignment = Alignment.Center,
                            ) { OIcon(Lucide.Trash, 14.dp, c.n500) }
                        }
                    }
                }
            }
        }
    }

    // 重命名对话框
    renameFor?.let { id ->
        val conv = vm.agentConversations.firstOrNull { it.id == id }
        var name by remember(id) { mutableStateOf(conv?.title ?: "") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renameFor = null },
            containerColor = c.surface,
            title = { Text("重命名对话", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
                        focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
                    ),
                )
            },
            confirmButton = {
                com.shiyin.music.ui.components.DialogActionsRow(
                    listOf(
                        com.shiyin.music.ui.components.DialogAction("取消") { renameFor = null },
                        com.shiyin.music.ui.components.DialogAction("保存", emphasized = true) {
                            vm.renameAgentConversation(id, name)
                            renameFor = null
                        },
                    )
                )
            },
        )
    }

    // 删除确认(对话删除不可撤销)
    deleteFor?.let { id ->
        val conv = vm.agentConversations.firstOrNull { it.id == id }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteFor = null },
            containerColor = c.surface,
            title = { Text("删除「${conv?.title ?: "该对话"}」？", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = { Text("对话内容会一并删除，不可撤销。", style = body(12.5f, FontWeight.Normal, c.n600)) },
            confirmButton = {
                com.shiyin.music.ui.components.DialogActionsRow(
                    listOf(
                        com.shiyin.music.ui.components.DialogAction("取消") { deleteFor = null },
                        com.shiyin.music.ui.components.DialogAction("删除", emphasized = true) {
                            vm.deleteAgentConversation(id)
                            deleteFor = null
                        },
                    )
                )
            },
        )
    }
}

package com.shiyin.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.ChartCurves
import com.shiyin.music.data.ai.LlmClient
import com.shiyin.music.data.ai.LlmProviderConfig
import com.shiyin.music.data.ai.blendCurves
import com.shiyin.music.data.ai.resampleTo
import com.shiyin.music.ui.components.AgentLogoThinking
import com.shiyin.music.ui.components.EqBars
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.OrganicSwitch
import com.shiyin.music.ui.components.PasswordField
import com.shiyin.music.ui.components.clickableNoRipple
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.shadowMd
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * v1.3.10: 模型「选择题」卡片(参照 Claude 的选择弹窗,移动端适配)。
 *
 * 结构(自上而下):标题行(问题左对齐加粗 + 右侧关闭) / 选项行(圆角方块数字徽章 + 文字,
 * 行间细分隔线,按下时 accent 描边 + 略深底色) / 「其他」出口行(铅笔 + 占位文字 + 跳过)。
 *
 * 刻意的取舍:
 * - **单问题单组**,不做分页(用户 2026-09-28 定调;schema 也只支持一个 question)。
 * - 「其他」出口**始终存在**——模型给的选项再周全也可能没覆盖用户的真实想法,
 *   只有封闭选项会把用户卡死。
 * - 移动端没有键盘 ↑↓ 导航,所以"高亮"落在**按下态**(手指按住哪行哪行亮),
 *   不引入桌面端的焦点模型。
 * - 强调色一律取主题 accent,不引入新的蓝。
 */
@Composable
private fun AgentClarifyCard(
    question: String,
    options: List<String>,
    notes: List<String> = emptyList(),
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onOther: () -> Unit,
) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .shadowMd(RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .padding(vertical = 6.dp),
    ) {
        // 标题行:问题 + 关闭(× = 跳过这次选择)
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(question, style = body(14f, FontWeight.Bold, c.text), modifier = Modifier.weight(1f))
            Box(
                Modifier.size(30.dp).clip(CircleShape).clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.Close, 16.dp, c.n600) }
        }
        // 选项:整行可点;按下态 = accent 描边 + 略深底色 + 徽章反色
        options.forEachIndexed { i, opt ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(c.divider))
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (pressed) c.a100 else androidx.compose.ui.graphics.Color.Transparent)
                    .border(
                        1.5.dp,
                        if (pressed) c.accent else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable(interactionSource = interaction, indication = null) { onPick(opt) }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (pressed) c.accent else c.n200),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${i + 1}",
                        style = body(
                            11.5f,
                            FontWeight.Bold,
                            if (pressed) androidx.compose.ui.graphics.Color.White else c.n700,
                        ),
                    )
                }
                // v1.3.15:主文本 + 副文案(推荐卡里是"为什么挑这首";没有理由时给专辑名)。
                // 点选的仍是 [opt];副文案只是给人看的,不参与发送,所以放在第二行而不是拼进
                // 同一个字符串 —— 否则点选发出的消息会带上理由,变成一句没法执行的怪话。
                Column(Modifier.weight(1f)) {
                    Text(opt, style = body(14f, FontWeight.Normal, c.text))
                    notes.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(
                            note,
                            style = body(11.5f, FontWeight.Normal, c.n500),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 「其他」出口:点它把焦点交给真正的输入框(卡片留着,问题还能看见)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.n100)
                .clickableNoRipple { onOther() }
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            OIcon(Lucide.Pencil, 15.dp, c.n600)
            Text(
                "其他/我自己说",
                style = body(13.5f, FontWeight.Normal, c.n500),
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.n200)
                    .clickableNoRipple { onDismiss() }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) { Text("跳过", style = body(12f, FontWeight.Bold, c.n700)) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** v1.3.9: THINKING/TOOL 阶段的实时耗时（"思考中 · 3s"）。0 秒算启动中,显示 0.0。 */
@Composable
private fun rememberElapsedSeconds(active: Boolean): Long {
    var startMs by remember { mutableStateOf(0L) }
    var elapsedSec by remember { mutableStateOf(0L) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        startMs = System.currentTimeMillis()
        while (true) {
            elapsedSec = (System.currentTimeMillis() - startMs) / 1000
            kotlinx.coroutines.delay(500)
        }
    }
    return elapsedSec
}

/**
 * v1.2.2 Agent: 独立对话页。
 *
 * 布局: 顶栏(返回/标题/右上设置齿轮) + 对话消息列表(用户气泡右侧/Agent 气泡左侧 +
 * Agent 消息下方内嵌独立步骤面板) + 底部输入框。
 *
 * 数据由 MainViewModel 持有(agentMessages/agentRunning/agentTotalTokens)。
 */
@Composable
fun AgentScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    // v1.3.3: 输入内容直接读写 VM 草稿(agentDraft)——执行中可继续码字,离开页面再回来不丢。
    val input = vm.agentDraft
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    // 打开页时刷新累计 tokens
    LaunchedEffect(Unit) { vm.refreshAgentTokens() }
    // 消息数变了 → 滚到底部
    LaunchedEffect(vm.agentMessages.size, vm.agentRunning) {
        if (vm.agentMessages.isNotEmpty()) listState.animateScrollToItem(vm.agentMessages.size - 1)
    }
    // v1.3.4: 流式期间持续贴底——思考链/打字机都是 replaceAt 更新(size 不变),上面那条
    // LaunchedEffect(size) 不会触发,长思考链把呼吸胶囊顶出可视区、用户看到页面一动不动
    // "卡住",回答出来才跳一下(用户反馈的"思考一分钟只闪一两秒"正是这个)。
    // 已经滚离底部(往上翻历史)时不强拽——查看历史优先于自动跟滚。
    val lastMsg = vm.agentMessages.lastOrNull()
    LaunchedEffect(lastMsg?.text, lastMsg?.thinking, vm.agentRunning) {
        if (!vm.agentRunning || lastMsg == null || lastMsg.role != "agent") return@LaunchedEffect
        val info = listState.layoutInfo
        val lastItem = info.visibleItemsInfo.lastOrNull()
        val atBottom = lastItem == null || lastItem.index >= vm.agentMessages.size - 2 ||
            (lastItem.offset + lastItem.size) >= info.viewportEndOffset - 200
        if (atBottom) listState.scrollToItem(vm.agentMessages.size - 1)
    }
    // v1.3.3: 键盘弹出时对话内容也要"顶上去"——列表高度被 imePadding 压缩后,
    // 最新消息会落在可视区外被键盘遮住;监听 IME inset 变化自动滚到底,内容保持可见。
    // 用 scrollToItem(瞬移)不用 animateScrollToItem——动画会让内容"追上去",
    // 视觉上变成"输入框先动、内容后动"两拍卡顿(用户反馈的正是这个)。
    val imeBottom = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(imeBottom, vm.agentMessages.size) {
        if (imeBottom > 0 && vm.agentMessages.isNotEmpty()) {
            listState.scrollToItem(vm.agentMessages.size - 1)
        }
    }

    // v1.3.4 修"键盘弹出后列表与输入框之间隔一大块空白":外层 Column 的 imePadding
    // 已经把整页(列表+输入框)一体上移了,列表 contentPadding 又绑了一次 IME 高度
    // ——双重 padding = 消息与输入框之间凭空多出一整块键盘高度的空白。contentPadding
    // 恒定小间距即可,同步移动全部交给外层那一个 imePadding。
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        // 顶栏
        Row(
            // v1.3.12(用户反馈"各页返回键位置乱、与板块不对齐"):拾光 Agent 所有层级
            // 统一用 **20dp** 左内边距(与全站设置子页一致),页头与内容同一条左基线。
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(c.surface).clickable {
                vm.persistAgentConversation()
                vm.agentOpen = false
            }, contentAlignment = Alignment.Center) {
                OIcon(Lucide.ChevronLeft, 20.dp, c.text)
            }
            Text("拾光Agent", style = heading(22), modifier = Modifier.weight(1f))
            // v1.3.9: 新对话 —— 当前对话先落库再清屏(与主流产品的 New Chat 同构)。
            Box(Modifier.size(38.dp).clip(CircleShape).background(c.surface).clickable(enabled = !vm.agentRunning) { vm.newAgentConversation() }, contentAlignment = Alignment.Center) {
                OIcon(Lucide.Plus, 18.dp, c.text)
            }
            // v1.3.8: 历史入口 —— 与设置键同尺寸/同底色/同圆角/同间距,只是多一颗。
            Box(Modifier.size(38.dp).clip(CircleShape).background(c.surface).clickable { vm.agentHistoryOpen = true }, contentAlignment = Alignment.Center) {
                OIcon(Lucide.History, 18.dp, c.text)
            }
            Box(Modifier.size(38.dp).clip(CircleShape).background(c.surface).clickable { vm.openAgentSettings() }, contentAlignment = Alignment.Center) {
                OIcon(Lucide.Settings, 18.dp, c.text)
            }
        }

        // 对话列表
        // v1.3.4: contentPadding 不再叠加 IME(见上方注释——一体上移由外层 imePadding
        // 承担,这里恒定小间距,消除"列表与输入框之间一大块空白")。
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(vm.agentMessages) { idx, msg ->
                AgentBubble(msg, vm, isLast = idx == vm.agentMessages.lastIndex, index = idx)
            }
            if (vm.agentMessages.isEmpty()) {
                item {
                    // v1.3.13:本会话的欢迎语序号——首次进空态随机取一次,之后固定
                    // (新鲜感跨会话,不在一次会话里跳来跳去)。
                    val greetingIdx = remember {
                        if (vm.agentGreetingIndex < 0) {
                            vm.agentGreetingIndex = kotlin.random.Random.nextInt(AGENT_GREETINGS.size)
                        }
                        vm.agentGreetingIndex
                    }
                    // v1.3.8 欢迎空态重做(用户规格):纵向三段式,不再把 Logo 和文字
                    // 横向拼在一起。
                    //  - 品牌标独占视觉主体(88dp,与 agent_brand.xml 自带建议尺寸一致);
                    //  - 下方 20dp 一句话,不重复"拾光Agent"品牌名、不加说明/宣传语;
                    //  - 上下配重 1 : 1.3 —— 整块落在可用区约 43% 处,即"中间略偏上",
                    //    与底部输入区形成稳定的上下层次。
                    Column(
                        Modifier.fillParentMaxHeight().fillMaxWidth().padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.weight(1f))
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(agentBrandRes(c.isDark)),
                            contentDescription = null,
                            // v1.3.9: 40→56dp。参考 Claude 欢迎页层级:Logo 是点睛,
                            // 视觉主体让位给大字号问候语。
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                        // v1.3.13:问候语改为打字机呈现(用户规格)。
                        // 文案**显式分成 1-2 行**渲染,不再靠自动折行——断点由文案决定,
                        // 不随机型宽度乱跳;每行短到能独占一行,颜文字只落在行尾且整组不拆。
                        // 会话内固定同一句、每会话只播一次(状态在 VM)。
                        TypewriterGreeting(
                            lines = AGENT_GREETINGS[greetingIdx % AGENT_GREETINGS.size],
                            played = vm.agentGreetingPlayed,
                            onPlayed = { vm.agentGreetingPlayed = true },
                        )
                        Spacer(Modifier.weight(1.3f))
                    }
                }
            }
        }

        // v1.3.2: 默认回复建议(从专辑页 Agent 入口进来时给出,点一条即发送)
        if (vm.agentSuggestions.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).imePadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (s in vm.agentSuggestions) {
                    // v1.3.3: 建议按钮用主题橙色——点它会以"用户"身份发出指令,
                    // 视觉上应与用户消息同源,不是 agent 的米白。
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(c.a200)
                            .clickable(enabled = !vm.agentRunning) {
                                vm.sendAgentMessage(s)
                                vm.clearAgentSuggestions()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(s, style = body(12.5f, FontWeight.Bold, c.a800))
                    }
                }
            }
        }

        // v1.3.10: 选择题卡片「其他」出口要能把焦点交回真正的输入框
        val clarifyFocus = remember { FocusRequester() }
        // v1.3.10: 模型发起的「选择题」卡片——悬浮在输入框正上方、紧贴输入框,不占对话气泡区。
        //
        // 为什么需要 [lastClarify] 缓存:选项点选/跳过会把 vm.agentClarify 立刻置空,
        // 若直接渲染 state,退出动画期间内容已没了 → 卡片"瞬间消失"而不是滑出。
        var lastClarify by remember {
            mutableStateOf<com.shiyin.music.data.ai.AgentTools.ClarifyRequest?>(null)
        }
        LaunchedEffect(vm.agentClarify) { if (vm.agentClarify != null) lastClarify = vm.agentClarify }
        AnimatedVisibility(
            visible = vm.agentClarify != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            lastClarify?.let { cq ->
                AgentClarifyCard(
                    question = cq.question,
                    options = cq.options,
                    notes = cq.optionNotes,
                    onPick = { vm.chooseAgentClarify(it) },
                    onDismiss = { vm.dismissAgentClarify() },
                    // 「其他」= 去真正的输入框打自己的答案:卡片留着(问题还看得见),
                    // 发送时 sendAgentMessage 会把它收起(= 选了「其他」,与卡片上的说明一致)。
                    onOther = { clarifyFocus.requestFocus(); keyboard?.show() },
                )
            }
        }

        // v1.3.5: 底部任务清单组件已撤(用户澄清:状态面板跟着对话消息走,不做固定
        // 清单)。输入区(v1.3.3: imePadding 已提到主 Column,这里不再重复)
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { vm.agentDraft = it },
                modifier = Modifier.weight(1f).focusRequester(clarifyFocus),
                // v1.3.3: 删掉执行中占位提示——不需要告诉用户"可打断",停止键本身就说明了。
                // v1.3.10: 选择题卡片弹出期间给出绕过提示(直接打字 = 选了「其他」)。
                placeholder = if (vm.agentClarify != null) {
                    { Text("或直接回复…", style = body(14f, FontWeight.Normal, c.n500)) }
                } else null,
                singleLine = false,
                maxLines = 3,
                // v1.3.3: 执行中也可继续码字(草稿暂存),只有发送被闸住。
                enabled = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (input.isNotBlank() && !vm.agentRunning) {
                        vm.sendAgentMessage(input); vm.agentDraft = ""; keyboard?.hide()
                    }
                }),
            )
            // v1.3.2: 发送键改图1风格——accent 圆角方块 + 白色向上箭头(原为圆形 + Send 纸飞机)
            // v1.3.3: 执行中变成停止键(■ 方块,可打断回复——对标 Claude/ChatGPT)。
            if (vm.agentRunning) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(c.accent)
                        .clickable { vm.stopAgent() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(13.dp).clip(RoundedCornerShape(2.dp)).background(androidx.compose.ui.graphics.Color.White))
                }
            } else {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (input.isBlank()) c.n400 else c.accent)
                        .clickable(enabled = input.isNotBlank()) {
                            vm.sendAgentMessage(input); vm.agentDraft = ""; keyboard?.hide()
                        },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.ArrowUp, 20.dp, androidx.compose.ui.graphics.Color.White) }
            }
        }
    }
    if (vm.agentSettingsOpen) AgentSettingsDialog(vm) { vm.agentSettingsOpen = false }
    // v1.3.8: 历史页做成与设置页同构的全屏 Dialog —— 改动全部留在 Agent 页内,
    // 不动 AppRoot 的路由。系统返回键由 Dialog 自己吃掉(dismissOnBackPress 默认 true)。
    if (vm.agentHistoryOpen) AgentHistoryDialog(vm) { vm.agentHistoryOpen = false }
    // v1.3.11:「拾光记忆」管理页 —— 由 Agent 设置页进入(同域,不分家)。
    // 声明在设置弹窗**之后** → 盖在它上面;页内返回只关这一层,退回设置页。
    // v1.3.14:「Agent 诊断样本」页 —— 由 Agent 设置页进入(同域,不分家)
    if (vm.agentDiagOpen) {
        Dialog(
            onDismissRequest = { vm.agentDiagOpen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
                AgentDiagScreen(vm) { vm.agentDiagOpen = false }
            }
        }
    }
    if (vm.agentMemoryScreenOpen) {
        Dialog(
            onDismissRequest = { vm.agentMemoryScreenOpen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            // §34:页面自己处理 Insets(状态栏/导航栏),**不硬编码顶部间距**。
            // 之前这里只有 background(c.bg),内容会顶到状态栏上(用户实测的 bug)。
            Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
                MemoryScreen(vm) { vm.agentMemoryScreenOpen = false }
            }
        }
    }
}

/**
 * v1.3.0 Agent 设置面板(右上齿轮触发)。全屏 Dialog。
 *
 * - 联网搜索:仅 Tavily API Key 输入框,不写多余提示。
 * - 大模型:供应商做成 chip 按钮组(预设 DeepSeek/OpenRouter/智谱/通义 + 已存自定义 +
 *   添加自定义)。点 chip 展开该供应商配置(API Key + 官网链接[仅自定义] + 模型下拉),
 *   模型下拉项由 GET {baseUrl}/models 运行时拉取(预设模型名易过时,如 deepseek-chat 已停用)。
 * - 使用统计:累计 token(prompt / completion)。
 *
 * 编辑态本地缓存,点「保存」才落库;激活切换即时生效。
 */
@Composable
private fun AgentSettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val c = LocalOrganic.current
    val tfColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
        focusedBorderColor = c.accent, unfocusedBorderColor = c.divider,
    )
    var providers by remember(vm.llmProviders) {
        mutableStateOf(vm.llmProviders.map { it.copy() })
    }
    var tavily by remember(vm.tavilyApiKey) { mutableStateOf(vm.tavilyApiKey) }
    // 当前展开配置的供应商 key(null=都不展开)。点 chip 切换。
    var expandedKey by remember { mutableStateOf<String?>(null) }
    // v1.3.5: 脏检查——tavily 或任一供应商配置与打开时不同才出现保存键(用户
    // 定调:平时右下角一直挂着"保存"很怪;只在编辑后出现)。激活切换是即时生效
    // 的独立动作,不算编辑。
    val dirty = tavily.trim() != vm.tavilyApiKey.trim() || providers != vm.llmProviders

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(c.surface).clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.ChevronLeft, 20.dp, c.text) }
                Text("拾光Agent 设置", style = heading(20), modifier = Modifier.weight(1f))
                // v22: 保存移到顶栏右上。原来挂在滚动内容的最底部 —— 改了 API Key 之后
                // 要一路滚到底才能保存（展开「使用统计」面板时尤其明显），而且它看起来
                // 像页面正文的一部分而不是动作按钮。顶栏固定可见，不用滚。
                if (dirty) {
                    TextButton(onClick = {
                        vm.setTavilyKey(tavily.trim())
                        vm.setLlmProviders(providers)
                        onDismiss()
                    }) { Text("保存", style = body(14f, FontWeight.Bold, c.accent)) }
                }
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── 联网搜索 ──
                SettingsSection("联网搜索") {
                    PasswordField(
                        value = tavily, onValueChange = { tavily = it },
                        label = "Tavily API Key", placeholder = "tavily.ai 的 API Key",
                    )
                }

                // ── 大模型 ──
                SettingsSection("大模型") {
                    // 供应商 chip 按钮组(CCS 式):横向流式排列,选中即展开配置。
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (p in providers) {
                            val selected = p.key == expandedKey
                            val active = p.key == vm.llmActiveProvider
                            ProviderChip(
                                text = p.displayName,
                                selected = selected,
                                active = active,
                                onClick = { expandedKey = if (selected) null else p.key },
                            )
                        }
                        // 添加自定义供应商 chip
                        ProviderChip(
                            text = "+ 自定义",
                            selected = false,
                            active = false,
                            onClick = {
                                // v1.3.3b review#A2: key 取现有自定义里最大编号+1,不随
                                // 删除回收——删"自定义1"再建若又生成 custom-1 会与存留的
                                // custom-1 撞 key(激活/查找都按 key,撞车指错供应商)。
                                val n = (providers.mapNotNull { p ->
                                    p.key.removePrefix("custom-").toIntOrNull()
                                }.maxOrNull() ?: 0) + 1
                                val nk = "custom-$n"
                                // v1.3.3: baseUrl 默认空(不预填 https://)——用户粘完整
                                // 链接时不必先删前缀;LlmConfig 规范化时自动补协议头。
                                providers = providers + LlmProviderConfig(nk, "自定义 $n", "", "", "")
                                expandedKey = nk
                            },
                        )
                    }
                    // 当前展开的供应商配置卡片
                    val exIdx = providers.indexOfFirst { it.key == expandedKey }
                    if (exIdx >= 0) {
                        ProviderConfigCard(
                            provider = providers[exIdx],
                            isActive = providers[exIdx].key == vm.llmActiveProvider,
                            // v1.3.3b review#A3: 激活时先落当前编辑态——只切 active 不保存
                            // 就退出的话,Agent 会用"新 key + 旧残缺配置"调用而失败。
                            onActivate = {
                                vm.setLlmProviders(providers)
                                vm.setLlmActiveProvider(providers[exIdx].key)
                            },
                            onKeyChange = { v ->
                                providers = providers.toMutableList().also { it[exIdx] = it[exIdx].copy(apiKey = v) }
                            },
                            onModelChange = { v ->
                                providers = providers.toMutableList().also { it[exIdx] = it[exIdx].copy(model = v) }
                            },
                            onBaseUrlChange = if (providers[exIdx].key.startsWith("custom-")) ({ v ->
                                providers = providers.toMutableList().also { it[exIdx] = it[exIdx].copy(baseUrl = v) }
                            }) else null,
                            onNameChange = if (providers[exIdx].key.startsWith("custom-")) ({ v ->
                                providers = providers.toMutableList().also { it[exIdx] = it[exIdx].copy(displayName = v.ifBlank { "自定义${exIdx + 1}" }) }
                            }) else null,
                            onDelete = if (providers[exIdx].key.startsWith("custom-")) ({
                                providers = providers.filterIndexed { i, _ -> i != exIdx }
                                expandedKey = null
                            }) else null,
                            tfColors = tfColors,
                        )
                    }
                }

                // ── 对话显示(v1.3.12) ─────────────────────────────────────────────
                // 用户实测:"思考链默认是展开的,应该在设置能够选择默认收起。"
                SettingsSection("对话显示") {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.setAgentThinkingCollapsed(!vm.agentThinkingCollapsed) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("思考过程默认收起", style = body(14f, FontWeight.Medium, c.text))
                            Text(
                                "开启后只显示「思考中 · Ns」,点一下才展开推理过程。",
                                style = body(11.5f, FontWeight.Normal, c.n600),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        OrganicSwitch(vm.agentThinkingCollapsed) {
                            vm.setAgentThinkingCollapsed(!vm.agentThinkingCollapsed)
                        }
                    }
                    // v1.4.0: 用户自选的「强制联网搜索」。模型知识面有限时会凭记忆猜出
                    // 错的歌手/专辑/年份，而用户分辨不出来；打开后这类内容一律先搜再答。
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.setAgentForceSearch(!vm.agentForceSearch) }
                            .padding(top = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("强制联网搜索", style = body(14f, FontWeight.Medium, c.text))
                            Text(
                                "遇到没把握的事实先上网查再回答，不凭记忆猜；代价是多等一次联网。",
                                style = body(11.5f, FontWeight.Normal, c.n600),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        OrganicSwitch(vm.agentForceSearch) {
                            vm.setAgentForceSearch(!vm.agentForceSearch)
                        }
                    }
                }

                // ── Agent 诊断样本(v1.3.14)─────────────────────────────────────────
                // 排查用:logcat 在本机被系统抑制、使用日志只记成败,出了"模型没按格式回"
                // 这类问题只有这里能看。
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                        .padding(horizontal = 16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.refreshAgentDiag(); vm.agentDiagOpen = true }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OIcon(Lucide.Info, 19.dp, c.n700)
                        Column(Modifier.weight(1f)) {
                            Text("Agent 诊断样本", style = body(14.5f, FontWeight.SemiBold, c.text))
                            Text(
                                "看最近几次 Agent 任务卡在哪一步(含对话内容)",
                                style = body(12f, FontWeight.Normal, c.n600),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        OIcon(Lucide.ChevronRight, 18.dp, c.n500)
                    }
                }

                // ── 拾光记忆(v1.3.12) ─────────────────────────────────────────────
                // 用户定调:功能区只有一个「拾光记忆」,**不要再套一层"记忆 → 拾光记忆"**
                // 的重复层级。所以这里不给 SettingsSection 标题,直接做成一个独立设置项。
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                        .padding(horizontal = 16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                vm.ensureMemoriesLoaded()
                                vm.agentMemoryScreenOpen = true
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OIcon(Lucide.Brain, 19.dp, c.n700)
                        Column(Modifier.weight(1f)) {
                            Text("拾光记忆", style = body(14.5f, FontWeight.SemiBold, c.text))
                            Text(
                                "管理拾光长期记住的关于你的信息",
                                style = body(12f, FontWeight.Normal, c.n600),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        OIcon(Lucide.ChevronRight, 18.dp, c.n500)
                    }
                }

                // ── 使用统计:Token 使用趋势面板 ──
                // v1.3.6 重做(按用户设计稿):圆角数据卡片;左上标题;右上**下拉单选框**选
                // 时间范围;核心趋势图:双 Y 轴(左=Token 量,右=成本估算),输入/输出/缓存命中
                // 三条平滑曲线带半透明面积填充,成本用虚线;底部图例。
                // v22 修正:改为包在 SettingsSection 里 —— 原来它是裸放的一张 16dp/灰底卡片，
                // 与「联网搜索」「大模型」两段的 20dp/白底 + 段落标题完全不是一套语言，
                // 视觉上像"大模型"段的附属物。
                SettingsSection("使用统计") { TokenTrendPanel(vm) }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    val c = LocalOrganic.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = body(13f, FontWeight.ExtraBold, c.text).copy(letterSpacing = 0.5.sp))
        content()
    }
}

/** 供应商 chip:选中(展开配置)高亮 accent;激活(当前用于 Agent)带橙点。
 *  v1.3.6: 激活的 chip 整颗变橙(不止橙点——用户定调"哪个中间商在用,一眼看到");
 *  同时选中+激活时用描边区分(橙底白字 + 白描边)。 */
@Composable
private fun ProviderChip(text: String, selected: Boolean, active: Boolean, onClick: () -> Unit) {
    val c = LocalOrganic.current
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                when {
                    selected -> c.accent
                    active -> c.a200
                    else -> c.bg
                }
            )
            .then(if (active && selected) Modifier.border(1.dp, androidx.compose.ui.graphics.Color.White, RoundedCornerShape(999.dp)) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 激活点保留(非选中态的白点在浅橙底上仍醒目;选中态文字白,点冗余但无害)
        if (active) Text("●", style = body(10f, FontWeight.Bold, if (selected) androidx.compose.ui.graphics.Color.White else c.accent))
        Text(
            text,
            style = body(12.5f, FontWeight.Bold,
                when {
                    selected -> androidx.compose.ui.graphics.Color.White
                    active -> c.a800
                    else -> c.text
                },
            ),
        )
    }
}

/**
 * 供应商配置卡片(点 chip 后展开)。含 API Key、官网链接(仅自定义)、模型下拉。
 * 模型下拉项:[LlmClient.listModels] 运行时 GET {baseUrl}/models 拉取;失败回退手输。
 */
@Composable
private fun ProviderConfigCard(
    provider: LlmProviderConfig,
    isActive: Boolean,
    onActivate: () -> Unit,
    onKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onBaseUrlChange: ((String) -> Unit)?,
    /** v1.3.1: 仅自定义供应商可改显示名(chip 上的按钮文字随它变)。 */
    onNameChange: ((String) -> Unit)?,
    onDelete: (() -> Unit)?,
    tfColors: androidx.compose.material3.TextFieldColors,
) {
    val c = LocalOrganic.current
    val scope = rememberCoroutineScope()
    // 运行时拉取的模型列表 + 加载/错误态
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var modelsMenuOpen by remember { mutableStateOf(false) }

    fun fetchModels() {
        if (provider.apiKey.isBlank()) { modelsError = "请先填 API Key"; return }
        loadingModels = true; modelsError = null
        scope.launch {
            val list = LlmClient.listModels(provider)
            loadingModels = false
            if (list.isEmpty()) modelsError = "获取失败,请检查 Key 和网络"
            else { models = list; modelsMenuOpen = true }
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.bg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 头:激活切换 + 删除
        // v1.3.3: "设为当前"做成明显的实心描边按钮(非激活=橙描边橙字,激活=橙底白字),
        // 之前藏在角落不显眼。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // v22: 未配置时按钮本来就禁用，但原来点了没反应、看不出禁用态 —— 补降透明度。
            val canActivate = provider.isConfigured
            val btnAlpha = if (canActivate) 1f else 0.45f
            Box(
                Modifier.clip(RoundedCornerShape(999.dp))
                    .then(
                        if (isActive) Modifier.background(c.accent.copy(alpha = btnAlpha))
                        else Modifier.border(width = 1.5.dp, color = c.accent.copy(alpha = btnAlpha), shape = RoundedCornerShape(999.dp)),
                    )
                    .clickable(enabled = canActivate) { onActivate() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isActive) "✓ 当前使用" else "使用该源",
                    style = body(12f, FontWeight.Bold,
                        (if (isActive) androidx.compose.ui.graphics.Color.White else c.accent).copy(alpha = btnAlpha)),
                )
            }
            Spacer(Modifier.weight(1f))
            if (!provider.isConfigured) {
                Text("未配置", style = body(10f, FontWeight.Normal, c.n500))
            }
            if (onDelete != null) {
                // v22: 删除是破坏性操作 —— 原来一个 28dp 图标紧贴"未配置"文字、点了直接删，
                // 极易误触且不可撤销。这里留出间距 + 二次确认。
                var confirmDelete by remember { mutableStateOf(false) }
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(c.surface)
                        .clickable { confirmDelete = true },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.Trash, 14.dp, c.n500) }
                if (confirmDelete) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        containerColor = c.surface,
                        title = {
                            Text("删除「${provider.displayName}」？", style = body(15f, FontWeight.ExtraBold, c.text))
                        },
                        text = {
                            Text(
                                "该供应商的 API Key 与模型设置会一并删除，不可撤销。",
                                style = body(12.5f, FontWeight.Normal, c.n600),
                            )
                        },
                        confirmButton = {
                            com.shiyin.music.ui.components.DialogActionsRow(
                                listOf(
                                    com.shiyin.music.ui.components.DialogAction("取消") { confirmDelete = false },
                                    com.shiyin.music.ui.components.DialogAction("删除", emphasized = true) {
                                        confirmDelete = false
                                        onDelete()
                                    },
                                )
                            )
                        },
                    )
                }
            }
        }
        // v1.3.1: 自定义供应商显示名(点 chip 展开的就是它,允许起好认的名字如"公司代理")。
        if (onNameChange != null) {
            OutlinedTextField(
                value = provider.displayName,
                onValueChange = onNameChange,
                label = { Text("名称", style = body(12f, FontWeight.Normal, c.n600)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = tfColors,
            )
        }
        // v1.3.1: API Key 密文保管(•••• 遮蔽 + 眼睛切换),与 Tavily Key 同一组件。
        PasswordField(
            value = provider.apiKey, onValueChange = onKeyChange,
            label = "API Key", placeholder = "sk-...",
        )
        if (onBaseUrlChange != null) {
            OutlinedTextField(
                value = provider.baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("官网链接 (baseUrl, 含 /v1)", style = body(12f, FontWeight.Normal, c.n600)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), colors = tfColors,
            )
        }
        // 模型下拉:点击触发 GET /models 拉取,成功后弹出菜单选择;也支持手输。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = provider.model,
                onValueChange = onModelChange,
                label = { Text("模型", style = body(12f, FontWeight.Normal, c.n600)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = tfColors,
            )
            Box {
                Box(
                    Modifier.padding(start = 8.dp).size(42.dp).clip(CircleShape).background(c.surface)
                        .clickable { fetchModels() },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.RefreshCw, 16.dp, c.accent) }
                // v1.3.6c: 菜单底色跟主题(M3 默认是浅色 surface,dark 模式下白得刺眼)
                androidx.compose.material3.DropdownMenu(
                    modelsMenuOpen, { modelsMenuOpen = false },
                    containerColor = c.surface,
                ) {
                    for (m in models) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(m, style = body(12f, FontWeight.Normal, c.text)) },
                            onClick = { onModelChange(m); modelsMenuOpen = false },
                        )
                    }
                }
            }
        }
        if (loadingModels) Text("获取中…", style = body(10f, FontWeight.Normal, c.n500))
        if (modelsError != null) Text(modelsError!!, style = body(10f, FontWeight.Normal, c.n500))
    }
}

@Composable
private fun AgentBubble(
    msg: MainViewModel.AgentMessage,
    vm: MainViewModel,
    isLast: Boolean = false,
    index: Int = -1,
) {
    val c = LocalOrganic.current
    val isUser = msg.role == "user"
    // v1.3.5: 点击用户消息 → 装回输入框改写重发(历史保留,不删对话——旧版 rewind
    // 删对话,用户点一下气泡"整段消失")。
    val keyboard = LocalSoftwareKeyboardController.current
    // v1.3.3: 去掉气泡入场动画——LazyColumn 滑动回收后重进会重播展开动画,气泡以
    // 0 高度出现再撑开,与相邻气泡视觉重叠("叠在一起");改为静态渲染,尺寸恒定。
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        // v1.3.5: 步骤面板回到气泡内(底部固定任务清单组件已撤——用户定调:状态
        // 跟着对话走,聊天流里逐行看;清单概念是给"开发过程"看的,不是 app UI)。
        // v1.3.9(选项2): 动态 Logo 是状态记录的前缀——同一时刻只出现在一处：
        // 有步骤在执行(TOOL/SEARCH)时归步骤块,否则归思考块(THINKING)。
        val stepsLive = !isUser && msg.steps?.isNotEmpty() == true &&
            vm.agentRunning && isLast && msg.text.isBlank()
        // v1.3.11:记忆运行状态只挂在最新一条 Agent 消息下(与落款 Logo 同一约定:发下一条就换地方)。
        // **一轮最多一行**:相位在同一条上更新(MemoryTraceFlow.uiRows 保证),不并列显示历史状态。
        val memTrace = if (!isUser && isLast) vm.agentMemoryTrace else null
        if (memTrace != null) {
            Column(Modifier.padding(bottom = 6.dp).widthIn(max = 320.dp)) {
                MemoryTraceRow(memTrace, c)
            }
        }
        if (!isUser && msg.steps?.isNotEmpty() == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stepsLive) {
                    AgentLogoThinking(size = 54.dp, speedMultiplier = 2f)
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.padding(bottom = 6.dp).widthIn(max = 320.dp)) {
                    for (s in msg.steps) StepRow(s, c)
                }
            }
        }
        if (!isUser && !msg.thinking.isNullOrBlank()) {
            ThinkingBlock(
                msg.thinking,
                live = msg.text.isBlank(),
                thinkingMs = msg.thinkingMs,
                showLogo = !stepsLive,
                // v1.3.12:设置里可要求"思考过程默认收起"
                defaultCollapsed = vm.agentThinkingCollapsed,
            )
        }
        if (msg.text.isNotBlank()) {
            if (isUser) {
                Box(
                    Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.accent)
                        // v1.3.5: 点击只装回输入框,**不再删对话**——旧版点历史消息
                        // 直接 rewind 掉之后的整段对话,用户点一下气泡"对话全没了"
                        // (误触灾难)。改写后点发送 = 以草稿重发一条新消息,历史保留。
                        .clickable(enabled = !vm.agentRunning) {
                            // v1.3.9: 点击用户消息 = 装回输入框 + 置覆盖锚点,
                            // 发送时**替换**该条起的后续内容(主流产品的编辑重发语义)。
                            vm.prepareAgentMessageEdit(index)
                            keyboard?.show()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(msg.text, style = body(13.5f, FontWeight.Normal, androidx.compose.ui.graphics.Color.White))
                }
            } else {
                // v1.3.9: 顺序修正(用户定调)——**思考链在上、最终输出在下**。
                // 之前是 [已思考] → [正文] → [查看思考过程],正文被夹在中间;
                // 现在:先思考块(已思考 / 查看思考过程 + 展开记录),再正文。
                val showProcessHistory = msg.processHistory.size >= 2 ||
                    msg.processHistory.any { it.summary?.isNotBlank() == true }
                if (showProcessHistory) {
                    val expanded = remember { mutableStateOf(false) }
                    Column(Modifier.padding(bottom = 6.dp).widthIn(max = 320.dp)) {
                        // 折叠/展开按钮
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { expanded.value = !expanded.value }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                if (expanded.value) "▲" else "▼",
                                style = body(11f, FontWeight.Normal, c.n500),
                            )
                            Text(
                                "查看思考过程",
                                style = body(11.5f, FontWeight.Medium, c.n600),
                            )
                        }

                        // 折叠区域内容
                        androidx.compose.animation.AnimatedVisibility(
                            visible = expanded.value,
                            enter = androidx.compose.animation.expandVertically(
                                animationSpec = tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            ),
                            exit = androidx.compose.animation.shrinkVertically(
                                animationSpec = tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            ),
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, start = 8.dp, end = 8.dp, bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                // v1.3.9(P6): 每条记录 = 类型图标 + 单行极简状态文字(+可选缩进展开)。
                                // 图标随动作类型切换(思考=方盒/搜索=放大镜/浏览=文档)——"换了图标"
                                // 本身就是"这是一条新记录"的信号。所有记录共用同一左基线,不加深缩进。
                                for (step in msg.processHistory) ProcessRecordRow(step, c)
                            }
                        }
                    }
                }

                // v1.3.8: Agent 回复内容(最终输出——在思考链之下)
                // v1.3.13:打字机还在吐字时**不算完成** —— 否则【复制】【重试】与署名 Logo
                // 会在正文还没吐完时就冒出来(用户实测)。打字机与 agentRunning 是解耦的
                // (见 AgentReplyText 的 v1.3.9 注释),所以这里必须单独听它是否还在打字。
                var typingNow by remember { mutableStateOf(false) }
                AgentReplyText(
                    msg.text,
                    streaming = vm.agentRunning && isLast && !isUser,
                    isLast = isLast,
                    onTypingChanged = { typingNow = it },
                )

                // v1.3.8: 状态机 Logo——根据消息状态动态显示
                // 动画标(思考中/执行中):均 60dp、2x 速度(全局统一,前期与中期不再有节奏差)
                // 静止标(生成中/已完成署名):均 66dp
                // v1.3.13:打字中(typingNow)也算"还在执行" —— 操作栏与署名只在**真正完成**
                // (模型答完 **且** 打字机追平)之后出现。
                val isExecuting = (vm.agentRunning && isLast) || (isLast && typingNow)
                val hasContent = msg.text.isNotBlank()

                when {
                    // GENERATING 状态：有内容且仍在运行 → Logo **静止**（规格：生成开始
                    // 立即完全停止，静止本身就是"轮到文字说话"的语义）。操作栏完成后出现。
                    isExecuting && hasContent -> {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(agentBrandRes(c.isDark)),
                            contentDescription = null,
                            modifier = Modifier.padding(top = 6.dp).size(60.dp),
                        )
                    }
                    // COMPLETED 状态：有内容且已完成
                    // 布局严格照 Claude 真机截图：正文 → 操作栏(复制/重试) → **Logo 另起
                    // 一行在下方**（不与操作栏同行，也不在其上方）。
                    // v1.3.9: 落款 Logo **只属最新一条回复**（用户定调:署名唯一;发了新
                    // 消息,旧回复的落款随之消失）;操作栏每条都保留。
                    hasContent -> {
                        Column {
                            Row(
                                Modifier.padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape)
                                        .clickable {
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.text))
                                            vm.showToast("已复制", null)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { OIcon(Lucide.Copy, 15.dp, c.n500) }
                                if (isLast && !vm.agentRunning) {
                                    Box(
                                        Modifier.size(30.dp).clip(CircleShape)
                                            .clickable { vm.retryAgentMessage(index) },
                                        contentAlignment = Alignment.Center,
                                    ) { OIcon(Lucide.RefreshCw, 15.dp, c.n500) }
                                }
                            }
                            if (isLast) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(agentBrandRes(c.isDark)),
                                    contentDescription = null,
                                    // v1.3.15: 44→60dp(署名标偏小),并**贴近上方操作栏**
                                    // (6→2dp:用户反馈署名离复制/重试太远)。与生成中的静止标
                                    // 同尺寸,署名出现时不再有大小跳变。
                                    // drawable 已按裁剪视口重生成,盒子多大图案就多大。
                                    // 左缘与消息列对齐(x=0);思考中胶囊的 Logo 也对齐到这里。
                                    modifier = Modifier.padding(top = 2.dp).size(60.dp),
                                )
                            }
                        }
                    }
                    // THINKING/TOOL 状态：无内容但执行中（由占位气泡/步骤面板处理，这里不显示）
                }
            }
        } else if (!isUser && msg.steps.isNullOrEmpty() && msg.thinking.isNullOrBlank() &&
            // v1.3.11:记忆状态**进行中**时不再叠通用占位气泡——光流那行本身就是加载指示,
            // 两个动画同时出现会互相打架(与"同一时刻只一处动态 Logo"同一条规矩)。
            // 终态(已保存/未找到)则允许与"思考中"并存:一个说记忆的结局,一个说还在生成。
            vm.agentMemoryTrace?.running != true
        ) {
            // v1.3.9: 占位气泡（THINKING 进入态：思考链尚未流回之前）
            // 动态 Logo 40dp + "思考中 · Xs"(随实际耗时增长的计时器，规格要求)。
            val c2 = c
            var elapsedSec by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) { kotlinx.coroutines.delay(1000); elapsedSec++ }
            }
            Row(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c2.bg)
                    // v1.3.9: start=0——Logo 左缘对齐到消息列(与署名 Logo 同一垂直线),
                    // 用户定调"加载 Logo 对齐署名 Logo 的位置"。
                    .padding(start = 0.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // v1.3.15: 尺寸 40→60dp、速度与"执行中"统一为 2x(用户反馈:刚发命令时的
                // 思考中比执行中**慢一半**,同一个 Logo 前后换了节奏。原因就是这处用默认 1f、
                // 步骤面板那处传 2f,文件里的注释还写着"平时 1f"—— 视觉上就是两档速度)。
                // 与 ThinkingBlock 内的 Logo 始终保持同尺寸、同速:占位气泡到思考块的切换
                // 不应有任何大小/速度跳变。
                AgentLogoThinking(size = 54.dp, speedMultiplier = 2f)
                Text(
                    // v1.3.9: Kotlin 字符串模板——"$elapsedSec" 后接中文须 ${} 包住变量名。
                    "思考中 · ${elapsedSec}s",
                    style = body(12f, FontWeight.Bold, c2.n600),
                )
            }
        }
        // v1.3.5: 消息级"本次 N tokens"删除(用户定调:用量不在对话里报,统计曲线
        // 挪到 Agent 设置页)。
    }
}

/**
 * v1.3.3: 思考链块(按用户设计规格三态)——放回复上方,先思考后说话。
 *  live 态: 胶囊显示 reasoning 尾部字幕(新内容右侧滑入感) + 呼吸圆点(40%-90%,
 *           1.5-2s ease-in-out 循环),不是通用 loading 圈。
 *  完成态: 折叠为"已思考 X 秒"(耗时本身有信息量),高度收缩 200-250ms ease-out。
 *  展开态: reasoning 按语义分段(转折词分段),段落间细分隔线,expandVertically
 *          200ms FastOutSlowIn。v1.3.4: live 期间也可点开(边思考边看,用户定调
 *          "点击之后它就展开了"才是正常产品逻辑,不再等完成才给点)。
 *  卡住降级: live 超过 9s 无新内容(由外部不再更新触发不了,内部用 LaunchedEffect
 *          监测 thinking 文本静止时长)→ 文案切"仍在思考…"。
 */
/**
 * v1.3.9: 思考中秒表（规格：THINKING 状态文字 =「思考中 · Ns」，随实际耗时增长）。
 * 自计时——挂上就每秒自增，不依赖外部传值。
 */
@Composable
private fun AgentElapseText(running: Boolean, cText: androidx.compose.ui.graphics.Color) {
    var sec by remember { mutableStateOf(0) }
    if (running) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                sec++
            }
        }
    }
    Text(
        "思考中（用时 ${sec} 秒）",
        style = body(12f, FontWeight.Bold, cText),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ThinkingBlock(
    thinking: String,
    live: Boolean,
    thinkingMs: Long = 0L,
    /** v1.3.9: 动态 Logo 是否归本块显示（TOOL 阶段的步骤块已占用时为 false，避免两个动画同时出现）。 */
    showLogo: Boolean = true,
    /** v1.3.12:设置「思考过程默认收起」——开了就不再自动摊开(仍可手点展开)。 */
    defaultCollapsed: Boolean = false,
) {
    val c = LocalOrganic.current
    // v1.3.5: live 态默认展开(用户定调:思考中就摊开看,像 ChatGPT 的 reasoning
    // 流);回复出来(live=false)自动折叠成"已思考 X 秒"。用户手动点开/收起过
    // (manualToggle)就尊重手选,不再自动切。
    // v1.3.12:设置里可改为"默认收起"(defaultCollapsed)——那时连 live 也不自动摊开。
    var expanded by remember { mutableStateOf(live && !defaultCollapsed) }
    var manualToggle by remember { mutableStateOf(false) }
    LaunchedEffect(live) {
        if (!manualToggle) expanded = live && !defaultCollapsed
    }
    // live 态:最近一次 thinking 变化的时间——静止超 9s 显示"仍在思考…"
    var stalled by remember { mutableStateOf(false) }
    if (live) {
        LaunchedEffect(thinking) {
            stalled = false
            kotlinx.coroutines.delay(9000)
            stalled = true
        }
    }
    Column(Modifier.widthIn(max = 320.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(c.bg)
                .clickable {
                    manualToggle = true
                    expanded = !expanded
                }
                // v1.3.9: start=0——live 态 Logo 左缘对齐消息列(与署名 Logo 同线)。
                .padding(start = 0.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // v1.3.9: live 态用动态品牌标志替换呼吸圆点（规格：THINKING = 动态 Logo +
            // 实际耗时计时）；右侧加「思考中 · Ns」秒表。
            if (live) {
                if (showLogo) com.shiyin.music.ui.components.AgentLogoThinking(size = 54.dp, speedMultiplier = 2f)
                AgentElapseText(running = true, cText = c.n600)
            } else {
                Text(
                    "已思考（用时 ${(thinkingMs / 1000).coerceAtLeast(1)} 秒）",
                    style = body(12f, FontWeight.Bold, c.n600),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            // v1.3.4: live 也给展开箭头(方向随展开态),提示可点。
            OIcon(if (expanded) Lucide.ChevronUp else Lucide.ChevronDown, 12.dp, c.n600)
        }
        // 展开态(P6):叙述性文字保持**连续段落**,不做项目符号、不按转折词切断。
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            ) + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            ) + androidx.compose.animation.fadeOut(tween(150)),
        ) {
            // v1.3.12 真机 bug:思考正文原来是个**不设限的 Text** —— 越写越长,长到超出屏幕
            // 之后就"卡住"了,新内容落在看不见的地方(用户:"并没有像成熟产品一样旧文向上,
            // 露出新的思考内容")。现在:限高 168dp + 自己滚 + **内容变长就把视口钉到底部**
            // (旧文向上、新文始终露出),与 Claude / DeepSeek 的阅读方式一致。
            val thinkScroll = rememberScrollState()
            androidx.compose.runtime.LaunchedEffect(live) {
                androidx.compose.runtime.snapshotFlow { thinkScroll.maxValue }
                    .collect { max ->
                        // 只在进行中跟随:用户往上翻看历史时不要抢他的位置
                        if (live && max > 0) thinkScroll.scrollTo(max)
                    }
            }
            Box(
                Modifier
                    .padding(top = 4.dp, start = 19.dp, end = 10.dp, bottom = 4.dp)
                    .heightIn(max = 168.dp)
                    .verticalScroll(thinkScroll),
            ) {
                Text(
                    thinking,
                    style = body(11.5f, FontWeight.Normal, c.n500).copy(lineHeight = 17.sp),
                )
            }
        }
    }
}

/** v1.3.3: Agent 回复文本——流式已由 VM 逐段写入 msg.text,这里不再本地模拟打字机;
 *  尾部光标(▍)随文本存在,流完自然消失。
 * v1.3.5: 真逐字打字机——VM 侧 onContent 是全量串,一整段一整段跳(用户反馈"像
 * 一段动画渲染出来,不是一个字一个字吐")。这里维护显示游标:每 ~30ms 吐 1-2
 * 个字符平滑追赶全量文本;流结束时一次性追平(不拖泥带水)。CJK 与拉丁都按
 * 字符数推进(视觉节奏一致性够用)。
 */
@Composable
private fun AgentReplyText(
    text: String,
    streaming: Boolean,
    isLast: Boolean,
    /** v1.3.13:打字机是否还在吐字 —— 气泡据此把"操作栏 / 署名 Logo"推迟到真正完成。 */
    onTypingChanged: (Boolean) -> Unit = {},
) {
    val c = LocalOrganic.current
    // v1.3.9 打字机修复(用户实测"最终回答瞬间输出"):技能类回复的正文是**一次性**
    // 写入的,`agentRunning` 几乎立刻翻 false——只按 streaming 判断,动画还没跑就已结束。
    // 现在:首帧决定起点(流式中从 0 开始;历史消息直接整段),末条消息**持续跑到追平**
    // 为止(与 agentRunning 解耦),不再中途被"瞬间补全"截断。
    var shownCount by remember { mutableStateOf(if (streaming) 0 else text.length) }
    LaunchedEffect(text, isLast) {
        if (!isLast) {
            shownCount = text.length
            onTypingChanged(false)
            return@LaunchedEffect
        }
        if (shownCount >= text.length) {
            // 已经追平(或本来就没有要吐的字)→ 空闲
            onTypingChanged(false)
            return@LaunchedEffect
        }
        onTypingChanged(true)
        val breaker = java.text.BreakIterator.getCharacterInstance()
        breaker.setText(text)
        while (shownCount < text.length) {
            val next = breaker.following(shownCount)
            if (next == java.text.BreakIterator.DONE) break
            shownCount = next
            kotlinx.coroutines.delay(45)
        }
        onTypingChanged(false)
    }
    val shown = text.take(shownCount)

    // v1.3.8: Markdown 渲染——支持 **bold** / *italic* / `code`
    val annotated = remember(shown) { parseMarkdown(shown, c) }

    Box(
        Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            // v1.3.9: 去掉打字光标(用户:黑色粗「▍」难看)——纯文字逐字出现即可。
            text = annotated,
            style = body(13.5f, FontWeight.Normal, c.text),
        )
    }
}

// ── v1.3.13: 空态欢迎语(打字机) ──────────────────────────────────────────────
/**
 * 欢迎语文案池。调性:她是**陪你听歌的人**,不是客服。
 *
 * 不写"有什么我能帮你的"(通用助手腔)、不列功能清单、不催输入;
 * 也**不替用户安排下一步**("慢慢挑""不着急"那类预设动作的说法一律弃用)。
 * 排版硬约束(用户要求):**每行都短到能独占一行**,否则窄屏上第二行会被挤成一两个字;
 * 颜文字只出现在行尾、且整组跟着这一行(靠显式分行 + softWrap=false 保证不被拆开),
 * 取值来自 [com.shiyin.music.data.ai.KaomojiPolicy] 的同一份清单(UI 与模型输出不许漂移),
 * 只在情绪点略高的两条用;不用也不是缺陷——不写死有或无。
 */
private val AGENT_GREETINGS: List<List<String>> = listOf(
    // ── 一行(短) ──
    listOf("今天想听点什么？"),
    listOf("唱片都在架上。"),
    listOf("嗯，我在。 (｡・ω・｡)"),
    listOf("今晚想听哪一张？ ( ´ ▽ ` )"),
    // ── 两行(长) ──
    listOf("要不要听点什么？", "你决定，我播放。"),
    listOf("要不要一起，", "把这张专辑从头听到尾？"),
    listOf("音乐都整理好了。", "想听歌，还是随便聊聊？"),
    listOf("架上的唱片都整理好了。", "想从哪一张开始？"),
    listOf("你想找的那首，", "也许就躺在架上第三排。"),
    listOf("有想听的专辑吗？", "没有的话，我挑一张。"),
    listOf("今天想听抒情一点的，", "还是想听燥一点的？"),
    listOf("听歌，还是聊聊天？", "我都在这儿。"),
)

/** 欢迎语候选字号:从大到小试,取"每一行都能独占一行"的最大值。 */
private val GREETING_SIZES = listOf(24, 22, 20, 18, 16)

private const val GREETING_BASE_DELAY_MS = 45L

/** 首字延迟:静默这一段正是"她在想"的来源(参考 Claude / ChatGPT 官网的节奏)。 */
private const val GREETING_INITIAL_DELAY_MS = 400L

/** 末尾做渐入的字素数——再大就整段淡入、失去逐字感。 */
private const val GREETING_ALPHA_RAMP = 4

private val GREETING_PAUSE_STRONG = setOf("。", "！", "？", "…", "!", "?", "\n")
private val GREETING_PAUSE_WEAK = setOf("，", "、", "；", "：", ",", ";", ":")

/** 标点停顿:弱标点约 4 倍字速(换气),强标点/换行约 7 倍(一句说完)。 */
private fun greetingPauseAfter(grapheme: String): Long = when (grapheme) {
    in GREETING_PAUSE_STRONG -> 320L
    in GREETING_PAUSE_WEAK -> 190L
    else -> GREETING_BASE_DELAY_MS
}

/**
 * 打字机欢迎语。无光标(与 [AgentReplyText] 同一决定);首字延迟 → 逐字族推进 →
 * 标点处停顿 → 新出现的末尾几个字素做极轻的 alpha 渐入。
 *
 * 排版:文案**显式分行**,不靠自动折行(断点才不会随屏幕宽度乱跳);组合期用
 * TextMeasurer 逐行量宽、自动降到能一行放下的最大字号,再配 softWrap=false,
 * 彻底排除"第二行只剩一两个字""颜文字被拆到两行"这类事故。量的是**完整**文案,
 * 所以打字过程中字号不会跳。
 *
 * [played] 为真(本会话已经播过)时直接整段呈现,不重放——切 tab、滚动回来都安静。
 * 打字期间点一下即可补全(打字机的必备出口)。
 */
@Composable
private fun TypewriterGreeting(
    lines: List<String>,
    played: Boolean,
    onPlayed: () -> Unit,
    initialDelayMs: Long = GREETING_INITIAL_DELAY_MS,
) {
    val c = LocalOrganic.current
    val full = remember(lines) { lines.joinToString("\n") }
    var shown by rememberSaveable(full) { mutableIntStateOf(if (played) full.length else 0) }
    var skip by remember { mutableStateOf(false) }

    LaunchedEffect(full, played) {
        if (played || shown >= full.length) {
            onPlayed()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(initialDelayMs)
        val breaker = java.text.BreakIterator.getCharacterInstance()
        breaker.setText(full)
        var lastEmitted = ""
        var i = 0
        while (i < full.length) {
            if (skip) break
            val next = breaker.following(i)
            if (next == java.text.BreakIterator.DONE) break
            lastEmitted = full.substring(i, next)
            i = next
            shown = i
            kotlinx.coroutines.delay(greetingPauseAfter(lastEmitted))
        }
        shown = full.length
        onPlayed()
    }

    val shownText = full.take(shown)

    Column(
        Modifier.fillMaxWidth().clickableNoRipple { skip = true },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val availPx = with(LocalDensity.current) { maxWidth.toPx() }
            // heading() 是 @Composable,只能在组合里取一次"字体模板",再在 remember 里
            // 按字号派生——量宽循环不进组合作用域。
            val probe = heading(GREETING_SIZES.first())
            val size = remember(lines, availPx, probe) {
                GREETING_SIZES.firstOrNull { s ->
                    val st = probe.copy(fontSize = s.sp)
                    lines.all { ln ->
                        measurer.measure(AnnotatedString(ln), style = st).size.width.toFloat() <= availPx
                    }
                } ?: GREETING_SIZES.last()
            }
            Text(
                text = shownText,
                style = probe.copy(fontSize = size.sp, lineHeight = 34.sp),
                textAlign = TextAlign.Center,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                    // TalkBack 一次读完整句,不要跟着打字机一个字一个字念
                    contentDescription = lines.joinToString(" ")
                },
            )
        }
    }
}

/**
 * v1.3.15:删掉问候语尾部的**逐字渐入**(原实现:末尾 4 个字素 0.35→1 alpha)。
 * 用户反馈"打字到倒数第三个字时有打光效果,显示并不好"—— 打字机本身已经把"正在写"讲清楚了,
 * 再给尾字加一道由浅到深的光只会让那两个字看起来像没渲染出来。现在所有字素同色,写出来即完整。
 */

/**
 * v1.3.8: 简化 Markdown 解析器——支持 **bold** / *italic* / `code`。
 *
 * 参考主流 AI 产品（ChatGPT/Claude）的格式支持，仅解析最常用的三种内联格式。
 * 缓冲策略：未闭合的格式标记（如 `**bol`）保持原样，等到配对完成再渲染。
 */
private fun parseMarkdown(text: String, c: com.shiyin.music.ui.theme.OrganicColors): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // **bold** (需要至少 4 个字符才尝试匹配: **X*)
                text.startsWith("**", i) && i + 4 <= text.length -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
                        append(text.substring(i + 2, end))
                        pop()
                        i = end + 2
                    } else {
                        // 未闭合，保持原样
                        append(text[i])
                        i++
                    }
                }
                // *italic* (需要至少 3 个字符: *X*)
                text.startsWith("*", i) && i + 2 < text.length && text[i + 1] != '*' -> {
                    val end = text.indexOf("*", i + 1)
                    if (end != -1 && (end == text.lastIndex || text[end + 1] != '*')) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // `code` (需要至少 3 个字符: `X`)
                text.startsWith("`", i) && i + 2 <= text.length -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            background = c.surface,
                            color = c.n700,
                        ))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

/** v1.3.9(P6): 动作类型 → 图标。换图标就是"这是一条新类型的记录"的信号。 */
private fun processTypeIcon(type: String) = when {
    type.contains("search") || type.contains("web") || type.contains("fetch") -> Lucide.Search
    type.contains("browse") || type.contains("page") || type.contains("doc") -> Lucide.FileText
    else -> Lucide.Box
}

/**
 * v1.3.9(P6): 单条中间过程记录 ——「类型图标 + 单行极简状态文字」；
 * 有展开内容时另起一行、只缩进一层，叙述性文字保持连续段落（不拆项目符号）。
 * 所有记录共用同一左基线，条数增加不加深层级。
 */
@Composable
private fun ProcessRecordRow(step: MainViewModel.ProcessStep, c: com.shiyin.music.ui.theme.OrganicColors) {
    val detail = step.summary
    val hasDetail = !detail.isNullOrBlank()
    var expanded by remember(step) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = hasDetail) { expanded = !expanded }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OIcon(processTypeIcon(step.type), 13.dp, c.n600)
            Text(
                step.label,
                style = body(12f, FontWeight.Bold, c.n700),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hasDetail) OIcon(if (expanded) Lucide.ChevronUp else Lucide.ChevronDown, 12.dp, c.n500)
        }
        if (expanded && detail != null) {
            Text(
                detail,
                style = body(11.5f, FontWeight.Normal, c.n600).copy(lineHeight = 17.sp),
                modifier = Modifier.padding(start = 19.dp, end = 4.dp, top = 1.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun StepRow(s: AgentEngine.Step, c: com.shiyin.music.ui.theme.OrganicColors) {
    // v1.3.9(P6): 行首换成**动作类型图标**(与折叠区同一套图标语言),不再用 ✓/✗ 字符——
    // 类型一眼可辨,状态靠颜色(进行中=accent / 完成=n700 / 失败=n400)。
    val tint = when (s.status) {
        AgentEngine.StepStatus.RUNNING -> c.accent
        AgentEngine.StepStatus.DONE -> c.n700
        AgentEngine.StepStatus.FAILED -> c.n400
        else -> c.n500
    }
    val animatedTint by animateColorAsState(tint, animationSpec = tween(250), label = "stepTint")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OIcon(processTypeIcon(s.id), 13.dp, animatedTint)
        Text(
            // FAILED 行内直接带出失败原因(错误码/网络异常),不只给一个叉
            if (s.status == AgentEngine.StepStatus.FAILED && !s.error.isNullOrBlank()) "${s.label}（${s.error}）" else s.label,
            style = body(12f, FontWeight.Normal, if (s.status == AgentEngine.StepStatus.DONE) c.n700 else c.text),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (s.status == AgentEngine.StepStatus.RUNNING) {
            Box(Modifier.width(14.dp)) { EqBars(playing = true, barMaxHeight = 10.dp) }
        }
    }
}

/**
 * v1.3.11:「记忆」状态的运行反馈行 —— 记忆中 / 记忆已保存 / 记忆保存失败 /
 * 正在回忆 / 已找到记忆 / 未找到相关记忆 / 回忆失败。
 *
 * 为什么要它:记忆出问题时从对话里看不出断在哪一步(没存?存失败?没检索到?检索异常?
 * 没注入?注入了没用?)。这一行把**真实状态**摆出来,让问题可判断。
 *
 * 视觉规格(用户定调):
 *  - **进行中** → 文案上有一道**从左向右流动的光**。实现:把文字渲进自己的离屏层
 *    (CompositingStrategy.Offscreen),再用 `SrcAtop` 把渐变色带只染在**已有笔画**上
 *    —— 效果是"光流过字面",而不是一条通用 loading 圈。与步骤行的音浪条刻意区分开:
 *    一眼能分出"记忆在动"与"工具在跑"。
 *  - **终态** → 完全静止,一帧动画都不留(状态真假不能靠动画装)。
 *  - 颜色与步骤行同一套语言:进行中 accent / 成功 n700 / 失败 n400。
 */
@Composable
private fun MemoryTraceRow(
    trace: com.shiyin.music.data.memory.MemoryTrace,
    c: com.shiyin.music.ui.theme.OrganicColors,
) {
    val phase = trace.phase
    val tint = when (phase) {
        com.shiyin.music.data.memory.MemoryTrace.Phase.SAVED,
        com.shiyin.music.data.memory.MemoryTrace.Phase.FOUND -> c.n700
        com.shiyin.music.data.memory.MemoryTrace.Phase.SAVE_FAILED,
        com.shiyin.music.data.memory.MemoryTrace.Phase.RECALL_FAILED -> c.n400
        // 未找到相关记忆不是错误,用中性色(与"失败"区分开)
        com.shiyin.music.data.memory.MemoryTrace.Phase.NOT_FOUND -> c.n500
        else -> c.accent
    }
    val animatedTint by animateColorAsState(tint, animationSpec = tween(250), label = "memTint")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OIcon(Lucide.Brain, 13.dp, animatedTint)
        if (trace.running) {
            // 光流:0→1 线性循环(等速,像光扫过;不用缓动,缓动会显得"呼吸")
            val transition = rememberInfiniteTransition(label = "memFlow")
            val sweep by transition.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
                label = "memSweep",
            )
            Text(
                trace.label,
                style = body(12f, FontWeight.Normal, c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    // 离屏层:让下面的 SrcAtop 只与本行文字混色,不吃到气泡背景
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val band = size.width * 0.45f
                        val x = -band + (size.width + band) * sweep
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, animatedTint, Color.Transparent),
                                startX = x,
                                endX = x + band,
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            )
        } else {
            Text(
                trace.label,
                style = body(12f, FontWeight.Normal, if (tint == c.n700) c.n700 else c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}


/**
 * v1.3.6: Token 使用趋势面板(用户设计稿)——"数据监控面板"层次:
 *  - 卡片容器:c.n200(light=米白卡/dark=深棕卡,跟随主题翻转——首版用 n900 在
 *    light 下近黑、dark 下翻白,两模式全错,用户实怒"跟主题不搭";主题的 ramp
 *    是双向翻转的,深浅必须取语义中点色,不是 n900)
 *  - 头行:左"Token 使用趋势"标题(c.text),右**下拉单选框**选时间范围
 *    (当天/3天/7天/14天/30天)
 *  - 双 Y 轴:左=Token 数(顶/中/零刻度),右=成本¥
 *  - 曲线组:输入/输出/缓存命中=平滑曲线(cubic 中点法)+半透明面积;
 *    成本=虚线(dashPathEffect,右轴度量)
 *  - X 轴时间轴;底部图例。成本:输入$0.5/M、输出$1.5/M 折¥估算。
 *  暂不做模型维度(用户注明)。
 */
@Composable
private fun TokenTrendPanel(vm: MainViewModel) {
    val c = LocalOrganic.current
    var rangeDays by remember { mutableStateOf(7) }
    // v1.3.10 A19: 一次拉齐(曲线/合计/功能榜/累计同窗口同刻,不各查各的)
    var snap by remember { mutableStateOf<com.shiyin.music.data.ai.UsageSnapshot?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(rangeDays) {
        snap = com.shiyin.music.data.ai.TokenUsageStore.snapshot(ctx, rangeDays)
    }
    // 曲线配色(accent 橙 + 主题灰阶——两主题下都可读)
    val inColor = c.accent
    val outColor = c.n700
    val cacheColor = c.n500
    val costColor = c.a700
    // v22: 成本改走 TokenPricing。原来这里写死 `(in*0.5 + out*1.5)/1e6*7.2`，
    // 注释却声称"按激活供应商价表估算"—— 实际没读任何价表、汇率也写死，
    // 且**缓存命中的 token 一分钱都没算进成本**。
    val providerKey = vm.llmActiveProvider
    val providerName = vm.llmProviders.firstOrNull { it.key == providerKey }?.displayName ?: providerKey
    fun costOf(inTok: Long, outTok: Long, cacheTok: Long): Double =
        com.shiyin.music.data.ai.TokenPricing.estimateCny(providerKey, inTok, outTok, cacheTok)
    val rangeLabel = com.shiyin.music.data.ai.TokenUsageWindow.rangeLabel(rangeDays)

    Column(
        // v22: 内层数据卡改用 c.bg（与供应商配置卡同一套语言）。外层段落卡片与标题
        // 由 SettingsSection 提供，所以这里不再自带灰底与重复标题。
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.bg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 头行:范围选择
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("时间范围", style = body(11.5f, FontWeight.Normal, c.n600))
            Spacer(Modifier.weight(1f))
            var rangeOpen by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.divider)
                        .clickable { rangeOpen = true }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(rangeLabel, style = body(11f, FontWeight.Bold, c.text))
                    OIcon(Lucide.ChevronDown, 12.dp, c.n600)
                }
                // v1.3.6c: 范围选择菜单底色跟主题(dark 下 M3 默认白底刺眼)
                androidx.compose.material3.DropdownMenu(
                    rangeOpen, { rangeOpen = false },
                    containerColor = c.surface,
                ) {
                    for (d in listOf(1, 3, 7, 14, 30)) {
                        val lab = when (d) { 1 -> "当天"; else -> "${d}天" }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(lab, style = body(12f, if (d == rangeDays) FontWeight.ExtraBold else FontWeight.Normal, if (d == rangeDays) c.accent else c.text)) },
                            onClick = { rangeDays = d; rangeOpen = false },
                        )
                    }
                }
            }
        }
        if (snap == null) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                Text("加载中…", style = body(11f, FontWeight.Normal, c.n500))
            }
            return@Column
        }
        val s = snap!!
        // v22: 缓存命中是**输入 token 的子集**（prompt_tokens_details.cached_tokens），
        // 不能与输入相加 —— 原来的 in+out+cache 属于重复计数。判断"有无数据"看输入+输出即可。
        if (!s.hasData) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                Text("暂无使用记录", style = body(11f, FontWeight.Normal, c.n500))
            }
            return@Column
        }
        val data = s.daily

        // ── 合计区(v1.3.10 A19 按用户"太空泛/准度不够"重做)────────────────────
        // 联网调研成熟产品(OpenAI/Anthropic 控制台、Cursor 的 Usage、OpenRouter Activity)
        // 的共识:对**非工程师用户**要钱的数字在前、token 在后;必须有**归因**(哪个功能在花)
        // 与**可核对的量**(调用次数、平均每次);缓存要当成"省了多少"而不是一条灰线。
        val sumIn = s.sumInput
        val sumOut = s.sumOutput
        val sumCache = s.sumCache
        val sumCost = costOf(sumIn, sumOut, sumCache)
        val calls = s.callsInRange
        val avgCost = if (calls > 0) sumCost / calls else 0.0
        val hitRate = if (sumIn > 0) sumCache * 100.0 / sumIn else 0.0
        val saved = com.shiyin.music.data.ai.TokenPricing.cacheSavingsCny(providerKey, sumIn, sumCache)
        val allCost = costOf(s.allTimeInput, s.allTimeOutput, 0L)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            // 钱在前:普通用户关心的是花了多少,而不是 token 是什么。
            Text("¥${trimCost(sumCost)}", style = body(21f, FontWeight.ExtraBold, c.accent))
            Text(
                " ${rangeLabel}估算",
                style = body(9.5f, FontWeight.Bold, c.n500),
                modifier = Modifier.padding(bottom = 3.dp),
            )
            Spacer(Modifier.weight(1f))
            // 调用次数让上面的钱可被核对(如 6 次 × 约 ¥0.007)
            Text(
                if (calls > 0) "${calls} 次调用 · 平均每次 ¥${trimCost(avgCost)}"
                else "无调用记录",
                style = body(10f, FontWeight.Bold, c.n600),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        // token 明细退居第二行(想知道细节的人往下看)
        Text(
            // ⚠️ 模板里的变量一律用 ${} —— Kotlin 里 CJK 也是合法标识符字符,
            // "$sumIn字" 会被当成变量名。
            "输入 ${shortTok(sumIn)} · 输出 ${shortTok(sumOut)}" +
                (if (sumCache > 0)
                    " · 缓存命中 ${"%.0f".format(hitRate)}%（省约 ¥${trimCost(saved)}）"
                else ""),
            style = body(10.5f, FontWeight.Bold, c.text),
        )
        Text(
            "累计至今 输入 ${shortTok(s.allTimeInput)} · 输出 ${shortTok(s.allTimeOutput)} ≈ ¥${trimCost(allCost)}",
            style = body(10f, FontWeight.Normal, c.n600),
        )
        // 把"约 ¥x"是按什么价算的写出来，避免成本曲线变成黑箱。
        Text(
            "成本为估算（$providerName）：${com.shiyin.music.data.ai.TokenPricing.assumptionText(providerKey)}",
            style = body(9.5f, FontWeight.Normal, c.n500),
        )

        // v22: 按天聚合时"当天"只有今天一个数据点，画不出趋势 —— 原来会渲染出一条退化
        // 的空路径（图表看着是空白）。这种情况直接把上面的合计当结论，不再画图。
        if (rangeDays <= 1) {
            Text(
                "「当天」按天聚合只有今天一个数据点，画不出趋势；上方即今日用量。",
                style = body(10.5f, FontWeight.Normal, c.n500),
            )
            TokenFeatureBreakdown(s.bySkill, providerKey)
            return@Column
        }

        // ── 双 Y 轴趋势图 ──
        val maxTok = data.maxOf { maxOf(it.input, it.output, it.cache) }.coerceAtLeast(1L)
        val maxCost = data.maxOf { costOf(it.input, it.output, it.cache) }.coerceAtLeast(0.01)
        // v1.3.10 A19(用户要求):切换时间范围导致数据变化时,曲线要**过渡渲染**,不能硬切。
        // 点数本来是变的(1/3/7/14/30),形状无从对应 —— 所以先把每条曲线**线性重采样到
        // 统一分辨率**(48 点)再逐点插值,于是"点数变化"也变成一次平滑形变。
        // 归一化用**目标**上限:轴刻度立刻显示新范围(数字不跳),曲线在归一化空间里过渡。
        val chartN = 48
        val targetCurves = ChartCurves(
            inp = resampleTo(data.map { it.input.toFloat() / maxTok }, chartN),
            out = resampleTo(data.map { it.output.toFloat() / maxTok }, chartN),
            cache = resampleTo(data.map { it.cache.toFloat() / maxTok }, chartN),
            cost = resampleTo(
                data.map { (costOf(it.input, it.output, it.cache) / maxCost).toFloat() }, chartN,
            ),
        )
        var fromCurves by remember { mutableStateOf<ChartCurves?>(null) }
        var toCurves by remember { mutableStateOf(targetCurves) }
        val morph = remember { androidx.compose.animation.core.Animatable(1f) }
        // 用"汇总值"当键:同一范围里数字变了(比如新用了 token)也重跑动画,不然画面会停旧数据。
        val chartKey = listOf(rangeDays, data.size, s.sumInput, s.sumOutput, s.sumCache)
        LaunchedEffect(chartKey) {
            // 起点 = **此刻正在显示的画面**(不是上一份目标)。这样快速连点范围时
            // 不会先跳回旧目标再过渡,而是从当前形状继续morph。
            val displayed = fromCurves?.let { blendCurves(it, toCurves, morph.value) }
            if (displayed == null) {
                // 首次显示:直接到位,不做"从零长出来"的动画
                toCurves = targetCurves
                morph.snapTo(1f)
                return@LaunchedEffect
            }
            fromCurves = displayed
            toCurves = targetCurves
            morph.snapTo(0f)
            morph.animateTo(
                1f,
                androidx.compose.animation.core.tween(
                    420, easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            )
        }
        val curves = fromCurves?.let { blendCurves(it, toCurves, morph.value) } ?: toCurves
        // v1.3.10 A19: X 轴起点改用**本地天**标签(TokenUsageWindow),与分桶同一套算术 ——
        // 原来拿 now-(days-1)*86400000 直接格式化,天边界是 UTC 的,标签会与曲线错一天。
        val xLabelStart = com.shiyin.music.data.ai.TokenUsageWindow.dayLabel(s.dayRange.first(), s.tzOffsetMs)
        val xLabelEnd = if (rangeDays == 1) "现在" else "今天"

        // v1.3.10 A19(用户反馈):手指在图上移动时,每个位置都要能读到"那一天用了多少"。
        // null = 没在触摸;否则 = 命中的**原始数据天序号**(不是重采样后的下标——数值必须是
        // 真实某天的数字,不能是插值出来的)。
        var hoverIdx by remember { mutableStateOf<Int?>(null) }
        // 150→250dp(用户:"使用统计在页面最后面的板块,变高也没有任何问题""线太不圆润了,
        // 估计就是设置的太矮了")。纵向空间多了,同样的数据点铺开后曲线明显更舒展。
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(250.dp)
                .pointerInput(data.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        fun idxAt(x: Float): Int = if (data.size <= 1) 0
                        else ((x / w) * (data.size - 1)).roundToInt().coerceIn(0, data.size - 1)
                        hoverIdx = idxAt(down.position.x)
                        // 只有"横向为主"的拖动才接管(纵向留给外层滚动,否则设置页在这块
                        // 区域会滑不动);判定为纵向就收起卡片并把事件让出去。
                        var tookOver = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            val dx = kotlin.math.abs(ch.position.x - down.position.x)
                            val dy = kotlin.math.abs(ch.position.y - down.position.y)
                            if (!ch.pressed) break
                            if (!tookOver && dx > viewConfiguration.touchSlop && dx > dy) tookOver = true
                            if (!tookOver && dy > viewConfiguration.touchSlop && dy >= dx) {
                                hoverIdx = null
                                break
                            }
                            if (tookOver) {
                                ch.consume()
                                hoverIdx = idxAt(ch.position.x)
                            }
                        }
                        hoverIdx = null
                    }
                },
        ) {
            val padTop = 8.dp
            // v1.3.10 A19:底部留白 14→18dp —— X 轴日期字号从 8.5 提到 10sp,原来的
            // 14dp 塞不下(会与绘图区下沿贴死)。
            val chartH = maxHeight - padTop - 18.dp
            // 刻度标签的行位必须**与网格线对齐**:Canvas 里网格线在 plotH-usable*(i/4)
            // (usable=plotH*0.9,5 条线),即自上而下 10% / 32.5% / 55% / 77.5% / 100%。
            // 所以标签列只占 [10% .. 100%] 这一段(高度 90%),而不是 fillMaxHeight()——
            // 原来占满整个 Box,最下方的 "0"/"¥0" 落到 X 轴日期那一行上,两组小字正好
            // 叠在一起(用户实测"字体重叠了数字")。
            val axisTop = chartH * 0.1f
            val axisSpan = chartH * 0.9f
            Canvas(Modifier.fillMaxWidth().height(chartH).padding(top = padTop)) {
                // 重采样后固定 chartN 点 → x 均匀铺满(morph 过程中点数不变,曲线才连得上)
                val plotW = size.width
                val plotH = size.height
                val stepX = plotW / (chartN - 1).toFloat()
                val usable = plotH * 0.9f
                // v1.3.10 A19:3 条 → 5 条网格线(4 等分)。图变高后 3 条太疏,
                // 读数只能靠两端;5 条给到 1/4 刻度,配 5 个刻度标签。
                for (i in 0..4) {
                    val y = plotH - usable * (i / 4f)
                    // v1.3.6: 网格线用主题文字色低透明(两主题自动可读;硬编码 White
                    // 在 light 米白卡上不可见)。
                    drawLine(
                        color = c.text.copy(alpha = 0.12f),
                        start = Offset(0f, y), end = Offset(plotW, y),
                        strokeWidth = 1.dp.toPx() * 0.6f,
                    )
                }
                fun smoothPath(pts: List<Offset>): androidx.compose.ui.graphics.Path {
                    val p = androidx.compose.ui.graphics.Path()
                    p.moveTo(pts.first().x, pts.first().y)
                    for (i in 1 until pts.size) {
                        val prev = pts[i - 1]; val cur = pts[i]
                        val midX = (prev.x + cur.x) / 2f
                        p.cubicTo(midX, prev.y, midX, cur.y, cur.x, cur.y)
                    }
                    return p
                }
                fun areaPath(pts: List<Offset>): androidx.compose.ui.graphics.Path {
                    val p = smoothPath(pts)
                    p.lineTo(pts.last().x, plotH)
                    p.lineTo(pts.first().x, plotH)
                    p.close()
                    return p
                }
                fun yOfNorm(v: Float) = plotH - usable * v
                fun pts(series: FloatArray) =
                    (0 until chartN).map { i -> Offset(i * stepX, yOfNorm(series[i])) }
                val inPts = pts(curves.inp)
                val outPts = pts(curves.out)
                val cachePts = pts(curves.cache)
                drawPath(areaPath(inPts), inColor.copy(alpha = 0.20f))
                drawPath(areaPath(outPts), outColor.copy(alpha = 0.16f))
                drawPath(smoothPath(inPts), inColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                drawPath(smoothPath(outPts), outColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                if (curves.cache.any { it > 0.0005f }) {
                    drawPath(smoothPath(cachePts), cacheColor, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                }
                drawPath(
                    smoothPath(pts(curves.cost)), costColor,
                    style = Stroke(
                        1.8.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    ),
                )
                // v1.3.15:在**真实数据点**位置画圆点(当天 1、3 天 3、7 天 7 个点)。
                // 为什么必须标:"按天聚合"的采样本来就少,画之前又被线性重采样到 48 个点 ——
                // 于是 3 个采样看起来像一条平滑的持续上升趋势,用户(以及外部模型)据此误判成
                // "上下文在不断膨胀、每次都在重复发送"。标出真实点位,"这是 N 个采样"一目了然,
                // 不会再被读成连续趋势。点多时(14/30 天)不标,免得串成糖葫芦。
                val realN = data.size
                if (realN in 2..14 && realN < chartN) {
                    val dotR = 2.4.dp.toPx()
                    for (i in 0 until realN) {
                        // 反解 resampleTo 的线性映射:第 i 个真实点落在第 si 个采样位上(四舍五入)
                        val si = ((i * (chartN - 1) + (realN - 1) / 2) / (realN - 1)).coerceIn(0, chartN - 1)
                        val dotX = si * stepX
                        fun dot(series: FloatArray, dotColor: Color, r: Float) {
                            val dotY = yOfNorm(series[si])
                            // 先铺一圈底色再画点:压在两条曲线交叠处也读得出来
                            drawCircle(color = c.surface, radius = r + 1.2.dp.toPx(), center = Offset(dotX, dotY))
                            drawCircle(color = dotColor, radius = r, center = Offset(dotX, dotY))
                        }
                        dot(curves.inp, inColor, dotR)
                        dot(curves.out, outColor, dotR)
                        if (curves.cache.any { it > 0.0005f }) dot(curves.cache, cacheColor, dotR * 0.8f)
                    }
                }
                // v1.3.10 A19:手指位置的十字准线 + 各序列圆点。
                // 圆点取**当前正在绘制的曲线**在该处的值(而非原始值单独归一化)——
                // 这样 morph 过程中圆点始终贴在线上,不会飘到线外。
                hoverIdx?.let { hi ->
                    val n = data.size
                    val fx = if (n > 1) hi.toFloat() / (n - 1) else 0.5f
                    val x = fx * plotW
                    drawLine(
                        color = c.text.copy(alpha = 0.30f),
                        start = Offset(x, 0f), end = Offset(x, plotH),
                        strokeWidth = 1.dp.toPx(),
                    )
                    fun curveAt(a: FloatArray): Float {
                        val t = if (n > 1) fx * (chartN - 1) else (chartN - 1) / 2f
                        val i0 = t.toInt().coerceIn(0, chartN - 1)
                        val i1 = (i0 + 1).coerceAtMost(chartN - 1)
                        return a[i0] + (a[i1] - a[i0]) * (t - i0)
                    }
                    fun dot(a: FloatArray, col: androidx.compose.ui.graphics.Color) {
                        val cy = yOfNorm(curveAt(a).coerceIn(0f, 1f))
                        drawCircle(col, radius = 4.dp.toPx(), center = Offset(x, cy))
                        drawCircle(c.bg, radius = 1.8.dp.toPx(), center = Offset(x, cy))
                    }
                    dot(curves.inp, inColor)
                    dot(curves.out, outColor)
                    if (curves.cache.any { it > 0.0005f }) dot(curves.cache, cacheColor)
                    dot(curves.cost, costColor)
                }
            }
            // 左 Y 轴(Token 刻度)。字号 8→10sp:8sp 在手机上几乎读不出来,
            // 用户反馈"XY 轴没有任何内容"有一部分就是字太小看不清。
            // 5 档刻度(与 4 等分的 5 条网格线一一对应)。
            Column(
                Modifier.padding(top = padTop + axisTop).height(axisSpan),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(shortTok(maxTok), style = body(10f, FontWeight.Bold, c.n700))
                Text(shortTok(maxTok * 3 / 4), style = body(10f, FontWeight.Bold, c.n600))
                Text(shortTok(maxTok / 2), style = body(10f, FontWeight.Bold, c.n600))
                Text(shortTok(maxTok / 4), style = body(10f, FontWeight.Bold, c.n600))
                Text("0", style = body(10f, FontWeight.Bold, c.n600))
            }
            // 右 Y 轴(成本刻度)
            Column(
                Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                    .padding(top = padTop + axisTop).height(axisSpan),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("¥" + trimCost(maxCost), style = body(10f, FontWeight.Bold, costColor.copy(alpha = 0.95f)))
                Text("¥" + trimCost(maxCost * 3 / 4), style = body(10f, FontWeight.Bold, costColor.copy(alpha = 0.9f)))
                Text("¥" + trimCost(maxCost / 2), style = body(10f, FontWeight.Bold, costColor.copy(alpha = 0.9f)))
                Text("¥" + trimCost(maxCost / 4), style = body(10f, FontWeight.Bold, costColor.copy(alpha = 0.9f)))
                Text("¥0", style = body(10f, FontWeight.Bold, costColor.copy(alpha = 0.9f)))
            }
            // X 轴日期(底部)
            Row(
                Modifier.align(androidx.compose.ui.Alignment.BottomStart).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(xLabelStart, style = body(10f, FontWeight.Bold, c.n600))
                Text(xLabelEnd, style = body(10f, FontWeight.Bold, c.n600))
            }
            // v1.3.10 A19:触摸位置的数据卡(用户参考图:每个位置都要能读到该时间下的用量)。
            // 显示的是**真实某天**的原始数字,不是重采样后的插值。
            hoverIdx?.let { rawIdx ->
                val hi = rawIdx.coerceIn(0, data.size - 1)
                val d = data[hi]
                val n = data.size
                val fx = if (n > 1) hi.toFloat() / (n - 1) else 0.5f
                val tipW = 156.dp
                // 准线在右半边时把卡片放左侧(否则会出界);再统一夹进可视区。
                val rawX = if (fx > 0.55f) maxWidth * fx - tipW - 8.dp else maxWidth * fx + 8.dp
                val tipX = rawX.coerceIn(0.dp, (maxWidth - tipW).coerceAtLeast(0.dp))
                Column(
                    Modifier
                        .offset(x = tipX, y = padTop)
                        .width(tipW)
                        .shadowMd(RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.surface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        com.shiyin.music.data.ai.TokenUsageWindow.dayLabel(
                            s.dayRange.getOrElse(hi) { s.dayRange.last() }, s.tzOffsetMs,
                        ),
                        style = body(11.5f, FontWeight.ExtraBold, c.text),
                    )
                    UsageTipRow(inColor, "输入", "%,d".format(d.input))
                    UsageTipRow(outColor, "输出", "%,d".format(d.output))
                    if (d.cache > 0) UsageTipRow(cacheColor, "缓存命中", "%,d".format(d.cache))
                    UsageTipRow(costColor, "成本", "¥" + trimCost(costOf(d.input, d.output, d.cache)))
                }
            }
        }
        // 图例:曲线色块 + 成本虚线段
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(inColor))
                Text("输入 Token", style = body(9.5f, FontWeight.Bold, c.n500))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(outColor))
                Text("输出 Token", style = body(9.5f, FontWeight.Bold, c.n500))
            }
            if (curves.cache.any { it > 0.0005f }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(cacheColor))
                    Text("缓存命中", style = body(9.5f, FontWeight.Bold, c.n500))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.foundation.Canvas(Modifier.size(16.dp, 2.dp)) {
                    drawLine(
                        costColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                    )
                }
                Text("成本（估算）", style = body(9.5f, FontWeight.Bold, c.n500))
            }
        }
        // ── 功能明细:哪个功能在花钱 ──
        TokenFeatureBreakdown(s.bySkill, providerKey)
    }
}

/** v1.3.10 A19:趋势图数据卡里的一行 —— 色点 + 名称 + 数值(与曲线同色,便于对号入座)。 */
@Composable
private fun UsageTipRow(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    value: String,
) {
    val c = LocalOrganic.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, style = body(10.5f, FontWeight.Bold, c.n600))
        Text(
            value,
            style = body(10.5f, FontWeight.Bold, c.text),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * v1.3.10 A19: 用量按**功能**归因的明细列表。
 *
 * 为什么加这个:原来面板只有一根总量曲线 —— 用户看不出"钱花在哪",这正是"太空泛"。
 * 联网调研里每篇讲 LLM 成本可视化的文章都把"归因(per-feature/per-endpoint)"列为首要
 * 指标:没有归因,总量只是个令人焦虑的陌生数字;有了归因,用户能立刻做出判断
 * ("原来是歌词翻译在花,那我少翻几首")。
 *
 * 每行:功能名 · 占比条 · 成本 · token 量。占比按**成本**而非 token 算——
 * 输出 token 比输入贵 3-4 倍,按 token 算会把"贵但量小的功能"显示得无关紧要。
 */
@Composable
private fun TokenFeatureBreakdown(
    bySkill: List<com.shiyin.music.data.db.SkillUsage>,
    providerKey: String,
) {
    if (bySkill.isEmpty()) return
    val c = LocalOrganic.current
    fun costOf(u: com.shiyin.music.data.db.SkillUsage): Double =
        com.shiyin.music.data.ai.TokenPricing.estimateCny(providerKey, u.inputTokens, u.outputTokens, u.cacheTokens)
    val total = bySkill.sumOf { costOf(it) }.coerceAtLeast(0.000001)
    val maxCost = bySkill.maxOf { costOf(it) }.coerceAtLeast(0.000001)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("功能明细", style = body(11.5f, FontWeight.ExtraBold, c.text).copy(letterSpacing = 0.3.sp))
        for (u in bySkill) {
            val cost = costOf(u)
            val share = cost / total
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    com.shiyin.music.data.ai.TokenUsageWindow.skillLabel(u.skill),
                    style = body(10.5f, FontWeight.Bold, c.text),
                    modifier = Modifier.width(74.dp),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                // 占比条(相对最大功能,便于横向比较;右侧数字是实际占比)
                Box(
                    Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(999.dp)).background(c.divider),
                ) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth((cost / maxCost).toFloat().coerceIn(0.03f, 1f))
                            .clip(RoundedCornerShape(999.dp)).background(c.accent.copy(alpha = 0.85f)),
                    )
                }
                Text(
                    " ¥${trimCost(cost)} · ${"%.0f".format(share * 100)}% · ${u.calls}次",
                    style = body(9.5f, FontWeight.Bold, c.n600),
                    modifier = Modifier.width(104.dp),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** token 数短写:1.2k / 3.4M。 */
private fun shortTok(v: Long): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000f)
    v >= 1000 -> "%.1fk".format(v / 1000f)
    else -> "$v"
}

/** v1.3.8: 品牌标按主题选盘面变体（浅色=米白盘 agent_brand，深色=黑胶盘 agent_brand_dark）。 */
private fun agentBrandRes(isDark: Boolean): Int =
    if (isDark) com.shiyin.music.R.drawable.agent_brand_dark else com.shiyin.music.R.drawable.agent_brand

/** 成本短写:¥0.032(≥100 显示 ¥3.2)。 */
private fun trimCost(v: Double): String = when {
    v >= 100 -> "%.1f".format(v)
    v >= 1 -> "%.2f".format(v)
    else -> "%.3f".format(v)
}


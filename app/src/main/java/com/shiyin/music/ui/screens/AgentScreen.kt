package com.shiyin.music.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmClient
import com.shiyin.music.data.ai.LlmProviderConfig
import com.shiyin.music.ui.components.EqBars
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.PasswordField
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic
import kotlinx.coroutines.launch

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
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                .statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(c.surface).clickable { vm.agentOpen = false }, contentAlignment = Alignment.Center) {
                OIcon(Lucide.ChevronLeft, 20.dp, c.text)
            }
            Text("Agent", style = heading(22), modifier = Modifier.weight(1f))
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
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(vm.agentMessages) { idx, msg ->
                AgentBubble(msg, vm, isLast = idx == vm.agentMessages.lastIndex)
            }
            if (vm.agentMessages.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("告诉 Agent 你想做什么", style = heading(18))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "例如:\n「帮我把这张专辑排成正确顺序」\n「这个专辑歌手名识别反了,帮我查一下」\n「给当前这首歌找歌词」",
                            style = body(13f, FontWeight.Normal, c.n600), textAlign = TextAlign.Center,
                        )
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
                modifier = Modifier.weight(1f),
                // v1.3.3: 删掉执行中占位提示——不需要告诉用户"可打断",停止键本身就说明了。
                placeholder = null,
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
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(c.surface).clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.ChevronLeft, 20.dp, c.text) }
                Text("Agent 设置", style = heading(20), modifier = Modifier.weight(1f))
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

                // ── 使用统计:Token 使用趋势面板 ──
                // v1.3.6 重做(按用户设计稿):深色圆角数据卡片;左上标题;右上**下拉
                // 单选框**选时间范围(当天/3天/7天/14天/30天);核心趋势图:双 Y 轴
                // (左=Token 量,右=成本),输入/输出/缓存命中三条**平滑曲线**带半透明
                // 面积填充,成本用**虚线**(按激活供应商价表估算);底部图例。暂不做
                // 模型维度。所有元素随范围切换联动。
                TokenTrendPanel(vm)

                // 保存(v1.3.5: 只有编辑过(dirty)才显示——平时不挂常驻按钮)
                if (dirty) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            vm.setTavilyKey(tavily.trim())
                            vm.setLlmProviders(providers)
                            onDismiss()
                        }) { Text("保存", style = body(14f, FontWeight.Bold, c.accent)) }
                    }
                }
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
            Box(
                Modifier.clip(RoundedCornerShape(999.dp))
                    .then(
                        if (isActive) Modifier.background(c.accent)
                        else Modifier.border(width = 1.5.dp, color = c.accent, shape = RoundedCornerShape(999.dp)),
                    )
                    .clickable(enabled = provider.isConfigured) { onActivate() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isActive) "✓ 当前使用" else "使用该源",
                    style = body(12f, FontWeight.Bold, if (isActive) androidx.compose.ui.graphics.Color.White else c.accent),
                )
            }
            Spacer(Modifier.weight(1f))
            if (!provider.isConfigured) {
                Text("未配置", style = body(10f, FontWeight.Normal, c.n500))
            }
            if (onDelete != null) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(c.surface).clickable { onDelete() },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.Trash, 14.dp, c.n500) }
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
        if (!isUser && msg.steps?.isNotEmpty() == true) {
            Column(Modifier.padding(bottom = 6.dp).widthIn(max = 320.dp)) {
                for (s in msg.steps) StepRow(s, c)
            }
        }
        if (!isUser && !msg.thinking.isNullOrBlank()) {
            ThinkingBlock(msg.thinking, live = msg.text.isBlank(), thinkingMs = msg.thinkingMs)
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
                            vm.agentDraft = msg.text
                            keyboard?.show()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(msg.text, style = body(13.5f, FontWeight.Normal, androidx.compose.ui.graphics.Color.White))
                }
            } else {
                AgentReplyText(msg.text, streaming = vm.agentRunning && isLast && !isUser)
            }
        } else if (!isUser && msg.steps.isNullOrEmpty() && msg.thinking.isNullOrBlank()) {
            // v1.3.4: 占位气泡换成与思考链同一套呼吸语言(圆点 + "正在思考…"),
            // 不是三点跳动——用户定调"像呼吸灯一样,没有呼吸就是卡住了"。执行中
            // 任何流(reasoning/回复/步骤)到达都会替换掉这块。
            val c2 = c
            Row(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c2.bg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val breathe by rememberInfiniteTransition(label = "waitDot").animateFloat(
                    initialValue = 0.4f, targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        tween(900, easing = androidx.compose.animation.core.EaseInOutSine),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    ),
                    label = "waitDotA",
                )
                // v1.3.6e: 圆点 6→9dp + 白色改橙色(c.accent)——白点在浅色气泡上
                // 几乎看不见(用户反馈"白色不明显");文字 10→12sp 同步放大。
                Box(Modifier.size(9.dp).clip(CircleShape).background(c2.accent.copy(alpha = breathe)))
                Text("正在思考…", style = body(12f, FontWeight.Bold, c2.n600))
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
@Composable
private fun ThinkingBlock(thinking: String, live: Boolean, thinkingMs: Long = 0L) {
    val c = LocalOrganic.current
    // v1.3.5: live 态默认展开(用户定调:思考中就摊开看,像 ChatGPT 的 reasoning
    // 流);回复出来(live=false)自动折叠成"已思考 X 秒"。用户手动点开/收起过
    // (manualToggle)就尊重手选,不再自动切。
    var expanded by remember { mutableStateOf(live) }
    var manualToggle by remember { mutableStateOf(false) }
    LaunchedEffect(live) {
        if (!manualToggle) expanded = live
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
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 呼吸圆点:透明度 0.4→0.9 ease-in-out 循环(周期 ~1.8s),比旋转圈更"沉思"
            // v1.3.6e: 6→9dp + 白改橙(c.accent,白点浅色模式下看不见);文字 10→12sp。
            if (live) {
                val breathe by rememberInfiniteTransition(label = "thinkDot").animateFloat(
                    initialValue = 0.4f, targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        tween(900, easing = androidx.compose.animation.core.EaseInOutSine),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    ),
                    label = "thinkDotA",
                )
                Box(
                    Modifier.size(9.dp).clip(CircleShape)
                        .background(c.accent.copy(alpha = breathe))
                )
            }
            Text(
                when {
                    live && stalled -> "仍在思考…"
                    live && thinking.isNotBlank() -> "正在思考 " + thinking.takeLast(24).replace('\n', ' ')
                    live -> "正在思考…"
                    else -> "已思考 ${(thinkingMs / 1000).coerceAtLeast(1)} 秒"
                },
                style = body(12f, FontWeight.Bold, c.n600),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // v1.3.4: live 也给展开箭头(方向随展开态),提示可点。
            OIcon(if (expanded) Lucide.ChevronUp else Lucide.ChevronDown, 12.dp, c.n600)
        }
        // 展开态:reasoning 按语义分段(首先/然后/所以/但是…转折词分段),段间细分隔线
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            ) + androidx.compose.animation.fadeIn(tween(200)),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            ) + androidx.compose.animation.fadeOut(tween(150)),
        ) {
            Column(Modifier.padding(top = 4.dp, start = 10.dp, end = 10.dp)) {
                val paras = thinking.split(Regex("(?=首先|然后|所以|但是|不过|因此|接下来|另外)"))
                    .map { it.trim() }.filter { it.isNotEmpty() }
                for ((i, p) in paras.withIndex()) {
                    if (i > 0) Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(c.divider.copy(alpha = 0.1f))
                            .padding(vertical = 0.dp)
                    )
                    Text(
                        p,
                        style = body(11f, FontWeight.Normal, c.n500).copy(lineHeight = 16.sp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
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
private fun AgentReplyText(text: String, streaming: Boolean) {
    val c = LocalOrganic.current
    var shownCount by remember(text) { mutableStateOf(0) }
    if (streaming) {
        // 每帧吐字:LaunchedEffect 循环推进游标,追到全量即停(等下一批增量到达
        // 由 text 变化重启 effect 继续)。
        LaunchedEffect(text) {
            while (shownCount < text.length) {
                shownCount = (shownCount + 2).coerceAtMost(text.length)
                kotlinx.coroutines.delay(30)
            }
        }
    } else {
        // 流结束:立即追平(收尾不拖)
        shownCount = text.length
    }
    val shown = text.take(shownCount)
    Box(
        Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            if (streaming) "$shown▍" else shown,
            style = body(13.5f, FontWeight.Normal, c.text),
        )
    }
}

@Composable
private fun StepRow(s: AgentEngine.Step, c: com.shiyin.music.ui.theme.OrganicColors) {
    // v1.3.3: 去掉行入场展开动画——滑动回收重进会重播,行以 0 高出现再撑开造成重叠;
    // 保留状态色平滑过渡。RUNNING 行首用拾音式音浪(EqBars)表达"进行中"。
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.status == AgentEngine.StepStatus.RUNNING) {
            Box(Modifier.width(18.dp)) {
                EqBars(playing = true, barMaxHeight = 12.dp)
            }
            Text(s.label, style = body(12.5f, FontWeight.Normal, c.text))
        } else {
            val (icon, tint) = when (s.status) {
                AgentEngine.StepStatus.DONE -> "✓" to c.n700
                AgentEngine.StepStatus.FAILED -> "✗" to c.n400
                else -> "○" to c.n500
            }
            val animatedTint by animateColorAsState(tint, animationSpec = tween(250), label = "stepTint")
            Text(icon, style = body(13f, FontWeight.Bold, animatedTint))
            // v1.3.3: FAILED 行内直接显示失败原因(错误码/网络异常),不只给 ✗
            if (s.status == AgentEngine.StepStatus.FAILED && !s.error.isNullOrBlank()) {
                Text(
                    "${s.label}（${s.error}）",
                    style = body(12.5f, FontWeight.Normal, c.n500),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(s.label, style = body(12.5f, FontWeight.Normal, c.text))
            }
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
    var metrics by remember { mutableStateOf<List<Triple<Long, Long, Long>>?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(rangeDays) {
        metrics = com.shiyin.music.data.ai.TokenUsageStore.dailyMetrics(ctx, rangeDays)
    }
    // 曲线配色(accent 橙 + 主题灰阶——两主题下都可读)
    val inColor = c.accent
    val outColor = c.n700
    val cacheColor = c.n500
    val costColor = c.a700
    fun costOf(inTok: Long, outTok: Long): Double = (inTok * 0.5 + outTok * 1.5) / 1_000_000.0 * 7.2
    val rangeLabel = when (rangeDays) { 1 -> "当天"; 3 -> "3天"; 7 -> "7天"; 14 -> "14天"; else -> "30天" }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.n200)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 头行:标题 + 下拉单选
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Token 使用趋势", style = body(13f, FontWeight.ExtraBold, c.text))
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
        if (metrics == null) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                Text("加载中…", style = body(11f, FontWeight.Normal, c.n500))
            }
            return@Column
        }
        val data = metrics!!
        val totalSum = data.sumOf { it.first + it.second + it.third }
        if (totalSum == 0L) {
            Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                Text("暂无使用记录", style = body(11f, FontWeight.Normal, c.n500))
            }
            return@Column
        }

        // ── 双 Y 轴趋势图 ──
        val maxTok = data.maxOf { maxOf(it.first, it.second, it.third) }.coerceAtLeast(1L)
        val maxCost = data.maxOf { costOf(it.first, it.second) }.coerceAtLeast(0.01)
        val xLabelStart = if (rangeDays == 1) "0时"
            else java.text.SimpleDateFormat("M/d", java.util.Locale.CHINA).format(
                java.util.Date(System.currentTimeMillis() - (rangeDays - 1) * 86_400_000L),
            )
        val xLabelEnd = if (rangeDays == 1) "现在" else "今天"

        BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp)) {
            val padTop = 8.dp
            val chartH = maxHeight - padTop - 14.dp
            Canvas(Modifier.fillMaxWidth().height(chartH).padding(top = padTop)) {
                val n = data.size.coerceAtLeast(2)
                val plotW = size.width
                val plotH = size.height
                val stepX = plotW / (n - 1).toFloat()
                val usable = plotH * 0.9f
                for (i in 0..2) {
                    val y = plotH - usable * (i / 2f)
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
                fun yOf(v: Long) = plotH - usable * (v.toFloat() / maxTok)
                val inPts = data.mapIndexed { i, t -> Offset(i * stepX, yOf(t.first)) }
                val outPts = data.mapIndexed { i, t -> Offset(i * stepX, yOf(t.second)) }
                val cachePts = data.mapIndexed { i, t -> Offset(i * stepX, yOf(t.third)) }
                drawPath(areaPath(inPts), inColor.copy(alpha = 0.20f))
                drawPath(areaPath(outPts), outColor.copy(alpha = 0.16f))
                drawPath(smoothPath(inPts), inColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                drawPath(smoothPath(outPts), outColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                if (data.any { it.third > 0 }) {
                    drawPath(smoothPath(cachePts), cacheColor, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                }
                val costPts = data.mapIndexed { i, t ->
                    Offset(i * stepX, plotH - usable * ((costOf(t.first, t.second) / maxCost).toFloat()))
                }
                drawPath(
                    smoothPath(costPts), costColor,
                    style = Stroke(
                        1.8.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    ),
                )
            }
            // 左 Y 轴(Token 刻度)
            Column(
                Modifier.padding(top = padTop).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(shortTok(maxTok), style = body(8f, FontWeight.Bold, c.n600))
                Text(shortTok(maxTok / 2), style = body(8f, FontWeight.Bold, c.n600))
                Text("0", style = body(8f, FontWeight.Bold, c.n600))
            }
            // 右 Y 轴(成本刻度)
            Column(
                Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(top = padTop).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("¥" + trimCost(maxCost), style = body(8f, FontWeight.Bold, costColor.copy(alpha = 0.9f)))
                Text("¥" + trimCost(maxCost / 2), style = body(8f, FontWeight.Bold, costColor.copy(alpha = 0.9f)))
                Text("¥0", style = body(8f, FontWeight.Bold, costColor.copy(alpha = 0.9f)))
            }
            // X 轴日期(底部)
            Row(
                Modifier.align(androidx.compose.ui.Alignment.BottomStart).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(xLabelStart, style = body(8.5f, FontWeight.Bold, c.n500))
                Text(xLabelEnd, style = body(8.5f, FontWeight.Bold, c.n500))
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
            if (data.any { it.third > 0 }) {
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
                Text("成本", style = body(9.5f, FontWeight.Bold, c.n500))
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

/** 成本短写:¥0.032(≥100 显示 ¥3.2)。 */
private fun trimCost(v: Double): String = when {
    v >= 100 -> "%.1f".format(v)
    v >= 1 -> "%.2f".format(v)
    else -> "%.3f".format(v)
}


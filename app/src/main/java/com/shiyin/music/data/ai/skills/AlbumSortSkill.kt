package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.AgentService
import com.shiyin.music.data.ai.LlmProviderConfig

/**
 * v1.2.2 Agent 技能①:专辑曲目排序修正。
 *
 * 流程(步骤面板实时更新,面板预置 4 行对应 tracks/search/analyze/writeback):
 *  ①读取: 本地曲目当前顺序(即时)
 *  ②搜证: Tavily 搜官方曲目顺序资料(搜不到标 ✗,LLM 凭知识判断)
 *  ③分析: LLM 对照资料输出"按官方顺序排列的本地编号序列"——对哪首排第几做判断,
 *         名字与官方的差异(feat. 括号/空格/繁简)由 AI 理清对应关系
 *  ④写回: 按编号重排落库(ctx.applyAlbumOrderIndices);AI 未确定全部时走确认窗闸门
 *
 * 信息不足(Tavily 空 + LLM UNKNOWN)→ 不硬排,按两种情况给明确提示。
 */
object AlbumSortSkill : AgentEngine.AgentSkill {
    override val key = "album_sort"
    override val description = "把专辑曲目按官方顺序排序;指令里可点名任意专辑名(如「把《魔杰座》排成正确顺序」),也可作用于当前打开的专辑"
    override val parameters = """
        {"type":"object","properties":{"album":{"type":"string","description":"要排序的专辑名。用户点名了专辑就填;没点名则不要填(作用于当前打开的专辑)"}},"required":[]}
    """.trimIndent()
    override fun panelSteps(args: Map<String, String>) = listOf(
        AgentEngine.PanelStep("tracks", "读取专辑曲目"),
        AgentEngine.PanelStep("search", "联网搜索官方顺序"),
        AgentEngine.PanelStep("analyze", "AI 核对生成顺序"),
        AgentEngine.PanelStep("writeback", "写回排序"),
    )

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult {
        // 解析目标专辑:指令点名专辑名(args.album,任意专辑)→ 按名搜库;
        // 否则回退 args.albumKey / 当前专辑页。点名了但搜不到 → 明确告知,不瞎猜。
        val named = args["album"]
        val key = when {
            !named.isNullOrBlank() -> ctx.findAlbum(named) ?: return AgentEngine.SkillResult(
                "音乐库里没有找到名为「$named」的专辑。请确认专辑名写法,或打开那张专辑后再发指令。",
                success = false,
            )
            else -> args["albumKey"] ?: ctx.albumKey
        }
        if (key.isNullOrBlank()) {
            return AgentEngine.SkillResult("没有指定专辑,无法排序。可以说「把《专辑名》排成正确顺序」,或先打开某张专辑。", success = false)
        }
        onStep("tracks", AgentEngine.StepStatus.RUNNING)
        val tracks = ctx.albumTracksOf(key)
        if (tracks.isEmpty()) {
            return AgentEngine.SkillResult("该专辑没有曲目。", success = false)
        }
        val albumName = tracks.first().album
        val artistName = tracks.first().artist
        val currentTitles = tracks.map { it.title }
        onStep("tracks", AgentEngine.StepStatus.DONE)

        onStep("search", AgentEngine.StepStatus.RUNNING)
        val result = AgentService.fetchOfficialTrackOrder(
            config = config,
            tavilyKey = tavilyKey,
            album = albumName,
            artist = artistName,
            currentTracks = currentTitles,
            onStep = onStep,  // AgentService 内部推进 search DONE → analyze RUNNING/DONE
        )
        if (result == null) {
            val err = com.shiyin.music.data.ai.LlmClient.lastError ?: "未知错误"
            // v1.3.3: 失败立即标当前步骤 ✗ + 后续步骤 ✗,不要继续滚——用户要快速看到结果。
            onStep("search", AgentEngine.StepStatus.FAILED)
            onStep("analyze", AgentEngine.StepStatus.FAILED)
            onStep("writeback", AgentEngine.StepStatus.FAILED)
            return AgentEngine.SkillResult("LLM 调用失败: $err", success = false)
        }
        ctx.logUsage(result.promptTokens, result.completionTokens, result.cacheTokens)
        if (result.orderedIndices.isEmpty()) {
            // 信息不足:search/analyze 已完成(✓),但 analyze 结果为空 → 标 analyze ✗ + writeback ✗。
            onStep("analyze", AgentEngine.StepStatus.FAILED)
            onStep("writeback", AgentEngine.StepStatus.FAILED)
            // v1.3.15:失败要能分辨**败在哪一半**。真机连着两次《龙年》都是 ordered=0,但一句
            // 笼统的"没按格式输出"看不出到底是:
            //   ① 模型压根没输出曲目清单(回复被截断/只回了 UNKNOWN),还是
            //   ② 清单出来了、但全对不上本地写法(它写了官方名而不是本地名)。
            // 这两件的修法完全不同,所以这里按"模型输出了几行曲目"分档讲,并把它记进诊断。
            val titleLines = AgentService.parseTitleLines(result.rawReply).size
            val thinkLen = result.thinking?.length ?: 0
            // 另注:文案里过去写的是"曲目**编号**" —— v1.3.13 起协议早已改成**曲名**
            // (编号格式是弱模型的老坑),留着这句话会把排查带偏。
            val msg = when {
                result.webSearched && result.hadUnknown ->
                    "AI 判断《$albumName》曲序时未能确定答案(它识别为 UNKNOWN)。可能专辑名与官方写法不一致——在专辑 ⋮ 里检查专辑名/歌手名识别是否正确后重试。"
                !result.webSearched ->
                    "联网搜索《$albumName》没有找到相关资料(专辑名/歌手名识别有误、网络不可达或搜索 Key 失效)。先核对专辑名识别是否正确,再重试。"
                // v1.3.15:没输出清单时,**按事实说清是哪一种**(真机实测:拿到完整 tracklist
                // 却回了空串 —— 这跟"截断"与"不肯回答"是三种完全不同的病,别用一句猜的话糊过去)
                titleLines == 0 -> buildString {
                    append("AI 联网查到《$albumName》的资料了,但这次**没有输出曲目清单**")
                    append(
                        when {
                            result.finishReason == "length" -> "(回复被截断:输出预算用尽,finish=length)"
                            thinkLen > 0 -> "(它只思考了、正文为空,finish=${result.finishReason ?: "?"})"
                            else -> "(空回复:finish=${result.finishReason ?: "?"},正文与思考都为空)"
                        },
                    )
                    append("。可点重试。")
                }
                else ->
                    "AI 认出了 $titleLines 首,但**写法与本地曲目对不上**(本地 ${currentTitles.size} 首一首都没匹配上)。它需要照抄本地写法——可点重试;反复出现建议换更强模型。"
            }
            // v1.3.14:失败可诊断 —— 记下"命中多少 / 模型这次回了什么",日志被系统抑制时靠它排查
            ctx.recordDiag(
                skill = "album_sort", stage = "parse", ok = false,
                counts = mapOf(
                    "candidates" to currentTitles.size,
                    "titleLines" to titleLines,
                    "ordered" to result.orderedIndices.size,
                    "webSearched" to if (result.webSearched) 1 else 0,
                    // v1.3.15:输出用量与思考长度 —— "被截断"与"只思考不回答"靠这两个数分开
                    "ct" to result.completionTokens,
                    "thinkLen" to thinkLen,
                ),
                replyRaw = result.rawReply,
                errorCode = when {
                    result.webSearched && result.hadUnknown -> "UNKNOWN"
                    !result.webSearched -> "NO_WEB_DATA"
                    titleLines == 0 && result.finishReason == "length" -> "NO_LIST_TRUNCATED"
                    titleLines == 0 -> "NO_LIST"
                    else -> "NO_MATCH"
                },
                errorDetail = msg.replace("**", "").take(150),
                // v1.3.15:把**当时喂给模型的联网资料**一并记下 —— 只有"模型回了什么"是定不了性的,
                // 资料与回复同框才能分辨"联网没给到料"还是"模型拿到了料却不照格式输出"。
                webRaw = result.webSnippet,
            )
            return AgentEngine.SkillResult(msg.replace("**", ""), success = false)
        }
        onStep("writeback", AgentEngine.StepStatus.RUNNING)
        // v1.3.15:模型给的已是**本地曲目名序列**(v1.3.13 起协议从"编号"改为"照抄本地写法",
        // 因为编号格式是弱模型的老坑)→ 照它的顺序写下标即可,零文本匹配。
        ctx.applyAlbumOrderIndices(key, result.orderedIndices)
        onStep("writeback", AgentEngine.StepStatus.DONE)
        val msg = if (result.confidence == AgentService.Confidence.LOW)
            "已按可查到的信息排序(置信度低,建议核对)。"
        else "已按官方顺序排列《$albumName》。"
        ctx.recordDiag(
            skill = "album_sort", stage = "parse", ok = true,
            counts = mapOf("candidates" to currentTitles.size, "ordered" to result.orderedIndices.size),
            // 成功样本也带联网资料:好的一次和坏的一次对比,能看出"资料长什么样时才成功"
            webRaw = result.webSnippet,
        )
        return AgentEngine.SkillResult(msg, needsConfirm = ctx.hasPendingOrder(), thinking = result.thinking)
    }
}

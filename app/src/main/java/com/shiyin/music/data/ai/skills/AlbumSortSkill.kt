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
            // v1.3.3 按实际情况回复,不用"可能专辑太小众"套话——用户每次失败看到
            // 同一句猜测式文案,像 AI 在敷衍。按真实失败点分别说明:
            //  - 有联网资料但解析不出编号:AI 的回答没遵守编号格式(模型没按指令输出)
            //  - 完全无返回/UNKNOWN:AI 判断不了,建议核实专辑名识别
            //  - 无联网资料:搜索环节问题(网络/Key/识别名)
            val msg = when {
                result.webSearched && result.hadUnknown ->
                    "AI 判断《$albumName》曲序时未能确定答案(它识别为 UNKNOWN)。可能专辑名与官方写法不一致——在专辑 ⋮ 里检查专辑名/歌手名识别是否正确后重试。"
                result.webSearched ->
                    "AI 联网查到了《$albumName》的资料,但回答没有按要求的格式输出(未给出可解析的曲目编号)。可点重试;反复出现建议换更强模型(当前模型对编号格式遵守度不稳定)。"
                else ->
                    "联网搜索《$albumName》没有找到相关资料(专辑名/歌手名识别有误、网络不可达或搜索 Key 失效)。先核对专辑名识别是否正确,再重试。"
            }
            return AgentEngine.SkillResult(msg, success = false)
        }
        onStep("writeback", AgentEngine.StepStatus.RUNNING)
        // v1.3.3: AI 输出的是本地编号序列——直接按编号重排,零文本匹配;名字与官方
        // 的差异由 AI 在分析阶段理清对应关系(用户定调:AI 智能决策,不是本地兜底)。
        ctx.applyAlbumOrderIndices(key, result.orderedIndices)
        onStep("writeback", AgentEngine.StepStatus.DONE)
        val msg = if (result.confidence == AgentService.Confidence.LOW)
            "已按可查到的信息排序(置信度低,建议核对)。"
        else "已按官方顺序排列《$albumName》。"
        return AgentEngine.SkillResult(msg, needsConfirm = ctx.hasPendingOrder(), thinking = result.thinking)
    }
}

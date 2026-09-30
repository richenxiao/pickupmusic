package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.10 Agent 技能:开放式选曲("你自己挑一首")。
 *
 * 用户反馈的缺陷:说「你自己挑一首」「根据现在的心情放一首」时,Agent 会反问"你想听什么歌"。
 * 根因是**工具缺口**——模型手里既没有"拿全库候选"的入口,也没有"播我选的那首"的出口。
 *
 * 分工:[AgentLibrarySearch] 给**真实候选**(本地库 + 假名/罗马字/译名兜底),模型**自己选**,
 * [PlayTrackSkill] 落地播放。选择权在模型手里,不在匹配逻辑里。
 *
 * ⚠️ 返回值纪律(踩过坑):工具返回值会进消息流,弱模型会**把返回文本直接当回答吐给用户**。
 * 所以这里只写"能被念出来的人话"(曲名/歌手/专辑),**不写 track_id、不写工具名、不写内部
 * 指示**——用户实测反馈过"把方法名和歌曲id都漏出来了"。连贯下一步的指令写在 description 里
 * (模型看得到但不会复述)。
 */
object DiscoverMusicSkill : AgentEngine.AgentSkill {

    /** 候选上限:给模型挑的小清单,不是给用户挑的菜单(太长会变成"让我选十几首")。 */
    private const val MAX_CANDIDATES = 5

    override val key = "discover_music"
    override val description =
        "开放式选曲:用户**没有点名歌名/歌手**、让你自己挑时用(「你自己挑一首」「根据我现在的心情放一首」" +
            "「随便来首适合现在听的」)。返回库里真实存在的几首候选。" +
            "**调用后你必须自己从中挑一首,并立刻用 play_track 播放**;不要把候选清单当回答直接给用户,更不要反问歌名。"
    override val parameters = """
        {"type":"object","properties":{
          "mood":{"type":"string","description":"用户表达的处境/心情/场景关键词(用原话里的词,如 难受/加班/下雨);没提到就留空"}
        },"required":[]}
    """.trimIndent()

    override fun panelSteps(args: Map<String, String>) =
        listOf(AgentEngine.PanelStep("search", "在库里找候选"))

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        onStep("search", AgentEngine.StepStatus.RUNNING)

        val mood = args["mood"]?.trim().orEmpty()
        val limit = MAX_CANDIDATES
        // 有处境/心情 → 让模型把它联想成"可能出现在曲名里的概念词"再搜库([resolveByMood]);
        // 模型想得对也得库里真有那首歌,才进候选。没有处境才退到"按常听挑"。
        val matched = if (mood.isBlank()) emptyList() else AgentLibrarySearch.resolveByMood(ctx, config, mood, limit)
        val candidates = (matched + AgentLibrarySearch.diverseFallback(ctx, limit))
            .distinctBy { it.id }
            .take(limit)

        if (candidates.isEmpty()) {
            onStep("search", AgentEngine.StepStatus.FAILED)
            return@withContext AgentEngine.SkillResult("音乐库是空的,没有歌可以挑。", success = false)
        }
        onStep("search", AgentEngine.StepStatus.DONE)
        android.util.Log.i("Agent", "discover_music: mood=「$mood」→ 候选 ${candidates.size} 首(直接命中 ${matched.size})")

        // 只回人话(可被直接念出来),不含 id / 工具名 / 内部指示。
        // v1.3.13:同时给出**可点选项** —— 用户定调"列了 A/B/C 还让人打字说歌名很蠢,
        // 应该给按钮点一下就播"。选项文本写成**指令句**(「播放《X》」),点选后那条消息
        // 才能被识别成播放指令、直接落地(否则等于让用户再兜一圈)。
        AgentEngine.SkillResult(
            summary = buildString {
                if (mood.isNotBlank() && matched.isNotEmpty()) appendLine("库里和「$mood」相关的,点一下就直接放:")
                else if (mood.isNotBlank()) appendLine("库里没有直接对应「$mood」的,这几首是平时常听的:")
                else appendLine("这几首是按平时常听挑的:")
                candidates.forEach { appendLine("《${it.title}》 — ${it.artist} · 专辑《${it.album}》") }
            }.trimEnd(),
            options = candidates.take(3).map { "播放《${it.title}》" },
        )
    }
}

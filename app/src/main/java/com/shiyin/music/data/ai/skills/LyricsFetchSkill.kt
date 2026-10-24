package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import com.shiyin.music.data.lyrics.LyricsFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.2.2 Agent 技能③:为当前播放歌曲找歌词。
 *
 * 只读技能——不写库(歌词匹配由现有 matchOnline/loadLyricsFor 管线持久化,本技能
 * 仅触发检索并返回结果摘要给对话面板)。复用 LyricsFetcher(LRCLIB→网易云级联)。
 *
 * 不依赖 LLM/Tavily(纯结构化检索),所以忽略 config/tavilyKey。
 */
object LyricsFetchSkill : AgentEngine.AgentSkill {
    override val key = "lyrics_fetch"
    /**
     * v1.3.10 需求 A27:描述不再把自己说成"找歌词"这件事的**全部**。
     * 旧描述是 `为当前播放的歌曲找歌词(LRCLIB→网易云)`——等于把"找歌词 = 这个技能"
     * 钉死在模型脑子里,内置源搜不到它就只会回"无此资源",不会想到还有其他路
     * (真机实测:"死死限定住,那就是 workflow 不是 agent")。
     */
    override val description =
        "从**内置**歌词源检索当前播放歌曲的歌词。" +
            "注意:这只是来源之一,不是「找歌词」的全部——**内置源都没有时别就此收手**: " +
            "改用 web_search 上网找,找到歌词后用 save_lyrics 落地(可撤销,歌词本里能删/换源)。"
    override val parameters = """{"type":"object","properties":{},"required":[]}"""
    override fun panelSteps(args: Map<String, String>) = listOf(
        AgentEngine.PanelStep("search", "搜索歌词源"),
    )

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        val track = ctx.currentTrack()
        if (track == null) {
            return@withContext AgentEngine.SkillResult("当前没有播放曲目。先播放一首歌再发指令。", success = false)
        }
        onStep("search", AgentEngine.StepStatus.RUNNING)
        // 用 activeSources():关掉的源不查;**没配 Key 的 LrcShare 也不查**
        // (2026-10-24 站方改版后它必须带用户自己的 Key,否则每个请求都是 401)。
        for (src in LyricsFetcher.activeSources()) {
            val raw = runCatching {
                LyricsFetcher.fetch(src, track.title, track.artist, track.album, track.durationSec)
            }.getOrNull()
            if (!raw.isNullOrBlank()) {
                onStep("search", AgentEngine.StepStatus.DONE)
                val hasLrc = raw.contains("[0") || raw.contains("[00:")
                return@withContext AgentEngine.SkillResult(
                    "已从 $src 找到《${track.title}》的歌词${if (hasLrc) "(带时间轴)" else "(纯文本)"}。",
                )
            }
        }
        onStep("search", AgentEngine.StepStatus.DONE)
        AgentEngine.SkillResult("内置歌词源都没有《${track.title}》的歌词。", success = false)
    }
}

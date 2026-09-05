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
    override val description = "为当前播放的歌曲找歌词(LRCLIB→网易云)"

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
        for (src in LyricsFetcher.SOURCES) {
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
        AgentEngine.SkillResult("未在 LRCLIB/网易云找到《${track.title}》的歌词。", success = false)
    }
}

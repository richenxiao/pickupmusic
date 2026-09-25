package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.8 Agent 技能⑩:收听统计(只读)。
 *
 * **只做「本周」**,与统计页口径完全一致——范围一旦扩大(最近一个月/全部时间),
 * 用户在 Agent 这里拿到的数字就跟他打开统计页面看到的不一样了,而口径统一这条规矩
 * 要保护的恰恰是"两个入口的答案是同一件事"。以后真要更宽的范围,再单独加参数。
 *
 * 输出由技能自己拼,**不调 LLM**:统计是确定性数字,让模型转述只会引入改写风险
 * (把 7 次说成 8 次、把"本周"漏掉)。这也是它比 song_story 快得多的原因。
 */
object ListeningStatsSkill : AgentEngine.AgentSkill {
    override val key = "listening_stats"
    override val description =
        "回答**本周**的收听统计:总听歌时长、最常听的歌曲/专辑/歌手。" +
            "用户问「我最近听得最多的是什么」「这周听了多久」「本周最常听谁」时用这个工具。" +
            "统计范围固定为本周(从周一起算),与 App 的收听统计页一致;" +
            "如果用户明确问的是别的时间范围(如最近一个月、全部时间),本工具答不了——不要用它硬答,如实说明只有本周统计。"
    override val parameters = """{"type":"object","properties":{},"required":[]}"""

    override fun panelSteps(args: Map<String, String>) = listOf(
        AgentEngine.PanelStep("read", "读取本周收听统计"),
    )

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        onStep("read", AgentEngine.StepStatus.RUNNING)
        // ⚠️ 不能用裸 runCatching:它捕 Throwable,会把 CancellationException 一起吞掉
        // ——用户点「停止」时这次取消被当成"读取失败"的普通业务错误,协程继续跑完。
        // 项目硬约束:取消必须原样穿透(SongStorySkill.safeCall / AgentTools
        // .runCatchingCancellable / TavilyService 都是这个形状)。
        val s = try {
            ctx.weeklyListeningStats()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (s == null) {
            onStep("read", AgentEngine.StepStatus.FAILED)
            return@withContext AgentEngine.SkillResult("读取收听统计失败,稍后再试。", success = false)
        }
        onStep("read", AgentEngine.StepStatus.DONE)

        if (s.totalSeconds == 0L && s.topTracks.isEmpty() && s.topArtists.isEmpty() && s.topAlbums.isEmpty()) {
            return@withContext AgentEngine.SkillResult(
                "本周还没有有效播放记录(听满 30 秒才算一次)。放几首歌再来问我吧。",
            )
        }

        val parts = mutableListOf<String>()
        if (s.totalSeconds > 0) {
            val h = s.totalSeconds / 3600
            val m = (s.totalSeconds % 3600) / 60
            parts += "本周(周一起)一共听了 ${if (h > 0) "${h} 小时 ${m} 分钟" else "${m} 分钟"}。"
        }
        // 最常听歌曲:mediaId 需要换成标题;已删除的曲目跳过(statistics 表里可能留着)
        s.topTracks.firstOrNull()?.let { (id, plays) ->
            ctx.trackById(id)?.let { t -> parts += "最常听的是《${t.title}》— ${t.artist}($plays 次)。" }
        }
        s.topArtists.firstOrNull()?.let { (name, plays) -> parts += "最常听歌手:$name($plays 次)。" }
        // 专辑键是 albumKey,借 albumTracksOf 拿回专辑显示名(与统计页同一套解析)
        s.topAlbums.firstOrNull()?.let { (key, plays) ->
            ctx.albumTracksOf(key).firstOrNull()?.let { t -> parts += "最常听专辑:《${t.album}》($plays 次循环)。" }
        }

        if (parts.isEmpty()) {
            return@withContext AgentEngine.SkillResult(
                "本周有播放记录,但没有哪首歌/专辑重复听够到能上榜。",
            )
        }
        AgentEngine.SkillResult(parts.joinToString(""))
    }
}

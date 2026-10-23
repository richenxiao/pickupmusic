package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.8 Agent 技能⑪:按官方发布时间排序歌手作品。
 *
 * 补的是**能力空洞**:歌手页早有「AI 排时间」(联网查发行日期→按时间排),但那是一条
 * 只在歌手页菜单里点得到的 vm 函数、**没挂进 Agent 工具表**。用户在对话里说
 * "排一下某歌手的发布日期"时,模型翻遍工具表找不到对应能力,只能抓最近的
 * `library_query`(它的描述里有"歌手"字样)→ 答非所问,再由那个技能的空回复 bug
 * 表现为"列完清单打勾、然后什么都不说"。
 *
 * 落地**完全复用** [MainViewModel.fetchArtistAlbumDates] 的同一实现与同一套作品筛选
 * 规则(经 SkillContext.sortArtistAlbumsByDate 桥),因此 Agent 与歌手页给出的作品集
 * 和顺序必然一致——两个入口说同一件事。
 *
 * 需要联网(Tavily)+ LLM,耗时与歌手页那次一致(约 10–20s)。
 */
object AlbumDatesSkill : AgentEngine.AgentSkill {
    override val key = "album_dates"
    override val description =
        "按官方发布时间给某位歌手的专辑/EP/单曲排序(联网查发行日期后按时间排)。" +
            "用户说「把某歌手的专辑按发布时间排一下」「排一下某歌手的发布日期」「某歌手的作品按时间排」时用这个工具。" +
            "注意:这只做排序,不改专辑名/封面等其它信息;要查某首歌在不在库里请用 library_query。"
    override val parameters = """
        {"type":"object","properties":{"artist":{"type":"string","description":"要排序的歌手名。用户点名了歌手就填;没点名则不要填(默认作用于当前打开的歌手页)"}},"required":[]}
    """.trimIndent()

    override fun panelSteps(args: Map<String, String>) = listOf(
        AgentEngine.PanelStep("fetch", "联网获取发布时间"),
        AgentEngine.PanelStep("write", "按发布时间排序"),
    )

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        // 点名歌手 > 当前歌手页。两个都没有 → 明确引导,不要瞎猜一个歌手去写库。
        val named = args["artist"]?.trim().orEmpty()
        val artist = if (named.isNotBlank()) named else ctx.artistKey.orEmpty()
        if (artist.isBlank()) {
            onStep("fetch", AgentEngine.StepStatus.FAILED)
            onStep("write", AgentEngine.StepStatus.FAILED)
            return@withContext AgentEngine.SkillResult(
                "不知道要排哪位歌手的作品。可以说「把周杰伦的专辑按发布时间排一下」,或者先打开那位歌手的页面。",
                success = false,
            )
        }

        onStep("fetch", AgentEngine.StepStatus.RUNNING)
        val outcome = ctx.sortArtistAlbumsByDate(artist)
        if (outcome.datedCount <= 0) {
            onStep("fetch", AgentEngine.StepStatus.FAILED)
            onStep("write", AgentEngine.StepStatus.FAILED)
            // 原样透传真实原因(未配 Key / LLM 429 / 没搜到资料 / 解析不出日期),
            // 而不是笼统报"失败了"——用户看到的与歌手页状态行是同一句话。
            return@withContext AgentEngine.SkillResult(
                outcome.detail.ifBlank { "没能获取「$artist」的发布时间,可以稍后重试。" },
                success = false,
            )
        }
        onStep("fetch", AgentEngine.StepStatus.DONE)
        onStep("write", AgentEngine.StepStatus.DONE)
        AgentEngine.SkillResult("已按发布时间给「$artist」的 ${outcome.datedCount} 张作品排序,最新的在最上面。")
    }
}

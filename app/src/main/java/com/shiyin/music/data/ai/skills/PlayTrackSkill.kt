package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.10 Agent 技能:播放"已经定下来的那一首"(discover_music 挑完之后落地)。
 *
 * ⚠️ 参数用**歌名(+歌手)**,不用 track_id——这是刻意的设计决定:
 * 工具返回值会进入消息流,弱模型(实测 sensenova-flash-lite 这类)不会稳定地串第二次
 * 工具调用,而是**把工具返回的文本当回答直接吐给用户**。所以返回值里一旦出现
 * `track_id=123`、"用 play_track(...) 播放"这类机器文本,用户就会在聊天里看到它们
 * (真机实测反馈:"把方法名和歌曲id都漏出来了")。
 * → 结论:工具参数与返回值只出现**人话**;id 这类内部标识留在工具内部,不进消息流。
 *
 * 名字解析走 [AgentLibrarySearch](FuzzySearch + 假名/罗马字 + 译名兜底),同样不需要
 * 使用场景得多准。只操作播放状态,不碰音乐库内容。
 */
object PlayTrackSkill : AgentEngine.AgentSkill {

    override val key = "play_track"
    override val description =
        "播放一首已经选定的曲目(通常是从 discover_music 的候选里挑出来的那一首)。" +
            "参数用歌名;有同名曲目时补歌手名区分。歌名不必说得很准,别名/译名/罗马字/片段都能匹配。"
    override val parameters = """
        {"type":"object","properties":{
          "title":{"type":"string","description":"要播放的曲目名"},
          "artist":{"type":"string","description":"可选:歌手名,用于在多个同名曲目里选对"}
        },"required":["title"]}
    """.trimIndent()

    override fun panelSteps(args: Map<String, String>) =
        listOf(AgentEngine.PanelStep("apply", "播放选中的这首"))

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        onStep("apply", AgentEngine.StepStatus.RUNNING)
        fun fail(msg: String): AgentEngine.SkillResult {
            onStep("apply", AgentEngine.StepStatus.FAILED)
            return AgentEngine.SkillResult(msg, success = false)
        }

        val title = args["title"]?.trim().orEmpty()
        if (title.isBlank()) return@withContext fail("没听清要放哪一首,再说一次歌名?")
        // 控制器未连接时播放是**无声 no-op**,不前置拦下就会回"开始播放"却听不到声音。
        if (!ctx.playerReady()) return@withContext fail("播放器还没准备好(后台服务未连接),过一会儿再说一次试试。")

        val artist = args["artist"]?.trim().orEmpty()
        val hits = AgentLibrarySearch.resolve(ctx, config, "$title $artist".trim(), 10)
        if (hits.isEmpty()) return@withContext fail("库里没找到《$title》,我重新挑一首?")
        val titleKey = com.shiyin.music.data.search.FuzzySearch.normalize(title)
        val artistKey = com.shiyin.music.data.search.FuzzySearch.normalize(artist)
        val pick = hits.firstOrNull {
            com.shiyin.music.data.search.FuzzySearch.normalize(it.title) == titleKey &&
                (artistKey.isBlank() || com.shiyin.music.data.search.FuzzySearch.normalize(it.artist).contains(artistKey))
        } ?: hits.firstOrNull { com.shiyin.music.data.search.FuzzySearch.normalize(it.title) == titleKey }
        ?: hits.first()

        ctx.playTracks(listOf(pick), pick.id)
        android.util.Log.i("Agent", "play_track: 《${pick.title}》— ${pick.artist} (id=${pick.id})")
        onStep("apply", AgentEngine.StepStatus.DONE)
        AgentEngine.SkillResult("正在播放《${pick.title}》— ${pick.artist}。")
    }
}

package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.6 Agent 技能⑤:音乐库查询——**回答"库里有没有/在哪个专辑"这类询问**。
 *
 * v1.3.10 两处调整:
 *  1. 检索走 [AgentLibrarySearch]:FuzzySearch(全半角/繁简/编辑距离)+ 假名↔罗马字 +
 *     译名/别名写法兜底。旧实现是 `lowercase().filter{isLetterOrDigit()}.contains()`,
 *     搜不准是它的问题(用户按罗马字/译名/少打一个字都搜不到)。
 *  2. **返回值只写人话**:曲名/歌手/专辑。旧版本(以及本版第一稿)把 `track_id`、
 *     "用 play_track(...) 播放"这类机器文本放进返回值,弱模型会**把工具返回直接当回答
 *     念给用户**——用户实测看到"方法名和歌曲 id"就是这个原因。下一步指令只留在
 *     description 里(模型看得到、不会复述)。
 *
 * 边界:用户是要**听**就用 playback_control(一次调用完成查找+播放),不要用本工具。
 */
object LibraryQuerySkill : AgentEngine.AgentSkill {

    override val key = "library_query"
    override val description =
        "查询本地音乐库:回答「库里有没有某首歌/某歌手的歌/某专辑」「在哪个专辑里」这类**询问**。" +
            "用户聊到具体歌曲、歌手、专辑是否存在时用它真查,不要凭记忆猜;" +
            "歌名不必说准(别名/译名/罗马字/片段都能匹配)。" +
            "⚠️ 如果用户是想**听**这首歌(「放一下X」),直接用 playback_control(它一次调用就会开始播放),不要用本工具。"
    override val parameters = """
        {"type":"object","properties":{"query":{"type":"string","description":"要查找的歌名/歌手名/专辑名,用用户说的原样文字填写"}},"required":["query"]}
    """.trimIndent()

    override fun panelSteps(args: Map<String, String>) = listOf(
        AgentEngine.PanelStep("search", "检索音乐库"),
    )

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        val query = args["query"]?.takeIf { it.isNotBlank() }
            ?: return@withContext AgentEngine.SkillResult("你想找哪首歌/哪个歌手?可以直接说歌名。", success = false)

        onStep("search", AgentEngine.StepStatus.RUNNING)
        val tracks = AgentLibrarySearch.resolve(ctx, config, query, 8)
        val artists = ctx.searchArtists(query, 5)
        if (tracks.isEmpty() && artists.isEmpty()) {
            onStep("search", AgentEngine.StepStatus.DONE)
            return@withContext AgentEngine.SkillResult(
                "库里没有「$query」相关的歌曲或歌手(别名、译名、罗马字、错别字都试过了)。",
            )
        }
        onStep("search", AgentEngine.StepStatus.DONE)

        // 只回人话:可被直接念给用户,不含 id / 工具名 / 内部指示。
        AgentEngine.SkillResult(buildString {
            if (tracks.isNotEmpty()) {
                appendLine("库里找到这些(按最常听排序):")
                tracks.forEach { appendLine("《${it.title}》 — ${it.artist} · 专辑《${it.album}》") }
            } else {
                appendLine("没有同名的曲目。")
            }
            if (artists.isNotEmpty()) append("库里有这些歌手:" + artists.joinToString("、"))
        }.trimEnd())
    }
}

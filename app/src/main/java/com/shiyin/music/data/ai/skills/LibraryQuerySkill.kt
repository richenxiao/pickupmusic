package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.6 Agent 技能⑤:音乐库查询(用户定调——聊库里的歌,模型必须真查,不能瞎编
 * "我不能调用音乐库";此前技能清单里根本没有查询类技能,模型说实话反而被骂)。
 *
 * 场景:"库里有没有XX这首歌?"/"帮我找一下XX唱的歌"/"音乐库里有哪些XX的专辑"。
 * 流程:①按 args.query 全库模糊搜(标题/歌手/专辑,规范化 contains,播放次数排序)
 *      ②把命中曲目列表交 LLM 组织成自然回答(它只做"把检索结果说成人话",
 *        不做编造——prompt 明确"列表之外的信息不要编")
 * 无 args.query(模型没抄)时回退用户原话——args 注入见 MainViewModel 调度处。
 */
object LibraryQuerySkill : AgentEngine.AgentSkill {
    override val key = "library_query"
    override val description = "查询本地音乐库(库里有没有某首歌/某歌手的歌/某专辑——把 args.query 填成要找的歌名/歌手名/专辑名,返回真实检索结果);用户聊到具体歌曲/歌手是否存在时必须用它查,不要凭记忆猜"

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        val query = args["query"]?.takeIf { it.isNotBlank() }
            ?: return@withContext AgentEngine.SkillResult("你想找哪首歌/哪个歌手?可以直接说歌名。", success = false)

        // ① 全库检索(纯本地,零网络)
        val tracks = ctx.searchTracks(query, 12)
        val artists = ctx.searchArtists(query, 5)
        if (tracks.isEmpty() && artists.isEmpty()) {
            onStep("search", AgentEngine.StepStatus.DONE)
            return@withContext AgentEngine.SkillResult(
                "音乐库里没有找到「$query」相关的歌曲或歌手(可能是识别名有偏差,换个写法试试)。",
            )
        }
        onStep("search", AgentEngine.StepStatus.DONE)

        // ② 命中列表 → LLM 组织自然回答(检索已按播放次数排序,首位即最常听)
        onStep("answer", AgentEngine.StepStatus.RUNNING)
        val trackBlock = tracks.joinToString("\n") { "· ${it.title} — ${it.artist}(专辑《${it.album}》)" }
        val artistBlock = artists.joinToString("、")
        val foundPart = if (tracks.isNotEmpty()) "本地音乐库检索结果(按播放次数排序):\n$trackBlock" else "没有直接命中的歌曲。"
        val artistPart = if (artists.isNotEmpty()) "\n命中的歌手:$artistBlock" else ""
        val prompt = """
            用户在音乐播放器里问:「$query」。
            $foundPart$artistPart

            用朋友口吻把结果告诉用户,简洁中文 1-3 句:库里有没有、最常听的是哪首、在哪个专辑。
            只基于上面的检索结果回答,结果之外的信息(比如这首歌是谁写的)不要编。
        """.trimIndent()
        val resp = com.shiyin.music.data.ai.LlmClient.call(config, listOf("user" to prompt), temperature = 0.3, maxTokens = 1024)
        onStep("answer", if (resp == null) AgentEngine.StepStatus.FAILED else AgentEngine.StepStatus.DONE)
        if (resp == null) {
            // LLM 挂了也把检索结果直给用户(核心价值在检索,不在润色)
            return@withContext AgentEngine.SkillResult(
                "库里找到 ${tracks.size} 首「$query」相关:\n" + trackBlock,
            )
        }
        resp.let { ctx.logUsage(it.promptTokens, it.completionTokens, it.cacheTokens) }
        AgentEngine.SkillResult(resp.content?.trim() ?: trackBlock, thinking = resp.thinking)
    }
}

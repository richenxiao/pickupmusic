package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.AgentService
import com.shiyin.music.data.ai.LlmClient
import com.shiyin.music.data.ai.LlmProviderConfig
import com.shiyin.music.data.ai.TavilyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * v1.3.8 Agent 技能⑦:歌曲背景故事 / 含义解析。
 *
 * 与 `web_search` 工具的区别(这是本技能存在的理由):
 *  - web_search 把**用户整句话**丢给搜索引擎搜一次,适合"帮我查一下 X 是谁"这类定点事实;
 *  - 本技能针对"讲讲这首歌的故事""这首歌是什么意思"这类**开放式了解**需求,内部
 *    **规划多个搜索角度并发搜**(创作背景 / 歌词含义 / 幕后),再把资料**整理成一段
 *    连贯介绍**,而不是把几段搜索原文拼接回给用户。
 *
 * 三个角度是固定模板(定稿):零额外 LLM 往返,总耗时 = max(三个并发查询),
 * 与 AgentService.fetchOfficialTrackOrder 的主/备并发同一模式。关键词按曲目语言切换
 * (中/日/英)——对日语曲目用中文关键词是搜不到东西的,这条 AgentService 已踩过。
 *
 * 资料不足时**明说没找到,不用模型自身知识编**(拍板)——与 album_sort /
 * track_rename 的诚实提示一致。
 */
object SongStorySkill : AgentEngine.AgentSkill {
    override val key = "song_story"
    override val description =
        "讲一首歌的背景故事与含义解析(创作背景、歌词在讲什么、幕后花絮),会联网收集多个角度的资料再整理成一段介绍。" +
            "用户说「讲讲这首歌的故事」「这首歌是什么意思」「这首歌的来历」时用这个工具。" +
            "只讲背景与含义,不提供歌词原文;要查某首歌是否在库里请用 library_query。"
    override val parameters = """
        {"type":"object","properties":{"song":{"type":"string","description":"要讲哪首歌。用户点名了某首歌时填歌名;没点名则不要填(默认讲当前正在播放的歌)"}},"required":[]}
    """.trimIndent()

    override fun panelSteps(args: Map<String, String>) = listOf(
        AgentEngine.PanelStep("search", "联网收集资料"),
        AgentEngine.PanelStep("compose", "整理成介绍"),
    )

    /** 每个角度最多贡献多少字进 prompt——三个角度合计控制在 6000 字内,防 prompt 爆掉。 */
    private const val PER_ANGLE_CHARS = 2000

    /**
     * 固定 3 角度 × 曲目语言。抽成独立函数便于单测(角度选错是静默失败:
     * 日语曲目用中文关键词搜,只会拿到空资料然后误报"没搜到")。
     */
    internal fun searchQueriesFor(title: String, artist: String): List<String> {
        val by = if (artist.isBlank()) title else "$title $artist"
        return when (AgentService.detectLanguage(by)) {
            "ja" -> listOf(
                "$by 楽曲 制作 背景",
                "$by 歌詞 意味 解釈",
                "$by 楽曲 エピソード 裏話",
            )
            "zh" -> listOf(
                "$by 创作背景 创作故事",
                "$by 歌词含义 解读",
                "$by 幕后 花絮 制作",
            )
            else -> listOf(
                "$by song background story making of",
                "$by lyrics meaning interpretation",
                "$by behind the scenes recording",
            )
        }
    }

    /** 取消必须原样穿透(项目硬约束:吞掉会让"已停止"的任务继续跑完)。 */
    private inline fun <T> safeCall(block: () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        // 1. 定曲目:点名 → 库里找(拿识别后的标题/歌手,查询词更准);没点名 → 当前播放。
        val named = args["song"]?.trim().orEmpty()
        val track = if (named.isNotBlank()) ctx.searchTracks(named, 1).firstOrNull() else ctx.currentTrack()
        // 点名了但库里没有时,退回用用户给的名字搜(库外的歌也能讲)
        val title = track?.title?.takeIf { it.isNotBlank() } ?: named
        val artist = track?.artist.orEmpty()
        if (title.isBlank()) {
            onStep("search", AgentEngine.StepStatus.FAILED)
            onStep("compose", AgentEngine.StepStatus.FAILED)
            return@withContext AgentEngine.SkillResult(
                "不知道要讲哪首歌。先播放一首歌再说「讲讲这首歌的故事」,或者点名:「讲讲《歌名》的故事」。",
                success = false,
            )
        }
        val display = if (artist.isBlank()) "《$title》" else "《$title》($artist)"

        // 2. 三个角度并发搜。总耗时 = max(三个),不是三者之和。
        onStep("search", AgentEngine.StepStatus.RUNNING)
        val queries = searchQueriesFor(title, artist)
        val contexts = coroutineScope {
            queries.map { q -> async { safeCall { TavilyService.search(tavilyKey, q, includeAnswer = true) } } }
                .map { it.await() }
        }
        val useful = contexts.filter { !it.isNullOrBlank() }
        if (useful.isEmpty()) {
            LlmClient.clearLastError()  // 空资料是业务失败,别把上一轮残留的 LLM 错误显示成原因
            onStep("search", AgentEngine.StepStatus.FAILED)
            onStep("compose", AgentEngine.StepStatus.FAILED)
            // 拍板:明说没找到,不用模型自身知识编
            return@withContext AgentEngine.SkillResult(
                if (tavilyKey.isBlank())
                    "没有配置联网搜索,讲不了 $display 的背景故事(右上「设置」里可以填 Tavily Key)。"
                else
                    "没搜到 $display 的背景资料(可能是冷门曲目、歌名或歌手识别有偏差、网络不可达)。" +
                        "可以试试核对曲目信息,或换个说法。",
                success = false,
            )
        }
        onStep("search", AgentEngine.StepStatus.DONE)

        // 3. 整理成连贯介绍(不是拼接原文)。
        onStep("compose", AgentEngine.StepStatus.RUNNING)
        val materials = useful.joinToString("\n\n---\n\n") { it!!.take(PER_ANGLE_CHARS) }
        val prompt = """
            用户想了解 $display 这首歌。下面是联网搜到的资料(来自不同角度,可能有重复或无关内容):

            $materials

            请把这些资料整理成一段连贯的介绍,要求:
            1. 2-3 个自然段,每段讲一个角度(如创作背景 / 歌词在讲什么 / 幕后或流传),全篇 150-250 字
            2. 用像朋友讲歌那样自然的中文,不要"根据资料""综上所述""以下是"这类书面腔,不要罗列条目、不要小标题
            3. 只写资料支持的内容;资料没覆盖的角度直接略过,不要为了凑结构而编造
            4. 不要复述歌词原文,只说歌词在讲什么
            5. 直接输出介绍正文,不要开场白、不要结尾客套
        """.trimIndent()
        // temperature 略高(0.4):这是散文型输出,不是结构化抽取,太低温会写得像词条
        val resp = LlmClient.call(config, listOf("user" to prompt), temperature = 0.4, maxTokens = 1024)
        if (resp == null) {
            onStep("compose", AgentEngine.StepStatus.FAILED)
            val err = LlmClient.lastError ?: "未知错误"
            return@withContext AgentEngine.SkillResult("整理了资料但生成介绍失败:$err", success = false)
        }
        ctx.logUsage(resp.promptTokens, resp.completionTokens, resp.cacheTokens)
        // 推理模型可能把正文全写进思考链、content 为空。按项目既定约定(v1.3.6c)不拿
        // reasoning 冒充回答——思考链只在折叠块里展示,这里如实报失败。
        val body = resp.content?.trim().orEmpty()
        if (body.isBlank()) {
            onStep("compose", AgentEngine.StepStatus.FAILED)
            return@withContext AgentEngine.SkillResult(
                "资料拿到了,但模型这次没输出正文(可能思考过长被截断)。换更强的模型或重试一次。",
                success = false, thinking = resp.thinking,
            )
        }
        onStep("compose", AgentEngine.StepStatus.DONE)
        AgentEngine.SkillResult(body, thinking = resp.thinking)
    }
}

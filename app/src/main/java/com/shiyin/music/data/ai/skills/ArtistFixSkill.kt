package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmClient
import com.shiyin.music.data.ai.LlmProviderConfig
import com.shiyin.music.data.ai.TavilyService
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.2.2 Agent 技能②:歌手名/歌曲名识别修正。
 *
 * 现象:文件元数据里歌手名识别反了(顺序颠倒/张冠李戴),用 LLM 联网搜证后校正。
 *
 * 流程:
 *  ①搜证: Tavily 搜该专辑/曲目对应的官方歌手名
 *  ②分析: LLM 据 Tavily 摘要 + 本地曲目列表,产出每首歌的修正后歌手(JSON)
 *  ③写回: 调 vm.saveTrackInfo(mediaId, title, correctedArtist)——只碰
 *    track_info_override(单曲粒度),**绝不碰 artist_aliases 全局表**(避免连锁合并)。
 *
 * 信息不足 → 不写,返回提示。LLM 可能判错 → 写回走单曲粒度,用户可在曲目 ⋮ 手动改回。
 */
object ArtistFixSkill : AgentEngine.AgentSkill {
    override val key = "artist_fix"
    override val description = "修正专辑/曲目的歌手名识别错误(联网搜证后校正单曲歌手字段);指令里可点名任意专辑名,不限当前页"

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        // 解析目标专辑:点名专辑名 → 按名搜库;否则当前专辑页。
        val named = args["album"]
        val srcKey = when {
            !named.isNullOrBlank() -> ctx.findAlbum(named) ?: return@withContext AgentEngine.SkillResult(
                "音乐库里没有找到名为「$named」的专辑。请确认专辑名写法,或打开那张专辑后再发指令。",
                success = false,
            )
            else -> ctx.albumKey
        }
        val tracks = if (srcKey != null) ctx.albumTracksOf(srcKey) else emptyList()
        if (tracks.isEmpty()) {
            return@withContext AgentEngine.SkillResult("没有指定专辑,无法修正。可以说「《专辑名》歌手名识别反了」,或先打开某张专辑。", success = false)
        }
        val albumName = tracks.first().album
        val artistName = args["artist"] ?: tracks.first().artist

        onStep("search", AgentEngine.StepStatus.RUNNING)
        val tavilyCtx = runCatching {
            TavilyService.search(tavilyKey, "$albumName $artistName 专辑 歌手 artist")
        }.getOrNull()
        // v1.3.5: search 必须落终态——旧版这里直接进 analyze,search 行永远停在
        // RUNNING(EqBars 无限滚),用户看到"联网搜证一直在转、不知道卡没卡"。
        // 空结果是"没搜到资料"的业务失败,不是 LLM 错误——清掉残留 lastError。
        if (tavilyCtx.isNullOrBlank()) LlmClient.clearLastError()
        onStep("search", if (tavilyCtx.isNullOrBlank()) AgentEngine.StepStatus.FAILED else AgentEngine.StepStatus.DONE)

        onStep("analyze", AgentEngine.StepStatus.RUNNING)
        // 把 mediaId 一起喂给 LLM:写回按 id 匹配,不给 id 模型只能瞎编,会导致一条都匹配不上。
        val trackList = tracks.joinToString(", ") { "${it.id}:「${it.title}→${it.artist}」" }
        val ctxBlock = if (tavilyCtx.isNullOrBlank()) "(未搜到网络信息)" else "搜索信息:\n$tavilyCtx"
        val prompt = """
            你是音乐助手。下面这张专辑的本地曲目歌手名可能识别有误(如顺序颠倒/张冠李戴)。
            专辑:$albumName  本地识别的歌手:$artistName
            本地曲目(id:标题→当前歌手):$trackList

            $ctxBlock

            要求:
            1. 根据 $albumName $artistName 的官方信息,为需要修正歌手的歌给出正确歌手名
            2. 信息不足无法确定时,对那首歌不返回(保持原歌手名,不要瞎猜)
            3. mediaId 必须取上面列表里该曲目前的数字 id,不要编造别的值
            4. 只返回 JSON: {"fixes":[{"mediaId":<列表里的id>,"artist":"正确歌手名"}]}
            5. 不要多余文字
        """.trimIndent()
        val resp = LlmClient.call(config, listOf("user" to prompt)) ?: run {
            // v1.3.5: 失败时 analyze+writeback 都落终态——留一个 ○ 在面板上对用户
            // 就是"还在跑"的假信号。
            onStep("analyze", AgentEngine.StepStatus.FAILED)
            onStep("writeback", AgentEngine.StepStatus.FAILED)
            return@withContext AgentEngine.SkillResult("未能识别,请检查网络或 LLM API Key。", success = false)
        }
        ctx.logUsage(resp.promptTokens, resp.completionTokens, resp.cacheTokens)
        val fixes = parseFixes(resp.content ?: "")
        if (fixes.isEmpty()) {
            // v1.3.5: "无需修正"路径 writeback 也要标 DONE(流程完整走完,只是没东西
            // 可写)——旧版留 ○,用户以为还没做完。
            onStep("analyze", AgentEngine.StepStatus.DONE)
            onStep("writeback", AgentEngine.StepStatus.DONE)
            return@withContext AgentEngine.SkillResult("未找到足够信息,无法确定《$albumName》的正确歌手归属。")
        }

        onStep("writeback", AgentEngine.StepStatus.RUNNING)
        var applied = 0
        for (f in fixes) {
            val t = tracks.firstOrNull { it.id == f.first } ?: continue
            if (f.second.isNotBlank() && f.second != t.artist) {
                ctx.saveTrackInfo(t.id, t.title, f.second)
                applied++
            }
        }
        onStep("writeback", AgentEngine.StepStatus.DONE)
        if (applied == 0) {
            AgentEngine.SkillResult("未找到需要修正的歌手名。")
        } else {
            AgentEngine.SkillResult("已修正 $applied 首曲目的歌手名。可在曲目 ⋮ 菜单核对/改回。")
        }
    }

    private fun parseFixes(raw: String): List<Pair<Long, String>> = try {
        val direct = runCatching { JsonParser.parseString(raw.trim()).asJsonObject }.getOrNull()
        val obj = direct ?: run {
            val m = Regex("""\{.*}""", RegexOption.DOT_MATCHES_ALL).find(raw) ?: return emptyList()
            runCatching { JsonParser.parseString(m.value).asJsonObject }.getOrNull()
        } ?: return emptyList()
        val arr = obj.getAsJsonArray("fixes") ?: return emptyList()
        arr.mapNotNull { el ->
            val o = el?.asJsonObject ?: return@mapNotNull null
            val id = o.get("mediaId")?.asLong ?: return@mapNotNull null
            val a = o.get("artist")?.asString ?: return@mapNotNull null
            id to a
        }
    } catch (_: Exception) { emptyList() }
}

package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.MediaScanner
import com.shiyin.music.data.Track
import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.Fit
import com.shiyin.music.data.ai.LlmClient
import com.shiyin.music.data.ai.LlmProviderConfig
import com.shiyin.music.data.ai.PickOutcome
import com.shiyin.music.data.ai.PoolTrack
import com.shiyin.music.data.ai.RecCandidate
import com.shiyin.music.data.ai.RecommendationPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.15 起:按**场景/歌手/专辑**挑歌(重构自 v1.3.10 的"开放式选曲")。
 *
 * 真机缺陷(用户报):
 *  ① 「推荐方大同适合睡觉的歌」→ 推荐列表里一首方大同都没有(歌手限定没生效);
 *  ② 纠正"我要的是方大同的"→ 它又给了一张**按热度**排的表(静默降级成热度排序);
 *  ③ 最后说"直接推荐"→ 才问要不要播,但**没写播的是哪首**。
 *
 * 分工(用户定调,别搞反):
 *  - **硬约束(歌手/专辑)由代码保证** —— 候选池只在该歌手/该专辑范围内取,不靠模型自觉;
 *    歌手名先经 [RecommendationPlan.resolveArtists] 解析(繁简/大小写/别名),库内没有就
 *    如实说"没有",**绝不拿别的歌手顶上**;
 *  - **软约束(适不适合这个场景)由模型判断** —— 代码只把候选递过去、校验它给的序号;
 *  - **降级必须显式** —— 挑不出贴合的就说清楚"这几首是按最常听给的",而不是假装那是推荐。
 *
 * 没有场景(只给了歌手/专辑)时**不调模型**:那种情况下模型没有任何可判断的信息,
 * 调它只会加延迟并可能编出理由 —— 直接给最常听的即可(确定性,零成本)。
 *
 * ⚠️ 返回值纪律(踩过坑):工具返回值会进消息流,弱模型会把返回文本直接当回答吐给用户,
 * 所以只写"能被念出来的人话"(曲名/歌手),不写 track_id / 工具名 / 内部指示。
 */
object DiscoverMusicSkill : AgentEngine.AgentSkill {

    /** 可点选项条数上限(= 卡片能显示的条数,与 clarify 的 5 一致)。 */
    private val MAX_PICKS = RecommendationPlan.MAX_PICKS

    override val key = "discover_music"

    /**
     * ⚠️ 参数名必须写在**描述正文**里:回退链路(供应商不支持原生 tool calling 时)给模型的
     * 技能清单只有「key: description」,不含 parameters schema —— 不写进描述,模型就不知道
     * 有 artist/scene/album 这几个槽位,硬约束在回退链路下必然失效。
     */
    override val description =
        "按场景/歌手/专辑**挑几首**推荐(用户没有点名具体歌名时用:「推荐适合睡觉的歌」「方大同有没有」" +
            "「放点适合跑步的」)。参数(**至少给一个**):scene=场景/心情/处境(睡觉、跑步、加班、下雨," +
            "用用户原话里的词)、artist=歌手名、album=专辑名。给了 scene 按契合度挑;只给 artist/album " +
            "则给该范围内最常听的。**挑出的歌要给可点选项让用户一点即播,不要反问歌名。**"

    override val parameters = """
        {"type":"object","properties":{
          "scene":{"type":"string","description":"场景/心情/处境关键词,用用户原话里的词(睡觉/跑步/加班/下雨)。没提到就留空"},
          "artist":{"type":"string","description":"用户点名的歌手名(如 方大同)。没点名就留空"},
          "album":{"type":"string","description":"用户点名的专辑名。没点名就留空"},
          "mood":{"type":"string","description":"旧参数名,含义等同 scene(兼容保留)"}
        },"required":[]}
    """.trimIndent()

    /**
     * 面板步骤。**步骤 id 必须与 [execute] 里 onStep 用的 id 一致**(有单测钉住)。
     * "pick" 只在有场景时声明 —— 没有场景压根不调模型,不该在面板上挂一行假的"正在挑"。
     */
    override fun panelSteps(args: Map<String, String>) = buildList {
        add(AgentEngine.PanelStep("resolve", "确认歌手/专辑"))
        add(AgentEngine.PanelStep("pool", "取候选"))
        if (!(args["scene"] ?: args["mood"]).isNullOrBlank()) {
            add(AgentEngine.PanelStep("pick", "看哪几首贴合"))
        }
    }

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        // ── 参数:scene 是主参数,旧的 mood 当 scene 处理(兼容既有调用与模型习惯)──
        val sceneArg = (args["scene"] ?: args["mood"])?.trim().orEmpty()
        val artistArg = args["artist"]?.trim()?.takeIf { it.isNotBlank() }
        val albumArg = args["album"]?.trim()?.takeIf { it.isNotBlank() }
        if (sceneArg.isBlank() && artistArg == null && albumArg == null) {
            onStep("resolve", AgentEngine.StepStatus.FAILED)
            ctx.recordDiag("discover_music", "args", ok = false, errorCode = "MISSING_ARGS")
            return@withContext AgentEngine.SkillResult(
                "我还不知道你想听什么样的 —— 告诉我是谁的、哪张专辑,或者什么场景(睡觉/跑步/下雨)?",
                success = false,
            )
        }

        // ── 槽位合并:本轮为准;「只补一句『方大同的』」时由代码继承上一轮场景 ──
        onStep("resolve", AgentEngine.StepStatus.RUNNING)
        val rc = ctx.recTurnContext()
        val merged = RecommendationPlan.mergeIntent(
            prev = rc.prev, turnIndex = rc.turnIndex, nowMs = rc.nowMs,
            userMessage = rc.userMessage, scene = sceneArg.ifBlank { null },
            artist = artistArg, album = albumArg,
        )
        val all = ctx.listAllTracks()
        if (all.isEmpty()) {
            onStep("resolve", AgentEngine.StepStatus.FAILED)
            return@withContext AgentEngine.SkillResult("音乐库是空的,没有歌可以挑。", success = false)
        }

        // ── ① 歌手解析(硬约束的入口)──
        val libraryArtists = all.flatMap { MediaScanner.splitArtists(it.artist) }
            .filter { it.isNotBlank() }.distinct()
        val resolution = RecommendationPlan.resolveArtists(
            modelArtist = merged.artist, userMessage = rc.userMessage, libraryArtists = libraryArtists,
        )
        ctx.recordDiag(
            skill = "discover_music", stage = "artist_resolve",
            ok = resolution.unresolved == null,
            counts = mapOf(
                "library" to libraryArtists.size,
                "resolved" to resolution.artists.size,
                "suggestions" to resolution.suggestions.size,
            ),
            errorCode = if (resolution.unresolved != null) "UNRESOLVED_ARTIST" else null,
            errorDetail = resolution.unresolved,
        )
        // 用户点了名但库里没有这个歌手 → **如实说 + 给近似建议**,不拿别的歌手顶上
        if (resolution.unresolved != null) {
            onStep("resolve", AgentEngine.StepStatus.FAILED)
            val u = resolution.unresolved
            return@withContext AgentEngine.SkillResult(
                summary = buildString {
                    append("库里没有「$u」这位歌手。")
                    if (resolution.suggestions.isNotEmpty()) append("你是想找:${resolution.suggestions.joinToString("、")} 吗?")
                },
                options = resolution.suggestions.map { "推荐${it}的歌" },
                success = false,
            )
        }
        val artistNames = resolution.artists.map { it.name }.toHashSet()
        val artistLabel = if (artistNames.isEmpty()) null else artistNames.joinToString("、")
        onStep("resolve", AgentEngine.StepStatus.DONE)

        // ── ② 候选池:硬约束在代码里落地(严格限定在该歌手/该专辑范围内)──
        onStep("pool", AgentEngine.StepStatus.RUNNING)
        val albumKey = albumArg?.let { ctx.findAlbum(it) }
        if (albumArg != null && albumKey == null) {
            onStep("pool", AgentEngine.StepStatus.FAILED)
            ctx.recordDiag("discover_music", "pool", ok = false, errorCode = "ALBUM_NOT_FOUND", errorDetail = albumArg)
            return@withContext AgentEngine.SkillResult("库里没有找到「$albumArg」这张专辑。", success = false)
        }
        val base: List<Track> = if (albumKey != null) ctx.albumTracksOf(albumKey) else all
        // 播放热度:listAllTracks 已按最常听排序,用位次换算成递减的 playCount
        // (候选池的"最常听优先/保底"逻辑据此工作),不为它再加一条桥。
        val rank = HashMap<Long, Int>(all.size)
        all.forEachIndexed { i, t -> rank[t.id] = all.size - i }
        val poolTracks = base.asSequence()
            .filter { artistNames.isEmpty() || MediaScanner.splitArtists(it.artist).any { a -> a in artistNames } }
            .map { PoolTrack(it.id, it.title, it.artist, it.album, playCount = rank[it.id] ?: 0) }
            .toList()
        val policy = RecommendationPlan.poolPolicy(hasEntity = artistNames.isNotEmpty() || albumKey != null)
        val pool = RecommendationPlan.buildCandidatePool(poolTracks, policy.cap, policy.perArtistCap)
        ctx.recordDiag(
            skill = "discover_music", stage = "pool", ok = pool.isNotEmpty(),
            counts = mapOf(
                "base" to base.size, "pool" to pool.size,
                "restricted" to if (policy.perArtistCap == null) 1 else 0,
            ),
            errorCode = if (pool.isEmpty()) "EMPTY_POOL" else null,
        )
        if (pool.isEmpty()) {
            onStep("pool", AgentEngine.StepStatus.FAILED)
            val who = listOfNotNull(artistLabel?.let { "「$it」" }, albumArg?.let { "《$it》" }).joinToString("")
            return@withContext AgentEngine.SkillResult("${who.ifBlank { "按这个条件" }}库里没有找到可推荐的歌。", success = false)
        }
        onStep("pool", AgentEngine.StepStatus.DONE)
        // 记下本轮槽位(含代码继承来的场景):下一轮只补一句「方大同的」时接得上
        ctx.recordRecIntent(merged.scene, merged.artist, merged.album)

        // ── ③ 没有场景 → 不需要模型判断:给该范围内最常听的(确定性,不编理由)──
        val scene = merged.scene
        if (scene == null) {
            return@withContext pickResult(
                header = plainHeader(artistLabel, albumArg),
                items = pool.take(MAX_PICKS),
            )
        }

        // ── ④ 有场景 → 交模型判断契合度(它给序号,合法性由代码校验)──
        onStep("pick", AgentEngine.StepStatus.RUNNING)
        val prompt = buildPickPrompt(scene, rc.userMessage, pool, artistLabel, albumArg)
        var raw = callModel(ctx, config, prompt)
        var outcome = RecommendationPlan.parsePickResult(raw.orEmpty(), pool.size)
        ctx.recordDiag(
            skill = "discover_music", stage = "pick_parse", ok = outcome is PickOutcome.Ok,
            counts = mapOf("candidates" to pool.size, "picks" to ((outcome as? PickOutcome.Ok)?.picks?.size ?: 0)),
            replyRaw = raw, errorCode = (outcome as? PickOutcome.Fail)?.reason,
        )
        if (outcome is PickOutcome.Fail) {
            // 重试一次:解析失败/输出不成形时,明确要求"只输出 JSON"再问一遍
            raw = callModel(ctx, config, "$prompt\n\n上一次你的输出无法解析。这次**只输出那一个 JSON 对象**,不要任何解释、不要围栏。")
            outcome = RecommendationPlan.parsePickResult(raw.orEmpty(), pool.size)
            ctx.recordDiag(
                skill = "discover_music", stage = "pick_parse", ok = outcome is PickOutcome.Ok,
                counts = mapOf("retry" to 1, "picks" to ((outcome as? PickOutcome.Ok)?.picks?.size ?: 0)),
                replyRaw = raw, errorCode = (outcome as? PickOutcome.Fail)?.reason,
            )
        }
        val ok = outcome as? PickOutcome.Ok
        val chosen = ok?.picks?.mapNotNull { p -> pool.firstOrNull { it.no == p.no } }.orEmpty()
        ctx.recordDiag(
            skill = "discover_music", stage = "pick_validate",
            ok = ok != null && ok.fit != Fit.NONE && chosen.isNotEmpty(),
            counts = mapOf(
                "candidates" to pool.size, "validPicks" to chosen.size,
                "fit" to (ok?.fit?.ordinal ?: -1),
            ),
            errorCode = when {
                ok == null -> "PARSE_FAILED"
                chosen.isEmpty() -> "NO_VALID_PICK"
                else -> null
            },
        )
        onStep("pick", AgentEngine.StepStatus.DONE)

        // ── ⑤ 显式降级:挑不出贴合的就说清楚"这是按最常听给的",不假装成推荐 ──
        if (chosen.isEmpty()) {
            return@withContext pickResult(
                header = degradedHeader(scene, artistLabel, albumArg),
                items = pool.take(MAX_PICKS),
            )
        }
        return@withContext pickResult(header = matchHeader(scene, artistLabel, albumArg), items = chosen)
    }

    /** 让模型判断"候选里哪几首贴合这个场景"。只输出 JSON(解析与校验在 [RecommendationPlan.parsePickResult])。 */
    private fun buildPickPrompt(
        scene: String,
        userMessage: String,
        pool: List<RecCandidate>,
        artistLabel: String?,
        albumArg: String?,
    ): String = buildString {
        appendLine("用户在本地音乐播放器里说:「${userMessage.ifBlank { scene }}」。")
        appendLine("下面是**他库里真实存在的候选**(已按最常听排序${scopeNote(artistLabel, albumArg)})。")
        appendLine("**只能在候选里挑,不要推荐候选之外的歌,也不要编造。**")
        pool.forEach { appendLine("${it.no}. ${it.title} — ${it.artist}") }
        appendLine()
        appendLine("请判断这些候选里哪几首贴合他要的场景/心情,只输出一个 JSON 对象:")
        appendLine("""{"fit":"strong|weak|none","picks":[{"i":序号,"why":"一句话理由(20 字内)"}]}""")
        appendLine("- 都不贴合时 fit 用 none,picks 可以为空。")
        appendLine("- 最多挑 $MAX_PICKS 首;序号必须来自上面列表。")
        appendLine("- 只输出 JSON,不要解释、不要 markdown 围栏。")
    }

    private fun scopeNote(artistLabel: String?, albumArg: String?): String = when {
        artistLabel != null && albumArg != null -> ",且都来自 $artistLabel 的《$albumArg》"
        albumArg != null -> ",且都来自《$albumArg》"
        artistLabel != null -> ",且都是 $artistLabel 的歌"
        else -> ""
    }

    private suspend fun callModel(
        ctx: AgentEngine.SkillContext,
        config: LlmProviderConfig,
        prompt: String,
    ): String? {
        if (!config.isConfigured) return null
        val resp = LlmClient.call(config, listOf("user" to prompt), temperature = 0.3, maxTokens = 500) ?: return null
        ctx.logUsage(resp.promptTokens, resp.completionTokens, resp.cacheTokens)
        return resp.content
    }

    /**
     * 组装最终结果:**首行就是标题**,且文本清单条数与可点选项条数**一致**
     * (列了却点不到,用户会以为卡片坏了)。
     */
    private fun pickResult(header: String, items: List<RecCandidate>) = AgentEngine.SkillResult(
        summary = buildString {
            appendLine(header)
            items.forEach { appendLine("《${it.title}》 — ${it.artist}") }
        }.trimEnd(),
        options = items.map { "播放《${it.title}》" },
    )

    private fun plainHeader(artistLabel: String?, albumArg: String?): String = when {
        artistLabel != null && albumArg != null -> "$artistLabel 的《$albumArg》里,这几首点一下就放:"
        albumArg != null -> "《$albumArg》里的这几首,点一下就放:"
        artistLabel != null -> "$artistLabel 里最常听的这几首,点一下就放:"
        else -> "这几首是按平时常听挑的,点一下就放:"
    }

    private fun matchHeader(scene: String, artistLabel: String?, albumArg: String?): String = when {
        artistLabel != null && albumArg != null -> "《$albumArg》里适合「$scene」的这几首,点一下就放:"
        artistLabel != null -> "$artistLabel 里适合「$scene」的这几首,点一下就放:"
        else -> "适合「$scene」的这几首,点一下就放:"
    }

    /** 显式降级:**说清"没挑出特别贴合的"**,而不是让用户以为这是筛选过的推荐。 */
    private fun degradedHeader(scene: String, artistLabel: String?, albumArg: String?): String = when {
        artistLabel != null -> "没有明显贴合「$scene」的,$artistLabel 这几首是按最常听给你的:"
        albumArg != null -> "没有明显贴合「$scene」的,《$albumArg》这几首是按最常听给你的:"
        else -> "没有明显贴合「$scene」的,这几首是按最常听给你的:"
    }
}

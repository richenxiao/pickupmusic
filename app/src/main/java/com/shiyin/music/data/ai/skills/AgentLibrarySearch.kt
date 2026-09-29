package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.Track
import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmClient
import com.shiyin.music.data.ai.LlmProviderConfig

/**
 * v1.3.10: Agent 侧「在本地音乐库里找歌」的统一入口（库内检索 + 写法兜底）。
 *
 * 分层（用户定调:**存不存在只由本地库决定,模型不参与判断**）:
 *  1. 直接搜 —— [AgentEngine.SkillContext.searchTracks],内部走 FuzzySearch
 *     （全角→半角 / 繁→简 / 去空格 / 编辑距离容错）；
 *  2. 假名↔罗马字 —— FuzzySearch 内已兜（「カブトムシ」↔「kabutomushi」）；
 *  3. 仍无结果 → 让 LLM **只提"可能的其他写法"**（中文译名/原文名/读音/别名），
 *     逐个回本地重搜。**提议只是查询串,命中与否由库里真实数据决定**——模型永远
 *     无法凭空"造出"一首不存在的歌。
 *  4. 三条都空才返回空列表 → 调用方如实说"库里没有"(不许编"我记得有")。
 *
 * ⚠️ 依赖点:第 3 步需要已配置的 LLM。没配 key 时自动跳过,退化为纯本地检索。
 */
internal object AgentLibrarySearch {

    /** 兜底扩展最多采纳的写法数(每次都会回本地重搜,不宜过多)。 */
    private const val MAX_VARIANTS = 5

    suspend fun resolve(
        ctx: AgentEngine.SkillContext,
        config: LlmProviderConfig,
        query: String,
        limit: Int,
    ): List<Track> {
        val direct = ctx.searchTracks(query, limit)
        if (direct.isNotEmpty()) return direct

        val variants = proposeVariants(ctx, config, query)
        if (variants.isEmpty()) return emptyList()

        val merged = LinkedHashMap<Long, Track>()
        for (v in variants) for (t in ctx.searchTracks(v, limit)) merged.putIfAbsent(t.id, t)
        android.util.Log.i("Agent", "search 兜底:「$query」→ 写法 $variants → 命中 ${merged.size} 首")
        return merged.values.take(limit)
    }

    /**
     * 让 LLM 提"用户可能想找的其它写法"。只接受短、无装饰的候选串。
     *
     * 刻意不解析模型对"存不存在"的判断:它返回什么都不影响结论,最终仍由本地库裁决。
     */
    private suspend fun proposeVariants(
        ctx: AgentEngine.SkillContext,
        config: LlmProviderConfig,
        query: String,
    ): List<String> {
        if (!config.isConfigured) return emptyList()
        val prompt = """
            用户在自己的本地音乐库里找「$query」,直接搜索没有结果。
            请只输出最多 $MAX_VARIANTS 个**可能的其他写法**,每行一个:
            - 外语歌的中文译名 / 原文名互译
            - 日文歌名的假名写法、罗马字写法
            - 常见别名、简称、正式全名
            不要编号、不要解释、不要引号、不要标点修饰;不确定的就不要写。
        """.trimIndent()
        val resp = LlmClient.call(config, listOf("user" to prompt), temperature = 0.2, maxTokens = 200)
            ?: return emptyList()
        resp.let { ctx.logUsage(it.promptTokens, it.completionTokens, it.cacheTokens) }
        val bullet = Regex("""^[\s\-*·>#]*(?:\d+[.、)]\s*)?""")
        return (resp.content ?: "").lineSequence()
            .map { bullet.replace(it, "").trim().trim('"', '「', '」', '《', '》') }
            .filter { it.isNotBlank() && it.length <= 40 && it != query }
            .distinct()
            .take(MAX_VARIANTS)
            .toList()
    }

    /**
     * 「按处境/心情挑歌」的检索。
     *
     * 用户给的是**场景**(「今天早上天气很好」「加班中」「下雨了」),曲名里通常没有这些词
     * ——**理解必须由模型做**:让它把场景联想成"可能出现在曲名/专辑名/歌手名里的概念词"
     * (朝 / 夜明け / morning / sunrise / 阳光…),再逐个回**本地库**搜。
     *
     * 这是"让大模型思考"的落点:模型负责理解与联想,库负责提供真实曲目;两边都不越界
     * ——模型想得再对,搜不到的曲目也进不了候选(不可能凭空造歌)。
     */
    suspend fun resolveByMood(
        ctx: AgentEngine.SkillContext,
        config: LlmProviderConfig,
        mood: String,
        limit: Int,
    ): List<Track> {
        val direct = ctx.searchTracks(mood, limit)
        if (direct.isNotEmpty()) return direct
        val concepts = proposeConcepts(ctx, config, mood)
        if (concepts.isEmpty()) return emptyList()
        val merged = LinkedHashMap<Long, Track>()
        for (c in concepts) for (t in ctx.searchTracks(c, limit)) merged.putIfAbsent(t.id, t)
        android.util.Log.i("Agent", "mood 检索:「$mood」→ 概念 $concepts → 命中 ${merged.size} 首")
        return merged.values.toList()
    }

    /** 把"此刻的处境"联想法成可搜的词(概念/场景/时段)。失败或没配 key → 空(退化到只按常听挑)。 */
    private suspend fun proposeConcepts(
        ctx: AgentEngine.SkillContext,
        config: LlmProviderConfig,
        mood: String,
    ): List<String> {
        if (!config.isConfigured) return emptyList()
        val prompt = """
            用户在本地音乐播放器里说:「$mood」。他想让你据此挑一首歌陪他现在这一刻。
            请只输出最多 8 个**可能出现在歌曲名/专辑名/歌手名里的词**(中文、日文、英文都可以),
            用来在本地库里搜出契合此刻的歌。例如「今天早上天气很好」→ 朝 / 夜明け / morning / sunrise / 阳光。
            每行一个词,不要编号、不要解释、不要整句;没想到就少写几个。
        """.trimIndent()
        val resp = LlmClient.call(config, listOf("user" to prompt), temperature = 0.4, maxTokens = 160)
            ?: return emptyList()
        resp.let { ctx.logUsage(it.promptTokens, it.completionTokens, it.cacheTokens) }
        val bullet = Regex("""^[\s\-*·>#]*(?:\d+[.、)]\s*)?""")
        return (resp.content ?: "").lineSequence()
            .map { bullet.replace(it, "").trim().trim('"', '「', '」', '《', '》') }
            .filter { it.isNotBlank() && it.length <= 12 && it != mood }
            .distinct()
            .take(8)
            .toList()
    }

    /**
     * 无关键词时的"随便挑"候选池:走 [AgentEngine.SkillContext.listAllTracks](按最常听排序),
     * 同歌手最多 2 首——否则"挑一首"很容易整批都是同一个人,选择就没有意义了。
     * 纯本地、确定性、零网络。
     */
    fun diverseFallback(ctx: AgentEngine.SkillContext, limit: Int): List<Track> {
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<Track>(limit)
        for (t in ctx.listAllTracks()) {
            val n = perArtist[t.artist] ?: 0
            if (n >= 2) continue
            perArtist[t.artist] = n + 1
            out += t
            if (out.size >= limit) break
        }
        return out
    }
}

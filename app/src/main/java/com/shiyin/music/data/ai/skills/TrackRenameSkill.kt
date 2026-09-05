package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmClient
import com.shiyin.music.data.ai.LlmProviderConfig
import com.shiyin.music.data.ai.TavilyService
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.3b Agent 技能④:歌曲名修正(用户定调"让模型能接管每一个功能"——单曲改名
 * 管道早已存在(saveTrackInfo,专辑页手动改名同路径),缺的只是这个技能)。
 *
 * 场景:文件元数据歌名识别有误(繁简写错/带多余注记/半角全角混乱/顺序颠倒)。
 * 流程与 artist_fix 同构:
 *  ①搜证: Tavily 搜该专辑官方曲目表
 *  ②分析: LLM 对照官方曲目表 + 本地曲目(id:标题),产出需要改名的曲目 JSON
 *  ③写回: ctx.saveTrackInfo(mediaId, 新标题, 原歌手)——只碰 track_info_override
 *    (单曲粒度,显示层),不动文件标签。
 */
object TrackRenameSkill : AgentEngine.AgentSkill {
    override val key = "track_rename"
    override val description = "修正专辑/曲目的歌曲名识别错误(联网核对官方曲目名后修正单曲标题);指令里可点名任意专辑名"

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        // v1.3.6e: 直接改名路径——用户点名新歌名(new_title)时不再绕"联网比对官方
        // 曲目表"(那条链路拿不到用户点名的旧/新名,只能整专辑盲比,结果就是
        // "改不了名")。旧歌名有 → 点名专辑/当前专辑页内含匹配定位;没有 → 当前播放。
        // v1.3.6f: fastPath 现在无条件带 rule(用户原话),directNew 才是点名直改的
        // 信号——这里以 new_title 为准分流,rule 交给下面的规则路径理解。
        val directTitle = args["new_title"]?.trim()
        if (!directTitle.isNullOrBlank()) {
            val oldHint = args["track"]?.trim().orEmpty()
            android.util.Log.i("TrackRename", "direct path: track=$oldHint new=$directTitle album=${args["album"]}")
            // 步骤 id 用 locate/rename(区别于比对链路的 search/analyze/writeback),
            // MainViewModel 行映射把它们对到「定位歌曲/写回新歌名」两行,不串行。
            onStep("locate", AgentEngine.StepStatus.RUNNING)
            // 定位优先级:点名专辑内搜 > 当前专辑页内含匹配 > 全库搜(按播放次数
            // 排序) > 当前播放。全库搜放后面——同名子串的歌多时 first 命中可能
            // 不是用户指的那首;点名专辑/当前专辑页的语境更可信。
            val namedAlbum = args["album"]?.takeIf { it.isNotBlank() }?.let { ctx.findAlbum(it) }
            val target: com.shiyin.music.data.Track? = when {
                oldHint.isNotBlank() ->
                    namedAlbum?.let { k -> ctx.albumTracksOf(k).firstOrNull { it.title.contains(oldHint, ignoreCase = true) } }
                        ?: ctx.albumTracks().firstOrNull { it.title.contains(oldHint, ignoreCase = true) }
                        ?: ctx.searchTracks(oldHint, 5).firstOrNull()
                else -> ctx.currentTrack() ?: ctx.albumTracks().firstOrNull()
            }
            android.util.Log.i("TrackRename", "direct locate: target=${target?.title} id=${target?.id}")
            if (target == null) {
                onStep("locate", AgentEngine.StepStatus.FAILED)
                onStep("rename", AgentEngine.StepStatus.FAILED)
                return@withContext AgentEngine.SkillResult(
                    "库里找不到「$oldHint」这首歌,无法改名。可以点开那首歌后再说「把歌名改成「$directTitle」」。",
                    success = false,
                )
            }
            if (directTitle == target.title) {
                onStep("locate", AgentEngine.StepStatus.DONE)
                onStep("rename", AgentEngine.StepStatus.DONE)
                return@withContext AgentEngine.SkillResult("「${target.title}」的歌名已经是「$directTitle」,无需修改。")
            }
            onStep("rename", AgentEngine.StepStatus.RUNNING)
            ctx.saveTrackInfo(target.id, directTitle, target.artist)
            onStep("rename", AgentEngine.StepStatus.DONE)
            return@withContext AgentEngine.SkillResult(
                "已把「${target.title}」改名为「$directTitle」(歌手:${target.artist})。可在曲目 ⋮ 菜单核对/改回。",
            )
        }

        // v1.3.6e: 批量规则路径——用户描述"怎么改"(去掉-歌手名后缀/繁转简/去编号…)
        // 而不是"改成什么"。流程 = 分析需求 → 列出每首歌的修改清单 → 写回 → 列出
        // 全部改动供验收(用户定调:本质就是"分析需求→列方案与操作对象清单→调功能
        // 实现→验收"的过程)。目标专辑:点名专辑名 > 当前专辑页 > 报错引导。
        val rule = args["rule"]?.trim()
        if (!rule.isNullOrBlank()) {
            android.util.Log.i("TrackRename", "rule path: album=${args["album"]} rule=$rule")
            val namedAlbum = args["album"]
            val srcKey = when {
                !namedAlbum.isNullOrBlank() -> ctx.findAlbum(namedAlbum) ?: return@withContext AgentEngine.SkillResult(
                    "音乐库里没有找到名为「$namedAlbum」的专辑。请确认专辑名写法,或打开那张专辑后再发指令。",
                    success = false,
                )
                else -> ctx.albumKey ?: return@withContext AgentEngine.SkillResult(
                    "不知道要改哪张专辑。可以说「把《专辑名》里每首歌的XX后缀去掉」,或先打开那张专辑。",
                    success = false,
                )
            }
            val tracks = ctx.albumTracksOf(srcKey)
            if (tracks.isEmpty()) {
                return@withContext AgentEngine.SkillResult("专辑里没有曲目,无事可改。", success = false)
            }
            val albumName = tracks.first().album
            val artistName = tracks.first().artist

            onStep("analyze", AgentEngine.StepStatus.RUNNING)
            // 一次 LLM 把规则落到每首歌:输入=用户需求原话 + 全部曲目(id. 现标题),
            // 输出=每首歌的新标题 JSON(不含要改的行也返回,保持行号对齐方便核对)。
            val trackList = tracks.joinToString("\n") { "${it.id}. ${it.title}" }
            val prompt = """
                用户的批量改歌名需求:「$rule」
                专辑:《$albumName》 歌手:$artistName
                本地曲目(id. 现标题):
                $trackList

                要求:
                1. 把用户需求理解为对每首歌标题的统一变换规则(如"去掉-歌手名后缀"=删除标题中「-」及之后的歌手名部分;"繁体转简体"=逐字转换;"片假名换汉字官方名"=逐首给出对应的汉字官方名)
                2. 逐首应用规则,给出新标题;规则对这首歌不适用(标题本来就没有该后缀)→ 新标题=原标题
                3. 只做规则明确要求的变换;不确定某首歌的官方名 → 该首保持原标题(返回 新标题=原标题)
                4. 标题已符合规则的歌也返回(新标题=原标题),保持每行都输出,方便核对
                5. id 必须取上面列表里该行开头的数字
                6. 答案(最终 JSON)写进 content 正文,不要只放在思考过程里
                7. 只返回 JSON: {"fixes":[{"mediaId":<id>,"title":"新标题"}]},不要多余文字
            """.trimIndent()
            var resp = LlmClient.call(config, listOf("user" to prompt), maxTokens = 4096)
            if (resp == null) {
                onStep("analyze", AgentEngine.StepStatus.FAILED)
                onStep("writeback", AgentEngine.StepStatus.FAILED)
                val err = LlmClient.lastError ?: "未知错误"
                return@withContext AgentEngine.SkillResult("分析需求失败(LLM 调用失败):$err", success = false)
            }
            ctx.logUsage(resp.promptTokens, resp.completionTokens, resp.cacheTokens)
            // v1.3.6f: 二次提取——推理模型(sensenova)把 token 全花在 reasoning 逐首
            // 分析上,content 空且思考链里也没有最终 JSON(用户复现:片假名→汉字官方名
            // 场景,content 空、思考链只有分析没有答案)。把思考链喂回去,要求"按你
            // 上面的分析直接给出最终 JSON",一次廉价往返把它捞出来。
            var fixes = parseFixes(resp.content ?: "", resp.thinking)
            android.util.Log.i("TrackRename", "rule LLM resp(${fixes.size} fixes): ${(resp.content ?: "").take(400)} | thinking300=${(resp.thinking ?: "").take(300)}")
            if (fixes.isEmpty()) {
                val think = resp.thinking ?: ""
                if (think.isNotBlank()) {
                    android.util.Log.i("TrackRename", "rule retry: content empty, re-asking from thinking")
                    val retryPrompt = """
                        你刚才对下面这批曲目的分析(在思考过程里)已经完成,但最终答案没有输出到正文。
                        请按你上面的分析结论,直接给出最终 JSON,不要再重新分析,不要输出其他文字:
                        {"fixes":[{"mediaId":<id>,"title":"新标题"}]}

                        原需求:「$rule」
                        曲目列表(保持上面的 id):
                        $trackList
                    """.trimIndent()
                    val retry = LlmClient.call(config, listOf("user" to retryPrompt), maxTokens = 4096)
                    if (retry != null) {
                        ctx.logUsage(retry.promptTokens, retry.completionTokens, retry.cacheTokens)
                        fixes = parseFixes(retry.content ?: "", retry.thinking)
                        android.util.Log.i("TrackRename", "rule retry resp(${fixes.size} fixes): ${(retry.content ?: "").take(400)}")
                        if (fixes.isNotEmpty()) resp = retry
                    }
                }
            }
            // 落实到"真正要改的行":新标题非空且 ≠ 现标题才算改动
            val changes = fixes.mapNotNull { f ->
                val t = tracks.firstOrNull { it.id == f.first } ?: return@mapNotNull null
                if (f.second.isNotBlank() && f.second != t.title) t to f.second else null
            }
            if (changes.isEmpty()) {
                onStep("analyze", AgentEngine.StepStatus.DONE)
                onStep("writeback", AgentEngine.StepStatus.DONE)
                return@withContext AgentEngine.SkillResult(
                    if (tavilyKey.isBlank())
                        "分析完成:《$albumName》里没有规则能落到的改动。如果需要对照官方名修正,建议说「《$albumName》的歌名识别有误,联网核对官方名」。"
                    else
                        "分析完成:《$albumName》里没有规则能落到的改动(标题已符合规则,或我不确定某些官方名,拿不准的没有动)。可换个说法再试,或用曲目 ⋮ 菜单手动改。",
                    thinking = resp.thinking,
                )
            }
            // 验收清单:旧 → 新,每行一首,用户一眼核对
            val manifest = changes.joinToString("\n") { (t, newT) -> "· ${t.title} → $newT" }
            android.util.Log.i("TrackRename", "rule writeback: ${changes.size} tracks -> ${changes.joinToString("; ") { (t, n) -> "${t.id}=${t.title}->$n" }}")
            onStep("writeback", AgentEngine.StepStatus.RUNNING)
            for ((t, newT) in changes) ctx.saveTrackInfo(t.id, newT, t.artist)
            onStep("writeback", AgentEngine.StepStatus.DONE)
            return@withContext AgentEngine.SkillResult(
                "已按「${rule.take(40)}${if (rule.length > 40) "…" else ""}」修改《$albumName》${changes.size} 首歌:\n$manifest\n可在曲目 ⋮ 菜单核对/改回。",
                thinking = resp.thinking,
            )
        }

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
            return@withContext AgentEngine.SkillResult("没有指定专辑,无法修正。可以说「《专辑名》的歌名识别有误」,或先打开某张专辑。", success = false)
        }
        val albumName = tracks.first().album
        val artistName = args["artist"] ?: tracks.first().artist

        onStep("search", AgentEngine.StepStatus.RUNNING)
        val tavilyCtx = runCatching {
            TavilyService.search(tavilyKey, "$albumName $artistName 官方曲目 tracklist 曲目表")
        }.getOrNull()
        if (tavilyCtx.isNullOrBlank()) LlmClient.clearLastError()
        onStep("search", if (tavilyCtx.isNullOrBlank()) AgentEngine.StepStatus.FAILED else AgentEngine.StepStatus.DONE)

        onStep("analyze", AgentEngine.StepStatus.RUNNING)
        // mediaId 一起喂给 LLM(写回按 id 匹配,与 artist_fix 同因)
        val trackList = tracks.joinToString("\n") { "${it.id}. ${it.title}" }
        val ctxBlock = if (tavilyCtx.isNullOrBlank()) "(未搜到网络资料,凭你的知识判断)" else "网络资料(官方曲目表):\n$tavilyCtx"
        val prompt = """
            下面这张专辑的本地曲目标题可能有识别错误(繁简写错/多余注记/标点混乱/顺序颠倒)。
            专辑:$albumName  本地识别的歌手:$artistName
            本地曲目(id. 标题):
            $trackList

            $ctxBlock

            要求:
            1. 对照官方曲目名,为标题识别有误的歌给出正确的官方标题
            2. 标题本来就正确的歌不要返回(不要无意义地逐字校对)
            3. 无法确定的歌不要返回(保持原标题,不要瞎猜)
            4. id 必须取上面列表里该行开头的数字,不要编造
            5. 只返回 JSON: {"fixes":[{"mediaId":<列表里的id>,"title":"正确的官方标题"}]}
            6. 不要多余文字
        """.trimIndent()
        val resp = LlmClient.call(config, listOf("user" to prompt), maxTokens = 2048)
        if (resp == null) {
            // v1.3.5: 失败两行都落终态,不留假"还在跑"的 ○。
            onStep("analyze", AgentEngine.StepStatus.FAILED)
            onStep("writeback", AgentEngine.StepStatus.FAILED)
            val err = LlmClient.lastError ?: "未知错误"
            return@withContext AgentEngine.SkillResult("LLM 调用失败:$err", success = false)
        }
        ctx.logUsage(resp.promptTokens, resp.completionTokens, resp.cacheTokens)
        val fixes = parseFixes(resp.content ?: "", resp.thinking)
        android.util.Log.i("TrackRename", "compare resp(${fixes.size} fixes): ${(resp.content ?: "").take(300)}")
        if (fixes.isEmpty()) {
            // v1.3.5: 流程完整走完——writeback 也标 DONE,不留 ○。
            onStep("analyze", AgentEngine.StepStatus.DONE)
            onStep("writeback", AgentEngine.StepStatus.DONE)
            return@withContext AgentEngine.SkillResult(
                if (tavilyCtx.isNullOrBlank())
                    "联网未搜到《$albumName》的官方曲目表(专辑名识别有误/网络不可达/搜索 Key 失效),无法核对歌名。"
                else "核对完成:《$albumName》的所有歌名都与官方一致,无需修正。"
            )
        }

        onStep("writeback", AgentEngine.StepStatus.RUNNING)
        var applied = 0
        for (f in fixes) {
            val t = tracks.firstOrNull { it.id == f.first } ?: continue
            if (f.second.isNotBlank() && f.second != t.title) {
                ctx.saveTrackInfo(t.id, f.second, t.artist)
                applied++
            }
        }
        onStep("writeback", AgentEngine.StepStatus.DONE)
        if (applied == 0) {
            AgentEngine.SkillResult("未找到需要修正的歌名。", thinking = resp.thinking)
        } else {
            AgentEngine.SkillResult("已修正 $applied 首歌的标题。可在曲目 ⋮ 菜单核对/改回。", thinking = resp.thinking)
        }
    }

    /** v1.3.6f: 解析修正清单。模型推理链常把最终 JSON 也写在 reasoning 里而
     *  content 空(用户复现:回复只有 reasoning,content 空 → fixes=0 → 流程
     *  "成功"却零写回)。这里 content 解析不到时再从 thinking 里抠 JSON。 */
    private fun parseFixes(raw: String, thinking: String? = null): List<Pair<Long, String>> {
        val fromContent = parseFixesJson(raw)
        if (fromContent.isNotEmpty()) return fromContent
        if (!thinking.isNullOrBlank()) {
            val fromThinking = parseFixesJson(thinking)
            if (fromThinking.isNotEmpty()) return fromThinking
        }
        return emptyList()
    }

    private fun parseFixesJson(raw: String): List<Pair<Long, String>> = try {
        val direct = runCatching { JsonParser.parseString(raw.trim()).asJsonObject }.getOrNull()
        val obj = direct ?: run {
            val m = Regex("""\{.*}""", RegexOption.DOT_MATCHES_ALL).find(raw) ?: return emptyList()
            runCatching { JsonParser.parseString(m.value).asJsonObject }.getOrNull()
        } ?: return emptyList()
        val arr = obj.getAsJsonArray("fixes") ?: return emptyList()
        arr.mapNotNull { el ->
            val o = el?.asJsonObject ?: return@mapNotNull null
            val id = o.get("mediaId")?.asLong ?: return@mapNotNull null
            val title = o.get("title")?.asString ?: return@mapNotNull null
            id to title
        }
    } catch (_: Exception) { emptyList() }
}

package com.shiyin.music.data.ai

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.2.2 Agent: 对话式 Agent 引擎。用户自然语言指令 → 意图理解(LLM 选技能) →
 * 执行技能(联网搜证 + 生成 + 回写)→ 步骤回调实时更新面板。
 *
 * 设计要点(用户确认的决策):
 *  - 意图理解:把"可用技能清单 + 当前上下文"喂给 LLM,让它返回
 *    {"skill":"...","args":{...},"steps":["..."]} JSON。LLM 做选技能,不做写库。
 *  - 技能执行:每个 [AgentSkill] 自己编排(Tavily + LLM),最后调 MainViewModel 的
 *    公开写回函数(applyAlbumOrderText/saveTrackInfo 等)。写入层只碰
 *    track_info_override/album_order/track_album_move,**绝不碰 artist_aliases 全局表**。
 *  - pending 闸门:技能产出需写回时,走 vm 已有的 pendingOrder 确认窗(应用/驳回),
 *    AI 不直接覆盖。needsConfirm=true 时 UI 提示用户确认。
 *  - 信息不足:技能返回空/低置信时,不硬执行,返回"未找到足够信息"。
 */
object AgentEngine {

    enum class StepStatus { PENDING, RUNNING, DONE, FAILED }
    /** v1.3.3: [error] 仅 FAILED 时有值——行内直接显示失败原因(如"HTTP 429: 额度耗尽"),
     *  不再只给一个 ✗ 让用户猜。 */
    data class Step(
        val id: String,
        val label: String,
        val status: StepStatus = StepStatus.PENDING,
        val error: String? = null,
    )

    /** 技能执行上下文:技能可读当前专辑/歌手/曲目 + 按名搜库 + 调 vm 写回。 */
    interface SkillContext {
        val albumKey: String?
        val artistKey: String?
        fun currentTrack(): com.shiyin.music.data.Track?
        fun albumTracks(): List<com.shiyin.music.data.Track>
        /** 读任意专辑(按 albumKey)的曲目(当前排序),技能按名找到专辑后用。 */
        fun albumTracksOf(key: String): List<com.shiyin.music.data.Track>
        /** 按用户说的专辑名在音乐库里找专辑(忽略大小写/空格/标点),返回 albumKey;找不到 null。 */
        fun findAlbum(query: String): String?
        /** v1.3.6: 全库搜歌(标题/歌手/专辑模糊匹配,按播放次数排序),供
         *  library_query 技能回答"库里有没有XX"类问题。空列表=没搜到。 */
        fun searchTracks(query: String, limit: Int): List<com.shiyin.music.data.Track>
        /** 全库搜歌手名(前缀/包含),返回歌手名列表(带该歌手总播放次数的文案用)。 */
        fun searchArtists(query: String, limit: Int): List<String>
        // 技能调 vm 写回的桥(由 MainViewModel 实现,避免 Agent 层直接持有 vm)
        fun applyAlbumOrderText(key: String, text: String)
        /** v1.3.3: 按本地索引写回排序——AI 输出编号方案(对哪首排第几做判断),零文本匹配。 */
        fun applyAlbumOrderIndices(key: String, indices: List<Int>)
        fun saveTrackInfo(mediaId: Long, title: String, artist: String)
        fun hasPendingOrder(): Boolean
        /** 记录一次 LLM 调用的 token 消耗(落 token_usage_log 表,供设置页累计显示)。
         *  v1.3.6: cacheTokens=缓存命中 token(用量面板曲线;0=供应商不报)。 */
        fun logUsage(promptTokens: Int, completionTokens: Int, cacheTokens: Int = 0)
    }

    /** 技能执行结果。summary 给对话面板展示;needsConfirm→UI 提示用户走 pending 确认。 */
    data class SkillResult(
        val summary: String,
        val needsConfirm: Boolean = false,
        val success: Boolean = true,
        /** v1.3.3: 技能内 LLM 调用的思考链(推理模型才有)——透出给 UI 折叠展示。 */
        val thinking: String? = null,
    )

    /** 意图理解返回的计划(skillKey + 步骤标签 + 给技能的参数)。 */
    data class IntentPlan(
        val skillKey: String,
        val args: Map<String, String>,
        val steps: List<String>,
    )

    /**
     * v1.3.3: 单次调用合并路由结果——技能指令走 [Skill],闲聊直接带 [chatReply]。
     * 两者都空 = 调用失败/格式错(调用方按 lastError 透传)。
     */
    data class Route(
        val skill: IntentPlan?,
        val chatReply: String?,
        val thinking: String? = null,
        /** v1.3.3: 本次路由的思考耗时(ms)——UI 折叠态显示"已思考 X 秒"。 */
        val thinkingMs: Long = 0L,
        /** v1.3.3b review#B7: 路由调用的 token 消耗——消息级"本次 N tokens"显示用。 */
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
    )

    /** 技能接口。各技能实现 [execute],内部编排 + 回调 [onStep] + 返回结果。 */
    interface AgentSkill {
        val key: String
        val description: String
        suspend fun execute(
            ctx: SkillContext,
            args: Map<String, String>,
            config: LlmProviderConfig,
            tavilyKey: String,
            onStep: (String, StepStatus) -> Unit,
        ): SkillResult
    }

    /** 已注册技能。新增技能 = 实现一个 AgentSkill + 加到本列表。 */
    val skills: List<AgentSkill> = listOf(
        com.shiyin.music.data.ai.skills.AlbumSortSkill,
        com.shiyin.music.data.ai.skills.ArtistFixSkill,
        com.shiyin.music.data.ai.skills.LyricsFetchSkill,
        com.shiyin.music.data.ai.skills.TrackRenameSkill,
        com.shiyin.music.data.ai.skills.LibraryQuerySkill,
    )

    fun skillByKey(key: String): AgentSkill? = skills.firstOrNull { it.key == key }

    /** 技能清单文本(拼进意图理解 prompt)。 */
    private fun skillsManifest(): String = skills.joinToString("\n") { "  - ${it.key}: ${it.description}" }

    /**
     * v1.3.5: 剥离混进 content 的前导思考块。sensenova 等模型偶发把 reasoning 写进
     * content 开头(整段 "Thinking Process: 1. **Analyze..." / "The user is asking..."
     * / 中文"思考过程:")。
     * v1.3.6d: 判定升级 looksLikeThinking(标记表扩容 + 弱英文形态需佐证)。
     * 剥离策略:优先找"正文起点"(Final/最终/正式 输出|回答 之后);找不到则试
     * "最后一个非推理段"(推理后通常重写一遍干净答案);都没有→返回原文
     * (宁可漏剥不误杀,但标记命中的概率已大幅提高)。
     */
    private fun stripInlineThinking(text: String, hasReasoning: Boolean = false): String {
        if (!looksLikeThinking(text, hasReasoning)) return text
        // 1) 明确收尾记号:正文从这之后开始
        val finals = Regex("""(?i)(final (output|answer|response|check|reply)|最终(输出|回答)|正式(输出|回答)|给用户的回答)""")
        finals.find(text)?.let { m ->
            val rest = text.substring(m.range.last + 1).trim()
            if (rest.isNotBlank()) return rest
        }
        // 2) 末段兜底:思考后重写干净答案(找最后一个非空行段,若它不带编号/
        //    bullet 结构且不是前缀形态,当正文)
        val paras = text.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.size >= 2) {
            val last = paras.last()
            val isStillThinking = Regex("""^\s*(\d+\.|[-*·•])\s""").containsMatchIn(last) ||
                listOf("the user", "we need", "i need", "let me", "thinking").any { last.startsWith(it, ignoreCase = true) }
            if (!isStillThinking && last.length >= 6) return last
        }
        // 3) 找不到正文——整段当思考:返回空(调用方按"content 思考化"处理,
        //    chatReply 会落到空→显示提示,思考链在折叠块里完整可见,不冒充答案)
        return ""
    }

    /**
     * v1.3.3 单次调用合并路由(SSE 流式版):一次 LLM 调用同时完成"选技能 or
     * 直接聊天回答"。reasoning 增量经 [onThinking] 实时流出("正在思考"效果);
     * content 流出时经 [onContent] 实时透出(打字机回复,不等整段)。
     * 模型约定输出:技能指令返回 {"skill":...,"args":{...}};否则直接输出聊天回复。
     * v1.3.4: 模型可在聊天回复前加一行 [[FACT]] 标记声明"这是事实性问题,建议联网"——
     * route 解析剥离标记并置 [Route.needsWeb],由调用方在聊天兜底前先搜证。
     */
    suspend fun route(
        intent: String,
        ctx: SkillContext,
        config: LlmProviderConfig,
        onStep: (String, StepStatus) -> Unit,
        /** 最近对话(role to text,不含当前这条)——让"它/刚才"这类追问可路由。 */
        history: List<Pair<String, String>> = emptyList(),
        /** 思考链实时流出(reasoning 增量;v1.3.4 可回传 null=网络重试轮开始,调用方
         *  清空已积累的展示缓冲——重试成功会从头重发全量,不清会叠成两段)。 */
        onThinking: ((String?) -> Unit)? = null,
        /** 回复文本实时流出(content 累积全量,已剥离技能 JSON 情况仅聊天时回调)。 */
        onContent: ((String) -> Unit)? = null,
        /** v1.3.4: 搜证网页摘要塞进聊天 prompt(route 外部完成 Tavily 搜索后传回)。 */
        webContext: String = "",
    ): Route? = withContext(Dispatchers.IO) {
        onStep("intent", StepStatus.RUNNING)
        // 高频指令本地快速路径:固定句式正则直接选技能,零 LLM 往返。
        fastPathPlan(intent)?.let {
            android.util.Log.i("Agent", "fast-path: skill=${it.skillKey} args=${it.args}(省一次路由 LLM)")
            onStep("intent", StepStatus.DONE)
            return@withContext Route(it, null)
        }
        val historyBlock = if (history.isEmpty()) "" else
            "最近对话:\n" + history.joinToString("\n") { (r, t) ->
                "${if (r == "user") "用户" else "助手"}: ${t.take(300)}"
            } + "\n"
        val albumName = ctx.albumKey?.let { ctx.albumTracks().firstOrNull()?.album }
        val artistName = ctx.artistKey ?: ctx.currentTrack()?.artist
        val trackName = ctx.currentTrack()?.title
        val contextBlock = buildString {
            if (albumName != null) appendLine("当前专辑: $albumName")
            if (artistName != null) appendLine("当前歌手: $artistName")
            if (trackName != null) appendLine("当前播放曲目: $trackName")
        }
        // v1.3.6: 聊天死板重做——旧 prompt 是一整块"路由规则书",弱模型读进去
        // 就被规则腔感染,闲聊也答成"我目前主要能帮你做曲目排序/修正歌手名/找
        // 歌词这三件事"的模板。改两段式:先 system 给朋友式人设(自然聊天基调),
        // 再 user 段给路由规则(需要时才执行)。规则里明确"闲聊别提技能清单,
        // 除非用户问你能干什么";历史放宽到 300 字(旧 120 字把上下文剪残)。
        val systemMsg = """
            你是「拾音 PickUpMusic」音乐播放器里的 Agent,用户的听歌伙伴。性格:懂音乐、随和、有点幽默、说话像朋友,不说客服腔,不复述自己的功能清单(用户主动问"你能干什么"才介绍)。中文回复简洁自然,一般 1-3 句。
        """.trimIndent()
        val routeMsg = """
            [路由]下面这条用户消息,判断走哪条路:
            A. 是明确想执行某个音乐库操作 → 只输出 JSON(不要其他文字): {"skill":"技能key","args":{参数},"steps":["步骤1","步骤2"]}
               steps 是 2-4 步动作式短句,会在面板上按序执行。指令点名专辑名时 args 填 album="专辑名"。
               track_rename 补充:用户直接点名要把某首歌改名时(如 「旧名」改成「新名」/把歌名改为XX),
               args 填 track="用户说的旧歌名"、new_title="用户要的新歌名";没点名旧歌名只给新名时 track 可省略。
               用户描述的是统一变换规则而非具体新名时(如 去掉每首歌名后面的-歌手名后缀/繁体转简体),
               args 填 rule="用户需求原话" + album="专辑名"(用户点名了的话)。
               可用技能:
${skillsManifest()}
            B. 不是要执行操作(聊天/问问题/聊音乐/打招呼/感慨) → 忽略本段所有规则,像上面人设那样自然回复(此时绝不输出 JSON,绝不提技能清单)。
            ${if (webContext.isNotBlank()) "\n[联网资料]用户刚问了个事实性问题,已联网核实。回答以下面资料为准,资料没提的不要编:\n$webContext\n" else ""}
            ${if (contextBlock.isNotEmpty()) "\n[当前上下文]\n$contextBlock\n" else ""}
            $historyBlock
            用户消息: "$intent"
        """.trimIndent()
        val started = System.currentTimeMillis()
        // 流式:reasoning 实时流出给"正在思考";content 边流边判断是否 JSON——
        // JSON 技能路由不打字机(那会闪出半截 JSON),聊天则实时透出打字机。
        val contentSb = StringBuilder()
        // v1.3.6d: 本地同步累积 reasoning(供 looksLikeThinking 佐证判定——
        // 模型有独立思考链时,content 里混英文推理开头大概率也是思考)
        val thinkingAcc = StringBuilder()
        val resp = LlmClient.callStream(config, listOf("system" to systemMsg, "user" to routeMsg)) { content, reasoning ->
            // v1.3.3b review#6: 双 null = 网络异常重试轮开始(LlmClient 通知清缓冲)——
            // 重试成功会从头发全量,不清的话打字机内容重复一段。
            if (content == null && reasoning == null) {
                contentSb.clear()
                thinkingAcc.clear()
                onThinking?.invoke(null)  // v1.3.4: 思考链缓冲同样要清
                return@callStream
            }
            reasoning?.let { thinkingAcc.append(it); onThinking?.invoke(it) }
            content?.let { c ->
                contentSb.append(c)
                // 粗判:已流出部分像 JSON 开头 → 不透出(等路由完成);否则打字机透出。
                // v1.3.6d: 思考泄漏第三次修复——此前只按"已知标记前缀"判,模型的
                // 英文推理开头("The user is asking..."/"We need to determine...")
                // 不在表里,照样漏进气泡。改两层防线:
                // ①标记前缀(含劈开流的前缀片段)命中 → 整段缓冲到路由结束再清洗;
                // ②对已流出的缓冲,若开头像思考(looksLikeThinking,弱英文形态需
                //   佐证),同样停止透出,等全文到了再剥。只有开头干净才流式透出。
                val soFar = contentSb.toString()
                val headTrim = soFar.trimStart()
                val looksJson = headTrim.startsWith("{") || headTrim.startsWith("```")
                val maybeThinkingHead = headTrim.length <= 40 &&
                    THINKING_PREFIXES.any { p ->
                        p.startsWith(headTrim) || headTrim.startsWith(p)
                    }
                // 弱英文开头形态的部分缓冲("The use...")也先按疑似思考缓冲——
                // 等 40 字符以上再 looksLikeThinking 精判
                val weakHeadPatterns = listOf("the use", "we nee", "i nee", "the tas", "first,", "let me", "let's", "analy", "looking")
                val maybeWeakThinking = weakHeadPatterns.any { headTrim.startsWith(it, ignoreCase = true) }
                val startsThinking = looksLikeThinking(soFar, thinkingAcc.isNotEmpty())
                if (!looksJson && !maybeThinkingHead && !maybeWeakThinking && !startsThinking) {
                    var visible = stripInlineThinking(soFar, thinkingAcc.isNotEmpty())
                    // 完整/变体标记:任意位置全删
                    visible = visible.replace(Regex("""(\[{1,2}|【{1,2})\s*FACT\s*(\]{1,2}|】{1,2})""", RegexOption.IGNORE_CASE), "")
                    // 尾部流到一半的标记前缀(流完就轮到上面的全删规则)
                    visible = visible.replace(Regex("""(\[|【)\s*[Ff][Aa]?$"""), "")
                    visible = visible.replace(Regex("""(\[{1,2}|【{1,2})\s*[Ff][Aa]?[Cc]?[Tt]?$"""), "")
                    onContent?.invoke(visible.trimStart())
                }
            }
        } ?: run {
            android.util.Log.w("Agent", "route: LLM call 返回 null")
            onStep("intent", StepStatus.FAILED); return@withContext null
        }
        android.util.Log.i("Agent", "route content=${resp.content?.take(300)} (${System.currentTimeMillis() - started}ms)")
        ctx.logUsage(resp.promptTokens, resp.completionTokens, resp.cacheTokens)
        onStep("intent", StepStatus.DONE)
        val elapsedMs = System.currentTimeMillis() - started
        // 判定:输出是 {"skill":...} JSON → 技能路由;否则整段就是聊天回复。
        // content 与 thinking 都空 → chatReply=null(调用方按调用失败透传 lastError)。
        val plan = parseIntent(resp.content ?: "")
        if (plan != null) Route(plan, null, resp.thinking, elapsedMs, resp.promptTokens, resp.completionTokens)
        else {
            // v1.3.5: 残留的 [[FACT]]/【【Fact】】变体标记与```围栏一律剥离——路由
            // 协议记号不进用户气泡(事实性判定已全部本地化 looksLikeFactQuestion,
            // 不再依赖模型打标,模型漏写/瞎写都无所谓)。
            val raw = resp.content ?: ""
            val body = raw
                .replace(Regex("""(\[{1,2}|【{1,2})\s*FACT\s*(\]{1,2}|】{1,2})""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^```[a-zA-Z]*\s*"""), "")
                .replace(Regex("""```\s*$"""), "")
                .trimStart()
            // v1.3.3: trimStart——sensenova 等模型回复以 \n\n 开头,不裁掉气泡顶会有
            // 一整行空白。
            // v1.3.6c: content 空**不再回退 thinking**——推理模型把全部输出放思考链时,
            // 把整段 reasoning 当聊天回复展示就是"思考泄漏进气泡"(用户连续三轮反馈)。
            // 思考链只进折叠的 ThinkingBlock 展示;content 真空时 chatReply=null,
            // 调用方按"模型无有效输出"提示,不冒充答案。
            // v1.3.6d: stripInlineThinking 带佐证判(模型有独立 reasoning 时弱英文
            // 形态也剥),全文清洗比逐块更准。
            val chat = stripInlineThinking(body, resp.thinking?.isNotBlank() == true).takeIf { it.isNotBlank() }
            Route(null, chat, resp.thinking, elapsedMs, resp.promptTokens, resp.completionTokens)
        }
    }

    /**
     * v1.3.2: 高频指令本地快速路由(省一次路由 LLM 往返)。
     *  - 排序:须有「排/整理/修正 + 顺序/曲序」或「按官方/正确顺序」的动作句式——
     *    纯聊天里出现"顺序"二字(如"为什么会卡在写回顺序上")不会误命中。
     *  - 修歌手:「歌手/艺人」+ 识别错误类动词。
     *  - 找歌词:「歌词」+ 找/搜/配 等动作动词(问"歌词什么意思"不走技能)。
     * 书名号/引号里的名字 → args.album;没有则技能自己回退当前专辑页。
     */
    private fun fastPathPlan(intent: String): IntentPlan? {
        // v1.3.4: 书名号里抓到的名字做资格判定——纯数字(如用户说"排第15首歌"被
        // 《15》误抓)、单字符不作为专辑名;空则不带 album 参数(技能自己回退当前页)。
        val albumArgRaw = Regex("""《([^》]+)》""").find(intent)?.groupValues?.get(1)
            ?: Regex("""「([^」]+)」""").find(intent)?.groupValues?.get(1)
        val albumArg = albumArgRaw?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() } && it.length >= 2 }
        val args = albumArg?.let { mapOf("album" to it) } ?: emptyMap()

        val sortHit = Regex("""(排|整理|修正)(成|为|到|一下)*(正确|官方|正式)?(的)?(曲目|播放)?(顺序|曲序)""")
            .containsMatchIn(intent) ||
            Regex("""按\s*(官方|正确)\s*(顺序|曲序)""").containsMatchIn(intent)
        if (sortHit) return IntentPlan("album_sort", args, listOf("读取专辑曲目", "联网搜索官方顺序", "AI 核对生成顺序", "写回排序"))

        // v1.3.6e: 歌名批量规则上下文提前算——artist_fix 判定要用它做排除:
        // "统一去掉歌名的-歌手名后缀"同时含"歌手"与"识别(错乱)",不排除就被
        // artist_fix 抢走,永远进不了改名规则路径(用户正是这么说的)。
        val artistCtx = Regex("歌手|艺人|演唱者").containsMatchIn(intent)
        val orderCtx = Regex("顺序|曲序|排序|排到|排成").containsMatchIn(intent)
        val ruleCtx = !orderCtx &&
            Regex("""(去掉|删除|移除|剥掉|清理|统一|繁体|简体|转简|转繁|编号|后缀|前缀|规范化|格式化)""").containsMatchIn(intent) &&
            Regex("""(歌名|歌的名|曲目名|标题|后缀|前缀)""").containsMatchIn(intent)

        val artistFixHit = artistCtx && !ruleCtx &&
            Regex("反了|识别|修正|不对|有误|错了|查一下|归属|张冠李戴").containsMatchIn(intent)
        if (artistFixHit) return IntentPlan("artist_fix", args, listOf("联网搜证", "AI 分析修正", "写回本地"))

        // v1.3.3b: 修歌名——「歌名/标题」+ 修正类动词(与修歌手的判定分开,两个词都
        // 出现时歌名优先:更具体的意图先判)。v1.3.4: 补"帮我改/修一下歌名"句式。
        // v1.3.6e: track_rename 三条路,按具体度排序:
        //  ①点名直改:引号旧→新名(把「搁浅」改成「擱淺」)——args track/new_title,
        //    技能零 LLM 直写(旧链路把点名的新旧名全丢了,只传专辑名,结果"改不了名")。
        //  ②批量规则:用户描述"怎么改"而不是"改成什么"(统一去掉-歌手名后缀/繁转简/
        //    去编号)——args rule=需求原话,技能一次 LLM 把规则落到每首歌的修改清单。
        //  ③泛泛修正(歌名识别有误):不带规则,走联网比对官方曲目表(原行为)。
        // 「专辑」字样只挡 ①(改专辑名不归本技能);②的句式常带"专辑中每首歌",
        // 不能挡。artistCtx(已在上面提前算)挡 ①/③,不挡 ②——"去掉歌名的-
        // 歌手名后缀"里的"歌手"是后缀描述,不是修歌手意图。
        val quotedNames = Regex("""[「『“《]([^」』”》]+)[」』”》]""").findAll(intent)
            .map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        val renameVerb = Regex("""改成|改为|换成|重命名|改名|更名|改叫|叫做|叫作|修正成|修正为|统一为""")
            .containsMatchIn(intent)
        val albumWordCtx = intent.contains("专辑")
        val directNew = if (renameVerb && !artistCtx && !orderCtx && !albumWordCtx && quotedNames.isNotEmpty()) quotedNames.last() else null
        val directOld = if (directNew != null && quotedNames.size >= 2) quotedNames[quotedNames.size - 2] else ""
        val trackRenameHit = directNew != null || ruleCtx || (!artistCtx &&
            (Regex("歌名|歌的名|曲目名|标题").containsMatchIn(intent) &&
                Regex("反了|识别|修正|不对|有误|错了|统一|改|修|帮").containsMatchIn(intent))
            )
        if (trackRenameHit) {
            val renameAlbum = albumArg?.takeIf { it != directNew && it != directOld }
            // v1.3.6f: 命中改名技能就**无条件**带 rule=用户原话——不再依赖 ruleCtx
            // 正则去猜句式(用户复现的指令没踩中正则,args 全空落回老的联网比对
            // 路径,对冷门歌手搜不到资料,模型零修改却报"完成"——"显示成功但没变"
            // 的根因)。技能侧 LLM 拿原话自己理解"去掉-歌手名后缀"怎么逐首落地;
            // directNew(点名直改)优先级更高,有它技能走零 LLM 直写。
            val renameArgs = buildMap {
                renameAlbum?.let { put("album", it) }
                put("rule", intent.trim())
                if (directNew != null) { put("track", directOld); put("new_title", directNew) }
            }
            return IntentPlan(
                "track_rename", renameArgs,
                if (directNew != null) listOf("定位歌曲", "写回新歌名") else listOf("分析需求与修改清单", "写回并验收"),
            )
        }

        val lyricsHit = intent.contains("歌词") && Regex("找|搜|配|下载|获取|来一份").containsMatchIn(intent)
        if (lyricsHit) return IntentPlan("lyrics_fetch", emptyMap(), listOf("搜索歌词源"))

        return null
    }

    /** v1.3.6c: 模型往 content 开头混写思考的已知标记(全小写比较,前缀判定用)。
     *  v1.3.6d 扩容:sensenova 等推理模型的英文推理开头("The user..."/"We need..."
     *  /数字编号分析)也是思考——用户连续多轮看到思考进聊天气泡,根因是这些形态
     *  没在判定表里。宁可错杀(极少数朋友式英文回复开头撞上)也不漏放。 */
    private val THINKING_PREFIXES = listOf(
        "thinking process:", "thought process", "let me think", "让我想", "让我思考", "思考过程", "【思考", "reasoning:",
        // 英文推理开头形态(sensenova/DeepSeek-R1 常见)
        "the user", "we need", "i need", "the task", "first,", "okay,", "let's", "let me",
        "analyze the", "looking at", "the instruction", "the prompt", "the request", "the question",
        "1. **", "1. analyze", "1. analyze the request",
    )

    /** 上面这些英文前缀太宽(朋友式回复也可能以 "Okay,..." 开头)——英文形态只在
     *  模型确实产出了独立 reasoning 字段、或带明显推理结构(多行编号/加粗列表)
     *  时才当思考剥离;中文标记任意条件都剥。 */
    private fun looksLikeThinking(text: String, hasReasoning: Boolean): Boolean {
        val head = text.trimStart()
        if (head.isEmpty()) return false
        // 中文标记:无条件判思考
        val zhMarkers = listOf("思考过程", "让我想", "让我思考", "【思考")
        if (zhMarkers.any { head.startsWith(it) }) return true
        // 英文明确思考记号:无条件
        val enMarkers = listOf("thinking process:", "thought process", "reasoning:")
        if (enMarkers.any { head.startsWith(it, ignoreCase = true) }) return true
        // 英文弱形态(可能正常回复):需佐证——独立 reasoning 非空,或多行且带编号
        val weakEn = listOf("the user", "we need", "i need", "the task", "first,", "let me", "let's",
            "analyze the", "looking at", "the instruction", "the prompt", "the request")
        if (weakEn.any { head.startsWith(it, ignoreCase = true) }) {
            val bulletLines = head.lines().count { Regex("""^\s*(\d+\.|[-*·•])\s""").containsMatchIn(it) }
            return hasReasoning || bulletLines >= 2 || head.lines().size >= 4
        }
        // "1. **xxx**"直接命中
        if (Regex("""^1\.\s*\*\*""").containsMatchIn(head)) return true
        return false
    }

    /** 容错解析技能路由 JSON(skill/args)。unknown/非技能 → null(由 route 归为聊天)。 */
    private fun parseIntent(raw: String): IntentPlan? {        return try {
            val direct = runCatching { JsonParser.parseString(raw.trim()).asJsonObject }.getOrNull()
            val obj = direct ?: run {
                val m = Regex("""\{.*}""", RegexOption.DOT_MATCHES_ALL).find(raw)
                if (m == null) return null
                runCatching { JsonParser.parseString(m.value).asJsonObject }.getOrNull()
            } ?: return null
            val skillKey = obj.get("skill")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            if (skillKey == "unknown") return null
            // 技能 key 必须真实存在,模型编造的 key 归为聊天(用户能看到模型的困惑回复)
            if (skillByKey(skillKey) == null) return null
            val args = obj.getAsJsonObject("args")?.entrySet()?.associate { it.key to (it.value?.takeIf { p -> p.isJsonPrimitive }?.asString ?: "") } ?: emptyMap()
            val steps = obj.getAsJsonArray("steps")?.mapNotNull { it?.takeIf { p -> p.isJsonPrimitive }?.asString } ?: emptyList()
            IntentPlan(skillKey, args, steps)
        } catch (_: Exception) { null }
    }

    /**
     * v1.3.4: 事实性问题的本地兜底判定——弱模型(sensenova-flash-lite 等)对
     * "[[FACT]] 三选一"新约定的遵守度不可靠,模型忘了标时本地再判一层:
     * 疑问语气(问号/吗/呢) + 事实类疑问词(谁/什么时候/哪一年/多少/是什么/为什么…)
     * 即视为事实问题,需要联网。问候/闲聊/主观表达(觉得/好听/推荐)不含这些词。
     */
    fun looksLikeFactQuestion(intent: String): Boolean {
        val q = intent.trim()
        val interrogative = q.endsWith("?") || q.endsWith("？") || q.contains("吗") || q.contains("呢") ||
            Regex("""(谁|什么|何时|哪|几|多少|为什么|为啥|怎么|如何|是不是|有没有)""").containsMatchIn(q)
        if (!interrogative) return false
        return Regex("""(谁|什么时候|哪一年|哪年|几几年|多少|是什么|指的是什么|为什么|为啥|什么意思)""").containsMatchIn(q)
    }
}

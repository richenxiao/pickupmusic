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

    /** v1.3.8: 循环模式的语义表示——技能层不碰 Media3 的 REPEAT_MODE_* 常量,
     *  由 MainViewModel 的桥实现负责翻译成 PlayerController 的值。 */
    enum class RepeatMode { OFF, ALL, ONE }

    /**
     * v1.3.8: 「按发布时间排序歌手作品」的结果。
     *
     * [detail] 承载**真实失败原因**(LLM 429 / 未搜到资料 / 未配 Key / 解析不出日期),
     * 由技能原样回执给用户——而不是笼统报一句"失败了"。歌手页的进度行也是同一套文案,
     * 两个入口说同一件事。
     */
    data class AlbumDateOutcome(val datedCount: Int, val detail: String = "")

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
        /** v1.3.8: 睡眠定时——到点淡出并暂停。minutes<=0 = 取消(见 PlayerController.setSleepTimer)。 */
        fun setSleepTimer(minutes: Int)
        /** v1.3.8: 睡眠定时——本曲结束后模式。 */
        fun setSleepTimerEndOfTrack()
        /** v1.3.8: 睡眠定时当前状态文案(PlayerController.sleepStatusText),用于回执确认。 */
        fun sleepTimerStatus(): String

        // ── v1.3.8: 播放控制与播放模式(playback_control / playback_mode 技能) ──
        // 只操作播放状态,**不改音乐库内容**——与"不可逆操作永不接入 Agent"的约定一致
        // (见项目约定:删除/清空/覆盖式导入这类一律不做)。
        /** 播放这批曲目(队列 + 起点)。 */
        fun playTracks(tracks: List<com.shiyin.music.data.Track>, startId: Long)
        fun pausePlayback()
        fun resumePlayback()
        fun nextTrack()
        fun previousTrack()
        fun isPlaying(): Boolean
        fun setShuffle(on: Boolean)
        fun isShuffleOn(): Boolean
        fun setRepeatMode(mode: RepeatMode)
        fun repeatMode(): RepeatMode
        /** 变速(沿用用户的复古变速偏好;写回设置,重启后保持)。 */
        fun setPlaybackSpeed(speed: Float)

        // ── v1.3.8: 收听统计(只读) ──────────────────────────────────────
        /** 本周收听统计。**口径与统计页共用同一实现**(data/stats/ListeningStats.kt),
         *  保证两个入口给出的数字是同一件事。 */
        suspend fun weeklyListeningStats(): com.shiyin.music.data.stats.WeeklyStats
        /** 按 mediaId 取曲目(含「完整专辑」容器曲目,它们不在 lib() 里)。 */
        fun trackById(id: Long): com.shiyin.music.data.Track?

        /**
         * v1.3.8: 按官方发布时间排序某歌手的专辑/EP/单曲(联网查发行日期)。
         *
         * **复用歌手页「AI 排时间」的同一实现与同一套作品筛选规则**(专辑位歌手 +
         * 排除合集),保证 Agent 与歌手页给出同一份作品集、同一个顺序——两个入口
         * 说同一件事。**写库**(album_art_cache.releaseDate + artist_album_order)。
         */
        suspend fun sortArtistAlbumsByDate(artistName: String): AlbumDateOutcome
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

    /** v1.3.8(P2):步骤面板的一行。[id] 必须与技能在 execute 里回调 onStep 用的 id
     *  **完全一致**——面板行 id == 语义 id,映射关系不复存在,也就没有错位可言。 */
    data class PanelStep(val id: String, val label: String)

    /** 技能接口。各技能实现 [execute],内部编排 + 回调 [onStep] + 返回结果。
     *
     *  v1.3.8(D1-A 决策):[parameters] —— 该技能在原生 tool calling 里的 JSON Schema
     *  (即 tools[].function.parameters 的正文)。**与实现同文件声明**,而不是集中放在
     *  分发层:本项目反复出现「契约与实现分居两处 → 漂移」的模式(步骤行映射表、
     *  批量编排),多写一段声明换掉这个风险是划算的。
     *
     *  新增技能只需:①本文件的 [skills] 列表加一行(声明"我存在") ②实现类里写
     *  key/description/parameters/panelSteps/execute——**不需要改分发逻辑或
     *  MainViewModel**。多路径技能用 [panelSteps] 按 args 分流。
     */
    interface AgentSkill {
        val key: String
        val description: String
        /** JSON Schema 对象文本,如 `{"type":"object","properties":{...}}`。 */
        val parameters: String
        /** 执行前就渲染的步骤清单(按执行顺序)。返回空 = 这个技能不显示步骤面板。
         *
         *  v1.3.8(P2):取代 MainViewModel 里的 `panelStepsFor(skillKey)` + 语义 id→行号
         *  switch 两处硬编码。默认空实现,普通技能覆写即可。 */
        fun panelSteps(args: Map<String, String>): List<PanelStep> = emptyList()
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
        com.shiyin.music.data.ai.skills.SleepTimerSkill,
        com.shiyin.music.data.ai.skills.SongStorySkill,
        com.shiyin.music.data.ai.skills.PlaybackControlSkill,
        com.shiyin.music.data.ai.skills.PlaybackModeSkill,
        com.shiyin.music.data.ai.skills.ListeningStatsSkill,
        com.shiyin.music.data.ai.skills.AlbumDatesSkill,
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
            // 协议记号不进用户气泡。v1.3.8: 事实性判定已由原生工具调用(web_search)
            // 承担,本正则链路只剩「供应商不认 tools」时的降级通道(见 AgentTools.dispatch)。
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
     * v1.3.2 / v1.3.8(D2):高频指令的本地快速路径——零 LLM 往返直接选中技能。
     *
     * **v1.3.8 收窄:只保留 album_sort 一条。** 原实现里 artist_fix / track_rename /
     * library_query 的正则消歧全部删除。
     *
     * 删除理由:那套正则本质是**用本地正则手工复刻工具 description 该承担的语义消歧**,
     * 且已反复踩坑——v1.3.6e/f 连续两次修补(ruleCtx/artistCtx 互斥、"去掉歌名的-
     * 歌手名后缀"被 artist_fix 抢走、指令没踩中正则导致 args 全空落回错误路径)。
     * 原生 tool calling 生效后语义消歧已由模型读工具描述完成,再留一套正则 =
     * 新旧两套消歧机制并存,风险只是换了战场。
     *
     * 保留 album_sort 的理由:"打开专辑 → 排成正确顺序"是 golden path,高频且句式固定,
     * 值得保住 0 次 LLM 的即时响应。
     *
     * steps 返回空——步骤行文案统一由 MainViewModel.panelStepsFor 派生,模型不再产出清单。
     */
    fun fastPathPlan(intent: String): IntentPlan? {
        // 动作句式:须有「排/整理/修正 + 顺序/曲序」或「按官方/正确顺序」。
        // 纯聊天里出现"顺序"二字(如"为什么会卡在写回顺序上")不会误命中。
        val sortHit = Regex("""(排|整理|修正)(成|为|到|一下)*(正确|官方|正式)?(的)?(曲目|播放)?(顺序|曲序)""")
            .containsMatchIn(intent) ||
            Regex("""按\s*(官方|正确)\s*(顺序|曲序)""").containsMatchIn(intent)
        if (!sortHit) return null
        // v1.3.4: 书名号里抓到的名字做资格判定——纯数字(如用户说"排第15首歌"被《15》
        // 误抓)、单字符不作为专辑名;空则不带 album 参数(技能自己回退当前专辑页)。
        val albumArgRaw = Regex("""《([^》]+)》""").find(intent)?.groupValues?.get(1)
            ?: Regex("""「([^」]+)」""").find(intent)?.groupValues?.get(1)
        val albumArg = albumArgRaw?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() } && it.length >= 2 }
        val args = albumArg?.let { mapOf("album" to it) } ?: emptyMap()
        return IntentPlan("album_sort", args, emptyList())
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
                // v1.3.8: 原为 Regex("""\{.*}""")——裸 '}' 在 Android 上编译抛
                // PatternSyntaxException(JVM 单测用桌面正则,测不出来)。
                val json = com.shiyin.music.data.firstBalancedJsonObject(raw) ?: return null
                runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
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

}

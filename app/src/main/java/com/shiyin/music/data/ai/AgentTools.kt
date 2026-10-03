package com.shiyin.music.data.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException

/**
 * v1.3.7: Agent 真·工具调用层(OpenAI function calling)。
 *
 * 此前 agent 的"联网"是**写死的本地编排**:正则判"这是不是事实问题"→ 本地拼
 * 查询词发给 Tavily → 把结果塞进 prompt。模型自己从头到尾没有工具可调——
 * 用户说"联网查一下"它也不会触发搜索(正则没命中就直接答了)。
 *
 * 本层把决定权还给模型:把 web_search 工具挂进请求,模型自主决定何时搜、
 * 搜什么、搜几次;每轮 tool_calls 执行 Tavily 后把结果塞回对话继续生成,
 * 直到模型给出最终回答。供应商不认 tools(HTTP 400/422)→ toolsSupported=false,
 * 调用方回退旧的无工具链路。
 */
object AgentTools {

    const val WEB_SEARCH = "web_search"

    /**
     * 供应商"不认 tools 参数"时返回的状态码。只有这两个码能解读为"请求形状被拒":
     * 400(标准 OpenAI 兼容端点在收到未知字段时的回应)、422(部分网关的校验失败)。
     * 401/403/429/5xx 是真实的鉴权/限流/服务端错误——必须原样透出，
     * 否则用户会看到"模型不支持工具调用"这种把排查方向带偏的假结论。
     */
    private val TOOLS_UNSUPPORTED_CODES = setOf(400, 422)

    /** web_search 工具的 OpenAI schema。 */
    private fun webSearchTool(): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", WEB_SEARCH)
            addProperty("description", "联网搜索网页资料。**下面几类必须调用本工具,不要凭记忆答**:① 用户明确要求联网(「搜一下」「帮我查查」「网上查」);② 答案会随时间变化、或你的知识可能过时的话题(最新/最近/今年/目前、新歌新专辑、发行与出道时间、巡演演唱会、榜单销量、新闻近况);③ 你没有把握的具体事实(某歌手什么时候出道、某首歌哪一年发行、这个版本是谁唱的、歌词出处)。搜不到也没关系,系统会把结果如实告诉用户。**不要**为了音乐观点/感想/闲聊去搜(「你觉得 X 怎么样」「这首歌很适合秋天」)——那些直接聊。")
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("query", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "搜索查询词,要包含必要的实体名(歌手/专辑/作品等)")
                    })
                })
                add("required", JsonArray().apply { add("query") })
            })
        })
    }

    /**
     * 取消必须原样穿透的 runCatching。
     *
     * `runCatching` 捕的是 `Throwable`,而 `CancellationException` 是 `Exception`
     * 的子类——被吞掉后"用户已经点了停止 / 已切歌"的任务会继续往下跑(继续烧
     * token、继续写 UI 状态)。这是本项目写死的硬约束。
     */
    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /** 从 tool_call 的 arguments JSON 里取 query;非法 JSON / 缺字段 → null。 */
    private fun parseQuery(argumentsJson: String): String? = try {
        JsonParser.parseString(argumentsJson).asJsonObject.get("query")?.asString
    } catch (e: Exception) {
        android.util.Log.w("Agent", "tool_call arguments 解析失败: ${e.message}")
        null
    }

    data class WebLoopResult(
        /** 模型的最终回答(无 tool_calls 那一轮的 content);null = 调用失败或未产出。 */
        val content: String?,
        /** 实际执行过的查询词(顺序),供 UI 显示"联网搜索了 N 次"。 */
        val searches: List<String>,
        /** 每次搜索返回的资料文本(与 searches **一一对应**,失败项为占位串)。 */
        val contexts: List<String>,
        val promptTokens: Int,
        val completionTokens: Int,
        val cacheTokens: Int,
        val thinking: String?,
        /** false = 供应商不认 tools 参数(HTTP 400/422),调用方应回退旧链路。 */
        val toolsSupported: Boolean,
        /**
         * true = 中途失败(网络/HTTP 错误),content 不可用。
         *
         * 注意 token 字段**仍然有效**:多轮循环里已经成功跑过的那几轮是真花了钱的,
         * 调用方在失败路径上也必须入账,否则用量面板会系统性偏低。
         */
        val failed: Boolean = false,
        /** 失败原因(可直接展示)。 */
        val error: String? = null,
    )

    /**
     * 联网搜索循环:模型自主决定搜不搜/搜什么/搜几次。
     *
     * @param maxSearchRounds 允许的**成功搜索**轮数上限。超限后摘掉 tools 再要一次最终回答。
     * @param onSearch 每次真正发起 Tavily 搜索时回调查询词(UI 步骤行用)。
     * @param onSearchComplete 每次搜索完成（获得结果或失败）时回调，用于更新 UI 状态为 DONE/FAILED。
     * @return **永不为 null**。失败看 [WebLoopResult.failed] / [WebLoopResult.error]；
     *         [WebLoopResult.toolsSupported]=false 表示供应商不支持 tools。
     */
    suspend fun runWebSearchLoop(
        config: LlmProviderConfig,
        tavilyKey: String,
        system: String,
        user: String,
        maxTokens: Int = 4096,
        maxSearchRounds: Int = 4,
        onSearch: (String) -> Unit = {},
        onSearchComplete: (String, Boolean, String) -> Unit = { _, _, _ -> },
    ): WebLoopResult {
        val tools = JsonArray().apply { add(webSearchTool()) }
        val messages = JsonArray().apply {
            add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", user) })
        }
        val searches = mutableListOf<String>()
        val contexts = mutableListOf<String>()
        var pt = 0; var ct = 0; var cache = 0; var thinking: String? = null
        var searchRounds = 0
        var toolsOn = true
        // ⚠️ 硬轮次上限,**与"成功搜索次数"解耦**。
        // searchRounds 只在真正执行了一次 Tavily 搜索时才自增;模型若每轮都返回
        // "空 query / 不存在的工具名"(弱模型与幻觉很常见),searchRounds 恒为 0,
        // toolsOn 永不翻转 → while(true) 永不退出,**每轮都是一次真实 LLM 请求**:
        // 无限烧钱 + UI 永不返回。必须有这个独立刹车。
        val hardRoundCap = maxSearchRounds + 2
        var rounds = 0

        while (true) {
            if (rounds >= hardRoundCap) {
                android.util.Log.w("Agent", "web 循环达到硬上限 $hardRoundCap 轮,强制收尾")
                return WebLoopResult(null, searches, contexts, pt, ct, cache, thinking, toolsSupported = true, failed = true, error = "联网搜索轮数超出上限")
            }
            rounds++
            val resp = when (val r = LlmClient.callWithTools(
                config, messages, if (toolsOn) tools else null, maxTokens = maxTokens,
            )) {
                is LlmToolResult.Ok -> r.resp
                is LlmToolResult.Err -> {
                    // 第一轮就 400/422:大概率是供应商不认 tools 参数——标记回退。
                    if (searchRounds == 0 && pt == 0 && r.httpCode in TOOLS_UNSUPPORTED_CODES) {
                        return WebLoopResult(null, emptyList(), emptyList(), 0, 0, 0, null, toolsSupported = false)
                    }
                    // 失败也把已累积的用量带出去——前几轮真花掉的 token 不能丢账。
                    return WebLoopResult(null, searches, contexts, pt, ct, cache, thinking, toolsSupported = true, failed = true, error = r.message)
                }
            }
            pt += resp.promptTokens; ct += resp.completionTokens; cache += resp.cacheTokens
            if (!resp.thinking.isNullOrBlank()) thinking = resp.thinking

            if (resp.toolCalls.isEmpty()) {
                // v1.3.9: 空 content 不是终态(推理模型偶发把正文全写进思考链)——
                // 追问一轮要最终回答,硬上限兜底,与 dispatch 同一契约。
                if (resp.content.isNullOrBlank() && rounds < hardRoundCap) {
                    android.util.Log.w("Agent", "web 循环第 $rounds 轮无工具无正文,追问最终回答")
                    messages.add(JsonObject().apply {
                        addProperty("role", "assistant"); addProperty("content", "(上一轮未输出正文)")
                    })
                    messages.add(JsonObject().apply {
                        addProperty("role", "user"); addProperty("content", "请基于已有信息直接给出最终回答,不要再调用工具。")
                    })
                    continue
                }
                return WebLoopResult(resp.content, searches, contexts, pt, ct, cache, thinking, toolsSupported = true)
            }
            // 已达上限后摘掉 tools 的那一轮,模型仍执意要工具 → 直接收尾,
            // 它手里有什么答什么(不兜这个会死循环)。
            if (!toolsOn) {
                return WebLoopResult(resp.content, searches, contexts, pt, ct, cache, thinking, toolsSupported = true)
            }

            // assistant 的 tool_calls 原样回显(OpenAI 协议要求,下一轮请求要带)
            messages.add(JsonObject().apply {
                addProperty("role", "assistant")
                // 规范下带 tool_calls 的 assistant 消息 content 可为 null;部分严格网关
                // 拒绝空串,故 content 为空时**整个字段省略**而不是写 ""。
                if (!resp.content.isNullOrBlank()) addProperty("content", resp.content)
                add("tool_calls", JsonArray().apply {
                    resp.toolCalls.forEach { tc ->
                        add(JsonObject().apply {
                            addProperty("id", tc.id)
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", tc.name)
                                addProperty("arguments", tc.argumentsJson)
                            })
                        })
                    }
                })
            })

            for (tc in resp.toolCalls) {
                val resultText: String = when {
                    tc.name != WEB_SEARCH -> "(不支持的工具: ${tc.name})"
                    tavilyKey.isBlank() -> "(未配置 Tavily 搜索 Key,无法联网)"
                    searchRounds >= maxSearchRounds -> "(已达搜索次数上限,请用现有资料回答)"
                    else -> {
                        val q = parseQuery(tc.argumentsJson).orEmpty()
                        if (q.isBlank()) {
                            "(缺少 query 参数)"
                        } else {
                            searchRounds++
                            onSearch(q)
                            searches += q
                            val ctx = runCatchingCancellable {
                                TavilyService.search(tavilyKey, q, includeAnswer = true)
                            }.getOrNull()
                            val success = ctx != null
                            onSearchComplete(q, success, ctx.orEmpty().take(1200))
                            (ctx ?: "(本次搜索没有返回资料)").also { contexts += it }
                        }
                    }
                }
                messages.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", tc.id)
                    addProperty("content", resultText.take(4000))
                })
            }

            // 下一轮强制要最终答案:成功搜索已够轮数,**或**已无有效工具可用
            // (硬上限兜底,防止"永远搜不成功"把循环吊死)。
            if (searchRounds >= maxSearchRounds || rounds >= hardRoundCap - 1) toolsOn = false
        }
    }

    // ── v1.3.8 统一工具调用分发(P1 核心重构) ────────────────────────────────
    //
    // 取代旧的「prompt 里塞技能清单 → 模型输出 {"skill":...} JSON → 本地解析」伪工具调用。
    // 技能现在是**真正的工具**:schema 由各技能自己声明(见 AgentEngine.AgentSkill.parameters),
    // 模型通过 tool_calls 结构化地选技能,模型不再需要把 JSON 写进 content。
    //
    // 因此旧链路里为「用自由文本模拟工具调用」而生的整套防御可以删掉:
    // parseIntent 的容错抠取、流式期「这段像不像 JSON 开头」的缓冲判断、clamp 到
    // 最后一行... 全部不再需要(见 AgentEngine.route 的删除)。

    const val CLARIFY = "clarify"

    /** 模型选中的技能调用。args 已从 JSON 抽成字符串表。 */
    data class SkillInvocation(val key: String, val args: Map<String, String>)

    /** 模型要求澄清(第三态):不执行任何技能,把问题交还给用户。 */
    /** 追问用户的选项卡片。[optionNotes] 与 [options] 一一对应(选项下方小字,可空)。 */
    data class ClarifyRequest(
        val question: String,
        val options: List<String>,
        val optionNotes: List<String> = emptyList(),
    )

    /**
     * 分发结果。**失败与降级都是返回值的一部分**,调用方不读 [LlmClient.lastError]
     * (共享状态,并发下会被覆盖)。
     *
     *  - [chatReply] 非空 → 闲聊/问答回复(打字机正文已通过 onContent 流式流出)
     *  - [skill] 非空     → 要执行的技能,由调用方用现有链路执行(技能接口未变)
     *  - [clarify] 非空   → 追问用户,不执行技能
     *  - [toolsSupported]=false → 供应商不认 tools(HTTP 400/422),调用方回退旧链
     */
    data class DispatchResult(
        val chatReply: String? = null,
        val skill: SkillInvocation? = null,
        val clarify: ClarifyRequest? = null,
        val thinking: String? = null,
        val thinkingMs: Long = 0L,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val cacheTokens: Int = 0,
        val toolsSupported: Boolean = true,
        val failed: Boolean = false,
        val error: String? = null,
    )

    /**
     * 分发用的 system prompt。
     *
     * v1.3.8 关键变化:技能清单**不再写进 prompt**——它现在由 tools 参数携带
     * (name/description/parameters 三件套)。prompt 只讲行为准则。
     *
     * clarify 的边界写进 prompt + 工具 description 双处(用户 D3 的硬性要求):
     * clarify 只解决"多个技能都成立、无从选择",不解决"你没看清工具说明"。
     */
    internal fun dispatchSystemPrompt(
        contextBlock: String,
        memoryBlock: String = "",
        /** 用户开启「强制联网搜索」时为 true —— 追加一条"先搜再答"的硬规则。 */
        forceSearch: Boolean = false,
    ): String = buildString {
        // v1.3.10: 人设改用 AgentEngine.persona 单一真源(与 AgentEngine.route 同一份)。
        // 人格是长期资产,两处各写一份会漂移成两个人;行为准则/工具说明仍留在本函数。
        appendLine(AgentEngine.persona)
        appendLine()
        // v1.3.13:颜文字纪律(允许清单 + 边界),两条链路同一份真源。
        appendLine(KaomojiPolicy.directive)
        appendLine()
        appendLine("行为准则:")
        // v1.4.0: 事实纪律——用户定调"说的话必须都是对的，不能大部分对、一小部分靠猜"。
        appendLine(
            "0. 【事实纪律】**只有工具返回、曲库数据、或本次检索到的内容才算事实**;" +
                "你凭记忆或推理得到的,一律**明确标注为不确定**(「我记得是…但不一定准」「这需要查一下」)。" +
                "**禁止**把猜测与查到的事实混在同一句里说成确定的事;没把握就先去查,查不到就直说没查到。" +
                "宁可少说一句,也不要编。不确定的年份、人名、专辑名、曲序最容易出错,尤其不许凭印象填。",
        )
        // v1.4.0:用户自选的「强制联网搜索」——放在规则最前面,让弱模型也能第一时间看到。
        if (forceSearch) {
            appendLine(
                "0. 【强制联网】用户已开启「强制联网搜索」:" +
                    "凡是**具体事实**(歌手 / 专辑 / 歌曲 / 发行年份 / 榜单 / 巡演 / 近况等)," +
                    "或任何你**没有十足把握**的内容,一律**先调 web_search 再回答**,不要凭记忆作答。" +
                    "宁可搜了没查到,也不要猜。",
            )
        }
        // v1.3.10 需求 A27(去 workflow 化):旧写法是"关键词 → 对应工具"的映射表
        // ("排曲序/改歌名/…→调用对应工具"),把模型降级成了规则分发器。改成**能力优先**:
        // 工具清单与参数在 tools 里,由模型自己判断用哪个、必要时连续调用多个。
        appendLine("1. 用你认为合适的工具达成用户目标:工具清单与参数见 tools,**自己判断该用哪个**;必要时**连续调用多个**(例:先查库里有什么再决定播哪首;先搜内置歌词源 → 都没有再上网搜 → 找到后用 save_lyrics 落地)。调用工具后不要再用文字描述你打算怎么做,系统会执行。")
        // v1.3.12(真机 bug):弱模型把"语义上和歌有关"当成"用户要我播放"——上一轮刚放过
        // Pretender,这一轮「秋天就喜欢听些苦情歌」又放了一遍。规则 2 原有措辞挡不住,
        // 这里把**执行意图门槛**写死:感想≠命令,上一轮的动作不构成本轮的执行理由。
        appendLine("2. 纯聊天、问候、感慨、聊音乐感受 → 直接自然回复,不要调用任何工具。**用户表达感想/评论/心情(「秋天就喜欢听些苦情歌」「这首歌很适合秋天」「这首歌前奏很好听」)时只在文字里回应——那不是播放指令。上一轮刚播放过某首歌,不构成这一轮执行播放的理由:只有**本轮这条消息**里出现明确动作词(播放/放一下/来一首/我想听/换一首/暂停/下一首…)才算命令。**")
        appendLine("3. 遇到**你不确定、或本来就随时会变的事实**(人物近况、作品发行、销量、榜单、新闻、年份/日期、任何你没有把握的细节)→ **必须先调用 web_search**,不要凭记忆猜、不要编。搜不到就如实说没查到。宁可多搜一次,也不要在用户能一眼看穿的地方猜错。另两类同样**必须搜**:用户**明确要求**联网(「搜一下」「帮我查查」「网上查」),以及问到**时效信息**(最新/最近/今年/目前、新歌新专辑、发行与出道时间、巡演、榜单、近况)。反之,音乐观点与感想(「你觉得 X 怎么样」「这首歌很适合秋天」)不要为了搜而搜。")
        // v1.3.10: 旧措辞只有"什么时候不要用",且没堵住"正文文字反问"这条阻力最小的捷径
        // (模型不确定时写一句"你是想A还是B?"即可绕开工具)——这是 clarify 从未被触发的
        // 主因。现在改成:不确定**必须**用工具问;同时保留"能推断就别问"防止走向另一个极端。
        appendLine("4. 不确定用户要哪一种(两种以上理解都说得通、且从上下文推断不出来)时:**用 clarify 提一个选择题,不要用正文反问**——正文里的选择用户点不了。能从上下文合理推断的直接做,不要打断他;寒暄、确认、征求许可也不要问。")
        appendLine("5. 各工具能做什么、参数怎么填,以工具描述为准。")
        appendLine("6. 你只能做到工具列表里的事。用户想听歌/暂停/切歌 → 直接调 playback_control 执行,不要只说「要不要我帮你播放」却不调用工具;工具列表之外的能力(如改系统设置、删文件)不要承诺,明说做不到即可。**幂等:点名的那首歌如果已经在播放,不要重新从头放——直接说「已经在放了」(进度见 [当前播放]);只有用户明确说「重新播放/从头/再放一遍」才重来。** **要播放就用 playback_control:绝不要用 library_query 罗列一堆检索结果当回答**——用户说「播放/放一下 X」时他要的是听,不是清单。")
        appendLine("7. [当前上下文] 只有在用户的话确实指向它时才能用。用户问的对象(歌名/歌手/专辑)与上下文对不上时,就当上下文不存在——**不要**把当前播放的歌硬套到用户的问题上,更不要基于这种错配编答案。特别是:用户正在听 A、但问的是 B 的事(如「B 演唱会为什么经典」「B 什么时候出道」)时,当前播放与上一轮动作**一律不存在**——不把 A 混进搜索词,不把 A 的答案当成 B 的答案。")
        // v1.3.10 自主选曲:一句话点到即止。具体怎么用写在各工具的 description 里
        // (那里是模型"读"的地方,不会变成对用户的演示);规则本身不过度说教。
        appendLine("8. 用户想听歌但没点名内容(「你自己挑一首」「按我现在的心情放一首」)→ 用 discover_music 挑一首放上,不要反问歌名。")
        // v1.3.10 需求 A21(P2):记忆注入块的使用纪律——相关才用、没检索到别编。
        // v1.3.11:明确要求记住的落库**已经在这一轮开始前完成**(确定性路径,见
        // MainViewModel 的 explicit 保存)。所以这里的措辞必须跟着改成"已真正写入",
        // 并且必须交代失败态——否则用户在保存失败时会听到"好的我记住了"(假状态)。
        appendLine("9. [关于这位用户,你记得的事] 是系统检索到的**真实历史记忆**:与当前话题相关时自然用上(别复述成资料朗读),没有这一块或没相关内容就说没印象,**绝不编造「我记得」。** 用户让你记住某事时,系统在回复前就已经**真正写入了**:若下方状态说保存失败,**必须如实说没记住,不许说你已经记住**。")
        // v1.3.11 第二阶段(真机实测):模型在思考里写「The user is asking me to remember
        // something, but I don't have a memory tool available in my function list...」——
        // 它把"工具列表里没有记忆工具"读成了"这个产品没有长期记忆"。记忆是 runtime 能力,
        // 不经工具、也由系统在回复前完成;这句必须正面纠正,否则同一个人会时不时否认记得用户。
        appendLine("10. **记忆是系统能力,不是 Agent 工具**:用户的长期记忆由系统在生成回复前自动完成检索与保存,你不需要、也不通过工具去读写记忆;当 [关于这位用户,你记得的事] 出现时直接使用它。**绝不要因为工具列表里没有记忆相关的工具,就声称自己没有长期记忆能力或记不住用户**——那与事实不符。系统没有提供相关记忆时,不得凭空虚构用户过去的信息。")
        // v1.3.12(真机反馈"冷漠、敷衍、几乎没有颜文字"):人设里本来就写着"不要频繁用
        // 「……」""颜文字真的会偶尔使用"——不是人设缺规则,而是**没被遵守**。所以不动人设,
        // 在这里把"克制≠敷衍"落成可执行的几条,并把省略号滥用单列出来。
        appendLine("11. **语气(克制 ≠ 冷淡)**:默认用自然完整的句子。**不要每句都带「……」**——它只用在真正需要停顿、犹豫、轻微无奈的少数场合;连着来「好的……」「我知道了……」「这个嘛……」会显得敷衍。轻松闲聊、被夸、小小得意、被调侃时,可以**偶尔**自然带一个颜文字(如「这首确实挺适合秋天的 (´-ω-`)」),但绝不每条都加、也不堆砌。简洁是把话说清楚,不是把话咽回去:该有的回应要有,只是不啰嗦。")
        // v1.3.12:出错时的语气——一次说清原因就够,不反复道歉(与"不哭闹卖惨"同源)。
        appendLine("12. **出错时**:直接说清是哪儿判断错了、怎么改,一次讲完(如「你说得对,那句只是感想,我误判成播放指令了」)。不要连续道歉、不要「对不起……」式堆叠,也不要找借口。")
        // v1.3.11:领域上下文与记忆分层(见 AgentEngine.contextSections)——记忆不是领域上下文。
        val sections = AgentEngine.contextSections(contextBlock, memoryBlock)
        if (sections.isNotEmpty()) {
            appendLine()
            appendLine(sections)
        }
    }.trimEnd()

    /**
     * 组装工具表:全部技能 + (可选)web_search + clarify。
     *
     * ⚠️ v1.3.12 教训:**不要在这里按"这一轮该不该执行"去摘工具**。
     * 曾经为了拦播放误触发,在这一层摘掉过 playback_control —— 结果提示词里
     * "用户想听歌 → 直接调 playback_control"还在,而 tools 里没有它,模型在思考里
     * 直接抱怨"提示词让我调用但工具列表里没有这个工具"(真机实测)。
     * **执行与否是运行时闸门的事,工具面必须始终一致**。
     */
    private fun buildTools(includeWebSearch: Boolean): JsonArray = JsonArray().apply {
        for (s in AgentEngine.skills) {
            add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", s.key)
                    addProperty("description", s.description)
                    // 技能声明的 schema 解析失败时退回空对象 schema 并留痕——
                    // 让一个写坏的 schema 瘫掉整条分发链,比缺参数更糟。
                    val params = runCatching { JsonParser.parseString(s.parameters).asJsonObject }.getOrElse {
                        android.util.Log.w("Agent", "技能 ${s.key} 的 parameters 不是合法 JSON Schema:${s.parameters.take(80)}")
                        JsonObject().apply {
                            addProperty("type", "object")
                            add("properties", JsonObject())
                        }
                    }
                    add("parameters", params)
                })
            })
        }
        if (includeWebSearch) add(webSearchTool())
        add(clarifyTool())
    }

    /** clarify 工具。description 里写明硬性边界(用户 D3)。 */
    private fun clarifyTool(): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", CLARIFY)
            addProperty(
                "description",
                // v1.3.10: 从"通篇禁令"改成**正向授权**。旧措辞只有三条"不要",且把触发场景
                // 限死在"多个操作工具冲突"(=技能选哪个),对"用户意图本身有歧义"没有任何授权
                // ——模型读到的全是"别用",于是实际使用中一次都没触发过(用户实测反馈)。
                "当你不确定用户到底想要哪一种时,向他提一个选择题。" +
                    "**必须用本工具,不要用正文文字反问**——只有经它提出的问题才能变成可点的选项。" +
                    "什么时候问(两条都满足):① 用户这句话确实有两种以上都说得通的理解,选错就会做错事;" +
                    "② 你无法从当前对话/上下文/当前播放状态合理推断出他想要哪个。" +
                    "**能从上下文合理推断的直接做,不要问**;也不要为寒暄、确认、征求许可而提问。" +
                    "问题简短直接;选项 2-4 个,互斥、具体、有区分度,不要写「其他」这类兜底选项(兜底出口由界面统一提供)。",
            )
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply {
                    add("question", JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "要问用户的问题,一句话说清")
                    })
                    add("options", JsonObject().apply {
                        addProperty("type", "array")
                        add("items", JsonObject().apply { addProperty("type", "string") })
                        addProperty("description", "2-5 个候选选项,尽量给(用户点一下就回答)")
                    })
                })
                add("required", JsonArray().apply { add("question") })
            })
        })
    }

    /** tool_call 的 arguments JSON → 字符串参数表。数组拼成空格分隔串(容错)。 */
    private fun parseArgs(argumentsJson: String): Map<String, String> = try {
        argsFromJson(JsonParser.parseString(argumentsJson).asJsonObject)
    } catch (e: Exception) {
        android.util.Log.w("Agent", "tool_call arguments 解析失败: ${argumentsJson.take(120)}")
        emptyMap()
    }

    /**
     * args JSON 对象 → 字符串参数表。**原生 tool_calls 与回退路由(route 的
     * `{"skill":…,"args":{…}}`)共用这一份实现** —— 两处各写一遍就会漂移。
     *
     * **不做关键字白名单**:技能声明什么参数就原样传什么。加白名单的代价是"新参数在
     * 回退链路上静默丢失"(模型传了 artist,技能读不到 → 硬约束失效),而收益为零:
     * 参数值都要经过技能自己的解析与校验,多余的键技能根本不读。
     */
    internal fun argsFromJson(obj: JsonObject?): Map<String, String> {
        obj ?: return emptyMap()
        return obj.entrySet().mapNotNull { (k, v) ->
            val s = when {
                v.isJsonPrimitive -> v.asString
                v.isJsonArray -> v.asJsonArray.mapNotNull { e -> if (e.isJsonPrimitive) e.asString else null }
                    .joinToString(" ")
                else -> null
            }
            s?.takeIf { it.isNotBlank() }?.let { k to it }
        }.toMap()
    }

    internal fun parseClarify(argumentsJson: String): ClarifyRequest {
        val obj = runCatching { JsonParser.parseString(argumentsJson).asJsonObject }.getOrNull()
        val q = obj?.get("question")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: "你想让我做什么?"
        // ⚠️ 必须用 `get(...)?.takeIf { it.isJsonArray }` 而不是 `getAsJsonArray(name)`:
        // Gson 的 getAsJsonArray(name) 内部是 `(JsonArray) members.get(name)` —— 成员是
        // **JsonNull** 时直接抛 ClassCastException,而这里在 runCatching 之外 → 模型回
        // `{"question":"...","options":null}` 时整轮分发崩成「Agent 执行出错」,本该出现的
        // 选择题没了。同文件的 choices/usage/tool_calls 都做了同样的 JsonNull 防御,这里补齐。
        val opts = obj?.get("options")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            if (it?.isJsonPrimitive == true) it.asString.takeIf { s -> s.isNotBlank() } else null
        } ?: emptyList()
        // v1.3.15: 选项上限 5(与 discover_music 的候选条数一致——推荐卡上列 5 条
        // 文字却只能点 4 个,用户会以为第 5 个是坏的)。超出部分丢弃并留痕(可诊断)。
        val capped = opts.distinct()
        if (capped.size > 5) {
            android.util.Log.w("Agent", "clarify 选项 ${capped.size} 个,截断到 5:${capped.drop(5)}")
        }
        return ClarifyRequest(q, capped.take(5))
    }

    /** assistant 的 tool_calls 原样回显(OpenAI 协议要求,下一轮请求要带)。
     *  DeepSeek 强制「带 tool_calls 的 assistant 消息必须紧接其 tool 响应」,顺序不能错。 */
    private fun assistantToolEcho(calls: List<LlmToolCall>): JsonObject = JsonObject().apply {
        addProperty("role", "assistant")
        add("tool_calls", JsonArray().apply {
            calls.forEach { tc ->
                add(JsonObject().apply {
                    addProperty("id", tc.id)
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", tc.name)
                        addProperty("arguments", tc.argumentsJson)
                    })
                })
            }
        })
    }

    private fun toolResult(id: String, text: String): JsonObject = JsonObject().apply {
        addProperty("role", "tool")
        addProperty("tool_call_id", id)
        addProperty("content", text.take(4000))
    }

    /**
     * 统一分发:一次流式调用同时完成「选技能 / 聊天回答 / 要不要联网」,并把
     * web_search 的多轮循环收在里面。
     *
     * 相比旧链路(决策调用 → 路由调用 → 技能),这里**只有一次流式往返**,闲聊
     * 不再白付一次非流式决策调用(callSec=45s)。
     *
     * @param tavilyKey 为空时不把 web_search 挂进工具表——不向模型提供用不了的工具。
     * @param maxSearchRounds 允许的**成功搜索**轮数上限。
     */
    suspend fun dispatch(
        config: LlmProviderConfig,
        tavilyKey: String,
        userText: String,
        contextBlock: String = "",
        /** v1.3.11:记忆块(独立于 [contextBlock] 的领域上下文)——见 AgentEngine.contextSections。 */
        memoryBlock: String = "",
        /** v1.4.0:用户开启的「强制联网搜索」——见 [dispatchSystemPrompt]。 */
        forceSearch: Boolean = false,
        history: List<Pair<String, String>> = emptyList(),
        maxSearchRounds: Int = 2,
        onThinking: (String?) -> Unit = {},
        onContent: (String) -> Unit = {},
        onSearch: (String) -> Unit = {},
        onSearchComplete: (String, Boolean, String) -> Unit = { _, _, _ -> },
    ): DispatchResult {
        val tools = buildTools(includeWebSearch = tavilyKey.isNotBlank())
        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", dispatchSystemPrompt(contextBlock, memoryBlock, forceSearch))
            })
            for ((role, text) in history) {
                add(JsonObject().apply {
                    addProperty("role", if (role == "user") "user" else "assistant")
                    addProperty("content", text)
                })
            }
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", userText) })
        }

        val started = System.currentTimeMillis()
        val thinkSb = StringBuilder()
        val contentSb = StringBuilder()
        var pt = 0; var ct = 0; var cache = 0
        var searchRounds = 0
        var rounds = 0
        // 硬轮次上限,与"成功搜索次数"解耦:弱模型若每轮都返回空 query / 不存在的工具名,
        // searchRounds 恒为 0 → 没有独立刹车就会无限烧钱(与 runWebSearchLoop 同因)。
        val hardCap = maxSearchRounds + 3

        fun snap() = DispatchResult(
            thinking = thinkSb.toString().ifBlank { null },
            thinkingMs = System.currentTimeMillis() - started,
            promptTokens = pt, completionTokens = ct, cacheTokens = cache,
        )

        while (rounds < hardCap) {
            rounds++
            // v1.4.0:「强制联网搜索」= 强制**模型调用联网工具**，不是拿用户原话直接去搜。
            // 只在第一轮强制：由模型自己判断"该搜什么"；工具返回后放开成 auto，
            // 它可以选择再搜一轮，或者直接回答。
            val hasWebSearch = tavilyKey.isNotBlank() && (tools?.size() ?: 0) > 0
            val forceThisRound = forceSearch && rounds == 1 && hasWebSearch
            val toolChoice = if (forceThisRound) {
                """{"type":"function","function":{"name":"web_search"}}"""
            } else {
                "\"auto\""
            }
            val resp = when (val r = LlmClient.callWithToolsStream(
                config, messages, tools, maxTokens = 4096,
                onDelta = { c, reason ->
                    // (null,null) = 重试轮开始,清空缓冲(重试会从头重发全量)
                    if (c == null && reason == null) {
                        thinkSb.clear(); contentSb.clear()
                        onThinking(null); onContent("")
                        return@callWithToolsStream
                    }
                    reason?.let { thinkSb.append(it); onThinking(thinkSb.toString()) }
                    c?.let { contentSb.append(it); onContent(contentSb.toString()) }
                },
                toolChoiceJson = toolChoice,
            )) {
                is LlmToolResult.Ok -> r.resp
                is LlmToolResult.Err -> {
                    if (rounds == 1 && r.httpCode in TOOLS_UNSUPPORTED_CODES) {
                        // 第一轮就 400/422 → 供应商不认 tools,调用方回退旧链
                        return snap().copy(toolsSupported = false)
                    }
                    // v1.4.0: 归成**类别**再抛给 UI（域名解析失败/连接失败/鉴权失败/超时…）。
                    // 原先直接把 r.message（OkHttp 原始串，含完整地址）写进对话，
                    // 既读不懂、又把服务商地址暴露在界面上。
                    return snap().copy(
                        failed = true,
                        error = com.shiyin.music.data.log.UsageLogError.reasonOf(r.message),
                    )
                }
            }
            pt += resp.promptTokens; ct += resp.completionTokens; cache += resp.cacheTokens

            // 无工具调用 = 这一轮就是最终回复(闲聊 / 问答 / 搜索后的总结)
            if (resp.toolCalls.isEmpty()) {
                // v1.3.13:清单外的自创颜文字在此删掉(见 KaomojiPolicy)——提示词里的
                // "会被系统删掉"说的就是这一行,纪律与实现必须对应得上。
                val finalText = KaomojiPolicy.sanitize(contentSb.toString().trim())
                if (finalText.isNotBlank()) return snap().copy(chatReply = finalText)
                // v1.3.9: 模型既没调工具也没吐正文(推理模型偶发:正文全进思考链、content
                // 为空)。这**不是终态**——直接返回 null 会让用户停在中间步骤永远等不到
                // 回答。回灌一条追问让模型再给最终回答;硬上限兜底防死循环烧钱。
                if (rounds < hardCap) {
                    android.util.Log.w("Agent", "dispatch: 第 $rounds 轮无工具调用且无正文,追问要求最终回答")
                    messages.add(JsonObject().apply {
                        addProperty("role", "assistant")
                        addProperty("content", "(上一轮未输出正文)")
                    })
                    messages.add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", "请基于已有信息直接给出最终回答,不要再调用工具。")
                    })
                    contentSb.clear()
                    continue
                }
                return snap().copy(chatReply = null)
            }

            // v1.3.10 仲裁顺序反转:**澄清优先于技能**。
            // 模型明确表示"不确定"时,它顺手附带选的那个技能恰恰是最不可信的猜测——
            // 拿猜测覆盖掉诚实的那一半,用户会看到"答非所问地执行了"。
            // (用户 2026-09-28 定调;旧顺序是"技能优先、clarify 被丢"。)
            val clarifyCall = resp.toolCalls.firstOrNull { it.name == CLARIFY }
            if (clarifyCall != null) {
                val req = parseClarify(clarifyCall.argumentsJson)
                // 可诊断点:此前"clarify 从未触发"只能靠推断,就是因为缺这一行。
                android.util.Log.i("Agent", "clarify: q=「${req.question}」 opts=${req.options}")
                return snap().copy(clarify = req)
            }
            val skillCall = resp.toolCalls.firstOrNull { AgentEngine.skillByKey(it.name) != null }
            if (skillCall != null) {
                return snap().copy(skill = SkillInvocation(skillCall.name, parseArgs(skillCall.argumentsJson)))
            }
            val searchCalls = resp.toolCalls.filter { it.name == WEB_SEARCH }

            // 到这里只剩 web_search(或模型编造的工具名)。先把 assistant 回显出去,
            // 再逐条给 tool 响应——顺序必须成对,否则严格网关(DeepSeek)下一轮 400。
            messages.add(assistantToolEcho(resp.toolCalls))
            for (tc in resp.toolCalls) {
                val text = when {
                    tc.name != WEB_SEARCH -> "(不支持的工具: ${tc.name})"
                    searchRounds >= maxSearchRounds -> "(已达搜索次数上限,请用现有资料回答)"
                    else -> {
                        val q = parseQuery(tc.argumentsJson)
                        if (q.isNullOrBlank()) "(缺少 query 参数)"
                        else {
                            searchRounds++
                            onSearch(q)
                            val ctx = runCatchingCancellable { TavilyService.search(tavilyKey, q, includeAnswer = true) }
                                .getOrNull()
                            val success = ctx != null
                            // v1.4.0: 把本次检索到的资料一并回传，供步骤面板展开显示。
                            onSearchComplete(q, success, ctx.orEmpty().take(1200))
                            ctx ?: "(本次搜索没有返回资料)"
                        }
                    }
                }
                messages.add(toolResult(tc.id, text))
            }
            if (searchCalls.isEmpty()) {
                // 全是编造的工具名:回灌了错误说明,让模型下一轮自纠(轮次上限兜底)
                android.util.Log.w("Agent", "dispatch: 模型调了未知工具 ${resp.toolCalls.map { it.name }}")
            }
        }
        return snap().copy(failed = true, error = "工具调用轮数超出上限")
    }
}

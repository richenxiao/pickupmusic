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
            addProperty("description", "联网搜索网页资料。遇到实时信息、事实核查、发行日期、曲目表、人物/作品资料等你不确定或训练数据没有的内容时调用。")
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
    data class ClarifyRequest(val question: String, val options: List<String>)

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
    private fun dispatchSystemPrompt(contextBlock: String): String = buildString {
        appendLine("你是「拾音 PickUpMusic」音乐播放器里的 Agent,用户的听歌伙伴。性格:懂音乐、随和、有点幽默、说话像朋友,不说客服腔,不复述自己的功能清单(用户主动问才介绍)。中文回复简洁自然,一般 1-3 句。")
        appendLine()
        appendLine("行为准则:")
        appendLine("1. 用户想对音乐库做操作(排曲序/改歌名/改歌手名/找歌词/查库里有没有)→ 调用对应工具。调用后不要再用文字描述你打算怎么做,系统会执行。")
        appendLine("2. 纯聊天、问候、感慨、聊音乐感受 → 直接自然回复,不要调用任何工具。")
        appendLine("3. 需要实时信息、事实核查、作品或人物资料 → 调用 web_search。")
        appendLine("4. 只有当你确实无法在可用工具之间做出可靠选择时,才调用 clarify 追问。**不要**用 clarify 掩盖没看清工具说明——看完工具描述能判断该用哪个,就必须直接调用那个工具;也不要为寒暄、确认、征求许可而追问。")
        appendLine("5. 各工具能做什么、参数怎么填,以工具描述为准。")
        if (contextBlock.isNotEmpty()) {
            appendLine()
            appendLine("[当前上下文]")
            appendLine(contextBlock)
        }
    }.trimEnd()

    /** 组装工具表:全部技能 + (可选)web_search + clarify。 */
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
                "向用户追问以澄清意图。硬性边界:仅当【多个操作工具的语义都成立、你无法从中做出可靠选择】时才使用。" +
                    "不要用它弥补某个工具描述不清——若能依据现有工具描述做出可靠判断,必须直接调用那个工具。" +
                    "也不要为寒暄、确认、征求许可而调用它。",
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
                        addProperty("description", "2-4 个候选选项,尽量给(用户点一下就回答)")
                    })
                })
                add("required", JsonArray().apply { add("question") })
            })
        })
    }

    /** tool_call 的 arguments JSON → 字符串参数表。数组拼成空格分隔串(容错)。 */
    private fun parseArgs(argumentsJson: String): Map<String, String> = try {
        val obj = JsonParser.parseString(argumentsJson).asJsonObject
        obj.entrySet().mapNotNull { (k, v) ->
            val s = when {
                v.isJsonPrimitive -> v.asString
                v.isJsonArray -> v.asJsonArray.mapNotNull { e -> if (e.isJsonPrimitive) e.asString else null }
                    .joinToString(" ")
                else -> null
            }
            s?.takeIf { it.isNotBlank() }?.let { k to it }
        }.toMap()
    } catch (e: Exception) {
        android.util.Log.w("Agent", "tool_call arguments 解析失败: ${argumentsJson.take(120)}")
        emptyMap()
    }

    private fun parseClarify(argumentsJson: String): ClarifyRequest {
        val obj = runCatching { JsonParser.parseString(argumentsJson).asJsonObject }.getOrNull()
        val q = obj?.get("question")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            ?: "你想让我做什么?"
        val opts = obj?.getAsJsonArray("options")?.mapNotNull {
            if (it?.isJsonPrimitive == true) it.asString.takeIf { s -> s.isNotBlank() } else null
        } ?: emptyList()
        return ClarifyRequest(q, opts)
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
        history: List<Pair<String, String>> = emptyList(),
        maxSearchRounds: Int = 2,
        onThinking: (String?) -> Unit = {},
        onContent: (String) -> Unit = {},
        onSearch: (String) -> Unit = {},
    ): DispatchResult {
        val tools = buildTools(includeWebSearch = tavilyKey.isNotBlank())
        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", dispatchSystemPrompt(contextBlock))
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
            )) {
                is LlmToolResult.Ok -> r.resp
                is LlmToolResult.Err -> {
                    if (rounds == 1 && r.httpCode in TOOLS_UNSUPPORTED_CODES) {
                        // 第一轮就 400/422 → 供应商不认 tools,调用方回退旧链
                        return snap().copy(toolsSupported = false)
                    }
                    return snap().copy(failed = true, error = r.message)
                }
            }
            pt += resp.promptTokens; ct += resp.completionTokens; cache += resp.cacheTokens

            // 无工具调用 = 这一轮就是最终回复(闲聊 / 问答 / 搜索后的总结)
            if (resp.toolCalls.isEmpty()) {
                return snap().copy(chatReply = contentSb.toString().trim().takeIf { it.isNotBlank() })
            }

            val skillCall = resp.toolCalls.firstOrNull { AgentEngine.skillByKey(it.name) != null }
            val clarifyCall = resp.toolCalls.firstOrNull { it.name == CLARIFY }
            val searchCalls = resp.toolCalls.filter { it.name == WEB_SEARCH }

            // 技能优先:它是用户真实意图。澄清只在没有技能可执行时才有意义。
            if (skillCall != null) {
                return snap().copy(skill = SkillInvocation(skillCall.name, parseArgs(skillCall.argumentsJson)))
            }
            if (clarifyCall != null) {
                return snap().copy(clarify = parseClarify(clarifyCall.argumentsJson))
            }

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
                            runCatchingCancellable { TavilyService.search(tavilyKey, q, includeAnswer = true) }
                                .getOrNull() ?: "(本次搜索没有返回资料)"
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

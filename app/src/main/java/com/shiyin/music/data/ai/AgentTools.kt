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

    /**
     * 聊天链路的"要不要联网"判定——**模型自己决定**,不再是本地正则。
     *
     * 一轮调用:模型要么直接答(=不需要搜,content 丢弃),要么发起 tool_calls
     * (=执行搜索,把资料返回给调用方,由正式回复链路使用)。不做第二轮回答,
     * 省一次 LLM 往返。null=网络/配置失败(按不搜继续,不阻塞聊天);
     * toolsSupported=false=供应商不认 tools(调用方回退旧正则启发式)。
     */
    suspend fun decideAndSearch(
        config: LlmProviderConfig,
        tavilyKey: String,
        userText: String,
        maxQueries: Int = 2,
        onSearch: (String) -> Unit = {},
    ): WebLoopResult? {
        val tools = JsonArray().apply { add(webSearchTool()) }
        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", "你是联网搜索判断器。判断用户这条消息是否需要联网搜索才能准确回答:事实核查/实时信息/具体作品或人物资料/用户明确要求联网 → 调用 web_search(最多 $maxQueries 次,可按不同角度各搜一次);闲聊/主观感受/常识/对音乐库的操作指令 → 不要调用任何工具,直接回复任意简短内容。你的回复不会被展示,只有搜索动作有意义。")
            })
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", userText) })
        }
        val resp = when (val r = LlmClient.callWithTools(config, messages, tools, maxTokens = 512, callSec = 45)) {
            is LlmToolResult.Ok -> r.resp
            is LlmToolResult.Err ->
                return if (r.httpCode in TOOLS_UNSUPPORTED_CODES) {
                    // 供应商不认 tools → 调用方回退旧正则启发式(行为与上一版一致)
                    WebLoopResult(null, emptyList(), emptyList(), 0, 0, 0, null, toolsSupported = false)
                } else {
                    // 真实失败(鉴权/限流/网络):不搜也不阻塞聊天,但把原因留痕
                    android.util.Log.w("Agent", "搜索决策调用失败: ${r.message}")
                    null
                }
        }
        val searches = mutableListOf<String>()
        val contexts = mutableListOf<String>()
        resp.toolCalls.filter { it.name == WEB_SEARCH }.take(maxQueries).forEach { tc ->
            val q = parseQuery(tc.argumentsJson).orEmpty()
            if (q.isBlank()) return@forEach
            onSearch(q)
            searches += q
            val ctx = runCatchingCancellable { TavilyService.search(tavilyKey, q, includeAnswer = true) }.getOrNull()
            // searches 与 contexts 必须严格一一对应(调用方按下标/按数量配对展示)。
            // Tavily 失败时用占位串补位,不能只往 searches 里加。
            contexts += (ctx ?: "(本次搜索没有返回资料)")
        }
        return WebLoopResult(null, searches, contexts, resp.promptTokens, resp.completionTokens, resp.cacheTokens, resp.thinking, toolsSupported = true)
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
}

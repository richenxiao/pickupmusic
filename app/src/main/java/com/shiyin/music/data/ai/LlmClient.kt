package com.shiyin.music.data.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v1.2.2 Agent: LLM 响应（content + tokens usage）。usage 解析自 OpenAI 兼容响应的
 * `usage{prompt_tokens, completion_tokens}` 字段（DeepSeek/OpenRouter/智谱/通义均带）。
 */
data class LlmResponse(
    val content: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    /** v1.3.3: 模型思考链(如 DeepSeek-R1/Claude 的 reasoning 字段)——供 UI 折叠展示。 */
    val thinking: String? = null,
    /** v1.3.6: 缓存命中 token(usage.prompt_tokens_details.cached_tokens,
     *  DeepSeek 用 usage.prompt_cache_hit_tokens;没有=0)。用量面板"缓存命中"曲线。 */
    val cacheTokens: Int = 0,
    /**
     * v1.3.11: 服务端给的结束原因(`stop`=正常结束,`length`=被 max_tokens 截断)。
     *
     * **这次必须留下它**:用户报"翻译时好时坏"时,日志里只有 `body=<前300字>`,
     * 而 finish_reason 在响应尾部、根本不在那 300 字里 —— 只能靠人工推理半天。
     * 思考型模型把预算花光导致正文为空,正是靠这个字段一眼可判。
     */
    val finishReason: String? = null,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/** v1.3.7: 一次工具调用请求(OpenAI function calling 的 tool_calls 条目)。 */
data class LlmToolCall(
    val id: String,
    val name: String,
    /** function.arguments 原文(JSON 字符串,由调用方解析)。 */
    val argumentsJson: String,
)

/** v1.3.7: 带工具调用的响应——content 与 toolCalls 至少其一非空。 */
data class LlmToolResponse(
    val content: String?,
    val toolCalls: List<LlmToolCall>,
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheTokens: Int,
    val thinking: String?,
)

/**
 * v1.3.7: [LlmClient.callWithTools] 的结果——**错误随结果一起返回**。
 *
 * 为什么不复用 `lastError`：它是 `object LlmClient` 上的共享可变状态，而
 * "聊天搜索决策 / 歌手专辑年份 / 歌词翻译 / 曲目重命名比对"可能同时在跑。
 * 调用方若在 `callWithTools` 返回后再去读 `lastError`，那个窗口里完全可能被
 * 另一个协程覆盖（另一个调用成功时会把它置 null）→ 回退判定误判，甚至向用户
 * 报出 "LLM 调用失败:null"。把码与文案放进返回值，从根上消除这个竞态。
 */
sealed class LlmToolResult {
    data class Ok(val resp: LlmToolResponse) : LlmToolResult()

    /**
     * @param httpCode 服务端 HTTP 状态码；**null = 网络异常 / 响应不可解析**（可重试）。
     *                 只有 400/422 这类"请求形状被拒"才应被解读为"供应商不认 tools"；
     *                 401/403/429 是真实的鉴权/限流错误，必须原样透出，不能静默回退。
     * @param message  可直接展示给用户的错误文案。
     */
    data class Err(val httpCode: Int?, val message: String) : LlmToolResult()
}

/**
 * v1.2.2 Agent: 通用 OpenAI 兼容 LLM 客户端。给定 [LlmProviderConfig] + messages,
 * 调 chat/completions,解析 content + usage。多供应商共用同一套请求/解析逻辑。
 *
 * 取代旧 DeepSeekProvider(单供应商硬编码 endpoint/model)。旧 [AgentService] 改用
 * 本客户端的 call(config, messages) 即可支持任意已配置供应商。
 */
object LlmClient {
    private const val TAG = "Agent"
    private val JSON = "application/json".toMediaType()

    /** v1.3.1: 最近一次 call 失败的人类可读原因(HTTP 码+服务端 error.message/网络异常),
     *  供对话 UI 透传给用户(此前一律"无法理解这条指令",429/401/断网无从分辨)。
     *  成功调用即清空。Agent 单发串行(agentRunning 闸门),无并发竞争。 */
    @Volatile var lastError: String? = null
        private set

    /** v1.3.3: 清空 lastError——非 LLM 类业务失败(如 Tavily 没搜到资料)标步骤 FAILED
     *  前调用,避免把上一轮残留的 LLM 错误误显示成这次的原因。 */
    fun clearLastError() { lastError = null }

    /** 从 OpenAI 兼容错误体 {"error":{"message":...}} 提取信息,失败返回 null。 */
    private fun errorMessage(body: String): String? = try {
        JsonParser.parseString(body).asJsonObject.getAsJsonObject("error")?.get("message")
            ?.takeIf { it.isJsonPrimitive }?.asString
    } catch (_: Exception) { null }

    // v1.3.4: 推理模型(sensenova/DeepSeek-R1/glm thinking)非流式响应要等全部
    // reasoning 生成完才返回,弱网下 30s 常不够 → "AI 分析"步骤挂 90s 像卡死。
    // readTimeout 放宽到 120s,与主流推理 API 控制台建议一致;connect 不变(连不上
    // 还是快速失败)。
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 调 [config] 指定的 LLM。成功返回 content + usage;失败/未配置/HTTP 非 2xx → null。
     * [temperature] 默认 0(结构化输出稳定)。
     * v1.3.3: 网络类异常(SocketException/IOException,如"Software caused connection
     * abort"——app 息屏/切后台后 OkHttp 复用被服务端关闭的死连接)自动重试 2 次
     * (间隔 1s/2s)。HTTP 4xx/5xx(如 429 额度)不重试——那是服务端拒绝,重试无意义。
     * v1.3.5: 重试 2→1 次——readTimeout 放宽到 120s 后,网络异常路径最坏
     * 3 次 × 120s = 6 分钟"无返回"(用户实测 10 分钟量级假死)。1 次重试保住死连接
     * 复用的真实收益(1s 内失败的那种),超时类(真等了 120s)不再翻倍罚时。
     * v1.3.x furigana: [callSec] per-call 读超时(秒)——振假名兜底这类轻量结构化
     * 调用传 45s:全局 120s 是给流式聊天(打字机可边等边看)留的,后台静默任务撞上
     * 挂住的连接就是干等 2 分钟(真机 SocketTimeoutException 实测,串行链路总耗时
     * 破分钟的元凶)。[retries] 同因:兜底层有自己的"下次自动重试",这里 0 次即可。
     */
    suspend fun call(
        config: LlmProviderConfig,
        messages: List<Pair<String, String>>,
        temperature: Double = 0.0,
        maxTokens: Int = 2048,
        callSec: Long = 120,
        retries: Int = 1,
        /**
         * v1.3.15:**结构化提取类调用关掉思考**(仅对 DeepSeek 端点发该字段)。
         *
         * 真机定性(2026-10-02,《龙年》曲序):样本显示 `ct=4096 / thinkLen=11294 /
         * finish=length / 正文空` —— 模型把整份输出预算都花在"想"上(1.1 万字思考),
         * 一个字正文都没留下。DeepSeek V4 系列**默认开 thinking**,而"照抄曲名排个序"
         * 这种机械任务根本不需要思考:关掉它,既拿得到正文,又快又省。
         *
         * ⚠️ `thinking` 是 DeepSeek 专有字段:其它 OpenAI 兼容供应商收到未知字段可能直接 400,
         * 所以只对 baseUrl 含 deepseek 的供应商发送,并在撞到 4xx 时**去掉字段再试一次**。
         */
        noThinking: Boolean = false,
    ): LlmResponse? {
        var attempt = 0
        var backoffMs = 1000L
        while (true) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val r = callOnce(config, messages, temperature, maxTokens, callSec, noThinking)
            logCallLine(r, android.os.SystemClock.elapsedRealtime() - t0, callSec, maxTokens)
            // 成功 / 非网络类失败(服务端响应了:HTTP 错误码、配置问题) → 直接返回
            if (r != null || lastError?.startsWith("网络异常") != true) {
                // v1.3.15:供应商不认 `thinking` 字段(4xx)时,去掉它再试一次 ——
                // "省预算"不该变成"直接失败"。
                if (r == null && noThinking && lastError?.startsWith("HTTP 4") == true) {
                    android.util.Log.w(TAG, "thinking 字段被拒(${lastError?.take(60)}),去掉后重试一次")
                    val t2 = android.os.SystemClock.elapsedRealtime()
                    val r3 = callOnce(config, messages, temperature, maxTokens, callSec, noThinking = false)
                    logCallLine(r3, android.os.SystemClock.elapsedRealtime() - t2, callSec, maxTokens)
                    if (r3 != null) return r3
                }
                // v1.3.11:正文空 + 思考非空 = 思考型模型把预算吃光(见 LlmBudget 的实证)。
                // 用**双倍预算**再要一次:一次点击最多多花一次调用,换掉"时好时坏、点两三次"。
                if (r != null &&
                    shouldRetryWithBiggerBudget(r.content, r.thinking, r.finishReason, maxTokens)
                ) {
                    val bigger = doubledBudget(maxTokens)
                    android.util.Log.w(
                        TAG,
                        "正文为空但思考非空(finish=${r.finishReason ?: "?"})" +
                            "→ 预算 $maxTokens → $bigger,重试一次",
                    )
                    val t1 = android.os.SystemClock.elapsedRealtime()
                    val r2 = callOnce(config, messages, temperature, bigger, callSec, noThinking)
                    logCallLine(r2, android.os.SystemClock.elapsedRealtime() - t1, callSec, bigger)
                    // 加大预算仍拿不到 → 返回原始结果(调用方按"空响应"处理并报错),
                    // 不返回 null:HTTP 层这次是成功的,别谎报成网络失败。
                    if (r2 != null) return r2
                }
                return r
            }
            // 网络异常 → 重试(默认 1 次;轻量调用传 0)
            if (attempt >= retries) return r
            attempt++
            android.util.Log.w(TAG, "LLM 网络异常,${backoffMs}ms 后第 $attempt 次重试: $lastError")
            kotlinx.coroutines.delay(backoffMs)
            backoffMs *= 2
        }
    }

    /**
     * 每次 LLM 调用一行**可诊断**摘要。
     *
     * v1.3.11:原来只有 `LLM resp http=.. body=<前 300 字>` —— 正文为空时,那 300 字里
     * 既没有 finish_reason 也没有 token 用量,"思考吃光预算"这种失败只能靠猜
     * (用户报"翻译时好时坏",是人工翻半天日志才定案的)。这行把判据全摆出来:
     * finish / 用量 / 正文长度 / 思考长度,一眼可判。
     */
    private fun logCallLine(r: LlmResponse?, ms: Long, callSec: Long, maxTokens: Int) {
        if (r == null) {
            android.util.Log.i(TAG, "LLM call ${ms}ms (callSec=$callSec) → ${lastError?.take(80)}")
            return
        }
        android.util.Log.i(
            TAG,
            "LLM call ${ms}ms (callSec=$callSec, maxTok=$maxTokens) → ok " +
                "finish=${r.finishReason ?: "?"} pt=${r.promptTokens} ct=${r.completionTokens} " +
                "cache=${r.cacheTokens} contentLen=${r.content?.length ?: 0} " +
                "reasoningLen=${r.thinking?.length ?: 0}",
        )
    }

    /**
     * v1.3.3: 流式调用(SSE)。POST stream=true,逐行解析 data: {...} 增量——
     * content 与 reasoning(思考链,兼容 reasoning_content 字段名)都通过 [onDelta]
     * 实时透出,供 UI"正在思考"的实时流出效果。结束时返回聚合结果(带 usage)。
     * 失败(含流中途断) → null + lastError。不支持流式的供应商会返回非 SSE 响应,
     * 此时退化为整段一次性透出。
     * v1.3.3b: 网络类异常重试 1 次——与 call() 同因:app 息屏/切后台后连接池里的
     * 复用连接被服务端关掉,下一次流式请求撞死连接直接抛 SocketException。route()
     * 走的是流式,之前无重试,Agent 一撞上就"网络异常"直接怼给用户。
     * onDelta 可能已发出部分增量——重试前调用方无需清 UI(重试成功会重发全量,
     * 追加显示),这里只在重试轮开始时回调一次空 onDelta(null,null) 供调用方
     * 重置累积缓冲(调用方可选处理)。
     */
    suspend fun callStream(
        config: LlmProviderConfig,
        messages: List<Pair<String, String>>,
        temperature: Double = 0.0,
        maxTokens: Int = 2048,
        onDelta: (contentDelta: String?, reasoningDelta: String?) -> Unit,
    ): LlmResponse? = withContext(Dispatchers.IO) {
        val first = callStreamOnce(config, messages, temperature, maxTokens, onDelta)
        if (first != null) return@withContext first
        // 网络类异常才重试(HTTP 错误码/配置问题重试无意义)
        if (lastError?.startsWith("网络异常") != true) return@withContext null
        android.util.Log.w(TAG, "LLM stream 网络异常,1.5s 后重试一次: $lastError")
        kotlinx.coroutines.delay(1500)
        // v1.3.3b review#6: 重试前回调 (null,null) 通知调用方清累积缓冲——第一轮
        // 失败前可能已流出部分增量,重试成功会全量重发,不清的话打字机内容会重复一段。
        onDelta(null, null)
        callStreamOnce(config, messages, temperature, maxTokens, onDelta)
    }

    private suspend fun callStreamOnce(
        config: LlmProviderConfig,
        messages: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
        onDelta: (contentDelta: String?, reasoningDelta: String?) -> Unit,
    ): LlmResponse? = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            lastError = "供应商未配置(缺 API Key 或模型名)"
            return@withContext null
        }
        try {
            android.util.Log.d(TAG, "LLM stream → ${config.endpoint} model=${config.model}")
            val msgArray = JsonArray().apply {
                for ((role, content) in messages) {
                    add(JsonObject().apply {
                        addProperty("role", role)
                        addProperty("content", content)
                    })
                }
            }
            val body = JsonObject().apply {
                addProperty("model", config.model)
                add("messages", msgArray)
                addProperty("max_tokens", maxTokens)
                addProperty("temperature", temperature)
                addProperty("stream", true)
            }.toString()

            val req = Request.Builder()
                .url(config.endpoint)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody(JSON))
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                val respBody = resp.body?.string()
                val msg = errorMessage(respBody ?: "")
                lastError = "HTTP ${resp.code}" + if (msg.isNullOrBlank()) "" else ": ${msg.take(140)}"
                return@withContext null
            }
            val contentSb = StringBuilder()
            val reasoningSb = StringBuilder()
            var pt = 0; var ct = 0; var cacheTk = 0
            // SSE 逐行读:"data: {...}" 每行一个 chunk;"data: [DONE]" 结束。
            // v1.3.4 修"Agent 技能全中断":①用法:很多 OpenAI 兼容服务(tokenrouter 等)在
            // chat.completion 顶层返回 "usage": null —— asJsonObject 直接抛 JsonNull 强转异常,
            // 明明 HTTP 200 的成功响应被当成"网络异常"重试三轮后全失败。判 isJsonObject。
            // ②delta:glm-5.3-free 等模型对无 reasoning 的 chunk 回 "content": null,
            // ?.asString 本身安全,但 delta 里 choices 为空数组时 firstOrNull 为 null,
            // 继续前须判空(否则下一行 getAsJsonObject 对 JsonNull 抛异常)。
            java.io.BufferedReader(java.io.InputStreamReader(resp.body!!.byteStream(), Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val chunk = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
                    // 流式 usage 通常在最后一个 chunk;usage 可能为 **JsonNull**(部分网关
                    // 每个 chunk 都带 "usage": null)——必须 takeIf{isJsonObject},
                    // 直接 getAsJsonObject 会强转 JsonNull 抛 ClassCastException,
                    // 被外层 catch 归成「网络异常」把成功响应变成失败。
                    chunk.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { u ->
                        pt = u.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: pt
                        ct = u.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: ct
                        // v1.3.6: 缓存命中 token(OpenAI prompt_tokens_details.cached_tokens;
                        // DeepSeek 用顶层 prompt_cache_hit_tokens)——都认,取其一。
                        cacheTk = u.get("prompt_tokens_details")?.takeIf { it.isJsonObject }?.asJsonObject
                            ?.get("cached_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                            ?: u.get("prompt_cache_hit_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                            ?: cacheTk
                    }
                    val delta = chunk.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.firstOrNull { el -> el.isJsonObject }?.asJsonObject
                        ?.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val reasoning = delta.get("reasoning_content")?.asString ?: delta.get("reasoning")?.asString
                    val content = delta.get("content")?.asString
                    if (reasoning != null || content != null) {
                        reasoning?.let { reasoningSb.append(it) }
                        content?.let { contentSb.append(it) }
                        onDelta(content, reasoning)
                    }
                }
            }
            lastError = null
            val content = contentSb.toString().ifBlank { null }
            val thinking = reasoningSb.toString().ifBlank { null }
            LlmResponse(content, pt, ct, thinking, cacheTk)
        } catch (e: Exception) {
            // v1.3.4: CancellationException 不能吞——callStream 的调用方协程被取消
            // (用户点停止/离开页面)时,原样 rethrow 让取消正常传播;吞掉会把协程
            // 变成"正常完成",步骤面板永远停在中间态。
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastError = "网络异常: ${e.message ?: e.javaClass.simpleName}"
            android.util.Log.w(TAG, "LLM stream 异常: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 单次调用(无重试)。网络异常时 lastError 以"网络异常"开头,供 call 判定可重试。 */
    private suspend fun callOnce(config: LlmProviderConfig, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int, callSec: Long = 120, noThinking: Boolean = false): LlmResponse? =
        withContext(Dispatchers.IO) {
            if (!config.isConfigured) {
                lastError = "供应商未配置(缺 API Key 或模型名)"
                android.util.Log.w(TAG, "LLM call skip: provider 未配置(model=${config.model} keyLen=${config.apiKey.length} baseUrl=${config.baseUrl})")
                return@withContext null
            }
            try {
                android.util.Log.d(TAG, "LLM call → ${config.endpoint} model=${config.model}")
                val msgArray = JsonArray().apply {
                    for ((role, content) in messages) {
                        add(JsonObject().apply {
                            addProperty("role", role)
                            addProperty("content", content)
                        })
                    }
                }
                val body = JsonObject().apply {
                    addProperty("model", config.model)
                    add("messages", msgArray)
                    // v1.3.3: maxTokens 参数化——排序任务输出只是编号序列,用小限额
                    // (512)既省 token 又加快服务端生成;闲聊用默认 2048。
                    addProperty("max_tokens", maxTokens)
                    addProperty("temperature", temperature)
                    // v1.3.15:结构化提取关思考(见 noThinking 说明)。字段只发给 DeepSeek 端点。
                    if (shouldSendNoThinking(config.baseUrl, noThinking)) {
                        add("thinking", JsonObject().apply { addProperty("type", "disabled") })
                    }
                }.toString()

                val req = Request.Builder()
                    .url(config.endpoint)
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(JSON))
                    .build()

                // v1.3.x furigana: per-call 读超时——轻量结构化任务(振假名兜底)传
                // callSec=45,全局 120s 给流式聊天。code-review 修正:newBuilder()
                // 派生自全局 client(共享连接池/dispatcher/keep-alive 复用),替代
                // 此前每次 callSec≠120 就 new OkHttpClient()(独立连接池=每次完整
                // TLS 握手,实例线程不释放)。
                val perCallClient = if (callSec == 120L) client else client.newBuilder()
                    .readTimeout(callSec, TimeUnit.SECONDS)
                    .build()

                val resp = perCallClient.newCall(req).execute()
                val respBody = resp.body?.string()
                android.util.Log.d(TAG, "LLM resp http=${resp.code} body=${respBody?.take(300)}")
                if (respBody == null) { lastError = "服务端响应为空(HTTP ${resp.code})"; return@withContext null }
                if (!resp.isSuccessful) {
                    val msg = errorMessage(respBody)
                    lastError = "HTTP ${resp.code}" + if (msg.isNullOrBlank()) "" else ": ${msg.take(140)}"
                    return@withContext null
                }
                // v1.3.4: 网关偶尔回非 JSON 错误页(HTML),parse 抛异常同样会走
                // "网络异常"重试——runCatching 归位为明确的配置类错误。
                val json = runCatching { JsonParser.parseString(respBody).asJsonObject }.getOrNull() ?: run {
                    lastError = "响应不是 JSON(检查 baseUrl 是否指向 OpenAI 兼容端点)"
                    return@withContext null
                }

                val choices = json.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray ?: run {
                    android.util.Log.w(TAG, "LLM resp 无 choices 字段: ${json.keySet()}")
                    lastError = "响应缺少 choices(检查 baseUrl 是否为 OpenAI 兼容端点)"
                    return@withContext null
                }
                if (choices.size() == 0) {
                    lastError = "响应 choices 为空(检查模型名是否正确)"
                    return@withContext null
                }
                // v1.3.3: 推理模型思考后常把答案放 reasoning、content 为空——这不叫失败,
                // 正常返回(content 空串由调用方按"空响应"处理,不再硬设 lastError 触发
                // "LLM 调用失败"报错——那正是"发句你好都报错"的根因)。
                // v1.3.4: content 可能是 JSON null(glm-5.3-free 推理把答案全放
                // reasoning_content 时回 "content": null)——getAsJsonObject 语义的
                // 空安全判空,JsonNull.asString 会抛 UnsupportedOperationException,
                // 整个成功响应又被当"网络异常"烧三轮重试。
                // v1.3.11: 取 choices[0] 一次,复用于 message 与 finish_reason。
                // firstOrNull{isJsonObject} 比 get(0) 稳:元素可能是 JsonNull(见下)。
                val choice0 = choices.firstOrNull { el -> el.isJsonObject }?.asJsonObject
                val finishReason =
                    choice0?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString
                val msgObj = choice0
                    ?.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: run {
                    lastError = "响应缺少 message"
                    return@withContext null
                }
                val content = msgObj.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                lastError = null

                // v1.3.3: 解析思考链(DeepSeek-R1/Claude 的 reasoning 字段)
                val thinking = msgObj.get("reasoning")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: msgObj.get("reasoning_content")?.takeIf { it.isJsonPrimitive }?.asString

                val usage = json.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
                val pt = usage?.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val ct = usage?.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val cacheTk = usage?.get("prompt_tokens_details")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("cached_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                    ?: usage?.get("prompt_cache_hit_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                    ?: 0
                LlmResponse(content, pt, ct, thinking, cacheTk, finishReason)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = "网络异常: ${e.message ?: e.javaClass.simpleName}"
                android.util.Log.w(TAG, "LLM call 异常: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    /**
     * v1.3.7: OpenAI 兼容 function calling(非流式——工具循环不需要打字机)。
     *
     * 与 [call] 的差异:①messages 是原始 JSON(要支持 role=tool / assistant 的
     * tool_calls 回显,Pair<String,String> 表达不了);②请求带 tools + tool_choice=auto;
     * ③响应解析 choices[0].message.tool_calls。
     *
     * **错误随结果一起返回**(见 [LlmToolResult.Err])——调用方**不要**去读 [lastError]
     * 判定回退,它是共享状态、并发下会被覆盖(详见 [LlmToolResult] 的说明)。
     *
     * 网络异常(无 HTTP 码)重试 1 次,与 [call] 同一理由:项目实测最常见的失败是
     * "息屏/切后台后 OkHttp 复用连接被服务端关掉"(SocketException),重试即可恢复。
     * HTTP 错误不重试——重发同样的请求只会再拿一次同样的拒绝。
     */
    suspend fun callWithTools(
        config: LlmProviderConfig,
        messages: JsonArray,
        tools: JsonArray?,
        temperature: Double = 0.0,
        maxTokens: Int = 4096,
        callSec: Long = 120,
        retries: Int = 1,
    ): LlmToolResult = withContext(Dispatchers.IO) {
        var last: LlmToolResult = LlmToolResult.Err(null, "未发起调用")
        for (attempt in 0..retries) {
            last = callWithToolsOnce(config, messages, tools, temperature, maxTokens, callSec)
            if (last is LlmToolResult.Ok) break
            val err = last as LlmToolResult.Err
            // 只重试"网络层失败"(无 HTTP 码)
            if (err.httpCode != null || attempt == retries) break
            android.util.Log.w(TAG, "callWithTools 网络异常,重试 ${attempt + 1}/$retries: ${err.message}")
        }
        last
    }

    /** v1.3.8: 流式工具调用的分片累积器。tool_calls 在 SSE 里是**分片**到达的:
     *  首块带 id + name + 空 arguments,后续块 id/name 为空、只带 arguments 片段。
     *  按 `index` 归并(index 不同 = 并行工具调用)。 */
    private class ToolCallAcc {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
    }

    /**
     * v1.3.8: 流式 + 工具调用(SSE)。技能分发的唯一入口。
     *
     * **为什么必须流式**:非流式会让聊天打字机退化成"整段生成完才显示"——那正是本版
     * 要消除的卡顿感。工具调用本身不需要打字机(JSON 不该透出),但**同一路请求里
     * 的聊天回复需要**,所以两者必须共用一次调用。
     *
     * 0.3 实测形状(deepseek-v4-flash / sensenova-6.8-flash-lite @ token.sensenova.cn):
     *  - 两家都支持 `stream:true` + `tools`,均以 `data: [DONE]` 收尾
     *  - tool_calls 分片:首块 `{"index":0,"id":"call_x","function":{"name":"...","arguments":""}}`,
     *    后续块 `{"index":0,"id":"","function":{"name":"","arguments":"晴天"}}`
     *  - 两家都是推理模型:tool-call 轮的 content 为 `""` / `"\n\n"`,思考在
     *    `reasoning_content`(deepseek)/ `reasoning`(sensenova)
     *
     * 错误随结果返回([LlmToolResult.Err]),调用方**不要**读 [lastError] 判定回退
     * (共享状态,并发下会被覆盖,详见 [LlmToolResult])。
     */
    suspend fun callWithToolsStream(
        config: LlmProviderConfig,
        messages: JsonArray,
        tools: JsonArray?,
        temperature: Double = 0.0,
        maxTokens: Int = 4096,
        callSec: Long = 120,
        retries: Int = 1,
        onDelta: (contentDelta: String?, reasoningDelta: String?) -> Unit = { _, _ -> },
    ): LlmToolResult = withContext(Dispatchers.IO) {
        var last: LlmToolResult = LlmToolResult.Err(null, "未发起调用")
        for (attempt in 0..retries) {
            last = callWithToolsStreamOnce(config, messages, tools, temperature, maxTokens, callSec, onDelta)
            if (last is LlmToolResult.Ok) break
            val err = last as LlmToolResult.Err
            // 只重试"网络层失败"(无 HTTP 码):HTTP 错误重发同样请求只会再被拒一次
            if (err.httpCode != null || attempt == retries) break
            android.util.Log.w(TAG, "callWithToolsStream 网络异常,重试 ${attempt + 1}/$retries: ${err.message}")
            kotlinx.coroutines.delay(1500)
            // 重试轮开始时清空调用方已积累的展示缓冲(重试成功会从头重发全量),
            // 与 callStream 的 (null,null) 约定一致。
            onDelta(null, null)
        }
        last
    }

    /** [callWithToolsStream] 的单次实现(不含重试)。 */
    private fun callWithToolsStreamOnce(
        config: LlmProviderConfig,
        messages: JsonArray,
        tools: JsonArray?,
        temperature: Double,
        maxTokens: Int,
        callSec: Long,
        onDelta: (contentDelta: String?, reasoningDelta: String?) -> Unit,
    ): LlmToolResult {
        if (!config.isConfigured) {
            val m = "供应商未配置(缺 API Key 或模型名)"
            lastError = m
            return LlmToolResult.Err(null, m)
        }
        try {
            val body = JsonObject().apply {
                addProperty("model", config.model)
                add("messages", messages)
                if (tools != null && tools.size() > 0) {
                    add("tools", tools)
                    addProperty("tool_choice", "auto")
                }
                addProperty("max_tokens", maxTokens)
                addProperty("temperature", temperature)
                addProperty("stream", true)
            }.toString()

            val req = Request.Builder()
                .url(config.endpoint)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody(JSON))
                .build()
            val perCallClient = if (callSec == 120L) client else client.newBuilder()
                .readTimeout(callSec, TimeUnit.SECONDS)
                .build()

            val resp = perCallClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                val respBody = resp.body?.string()
                val msg = errorMessage(respBody ?: "")
                val m = "HTTP ${resp.code}" + if (msg.isNullOrBlank()) "" else ": ${msg.take(140)}"
                lastError = m
                return LlmToolResult.Err(resp.code, m)
            }

            val contentSb = StringBuilder()
            val reasoningSb = StringBuilder()
            val tcAcc = LinkedHashMap<Int, ToolCallAcc>()
            var pt = 0; var ct = 0; var cacheTk = 0
            java.io.BufferedReader(java.io.InputStreamReader(resp.body!!.byteStream(), Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val chunk = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
                    // 流式 usage 通常在最后一个 chunk;值可能为 null(不抛、跳过)
                    // ⚠️ 必须走 takeIf{isJsonObject} 而不是 getAsJsonObject():
                    // Gson 的 getAsJsonObject/getAsJsonArray 内部是 (JsonObject) members.get(k)
                    // 强转,成员为 **JsonNull** 时抛 ClassCastException。而部分 OpenAI 兼容
                    // 网关(如 DeepSeek 官方)会在每个 chunk 里带 "usage": null → 异常被外层
                    // catch(Exception) 归成「网络异常:…JsonNull」,成功响应变失败。这正是
                    // callWithToolsOnce 里已经修过的同一类 bug,这里补上。
                    chunk.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject?.let { u ->
                        pt = u.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: pt
                        ct = u.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: ct
                        cacheTk = u.get("prompt_tokens_details")?.takeIf { it.isJsonObject }?.asJsonObject
                            ?.get("cached_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                            ?: u.get("prompt_cache_hit_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                            ?: cacheTk
                    }
                    // 逐字段空安全:choices 元素可能是 JsonNull(见 callWithToolsOnce 的同款修复)
                    val delta = chunk.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.firstOrNull { el -> el.isJsonObject }
                        ?.asJsonObject?.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val reasoning = delta.get("reasoning_content")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: delta.get("reasoning")?.takeIf { it.isJsonPrimitive }?.asString
                    val content = delta.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                    delta.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
                        val o = el?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        val idx = o.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                        val acc = tcAcc.getOrPut(idx) { ToolCallAcc() }
                        // id/name 只在首块出现,后续块是空串——非空才覆盖
                        o.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                            ?.let { acc.id = it }
                        o.get("function")?.takeIf { it.isJsonObject }?.asJsonObject?.let { fn ->
                            fn.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                                ?.let { acc.name = it }
                            fn.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString
                                ?.let { acc.args.append(it) }
                        }
                    }
                    if (reasoning != null || content != null) {
                        reasoning?.let { reasoningSb.append(it) }
                        content?.let { contentSb.append(it) }
                        onDelta(content, reasoning)
                    }
                }
            }
            lastError = null
            val toolCalls = tcAcc.entries.sortedBy { it.key }.mapIndexedNotNull { i, e ->
                val name = e.value.name ?: return@mapIndexedNotNull null
                LlmToolCall(
                    // 与 callWithToolsOnce 同因:少数网关不回 id,回显时 tool_call_id
                    // 必须与 assistant 消息一致,空串会被严格网关判 400。合成稳定 id。
                    id = e.value.id ?: "call_$i",
                    name = name,
                    argumentsJson = e.value.args.toString().ifBlank { "{}" },
                )
            }
            return LlmToolResult.Ok(
                LlmToolResponse(
                    content = contentSb.toString().ifBlank { null },
                    toolCalls = toolCalls,
                    promptTokens = pt,
                    completionTokens = ct,
                    cacheTokens = cacheTk,
                    thinking = reasoningSb.toString().ifBlank { null },
                )
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val m = "网络异常: ${e.message ?: e.javaClass.simpleName}"
            lastError = m
            return LlmToolResult.Err(null, m)
        }
    }

    /** [callWithTools] 的单次实现(不含重试)。 */
    private fun callWithToolsOnce(
        config: LlmProviderConfig,
        messages: JsonArray,
        tools: JsonArray?,
        temperature: Double,
        maxTokens: Int,
        callSec: Long,
    ): LlmToolResult {
        if (!config.isConfigured) {
            lastError = "供应商未配置(缺 API Key 或模型名)"
            return LlmToolResult.Err(null, "供应商未配置(缺 API Key 或模型名)")
        }
        try {
            val body = JsonObject().apply {
                addProperty("model", config.model)
                add("messages", messages)
                if (tools != null && tools.size() > 0) {
                    add("tools", tools)
                    addProperty("tool_choice", "auto")
                }
                addProperty("max_tokens", maxTokens)
                addProperty("temperature", temperature)
            }.toString()

            val req = Request.Builder()
                .url(config.endpoint)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON))
                .build()
            val perCallClient = if (callSec == 120L) client else client.newBuilder()
                .readTimeout(callSec, TimeUnit.SECONDS)
                .build()

            val resp = perCallClient.newCall(req).execute()
            val respBody = resp.body?.string()
            if (respBody == null) {
                val m = "服务端响应为空(HTTP ${resp.code})"
                lastError = m
                return LlmToolResult.Err(resp.code, m)
            }
            if (!resp.isSuccessful) {
                val msg = errorMessage(respBody)
                val m = "HTTP ${resp.code}" + if (msg.isNullOrBlank()) "" else ": ${msg.take(140)}"
                lastError = m
                return LlmToolResult.Err(resp.code, m)
            }
            val json = runCatching { JsonParser.parseString(respBody).asJsonObject }.getOrNull() ?: run {
                val m = "响应不是 JSON(检查 baseUrl 是否指向 OpenAI 兼容端点)"
                lastError = m
                return LlmToolResult.Err(null, m)
            }
            // v1.3.x 修复(用户反馈⑪:官方 DeepSeek 报"网络异常JSONNULL"、翻译却正常):
            // 翻译走 call()(解析路径有完整 JsonNull 防御),agent 走 callWithTools()
            // ——这条路径的解析防御不全:choices 数组元素是 JsonNull 时 .asJsonObject
            // 直接抛 Gson 强转异常,message 以 "JsonNull" 开头,被 catch(Exception)
            // 捕获归类为"网络异常"——成功响应被当成网络错误,重试也救不了。
            // 解析段逐字段空安全,不再抛任何强转异常。
            val msgObj = json.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.firstOrNull { el -> el.isJsonObject }
                ?.asJsonObject
                ?.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: run {
                val m = "响应缺少 message(检查 baseUrl 是否为 OpenAI 兼容端点)"
                lastError = m
                return LlmToolResult.Err(null, m)
            }
            val content = msgObj.get("content")?.takeIf { it.isJsonPrimitive }?.asString
            val toolCalls = msgObj.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray?.mapIndexedNotNull { idx, el ->
                val o = el?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
                val fn = o.get("function")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
                val name = fn.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapIndexedNotNull null
                LlmToolCall(
                    // 少数网关不回 id;回显时 tool_call_id 必须与 assistant 消息里的 id 一致,
                    // 空串会被严格校验的网关判 400 → 整条链路失败。这里合成一个稳定 id。
                    id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                        ?: "call_$idx",
                    name = name,
                    argumentsJson = fn.get("arguments")?.takeIf { it.isJsonPrimitive }?.asString ?: "{}",
                )
            } ?: emptyList()
            val thinking = msgObj.get("reasoning")?.takeIf { it.isJsonPrimitive }?.asString
                ?: msgObj.get("reasoning_content")?.takeIf { it.isJsonPrimitive }?.asString
            val usage = json.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
            val pt = usage?.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val ct = usage?.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val cacheTk = usage?.get("prompt_tokens_details")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("cached_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                ?: usage?.get("prompt_cache_hit_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                ?: 0
            lastError = null
            return LlmToolResult.Ok(LlmToolResponse(content, toolCalls, pt, ct, cacheTk, thinking))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val m = "网络异常: ${e.message ?: e.javaClass.simpleName}"
            lastError = m
            return LlmToolResult.Err(null, m)
        }
    }

    /**
     * v1.3.0: 自动获取该供应商可用模型列表(GET {baseUrl}/models,OpenAI 标准)。
     * 预设模型名易过时(如 deepseek-chat 已于 2026-07 停用),改为运行时拉取。
     * 失败/未配置/HTTP 非 2xx → 空列表。返回模型 id 列表。
     */
    suspend fun listModels(config: LlmProviderConfig): List<String> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) return@withContext emptyList()
        try {
            val url = config.normalizedBaseUrl + "/models"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            android.util.Log.d(TAG, "listModels http=${resp.code} url=$url n=${if (resp.isSuccessful) body.length else 0}")
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = JsonParser.parseString(body).asJsonObject
            val arr = json.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@withContext emptyList()
            arr.mapNotNull { el ->
                el?.asJsonObject?.get("id")?.asString?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }
    }
}

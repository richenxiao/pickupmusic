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
) {
    val totalTokens: Int get() = promptTokens + completionTokens
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
     */
    suspend fun call(config: LlmProviderConfig, messages: List<Pair<String, String>>, temperature: Double = 0.0, maxTokens: Int = 2048): LlmResponse? {
        var attempt = 0
        var backoffMs = 1000L
        while (true) {
            val r = callOnce(config, messages, temperature, maxTokens)
            // 成功 / 非网络类失败(服务端响应了:HTTP 错误码、配置问题) → 直接返回
            if (r != null || lastError?.startsWith("网络异常") != true) return r
            // 网络异常 → 重试(最多 1 次)
            if (attempt >= 1) return r
            attempt++
            android.util.Log.w(TAG, "LLM 网络异常,${backoffMs}ms 后第 $attempt 次重试: $lastError")
            kotlinx.coroutines.delay(backoffMs)
            backoffMs *= 2
        }
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
                    // 流式 usage 通常在最后一个 chunk;usage 可能为 null(不抛、跳过)
                    chunk.getAsJsonObject("usage")?.let { u ->
                        pt = u.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: pt
                        ct = u.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: ct
                        // v1.3.6: 缓存命中 token(OpenAI prompt_tokens_details.cached_tokens;
                        // DeepSeek 用顶层 prompt_cache_hit_tokens)——都认,取其一。
                        cacheTk = u.getAsJsonObject("prompt_tokens_details")?.get("cached_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                            ?: u.get("prompt_cache_hit_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                            ?: cacheTk
                    }
                    val delta = chunk.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.getAsJsonObject("delta") ?: continue
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
    private suspend fun callOnce(config: LlmProviderConfig, messages: List<Pair<String, String>>, temperature: Double, maxTokens: Int): LlmResponse? =
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
                }.toString()

                val req = Request.Builder()
                    .url(config.endpoint)
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(JSON))
                    .build()

                val resp = client.newCall(req).execute()
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

                val choices = json.getAsJsonArray("choices") ?: run {
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
                val msgObj = choices[0].asJsonObject.getAsJsonObject("message") ?: run {
                    lastError = "响应缺少 message"
                    return@withContext null
                }
                val content = msgObj.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                lastError = null

                // v1.3.3: 解析思考链(DeepSeek-R1/Claude 的 reasoning 字段)
                val thinking = msgObj.get("reasoning")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: msgObj.get("reasoning_content")?.takeIf { it.isJsonPrimitive }?.asString

                val usage = json.getAsJsonObject("usage")
                val pt = usage?.get("prompt_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val ct = usage?.get("completion_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val cacheTk = usage?.getAsJsonObject("prompt_tokens_details")?.get("cached_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                    ?: usage?.get("prompt_cache_hit_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                    ?: 0
                LlmResponse(content, pt, ct, thinking, cacheTk)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = "网络异常: ${e.message ?: e.javaClass.simpleName}"
                android.util.Log.w(TAG, "LLM call 异常: ${e.javaClass.simpleName}: ${e.message}")
                null
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
            val arr = json.getAsJsonArray("data") ?: return@withContext emptyList()
            arr.mapNotNull { el ->
                el?.asJsonObject?.get("id")?.asString?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }
    }
}

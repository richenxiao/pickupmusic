package com.shiyin.music.data.ai

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
 * v1.2.2 Agent 场景一:Tavily 联网搜索通道。独立于 LLM 服务商,返回网页搜索摘要,
 * 供 AgentService 塞进 LLM prompt 作为"官方曲目顺序"的事实依据——让 LLM 做映射而非凭
 * 空生成,降低幻觉。
 *
 * 免费额度 100 RPM,手动单次触发(1-3 次搜索)绰绰有余。
 * 失败/无结果 → null,AgentService 据此判 LOW confidence(信息不足)。
 */
object TavilyService {

    private const val ENDPOINT = "https://api.tavily.com/search"
    private val JSON = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 搜索 [query],返回多条结果 content 拼成的摘要文本(供塞进 LLM prompt)。
     * 失败、HTTP 非 2xx、无结果 → null。
     */
    suspend fun search(apiKey: String, query: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            val body = JsonObject().apply {
                addProperty("api_key", apiKey)
                addProperty("query", query)
                // v1.3.2: basic + 3 条——advanced 深度搜索单次常要 5-15s,是排序链路最大的
                // 耗时点;曲目顺序这种检索 basic 足够,3 条正文信息量已够 LLM 参考。
                addProperty("search_depth", "basic")
                addProperty("max_results", 3)
                // 只要正文摘要,不要图片/answer,省 token
                addProperty("include_answer", false)
                addProperty("include_images", false)
            }.toString()

            val req = Request.Builder()
                .url(ENDPOINT)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON))
                .build()

            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: return@withContext null
            if (!resp.isSuccessful) return@withContext null
            val json = JsonParser.parseString(respBody).asJsonObject
            val results = json.getAsJsonArray("results") ?: return@withContext null
            val sb = StringBuilder()
            for (i in 0 until results.size()) {
                val o = results[i].asJsonObject
                val title = o.get("title")?.asString ?: ""
                val content = o.get("content")?.asString ?: ""
                if (content.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n\n---\n\n")
                    sb.append("【").append(title).append("】\n").append(content)
                }
            }
            sb.toString().ifBlank { null }
        } catch (_: Exception) { null }
    }
}

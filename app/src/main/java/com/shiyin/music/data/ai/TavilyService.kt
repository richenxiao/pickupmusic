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
     *
     * [includeAnswer] 为 true 时把 Tavily 的综合回答(answer 字段)拼在最前——
     * 专辑年表这类事实型查询,answer 常直接给出关键日期,比 3 条正文摘要更有用。
     */
    suspend fun search(apiKey: String, query: String, includeAnswer: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        try {
            val body = JsonObject().apply {
                addProperty("api_key", apiKey)
                addProperty("query", query)
                // v1.3.2: basic + 3 条——advanced 深度搜索单次常要 5-15s,是排序链路最大的
                // 耗时点;曲目顺序这种检索 basic 足够,3 条正文信息量已够 LLM 参考。
                addProperty("search_depth", "basic")
                addProperty("max_results", 3)
                // 只要正文摘要,不要图片,省 token;answer 由调用方决定(事实型查询值得要)
                addProperty("include_answer", includeAnswer)
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
            val sb = StringBuilder()
            if (includeAnswer) {
                json.get("answer")?.asString?.takeIf { it.isNotBlank() }?.let {
                    sb.append("【综合回答】\n").append(it)
                }
            }
            // results 可能为 null(如只返回了 answer)——answer 已拼进 sb 时不算空结果
            json.getAsJsonArray("results")?.let { results ->
                for (i in 0 until results.size()) {
                    val o = results[i].asJsonObject
                    val title = o.get("title")?.asString ?: ""
                    val content = o.get("content")?.asString ?: ""
                    if (content.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n\n---\n\n")
                        sb.append("【").append(title).append("】\n").append(content)
                    }
                }
            }
            sb.toString().ifBlank { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消必须原样穿透：runCatching/catch(Exception) 会把 CancellationException
            // 一起吞掉，让"用户已经停止/切歌"的任务继续跑（AgentTools 的取消语义依赖这里）。
            throw e
        } catch (e: Exception) {
            android.util.Log.w("Tavily", "search failed: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }
}

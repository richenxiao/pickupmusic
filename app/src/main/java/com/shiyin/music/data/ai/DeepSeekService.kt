package com.shiyin.music.data.ai

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v2.0: DeepSeek API integration for AI-assisted features.
 *
 * Uses the DeepSeek Chat API (compatible with OpenAI API format).
 */
object DeepSeekService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val API_URL = "https://api.deepseek.com/chat/completions"
    private val JSON_MEDIA = "application/json".toMediaType()

    /**
     * Ask DeepSeek for optimized iTunes search keywords.
     * Returns a search term string like "song title artist name" or null on failure.
     */
    suspend fun suggestSearchKeywords(apiKey: String, title: String, artist: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    你是一个音乐搜索助手。请为以下歌曲推荐最佳的 iTunes 搜索关键词。
                    歌曲：$title
                    歌手：$artist
                    只返回搜索关键词，用英文，不要解释，不要多余内容。
                """.trimIndent()
                val result = callDeepSeek(apiKey, prompt) ?: return@withContext null
                result.trim().ifBlank { null }
            } catch (_: Exception) { null }
        }

    /**
     * Ask DeepSeek for lyrics of a song.
     * Returns simplified plain text or null on failure.
     */
    suspend fun searchLyrics(apiKey: String, title: String, artist: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    请提供歌曲「$title」- $artist 的歌词。
                    格式要求：
                    1. 每行开头标注时间，格式为 MM:SS
                    2. 时间与歌词之间用空格隔开
                    3. 按时间顺序排列
                    4. 只返回歌词，不要解释
                    例如：
                    00:00 第一句歌词
                    00:05 第二句歌词
                """.trimIndent()
                val result = callDeepSeek(apiKey, prompt) ?: return@withContext null
                result.trim().ifBlank { null }
            } catch (_: Exception) { null }
        }

    private fun callDeepSeek(apiKey: String, prompt: String): String? {
        val bodyJson = """
            {
                "model": "deepseek-chat",
                "messages": [{"role": "user", "content": ${jsonEscape(prompt)}}],
                "max_tokens": 1024,
                "temperature": 0.1
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null
        if (!response.isSuccessful) return null

        val json = JsonParser.parseString(body).asJsonObject
        val choices = json.getAsJsonArray("choices") ?: return null
        if (choices.size() == 0) return null
        return choices[0].asJsonObject
            .getAsJsonObject("message")
            ?.get("content")?.asString
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
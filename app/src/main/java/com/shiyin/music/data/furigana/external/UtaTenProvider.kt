package com.shiyin.music.data.furigana.external

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * UtaTen 外部假名歌词 Provider（Android 实现，HttpURLConnection，无新增依赖）。
 *
 * 流程：搜索（/search/=/title=<q>=/artist_name=<q>=/）→ 取最匹配的 /lyric/<id> →
 * 抓取歌词页 HTML → UtaTenParser.parsePage → LyricAligner.align → List<OccurrenceReading>。
 *
 * ⚠️ Phase 2 修复：resolve 不再吞异常返回 null——改为抛 [ResolveException]（带可区分
 * 的 reason），让调用方（VM）能给用户明确的失败原因（"没找到歌曲"/"页面访问失败"/
 * "解析失败"/"对齐失败"）。网络/IO 异常仍会抛，但包装为 reason=NETWORK。
 *
 * 合规：UtaTen ふりがな为其自有内容、利用規约通常禁止自动抓取——**不适合未经评估直接
 * 进正式发行版**（见 docs/design/external-reading-providers.md §1.2）。
 */
class UtaTenProvider(
    private val userAgent: String = "PickUpMusic-Furigana/1.1 (lyric reading evidence; contact: dev)",
    private val diagFilePath: String? = null,  // 非空时，对齐失败把完整诊断写到此文件
) : ExternalReadingProvider {

    override val id: String = "utaten"

    /** resolve 的可区分失败原因。VM 据此给用户明确反馈。 */
    enum class Reason { NO_MATCH, NO_FURIGANA, NETWORK, PARSE, ALIGN }
    class ResolveException(val reason: Reason, message: String) : Exception(message)

    override suspend fun resolve(
        title: String,
        artist: String,
        localLyricLines: List<String>,
    ): List<OccurrenceReading>? = withContext(Dispatchers.IO) {
        // 1. 搜索歌曲
        val lyricId = try {
            searchLyricId(title, artist)
        } catch (t: Throwable) {
            throw ResolveException(Reason.NETWORK, "搜索页面访问失败：${t.message ?: t::class.simpleName}")
        }
        if (lyricId == null) throw ResolveException(Reason.NO_MATCH, "未找到匹配的歌曲")

        // 2. 抓取歌词页
        val html = try {
            fetchHtml("https://utaten.com/lyric/$lyricId")
        } catch (t: Throwable) {
            throw ResolveException(Reason.NETWORK, "歌词页面访问失败：${t.message ?: t::class.simpleName}")
        }
        if (html == null) throw ResolveException(Reason.NETWORK, "歌词页面访问失败（HTTP 非 200）")

        // 3. 解析假名
        val parsed = UtaTenParser.parsePage(html)
            ?: throw ResolveException(Reason.NO_FURIGANA, "来源页面无假名数据（或结构变化）")

        // 4. 对齐（fuzzy 逐行匹配）
        val (aligned, diag) = LyricAligner.alignWithDiagnostics(parsed, localLyricLines)
        if (aligned == null || aligned.isEmpty()) {
            val msg = buildString {
                append("无法对齐：")
                append("本地${diag.localLineCount}行/外部${diag.extLineCount}行, ")
                append("匹配${diag.matchedLineCount}行, ")
                append(diag.firstMismatchDetail.ifEmpty { "无 ruby run 可对齐" })
            }
            throw ResolveException(Reason.ALIGN, msg)
        }

        if (aligned.isEmpty()) throw ResolveException(Reason.PARSE, "解析结果为空")

        // 填 source 元数据
        aligned.map { it.copy(source = "$id:$artist/$title".take(60)) }
    }

    /** 搜索并返回最匹配的 /lyric/<id>。匹配策略：取搜索结果中第一个 /lyric/<id> 链接。 */
    private fun searchLyricId(title: String, artist: String): String? {
        val q = buildString {
            append("https://utaten.com/search/=/title=")
            append(URLEncoder.encode(title, "UTF-8"))
            append("=/artist_name=")
            append(URLEncoder.encode(artist, "UTF-8"))
            append("=/sort=score+DESC/")
        }
        val html = fetchHtml(q) ?: return null
        val ids = Regex("""/lyric/([a-z]{2}[0-9]+)""").findAll(html).map { it.groupValues[1] }.distinct().toList()
        return ids.firstOrNull()
    }

    private fun fetchHtml(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9")
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

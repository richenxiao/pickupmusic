package com.shiyin.music.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「检查更新」—— 本项目没有服务器，版本信息只存在于 GitHub Releases。
 *
 * === 为什么是三级回退 ===
 * `api.github.com` 在国内网络下**经常不可达**（超时/连接重置），单一链路会让"检查更新"
 * 对一部分用户永远失败。三条链路按"信息完整度"排序，前者失败就退后者：
 *
 * 1. **Releases API**（`api.github.com/repos/.../releases/latest`）
 *    信息最全：版本号 + 更新说明 + `.apk` 资产直链。有 60 次/小时的匿名限额（够用）。
 * 2. **releases.atom**（`github.com/.../releases.atom`）
 *    静态 XML，无 API 限额，域名是 `github.com`（比 api 子域更容易通）。有版本号 + 说明，
 *    但没有资产直链 —— 只能跳到 Release 页面让用户自己点。
 * 3. **releases/latest 的 302 跳转**
 *    只发一次 HEAD，从 `Location` 里抠出 tag。信息最少，但最容易成功。
 *
 * 任何一条拿到结果就返回；全失败返回 null（UI 保持"未发现更新"，不报错打扰用户）。
 *
 * === 未来想加镜像源 ===
 * 只需在 [SOURCES] 前面插一条自定义 baseUrl 即可，解析器（[UpdateParsers]）不用动。
 */
object UpdateChecker {

    const val OWNER = "richenxiao"
    const val REPO = "pickupmusic"

    private const val UA = "Shiyin/1.0 (Android local music player)"
    private const val TIMEOUT_MS = 8000

    /** 顺序即优先级。 */
    private val SOURCES = listOf(
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest",
        "https://github.com/$OWNER/$REPO/releases.atom",
        "https://github.com/$OWNER/$REPO/releases/latest",
    )

    /** 依次尝试三条链路，返回第一条成功的结果。 */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        // 1) Releases API
        getText(SOURCES[0], accept = "application/vnd.github+json")
            ?.let { UpdateParsers.parseGithubJson(it) }
            ?.let { return@withContext it }
        // 2) atom feed
        getText(SOURCES[1], accept = "application/atom+xml")
            ?.let { UpdateParsers.parseAtom(it) }
            ?.let { return@withContext it }
        // 3) 302 跳转里的 tag
        finalUrlOf(SOURCES[2])
            ?.let { UpdateParsers.tagFromUrl(it) }
            ?.let { tag ->
                return@withContext UpdateInfo(
                    version = tag.removePrefix("v").removePrefix("V"),
                    tag = tag,
                    pageUrl = "https://github.com/$OWNER/$REPO/releases/tag/$tag",
                )
            }
        null
    }

    private fun open(url: String, accept: String?, head: Boolean): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (head) "HEAD" else "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            if (accept != null) setRequestProperty("Accept", accept)
        }
    } catch (_: Exception) {
        null
    }

    private fun getText(url: String, accept: String?): String? = try {
        val conn = open(url, accept, head = false) ?: return null
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** 跟随跳转后的最终地址（`/releases/latest` → `/releases/tag/vX.Y.Z`）。 */
    private fun finalUrlOf(url: String): String? = try {
        val conn = open(url, accept = null, head = true) ?: return null
        try {
            if (conn.responseCode !in 200..399) null else conn.url?.toString()
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}

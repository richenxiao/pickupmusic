package com.shiyin.music.data.backup

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * v22: 极简 WebDAV 客户端（上传/下载/列目录）。
 *
 * === 为什么选 WebDAV 做云备份 ===
 *  - **零 SDK 依赖**：就是 HTTP（PROPFIND / PUT / GET），不需要引任何云盘 SDK
 *  - **国内可用**：坚果云（`https://dav.jianguoyun.com/dav/`）、Nextcloud、群晖都提供
 *  - 与本项目已有范式一致：写真源就是"用户自配地址 + 凭据"
 *
 * === 为什么用 OkHttp 而不是 HttpURLConnection ===
 * Android 的 `HttpURLConnection.setRequestMethod` 只接受固定的几个方法，
 * **`PROPFIND` 会直接抛 ProtocolException**。OkHttp 支持任意方法。
 *
 * 凭据只存在 DataStore，**刻意不进备份文件** —— 否则备份包泄露等于泄露云盘账号。
 */
class WebDavClient(
    baseUrl: String,
    private val user: String,
    private val password: String,
    private val client: OkHttpClient,
) {

    /** 规范化根地址（结尾一定带 `/`）。 */
    val root: String = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }

    private val auth: String = Credentials.basic(user, password)
    private val xmlType = "application/xml; charset=utf-8".toMediaType()

    private val propfindBody = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:"><d:prop>
          <d:getcontentlength/><d:getlastmodified/><d:resourcetype/>
        </d:prop></d:propfind>
    """.trimIndent()

    /** 给上传/下载单独一个超时更长的 client（备份包可能有几 MB）。 */
    private fun longClient(): OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 测试连接。返回可读结果文案；成功时返回 null。
     * 用 `Depth: 0` 的 PROPFIND —— 最轻量，只探根目录本身是否存在/有权限。
     */
    fun test(): String? = try {
        val req = Request.Builder()
            .url(root)
            .method("PROPFIND", propfindBody.toRequestBody(xmlType))
            .header("Authorization", auth)
            .header("Depth", "0")
            .build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                207, 200 -> null
                401, 403 -> "认证失败：请检查账号或密码（坚果云需用「应用密码」，不是登录密码）"
                404 -> "目录不存在：请检查地址（坚果云一般是 https://dav.jianguoyun.com/dav/）"
                else -> "服务器返回 ${resp.code}"
            }
        }
    } catch (e: Exception) {
        "连接失败：${e.message}"
    }

    /** 列出根目录下的文件（只保留直接子项，跳过目录本身）。 */
    fun list(): List<WebDavXml.Entry> = try {
        val req = Request.Builder()
            .url(root)
            .method("PROPFIND", propfindBody.toRequestBody(xmlType))
            .header("Authorization", auth)
            .header("Depth", "1")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string().orEmpty()
            WebDavXml.parseMultiStatus(body).filter { !it.isDirectory }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** 上传（覆盖同名）。成功返回 null，失败返回可读原因。 */
    fun upload(name: String, bytes: ByteArray): String? = try {
        val req = Request.Builder()
            .url(WebDavXml.joinUrl(root, name))
            .put(bytes.toRequestBody("application/zip".toMediaType()))
            .header("Authorization", auth)
            .build()
        longClient().newCall(req).execute().use { resp ->
            when {
                resp.isSuccessful -> null
                resp.code == 401 || resp.code == 403 -> "认证失败，无法写入"
                resp.code == 409 -> "目录不存在（请先在云盘里建好目标文件夹）"
                else -> "上传失败：HTTP ${resp.code}"
            }
        }
    } catch (e: Exception) {
        "上传失败：${e.message}"
    }

    /** 下载。失败返回 null。 */
    fun download(name: String): ByteArray? = try {
        val req = Request.Builder()
            .url(WebDavXml.joinUrl(root, name))
            .get()
            .header("Authorization", auth)
            .build()
        longClient().newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    } catch (_: Exception) {
        null
    }
}

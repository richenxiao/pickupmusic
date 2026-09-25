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
 *
 * === v24 关键修正：文件必须落在应用专属子文件夹里 ===
 *
 * 旧版把备份直接 PUT 到用户填的那个地址（坚果云就是 `/dav/` **账户根目录**），
 * 症状是「测试连接永远成功、备份永远 HTTP 404」。根因不是地址填错，而是**测试与
 * 上传探的根本不是同一条路径**：
 *  - `test()` 对 `/dav/` 做 PROPFIND —— 这个集合当然存在 → 207 → "连接成功"；
 *  - `upload()` PUT `/dav/xxx.zip` —— 服务端判定该写入位置不存在 → 404；
 *  - 旧的 `ensureCollection()` 只对 root 做 MKCOL，而 root（`/dav/`）本来就在，
 *    服务端回 405 → 被静默忽略 → **文件真正的父目录从来没被创建过**。
 *
 * 多份权威配置文档一致指向同一约定（Koodo Reader 官方配置、Zotero 中文社区 404
 * 速查、Reeden / BeeCount / Joplin 教程）：**数据要放在 `/dav/<App名>/` 这样的
 * 专属子文件夹里，且该文件夹必须存在**；Zotero 的 404 说明第一条就是"检查云端是否
 * 生成了 zotero 文件夹"。
 *
 * 因此本版引入 [workDir]：
 *  - 用户只填到挂载点（`/dav/`、或只有主机名）→ 自动用 `<挂载点>/PickUpMusic/`，
 *    缺失时按段递归 MKCOL 建出来；
 *  - 用户自己写了子目录（`/dav/我的备份/`）→ 原样尊重，不再擅自加层。
 * 并且 [test] 改成**端到端**：建目录 → 写真探针 → 删探针，测试通过就等于备份能成。
 */
class WebDavClient(
    baseUrl: String,
    private val user: String,
    private val password: String,
    private val client: OkHttpClient,
) {

    /** 规范化根地址（结尾一定带 `/`，并修掉坚果云的高频填写坑）。 */
    val root: String = normalizeRoot(baseUrl)

    /**
     * 实际读写目录。用户没指定子文件夹时自动落到 App 专属文件夹
     * `<root>PickUpMusic/`，见类注释与 [resolveWorkDir]。
     */
    val workDir: String = resolveWorkDir(root)

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
     *
     * 旧版只对根地址做一次 `Depth: 0` 的 PROPFIND —— **测不出"备份能不能成"**，
     * 正是"连接成功但一备份就 404"的由来。本版按真实写入链路走一遍：
     *   ① 根地址 PROPFIND（认证 / 地址是否对）
     *   ② 递归建好 [workDir]（缺失就建，与上传同一段逻辑）
     *   ③ 在 workDir 里 PUT 一个小探针文件、再删掉 —— 真写真删
     * 这样"测试通过"就等价于"备份能成"。
     */
    fun test(): String? = try {
        val rootCode = propfindCode(root)
        when {
            rootCode == 401 || rootCode == 403 ->
                "认证失败：请检查账号或密码（坚果云需用「应用密码」，不是登录密码）"
            rootCode != 207 && rootCode != 200 ->
                "服务器返回 $rootCode：地址可能不对（坚果云一般是 https://dav.jianguoyun.com/dav/）"
            else -> {
                ensureDirectories()
                val probe = WebDavXml.joinUrl(workDir, PROBE_NAME)
                val code = doPut(probe, PROBE_BYTES).use { it.code }
                if (code in 200..299) {
                    // 探针删掉；删不掉也不影响使用（下次上传会覆盖它）。
                    runCatching { doDelete(probe).use { } }
                    null
                } else {
                    when (code) {
                        401, 403 -> "认证失败：账号或密码不对，或没有该目录的写入权限"
                        404 -> "目录不存在且无法创建：$workDir（HTTP 404）"
                        409 -> "父目录不存在：$workDir（HTTP 409）"
                        507 -> "云端空间不足，写入被拒"
                        else -> "写入失败：HTTP $code（$workDir）"
                    }
                }
            }
        }
    } catch (e: Exception) {
        "连接失败：${e.message}"
    }

    /** 列出 [workDir] 下的文件（只保留直接子项，跳过目录本身）。 */
    fun list(): List<WebDavXml.Entry> = try {
        val req = Request.Builder()
            .url(workDir)
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
        // 目录可能在用户那边被删过/从没建过 —— 每次上传前确保它在（已存在是空操作）。
        ensureDirectories()
        val url = WebDavXml.joinUrl(workDir, name)
        var resp = doPut(url, bytes)
        if (resp.code == 404 || resp.code == 409) {
            resp.close()
            ensureDirectories()
            resp = doPut(url, bytes)
        }
        resp.use { r ->
            when {
                r.isSuccessful -> null
                r.code == 401 || r.code == 403 ->
                    "认证失败，无法写入（坚果云必须用「应用密码」，不是登录密码）"
                r.code == 404 ->
                    "HTTP 404：云端目录不存在（$workDir）"
                r.code == 409 -> "目录不存在（$workDir）"
                r.code == 507 -> "云端空间不足，上传被拒"
                else -> "上传失败：HTTP ${r.code}"
            }
        }
    } catch (e: Exception) {
        "上传失败：${e.message}"
    }

    private fun doPut(url: String, bytes: ByteArray): okhttp3.Response =
        longClient().newCall(
            Request.Builder()
                .url(url)
                .put(bytes.toRequestBody("application/zip".toMediaType()))
                .header("Authorization", auth)
                .build(),
        ).execute()

    private fun doDelete(url: String): okhttp3.Response =
        client.newCall(
            Request.Builder().url(url).delete().header("Authorization", auth).build(),
        ).execute()

    /** 对某个地址做 `Depth: 0` 的 PROPFIND，返回状态码（异常时返回 -1）。 */
    private fun propfindCode(url: String): Int = runCatching {
        val req = Request.Builder()
            .url(url)
            .method("PROPFIND", propfindBody.toRequestBody(xmlType))
            .header("Authorization", auth)
            .header("Depth", "0")
            .build()
        client.newCall(req).execute().use { it.code }
    }.getOrDefault(-1)

    /**
     * 自 [root] 起、按段递归 MKCOL 到 [workDir]，把整条父目录链都建出来。
     *
     * 这是旧版最致命的缺口：旧 `ensureCollection()` 只 MKCOL 了 root 本身，
     * 而 root 通常是**已经存在**的挂载点（服务端回 405），被静默忽略 →
     * 文件真正要落进去的那个子目录永远是缺的 → PUT 一路 404。
     *
     * 已存在的集合返回 405/301/302，都算通过；404（父目录还没有）/403（无权限）
     * 只记日志 —— 真正的错误由随后的 PUT 用它自己的状态码报给用户。
     */
    private fun ensureDirectories() {
        val schemeEnd = workDir.indexOf("//")
        val pathStart = if (schemeEnd >= 0) workDir.indexOf('/', schemeEnd + 2) else -1
        if (pathStart < 0) return
        val base = workDir.substring(0, pathStart)
        var acc = base
        for (seg in workDir.substring(pathStart).split('/').filter { it.isNotEmpty() }) {
            acc = "$acc/$seg"
            runCatching {
                val req = Request.Builder()
                    .url(acc)                       // MKCOL 不带结尾斜杠（部分服务端会拒）
                    .method("MKCOL", null)
                    .header("Authorization", auth)
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.code != 201 && r.code != 405 && r.code != 301 && r.code != 302) {
                        android.util.Log.w("WebDav", "MKCOL $acc -> ${r.code}")
                    }
                }
            }
        }
    }

    /** 下载。失败返回 null。 */
    fun download(name: String): ByteArray? = try {
        val req = Request.Builder()
            .url(WebDavXml.joinUrl(workDir, name))
            .get()
            .header("Authorization", auth)
            .build()
        longClient().newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        /** 应用在云端的专属文件夹名（ASCII，跨服务端最稳）。 */
        const val APP_DIR = "PickUpMusic"
        private const val PROBE_NAME = "pickupmusic-write-test.txt"
        private val PROBE_BYTES = "ok".toByteArray()

        /**
         * 规范化根地址。除了补结尾 `/`，还修掉坚果云文档导致的两个高频填写坑：
         *
         * 坚果云官方文档把"服务器地址"和"路径"**分两个字段**给用户（服务器
         * `dav.jianguoyun.com`，端口 443，路径 `/dav/`），所以在只填一个 URL 的
         * 应用里，用户极容易只填主机名 → PUT 落到主机根 → 服务端 404。
         * 这里按官方规则自动补 `/dav/`；用户自己写的子目录（`/dav/xxx`）不碰。
         *
         * 缺 scheme 时补 `https://`（WebDAV 基本都走 443）。
         */
        internal fun normalizeRoot(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            if (s.lowercase().contains("jianguoyun.com")) {
                val hostEnd = s.indexOf("//") + 2
                val slash = s.indexOf('/', hostEnd)
                if (slash < 0) {
                    s += "/dav"
                } else if (s.substring(slash).trimEnd('/').isEmpty()) {
                    s = s.trimEnd('/') + "/dav"
                }
            }
            if (!s.endsWith("/")) s += "/"
            return s
        }

        /**
         * 解析实际读写目录。
         *
         * 若 root 的路径段为空（只填了主机）或只有一个 `dav`（就是 WebDAV 挂载点本身），
         * 说明用户**没有自己选文件夹** —— 这种地址直接写文件会把备份丢在账户根目录下，
         * 而坚果云这类服务端的根是"同步文件夹总入口"，直接 PUT 文件会 404。
         * 因此自动落到 `<root>PickUpMusic/`。用户写了子目录就原样用。
         */
        internal fun resolveWorkDir(root: String): String {
            if (root.isEmpty()) return root
            val schemeEnd = root.indexOf("//")
            val pathStart = if (schemeEnd >= 0) root.indexOf('/', schemeEnd + 2) else -1
            val path = if (pathStart >= 0) root.substring(pathStart) else "/"
            val segs = path.split('/').filter { it.isNotEmpty() }
            val mountOnly = segs.isEmpty() ||
                (segs.size == 1 && segs[0].equals("dav", ignoreCase = true))
            return if (mountOnly) root + "$APP_DIR/" else root
        }
    }
}

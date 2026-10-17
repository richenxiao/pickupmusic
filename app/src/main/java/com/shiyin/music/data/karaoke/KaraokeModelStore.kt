package com.shiyin.music.data.karaoke

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 伴唱分轨模型的**落盘仓库**：安装（内置解包）/ 下载 / 删除 / 校验。
 *
 * ### 位置
 * 放 `filesDir/karaoke-models/`，**不放 `cacheDir`** —— `cacheDir` 是系统可以随时清掉的，
 * 而模型是几十 MB 的东西，被清掉就得让用户重下。原来的 `ensureModelFile` 用的是
 * `cacheDir`，这里一并改过来。
 *
 * ### 下载
 * 每个模型带**国内优先、国外兜底**两组地址（见 [MdxModel.urlDomestic] / [MdxModel.urlOfficial]），
 * 逐个试，**任何一个源成功后都必须用官方 sha256 校验**：不校验就不装 ——
 * 模型下坏了的表现是"伴奏听着不对"，那种问题事后极难追，不如当场拒绝。
 *
 * 先写 `.tmp` 再 rename，中途失败不会留下"看起来装好了"的半截文件。
 */
class KaraokeModelStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "karaoke-models"))

    fun fileOf(model: MdxModel): File = File(dir, model.assetName)

    /** 已装 = 文件在、且**字节数对得上**（大小不符说明是半截文件）。 */
    fun isInstalled(model: MdxModel): Boolean {
        val f = fileOf(model)
        return f.isFile && f.length() == model.sizeBytes
    }

    /** 有点文件但不是我们要的大小 —— 显示成"损坏，可重下"。 */
    fun isBroken(model: MdxModel): Boolean {
        val f = fileOf(model)
        return f.isFile && f.length() != model.sizeBytes
    }

    fun installedBytes(): Long {
        val f = fileOf(MdxModel.M9482)
        var n = if (f.isFile) f.length() else 0L
        for (m in MdxModel.values()) if (m != MdxModel.M9482) n += fileOf(m).let { if (it.isFile) it.length() else 0L }
        return n
    }

    /** 内置模型：从 APK 资产解包到仓库目录（幂等）。 */
    fun installBundled(context: Context, model: MdxModel): Boolean {
        if (isInstalled(model)) return true
        if (!model.bundled) return false
        return runCatching {
            dir.mkdirs()
            val dst = fileOf(model)
            val tmp = File(dir, model.assetName + ".tmp")
            context.assets.open(model.assetName).use { input ->
                tmp.outputStream().buffered(1 shl 16).use { input.copyTo(it) }
            }
            if (dst.exists()) dst.delete()
            check(tmp.renameTo(dst)) { "模型重命名失败" }
            true
        }.getOrDefault(false)
    }

    /**
     * 确保模型可用（内置的走解包，外部的必须已下载）。失败返回 null ——
     * 调用方负责给用户一句人话，而不是把异常抛到播放链路里。
     */
    fun ensure(context: Context, model: MdxModel): File? {
        if (isInstalled(model)) return fileOf(model)
        if (model.bundled) installBundled(context, model)
        return if (isInstalled(model)) fileOf(model) else null
    }

    fun delete(model: MdxModel): Boolean {
        val f = fileOf(model)
        return if (f.isFile) f.delete() else true
    }

    /**
     * 下载模型。**多源顺序重试 + 断点续传 + sha256 校验**。
     *
     * @param urls 由调用方按用户选的**下载渠道**给出（国内镜像 / 官方直连 / 自动）
     * @param onProgress 回调 `(已收字节, 总字节, 当前源主机)` —— 三个都给，UI 才能说明白
     *   "为什么慢"（比如卡在哪个源上），而不是只看到一个百分比。
     * @return 成功与否 + 人话原因
     */
    suspend fun download(
        model: MdxModel,
        urls: List<String>,
        onProgress: (Long, Long, String) -> Unit = { _, _, _ -> },
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val errors = mutableListOf<String>()
        for (url in urls) {
            try {
                val f = fetchToFile(url, model, onProgress)
                if (f == null) {
                    errors += "${hostOf(url)}：字节数对不上"
                    continue
                }
                if (!verifySha256(f, model.sha256)) {
                    f.delete()
                    errors += "${hostOf(url)}：sha256 校验不过"
                    continue
                }
                val dst = fileOf(model)
                if (dst.exists()) dst.delete()
                if (!f.renameTo(dst)) {
                    f.delete()
                    errors += "${hostOf(url)}：落盘失败"
                    continue
                }
                return@withContext true to "已下载 ${model.displayName}"
            } catch (t: Throwable) {
                errors += "${hostOf(url)}：${t.javaClass.simpleName}"
            }
        }
        false to ("所有下载源都失败了：" + errors.joinToString("；"))
    }

    /**
     * 下到 `.tmp`，**支持断点续传**。
     *
     * 为什么要续传：用户实测 "HQ3 下到 6% 就失败回到 1%" —— 那是**换源后从头再来**。
     * 现在换了源会先看 `.tmp` 已有多少字节，发 `Range: bytes=N-`；服务器回 206 就接着下，
     * 回 200 才从头（并如实告诉 UI "这个源不支持续传，已重下"）。
     *
     * 读超时给到 60s：实测 `hf-mirror.com` 单个 1KB 要 12s，30s 的超时会被慢源反复掐断。
     */
    private fun fetchToFile(
        url: String,
        model: MdxModel,
        onProgress: (Long, Long, String) -> Unit,
    ): File? {
        val tmp = File(dir, model.assetName + ".tmp")
        var done = if (tmp.isFile) tmp.length() else 0L
        if (done > model.sizeBytes) {           // 大小已经超了说明这文件不可信，重来
            tmp.delete(); done = 0L
        }
        val host = hostOf(url)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PickUpMusic/1.0")
            // .onnx 是二进制，别让中间层做 gzip（Content-Length 会对不上）
            setRequestProperty("Accept-Encoding", "identity")
            if (done > 0L) setRequestProperty("Range", "bytes=$done-")
        }
        try {
            val code = conn.responseCode
            val resumed = code == HttpURLConnection.HTTP_PARTIAL   // 206
            if (code !in 200..299) throw IOException("HTTP $code")
            if (done > 0L && !resumed) {
                // 这个源不支持续传 ⇒ 从头来（如实回传，UI 会看到进度回到 0）
                tmp.delete(); done = 0L
            }
            val total = model.sizeBytes
            onProgress(done, total, host)
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmp, /* append = */ resumed).buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastTick = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        // 每 256KB 回传一次，避免每 64KB 就跨线程抖一下 UI
                        if (done - lastTick >= 262_144L) {
                            lastTick = done
                            onProgress(done, total, host)
                        }
                    }
                }
            }
            onProgress(done, total, host)
            return if (tmp.length() == model.sizeBytes) tmp else null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    companion object {
        /** sha256 校验（十六进制、大小写不敏感）。纯逻辑，可 JVM 单测。 */
        fun verifySha256(file: File, expectedHex: String): Boolean = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1 shl 16).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            got.equals(expectedHex, ignoreCase = true)
        }.getOrDefault(false)
    }
}

/**
 * **下载渠道**（用户 2026-10-17 要求："请给出两种下载渠道选择"）。
 *
 * 起因：用户实测 Inst_HQ_3 "下到 6% 就失败回到 1%" —— 那时的源顺序把**慢源排在了前面**，
 * 慢源被读超时掐断后换源又从 0 开始。现在：渠道可选、换源**断点续传**、UI 显示当前源。
 *
 * 2026-10-17 本机实测（每个只取 1KB 验连通，**未整包下载**）：
 *  · `huggingface.co` **连接超时，不可用**；
 *  · GitHub releases 直连（跟随重定向）206，约 2.1s；
 *  · `ghproxy.net` 206，约 **1.1s，最快**；
 *  · `hf-mirror.com` 206，约 12.3s，**明显慢**。
 * ⚠️ 该实测是**当时那台机器**的网络；若当时挂着代理，GitHub 那项不代表国内裸连。
 */
enum class DownloadChannel(val label: String) {
    /** 默认：国内镜像优先，失败再走官方。 */
    AUTO("自动"),
    /** 只用国内镜像（ghproxy / hf-mirror）。 */
    DOMESTIC("国内镜像"),
    /** 只用官方源（GitHub / HuggingFace）——挂着梯子时选它往往最快。 */
    OFFICIAL("官方直连");

    companion object {
        fun byName(n: String?): DownloadChannel =
            entries.firstOrNull { it.name == n } ?: AUTO
    }
}

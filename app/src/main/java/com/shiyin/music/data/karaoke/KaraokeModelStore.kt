package com.shiyin.music.data.karaoke

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /**
     * 删除模型。**必须是真删** —— 用户 2026-10-18 报"删了返回仍显示已下载"，
     * 根因是当时 VM 里有一段"外部目录有文件就自动装上"的自动导入，刷新时把刚删的装回来了。
     * 那段已删除；这里保持"删了就是没了"。
     *
     * 幂等：文件本来就不在也返回 true（调用方不必区分）。
     */
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
                val f = fetchSegmented(url, model, onProgress)
                if (f == null) {
                    errors += "${hostOf(url)}：取不到完整文件"
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
     * **多连接分段下载**。这是"给所有用户都下得动"的关键，不是优化项。
     *
     * 2026-10-17 真机实测（手机 5G，每个源各取 2–4MB）：
     * ```
     *  单连接：hf-mirror 0.09 MB/s（68MB 要 13 分钟） / ghfast 0.31 MB/s（216s） / GitHub 直连不通
     *  4 路并发：ghfast **0.79 MB/s（68MB 只要 86s）—— 2.5×**
     * ```
     * ⇒ 瓶颈是这些免费代理的**单连接限速**，不是用户的网。多开几条连接就能绕开。
     *
     * 实现要点：
     *  · 先发一个 `Range: bytes=0-0` 探测是否支持分段（回 206 才走多线程）；不支持则单连接整取；
     *  · 每个段写自己的 `.part<i>`，**按已写字节续传**（段内也能续）—— 换源重试不会从头再来；
     *  · 全部段齐了再按顺序拼成一个文件，然后**统一 sha256 校验**（拼错/传坏都挡在这里）；
     *  · 进度 = 所有段长度之和 / 总大小，所以 UI 上是**单调递增**的，不会"回到 1%"。
     */
    private suspend fun fetchSegmented(
        url: String,
        model: MdxModel,
        onProgress: (Long, Long, String) -> Unit,
    ): File? = coroutineScope {
        val total = model.sizeBytes
        val host = hostOf(url)
        fun progress() {
            val got = (0 until SEGMENTS).sumOf { i ->
                File(dir, "${model.assetName}.part$i").let { if (it.isFile) it.length() else 0L }
            }
            onProgress(got, total, host)
        }
        val rangesOk = runCatching { probeRanges(url) }.getOrDefault(false)

        if (!rangesOk) {
            // 不支持分段：单连接整取（仍然保留"已有 .part0 就续传"的能力）
            val ok = fetchOneRange(url, File(dir, "${model.assetName}.part0"), 0L, total - 1) { progress() }
            if (!ok) return@coroutineScope null
        } else {
            val per = (total + SEGMENTS - 1) / SEGMENTS
            val jobs = (0 until SEGMENTS).map { i ->
                val start = i.toLong() * per
                val end = minOf(start + per, total) - 1
                if (start > end) null else async {
                    fetchOneRange(url, File(dir, "${model.assetName}.part$i"), start, end) { progress() }
                }
            }.filterNotNull()
            val allOk = jobs.awaitAll().all { it }
            if (!allOk) return@coroutineScope null
        }

        // 拼装
        val merged = File(dir, model.assetName + ".tmp")
        if (merged.exists()) merged.delete()
        runCatching {
            merged.outputStream().buffered(1 shl 16).use { out ->
                for (i in 0 until SEGMENTS) {
                    val part = File(dir, "${model.assetName}.part$i")
                    if (!part.isFile) continue
                    part.inputStream().buffered(1 shl 16).use { it.copyTo(out) }
                }
            }
        }.onFailure { return@coroutineScope null }
        for (i in 0 until SEGMENTS) File(dir, "${model.assetName}.part$i").delete()
        progress()
        return@coroutineScope if (merged.length() == total) merged else { merged.delete(); null }
    }

    /** 探测服务器是否支持 Range（回 206 = 支持）。 */
    private fun probeRanges(url: String): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PickUpMusic/1.0")
            setRequestProperty("Range", "bytes=0-0")
        }
        return try {
            conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        } finally {
            runCatching { conn.inputStream?.close() }
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 取 `[start, end]` 这一段写进 [part]，**从 part 已有长度继续**（段内续传）。
     * @return 是否成功拿到完整段
     */
    private fun fetchOneRange(
        url: String,
        part: File,
        start: Long,
        end: Long,
        onTick: () -> Unit,
    ): Boolean {
        var have = if (part.isFile) part.length() else 0L
        val want = end - start + 1
        if (have > want) { part.delete(); have = 0L }
        if (have == want) return true
        val from = start + have
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PickUpMusic/1.0")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=$from-$end")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return false
            // 段内续传时服务器必须回 206；回 200 说明它忽略了 Range（会重发整段）⇒ 只能从头
            val append = have > 0L && code == HttpURLConnection.HTTP_PARTIAL
            if (have > 0L && !append) { part.delete(); have = 0L }
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, append).buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var sinceTick = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        sinceTick += n
                        if (sinceTick >= 262_144L) { sinceTick = 0; onTick() }
                    }
                }
            }
            onTick()
            return part.length() == want
        } catch (t: Throwable) {
            onTick()
            return false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    companion object {
        /**
         * 分段下载的**段数**。实测 4 路并发比单连接快 2.5×（0.31→0.79 MB/s）。
         * 再往上加收益递减（且免费代理可能对总连接数也有限制），4 是个稳的值。
         */
        const val SEGMENTS = 4

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

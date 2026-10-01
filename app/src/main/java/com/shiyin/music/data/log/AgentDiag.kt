package com.shiyin.music.data.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v1.3.14:Agent **诊断样本** —— 把"模型到底回了什么、解析命中了多少"留在本地可查。
 *
 * 为什么需要它(真机实测的两件事):
 *  1. logcat 在本机被系统抑制 —— 我的 App 日志**一行都不进缓冲区**(实测该进程只有 55 行
 *     系统/图形日志、无任何自定义 tag),想靠日志排查 Agent 等于没有手段;
 *  2. 现有 `UsageLog` 只记"成功/失败",看不出"模型回复没按格式"卡在哪一步,
 *     而它的原则是**不记内容**(文件会被用户分享出去)。
 *
 * 所以这里**另开一路**,与 `UsageLog` 分开:
 *  - 独立目录 `filesDir/agent_diag/`,`UsageLog` 的"不记内容"原则**不破例**;
 *  - 环形缓冲([RING_SIZE] 条),**不进备份导出链**(避免把模型回复带进备份);
 *  - 会记**模型回复开头若干字**,所以诊断页必须提示"含对话内容,分享前请检查";
 *  - 入库前做密钥形态脱敏([sanitize])。
 *
 * 编码是**纯函数、且刻意不做转义**:分隔符与换行在 [flatten] 里被换成空格——
 * 诊断日志只需要"一眼看清模型回了什么",不需要保留原文格式;没有转义规则,
 * 也就没有"转义写错导致整行解析崩掉"的空间。手写(不用 Gson)是因为项目禁 Gson 反射绑定,
 * 而 org.json 在纯 JVM 单测里是桩、不可用。
 */
object AgentDiag {

    /** 环形缓冲上限:只留最近这么多条。 */
    const val RING_SIZE = 50

    /** 回复原文保留长度(超出截断)。 */
    const val REPLY_HEAD_MAX = 500

    private const val DIR_NAME = "agent_diag"
    private const val FILE_NAME = "diag.log"

    /** 字段分隔符。内容里出现的它会被 [flatten] 换成空格,所以不会有歧义。 */
    private const val SEP = '|'

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "agent-diag").apply { isDaemon = true }
    }

    /**
     * 一条诊断样本。
     *
     * 字段与"排查一个问题需要什么"对齐:`stage` 指出卡在哪一步,`counts` 给出量
     * (候选数/命中数),`replyHead` 是模型原话的开头。
     */
    data class Sample(
        val tsMs: Long,
        val skill: String,
        val stage: String,
        val ok: Boolean,
        val counts: Map<String, Int> = emptyMap(),
        val replyHead: String? = null,
        val errorCode: String? = null,
        val errorDetail: String? = null,
    )

    // ── 纯编解码(可单测) ──────────────────────────────────────────────────

    /**
     * 字段 → 单行安全文本:分隔符与所有空白(含换行)**统一折叠成单个空格**,再去首尾。
     *
     * 折叠的原因:模型回复常带缩进/空行,不折叠的话诊断页上一行会有大片空白、很难读。
     */
    internal fun flatten(s: String): String =
        s.replace(SEP, ' ').replace(Regex("""\s+"""), " ").trim()

    /** 样本 → 一行文本(字段顺序固定,便于人工阅读与解析)。 */
    internal fun encode(s: Sample): String {
        val counts = s.counts.entries.joinToString(",") { "${it.key}=${it.value}" }
        return listOf(
            s.tsMs.toString(),
            if (s.ok) "1" else "0",
            flatten(s.skill),
            flatten(s.stage),
            flatten(counts),
            flatten(s.replyHead.orEmpty()),
            flatten(s.errorCode.orEmpty()),
            flatten(s.errorDetail.orEmpty()),
        ).joinToString(SEP.toString())
    }

    /** 一行文本 → 样本;格式不对返回 null(坏行跳过,不让它拖垮整页)。 */
    internal fun decode(line: String): Sample? {
        val parts = line.split(SEP)
        if (parts.size < 8) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val counts = parts[4].split(',').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null
            else kv.substring(0, i) to (kv.substring(i + 1).toIntOrNull() ?: return@mapNotNull null)
        }.toMap()
        return Sample(
            tsMs = ts,
            skill = parts[2],
            stage = parts[3],
            ok = parts[1] == "1",
            counts = counts,
            replyHead = parts[5].ifBlank { null },
            errorCode = parts[6].ifBlank { null },
            errorDetail = parts[7].ifBlank { null },
        )
    }

    /**
     * 密钥形态脱敏。样本会被用户复制出去,**不能把 Key 带走**。
     *
     * 只做形态识别(前缀/长度特征),不追求完备 —— 真正的保险是"不记请求体"。
     */
    fun sanitize(text: String): String {
        var out = text
        // sk-xxxx / rk- / pk- 等常见形态
        out = out.replace(Regex("""\b(sk|rk|pk)-[A-Za-z0-9_\-]{8,}"""), "[已脱敏]")
        // Bearer / api_key / Authorization / token 后面跟的值
        out = out.replace(
            Regex("""(?i)\b(bearer|api[-_]?key|authorization|token)\b\s*[:=]?\s*[A-Za-z0-9_\-.]{12,}"""),
            "[已脱敏]",
        )
        // Tavily key 形态
        out = out.replace(Regex("""\btvly-[A-Za-z0-9]{8,}"""), "[已脱敏]")
        // 长串字母数字(≥32:常见 API Key 长度)
        out = out.replace(Regex("""\b[A-Za-z0-9]{32,}\b"""), "[已脱敏]")
        return out
    }

    /** 模型回复 → 脱敏 + 截断到 [REPLY_HEAD_MAX];空则 null。 */
    fun replyHeadOf(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        return sanitize(t).take(REPLY_HEAD_MAX)
    }

    // ── 落盘 ───────────────────────────────────────────────────────────────

    private fun dir(context: Context) = File(context.filesDir, DIR_NAME)

    private fun file(context: Context) = File(dir(context), FILE_NAME)

    /**
     * 记一条样本。[enabled] 由调用方从设置读(本类不依赖 SettingsStore,保持可测)。
     * 写盘走独立单线程,不阻塞 UI;**任何异常都吞掉** —— 诊断失败绝不能影响主流程。
     */
    fun record(context: Context, sample: Sample, enabled: Boolean) {
        if (!enabled) return
        val app = context.applicationContext
        io.execute {
            runCatching {
                val f = file(app)
                dir(app).mkdirs()
                val kept = readLines(f).takeLast(RING_SIZE - 1)
                f.writeText((kept + encode(sample)).joinToString("\n") + "\n")
            }
        }
    }

    private fun readLines(f: File): List<String> =
        runCatching { f.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** 最近 [limit] 条样本(**新的在前**,便于先看最新一次失败)。 */
    fun readAll(context: Context, limit: Int = RING_SIZE): List<Sample> =
        readLines(file(context)).mapNotNull { decode(it) }.takeLast(limit).reversed()

    /** 清空全部样本。 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** 供"复制全部"导出为纯文本(用户可直接粘贴给开发窗口)。 */
    fun exportText(context: Context): String {
        val samples = readAll(context)
        if (samples.isEmpty()) return ""
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        return samples.joinToString("\n\n") { sampleText(it, fmt) }
    }

    /**
     * 单条样本的纯文本(供「复制全部」)。
     *
     * 要**自成一条能读懂的证据**:时间/技能/阶段/成败,外加**量与错因**(counts / errorCode /
     * errorDetail)与**模型回复开头** —— 只给一行列表摘要的话,拿到文本也没法判断"是模型没
     * 按格式回、还是解析丢了、还是候选池本来就空"。抽成纯函数是为了可脱机单测(见 AgentDiagTest)。
     */
    internal fun sampleText(s: Sample, fmt: SimpleDateFormat): String = buildString {
        append(fmt.format(Date(s.tsMs)))
        append("  [").append(s.skill).append("] ").append(s.stage)
        append(if (s.ok) " · OK" else " · FAIL")
        if (s.counts.isNotEmpty()) {
            append("\n  counts: ").append(s.counts.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
        s.errorCode?.let { append("\n  error: ").append(it) }
        s.errorDetail?.let { append("\n  detail: ").append(it) }
        s.replyHead?.let { append("\n  模型回复开头: ").append(it) }
    }
}

package com.shiyin.music.data.log

import android.content.Context
import java.io.File

/**
 * v1.3.15:「导出对话」——把当前会话的**内容现场**写成一份 JSON,供人工排查/深度调研读取。
 *
 * 为什么要有它:[AgentDiag] 每阶段只留 500 字的模型回复开头(轻量排查够用),而"模型看了什么
 * 提示、回了什么、被解析成什么、工具收到了什么参数"这类**深度调查**需要完整现场。设备上装的是
 * release 包(非 debuggable):Room 库、内部存储(`filesDir`)、logcat 都取不到 —— 所以由 App
 * 主动写到**外部私有目录** `Android/data/<pkg>/files/agent_dump/`(adb 可 pull,用户也能自查)。
 *
 * 纪律:
 *  - 整份文本过一遍 [AgentDiag.sanitize]:密钥形态一律变 `[已脱敏]`;
 *  - **不写任何 apiKey 字段**(只写供应商名与模型名);
 *  - 由用户主动点击触发,不自动落盘(内容含对话与思考链)。
 */
internal object AgentChatDump {

    /** 一条消息(来自 UI 的会话列表)。 */
    data class Msg(
        val role: String,
        val text: String,
        val thinking: String?,
        val steps: List<String>,
        val tokens: Pair<Int, Int>?,
    )

    /** 环境信息(便于判断"是不是这个模型/这个版本的问题")。 */
    data class Meta(
        val appVersion: String,
        val device: String,
        val android: String,
        val provider: String,
        val model: String,
        val skills: List<String>,
        val diagEnabled: Boolean,
    )

    /** 构造导出的 JSON 文本(纯函数,可脱机单测)。 */
    fun build(
        messages: List<Msg>,
        toolTrail: List<String>,
        samples: List<AgentDiag.Sample>,
        meta: Meta,
        tsMs: Long,
    ): String {
        val sb = StringBuilder(4096)
        sb.append("{\n")
        sb.append("  \"exportedAtMs\": ").append(tsMs).append(",\n")
        sb.append("  \"meta\": {\n")
        sb.append("    \"appVersion\": ").append(str(meta.appVersion)).append(",\n")
        sb.append("    \"device\": ").append(str(meta.device)).append(",\n")
        sb.append("    \"android\": ").append(str(meta.android)).append(",\n")
        sb.append("    \"provider\": ").append(str(meta.provider)).append(",\n")
        sb.append("    \"model\": ").append(str(meta.model)).append(",\n")
        sb.append("    \"diagEnabled\": ").append(meta.diagEnabled).append(",\n")
        sb.append("    \"skills\": [").append(meta.skills.joinToString(", ") { str(it) }).append("]\n")
        sb.append("  },\n")

        sb.append("  \"toolTrail\": [")
        sb.append(toolTrail.joinToString(", ") { str(it) })
        sb.append("],\n")

        sb.append("  \"messages\": [\n")
        messages.forEachIndexed { i, m ->
            sb.append("    {\n")
            sb.append("      \"role\": ").append(str(m.role)).append(",\n")
            sb.append("      \"text\": ").append(str(m.text)).append(",\n")
            sb.append("      \"thinking\": ").append(str(m.thinking ?: "")).append(",\n")
            sb.append("      \"steps\": [").append(m.steps.joinToString(", ") { str(it) }).append("]")
            m.tokens?.let { (p, c) ->
                sb.append(",\n      \"tokens\": {\"prompt\": ").append(p).append(", \"completion\": ").append(c).append("}")
            }
            sb.append("\n    }").append(if (i == messages.lastIndex) "" else ",").append("\n")
        }
        sb.append("  ],\n")

        sb.append("  \"diagSamples\": [\n")
        samples.forEachIndexed { i, s ->
            sb.append("    {\n")
            sb.append("      \"tsMs\": ").append(s.tsMs).append(",\n")
            sb.append("      \"skill\": ").append(str(s.skill)).append(",\n")
            sb.append("      \"stage\": ").append(str(s.stage)).append(",\n")
            sb.append("      \"ok\": ").append(s.ok).append(",\n")
            sb.append("      \"counts\": {")
            sb.append(s.counts.entries.joinToString(", ") { "${str(it.key)}: ${it.value}" })
            sb.append("},\n")
            sb.append("      \"errorCode\": ").append(str(s.errorCode ?: "")).append(",\n")
            sb.append("      \"errorDetail\": ").append(str(s.errorDetail ?: "")).append(",\n")
            sb.append("      \"webHead\": ").append(str(s.webHead ?: "")).append(",\n")
            sb.append("      \"replyHead\": ").append(str(s.replyHead ?: "")).append("\n")
            sb.append("    }").append(if (i == samples.lastIndex) "" else ",").append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        // 整份文本统一脱敏(只重写密钥形态的长串,不动 JSON 语法)
        return AgentDiag.sanitize(sb.toString())
    }

    /**
     * 写到外部私有目录 `Android/data/<pkg>/files/agent_dump/chat_<ts>.json`。
     * 拿不到外部目录(极少数机型)时退回内部 filesDir(至少不丢数据,只是要用户在 App 内导出)。
     */
    fun write(context: Context, text: String, tsMs: Long): File? = try {
        val dir = context.getExternalFilesDir("agent_dump") ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "chat_$tsMs.json")
        f.writeText(text)
        f
    } catch (_: Exception) {
        null
    }

    // ── JSON 手写(不用 Gson 反射:见项目约定 "Gson+R8 反射禁令") ──────────────

    private fun str(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""

    private fun esc(s: String): String = buildString(s.length + 8) {
        for (ch in s) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append(String.format("\\u%04x", ch.code)) else append(ch)
        }
    }
}

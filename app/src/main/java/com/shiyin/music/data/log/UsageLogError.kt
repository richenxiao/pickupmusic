package com.shiyin.music.data.log

/**
 * v1.4.0：把**原始错误文本**归成**类别**，供使用日志记录。
 *
 * 为什么需要：`usage_logs` 的原则是"只记做了什么、不记内容"，但有两处直接写了原始错误串——
 * 云备份失败写的是 WebDAV 客户端的**可读文案**，里面带远端地址与目录路径
 * （实测形态：「服务器返回 404：地址可能不对（坚果云一般是 https://dav.jianguoyun.com/dav/）」、
 * 「目录不存在且无法创建：/dav/xxx（HTTP 409）」）；翻译失败写的是模型/管线的错误文本。
 * 而日志文件会随云备份上移、也会被用户导出分享，所以**只留"是什么错"，不留原文与地址**。
 *
 * 纯函数，可脱机单测（见 UsageLogErrorTest）；新增错误形态时**只加分支、绝不回退成"打印原文"**。
 */
internal object UsageLogError {

    /** WebDAV 客户端的两种状态码写法：「HTTP 404」「服务器返回 404」。 */
    private val CODE = Regex("""(?i)(?:HTTP\s*|服务器返回\s*)(\d{3})""")

    /** 原始错误串 → 类别。识别不出时返回「其他」——**不复制任何原文**。 */
    fun reasonOf(message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty()) return "未知"
        CODE.find(raw)?.let { return "HTTP ${it.groupValues[1]}" }
        val lower = raw.lowercase()
        return when {
            "认证失败" in raw || "unauthorized" in lower || "forbidden" in lower -> "鉴权失败"
            "timeout" in lower || "timed out" in lower -> "超时"
            "resolve" in lower || "unknownhost" in lower -> "域名解析失败"
            "连接失败" in raw || "connectexception" in lower || "connect" in lower -> "连接失败"
            "ssl" in lower || "certificate" in lower || "证书" in raw -> "TLS 证书"
            "no space" in lower || "enospc" in lower || "空间不足" in raw -> "存储空间不足"
            else -> "其他"
        }
    }
}

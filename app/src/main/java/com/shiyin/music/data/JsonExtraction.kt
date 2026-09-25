package com.shiyin.music.data

/**
 * v1.3.8: 从 LLM 的自由文本回复里抠出第一个**配平的** JSON 对象。
 *
 * ## 为什么必须是这个实现,而不是正则
 *
 * 原实现用 `Regex("""\{.*}""", DOT_MATCHES_ALL)` 抠 JSON,在两个层面都错:
 *
 * 1. **在 Android 上直接抛异常**。实测真机日志(星野源专辑日期):
 *    `PatternSyntaxException: Syntax error in regexp pattern near index 5` / `\{.*}`
 *    ——未转义的裸 `}` 在 Android 的 java.util.regex 上编译不过。而 JVM 单测用的是
 *    桌面 JDK 的正则(接受裸 `}`),**所以单测永远测不出这个 bug**,只在真机上炸。
 *    结果:模型明明返回了完全正确的 JSON,解析却整段失败、数据被丢掉,表现为
 *    "AI 联网搜到了却什么都没返回"。
 * 2. **贪婪匹配会吃太多**。`\{.*}` 会一直吃到**最后一个** `}`,模型在 JSON 后面
 *    再带说明文字(或返回第二个对象)时拼出来就不是合法 JSON 了。这一条
 *    [com.shiyin.music.data.furigana.FuriganaLlmAssist] 早已踩过并改用本方案。
 *
 * 因此全项目统一走这一个实现:首个 `{` 起逐字符计深度,遇到配平的 `}` 即止。
 * 字符串感知——`"}` 这样出现在字符串里的括号不会被误当结构括号。
 *
 * ⚠️ 新增 JSON 抠取时**不要**再写 `\{.*}` 族正则,直接用本函数。
 *
 * @return 配平的 JSON 对象文本;没有 `{` 或括号不配平返回 null。
 */
internal fun firstBalancedJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inStr = false
    var esc = false
    for (i in start until raw.length) {
        val c = raw[i]
        if (esc) { esc = false; continue }
        when {
            inStr && c == '\\' -> esc = true
            c == '"' -> inStr = !inStr
            !inStr && c == '{' -> depth++
            !inStr && c == '}' -> {
                depth--
                if (depth == 0) return raw.substring(start, i + 1)
            }
        }
    }
    return null
}

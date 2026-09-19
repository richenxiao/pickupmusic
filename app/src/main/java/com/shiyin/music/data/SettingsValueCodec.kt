package com.shiyin.music.data

/**
 * v22: 设置项的备份编解码（**纯逻辑，可单测**）。
 *
 * DataStore 存的是**带类型**的 Preferences，导出成 JSON 时只剩字符串。为了导入时能用
 * 正确的 `Preferences.Key<T>` 写回去，每个值前面加一个**类型前缀**。
 *
 * 为什么要前缀而不是"猜类型"：`"1"` 既可能是 String 也可能是 Int，`"true"` 同理；
 * 猜错会让 `prefs[intKey] = "1"` 直接抛 ClassCastException —— 整个恢复流程崩在最后一步。
 *
 * 集合元素用控制字符 U+0001 分隔（设置值里不可能出现）。
 */
object SettingsValueCodec {

    const val BOOL = 'b'
    const val STR = 's'
    const val FLOAT = 'f'
    const val INT = 'i'
    const val LONG = 'l'
    const val STR_SET = 't'

    /** 集合元素分隔符。 */
    const val SET_SEP = '\u0001'

    /** 编码。遇到未知类型返回 null —— 调用方跳过该 key，而不是写坏整份备份。 */
    fun encode(value: Any?): String? = when (value) {
        is Boolean -> "$BOOL$value"
        is String -> "$STR$value"
        is Float -> "$FLOAT$value"
        is Int -> "$INT$value"
        is Long -> "$LONG$value"
        is Set<*> -> STR_SET + value.filterIsInstance<String>().joinToString(SET_SEP.toString())
        else -> null
    }

    /** 取类型前缀；空串返回 null。 */
    fun typeOf(encoded: String): Char? = encoded.firstOrNull()

    /** 以下解码全部**容错**：解析失败返回 null / 空集，绝不抛异常。 */
    fun boolOf(encoded: String): Boolean? = encoded.drop(1).toBooleanStrictOrNull()

    fun stringOf(encoded: String): String = encoded.drop(1)

    fun floatOf(encoded: String): Float? = encoded.drop(1).toFloatOrNull()

    fun intOf(encoded: String): Int? = encoded.drop(1).toIntOrNull()

    fun longOf(encoded: String): Long? = encoded.drop(1).toLongOrNull()

    fun setOf(encoded: String): Set<String> =
        encoded.drop(1).split(SET_SEP).filter { it.isNotEmpty() }.toSet()
}

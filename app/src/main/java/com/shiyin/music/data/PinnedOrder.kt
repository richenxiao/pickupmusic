package com.shiyin.music.data

/**
 * v1.5.0 右滑固定：**有序**固定列表的编解码（纯逻辑，可 JVM 单测）。
 *
 * 为什么需要它：pinned 区需要"谁先谁后"，而 DataStore 的 `stringSetPreferencesKey`
 * 是 **Set，没有顺序**，存不了。所以另存一条分隔字符串。
 *
 * ⚠️ **顺序语义改过一次**（定稿更正）：
 *  · 原规格（2026-10-15）："后固定者在上" ⇒ index 0 = 最近固定；
 *  · 现规格（2026-10-18）：「**先固定的高于后固定的**」⇒ **index 0 = 最先固定**，
 *    新固定的**追加到末尾**。
 * 两版都存在过，历史数据无法自动判定用哪版 —— **不做迁移**，用户重排一次即可
 * （固定项通常只有几条）。
 *
 * 分隔符用 **U+0001（单元分隔符）**：专辑名/歌手名里不可能出现，也不会与用户数据冲突。
 * 刻意**不用逗号或换行**——歌手名可能含逗号，标签值可能含换行。
 *
 * ⚠️ 这里用 [SEP_CODE] 显式码点 + `toChar()`，而不是写 `''` 字面量：
 * 源码里一个不可见控制字符太容易被编辑器/工具链改坏或吞掉（本项目刚踩过一次），
 * 用数字写死则**一眼可查**。
 */
object PinnedOrder {

    /** 分隔符码点：U+0001 单元分隔符。 */
    const val SEP_CODE: Int = 1

    private fun sep(): String = SEP_CODE.toChar().toString()

    /** 编码：`[a, b, c]` → `"abc"`。空列表 → 空串。 */
    fun encode(keys: List<String>): String =
        keys.filter { it.isNotEmpty() }.joinToString(sep())

    /**
     * 解码：空串/null → 空列表。
     * 过滤掉空片段，这样"末尾多个分隔符"之类的脏数据不会变成空 key 混进排序。
     */
    fun decode(s: String?): List<String> {
        if (s.isNullOrEmpty()) return emptyList()
        return s.split(sep()).filter { it.isNotEmpty() }
    }

    /**
     * 固定 / 取消固定之后的**新列表**。规则：**先固定者在上**（index 0 排最前）。
     *
     * · 固定：**追加到末尾** —— 它是"后固定"的那个，排在已有固定项**下面**；
     * · 取消：直接移除，位次失效后该条目自然回到普通区原位。
     *
     * **幂等**：对已在列表里的 key 再固定一次 = 原位不动（不会莫名跳到末尾）。
     */
    fun applyPin(cur: List<String>, itemKey: String, pinned: Boolean): List<String> {
        if (itemKey.isEmpty()) return cur
        if (pinned) {
            // 已在列表里 ⇒ **原位不动**。否则用户误触一次就会把条目莫名挪到末尾。
            return if (itemKey in cur) cur else cur + itemKey
        }
        return cur.filter { it != itemKey }
    }
}

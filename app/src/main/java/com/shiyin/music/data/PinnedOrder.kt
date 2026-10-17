package com.shiyin.music.data

/**
 * v1.5.0 右滑固定：**有序**固定列表的编解码（纯逻辑，可 JVM 单测）。
 *
 * 为什么需要它：用户 2026-10-15 的规格要求 pinned 区"**后固定者在上**"，而
 * DataStore 的 `stringSetPreferencesKey` 是 **Set，没有顺序**，存不了"谁先谁后"。
 * 所以另存一条分隔字符串，index 0 = 最近固定。
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
}

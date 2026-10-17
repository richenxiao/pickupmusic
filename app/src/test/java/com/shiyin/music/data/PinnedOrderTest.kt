package com.shiyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有序固定列表的编解码（用户 2026-10-15 规格："后固定者在上" 需要顺序，而 Set 存不了顺序）。
 *
 * 这些用例把**分隔符本身**也钉住：源码里写一个不可见控制字符很容易被工具链改坏/吞掉
 * （本轮真踩过一次），所以用 [PinnedOrder.SEP_CODE] 显式码点，并在测试里断言它。
 */
class PinnedOrderTest {

    @Test
    fun `编码再解码_顺序不变`() {
        val keys = listOf("song:7", "album:abc", "artist:周杰伦", "pl:p3")
        assertEquals(keys, PinnedOrder.decode(PinnedOrder.encode(keys)))
    }

    @Test
    fun `分隔符就是 U+0001 且只有一个字符`() {
        val encoded = PinnedOrder.encode(listOf("a", "b"))
        assertEquals(3, encoded.length)                  // a + sep + b
        assertEquals(1, PinnedOrder.SEP_CODE)
        assertEquals(PinnedOrder.SEP_CODE.toChar(), encoded[1])
    }

    @Test
    fun `key 里含逗号或换行也不会被切开`() {
        // 分隔符刻意不用逗号/换行：歌手名可能含逗号，标签值可能含换行
        val keys = listOf("artist:A, B", "album:x\ny", "artist:AC/DC")
        assertEquals(keys, PinnedOrder.decode(PinnedOrder.encode(keys)))
    }

    @Test
    fun `空列表编码成空串`() {
        assertEquals("", PinnedOrder.encode(emptyList()))
        assertEquals(emptyList<String>(), PinnedOrder.decode(""))
        assertEquals(emptyList<String>(), PinnedOrder.decode(null))
    }

    @Test
    fun `脏数据里的空片段被过滤`() {
        val sep = PinnedOrder.SEP_CODE.toChar()
        // 连续分隔符（中间有空片段）
        assertEquals(listOf("a", "b"), PinnedOrder.decode("a$sep${sep}b"))
        // 首尾多余分隔符
        assertEquals(listOf("a", "b"), PinnedOrder.decode("${sep}a${sep}b$sep"))
    }

    @Test
    fun `编码时丢掉空字符串项`() {
        assertEquals(listOf("a"), PinnedOrder.decode(PinnedOrder.encode(listOf("", "a"))))
    }

    @Test
    fun `重复项保持两次出现_由调用方去重`() {
        // 编解码本身不负责去重（去重语义在 VM 的 setLibItemPinned 里），这里只保证不丢数据
        val keys = listOf("a", "b", "a")
        assertEquals(keys, PinnedOrder.decode(PinnedOrder.encode(keys)))
        assertTrue(PinnedOrder.decode(PinnedOrder.encode(keys)).size == 3)
    }

    // ── 排序规则（用户 2026-10-18 更正为"先固定者在上"）─────────────────────

    @Test
    fun `先固定的排在前面_后固定追加到末尾`() {
        var cur = emptyList<String>()
        cur = PinnedOrder.applyPin(cur, "第一个固定的", pinned = true)
        cur = PinnedOrder.applyPin(cur, "第二个固定的", pinned = true)
        cur = PinnedOrder.applyPin(cur, "第三个固定的", pinned = true)
        assertEquals(
            "index 0 必须在最上 ⇒ 最先固定的排第一（旧版是后固定者在上，已更正）",
            listOf("第一个固定的", "第二个固定的", "第三个固定的"), cur,
        )
    }

    @Test
    fun `取消固定只移除_其余顺序不变`() {
        val cur = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), PinnedOrder.applyPin(cur, "b", pinned = false))
        assertEquals(listOf("a", "b", "c"), PinnedOrder.applyPin(cur, "不在列表里的", pinned = false))
    }

    @Test
    fun `对已固定的项再固定一次_原位不动`() {
        // 幂等：否则用户误触一次就会把条目莫名挪到末尾
        val cur = listOf("a", "b", "c")
        assertEquals(cur, PinnedOrder.applyPin(cur, "b", pinned = true))
    }

    @Test
    fun `空 key 不参与排序`() {
        val cur = listOf("a")
        assertEquals(cur, PinnedOrder.applyPin(cur, "", pinned = true))
        assertEquals(cur, PinnedOrder.applyPin(cur, "", pinned = false))
    }

    @Test
    fun `取消后再固定_会排到末尾`() {
        var cur = listOf("a", "b", "c")
        cur = PinnedOrder.applyPin(cur, "a", pinned = false)   // 取消 a
        assertEquals(listOf("b", "c"), cur)
        cur = PinnedOrder.applyPin(cur, "a", pinned = true)    // 再固定 a
        assertEquals("重新固定 = 新的一次固定 ⇒ 排在末尾", listOf("b", "c", "a"), cur)
    }
}

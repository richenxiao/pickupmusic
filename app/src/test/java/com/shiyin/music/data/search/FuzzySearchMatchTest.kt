package com.shiyin.music.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 搜索相关性（A43）——第二轮（用户纠偏后）。
 *
 * 第一轮（65e4c08）把「2 字中文查询」的容错砍掉了（maxEdits=0），用户反馈
 * 「搜索太严格了，我是允许模糊搜索的，只是反对标注太散开」。第二轮改为：
 * 保留模糊容错，高亮收窄到真正相等的字（LCS 对齐），排序让精确命中压过弱命中累加。
 *
 * 本类只保留「不随需求变」的基础行为（英文容错、繁简互通、精确子串）；
 * 具体的高亮/排序/容错用例见 FuzzySearchRound2Test。
 */
class FuzzySearchMatchTest {

    // ── 基础行为（不随需求变） ───────────────────────────────────────────

    @Test
    fun `单字查询不容错`() {
        assertEquals(0, FuzzySearch.maxEdits("天"))
        assertEquals(0, FuzzySearch.maxEdits("a"))
    }

    @Test
    fun `英文长短词容错阈值`() {
        assertEquals(1, FuzzySearch.maxEdits("abcd"))
        assertEquals(2, FuzzySearch.maxEdits("newjeans"))
    }

    @Test
    fun `两字中文恢复容错`() {
        // 用户明确允许模糊搜索
        assertEquals(1, FuzzySearch.maxEdits("那天"))
        assertEquals(1, FuzzySearch.maxEdits("伤心"))
    }

    @Test
    fun `三字以上中文保留容错_用于兜住繁简与错字`() {
        assertEquals(1, FuzzySearch.maxEdits("周杰伦"))
        assertEquals(1, FuzzySearch.maxEdits("张雪友"))
    }

    @Test
    fun `搜_那天_能模糊命中_晴天娃娃`() {
        assertNotNull(FuzzySearch.bestWindow("晴天娃娃", "那天", 0))
    }

    @Test
    fun `搜_那天_能模糊命中_社会天使轮`() {
        assertNotNull(FuzzySearch.bestWindow("社会天使轮", "那天", 0))
    }

    @Test
    fun `搜_那天_能模糊命中_天天`() {
        assertNotNull(FuzzySearch.bestWindow("天天", "那天", 0))
    }

    @Test
    fun `搜_那天_仍然命中_那天`() {
        assertNotNull(FuzzySearch.bestWindow("那天", "那天", 0))
    }

    @Test
    fun `英文拼写错误仍然命中`() {
        assertNotNull(FuzzySearch.bestWindow("newjeans", "newjeasn", 0))
    }

    @Test
    fun `繁体中文经归一化后仍能精确命中`() {
        assertNotNull(FuzzySearch.bestWindow(FuzzySearch.norm("後來的我們").text, "后来的我们", 0))
    }

    // ── 高亮区间 ────────────────────────────────────────────────────────

    @Test
    fun `模糊命中给收窄的高亮区间`() {
        val nf = FuzzySearch.norm("NewJeans")
        val m = FuzzySearch.matchTokenInField("newjeasn", nf)
        assertNotNull(m)
        // 模糊命中现在给收窄后的高亮（LCS 对齐），不再是空
        assertTrue("模糊命中应产出收窄的高亮区间", m!!.ranges.isNotEmpty())
    }

    @Test
    fun `精确子串命中照常给高亮区间`() {
        val nf = FuzzySearch.norm("NewJeans")
        val m = FuzzySearch.matchTokenInField("newjeans", nf)
        assertNotNull(m)
        assertEquals(1, m!!.ranges.size)
    }

    @Test
    fun `中文模糊命中不再被拦掉`() {
        assertNotNull(FuzzySearch.matchTokenInField("那天", FuzzySearch.norm("晴天娃娃")))
    }
}

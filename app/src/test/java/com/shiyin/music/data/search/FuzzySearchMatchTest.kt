package com.shiyin.music.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 搜索相关性（A43）。用例直接来自用户真机反馈：
 * 搜【那天】会搜出【天天】【社会天使轮】【晴天娃娃】，且把「晴天」整段标亮。
 *
 * 修复前这些用例应当是红的：maxEdits("那天")==1（允许换一个字）→ 三个无关标题都会
 * 命中；而模糊命中会返回整段高亮区间。
 */
class FuzzySearchMatchTest {

    // ── 问题 A：无关歌曲混进结果 ────────────────────────────────────────

    @Test
    fun `两字及以下的全中文查询不做容错`() {
        assertEquals(0, FuzzySearch.maxEdits("那天"))
        assertEquals(0, FuzzySearch.maxEdits("天"))
    }

    @Test
    fun `三字以上中文仍保留容错_用于兜住繁简与错字`() {
        // 手写繁简表不含「倫」，「周杰伦 ↔ 周杰倫」至今依赖 1 编辑容错
        assertEquals(1, FuzzySearch.maxEdits("周杰伦"))
        assertEquals(1, FuzzySearch.maxEdits("张雪友"))
    }

    @Test
    fun `英文长词仍然容错_短词从严`() {
        assertEquals(0, FuzzySearch.maxEdits("a"))
        assertEquals(1, FuzzySearch.maxEdits("abcd"))
        assertEquals(2, FuzzySearch.maxEdits("newjeans"))
    }

    @Test
    fun `搜_那天_不再命中_晴天娃娃`() {
        assertNull(FuzzySearch.bestWindow("晴天娃娃", "那天", 0))
    }

    @Test
    fun `搜_那天_不再命中_社会天使轮`() {
        assertNull(FuzzySearch.bestWindow("社会天使轮", "那天", 0))
    }

    @Test
    fun `搜_那天_不再命中_天天`() {
        assertNull(FuzzySearch.bestWindow("天天", "那天", 0))
    }

    @Test
    fun `搜_那天_仍然命中_那天`() {
        assertNotNull(FuzzySearch.bestWindow("那天", "那天", 0))
    }

    @Test
    fun `英文拼写错误仍然命中`() {
        // 注意：bestWindow 收到的是**已归一化**的字段串（小写），大小写差异在 norm() 里消掉。
        assertNotNull(FuzzySearch.bestWindow("newjeans", "newjeasn", 0))
    }

    @Test
    fun `繁体中文经归一化后仍能精确命中`() {
        assertNotNull(FuzzySearch.bestWindow(FuzzySearch.norm("後來的我們").text, "后来的我们", 0))
    }

    // ── 问题 B：标亮错位 ────────────────────────────────────────────────

    @Test
    fun `模糊命中不给高亮区间`() {
        val nf = FuzzySearch.norm("NewJeans")
        val m = FuzzySearch.matchTokenInField("newjeasn", nf)
        assertNotNull(m)
        assertTrue("模糊命中不应产出高亮区间", m!!.ranges.isEmpty())
    }

    @Test
    fun `精确子串命中照常给高亮区间`() {
        val nf = FuzzySearch.norm("NewJeans")
        val m = FuzzySearch.matchTokenInField("newjeans", nf)
        assertNotNull(m)
        assertEquals(1, m!!.ranges.size)
    }

    @Test
    fun `中文模糊命中已被彻底拦掉`() {
        assertNull(FuzzySearch.matchTokenInField("那天", FuzzySearch.norm("晴天娃娃")))
    }
}
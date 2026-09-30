package com.shiyin.music.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.12:「拾光记忆」页面的展示映射回归测试。
 *
 * 钉的是**信息架构**(不是像素):分组正确、空分组隐藏、中文标签、日期格式。
 * 这些是规格里明确写死的、又是最容易在改 UI 时被悄悄改坏的部分。
 */
class MemoryPresentationTest {

    private fun m(id: String, type: String, content: String, updatedAt: Long = 0L) = Memory(
        id = id, type = type, content = content,
        createdAt = updatedAt, updatedAt = updatedAt, importance = 2,
        source = MemorySource.INFERRED, tags = emptyList(),
    )

    // ── 分组 ───────────────────────────────────────────────────────────────

    @Test
    fun `分组固定为 YOU 与 TOPICS 两组`() {
        assertEquals(listOf("YOU", "TOPICS"), MemoryPresentation.SECTIONS.map { it.title })
    }

    @Test
    fun `五个现有类型被分组完整覆盖且不重复`() {
        val grouped = MemoryPresentation.SECTIONS.flatMap { it.types }
        assertEquals("每个类型都必须有归属,不能漏", MemoryType.ALL.toSet(), grouped.toSet())
        assertEquals("不能有类型被放进两个分组", grouped.size, grouped.toSet().size)
    }

    @Test
    fun `个人信息与偏好归 YOU - 音乐关系经历归 TOPICS`() {
        val you = MemoryPresentation.SECTIONS.first { it.title == "YOU" }.types
        val topic = MemoryPresentation.SECTIONS.first { it.title == "TOPICS" }.types
        assertTrue(MemoryType.PERSONAL in you)
        assertTrue(MemoryType.PREFERENCE in you)
        assertTrue(MemoryType.MUSIC in topic)
        assertTrue(MemoryType.RELATIONSHIP in topic)
        assertTrue(MemoryType.EPISODIC in topic)
    }

    @Test
    fun `空分组直接不出现 - 不显示暂无记忆`() {
        val only = listOf(m("1", MemoryType.MUSIC, "用户喜欢《Pretender》"))
        val g = MemoryPresentation.group(only)
        assertEquals("只有音乐有内容时,只应有一个分组", 1, g.size)
        assertEquals("TOPICS", g.single().first.title)
        assertTrue("「YOU」下没有内容,不该出现", g.none { it.first.title == "YOU" })
    }

    @Test
    fun `组内按最近更新倒序`() {
        val list = listOf(
            m("old", MemoryType.MUSIC, "旧的", updatedAt = 100),
            m("new", MemoryType.MUSIC, "新的", updatedAt = 300),
            m("mid", MemoryType.MUSIC, "中间的", updatedAt = 200),
        )
        val rows = MemoryPresentation.group(list).single().second
        assertEquals(listOf("新的", "中间的", "旧的"), rows.map { it.content })
    }

    @Test
    fun `没有任何记忆时没有分组`() {
        assertTrue(MemoryPresentation.group(emptyList()).isEmpty())
    }

    // ── 中文标签:绝不暴露数据库取值 ───────────────────────────────────────

    @Test
    fun `类型标签是中文`() {
        assertEquals("个人信息", MemoryPresentation.typeLabel(MemoryType.PERSONAL))
        assertEquals("偏好", MemoryPresentation.typeLabel(MemoryType.PREFERENCE))
        assertEquals("音乐", MemoryPresentation.typeLabel(MemoryType.MUSIC))
        assertEquals("关系", MemoryPresentation.typeLabel(MemoryType.RELATIONSHIP))
        assertEquals("经历", MemoryPresentation.typeLabel(MemoryType.EPISODIC))
    }

    @Test
    fun `来源标签是用户能懂的说法`() {
        assertEquals("你告诉我的", MemoryPresentation.sourceLabel(MemorySource.EXPLICIT))
        assertEquals("你自己添加的", MemoryPresentation.sourceLabel(MemorySource.MANUAL))
        assertEquals("拾光自己记下的", MemoryPresentation.sourceLabel(MemorySource.INFERRED))
    }

    @Test
    fun `所有类型的标签都不含库内英文取值`() {
        for (t in MemoryType.ALL + listOf("banana")) {
            val label = MemoryPresentation.typeLabel(t)
            assertFalse("标签不该等于库内取值:$t", label == t)
            assertFalse("标签不该含英文:$label", label.any { it.code in 'a'.code..'z'.code })
        }
        for (s in listOf(MemorySource.EXPLICIT, MemorySource.MANUAL, MemorySource.INFERRED)) {
            assertFalse("来源标签不该等于库内取值:$s", MemoryPresentation.sourceLabel(s) == s)
        }
    }

    // ── 日期格式 ───────────────────────────────────────────────────────────

    @Test
    fun `列表日期是 yyyy-MM-dd 形态`() {
        val s = MemoryPresentation.formatDate(1_700_000_000_000L)
        assertTrue("实际:$s", Regex("""\d{4}/\d{2}/\d{2}""").matches(s))
    }

    @Test
    fun `详情日期是中文年月日形态`() {
        val s = MemoryPresentation.formatDateLong(1_700_000_000_000L)
        assertTrue("实际:$s", Regex("""\d{4}年\d{1,2}月\d{1,2}日""").matches(s))
    }
}

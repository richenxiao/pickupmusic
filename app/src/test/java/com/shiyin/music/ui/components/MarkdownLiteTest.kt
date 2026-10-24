package com.shiyin.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全 App 唯一 markdown 出口（[MarkdownLite]）的解析测试。
 *
 * 渲染（Compose）测不了，但解析是纯函数 —— 而"用户看到记号原样露出"这类返工
 * （更新弹层、提示弹窗、拾光回复各犯过一次）错的全是解析这一层。
 * 这里把三类内容源的契约钉住：
 *  · GitHub Release 正文（`##` / `**分组**` / `- 条目` / `---`）；
 *  · 提示弹窗文案（行内 `**加粗**`）；
 *  · 拾光回复（打字机**流式吐字** —— 未闭合记号必须原样保留，不能"吃一半"）。
 */
class MarkdownLiteTest {

    // ── 块级 ──────────────────────────────────────────────────────────────

    @Test
    fun `release 正文解析成 小节_分组_条目`() {
        val blocks = MarkdownLite.parse(
            """
            ## ✨ 新增功能

            **伴唱（BETA）**
            - 把人声与伴奏分开，三档切换：人声 / 原曲 / 伴奏
            - 模型可下载、可本地导入、可删除

            ---

            ## 🐛 问题修复
            """.trimIndent(),
        )
        val kinds = blocks.map { it::class.simpleName }
        assertEquals(listOf("Section", "Group", "Bullet", "Bullet", "Section"), kinds)
        val section = blocks[0] as MarkdownLite.Block.Section
        assertEquals("✨ 新增功能", section.title)
        val group = blocks[1] as MarkdownLite.Block.Group
        assertEquals("伴唱（BETA）", group.title)
    }

    @Test
    fun `代码围栏里的内容不参与块级解析`() {
        val blocks = MarkdownLite.parse("```\n## 这不是标题\n```")
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `编号条目当条目处理`() {
        val blocks = MarkdownLite.parse("1. 第一步\n2. 第二步")
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is MarkdownLite.Block.Bullet })
    }

    @Test
    fun `空行与分隔线不产生块`() {
        val blocks = MarkdownLite.parse("a\n\n---\n***\nb")
        assertEquals(2, blocks.size)
    }

    // ── 行内 ──────────────────────────────────────────────────────────────

    @Test
    fun `粗体与等宽`() {
        val spans = MarkdownLite.spans("普通**加粗**普通`代码`普通")
        assertEquals(
            listOf(
                MarkdownLite.Span("普通"),
                MarkdownLite.Span("加粗", bold = true),
                MarkdownLite.Span("普通"),
                MarkdownLite.Span("代码", mono = true),
                MarkdownLite.Span("普通"),
            ),
            spans,
        )
    }

    @Test
    fun `斜体_且不与粗体记号混淆`() {
        val spans = MarkdownLite.spans("前*斜*后")
        assertEquals(
            listOf(MarkdownLite.Span("前"), MarkdownLite.Span("斜", italic = true), MarkdownLite.Span("后")),
            spans,
        )
        // **粗体** 优先：不会把第一个 * 误判成斜体开头
        val bold = MarkdownLite.spans("**粗**")
        assertEquals(listOf(MarkdownLite.Span("粗", bold = true)), bold)
    }

    @Test
    fun `流式吐字中未闭合的记号保持原样`() {
        // 打字机正吐到 **bol ——此刻显示的必须是字面 **bol，而不是把记号吃掉
        // （否则下一毫秒记号闭合时整段文字"集体变形"，闪烁难看）
        assertEquals(listOf(MarkdownLite.Span("**bol")), MarkdownLite.spans("**bol"))
        assertEquals(listOf(MarkdownLite.Span("文本*")), MarkdownLite.spans("文本*"))
        assertEquals(listOf(MarkdownLite.Span("代码`")), MarkdownLite.spans("代码`"))
    }

    @Test
    fun `星号后跟空格不是斜体开头`() {
        // "前面 * 后面" 这种散文里的孤立星号必须原样
        assertEquals(listOf(MarkdownLite.Span("前面 * 后面")), MarkdownLite.spans("前面 * 后面"))
    }

    @Test
    fun `链接只留文字`() {
        val spans = MarkdownLite.spans("看[文档](https://example.com)即可")
        assertEquals(
            listOf(MarkdownLite.Span("看"), MarkdownLite.Span("文档"), MarkdownLite.Span("即可")),
            spans,
        )
    }

    @Test
    fun `单独的星号行是条目不是斜体`() {
        // "* 内容"是条目；行内解析收到的是已经剥掉前缀的正文
        val blocks = MarkdownLite.parse("* 星号条目")
        assertTrue(blocks[0] is MarkdownLite.Block.Bullet)
        assertEquals("星号条目", (blocks[0] as MarkdownLite.Block.Bullet).text)
    }
}

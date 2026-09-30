package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.13:专辑曲序**协议层**的回归测试。
 *
 * 真机反馈:「把《龙年》排成正确顺序」两次都失败 —— 一次模型回 UNKNOWN,一次"没按格式输出"。
 *
 * 现在的分工(**判断权在模型**):
 *  · **模型**:判断"官方这首 = 本地哪一首"(官方《花》在本地是 hana、官方 Live 版本地没有
 *    —— 这种只有它能对上),并**照抄本地列表里的写法**按官方顺序输出;
 *  · **代码**:回填下标 + 兜底(它写法有出入时的机械差异)。
 *
 * 所以这组测试钉两件事:
 *  ① 回填正确(模型给的顺序 = 最终的排序结果);
 *  ② 兜底网的边界 —— 机械差异能对上,但**语义差异对不上**(那不是代码的活)。
 */
class AlbumSortProtocolTest {

    // ── ① 解析:模型给的曲目名行 ──────────────────────────────────────────

    @Test
    fun `带编号的曲目名行会被剥掉编号`() {
        val lines = AgentService.parseTitleLines(
            """
            1. 第一首
            2、第二首
            (3) 第三首
            - 第四首
            """.trimIndent(),
        )
        assertEquals(listOf("第一首", "第二首", "第三首", "第四首"), lines)
    }

    @Test
    fun `markdown 强调与引号会被剥掉 - UNKNOWN 与空行丢弃`() {
        val lines = AgentService.parseTitleLines(
            """
            **曲名一**
            "曲名二"
            UNKNOWN

            ```曲名三```
            """.trimIndent(),
        )
        assertEquals(listOf("曲名一", "曲名二", "曲名三"), lines)
    }

    @Test
    fun `空内容不产出任何曲目名`() {
        assertTrue(AgentService.parseTitleLines(null).isEmpty())
        assertTrue(AgentService.parseTitleLines("").isEmpty())
        assertTrue(AgentService.parseTitleLines("   \n  ").isEmpty())
    }

    // ── ② 映射:官方曲序 → 本地下标 ───────────────────────────────────────

    @Test
    fun `本地乱序时按官方顺序给出下标`() {
        val local = listOf("第三首", "第一首", "第二首")
        val official = listOf("第一首", "第二首", "第三首")
        assertEquals(listOf(1, 2, 0), AgentService.officialToLocalIndices(official, local))
    }

    @Test
    fun `官方写法与本地不同也能对上 - 副标题与繁简全半角`() {
        val local = listOf("Pretender", "晴天", "夜に駆ける")
        val official = listOf("晴天 (Live)", "pretender")
        // 晴天 (Live) → 本地《晴天》(剥副标题后全等);pretender → 本地《Pretender》(大小写归一)
        assertEquals(listOf(1, 0), AgentService.officialToLocalIndices(official, local))
    }

    @Test
    fun `官方有而本地没有的曲目被跳过 - 缺的就不要`() {
        val local = listOf("第一首", "第二首")
        val official = listOf("第一首", "本地没有的", "第二首")
        assertEquals(listOf(0, 1), AgentService.officialToLocalIndices(official, local))
    }

    @Test
    fun `每条本地曲目只用一次 - 官方重复列出不会把它算两遍`() {
        val local = listOf("第一首", "第二首")
        val official = listOf("第一首", "第一首", "第二首")
        assertEquals(listOf(0, 1), AgentService.officialToLocalIndices(official, local))
    }

    @Test
    fun `单字曲名不会被塞进不相干的长标题`() {
        val local = listOf("夜に駆ける", "恋愛写真")
        // 「夜」「恋」都是单字:包含判定会误命中长标题,所以单字不走模糊档 —— 宁可少排,不要错排
        assertEquals(emptyList<Int>(), AgentService.officialToLocalIndices(listOf("夜", "恋"), local))
    }

    @Test
    fun `官方列表为空或本地为空时返回空`() {
        assertTrue(AgentService.officialToLocalIndices(emptyList(), listOf("甲")).isEmpty())
        assertTrue(AgentService.officialToLocalIndices(listOf("甲"), emptyList()).isEmpty())
    }

    @Test
    fun `语义差异不是代码的活 - 官方名与本地异名必须由模型对上`() {
        // 这条**不是**在断言"应该能匹配",而是在钉边界:代码做不到、也不该去做。
        // 官方《花》在本地文件名是 hana、官方是 Live 版而本地没有 —— 只有模型能判断同一首。
        // 所以协议要求模型**照抄本地写法**输出;代码只做回填,拿到官方写法本来就认不出。
        val local = listOf("hana", "Pretender")
        assertEquals(
            "代码对不上「花→hana」:只应认出 Pretender,不能把 花 硬塞给某首",
            listOf(1),
            AgentService.officialToLocalIndices(listOf("花", "Pretender"), local),
        )
        // 而模型照抄本地写法时,回填是可靠的
        assertEquals(
            listOf(0, 1),
            AgentService.officialToLocalIndices(listOf("hana", "Pretender"), local),
        )
    }

    // ── ③ 端到端形状:模型给一段官方曲序文本 ──────────────────────────────

    @Test
    fun `模型输出的官方曲序文本能直接映射成重排下标`() {
        val local = listOf("B面第一首", "A面第一首", "A面第二首")
        val modelReply = """
            思考完了。
            1. A面第一首
            2. A面第二首
            3. B面第一首
        """.trimIndent()
        val ordered = AgentService.officialToLocalIndices(AgentService.parseTitleLines(modelReply), local)
        assertEquals(listOf(1, 2, 0), ordered)
    }
}

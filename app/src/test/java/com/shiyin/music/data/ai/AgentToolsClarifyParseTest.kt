package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0:「澄清卡片」参数解析的回归。
 *
 * 为什么要单独测它:这段解析在 `runCatching` **之外**,一旦对某个 JSON 形状抛异常,
 * 整轮分发就崩成「Agent 执行出错」——用户看到的是报错而不是本该出现的选择题。
 * 而 Gson 的 `getAsJsonArray(name)` 内部是强转(`(JsonArray) members.get(name)`),
 * **成员是 JsonNull 时会抛 ClassCastException**;同文件的 choices/usage/tool_calls
 * 都写过这条防御,这里是把漏掉的一处补齐。
 */
class AgentToolsClarifyParseTest {

    private fun parse(json: String) = AgentTools.parseClarify(json)

    @Test
    fun `options 为 null 时不抛异常 - 退回通用的问句`() {
        // 真机可能遇到:`{"question":"放哪张?","options":null}`
        val r = parse("""{"question":"放哪张?","options":null}""")
        assertEquals("放哪张?", r.question)
        assertTrue("options 为空应是空列表而不是崩溃", r.options.isEmpty())
    }

    @Test
    fun `缺少 options 字段同样安全`() {
        val r = parse("""{"question":"你想听什么?"}""")
        assertEquals("你想听什么?", r.question)
        assertTrue(r.options.isEmpty())
    }

    @Test
    fun `question 缺失或非字符串时给默认问句`() {
        assertEquals("你想让我做什么?", parse("""{"options":["A"]}""").question)
        assertEquals("你想让我做什么?", parse("""{"question":null}""").question)
        assertEquals("你想让我做什么?", parse("""{"question":"   "}""").question)
    }

    @Test
    fun `选项去重且不超过 5 个 - 与推荐卡可点条数一致`() {
        val json = """{"question":"q","options":["A","A","B","C","D","E","F"]}"""
        val r = parse(json)
        assertEquals("去重后取前 5", listOf("A", "B", "C", "D", "E"), r.options)
    }

    @Test
    fun `非字符串与空白选项被丢弃`() {
        val r = parse("""{"question":"q","options":["A",null,"  ",123,{"k":1},"B"]}""")
        assertEquals(listOf("A", "123", "B"), r.options)
    }

    @Test
    fun `输入完全不是 JSON 时也不抛`() {
        val r = parse("模型说了一堆话,没有 JSON")
        assertEquals("你想让我做什么?", r.question)
        assertTrue(r.options.isEmpty())
    }
}

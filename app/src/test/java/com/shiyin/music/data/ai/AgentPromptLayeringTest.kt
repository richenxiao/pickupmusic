package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.11:Prompt 分层的回归锁。
 *
 * 背景(用户定的结构):
 *   Persona → 行为准则 → [当前上下文] → [关于这位用户,你记得的事] → 历史/用户消息
 *
 * 记忆**不是**领域上下文的两件事不能混在一个块里——旧实现把记忆拼进 `contextBlock`,
 * 整块又贴着「[当前上下文]」的标签,于是准则里"上下文跟用户问的对不上就当它不存在"
 * 会连带指导模型忽略记忆。这里把"分开"钉成断言。
 */
class AgentPromptLayeringTest {

    private val domain = "当前专辑: 魔杰座\n当前歌手: 周杰伦"
    private val memory = "[关于这位用户,你记得的事]\n· 用户叫 Richen"

    @Test
    fun `记忆块与当前上下文是分开的两段`() {
        val out = AgentTools.dispatchSystemPrompt(domain, memory)
        // ⚠️ 不能用 indexOf 找标题:行为准则 7/9 的**正文里也写着**"[当前上下文]"与
        // "[关于这位用户,你记得的事]"(那是在告诉模型这两块怎么用)。必须找"整行就是标题"
        // 的那一处,否则测的是规则文案、不是分段结构。
        val ctxAt = headerLineAt(out, "[当前上下文]")
        val memAt = headerLineAt(out, "[关于这位用户,你记得的事]")

        assertTrue("应有 [当前上下文] 段", ctxAt >= 0)
        assertTrue("应有记忆段", memAt >= 0)
        assertTrue("记忆段必须在当前上下文之后", memAt > ctxAt)
        // 用专辑名定位(它只在领域上下文里出现,规则正文里没有),避开规则文本的干扰
        assertTrue("领域上下文应在记忆段之前", out.indexOf("魔杰座") in 0 until memAt)
        assertFalse(
            "记忆内容不能出现在 [当前上下文] 段里",
            out.substring(ctxAt, memAt).contains("Richen"),
        )
    }

    /** 找"整行就是该标题"的下标(规则正文里会提到标题,不能用 indexOf)。找不到 = -1。 */
    private fun headerLineAt(out: String, header: String): Int {
        var idx = 0
        for (line in out.lines()) {
            if (line.trim() == header) return idx
            idx += line.length + 1
        }
        return -1
    }

    @Test
    fun `没有记忆时不出现记忆段`() {
        val out = AgentTools.dispatchSystemPrompt(domain, "")
        // 判"有没有记忆段"要看有没有这一行**标题**(规则 9 正文里也写着块名)
        assertEquals("不该出现记忆段", -1, headerLineAt(out, "[关于这位用户,你记得的事]"))
        assertTrue("领域上下文照常", headerLineAt(out, "[当前上下文]") >= 0)
    }

    @Test
    fun `没有领域上下文时记忆段仍然出现`() {
        val out = AgentTools.dispatchSystemPrompt("", memory)
        assertEquals("没有领域上下文时不该凭空造出这一段", -1, headerLineAt(out, "[当前上下文]"))
        assertTrue("记忆段应照常出现", headerLineAt(out, "[关于这位用户,你记得的事]") >= 0)
    }

    @Test
    fun `Persona 仍然是 system prompt 的第一段`() {
        val out = AgentTools.dispatchSystemPrompt(domain, memory)
        assertTrue("必须以 persona 开头,分层不得改变人设位置", out.startsWith(AgentEngine.persona))
    }

    @Test
    fun `fallback route 的用户消息里也带记忆块`() {
        val sections = "$domain\n$memory"
        val msg = AgentEngine.routeUserMessage(
            intent = "我叫什么",
            skills = "  - album_sort: 排序",
            historyBlock = "",
            webContext = "",
            sections = sections,
        )
        assertTrue("回退链路必须同样拿到记忆", msg.contains("用户叫 Richen"))
        assertTrue(msg.contains("[关于这位用户,你记得的事]"))
        assertTrue(msg.contains("我叫什么"))
    }

    @Test
    fun `没有记忆时 route 用户消息不出现记忆段`() {
        val msg = AgentEngine.routeUserMessage(
            intent = "你好",
            skills = "  - library_query: 搜库",
            historyBlock = "",
            webContext = "",
            sections = "[当前上下文]\n$domain",
        )
        assertTrue(msg.contains("[当前上下文]"))
        assertFalse(msg.contains("[关于这位用户"))
        assertTrue(msg.contains("你好"))
    }

    // ── v1.3.11 第二阶段:模型对「记忆能力」的错误认知 ─────────────────────
    // 真机实测:模型在思考里写「The user is asking me to remember something, but I don't
    // have a memory tool available in my function list...」——它把"工具列表里没有记忆工具"
    // 读成了"这个产品没有长期记忆"。这是错误的认知,必须由 prompt 明确纠正:
    // 记忆是**系统能力**,由 runtime 在回复前完成检索与保存,不经工具。

    @Test
    fun `主链路明确声明记忆是系统能力而非工具`() {
        val sys = AgentTools.dispatchSystemPrompt(domain, memory)
        assertTrue(
            "必须正面说明:记忆是系统能力、不通过工具",
            sys.contains("记忆是系统能力,不是 Agent 工具"),
        )
        assertTrue(
            "必须堵死「工具列表里没有记忆工具 ⇒ 我没有长期记忆」这条错误推断",
            sys.contains("绝不要因为工具列表里没有记忆相关的工具"),
        )
        assertTrue(
            "没有提供记忆时不得虚构用户过去的信息",
            sys.contains("不得凭空"),
        )
    }

    @Test
    fun `回退链路同样声明记忆是系统能力`() {
        val msg = AgentEngine.routeUserMessage(
            intent = "记住我叫御晨晓",
            skills = "  - album_sort: 排序",
            historyBlock = "",
            webContext = "",
            sections = memory,
        )
        assertTrue(
            "回退链路也必须声明,否则同一个人在主/回退链路两副认知",
            msg.contains("记忆是系统能力"),
        )
    }
}

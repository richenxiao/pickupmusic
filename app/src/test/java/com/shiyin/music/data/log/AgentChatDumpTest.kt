package com.shiyin.music.data.log

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.15:「导出对话」的纯构造层测试。
 *
 * 为什么要测:导出的 JSON 是**给人（以及给诊断者）看的唯一一份带内容的现场**。它一旦
 * 转义写错、或漏掉思考链/工具调用,排查就只能靠猜;而它又完全由纯函数拼出来,可以脱机钉住。
 */
class AgentChatDumpTest {

    private fun msg(
        role: String = "user",
        text: String = "推荐方大同适合睡觉的歌",
        thinking: String? = null,
        steps: List<String> = emptyList(),
        tokens: Pair<Int, Int>? = null,
    ) = AgentChatDump.Msg(role, text, thinking, steps, tokens)

    private val meta = AgentChatDump.Meta(
        appVersion = "1.3.0", device = "OnePlus PKG110", android = "36",
        provider = "DeepSeek", model = "deepseek-chat", skills = listOf("discover_music", "album_sort"),
        diagEnabled = true,
    )

    @Test
    fun `导出的必须是合法 JSON - 文本里的引号换行反斜杠都要转义`() {
        val nasty = "他说:\"来首\\《X》\"\n第二行\t制表符"
        val out = AgentChatDump.build(listOf(msg(text = nasty)), emptyList(), emptyList(), meta, 1_700_000_000_000L)
        // 真解析一遍:转义错一个字符这里就抛
        val root = JsonParser.parseString(out).asJsonObject
        assertEquals(nasty, root.getAsJsonArray("messages")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun `思考链与步骤都要落进导出 - 否则查不到模型怎么想的`() {
        val out = AgentChatDump.build(
            listOf(msg(role = "agent", text = "挑好了", thinking = "先解析歌手:方大同", steps = listOf("resolve:DONE"), tokens = 120 to 45)),
            emptyList(), emptyList(), meta, 1L,
        )
        val m = JsonParser.parseString(out).asJsonObject.getAsJsonArray("messages")[0].asJsonObject
        assertEquals("先解析歌手:方大同", m.get("thinking").asString)
        assertEquals("resolve:DONE", m.getAsJsonArray("steps")[0].asString)
        assertEquals(120, m.getAsJsonObject("tokens").get("prompt").asInt)
        assertEquals(45, m.getAsJsonObject("tokens").get("completion").asInt)
    }

    @Test
    fun `工具调用与诊断样本都带上`() {
        val sample = AgentDiag.Sample(
            tsMs = 5L, skill = "discover_music", stage = "pick_parse", ok = false,
            counts = mapOf("candidates" to 30), replyHead = "{\"fit\":\"maybe\"}",
            errorCode = "BAD_FIT", errorDetail = null, webHead = "Tracklist: 1. A 2. B",
        )
        val out = AgentChatDump.build(
            listOf(msg()), listOf("call discover_music {artist=方大同}"), listOf(sample), meta, 1L,
        )
        val root = JsonParser.parseString(out).asJsonObject
        assertTrue(root.getAsJsonArray("toolTrail")[0].asString.contains("discover_music"))
        val s = root.getAsJsonArray("diagSamples")[0].asJsonObject
        assertEquals("BAD_FIT", s.get("errorCode").asString)
        assertEquals("{\"fit\":\"maybe\"}", s.get("replyHead").asString)
        assertEquals("联网资料必须一起导出(定性'联网 vs 模型'靠它)", "Tracklist: 1. A 2. B", s.get("webHead").asString)
        assertEquals(30, s.getAsJsonObject("counts").get("candidates").asInt)
    }

    @Test
    fun `密钥形态在导出前被脱敏`() {
        val out = AgentChatDump.build(
            listOf(msg(text = "我的 key 是 sk-abcdefghijklmnopqrstuvwxyz012345")),
            listOf("call x {apiKey=sk-abcdefghijklmnopqrstuvwxyz012345}"),
            emptyList(), meta, 1L,
        )
        assertTrue("不该原样带出密钥", !out.contains("sk-abcdefghijklmnopqrstuvwxyz012345"))
        assertTrue(out.contains("[已脱敏]"))
    }

    @Test
    fun `元信息带上供应商与模型名 - 不含任何密钥字段`() {
        val out = AgentChatDump.build(emptyList(), emptyList(), emptyList(), meta, 1L)
        val m = JsonParser.parseString(out).asJsonObject.getAsJsonObject("meta")
        assertEquals("DeepSeek", m.get("provider").asString)
        assertEquals("deepseek-chat", m.get("model").asString)
        assertEquals("1.3.0", m.get("appVersion").asString)
        assertTrue("不该有 apiKey 这类字段", !m.has("apiKey") && !out.contains("apiKey"))
    }
}

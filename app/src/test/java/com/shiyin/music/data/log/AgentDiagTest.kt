package com.shiyin.music.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.14:诊断样本的**纯编解码与脱敏**测试。
 *
 * 为什么必须测:诊断样本存在的唯一目的是"出了问题能看清",如果编解码自己会丢字段、
 * 或者脱敏漏掉 Key,那它就不是诊断工具而是新的噪音源(甚至泄密)。
 * 这些函数刻意不依赖 Android,所以能脱机跑。
 */
class AgentDiagTest {

    private fun sample(
        reply: String? = null,
        counts: Map<String, Int> = mapOf("candidates" to 5, "valid" to 2),
        skill: String = "album_sort",
        stage: String = "parse",
        ok: Boolean = false,
    ) = AgentDiag.Sample(
        tsMs = 1_700_000_000_000L,
        skill = skill, stage = stage, ok = ok,
        counts = counts, replyHead = reply,
        errorCode = if (ok) null else "PARSE_FAIL",
        errorDetail = if (ok) null else "no ordered indices",
    )

    // ── 压平 ───────────────────────────────────────────────────────────────

    @Test
    fun `压平把分隔符与换行都换成空格`() {
        assertEquals("a b c", AgentDiag.flatten("a|b\nc"))
        assertEquals("a b", AgentDiag.flatten("  a\r\nb  "))
        assertEquals("", AgentDiag.flatten("\n\n"))
    }

    // ── 编解码往返 ─────────────────────────────────────────────────────────

    @Test
    fun `编码再解码能取回全部字段`() {
        val s = sample(reply = "1. 第一首\n2. 第二首", ok = false)
        val back = AgentDiag.decode(AgentDiag.encode(s))
        assertEquals(s.tsMs, back?.tsMs)
        assertEquals("album_sort", back?.skill)
        assertEquals("parse", back?.stage)
        assertEquals(false, back?.ok)
        assertEquals(mapOf("candidates" to 5, "valid" to 2), back?.counts)
        // 回复里的换行被压平成空格 —— 诊断只需一眼看清内容,不保留原格式
        assertEquals("1. 第一首 2. 第二首", back?.replyHead)
        assertEquals("PARSE_FAIL", back?.errorCode)
        assertEquals("no ordered indices", back?.errorDetail)
    }

    @Test
    fun `成功样本的可选字段解码为 null`() {
        val back = AgentDiag.decode(AgentDiag.encode(sample(ok = true, counts = emptyMap())))
        assertEquals(true, back?.ok)
        assertNull(back?.replyHead)
        assertNull(back?.errorCode)
        assertTrue(back!!.counts.isEmpty())
    }

    @Test
    fun `内容里的分隔符不会把一行拆坏`() {
        // 模型回复里出现 '|'(markdown 表格很常见)时必须仍是一行:分隔符被压平成空格
        val s = sample(reply = "| 序号 | 曲名 |\n| 1 | A |")
        val line = AgentDiag.encode(s)
        assertEquals("整行应恰好只有 7 个字段分隔符", 7, line.count { it == '|' })
        val back = AgentDiag.decode(line)
        assertEquals("序号 曲名 1 A", back?.replyHead)
    }

    @Test
    fun `坏行解码返回 null 不抛异常`() {
        assertNull(AgentDiag.decode(""))
        assertNull(AgentDiag.decode("只有一段没有分隔符"))
        assertNull(AgentDiag.decode("notanumber|1|a|b|c|d|e|f"))
    }

    // ── 脱敏 ───────────────────────────────────────────────────────────────

    @Test
    fun `密钥形态会被脱敏`() {
        for (raw in listOf(
            "Authorization: Bearer abcdefghijklmnop1234",
            "api_key=sk-abcdefghijklmnopqrst",
            "tvly-abcdefghijklmnop",
            "token: 0123456789abcdef0123456789abcdef",
        )) {
            val out = AgentDiag.sanitize(raw)
            assertTrue("「$raw」应被脱敏,实际:$out", out.contains("[已脱敏]"))
        }
    }

    @Test
    fun `普通文本不会被脱敏误伤`() {
        val raw = "用户想听方大同适合睡觉的歌,候选 3 首"
        assertEquals(raw, AgentDiag.sanitize(raw))
    }

    @Test
    fun `回复开头会被截断并脱敏`() {
        val long = "很长的回复".repeat(200)
        assertEquals(AgentDiag.REPLY_HEAD_MAX, AgentDiag.replyHeadOf(long)!!.length)
        assertEquals("[已脱敏]", AgentDiag.replyHeadOf("sk-abcdefghijklmnop"))
        assertNull(AgentDiag.replyHeadOf("   "))
        assertNull(AgentDiag.replyHeadOf(null))
    }
}

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
        // 模型回复里出现 '|'(markdown 表格很常见)时必须仍是一行:分隔符被压平成空格。
        // 断言方式:与"内容不含 '|' 的对照样本"比分隔符个数 —— 比写死字段数更准,
        // 以后再加字段也不会假红(v1.3.15 加第 9 段时就踩到过写死 7 的那种写法)。
        val control = AgentDiag.encode(sample(reply = "纯文本"))
        val line = AgentDiag.encode(sample(reply = "| 序号 | 曲名 |\n| 1 | A |"))
        assertEquals("含 '|' 的内容不该多出任何分隔符", control.count { it == '|' }, line.count { it == '|' })
        assertEquals("序号 曲名 1 A", AgentDiag.decode(line)?.replyHead)
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

    // ── 「复制全部」的文本 ─────────────────────────────────────────────────
    @Test
    fun `复制全部的文本要带全量与错因 - 不只是列表摘要`() {
        // 用户拿这段文本是要判断"模型没按格式回 / 解析丢了 / 候选池本来就空"三者中的哪一个,
        // 所以 counts / errorCode / errorDetail / 模型回复开头 一个都不能少。
        val text = AgentDiag.sampleText(
            sample(reply = """{"fit":"maybe"}"""),
            java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US),
        )
        assertTrue("阶段与成败:$text", text.contains("parse") && text.contains("FAIL"))
        assertTrue("量:$text", text.contains("candidates=5") && text.contains("valid=2"))
        assertTrue("错因码:$text", text.contains("PARSE_FAIL"))
        assertTrue("错因详情:$text", text.contains("no ordered indices"))
        assertTrue("模型回复开头:$text", text.contains("模型回复开头") && text.contains("maybe"))
    }

    @Test
    fun `成功样本的文本不写错因段`() {
        val text = AgentDiag.sampleText(
            sample(ok = true), java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US),
        )
        assertTrue(text.contains("OK"))
        assertTrue("没有错因就不该出现 error 段:$text", !text.contains("error:"))
    }

    // ── 联网资料字段(v1.3.15) ─────────────────────────────────────────────

    @Test
    fun `联网资料能存取 - 定性"联网还是模型"必须两者同框`() {
        val s = sample().copy(webHead = "Tracklist: 1. A 2. B")
        val round = AgentDiag.decode(AgentDiag.encode(s))
        assertEquals("联网资料必须原样存取", "Tracklist: 1. A 2. B", round?.webHead)
        assertEquals("其余字段不受影响", s.errorCode, round?.errorCode)
        assertEquals(s.replyHead, round?.replyHead)
    }

    @Test
    fun `老样本文件只有 8 段 - 仍要能读出来`() {
        // 升级前写下的行没有第 9 段:读了不能崩、也不能把整行判成坏行(否则历史样本全丢)
        val legacy = "1700000000000|0|album_sort|parse|ordered=0|{somejson}|PARSE_FAIL|没按格式"
        val s = AgentDiag.decode(legacy)
        assertEquals("album_sort", s?.skill)
        assertEquals("PARSE_FAIL", s?.errorCode)
        assertEquals("{somejson}", s?.replyHead)
        assertEquals("没有第 9 段 → 联网资料留空", null, s?.webHead)
    }

    @Test
    fun `联网资料也进「复制全部」的文本`() {
        val text = AgentDiag.sampleText(
            sample().copy(webHead = "Tracklist A B C"),
            java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US),
        )
        assertTrue("$text", text.contains("联网资料开头") && text.contains("Tracklist A B C"))
    }
}

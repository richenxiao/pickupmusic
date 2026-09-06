package com.shiyin.music.data.furigana

import com.shiyin.music.ui.components.RubySegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.x: LLM 兜底层单测——三道防线(位置白名单/合法假名/候选约束)各自的行为。
 * 纯逻辑,无 Android 依赖。
 */
class FuriganaLlmAssistTest {

    private fun gap(line: Int, start: Int, len: Int, surface: String, vararg candidates: String) =
        FuriganaLlmAssist.Gap(line, start, len, surface, candidates.toList())

    // ── collectGaps ────────────────────────────────────────────────────────

    @Test
    fun collectGaps_无注音汉字段被收集_已注音与纯假名段跳过() {
        val segs = listOf(
            listOf(
                RubySegment("明日", null, 0),          // 空缺 → 收
                RubySegment("も", null, 2),           // 纯假名 → 跳
                RubySegment("風", "かぜ", 3),          // 已注音 → 跳
            ),
            listOf(RubySegment("終わり", null, 0)),      // 空缺(带送假名) → 收
        )
        val gaps = FuriganaLlmAssist.collectGaps(segs) { emptyList() }
        assertEquals(2, gaps.size)
        assertEquals("明日", gaps[0].surface)
        assertEquals(0, gaps[0].lineIndex); assertEquals(0, gaps[0].charStart); assertEquals(2, gaps[0].length)
        assertEquals("終わり", gaps[1].surface); assertEquals(1, gaps[1].lineIndex)
    }

    @Test
    fun collectGaps_JMdict候选透传() {
        val segs = listOf(listOf(RubySegment("大地", null, 0)))
        val gaps = FuriganaLlmAssist.collectGaps(segs) { if (it == "大地") listOf("だいち", "おおち") else emptyList() }
        assertEquals(listOf("だいち", "おおち"), gaps[0].candidates)
    }

    // ── parseFixes 三道防线 ─────────────────────────────────────────────────

    private val gaps = listOf(
        gap(0, 0, 2, "明日", "あした", "みょうにち"),
        gap(1, 3, 2, "大地", "だいち", "おおち"),
        gap(2, 0, 3, "終わり"),
    )

    @Test
    fun parseFixes_合法返回全过() {
        val raw = """{"fixes":[{"line":0,"start":0,"len":2,"reading":"あした"},{"line":1,"start":3,"len":2,"reading":"おおち"},{"line":2,"start":0,"len":3,"reading":"おわり"}]}"""
        val fixes = FuriganaLlmAssist.parseFixes(raw, gaps)
        assertEquals(3, fixes.size)
        assertEquals("あした", fixes[0].reading)
        assertEquals("おわり", fixes[2].reading)
    }

    @Test
    fun parseFixes_位置白名单_编造位置丢弃() {
        // line=5 不在白名单;line=1 start=99 编造
        val raw = """{"fixes":[{"line":5,"start":0,"len":2,"reading":"あした"},{"line":1,"start":99,"len":2,"reading":"だいち"}]}"""
        val fixes = FuriganaLlmAssist.parseFixes(raw, gaps)
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun parseFixes_候选约束_有候选时答案必须在候选内() {
        // 大地 有候选,返回"だいちした"不在候选 → 丢;明日 返回候选内 みょうにち → 过
        val raw = """{"fixes":[{"line":0,"start":0,"len":2,"reading":"みょうにち"},{"line":1,"start":3,"len":2,"reading":"だいちした"}]}"""
        val fixes = FuriganaLlmAssist.parseFixes(raw, gaps)
        assertEquals(1, fixes.size)
        assertEquals("みょうにち", fixes[0].reading)
    }

    @Test
    fun parseFixes_无候选段_任何合法假名都可() {
        // 終わり 无候选(词典未收录),LLM 自由判断 → 合法假名即过
        val raw = """{"fixes":[{"line":2,"start":0,"len":3,"reading":"おわり"}]}"""
        val fixes = FuriganaLlmAssist.parseFixes(raw, gaps)
        assertEquals(1, fixes.size)
    }

    @Test
    fun parseFixes_非法假名过滤_罗马字星号片假名外字符全丢() {
        val raw = """{"fixes":[
            {"line":2,"start":0,"len":3,"reading":"owari"},
            {"line":2,"start":0,"len":3,"reading":"*"},
            {"line":2,"start":0,"len":3,"reading":"終"}]}
        """.trimIndent() + "}"
        val fixes = FuriganaLlmAssist.parseFixes(raw, gaps)
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun parseFixes_围栏容错_json外包裹文字仍可解析() {
        val raw = "好的,以下是结果:\n```json\n{\"fixes\":[{\"line\":0,\"start\":0,\"len\":2,\"reading\":\"あした\"}]}\n```"
        val fixes = FuriganaLlmAssist.parseFixes(raw, gaps)
        assertEquals(1, fixes.size)
    }

    @Test
    fun parseFixes_非JSON输入_空列表不抛() {
        assertTrue(FuriganaLlmAssist.parseFixes("我认为这首歌很棒", gaps).isEmpty())
        assertTrue(FuriganaLlmAssist.parseFixes("", gaps).isEmpty())
    }

    @Test
    fun parseFixes_重复位置_后到覆盖先到() {
        val raw = """{"fixes":[{"line":0,"start":0,"len":2,"reading":"あした"},{"line":0,"start":0,"len":2,"reading":"みょうにち"}]}"""
        val fixes = FuriganaLlmAssist.parseFixes(raw, gaps)
        assertEquals(1, fixes.size)  // 主键位置去重语义:evidence 表 REPLACE,同位置仅一行
        assertEquals("みょうにち", fixes[0].reading)
    }

    // ── buildPrompt ────────────────────────────────────────────────────────

    @Test
    fun buildPrompt_含行号歌词与候选清单() {
        val prompt = FuriganaLlmAssist.buildPrompt(
            listOf("明日も晴れる", "大地を揺らす"),
            listOf(gap(0, 0, 2, "明日", "あした", "みょうにち")),
        )
        assertTrue(prompt.contains("L0 明日も晴れる"))
        assertTrue(prompt.contains("L1 大地を揺らす"))
        assertTrue(prompt.contains("「明日」"))
        assertTrue(prompt.contains("あした|みょうにち"))
        assertTrue(prompt.contains("必须从候选里选"))
    }
}

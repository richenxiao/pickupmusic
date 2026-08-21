package com.shiyin.music.data.furigana.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UtaTen 解析 + 本地对齐 E2E PoC（藤井風「何なんw」）。
 *
 * 用真实抓取的 UtaTen hiragana 块 HTML fixture，解析 ruby 结构 → 与本地歌词对齐 →
 * 产出 occurrence 级读法。验证目标：
 *   - 何が → なに（第一个 何）
 *   - 何なん 的 何 → なん（后续 何）
 *   - 同一行多个「何」分别得到不同读法（不归一）
 *   - 多 occurrence、逐位置 (lineIndex, charStart) 粒度
 *   - 空格差异经归一化仍能对齐
 *   - 文本不匹配（版本差异）→ 返回 null（拒绝，绝不把读法错位）
 *
 * 这是「外部歌词假名校验」E2E proof-of-concept：从网上人工标注的带假名歌词自动
 * 得到 per-occurrence 真实读法，而非用户逐个长按。
 */
class UtaTenAlignE2ETest {

    private fun loadFixture(): String {
        val path = "/utaten/nannanw_hiragana.html"
        val src = UtaTenAlignE2ETest::class.java.getResourceAsStream(path)
            ?: error("fixture not found: $path")
        return java.io.InputStreamReader(src, Charsets.UTF_8).readText()
    }

    /** 从解析出的 surface 按行切分（trim 每行，模拟 LRC 解析后的纯文本歌词）。 */
    private fun deriveLocalLines(parsed: ParsedExternalLyric): List<String> =
        parsed.surface.split('\n').filter { it.isNotBlank() }.map { it.trim() }

    @Test
    fun 解析fixture_产出ruby读法_第一个何是なに() {
        val parsed = UtaTenParser.parsePage(loadFixture())
        assertNotNull(parsed)
        val p = parsed!!
        assertTrue("应有大量 ruby run", p.runs.size > 50)
        // 第一个单字「何」run 的读法应为 なに
        val firstNan = p.runs.first {
            it.length == 1 && p.surface.substring(it.startInSurface, it.startInSurface + 1) == "何"
        }
        assertEquals("なに", firstNan.reading)
    }

    @Test
    fun E2E_对齐_何が_なに_何なん_なん_逐occurrence不归一() {
        val parsed = UtaTenParser.parsePage(loadFixture())!!
        val localLines = deriveLocalLines(parsed)
        val aligned = LyricAligner.align(parsed, localLines)
        assertNotNull("同版本文本应能对齐", aligned)
        val occ = aligned!!.map { it.copy(source = "utaten") }

        // 找到本地行 "何があってもずっと大好きなのに" 的行号
        val naniLineIdx = localLines.indexOfFirst { it.startsWith("何があっても") }
        assertTrue("应找到 何があっても 行", naniLineIdx >= 0)
        val nani = occ.first { it.lineIndex == naniLineIdx && it.charStart == 0 }
        assertEquals("何が → なに", "なに", nani.reading)
        assertEquals("何", localLines[naniLineIdx].substring(0, 1))

        // "それは何なん" 行的 何 → なん
        val nanLineIdx = localLines.indexOfFirst { it.startsWith("それは何なん") }
        assertTrue("应找到 それは何なん 行", nanLineIdx >= 0)
        val nan = occ.first { it.lineIndex == nanLineIdx && it.charStart == 3 }
        assertEquals("何なん → なん", "なん", nan.reading)

        // 同一行多个「何」分别得不同读法：行 "何で何も聞いてくれんかったん" 有两个何
        val twoNanLineIdx = localLines.indexOfFirst { it.startsWith("何で何も聞いて") }
        assertTrue("应找到 何で何も 行", twoNanLineIdx >= 0)
        val two = occ.filter {
            it.lineIndex == twoNanLineIdx &&
                localLines[twoNanLineIdx].substring(it.charStart, it.charStart + it.length) == "何"
        }.sortedBy { it.charStart }
        assertTrue("该行应有 ≥2 个何 occurrence（实际 ${two.size}）", two.size >= 2)
        // 各 occurrence 的 charStart 互不相同（不归一），且都读 なん（UtaTen 实际标注）
        assertEquals("各 occurrence 位置独立不归一", two.size, two.map { it.charStart }.distinct().size)
        two.forEach { assertEquals("なん", it.reading) }
    }

    @Test
    fun 空格差异_归一化后仍能对齐() {
        val parsed = UtaTenParser.parsePage(loadFixture())!!
        val spaced = deriveLocalLines(parsed).map { it.replace("と", " と ").replace("て", " て ") }
        // 加了多余空格，归一化（去空白）后应与外部 surface 一致 → 仍能对齐
        val aligned = LyricAligner.align(parsed, spaced)
        assertNotNull("空格差异经归一化应能对齐", aligned)
    }

    @Test
    fun 文本不匹配_对齐失败返回null_拒绝错位() {
        val parsed = UtaTenParser.parsePage(loadFixture())!!
        val wrong = listOf("全然別の歌詞のテキスト", "これは一致しない")
        assertNull("不可靠对齐必须返回 null，绝不把读法错位", LyricAligner.align(parsed, wrong))
    }
}

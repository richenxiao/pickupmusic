package com.shiyin.music.data.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTranslationEngineTest {

    // ── 译前：行分组 ─────────────────────────────────────────────────────────

    @Test
    fun sentenceEndClosesGroup() {
        val g = LyricsTranslationEngine.group(listOf("你好。", "世界"))
        assertEquals(2, g.size)
        assertEquals(listOf(0), g[0].lineIndices)
        assertEquals(listOf(1), g[1].lineIndices)
    }

    @Test
    fun linesWithoutPunctuationAccumulateIntoOneGroup() {
        // 歌词一行常是半句：无句末标点时连续行拼成一组
        val g = LyricsTranslationEngine.group(listOf("君の", "名前を", "呼んでる"))
        assertEquals(1, g.size)
        assertEquals(listOf(0, 1, 2), g[0].lineIndices)
        assertEquals("君の 名前を 呼んでる", g[0].text)
        assertTrue(g[0].translatable)
    }

    @Test
    fun groupCappedAtFourLines() {
        val five = List(5) { "line$it" }
        val g = LyricsTranslationEngine.group(five)
        assertEquals(2, g.size)
        assertEquals(listOf(0, 1, 2, 3), g[0].lineIndices)
        assertEquals(listOf(4), g[1].lineIndices)
    }

    @Test
    fun groupCappedAtCharBudget() {
        val long = "あ".repeat(130)
        val g = LyricsTranslationEngine.group(listOf(long, "short"))
        assertEquals(2, g.size)
        assertEquals(listOf(0), g[0].lineIndices)
        assertEquals(listOf(1), g[1].lineIndices)
    }

    @Test
    fun blankLineSeparatesAndIsNotTranslatable() {
        val g = LyricsTranslationEngine.group(listOf("你好。", "", "世界。"))
        assertEquals(3, g.size)
        assertTrue(g[0].translatable)
        assertFalse(g[1].translatable)
        assertEquals("", g[1].text)
        assertTrue(g[2].translatable)
    }

    @Test
    fun symbolOnlyLineIsNotTranslatable() {
        val g = LyricsTranslationEngine.group(listOf("♪ ♪", "你好。"))
        assertEquals(2, g.size)
        assertFalse(g[0].translatable)
        assertTrue(g[1].translatable)
    }

    @Test
    fun translatableGroupsSkipsNonTranslatable() {
        val g = LyricsTranslationEngine.translatableGroups(listOf("你好。", "", "♪"))
        assertEquals(1, g.size)
        assertEquals(listOf(0), g[0].lineIndices)
    }

    // ── 译后：拆回对齐 ───────────────────────────────────────────────────────

    @Test
    fun singleLineGroupTakesWholeTranslation() {
        val lines = listOf("君の名前を呼んでる")
        val a = LyricsTranslationEngine.splitBack("呼喊着你的名字", lines, listOf(0))
        assertEquals(mapOf(0 to "呼喊着你的名字"), a.byLine)
        assertFalse(a.wholeGroupOnFirstLine)
    }

    @Test
    fun equalSegmentCountMapsOneToOne() {
        val lines = listOf("你好", "世界")
        val a = LyricsTranslationEngine.splitBack("你好。世界。", lines, listOf(0, 1))
        assertEquals(mapOf(0 to "你好。", 1 to "世界。"), a.byLine)
        assertFalse(a.wholeGroupOnFirstLine)
    }

    @Test
    fun proportionalAllocationSnapsToPunctuation() {
        val lines = listOf("一二三四五", "六七八九十")
        // 权重相等 → 切点落在中点附近的标点后
        val a = LyricsTranslationEngine.splitBack("甲乙丙丁戊。己庚辛壬癸", lines, listOf(0, 1))
        assertEquals(mapOf(0 to "甲乙丙丁戊。", 1 to "己庚辛壬癸"), a.byLine)
        assertFalse(a.wholeGroupOnFirstLine)
    }

    @Test
    fun misalignedFallsBackToWholeGroupOnFirstLine() {
        // 译文无任何标点 + 行权重悬殊 → 比例分配无法吸附标点 → 走兜底
        val lines = listOf("短", "这是一句非常长的歌词内容用来拉长权重")
        val a = LyricsTranslationEngine.splitBack("无标点的整段译文", lines, listOf(0, 1))
        assertTrue(a.wholeGroupOnFirstLine)
        assertEquals(mapOf(0 to "无标点的整段译文"), a.byLine)
        // 关键：绝不给第 1 行挂上属于整段的意思
        assertFalse(a.byLine.containsKey(1))
    }

    @Test
    fun emptyTranslationYieldsNothing() {
        val a = LyricsTranslationEngine.splitBack("   ", listOf("a", "b"), listOf(0, 1))
        assertTrue(a.byLine.isEmpty())
    }

    @Test
    fun emptyGroupYieldsNothing() {
        val a = LyricsTranslationEngine.splitBack("译文", emptyList(), emptyList())
        assertTrue(a.byLine.isEmpty())
    }

    /**
     * 不变量：无论对齐成功与否，返回的行号必须落在组内，且译文非空。
     * 这条不变量就是"宁可缺行，不可错行"的机器可检形式——错行会表现为
     * 某个行号拿到空译文或行号越界。
     */
    @Test
    fun invariantNeverEmitsForeignLineOrBlankText() {
        val lines = listOf(
            "君の名前を", "呼んでいる", "夜に駆ける", "その声が", "聞こえる",
        )
        val translations = listOf(
            "呼喊你的名字", "在夜里奔跑。", "那声音。能听见。",
            "一整段没有任何标点的中文译文用来触发兜底路径",
            "",
            "短。长一点的第二段译文内容在这里。",
        )
        val groups = LyricsTranslationEngine.group(lines)
        for (t in translations) {
            for (g in groups) {
                if (!g.translatable) continue
                val a = LyricsTranslationEngine.splitBack(t, lines, g.lineIndices)
                for ((line, text) in a.byLine) {
                    assertTrue("行号越界: $line", line in g.lineIndices)
                    assertTrue("空译文: line=$line", text.isNotBlank())
                }
                if (a.wholeGroupOnFirstLine) {
                    assertEquals(1, a.byLine.size)
                    assertTrue(a.byLine.containsKey(g.lineIndices[0]))
                }
            }
        }
    }

    // ── 汇总 ─────────────────────────────────────────────────────────────────

    @Test
    fun mergeAlignmentsSkipsNonTranslatableAndMissing() {
        val lines = listOf("你好。", "", "世界。")
        val groups = LyricsTranslationEngine.group(lines)
        val byGroup = mapOf(
            groups[0] to "你好。",
            // groups[1] 不可译；groups[2] 故意不给译文（模拟模型漏译）
        )
        val merged = LyricsTranslationEngine.mergeAlignments(lines, groups, byGroup)
        assertEquals(mapOf(0 to "你好。"), merged)
    }

    @Test
    fun mergeAlignmentsCollectsEveryTranslatedLine() {
        // 每行各自以句末标点闭合 → 三个单行组 → 每行都能拿到译文
        val lines = listOf("君の名前を呼んでる。", "夜に駆ける。", "その声が聞こえる。")
        val groups = LyricsTranslationEngine.group(lines)
        assertEquals(3, groups.size)
        val byGroup = groups.filter { it.translatable }.associateWith { "译:" + it.text }
        val merged = LyricsTranslationEngine.mergeAlignments(lines, groups, byGroup)
        assertEquals(lines.size, merged.size)
        for (i in lines.indices) assertTrue(merged.containsKey(i))
    }

    /**
     * 记录一条**有意为之**的行为：多行组只译回一整句时，只挂第一行。
     *
     * 这是离线路径的常态（ML Kit 是句级模型，一段跨行的句子译回来就是一个中文句子，
     * 内部往往没有可切分的标点）。此处不做猜测性切分——那会把半句话挂到错误的行上。
     * 若日后判定"离线也该逐行铺开"，改动点是 [LyricsTranslationEngine] 的档3容差策略，
     * 而不是这里的期望。
     */
    @Test
    fun multiLineGroupWithSingleSentenceOnlyFillsFirstLine() {
        val lines = listOf("君の名前を", "呼んでる", "夜に駆ける。")
        val groups = LyricsTranslationEngine.group(lines)
        assertEquals(1, groups.size)
        val a = LyricsTranslationEngine.splitBack("呼喊着你的名字在夜里奔跑", lines, groups[0].lineIndices)
        assertTrue(a.wholeGroupOnFirstLine)
        assertEquals(mapOf(0 to "呼喊着你的名字在夜里奔跑"), a.byLine)
    }
}

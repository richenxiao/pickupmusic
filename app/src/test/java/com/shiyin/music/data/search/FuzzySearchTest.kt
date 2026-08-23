package com.shiyin.music.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzySearchTest {

    private fun ranges(q: String, f: String): List<IntRange> =
        FuzzySearch.match(q, f)?.ranges ?: emptyList()

    // ── 繁简互通 ──────────────────────────────────────────────────────────
    @Test
    fun traditionalMatchesSimplified() {
        assertNotNull(FuzzySearch.match("藤井风", "藤井風"))
        assertNotNull(FuzzySearch.match("藤井風", "藤井风"))
    }

    @Test
    fun traditionalInsideField() {
        val m = FuzzySearch.match("雲門", "云门舞集")
        assertNotNull(m)
        assertEquals(listOf(0..1), m!!.ranges)
    }

    @Test
    fun commonCharsMap() {
        assertNotNull(FuzzySearch.match("音乐", "音樂"))
        assertNotNull(FuzzySearch.match("快乐", "快樂"))
        assertNotNull(FuzzySearch.match("张学友", "張學友"))
    }

    // ── 空格灵活 ──────────────────────────────────────────────────────────
    @Test
    fun spacesInQueryAreIgnored() {
        assertNotNull(FuzzySearch.match("藤井 风", "藤井风"))
        assertNotNull(FuzzySearch.match("藤井 风", "藤井 風"))
    }

    @Test
    fun spacesInFieldAreIgnored() {
        assertNotNull(FuzzySearch.match("藤井风", "藤井 风"))
        assertNotNull(FuzzySearch.match("周杰伦", "周杰伦 最伟大的作品"))
    }

    @Test
    fun multiTokenOrdered() {
        assertNotNull(FuzzySearch.match("杰伦 稻香", "周杰伦 - 稻香"))
        assertNull(FuzzySearch.match("稻香 杰伦", "周杰伦 - 稻香")) // 顺序敏感
    }

    // ── 英文错字容错（编辑距离） ──────────────────────────────────────────
    @Test
    fun englishTypoTolerance() {
        val m = FuzzySearch.match("weekend", "weeknd")
        assertNotNull(m)
        assertTrue(m!!.score < 800) // 走的是模糊分支而非精确包含
    }

    @Test
    fun transpositionCountsAsOneEdit() {
        assertNotNull(FuzzySearch.match("the", "teh"))
    }

    @Test
    fun tooFarApartRejected() {
        assertNull(FuzzySearch.match("abcdef", "qwerty"))
        assertNull(FuzzySearch.match("zz", "weeknd"))
    }

    @Test
    fun singleLetterNoTolerance() {
        assertNull(FuzzySearch.match("a", "b"))
    }

    // ── 中文错字 ──────────────────────────────────────────────────────────
    @Test
    fun chineseTypoTolerance() {
        assertNotNull(FuzzySearch.match("张雪友", "张学友"))
        assertNotNull(FuzzySearch.match("周杰伦", "周杰倫"))
    }

    // ── 高亮区间坐标 ──────────────────────────────────────────────────────
    @Test
    fun rangesPointIntoOriginalString() {
        // 字段含空格时，命中区间的坐标应落在原始字符串上
        val f = "藤井 風"
        val r = ranges("藤井风", f)
        assertEquals(listOf(0..3), r) // 含中间空格（索引 2）
        assertEquals(f, f.substring(r[0].first, r[0].last + 1))
    }

    @Test
    fun rangesForFuzzyMatchCoverTheWord() {
        val r = ranges("weekend", "Weeknd")
        assertEquals("Weeknd".substring(r[0].first, r[0].last + 1), "Weeknd")
    }

    // ── 归一化 ────────────────────────────────────────────────────────────
    @Test
    fun normalizePipeline() {
        assertEquals("藤井风", FuzzySearch.normalize("藤井 風"))
        assertEquals("zhoujielun", FuzzySearch.normalize("Zhou Jie Lun"))
    }

    // v1.2.0 #8 fix: 短 token 不靠单字共享误配
    @Test
    fun shortCjkTokenDoesNotMatchSingleSharedChar() {
        assertNull(FuzzySearch.match("伤心", "心雨"))
        assertNull(FuzzySearch.match("伤心", "月亮代表我的心"))
        assertNull(FuzzySearch.match("伤心", "悲伤"))
    }

    @Test
    fun shortCjkTokenStillMatchesSubstring() {
        assertNotNull(FuzzySearch.match("伤心", "伤心早餐店"))
    }
}

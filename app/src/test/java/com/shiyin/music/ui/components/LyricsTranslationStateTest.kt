package com.shiyin.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译 UI 状态机的单测。
 *
 * 三条核心不变量：
 * 1. **按曲隔离** —— 上一首的译文不得残留到新歌；
 * 2. **部分成功不误判为失败** —— 补齐失败不能把已拿到的译文一起丢掉；
 * 3. **数据与显示分离** —— `hasData`（持久化的数据）与 `hasContent`（当前是否显示）
 *    必须能各自独立成立，这是"再点一下又没了"不再等于"数据丢了"的前提。
 */
class LyricsTranslationStateTest {

    private val H = "hash1"

    @Test
    fun initialStateIsOffAndEmpty() {
        val s = LyricsTranslationState()
        assertEquals(LyricsTranslationMode.Off, s.mode)
        assertFalse(s.hasContent)
        assertFalse(s.hasData)
        assertNull(s.progressLabel)
        assertNull(s.error)
        assertNull(s.mediaId)
        assertNull(s.lyricsHash)
        assertTrue(s.byLine.isEmpty())
    }

    @Test
    fun beginSetsModeOwnershipAndClearsPreviousRun() {
        val s = LyricsTranslationState()
        s.begin(LyricsTranslationMode.Ai, 111L, "old")
        s.finish(mapOf(0 to "旧译文"), "旧错误")
        assertEquals(mapOf(0 to "旧译文"), s.byLine)

        s.begin(LyricsTranslationMode.Ai, 222L, "new")
        assertEquals(LyricsTranslationMode.Ai, s.mode)
        assertEquals(222L, s.mediaId)
        assertEquals("new", s.lyricsHash)
        assertTrue(s.running)
        assertTrue("新一轮不该带上一首的译文", s.byLine.isEmpty())
        assertNull("新一轮不该带上一首的错误", s.error)
        assertEquals(0, s.done)
        assertEquals(0, s.total)
    }

    @Test
    fun progressLabelOnlyWhileRunning() {
        val s = LyricsTranslationState()
        s.begin(LyricsTranslationMode.Ai, 1L, H)
        s.done = 23
        s.total = 58
        assertEquals("翻译中… 23/58 行", s.progressLabel)

        s.finish(mapOf(0 to "x"), null)
        assertNull("结束后不该再显示进行中", s.progressLabel)
    }

    @Test
    fun finishWithSuccessStoresTranslations() {
        val s = LyricsTranslationState()
        s.begin(LyricsTranslationMode.Ai, 7L, H)
        s.finish(mapOf(0 to "第一行", 2 to "第三行"), null)
        assertFalse(s.running)
        assertNull(s.error)
        assertTrue(s.hasContent)
        assertTrue(s.hasData)
        assertEquals("第一行", s.translationFor(0))
        assertEquals("第三行", s.translationFor(2))
        assertNull("缺行号 = 无译文", s.translationFor(1))
    }

    /** 部分成功：只译出一部分时，已拿到的译文必须留住，只把原因透给 UI。 */
    @Test
    fun finishWithFailureKeepsPartialTranslations() {
        val s = LyricsTranslationState()
        s.begin(LyricsTranslationMode.Ai, 7L, H)
        s.finish(mapOf(0 to "第一行"), "已译 1/3 行（其余未返回）")
        assertTrue("部分成果必须保留", s.hasContent)
        assertEquals("第一行", s.translationFor(0))
        assertEquals("已译 1/3 行（其余未返回）", s.error)
        assertFalse(s.running)
    }

    // ── 数据 vs 显示（本轮修复的核心）───────────────────────────────────────

    /** 隐藏只改显示态：数据仍在，`hasContent` 转 false 而 `hasData` 保持 true。 */
    @Test
    fun hideKeepsDataOnlyClearsVisibility() {
        val s = LyricsTranslationState()
        s.begin(LyricsTranslationMode.Ai, 7L, H)
        s.finish(mapOf(0 to "第一行"), null)

        s.hide()
        assertEquals(LyricsTranslationMode.Off, s.mode)
        assertFalse("隐藏后不该渲染", s.hasContent)
        assertTrue("隐藏不等于丢数据", s.hasData)
        assertEquals("译文仍可取", "第一行", s.translationFor(0))

        s.show()
        assertEquals(LyricsTranslationMode.Ai, s.mode)
        assertTrue(s.hasContent)
    }

    @Test
    fun showIsNoOpWhenThereIsNoData() {
        val s = LyricsTranslationState()
        s.show()
        assertEquals("无数据时 show 不该把档位打开", LyricsTranslationMode.Off, s.mode)
        assertFalse(s.hasContent)
    }

    /** 持久化恢复：默认**直接显示**（用户要求"和振假名一样，默认保存好的"）。 */
    @Test
    fun restoreShowsByDefault() {
        val s = LyricsTranslationState()
        s.restore(mapOf(0 to "恢复的译文", 1 to "第二行"), "ai", 42L, "h42")
        assertEquals(LyricsTranslationMode.Ai, s.mode)
        assertTrue(s.hasContent)
        assertTrue(s.hasData)
        assertEquals(42L, s.mediaId)
        assertEquals("h42", s.lyricsHash)
        assertFalse(s.running)
        assertNull(s.error)
    }

    @Test
    fun restoreMapsOfflineSourceToOfflineMode() {
        val s = LyricsTranslationState()
        s.restore(mapOf(0 to "离线译文"), "offline", 5L, H)
        assertEquals(LyricsTranslationMode.Offline, s.mode)
        assertTrue(s.hasContent)
    }

    @Test
    fun restoreIgnoresEmptyPayload() {
        val s = LyricsTranslationState()
        s.restore(emptyMap(), "ai", 5L, H)
        assertEquals("空数据不该把档位打开", LyricsTranslationMode.Off, s.mode)
        assertFalse(s.hasData)
    }

    @Test
    fun resetClearsEverything() {
        val s = LyricsTranslationState()
        s.begin(LyricsTranslationMode.Ai, 9L, H)
        s.finish(mapOf(0 to "x"), "err")
        s.reset()
        assertEquals(LyricsTranslationMode.Off, s.mode)
        assertFalse(s.running)
        assertFalse(s.hasContent)
        assertFalse(s.hasData)
        assertTrue(s.byLine.isEmpty())
        assertNull(s.error)
        assertNull(s.mediaId)
        assertNull(s.lyricsHash)
        assertEquals(0, s.done)
        assertEquals(0, s.total)
    }

    /** 档位关闭时即便有残留译文也不该渲染（防"关了还显示"）。 */
    @Test
    fun offModeHidesContentEvenIfTranslationsPresent() {
        val s = LyricsTranslationState()
        s.byLine = mapOf(0 to "x")
        s.mode = LyricsTranslationMode.Off
        assertFalse(s.hasContent)
        // translationFor 仍可读（供调试），但渲染层以 hasContent 为准
        assertEquals("x", s.translationFor(0))
    }
}

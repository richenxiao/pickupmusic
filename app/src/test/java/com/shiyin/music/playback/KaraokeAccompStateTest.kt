package com.shiyin.music.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-1：**按钮完成态的事件源**。
 *
 * 事故形态是"按钮先变成完成态，几秒后才真的过渡到伴奏"。所以这里钉住两件事：
 *  1. `audibleSeq` 只在**处理器真的读到伴奏**时推进（同一档位继续成功不再推进）；
 *  2. `inFallback` 要能如实表示"确实在回退原曲"，但**不能被单块抖动带跑**。
 */
class KaraokeAccompStateTest {

    @After
    fun tearDown() = KaraokeAccompState.reset()

    @Test
    fun `第一次读到伴奏_推进一次并记下时刻`() {
        KaraokeAccompState.onBlock(ready = true, mode = 1, nowMs = 1000L)
        assertEquals(1L, KaraokeAccompState.audibleSeq)
        assertEquals(1, KaraokeAccompState.audibleMode)
        assertEquals(1000L, KaraokeAccompState.audibleAtMs)
    }

    @Test
    fun `同一档位继续成功_不再推进`() {
        repeat(20) { KaraokeAccompState.onBlock(true, 1, 1000L + it) }
        assertEquals("只有'开始淡入'那一次才算事件，稳态不刷屏", 1L, KaraokeAccompState.audibleSeq)
    }

    @Test
    fun `换档后第一次成功_再推进一次`() {
        KaraokeAccompState.onBlock(true, 1, 1000L)
        KaraokeAccompState.onBlock(true, 2, 1400L)
        assertEquals(2L, KaraokeAccompState.audibleSeq)
        assertEquals(2, KaraokeAccompState.audibleMode)
        assertEquals(1400L, KaraokeAccompState.audibleAtMs)
    }

    @Test
    fun `单块欠载不算回退_避免按钮闪`() {
        KaraokeAccompState.onBlock(ready = false, mode = 1, nowMs = 1000L)
        assertFalse("一块抖动不该让按钮退回未就绪", KaraokeAccompState.inFallback)
    }

    @Test
    fun `连续欠载到阈值_才算确实在回退`() {
        repeat(KaraokeAccompState.FALLBACK_MIN_BLOCKS) {
            KaraokeAccompState.onBlock(false, 1, 1000L + it)
        }
        assertTrue(KaraokeAccompState.inFallback)
    }

    @Test
    fun `恢复成功后_欠载计数归零`() {
        repeat(5) { KaraokeAccompState.onBlock(false, 1, 1000L + it) }
        assertTrue(KaraokeAccompState.inFallback)
        KaraokeAccompState.onBlock(true, 1, 2000L)
        assertFalse("读到伴奏就不该还算在回退", KaraokeAccompState.inFallback)
    }

    @Test
    fun `回退期间不会误推进_audibleSeq`() {
        repeat(10) { KaraokeAccompState.onBlock(false, 1, 1000L + it) }
        assertEquals("欠载期间没有'开始淡入'可言", 0L, KaraokeAccompState.audibleSeq)
        assertEquals(-1, KaraokeAccompState.audibleMode)
    }

    @Test
    fun `reset_全部归零`() {
        KaraokeAccompState.onBlock(true, 2, 5000L)
        repeat(3) { KaraokeAccompState.onBlock(false, 2, 5001L + it) }
        KaraokeAccompState.reset()
        assertEquals(0L, KaraokeAccompState.audibleSeq)
        assertEquals(-1, KaraokeAccompState.audibleMode)
        assertEquals(-1L, KaraokeAccompState.audibleAtMs)
        assertFalse(KaraokeAccompState.inFallback)
    }

    @Test
    fun `阈值恰好是两块_能在300ms内反映`() {
        // 两条约束同时成立：够滤掉单块抖动，又不超过验收的 300ms（一块≈100ms）。
        assertTrue("阈值不能太高，否则超过 300ms 才反映", KaraokeAccompState.FALLBACK_MIN_BLOCKS <= 3)
        assertTrue("阈值不能是 1，否则单块抖动就让按钮闪", KaraokeAccompState.FALLBACK_MIN_BLOCKS >= 2)
    }
}

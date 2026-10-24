package com.shiyin.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补挂闭环触发依据（[KaraokeArmWatch]）的回归测试。要钉住的是一对**必须同时成立**的性质：
 *
 *  1. **同一首之内**的位置跳变（回绕重播 / 拖动进度）⇒ 该补挂。真机报障：一张专辑只有一首歌
 *     时开伴唱，按下一首回绕到本曲，音频线程解除武装、伴奏停在原曲，而 app 收不到任何回调。
 *  2. **真换曲** ⇒ 绝不能补挂。补挂会拿上一首的缓存去喂新歌，正是 2026-10-19 那个
 *     "换曲漏出上一首开头"的 bug；所以判据取"条目没变而位置自己跳了"，
 *     而不是"当前条目 id 还对得上"（后者在真换曲时会因快照未更新而误判）。
 */
class KaraokeArmWatchTest {

    @Test
    fun `首次采样不触发`() {
        val w = KaraokeArmWatch()
        assertFalse("还没有上一拍可比，不能凭空开窗口", w.sample(itemId = 7, posMs = 30_000, nowMs = 1_000, playing = true))
    }

    @Test
    fun `正常播放不触发`() {
        val w = KaraokeArmWatch()
        var pos = 0L
        var t = 0L
        repeat(20) {
            t += 100
            pos += 100
            assertFalse("按钟推进不该被当成跳变", w.sample(7, pos, t, playing = true))
        }
    }

    @Test
    fun `位置采样粗糙_间隔两拍也不触发`() {
        // 位置状态是 300ms 刷一次，100ms 的轮询会连着两拍读到同一个值：
        // 累计误差最大 ±300ms，必须远小于判据阈值，否则正常播放会被误判成跳变。
        val w = KaraokeArmWatch()
        w.sample(7, 0, 0, playing = true)
        assertFalse(w.sample(7, 0, 100, playing = true))
        assertFalse(w.sample(7, 300, 200, playing = true))
        assertFalse(w.sample(7, 300, 300, playing = true))
        assertFalse(w.sample(7, 600, 400, playing = true))
    }

    @Test
    fun `同曲回绕_开窗`() {
        val w = KaraokeArmWatch()
        w.sample(7, 200_000, 0, playing = true)      // 放到结尾
        assertTrue("回绕到 0 就是同一首之内的跳变", w.sample(7, 0, 100, playing = true))
    }

    @Test
    fun `同曲拖动进度_开窗`() {
        val w = KaraokeArmWatch()
        w.sample(7, 60_000, 0, playing = true)
        assertTrue("往回拖也是跳变（拖动同样会让音频线程解除武装）", w.sample(7, 20_000, 100, playing = true))
    }

    @Test
    fun `换曲不触发_哪怕位置跳得比回绕还狠`() {
        val w = KaraokeArmWatch()
        w.sample(7, 200_000, 0, playing = true)
        assertFalse(
            "条目 id 变了 ⇒ 由 onTrackStarted 决定停/挂，这里绝不能补挂",
            w.sample(9, 0, 100, playing = true),
        )
    }

    @Test
    fun `换曲后的后续采样也不触发`() {
        val w = KaraokeArmWatch()
        w.sample(7, 200_000, 0, playing = true)
        w.sample(9, 0, 100, playing = true)          // 换曲那一拍
        assertFalse("新条目要继续按它自己的进度走才算正常", w.sample(9, 100, 200, playing = true))
    }

    @Test
    fun `暂停时不按钟外推`() {
        val w = KaraokeArmWatch()
        w.sample(7, 10_000, 0, playing = false)
        assertFalse("暂停时位置本就该不动，不能算成跳变", w.sample(7, 10_000, 5_000, playing = false))
    }

    @Test
    fun `补挂窗口会自动关闭`() {
        val w = KaraokeArmWatch(jumpMs = 1_200, windowMs = 3_000)
        w.sample(7, 200_000, 0, playing = true)                  // 首拍
        assertTrue("回绕即开窗", w.sample(7, 0, 100, playing = true))
        assertTrue(w.sample(7, 100, 200, playing = true))        // 之后正常走，窗口内
        assertTrue(w.sample(7, 2_900, 3_000, playing = true))    // 3_000 < 窗口末 3_100
        assertFalse("窗口过了就收手，不能无限补挂", w.sample(7, 3_000, 3_100, playing = true))
    }

    @Test
    fun `窗口内再跳一次会续期`() {
        val w = KaraokeArmWatch(jumpMs = 1_200, windowMs = 3_000)
        w.sample(7, 200_000, 0, playing = true)
        assertTrue(w.sample(7, 0, 100, playing = true))          // 第一跳 ⇒ 窗口到 3_100
        assertTrue(w.sample(7, 2_500, 2_600, playing = true))    // 正常走，仍在窗口内
        assertTrue("窗口内又跳一次 ⇒ 续期到 5_700", w.sample(7, 0, 2_700, playing = true))
        assertTrue(w.sample(7, 1_000, 3_700, playing = true))    // 3_700 < 5_700
        assertFalse("续期后的窗口过了才收手", w.sample(7, 2_100, 5_700, playing = true))
    }

    @Test
    fun `会话未就绪时不触发`() {
        val w = KaraokeArmWatch()
        assertFalse(w.sample(itemId = null, posMs = 0, nowMs = 0, playing = true))
        assertFalse(w.sample(itemId = null, posMs = 5_000, nowMs = 100, playing = true))
    }
}

package com.shiyin.music.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 步骤 2c 的锚点匹配规则，是本轮**最容易悄悄错**的一处纯逻辑：
 *
 * - ExoPlayer 的 `AudioSink.handleBuffer` 在 AudioTrack 缓冲满时**可以返回 false 且不消费**，
 *   renderer 会拿**同一个 buffer（同一 pts）**重调 ⇒ sink 侧去重（这里不测，测不到）；
 * - `TrimmingAudioProcessor` 可能把**一个**输入 buffer 拆成**多次** `queueInput`；
 * - 被裁剪吃掉的 buffer 可能**一次 `queueInput` 都不产生**。
 *
 * 所以匹配规则是「`hookFrames` **严格相等** 才算本 buffer 的锚点」。这四条用例把那几条边界钉住。
 */
class KaraokeBufferAnchorTest {

    @After
    fun tearDown() {
        KaraokeBufferAnchor.clear()
    }

    @Test
    fun `严格相等才命中_否则不锚`() {
        KaraokeBufferAnchor.push(hookFrames = 0, ptsUs = 1_000)
        assertEquals(1_000L, KaraokeBufferAnchor.take(0))
        // 已经取走 ⇒ 再问就没有了（不能重复使用同一时间戳）
        assertEquals(-1L, KaraokeBufferAnchor.take(0))
    }

    @Test
    fun `hookFrames 对不上时返回无锚_不做任何猜测`() {
        KaraokeBufferAnchor.push(hookFrames = 100, ptsUs = 2_000)
        // 处理器才走到 0 帧 ⇒ 这条锚点说的不是本 buffer，不能拿它凑合
        assertEquals(-1L, KaraokeBufferAnchor.take(0))
    }

    @Test
    fun `被吃掉的 buffer_后到的同 hookFrames 锚点覆盖它`() {
        // 第一条锚点对应的 buffer 没走到处理器（被裁剪整块吃掉）⇒ 第二条才是有效的
        KaraokeBufferAnchor.push(hookFrames = 0, ptsUs = 1_000)
        KaraokeBufferAnchor.push(hookFrames = 0, ptsUs = 9_000)
        assertEquals(9_000L, KaraokeBufferAnchor.take(0))
    }

    @Test
    fun `过期锚点被丢弃并计数`() {
        val before = KaraokeBufferAnchor.dropped
        KaraokeBufferAnchor.push(hookFrames = 0, ptsUs = 1_000)
        KaraokeBufferAnchor.push(hookFrames = 500, ptsUs = 2_000)
        // 处理器已经走到 500 帧 ⇒ 第一条（0 帧的）永远配不上了，必须作废而不是错配
        assertEquals(2_000L, KaraokeBufferAnchor.take(500))
        assertEquals(before + 1, KaraokeBufferAnchor.dropped)
    }

    @Test
    fun `清空后既不命中也不留残留`() {
        KaraokeBufferAnchor.push(hookFrames = 0, ptsUs = 1_000)
        KaraokeBufferAnchor.clear()
        assertEquals(-1L, KaraokeBufferAnchor.take(0))
    }
}

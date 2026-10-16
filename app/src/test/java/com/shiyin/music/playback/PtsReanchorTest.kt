package com.shiyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 闸门 1（gapless 重锚）的**纯逻辑**测试。
 *
 * 真源是 Media3 1.7.1 的两条事实（行号见 `KARAOKE-PROGRESS.md`）：
 *  1. 渲染器偏移 = 1e12µs 的 epoch **+ 播放列表里前面各条目的时长**（逐条目累加）；
 *  2. 换条目时 `DecoderAudioRenderer.drainOutputBuffer` 的顺序是
 *     **先** `handleDiscontinuity`（`:517`）、**再** `setOutputStreamOffsetUs`（`:519`）、
 *     **最后** 才写该条目第一个 buffer（`:501`）。
 *
 * ⇒ "用播放器报来的偏移重锚"成立；而"拿 epoch 常量兜底"**只在第一条成立**。
 * 这几条用例就是把这两个结论钉死：谁把偏移口径改回常量、或者把换条目当普通重锚，
 * 第 2/3 条的用例会立刻失败。
 */
class PtsReanchorTest {

    private val epochUs = 1_000_000_000_000L

    /** 源帧号 → 该帧的渲染器 pts（µs）= 条目偏移 + 帧号换算。 */
    private fun ptsUsOf(srcFrame: Long, rate: Int, offsetUs: Long): Long =
        offsetUs + Math.round(srcFrame.toDouble() * 1_000_000.0 / rate)

    /** 驱动一个带锚点的 buffer。 */
    private fun feed(r: PtsReanchor, ptsUs: Long, rate: Int, frames: Int): Long =
        r.onBuffer(ptsUs = ptsUs, rate = rate, frames = frames, countMiss = true)

    // ── 首条曲目 / 兜底 ─────────────────────────────────────────────────────

    @Test
    fun `首条曲目_扣掉epoch后帧号精确`() {
        val r = PtsReanchor()
        r.onSinkOffset(epochUs)
        assertEquals("epoch 的帧数 = rate×1e6", 44_100_000_000L, r.offsetFramesFor(44100))

        var f = 0L
        repeat(10) {
            assertEquals("第 $f 帧的锚点算错", f, feed(r, ptsUsOf(f, 44100, epochUs), 44100, 4096))
            f += 4096
        }
        assertEquals("稳态不该出现重锚", 0, r.realignCount)
        assertEquals("首条不算换条目", 0, r.itemChangeCount)
    }

    @Test
    fun `偏移未知时退回epoch常量`() {
        val r = PtsReanchor()
        assertEquals(-1L, r.offsetUs)
        assertEquals(44_100_000_000L, r.offsetFramesFor(44100))
        assertEquals(48_000_000_000L, r.offsetFramesFor(48000))
        // 真机实测口径：条目内 + 偏移未报时，"戳差(P−T)" 恒等于 epoch 帧数
        assertEquals(1000L, feed(r, ptsUsOf(1000, 44100, epochUs), 44100, 8))
    }

    // ── 条目切换（gapless）──────────────────────────────────────────────────

    @Test
    fun `gapless换条目_第二条从第0帧开始_采样率也换了`() {
        val r = PtsReanchor()
        val dur1Us = 30_000_000L
        r.onSinkOffset(epochUs)                                  // 第一条：偏移 = epoch
        assertEquals(0L, feed(r, ptsUsOf(0, 44100, epochUs), 44100, 4096))
        assertEquals(4096L, feed(r, ptsUsOf(4096, 44100, epochUs), 44100, 4096))

        // 换条目：**照源码顺序**先 discontinuity、再新偏移
        r.onDiscontinuity()
        val off2 = epochUs + dur1Us
        r.onSinkOffset(off2)
        assertEquals("换条目应被记一次", 1, r.itemChangeCount)

        // 第二条第一个 buffer：帧号必须回到 0（采样率故意换成 48k）
        assertEquals("第二条起点必须是第 0 帧", 0L, feed(r, ptsUsOf(0, 48000, off2), 48000, 4096))
        assertEquals(4096L, feed(r, ptsUsOf(4096, 48000, off2), 48000, 4096))
        assertEquals("换条目引起的跳变**不该**算成『重锚』", 0, r.realignCount)
    }

    @Test
    fun `三条目_偏移逐条累加_每条都从第0帧开始`() {
        val r = PtsReanchor()
        val offUs = longArrayOf(epochUs, epochUs + 30_000_000L, epochUs + 50_000_000L)
        val rates = intArrayOf(44100, 48000, 44100)
        val frames = 4096

        for (i in 0..2) {
            if (i > 0) {
                r.onDiscontinuity()
                r.onSinkOffset(offUs[i])
            }
            assertEquals(
                "第 ${i + 1} 条起点",
                0L,
                feed(r, ptsUsOf(0, rates[i], offUs[i]), rates[i], frames),
            )
            assertEquals(
                "第 ${i + 1} 条第二块",
                frames.toLong(),
                feed(r, ptsUsOf(frames.toLong(), rates[i], offUs[i]), rates[i], frames),
            )
        }
        assertEquals(2, r.itemChangeCount)
        assertEquals("三条目稳态都不该重锚", 0, r.realignCount)
    }

    @Test
    fun `偏移按微秒存_换采样率不会拿旧采样率算`() {
        val r = PtsReanchor()
        val off = epochUs + 30_000_000L
        r.onSinkOffset(off)
        val at44 = Math.round(off.toDouble() * 44100 / 1_000_000.0)
        val at48 = Math.round(off.toDouble() * 48000 / 1_000_000.0)
        assertTrue("同一偏移在两种采样率下的帧数必然不同", at48 > at44)
        assertEquals(at44, r.offsetFramesFor(44100))
        assertEquals(at48, r.offsetFramesFor(48000))
    }

    @Test
    fun `重复报同一偏移不算换条目`() {
        val r = PtsReanchor()
        r.onSinkOffset(epochUs)
        feed(r, ptsUsOf(0, 44100, epochUs), 44100, 4096)
        r.onSinkOffset(epochUs)                       // 同值重复
        assertEquals(0, r.itemChangeCount)
        assertEquals("同值重复不该作废自数帧", 4096L, r.absNext)
    }

    @Test
    fun `非法偏移被忽略`() {
        val r = PtsReanchor()
        r.onSinkOffset(0L)
        r.onSinkOffset(-1L)
        assertEquals(0, r.offsetReportCount)
        assertEquals(-1L, r.offsetUs)
    }

    // ── seek / flush ────────────────────────────────────────────────────────

    @Test
    fun `seek后_按新位置重新锚定_且不算重锚`() {
        val r = PtsReanchor()
        r.onSinkOffset(epochUs)
        feed(r, ptsUsOf(0, 44100, epochUs), 44100, 4096)

        r.onFlush()
        assertEquals("flush 后自数帧作废", -1L, r.absNext)

        val at30s = 44_100L * 30
        assertEquals(at30s, feed(r, ptsUsOf(at30s, 44100, epochUs), 44100, 4096))
        assertEquals(at30s + 4096, r.absNext)
        assertEquals("seek 引起的跳变不算重锚", 0, r.realignCount)
    }

    // ── 拿不到锚点 ─────────────────────────────────────────────────────────

    @Test
    fun `从未锚定过就拿不到时间戳_一直返回-1_不瞎猜`() {
        val r = PtsReanchor()
        assertEquals("宁可 -1（调用方按欠载退回原曲），也不许猜一个位置", -1L,
            r.onBuffer(ptsUs = -1, rate = 44100, frames = 4096, countMiss = true))
        assertEquals(-1L,
            r.onBuffer(ptsUs = -1, rate = 44100, frames = 4096, countMiss = true))
        assertEquals(2, r.anchorMissCount)
    }

    @Test
    fun `锚定过之后偶尔丢一块时间戳_靠自数继续_且不算重锚`() {
        val r = PtsReanchor()
        r.onSinkOffset(epochUs)
        assertEquals(0L, feed(r, ptsUsOf(0, 44100, epochUs), 44100, 4096))
        assertEquals("沿用自数", 4096L,
            r.onBuffer(ptsUs = -1, rate = 44100, frames = 4096, countMiss = true))
        assertEquals(8192L, feed(r, ptsUsOf(8192, 44100, epochUs), 44100, 4096))
        assertEquals(0, r.realignCount)
    }

    @Test
    fun `偏移跳了1帧以内_不算重锚_超过才算`() {
        val r = PtsReanchor()
        r.onSinkOffset(epochUs)
        assertEquals(0L, feed(r, ptsUsOf(0, 44100, epochUs), 44100, 100))
        // 下一块自称是第 101 帧（差 1）⇒ 不算
        assertEquals(101L, feed(r, ptsUsOf(101, 44100, epochUs), 44100, 100))
        assertEquals(0, r.realignCount)
        // 再下一块跳了 200 帧 ⇒ 算一次
        assertEquals(301L, feed(r, ptsUsOf(301, 44100, epochUs), 44100, 100))
        assertEquals(1, r.realignCount)
    }

    // ── configure / release 的计数语义 ──────────────────────────────────────

    @Test
    fun `换采样率的条目边界会触发configure_但换条目计数不能被清掉`() {
        val r = PtsReanchor()
        r.onSinkOffset(epochUs)
        feed(r, ptsUsOf(0, 44100, epochUs), 44100, 4096)

        // 换条目且换采样率：播放器**先报新偏移，再 configure**
        val off2 = epochUs + 30_000_000L
        r.onSinkOffset(off2)
        assertEquals(1, r.itemChangeCount)

        r.onConfigureReset()
        assertEquals(
            "configure 只重置**每流**指标，不该抹掉会话级的换条目计数" +
                "（换采样率的边界恰好触发 configure，清掉就等于把最该看的那次切换抹掉）",
            1, r.itemChangeCount,
        )
        assertEquals("每流指标要归零", 0, r.realignCount)
        assertEquals(0, r.anchorMissCount)
        assertEquals(-1L, r.absNext)
        assertEquals("偏移必须留着（configure 之前刚报来）", off2, r.offsetUs)
    }

    @Test
    fun `释放时连偏移一起清干净`() {
        val r = PtsReanchor()
        r.onSinkOffset(epochUs)
        feed(r, ptsUsOf(0, 44100, epochUs), 44100, 4096)
        r.onRelease()
        assertEquals("下次是新 sink / 新渲染器，会重新报偏移", -1L, r.offsetUs)
        assertEquals(0, r.itemChangeCount)
        assertEquals(0, r.offsetReportCount)
        assertEquals("偏移清掉后回到 epoch 兜底", 44_100_000_000L, r.offsetFramesFor(44100))
    }
}

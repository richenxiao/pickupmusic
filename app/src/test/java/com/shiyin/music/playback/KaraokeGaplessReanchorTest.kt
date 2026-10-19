package com.shiyin.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 闸门 1 的**端到端**测试：让**真的** [KaraokeMixProcessor] 走一遍「三条目 gapless」的
 * sink 事件序列，断言每次去伴奏缓存取数时用的**绝对源帧号**。
 *
 * 为什么要这一层：[PtsReanchorTest] 只保证算术对；而**块 3 闸门失败的真实形态**是
 * "算术全对，但处理器拿到的时间戳没有扣掉渲染器偏移" —— 那只有把
 * 「sink 投锚点 → 处理器取锚点 → 混音按帧号取伴奏」整条链跑起来才能抓到。
 *
 * `feed()` **完全照 sink 的顺序**：先 `KaraokeBufferAnchor.push(已过帧数, pts)`（这是
 * `KaraokeAnchoringSink.handleBuffer` 干的事），再 `queueInput`。顺序写反了测试就没意义。
 */
class KaraokeGaplessReanchorTest {

    /** 记录每次 `readAbs` 被问到的**首帧号**（= 伴奏源的取值坐标）。 */
    private val asked = ArrayList<Long>()

    private val epochUs = 1_000_000_000_000L

    private fun us(frames: Long, rate: Int = 44100): Long =
        Math.round(frames.toDouble() * 1_000_000.0 / rate)

    private fun proc() = KaraokeMixProcessor(
        initialProbeMode = KaraokeAudioSpike.PROBE_OFF,
        logFile = null,
        captureFile = null,
        anchored = true,
        mixing = true,
    )

    @After
    fun tearDown() {
        // 静态状态复位，避免污染其他测试
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 300
        KaraokeMixControl.realSource = null
        KaraokeBufferAnchor.clear()
        KaraokeBufferAnchor.setOutputOffset(0L)
    }

    /** 进入"伴奏档 + 假伴奏源"状态；假源只记下被问到的帧号。 */
    private fun armFakeAccompaniment() {
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ACCOMP
        KaraokeMixControl.rampMs = 0
        KaraokeMixControl.realSource = AccompanimentSource { absFrame, _, _, _, _ ->
            asked.add(absFrame)
            true
        }
    }

    private fun feed(p: KaraokeMixProcessor, frames: Int, ptsUs: Long) {
        val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { b.putShort(1000).putShort(1000) }
        b.flip()
        assertEquals("帧数换算应当整除", 0, b.remaining() % (p.inputChannelCount * 2))
        KaraokeBufferAnchor.push(p.framesSeen, ptsUs)
        p.queueInput(b)
        p.getOutput()          // 取走输出（与真实管线一致）
    }

    private fun fmt(rate: Int) = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)

    /**
     * 换条目：按源码顺序 —— 先 discontinuity，再新偏移（`handleBuffer` 在其后）。
     *
     * ⚠️ 2026-10-19：条目切换会**解除伴奏源的武装**（修"换曲时漏出上一首开头"）。
     * 这里模拟 app 在 `onTrackStarted` 里的动作：**同一首重播**时缓存目录仍有效 ⇒ 重新武装。
     * "不重新武装会怎样"由 [换条目后不重新挂源_第二条绝不能再读上一首的缓存] 单独钉住。
     */
    private fun changeItem(p: KaraokeMixProcessor, newOffsetUs: Long) {
        p.onSinkDiscontinuity()
        KaraokeBufferAnchor.setOutputOffset(newOffsetUs)
        p.onSinkOffset(newOffsetUs)
        KaraokeMixControl.arm()
    }

    // ── 主用例 ──────────────────────────────────────────────────────────────

    @Test
    fun `采样率不变换条目_第二条伴奏也必须从第0帧取`() {
        armFakeAccompaniment()
        val p = proc()
        p.configure(fmt(44100))
        p.onSinkOffset(epochUs)

        feed(p, 1024, epochUs + us(0))
        feed(p, 1024, epochUs + us(1024))

        // 采样率没变 ⇒ 播放器**不会**再 configure（audioTrackNeedsConfigure=false），
        // framesSeen 跨条目继续累加，这正是最容易把重锚写错的那种边界。
        val off2 = epochUs + 30_000_000L
        changeItem(p, off2)
        feed(p, 1024, off2 + us(0))
        feed(p, 1024, off2 + us(1024))

        assertEquals("第 2 条若沿用旧偏移，这里会变成 1323000（=30s 处）", listOf(0L, 1024L, 0L, 1024L), asked)
        assertEquals(1, p.reanchor.itemChangeCount)
        assertEquals("换条目不该被算成『重锚』", 0, p.realignCount)
    }

    @Test
    fun `换条目后不重新挂源_第二条绝不能再读上一首的缓存`() {
        // **用户 2026-10-19 报的 BUG 的回归测试**：
        // 开着伴奏听完 A、系统进到 B 时，音频线程**已经在跑 B 的前几个块**了 ——
        // 那一刻 realSource 还指向 A，而帧号已经重置成"新条目的第 0 帧"
        // ⇒ 播出的是 A 的开头（用户听到约 0.5 秒，像卡了一下）。
        // 修法：条目一切换，**音频线程立刻**解除武装，与主线程快慢无关。
        armFakeAccompaniment()
        val p = proc()
        p.configure(fmt(44100))
        p.onSinkOffset(epochUs)
        feed(p, 1024, epochUs + us(0))
        feed(p, 1024, epochUs + us(1024))
        assertEquals("第 1 条正常取伴奏", listOf(0L, 1024L), asked)

        // 换条目，但**故意不重新挂源**（模拟主线程还没跑到 onTrackStarted）
        val off2 = epochUs + 30_000_000L
        p.onSinkDiscontinuity()
        KaraokeBufferAnchor.setOutputOffset(off2)
        p.onSinkOffset(off2)
        feed(p, 1024, off2 + us(0))
        feed(p, 1024, off2 + us(1024))

        assertEquals(
            "B 的前几个块绝不能再读 A 的缓存（否则就是那 0.5 秒漏音）",
            listOf(0L, 1024L),
            asked,
        )
    }

    @Test
    fun `换条目且采样率也换_configure归零后第二条仍从第0帧取`() {
        armFakeAccompaniment()
        val p = proc()
        p.configure(fmt(44100))
        p.onSinkOffset(epochUs)
        feed(p, 1024, epochUs + us(0))
        feed(p, 1024, epochUs + us(1024))

        val off2 = epochUs + 30_000_000L
        changeItem(p, off2)
        // 44.1k → 48k 会触发 configure（`audioTrackNeedsConfigure=true`）
        p.configure(fmt(48000))
        feed(p, 1024, off2 + us(0, 48000))
        feed(p, 1024, off2 + us(1024, 48000))

        assertEquals(listOf(0L, 1024L, 0L, 1024L), asked)
        assertEquals(1, p.reanchor.itemChangeCount)
    }

    /**
     * **回归钉**：不报偏移时（偏移永远退化成 epoch 常量），第二条会被算成"从 30 秒处开始"。
     * 这条用例存在的意义是把"为什么必须用播放器报来的偏移"写成可执行的证据 ——
     * 它同时说明：真的走错时，伴奏会去取一个**超出文件长度**的帧号 ⇒ 欠载 ⇒ 退回原曲。
     */
    @Test
    fun `不报偏移时第二条会错到30秒处_这就是必须用播放器偏移的证据`() {
        armFakeAccompaniment()
        val p = proc()
        p.configure(fmt(44100))
        p.onSinkOffset(epochUs)
        feed(p, 1024, epochUs + us(0))

        // 只发 discontinuity + 新 pts，**不发新偏移**（模拟"以为 Media3 不给偏移"）
        val off2 = epochUs + 30_000_000L
        p.onSinkDiscontinuity()
        feed(p, 1024, off2 + us(0))

        assertEquals(
            "第二条第一块被问到了 1323000 帧（30s 处）—— 错得很离谱，且超出短文件长度",
            1_323_000L,
            asked.last(),
        )
    }

    @Test
    fun `seek到中段_下一块必须按新位置取伴奏`() {
        armFakeAccompaniment()
        val p = proc()
        p.configure(fmt(44100))
        p.onSinkOffset(epochUs)
        feed(p, 1024, epochUs + us(0))

        // seek：sink 会 flush（处理器 onFlush 作废自数帧），随后 pts 直接跳到 60s 处
        p.flush()
        val at60s = 44_100L * 60
        feed(p, 1024, epochUs + us(at60s))

        assertEquals(listOf(0L, at60s), asked)
        assertEquals("seek 引起的跳变不算重锚", 0, p.realignCount)
    }

    @Test
    fun `原曲档不碰伴奏源_但帧号状态照常推进`() {
        // 原曲档走透传分支（不调伴奏源）；此时若把重锚也一起停掉，
        // 从原曲切到伴奏档的第一块就会取错位置 —— 这条钉住"重锚与档位无关"。
        armFakeAccompaniment()
        val p = proc()
        p.configure(fmt(44100))
        p.onSinkOffset(epochUs)

        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        feed(p, 1024, epochUs + us(0))
        assertEquals("原曲档不该问伴奏源", 0, asked.size)

        KaraokeMixControl.mode = KaraokeMixControl.MODE_ACCOMP
        feed(p, 1024, epochUs + us(1024))
        assertEquals("切成伴奏档后，帧号必须是接续的 1024", listOf(1024L), asked)
    }

    // ── 锚点队列的生命周期（真机缺陷钉住的回归）────────────────────────────

    @Test
    fun `流内flush不能抹掉sink刚推的锚点`() {
        // **2026-10-16 真机抓到的缺陷**：流水线在**每条流第一个 `handleBuffer` 内部**会再 flush 一次，
        // 而 sink 是先 push 锚点、再进 handleBuffer 的 ⇒ 若 `onFlush` 里清锚点，
        // 每条第一个块 `take(0)` 必然落空 ⇒ `srcFrame=-1` ⇒ 欠载退回原曲（真机日志 `锚缺=1`）。
        val p = proc()
        p.configure(fmt(44100))

        KaraokeBufferAnchor.push(0, epochUs)      // sink 推的锚点
        p.flush()                                 // 流水线紧接着的那次 flush
        assertEquals("flush 后锚点必须还在，否则每条第一个块都丢锚", epochUs, KaraokeBufferAnchor.take(0))
    }

    @Test
    fun `流边界configure要清掉上一条目残留的锚点`() {
        // 换采样率会重建 pipeline ⇒ `framesSeen` 归零；此时若队里还压着**上一条目**的锚点
        // （例如被 Trimming 拆分、最终没走到处理器的那个 buffer），`take(0)` 会撞上它而不命中。
        val p = proc()
        p.configure(fmt(44100))
        KaraokeBufferAnchor.push(9999, epochUs)   // 上一条目的残留
        p.configure(fmt(48000))                   // 新流
        assertEquals("新流里不留上一条目的锚点", -1L, KaraokeBufferAnchor.take(0))
    }
}

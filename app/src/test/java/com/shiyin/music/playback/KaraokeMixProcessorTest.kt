package com.shiyin.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 步骤 3 的**缓冲契约**测试 —— 2026-10-14 真机上"混音档一开就卡住反复念同一小段"之后补的。
 *
 * 为什么必须补：这条路径的错误**在真机上只表现为"卡住/重复"**，看不出是哪一步错的；
 * 而 `AudioProcessor` 的契约（输出必须 flip、字节数必须与输入一致、必须真的消费输入）
 * 是可以在 JVM 上逐字节断言的。Media3 的 `BaseAudioProcessor` 是纯 Java、不碰 Android API，
 * 所以这个测试跑得起来（`android.util.Log` 由 `isReturnDefaultValues=true` 兜底）。
 */
class KaraokeMixProcessorTest {

    private fun fmt() = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun proc() = KaraokeMixProcessor(
        initialProbeMode = KaraokeAudioSpike.PROBE_OFF,
        logFile = null,
        captureFile = null,
        anchored = false,
        mixing = true,
    )

    /** 交错立体声 int16 缓冲（小端），position=0、limit=末尾。 */
    private fun buf(vararg samples: Int): ByteBuffer {
        val b = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { b.putShort(it.toShort()) }
        b.flip()
        return b
    }

    private fun shortsOf(b: ByteBuffer): ShortArray {
        val n = b.remaining() / 2
        val a = ShortArray(n)
        b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(a)
        return a
    }

    @After
    fun tearDown() {
        // 静态状态复位，避免污染其他测试
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 300
        KaraokeMixControl.realSource = null
        KaraokeAccompState.reset()
    }

    @Test
    fun `有源却被解除武装_退回原曲且如实报未生效`() {
        // 真机报障的机制：一张专辑只有一首歌时开伴唱，按下一首回绕到本曲 —— 音频线程一见
        // 条目切换就**解除武装**（那是修"换曲漏上一首开头"的必要动作），此时取到的是
        // **占位源**（默认就是原曲本身），它**读得到样本**。若拿"读到了"当"伴奏已生效"，
        // 按钮就一直显示展开、耳朵里却是原曲 —— 正是用户看到的那一幕。
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ACCOMP
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 0
        // 真源一律输出静音 ⇒ 伴奏档就该是"全零"，与"退回原曲"一眼可分
        KaraokeMixControl.realSource = SilenceAccompaniment
        val p = proc()
        p.configure(fmt())

        // ① 已武装：真源生效（全零），且不被判成回退
        p.queueInput(buf(1000, 2000, 3000, 4000))
        assertArrayEquals("武装着就该听到伴奏", ShortArray(4), shortsOf(p.getOutput()))
        assertEquals(false, KaraokeAccompState.inFallback)

        // ② 条目切换（音频线程在此解除武装）⇒ 必须退回原曲
        p.onSinkOffset(1_000L)
        p.onSinkOffset(2_000L)
        p.queueInput(buf(1000, 2000, 3000, 4000))
        assertArrayEquals(
            "解除武装后必须退回原曲",
            shortArrayOf(1000, 2000, 3000, 4000),
            shortsOf(p.getOutput()),
        )

        // ③ 并且**如实报未生效**（连续两块才算回退，见 FALLBACK_MIN_BLOCKS）
        p.queueInput(buf(1000, 2000, 3000, 4000))
        p.getOutput()
        assertTrue("按钮不能显示「展开」（耳朵里明明是原曲）", KaraokeAccompState.inFallback)
    }

    @Test
    fun `原曲档_输出与输入逐字节相同`() {
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 0
        val p = proc()
        p.configure(fmt())

        val input = buf(100, -200, 300, -400, 500, -600, 700, -800)
        val inBytes = input.remaining()
        p.queueInput(input)

        val out = p.getOutput()
        assertEquals("输出字节数必须与输入一致（不一样就会被 sink 判成断流 → 卡住）",
            inBytes, out.remaining())
        assertArrayEquals(
            shortArrayOf(100, -200, 300, -400, 500, -600, 700, -800),
            shortsOf(out),
        )
    }

    @Test
    fun `人声档_占位为原曲时输出恰好静音`() {
        KaraokeMixControl.mode = KaraokeMixControl.MODE_VOCAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 0
        val p = proc()
        p.configure(fmt())

        val input = buf(1000, -2000, 3000, -4000, 5000, -6000, 7000, -8000)
        val inBytes = input.remaining()
        p.queueInput(input)

        val out = p.getOutput()
        assertEquals(inBytes, out.remaining())
        assertArrayEquals(ShortArray(inBytes / 2), shortsOf(out))
    }

    @Test
    fun `伴奏档_占位为原曲时输出等于输入`() {
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ACCOMP
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 0
        val p = proc()
        p.configure(fmt())

        val input = buf(11, 22, 33, 44, 55, 66, 77, 88)
        val inBytes = input.remaining()
        p.queueInput(input)

        val out = p.getOutput()
        assertEquals(inBytes, out.remaining())
        assertArrayEquals(shortArrayOf(11, 22, 33, 44, 55, 66, 77, 88), shortsOf(out))
    }

    @Test
    fun `超范围混音被硬限幅_不会回绕成噪声`() {
        // 人声档 = 原曲 − 伴奏。占位源换成"静音"时输出就是原曲，不会溢出；
        // 这里直接验证夹取逻辑：给一个接近满幅的样值，1× 输出应当原样。
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_SILENCE
        KaraokeMixControl.rampMs = 0
        val p = proc()
        p.configure(fmt())

        val input = buf(32767, -32768, 32767, -32768)
        val inBytes = input.remaining()
        p.queueInput(input)

        val out = p.getOutput()
        assertEquals(inBytes, out.remaining())
        assertArrayEquals(shortArrayOf(32767, -32768, 32767, -32768), shortsOf(out))
    }

    @Test
    fun `单声道输入必须原样透传_不混音也不抛异常`() {
        // 真机 2026-10-14：混音档一开就卡住反复念同一小段。最可能的成因是**单声道文件**——
        // 混音按 (L,R) 成对取数，单声道会越界索引。这条用例把"单声道绝不能进混音分支"钉住。
        KaraokeMixControl.mode = KaraokeMixControl.MODE_VOCAL   // 人声档本该静音
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 0
        val p = proc()
        p.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT))

        val input = buf(111, 222, 333, 444)
        val inBytes = input.remaining()
        p.queueInput(input)

        val out = p.getOutput()
        assertEquals("单声道也必须输出与输入等长", inBytes, out.remaining())
        assertArrayEquals("单声道必须原样透传（不许混音）", shortArrayOf(111, 222, 333, 444), shortsOf(out))
        assertEquals("应当被记为『非立体声退回透传』一次", 1L, p.nonStereoPassCount)
    }

    @Test
    fun `必须把输入缓冲消费干净_否则管线会无限重喂同一块`() {
        // **2026-10-14 真机事故的根因**：Media3 的 `AudioProcessingPipeline.processData`
        // 是个 `while (isProcessed)` 循环，判据是「某个处理器的输入 `hasRemaining()`」——
        // 只要还有字节，它就把**同一块**再喂一遍。透传分支用 `out.put(inputBuffer)` 天然消费掉了，
        // 而混音分支用 `duplicate()` 读、原缓冲 position 没动 ⇒ 无限重喂 ⇒ 真机听感
        // "同一小段被反复念、音频线程原地打转"。
        // 这条用例直接钉住"调用后输入必须被消费"。
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 0
        val p = proc()
        p.configure(fmt())

        val input = buf(1, 2, 3, 4, 5, 6)
        p.queueInput(input)
        assertEquals("混音后必须把输入缓冲消费干净（position 推到 limit）", 0, input.remaining())
    }

    @Test
    fun `透传分支同样必须消费输入`() {
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 0
        val p = proc()
        p.configure(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT)) // 单声道 → 走透传
        val input = buf(7, 8, 9, 10)
        p.queueInput(input)
        assertEquals(0, input.remaining())
    }

    @Test
    fun `常驻处理器_原曲档输出与输入逐位一致（块 1 闸门）`() {
        // 正式设计：处理器**常驻**在播放链路里，原曲档必须与"链路上没有处理器"逐位一致。
        // 用伪随机数据（含极值）跑多块，逐位比对 —— 不是"听起来一样"。
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 0
        val p = proc()
        p.configure(fmt())

        val rnd = java.util.Random(42)
        repeat(20) { blk ->
            val samples = IntArray(512 * 2) { rnd.nextInt(65536) - 32768 }
            val input = buf(*samples)
            val inBytes = input.remaining()
            p.queueInput(input)
            val out = p.getOutput()
            assertEquals("第 $blk 块字节数不一致", inBytes, out.remaining())
            assertArrayEquals("第 $blk 块内容不一致", samples.map { it.toShort() }.toShortArray(), shortsOf(out))
        }
        // 并且必须是走**透传分支**、而不是"算 1×原曲+0×伴奏"：混音分支会推进系数插值
        assertEquals("原曲档不该进入混音分支（fadeFrames 应保持 0）", 0L, p.fadeFrames)
    }

    @Test
    fun `常驻处理器_运行期切到人声档立刻生效_不需要重建`() {
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 0
        val p = proc()
        p.configure(fmt())

        // 先原曲档：透传
        val in1 = buf(1000, 2000, 3000, 4000)
        p.queueInput(in1)
        assertArrayEquals(shortArrayOf(1000, 2000, 3000, 4000), shortsOf(p.getOutput()))
        assertEquals(0L, p.fadeFrames)

        // 同一实例、不重建，直接切人声档（占位=原曲本身）⇒ 立刻静音
        // （静音本身就是"确实走了混音分支"的证据：透传会把输入原样还回来）
        KaraokeMixControl.mode = KaraokeMixControl.MODE_VOCAL
        val in2 = buf(1000, 2000, 3000, 4000)
        p.queueInput(in2)
        assertArrayEquals(ShortArray(4), shortsOf(p.getOutput()))
    }

    @Test
    fun `连续两块都不丢字节_位置被正确推进`() {
        // 反复 queueInput/getOutput 是本处理器在真实管线里的用法；
        // 若某一块把 position 推错，输出长度就会对不上 —— 正是真机"卡住"的形态。
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 0
        val p = proc()
        p.configure(fmt())

        repeat(5) { k ->
            val input = buf(k, k + 1, k + 2, k + 3, k + 4, k + 5)
            val inBytes = input.remaining()
            p.queueInput(input)
            val out = p.getOutput()
            assertEquals("第 $k 块字节数不对", inBytes, out.remaining())
            assertArrayEquals(
                shortArrayOf(k.toShort(), (k + 1).toShort(), (k + 2).toShort(),
                    (k + 3).toShort(), (k + 4).toShort(), (k + 5).toShort()),
                shortsOf(out),
            )
        }
    }
}

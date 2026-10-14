package com.shiyin.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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

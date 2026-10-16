package com.shiyin.music.data.karaoke.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * P0-1（伴奏升调）后半段的核心件：**跨域重采样**。
 *
 * 这里测的是"**音高**"而不是"长度"—— 升调事故的本质就是长度/时基对不上导致音高变了，
 * 只测"输出够长"是抓不到的。所以主用例是**正弦频率保持**：
 * 44.1kHz 里放一段 1kHz 正弦，重采样到 48k 之后**还必须量出 1kHz**
 * （若直接按 48k 播 44.1k 的数据，会量到 1088 Hz —— 正是 +1.47 半音）。
 */
class SincResamplerTest {

    /** 生成交错立体声正弦（左右同相）。 */
    private fun sine(frames: Int, rate: Int, freq: Double, amp: Double = 0.5): ShortArray {
        val a = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (amp * 32767.0 * sin(2.0 * PI * freq * i / rate)).toInt().toShort()
            a[i * 2] = v; a[i * 2 + 1] = v
        }
        return a
    }

    /** 用**零点穿越计数**估频率（不需要 FFT，够准且没依赖）。 */
    private fun estimateHz(sig: ShortArray, from: Int, to: Int, rate: Int): Double {
        var crossings = 0
        var prev = sig[from * 2]
        for (i in from + 1 until to) {
            val cur = sig[i * 2]
            if ((prev < 0 && cur >= 0) || (prev >= 0 && cur < 0)) crossings++
            prev = cur
        }
        val seconds = (to - from).toDouble() / rate
        return crossings / 2.0 / seconds
    }

    @Test
    fun `恒等比例_逐位一致`() {
        val src = sine(frames = 4096, rate = 44100, freq = 1000.0)
        val dst = ShortArray(src.size)
        SincResampler(44100, 44100).process(src, 0.0, 4096, dst)
        var maxDiff = 0
        for (i in src.indices) maxDiff = maxOf(maxDiff, abs(src[i] - dst[i]).toInt())
        assertTrue("同域重采样必须逐位一致（否则 44.1k 的老行为被改动了），实测最大差 $maxDiff", maxDiff == 0)
    }

    @Test
    fun `向上重采样_音高不变（44_1k 到 48k）`() {
        val rate = 44100
        val frames = 44100                       // 1 秒
        val src = sine(frames, rate, 1000.0)
        val rs = SincResampler(44100, 48000)
        val outFrames = (frames / rs.step).toInt()
        val dst = ShortArray(outFrames * 2)
        rs.process(src, 0.0, outFrames, dst)

        val hz = estimateHz(dst, 4410, outFrames - 4410, 48000)   // 掐头去尾避开边界
        assertTrue(
            "44.1k→48k 之后仍必须是 1kHz；若量到约 1088Hz 就是「按 48k 播 44.1k」的升调 bug（实测 ${"%.1f".format(hz)}Hz）",
            abs(hz - 1000.0) < 15.0,
        )
    }

    @Test
    fun `向上重采样_96k`() {
        val frames = 44100
        val src = sine(frames, 44100, 1000.0)
        val rs = SincResampler(44100, 96000)
        val outFrames = (frames / rs.step).toInt()
        val dst = ShortArray(outFrames * 2)
        rs.process(src, 0.0, outFrames, dst)
        val hz = estimateHz(dst, 9600, outFrames - 9600, 96000)
        assertTrue("44.1k→96k 仍须 1kHz（实测 ${"%.1f".format(hz)}Hz）", abs(hz - 1000.0) < 15.0)
    }

    @Test
    fun `向下重采样_音高不变（44_1k 到 32k）`() {
        val frames = 44100
        val src = sine(frames, 44100, 1000.0)
        val rs = SincResampler(44100, 32000)
        val outFrames = (frames / rs.step).toInt()
        val dst = ShortArray(outFrames * 2)
        rs.process(src, 0.0, outFrames, dst)
        val hz = estimateHz(dst, 3200, outFrames - 3200, 32000)
        assertTrue("44.1k→32k 仍须 1kHz（实测 ${"%.1f".format(hz)}Hz）", abs(hz - 1000.0) < 15.0)
    }

    @Test
    fun `向下重采样_阻带分量被压掉_带内分量保留`() {
        // 32k 的奈奎斯特是 16k：
        //  · 20kHz 深在阻带里 ⇒ 必须被压得很低，否则会折叠成 12kHz 的假音；
        //  · 15kHz 在带内     ⇒ 必须基本原样保留（证明不是"一刀切把高频全砍了"）。
        // 注：像 17kHz 这种"刚过奈奎斯特一点点"的频率，任何**短**滤波器的过渡带都盖不住；
        // 我们真正走的是**上采样**（44.1k 缓存 → ≥44.1k 播放），那里没有折叠风险。
        val frames = 44100
        fun peakAfter(freq: Double): Int {
            val src = sine(frames, 44100, freq)
            val rs = SincResampler(44100, 32000)
            val outFrames = (frames / rs.step).toInt()
            val dst = ShortArray(outFrames * 2)
            rs.process(src, 0.0, outFrames, dst)
            var p = 0
            for (i in outFrames / 5 until outFrames * 4 / 5) p = maxOf(p, abs(dst[i * 2].toInt()))
            return p
        }
        val inPeak = 0.5 * 32767
        val stop = peakAfter(20000.0)
        val pass = peakAfter(15000.0)
        assertTrue("20kHz 必须被压到很低（实测 $stop / ${inPeak.toInt()}）", stop < inPeak * 0.15)
        assertTrue("15kHz 在带内必须保留（实测 $pass / ${inPeak.toInt()}）", pass > inPeak * 0.7)
    }

    @Test
    fun `直流增益为1`() {
        val n = 2048
        val src = ShortArray(n * 2) { 8000 }
        val rs = SincResampler(44100, 48000)
        val outFrames = (n / rs.step).toInt()
        val dst = ShortArray(outFrames * 2)
        rs.process(src, 0.0, outFrames, dst)
        // 取中段（避开窗口边界）
        var min = Int.MAX_VALUE; var max = Int.MIN_VALUE
        for (i in outFrames / 4 until outFrames * 3 / 4) {
            val v = dst[i * 2].toInt()
            min = minOf(min, v); max = maxOf(max, v)
        }
        assertTrue("直流不该被放大或压小（实测 $min..$max，期望≈8000）", min > 7900 && max < 8100)
    }

    @Test
    fun `非法参数立刻报错_不静默降级`() {
        val bad = listOf<() -> Unit>(
            { SincResampler(0, 48000) },
            { SincResampler(44100, 0) },
            { SincResampler(44100, 48000, baseTaps = 3) },
            { SincResampler(44100, 48000, phases = 1) },
        )
        for (f in bad) {
            val threw = runCatching(f).isFailure
            assertTrue("非法参数必须抛异常，而不是静默给个错结果", threw)
        }
    }

    @Test
    fun `输出长度按比例_时长守恒`() {
        val src = sine(frames = 44100, rate = 44100, freq = 440.0)
        val rs = SincResampler(44100, 48000)
        val outFrames = (44100 / rs.step).toInt()
        // 1 秒音频在 48k 下应当是 48000 帧（±1）
        assertTrue("时长必须守恒：1s@44.1k → ${outFrames}f@48k", abs(outFrames - 48000) <= 1)
        // step 是"每个输出采样前进多少输入采样" ⇒ 1/step 才是用户报的那个 1.088 比例
        assertEquals("48k/44.1k = 1.0884（用户报的升调比例）", 1.0884, 1.0 / rs.step, 0.001)
    }
}

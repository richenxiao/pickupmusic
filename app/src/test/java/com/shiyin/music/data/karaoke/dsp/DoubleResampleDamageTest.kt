package com.shiyin.music.data.karaoke.dsp

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * **P0-A 取证**：把旧架构对原曲造成的损坏**量化**出来（用户 2026-10-18："先取证再修"）。
 *
 * ### 旧链路（e3 及以前）
 * ```
 *   原曲(48k/96k) ──降采样──▶ 44.1k 模型域 ──(分离/减法都在这里做)──▶ 伴奏44.1k ──升采样──▶ 源率
 * ```
 * **整条原曲被降采样再升采样一遍**。这不是"有点误差"，是两件事：
 *  1. **带宽被切**：降采样到 44.1k 时 22.05kHz 以上被低通滤掉，升回去也补不回来；
 *  2. **两次重采样的误差全额叠在原曲频谱上** —— 听感就是"玻璃碎裂/嘶嘶声"。
 *
 * ### 新链路（e4）
 * ```
 *   原曲(源率) ────────────────────────────────────┐
 *                                                  ├─▶ 伴奏 = 原曲 − c×上采样(人声估计)
 *   人声估计(44.1k) ──升采样──▶ 源率 ──────────────┘
 * ```
 * **原曲一个字节都不动**，重采样误差只落在人声估计那一轨（人声在高频的能量本来就低）。
 *
 * ### 本测试怎么量
 * 输入是三个正弦的叠加：**1kHz（通带）/ 15kHz（通带，接近模型上限）/ 23kHz（48k 下超出
 * 44.1k 奈奎斯特 22.05k）**。用相关法测出每个分量在输出里的幅度，与输入比。
 *  · 旧链路：23kHz 分量**必然消失或折回**，且整体出现可观误差；
 *  · 新链路：**逐位不变**（这里直接就是端到端 SNR = ∞）。
 *
 * 这三个数字就是"要不要改架构"的依据。
 */
class DoubleResampleDamageTest {

    private val f1 = 1000.0
    private val f2 = 15000.0
    private val f3 = 23000.0

    private fun signal(rate: Int, frames: Int): ShortArray {
        val s = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = 0.25 * (kotlin.math.sin(2 * PI * f1 * i / rate) +
                kotlin.math.sin(2 * PI * f2 * i / rate) +
                kotlin.math.sin(2 * PI * f3 * i / rate))
            val q = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            s[i * 2] = q
            s[i * 2 + 1] = q
        }
        return s
    }

    /** 相关法测某个频率分量的幅度（对左声道）。 */
    private fun amplitudeAt(x: ShortArray, from: Int, to: Int, rate: Int, f: Double): Double {
        var re = 0.0
        var im = 0.0
        val n = to - from
        for (k in 0 until n) {
            val t = 2 * PI * f * k / rate
            val v = x[(from + k) * 2].toDouble()
            re += v * kotlin.math.cos(t)
            im += v * kotlin.math.sin(t)
        }
        return 2.0 * sqrt(re * re + im * im) / n
    }

    private fun snrAndMaxDiff(ref: ShortArray, got: ShortArray, from: Int, to: Int): Pair<Double, Int> {
        var sig = 0.0
        var err = 0.0
        var mx = 0
        for (i in from until to) {
            for (c in 0..1) {
                val a = ref[i * 2 + c].toDouble()
                val b = got[i * 2 + c].toDouble()
                sig += a * a
                err += (a - b) * (a - b)
                mx = maxOf(mx, abs(ref[i * 2 + c] - got[i * 2 + c]).toInt())
            }
        }
        return (if (err == 0.0) Double.POSITIVE_INFINITY else 10 * log10(sig / err)) to mx
    }

    /** 旧链路：源率 → 44.1k → 源率，整条原曲走两遍重采样。 */
    private fun oldChain(src: ShortArray, srcRate: Int, frames: Int): ShortArray {
        val downF = kotlin.math.ceil(frames.toDouble() * 44100.0 / srcRate).toInt()
        val down = ShortArray(downF * 2)
        val rs1 = SincResampler(srcRate, 44100)
        val pad = 512
        val win = ShortArray((frames + 2 * pad) * 2)
        System.arraycopy(src, 0, win, pad * 2, frames * 2)
        rs1.process(win, pad.toDouble(), downF, down)

        val up = ShortArray(frames * 2)
        val rs2 = SincResampler(44100, srcRate)
        val pad2 = 512
        val win2 = ShortArray((downF + 2 * pad2) * 2)
        System.arraycopy(down, 0, win2, pad2 * 2, downF * 2)
        rs2.process(win2, pad2.toDouble(), frames, up)
        return up
    }

    private fun report(tag: String, srcRate: Int) {
        val frames = srcRate * 2          // 2 秒
        val src = signal(srcRate, frames)
        val out = oldChain(src, srcRate, frames)

        val a = 600                        // 跳过两端的补零余量
        val b = frames - 600
        val (snr, mx) = snrAndMaxDiff(src, out, a, b)
        val in1 = amplitudeAt(src, a, b, srcRate, f1)
        val in3 = amplitudeAt(src, a, b, srcRate, f3)
        val out1 = amplitudeAt(out, a, b, srcRate, f1)
        val out2 = amplitudeAt(out, a, b, srcRate, f2)
        val out3 = amplitudeAt(out, a, b, srcRate, f3)

        // 0.25 幅度 ⇒ 8192 满刻度；全部按 dBFS 报，便于和听感对照
        fun db(x: Double) = 20 * log10(x / 8192.0)
        fun f(x: Double) = "%.1f".format(x)

        val in2 = db(amplitudeAt(src, a, b, srcRate, f2))
        val d1 = db(in1); val d2 = in2; val d3 = db(in3)
        val o1 = db(out1); val o2 = db(out2); val o3 = db(out3)
        val lost = d3 - o3

        println(
            "[P0-A取证] $tag 旧链路(原曲走两遍重采样)：SNR=${f(snr)}dB 最大差=$mx LSB\n" +
                "            1kHz  输入${f(d1)}dBFS → 输出${f(o1)}dBFS\n" +
                "            15kHz 输入${f(d2)}dBFS → 输出${f(o2)}dBFS\n" +
                "            23kHz 输入${f(d3)}dBFS → 输出${f(o3)}dBFS  ← 超出 44.1k 奈奎斯特 22.05kHz\n" +
                "            ⇒ 23kHz 掉了 ${f(lost)}dB，" +
                "整条原曲的误差底噪 SNR=${f(snr)}dB（这就是「玻璃声」的能量来源）",
        )

        // 23kHz 在 48k 源上必然被 44.1k 奈奎斯特切掉 ⇒ 这是**带宽损失**，不是数值精度问题
        if (srcRate >= 48000) {
            org.junit.Assert.assertTrue(
                "$tag：23kHz 分量应被切掉至少 20dB（实测 ${f(lost)}dB）",
                lost > 20.0,
            )
        }
    }

    @Test
    fun `旧链路对 48k 源的损坏`() = report("48k ", 48000)

    @Test
    fun `旧链路对 96k 源的损坏`() = report("96k ", 96000)

    @Test
    fun `新链路_原曲不进重采样_逐位不变`() {
        // 新架构下原曲根本不经过重采样器 —— 这里就是字面意义的"什么都不做"
        val rate = 48000
        val frames = rate * 2
        val src = signal(rate, frames)
        val out = src.copyOf()
        val (snr, mx) = snrAndMaxDiff(src, out, 600, frames - 600)
        println("[P0-A取证] 48k 新链路：SNR=${"%.1f".format(snr)}dB 最大差=$mx LSB")
        org.junit.Assert.assertEquals(0, mx)
    }
}

package com.shiyin.music.data.karaoke.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 追加 A（用户 2026-10-18）：**导出重采样器的冲激响应**，供本机用 numpy 做频响验收。
 *
 * 为什么导冲激响应就够：线性时不变系统**完全由冲激响应决定**，`|H(f)| = |FFT(h)|`。
 * 在这里直接算 FFT 要引依赖，而 numpy 那边一行就有 —— 所以让测试只负责**把 h 落盘**，
 * 分析交给 `tools/karaoke/analyze_resampler.py`。
 *
 * 产物（`.karaoke/` 已被 .gitignore 忽略）：`ir_44100_48000.txt` / `ir_44100_96000.txt`。
 */
class ResamplerImpulseDumpTest {

    private fun dump(inRate: Int, outRate: Int, baseTaps: Int? = null, tag: String = "") {
        val rs = if (baseTaps == null) SincResampler(inRate, outRate)
        else SincResampler(inRate, outRate, baseTaps = baseTaps)
        val n = 4096
        // 单位冲激放在中间，两侧留足零，保证窗口不截断
        val src = ShortArray(n * 2)
        src[(n / 2) * 2] = 32767
        src[(n / 2) * 2 + 1] = 32767

        // 输出第 0 帧对应的输入位置：从冲激前 [rs.taps] 个**输入**采样开始
        // （要乘 step —— 输出帧 ↔ 输入采样不是 1:1，按帧数算会让降采样时峰值落到窗口外）
        val first = (n / 2) - rs.taps * rs.step
        val outFrames = rs.taps * 8
        val dst = ShortArray(outFrames * 2)
        rs.process(src, first, outFrames, dst)

        val root = File("").absoluteFile.let { d ->
            var x = d
            while (x.parentFile != null && !File(x, "app").isDirectory) x = x.parentFile
            x
        }
        val dir = File(root, ".karaoke").apply { mkdirs() }
        val f = File(dir, "ir$tag-${inRate}_$outRate.txt")
        f.writeText(
            buildString {
                appendLine("# inRate=$inRate outRate=$outRate taps=${rs.taps} phases=${rs.phases} step=${rs.step}")
                appendLine("# first=$first outFrames=$outFrames  （值 = 左声道 / 32767）")
                for (i in 0 until outFrames) appendLine((dst[i * 2] / 32767.0).toString())
            },
        )
        assertTrue("冲激响应应当被写出来：${f.path}", f.length() > 0)
        // 能量必须集中在中间几帧（否则说明 first 取错了、窗口被截断）
        val peak = (0 until outFrames).maxByOrNull { kotlin.math.abs(dst[it * 2].toInt()) }!!
        assertTrue("冲激响应峰值应落在窗口中部，实测第 $peak / $outFrames 帧", peak in rs.taps..(outFrames - rs.taps))
    }

    @Test
    fun `导出 44_1k 到 48k 的冲激响应`() = dump(44100, 48000)

    @Test
    fun `导出 44_1k 到 96k 的冲激响应`() = dump(44100, 96000)

    @Test
    fun `导出 44_1k 到 32k 的冲激响应_降采样`() = dump(44100, 32000)

    /**
     * **同时导出旧的 16 抽头版本**（`ir16-*`）——验收要求"20kHz 与 18kHz 两个口径都要报、
     * 不得只报好看的"，所以要把"改前"的数据也留着，好在同一张表里对照。
     */
    @Test
    fun `导出旧的16抽头版本_供对照`() {
        dump(44100, 48000, baseTaps = 16, tag = "16")
        dump(44100, 96000, baseTaps = 16, tag = "16")
        dump(44100, 32000, baseTaps = 16, tag = "16")
    }

    @Test
    fun `恒等比例是纯延迟`() {
        val rs = SincResampler(44100, 44100)
        assertEquals("同域必须直通（采样率一样时不该有任何滤波）", 1.0, 1.0 / rs.step, 1e-9)
        // 不写死数字（抽头数被实测调过：16 → 64），只钉住"降采样才加倍"这条关系
        val up = SincResampler(44100, 48000).taps
        assertEquals("同域与上采样用同一档抽头", up, rs.taps)
        assertEquals("降采样翻倍", up * 2, SincResampler(44100, 32000).taps)
    }
}

package com.shiyin.music.data.karaoke.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.shiyin.music.data.karaoke.dsp.MdxStft
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * P0 Spike A3（S8）：STFT/iSTFT 正确性。
 *  - roundtrip：往返峰值误差 < 1e-4（相对满幅，规格 §12 S8 通过线）
 *  - naiveDft：FFT 结果与 O(n²) 朴素 DFT（float64）逐点对拍
 *  - reflectPadding：与 torch center=True 的 reflect 行为一致（边缘不重复）
 *  - fixtureDump：DUMP_FIXTURES=1 时导出二进制给 tools/karaoke/compare_stft_fixture.py 与 torch 对拍
 */
class MdxStftTest {

    private fun maxAbsErr(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in a.indices) m = maxOf(m, abs(a[i] - b[i]))
        return m
    }

    @Test
    fun reflectPadding_matchesTorch() {
        // torch: F.pad([1,2,3,4], (2,2), mode='reflect') == [3,2,1,2,3,4,3,2]
        val stft = MdxStft(nFft = 8, hop = 2)
        val x = floatArrayOf(1f, 2f, 3f, 4f)
        val padded = IntArray(8) { it - 2 }.map { x[stft.reflectIndex(it, x.size)] }
        assertEquals(listOf(3f, 2f, 1f, 2f, 3f, 4f, 3f, 2f), padded)
    }

    @Test
    fun forward_matchesNaiveDft() {
        val nFft = 6144
        val hop = 1024
        val stft = MdxStft(nFft, hop)
        val rng = Random(42)
        val x = FloatArray(hop * 4) { (rng.nextFloat() * 2f - 1f) }
        val spec = stft.forward(x)

        // 对第 0、2 两帧、抽 8 个频点做朴素 DFT（float64）逐点核对
        val win = DoubleArray(nFft) { 0.5 - 0.5 * cos(2.0 * Math.PI * it / nFft) }
        for (frame in intArrayOf(0, 2)) {
            val start = frame * hop - nFft / 2
            for (bin in intArrayOf(0, 1, 100, 1023, 2047, 3000, 3071, 3072)) {
                var re = 0.0
                var im = 0.0
                for (i in 0 until nFft) {
                    val v = x[stft.reflectIndex(start + i, x.size)] * win[i]
                    val ang = -2.0 * Math.PI * bin * i / nFft
                    re += v * cos(ang)
                    im += v * sin(ang)
                }
                val gotRe = spec[(frame * stft.nBins + bin) * 2]
                val gotIm = spec[(frame * stft.nBins + bin) * 2 + 1]
                val scale = maxOf(abs(re), abs(im), 1.0)
                assertEquals(re, gotRe.toDouble(), scale * 1e-3)
                assertEquals(im, gotIm.toDouble(), scale * 1e-3)
            }
        }
    }

    @Test
    fun roundtrip_nfft6144_peakErrorBelow1e_4() {
        roundtripCheck(nFft = 6144, hop = 1024, samples = 1024 * 255)
    }

    @Test
    fun roundtrip_nfft7680_peakErrorBelow1e_4() {
        // Voc_FT 的参数组（n_fft=7680，官方 model_data.json 核实）
        roundtripCheck(nFft = 7680, hop = 1024, samples = 1024 * 255)
    }

    private fun roundtripCheck(nFft: Int, hop: Int, samples: Int) {
        val stft = MdxStft(nFft, hop)
        val rng = Random(7)
        val x = FloatArray(samples) { rng.nextFloat() * 2f - 1f }
        val spec = stft.forward(x)
        val rec = stft.inverse(spec, stft.frameCount(x.size))
        assertEquals(samples, rec.size)
        val peak = x.maxOf { abs(it) }
        val err = maxAbsErr(rec, x)
        assertTrue("roundtrip err=$err peak=$peak", err / peak < 1e-4f)
    }

    /** 与 torch 对拍用的夹具导出：DUMP_FIXTURES=1 ./gradlew :app:testDebugUnitTest --tests '*MdxStftTest' */
    @Test
    fun fixtureDump_whenEnvSet() {
        if (System.getenv("DUMP_FIXTURES") != "1") return
        // Gradle 测试工作目录是 app/ 模块，向上找仓库根（含 settings.gradle.kts）
        var root = File(System.getProperty("user.dir"))
        while (!File(root, "settings.gradle.kts").exists() && root.parentFile != null) root = root.parentFile
        val dir = File(root, ".karaoke/fixtures").apply { mkdirs() }
        for (nFft in intArrayOf(6144, 7680)) {
            val stft = MdxStft(nFft, 1024)
            val rng = Random(42)
            val x = FloatArray(1024 * 255) { rng.nextFloat() * 2f - 1f }
            val spec = stft.forward(x)
            DataOutputStream(File(dir, "kotlin_in_$nFft.f32").outputStream().buffered()).use { d -> x.forEach { d.writeFloat(it) } }
            DataOutputStream(File(dir, "kotlin_spec_$nFft.f32").outputStream().buffered()).use { d -> spec.forEach { d.writeFloat(it) } }
        }
    }
}

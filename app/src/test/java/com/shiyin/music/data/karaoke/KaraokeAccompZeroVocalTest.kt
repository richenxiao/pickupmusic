package com.shiyin.music.data.karaoke

import com.shiyin.music.playback.StemCacheAccompaniment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/**
 * **P0-A 验收判据（用户 2026-10-18）**：「强制人声估计为零 ⇒ 伴奏与原曲逐位一致（0 LSB）」。
 *
 * ### 为什么这一条能定生死
 * 人声模型下 `伴奏 = 原曲 − 补偿系数 × 上采样(人声估计)`。把人声估计**强制成 0**，
 * 右边就只剩 `原曲` —— 于是"伴奏 == 原曲"**逐位成立**当且仅当：
 *  1. 原曲在整条链路上**没有经过任何重采样**（一旦经过，哪怕只是 float 往返也会差 1 LSB）；
 *  2. 合成是在**源采样率域**做的（不跨域就没有域换算误差）。
 *
 * ### 和旧架构的对照（这就是 P0-A 要修的东西）
 * e3 及以前：缓存里存的是 `伴奏_44k = 原曲_44k − 人声×c`，`原曲_44k` 是**降采样**来的；
 * 播放时再把它**升**回源率。等于**整条原曲被降采样再升采样一遍**：
 *  · 48k/96k 源在 22.05kHz 以上的频段被**整段丢掉**；
 *  · 两次重采样的误差**全额叠在原曲频谱上**（听感即"玻璃碎裂/嘶嘶声"）。
 * 旧口径下这个测试只能到 ±1 LSB（44.1k）/ 一个很低的 SNR（48k/96k）——
 * 那些数字当时被当成"胶水的本底噪声"，其实是**架构本身**在损坏原曲。
 *
 * 现在原曲一个字节都没动，所以这里断言的是 **0 LSB，不是"误差很小"**。
 */
class KaraokeAccompZeroVocalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gen = 4096

    /** 写一段交错小端 int16 正弦 PCM。 */
    private fun writePcm(rate: Int, seconds: Double, freq: Double): Pair<File, Int> {
        val frames = (rate * seconds).toInt()
        val f = File(tmp.root, "src_$rate.pcm")
        DataOutputStream(f.outputStream().buffered(1 shl 16)).use { out ->
            val buf = ByteArray(frames * 4)
            var b = 0
            for (i in 0 until frames) {
                val v = (0.6 * 32767.0 * sin(2.0 * PI * freq * i / rate)).toInt()
                buf[b++] = (v and 0xFF).toByte(); buf[b++] = ((v shr 8) and 0xFF).toByte()
                buf[b++] = (v and 0xFF).toByte(); buf[b++] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(buf)
        }
        return f to frames
    }

    private fun readPcm(p: File, frames: Int): ShortArray =
        ShortArray(frames * 2).also { out ->
            val bytes = p.readBytes()
            var b = 0
            for (i in 0 until frames) {
                out[i * 2] = ((bytes[b].toInt() and 0xFF) or ((bytes[b + 1].toInt() and 0xFF) shl 8)).toShort()
                out[i * 2 + 1] = ((bytes[b + 2].toInt() and 0xFF) or ((bytes[b + 3].toInt() and 0xFF) shl 8)).toShort()
                b += 4
            }
        }

    /**
     * 建一份"**人声估计恒为 0**"的缓存，按源帧号读伴奏，返回 (读回的伴奏, 同段原曲)。
     *
     * @param vocalZero true = 模型输出全零（P0-A 判据）；false = 用一段可闻的伪人声，
     *                  用来钉住 `acc = 原曲 − c×w` 的**算术本身**。
     */
    private fun compose(srcRate: Int, seconds: Double, vocalZero: Boolean): Pair<ShortArray, ShortArray> {
        val (pcm, srcFrames) = writePcm(srcRate, seconds, 1000.0)
        val modelFrames = ceil(srcFrames.toDouble() * 44100.0 / srcRate).toInt()

        val cache = StemCache(File(tmp.root, "cache_$srcRate"))
        val key = StemCache.SourceKey("zv$srcRate", srcRate, 2, srcFrames)
        val dir = cache.variantDir(key, "mdx_9482", "1")
        val totalChunks = ceil(modelFrames.toDouble() / gen).toInt()
        for (c in 0 until totalChunks) {
            val from = c * gen
            val cnt = minOf(gen, modelFrames - from)
            val shorts = ShortArray(cnt * 2)
            if (!vocalZero) {
                // 伪人声：**与源同频（1kHz）**、幅度 0.2 —— 同频才能让"减了多少"用幅度直接读出来，
                // 不同频的话两项相位无关，减完的符号是随机的（那样连方向都验不了）。
                for (i in 0 until cnt) {
                    val v = (0.2 * 32767.0 * sin(2.0 * PI * 1000.0 * (from + i) / 44100.0)).toInt()
                    shorts[i * 2] = v.toShort()
                    shorts[i * 2 + 1] = v.toShort()
                }
            }
            cache.writeChunk(dir, c, shorts)
        }
        cache.writeManifest(
            dir, "mdx_9482", "1", key.fingerprint, gen, totalChunks, complete = true,
            sampleRate = 44100, channels = 2, lastAccess = 0L,
            srcRate = srcRate, srcFrames = srcFrames,
            primaryIsVocals = true, compensate = MdxModel.M9482.compensate,
        )

        val acc = StemCacheAccompaniment(cache, dir)
        val want = srcFrames / 2
        val start = srcFrames / 8
        val orig = ShortArray(want * 2)
        val back = ShortArray(want * 2)
        val src = readPcm(pcm, srcFrames)
        System.arraycopy(src, start * 2, orig, 0, want * 2)
        assertTrue("按源帧号读伴奏必须命中", acc.readAbs(start.toLong(), srcRate, orig, back, want))
        return back to orig
    }

    private fun maxDiff(a: ShortArray, b: ShortArray): Int {
        var m = 0
        for (i in a.indices) m = maxOf(m, kotlin.math.abs(a[i] - b[i]).toInt())
        return m
    }

    @Test
    fun `人声估计置零_44k1 源_伴奏与原曲逐位一致`() {
        val (got, orig) = compose(44100, 3.0, vocalZero = true)
        assertEquals("44.1k 同域必须 0 LSB", 0, maxDiff(got, orig))
        assertArrayEquals("逐位相同", orig, got)
    }

    @Test
    fun `人声估计置零_48k 源_伴奏与原曲逐位一致`() {
        val (got, orig) = compose(48000, 3.0, vocalZero = true)
        assertEquals("48k 跨域也必须 0 LSB", 0, maxDiff(got, orig))
        assertArrayEquals("逐位相同", orig, got)
    }

    @Test
    fun `人声估计置零_96k 源_伴奏与原曲逐位一致`() {
        val (got, orig) = compose(96000, 3.0, vocalZero = true)
        assertEquals("96k 跨域也必须 0 LSB", 0, maxDiff(got, orig))
        assertArrayEquals("逐位相同", orig, got)
    }

    @Test
    fun `非零人声估计_合成的是原曲减补偿后的人声`() {
        // 钉住算术本身：`acc = 原曲 − c×上采样(w)`。
        // 源与人声**同频同为 1kHz**，所以减完仍是一个 1kHz 正弦，
        //  源幅度 0.6、人声幅度 0.2 ⇒ 期望输出幅度 = 0.6 − c×0.2。
        // 用 RMS 比值来验（不受相位/起止点影响）。
        val c = MdxModel.M9482.compensate
        val (zero, orig) = compose(48000, 3.0, vocalZero = true)
        val (voiced, _) = compose(48000, 3.0, vocalZero = false)

        fun rms(a: ShortArray): Double {
            var s = 0.0
            for (v in a) s += v.toDouble() * v
            return kotlin.math.sqrt(s / a.size)
        }
        val ratio = rms(voiced) / rms(orig)
        val expect = (0.6 - c * 0.2) / 0.6
        assertTrue(
            "幅度比应≈${"%.3f".format(expect)}（0.6 → 0.6−c×0.2），实测 ${"%.3f".format(ratio)}",
            kotlin.math.abs(ratio - expect) < 0.02,
        )
        assertEquals("人声估计为 0 时比值必须是 1.000（逐位一致）", 1.0, rms(zero) / rms(orig), 1e-6)

        // 被减掉的那一轨 = c×上采样(w)，幅度应在 0.2×1.035×32767 ≈ 6780 附近
        var peak = 0
        for (i in zero.indices) peak = maxOf(peak, kotlin.math.abs(zero[i] - voiced[i]).toInt())
        val expectPeak = (0.2 * c * 32767).toInt()
        assertTrue(
            "被减掉的量级应≈${expectPeak}，实测峰值 $peak",
            peak in (expectPeak * 8 / 10)..(expectPeak * 12 / 10),
        )
    }
}

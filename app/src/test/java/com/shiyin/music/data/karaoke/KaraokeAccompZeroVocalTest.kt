package com.shiyin.music.data.karaoke

import com.shiyin.music.playback.StemCacheAccompaniment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.sin

/**
 * 追加 A 第 3 项（用户 2026-10-18）：**「人声估计置零」测试**。
 *
 * ### 它验的是什么
 * 若模型输出（人声估计）恒为 **0**，则 `伴奏 = 原曲 − 0×compensate` = **原曲本身**。
 * 于是整条链退化成"原曲走一圈再回来"，**任何损失都只能是重采样/域换算造成的** ——
 * 这是把"人声档那点残渣到底有多少来自我们的胶水"**量化**出来的最干净的办法。
 *
 * 链路（与真机一致）：
 * ```
 *   源 PCM(48k/96k) ──PcmFileReader(线性, 源→44.1k)──▶ 44.1k 模型域
 *        └─（人声=0 ⇒ 伴奏=原曲）─▶ 写进 StemCache(44.1k)
 *   播放按源帧号读 ──StemCacheAccompaniment(窗 sinc, 44.1k→源率)──▶ 与源 PCM 逐采样比
 * ```
 *
 * ⚠️ 同域（44.1k 源）必须**逐位一致**；异域给出 SNR(dB) 与最大绝对差。
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

    private fun readPcm(p: File, frames: Int): ShortArray {
        val bytes = p.readBytes()
        val out = ShortArray(frames * 2)
        var b = 0
        for (i in 0 until frames) {
            out[i * 2] = ((bytes[b].toInt() and 0xFF) or ((bytes[b + 1].toInt() and 0xFF) shl 8)).toShort()
            out[i * 2 + 1] = ((bytes[b + 2].toInt() and 0xFF) or ((bytes[b + 3].toInt() and 0xFF) shl 8)).toShort()
            b += 4
        }
        return out
    }

    /** 跑一圈：源 → 44.1k 模型域（线性）→ 缓存 → 按源率读回。返回 (读回的, 源)。 */
    private fun roundTrip(srcRate: Int, seconds: Double): Triple<StemCacheAccompaniment, ShortArray, ShortArray> {
        val (pcm, srcFrames) = writePcm(srcRate, seconds, 1000.0)
        val info = AudioDecoder.PcmFileInfo(pcm, srcRate, srcFrames)
        val modelFrames = ceil(srcFrames.toDouble() * 44100.0 / srcRate).toInt()

        // ① 源 → 44.1k 模型域（这一步就是"人声=0 时"缓存里该有的内容）
        val reader = AudioDecoder.PcmFileReader(info)
        val model = ShortArray(modelFrames * 2)
        var done = 0
        val chunk = ShortArray(gen * 2)
        val chunkL = FloatArray(gen)
        val chunkR = FloatArray(gen)
        while (done < modelFrames) {
            val n = minOf(gen, modelFrames - done)
            reader.readChunk(done, n, chunkL, chunkR)
            for (i in 0 until n) {
                model[(done + i) * 2] = (chunkL[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                model[(done + i) * 2 + 1] = (chunkR[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            done += n
        }
        reader.close()

        // ② 写进缓存（44.1k 模型域）
        val cache = StemCache(File(tmp.root, "cache_$srcRate"))
        val key = StemCache.SourceKey("zv$srcRate", srcRate, 2, srcFrames)
        val dir = cache.variantDir(key, "mdx_9482", "1")
        val totalChunks = ceil(modelFrames.toDouble() / gen).toInt()
        for (c in 0 until totalChunks) {
            val from = c * gen
            val cnt = minOf(gen, modelFrames - from)
            cache.writeChunk(dir, c, model.copyOfRange(from * 2, (from + cnt) * 2))
        }
        cache.writeManifest(
            dir, "mdx_9482", "1", key.fingerprint, gen, totalChunks, complete = true,
            sampleRate = 44100, channels = 2, lastAccess = 0L,
            srcRate = srcRate, srcFrames = srcFrames,
        )

        // ③ 按**源采样率**读回
        val acc = StemCacheAccompaniment(cache, dir)
        val want = srcFrames / 2
        val start = srcFrames / 8
        val orig = ShortArray(want * 2)
        val back = ShortArray(want * 2)
        assertTrue("按源帧号读伴奏必须命中", acc.readAbs(start.toLong(), srcRate, orig, back, want))

        val src = readPcm(pcm, srcFrames)
        val srcSlice = ShortArray(want * 2)
        System.arraycopy(src, start * 2, srcSlice, 0, want * 2)
        return Triple(acc, back, srcSlice)
    }

    private fun report(tag: String, got: ShortArray, want: ShortArray): Pair<Double, Int> {
        var sig = 0.0
        var err = 0.0
        var maxDiff = 0
        for (i in got.indices) {
            val a = want[i].toDouble()
            val b = got[i].toDouble()
            sig += a * a
            err += (a - b) * (a - b)
            maxDiff = maxOf(maxDiff, abs(want[i] - got[i]).toInt())
        }
        val snr = if (err == 0.0) Double.POSITIVE_INFINITY else 10 * log10(sig / err)
        println("[追加A] 人声估计置零 · $tag：SNR=${"%.1f".format(snr)}dB 最大绝对差=$maxDiff " +
            "(满幅 32767 ⇒ ${"%.1f".format(20 * log10(maxOf(maxDiff, 1) / 32767.0))}dBFS)")
        return snr to maxDiff
    }

    @Test
    fun `人声估计置零_44k1 源_同域只差量化噪声`() {
        // ⚠️ 这条链路里 `PcmFileReader` 会把 PCM 走一遍 **float**（`/32768f` 再 `×32767`），
        // 所以**不可能逐位相同**，只能到 ±1 LSB。真正"同域逐位一致"的不变量由
        // `KaraokeSampleRateDomainTest.44k1 源_同域直通_不经过重采样` 负责（那里是纯拷贝）。
        val (_, got, want) = roundTrip(44100, 3.0)
        var maxDiff = 0
        for (i in got.indices) maxDiff = maxOf(maxDiff, abs(want[i] - got[i]).toInt())
        assertTrue("同域只该有 float 往返的 ±1 LSB 量化，实测最大差 $maxDiff", maxDiff <= 1)
        report("44.1k（同域）", got, want)
    }

    @Test
    fun `人声估计置零_48k 源_跨域残差`() {
        val (_, got, want) = roundTrip(48000, 3.0)
        val (snr, maxDiff) = report("48k（跨域）", got, want)
        // 留痕即可，不在测试里定死门槛（门槛由用户按实测判）
        assertTrue("跨域不该是灾难性的（SNR 至少 10dB），实测 ${snr}dB，最大差 $maxDiff", snr > 10.0)
    }

    @Test
    fun `人声估计置零_96k 源_跨域残差`() {
        val (_, got, want) = roundTrip(96000, 3.0)
        val (snr, maxDiff) = report("96k（跨域）", got, want)
        assertTrue("跨域不该是灾难性的（SNR 至少 10dB），实测 ${snr}dB，最大差 $maxDiff", snr > 10.0)
    }
}

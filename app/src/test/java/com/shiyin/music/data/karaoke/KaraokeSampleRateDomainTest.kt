package com.shiyin.music.data.karaoke

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.shiyin.music.playback.StemCacheAccompaniment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * **P0-1「伴奏偶尔升调/像被加速」的回归测试**（48kHz 与 96kHz 走**真实模型路径**）。
 *
 * ### 事故是什么
 * 分离引擎只在 44.1k 模型域里工作，而 `AudioDecoder.PcmFileReader` 的索引空间也是 44.1k。
 * 生产缓存路径却把**源帧数**当模型域帧数传进去（`KaraokeCacheJob`），于是 48k 的歌被按
 * `48000/44100 = 1.0884` 压缩 —— 播放时按源帧号取出来就是**升调 + 加速**
 * （`12·log2(1.0884) ≈ +1.47` 半音，用户实测）。
 * 44.1k 的歌比值恰好 1，所以一直没暴露；"零拍测试"又把伴奏换成原曲本身，绕过了模型路径。
 *
 * ### 这里测什么
 * 不测"长度对不对"，测**音高**：48k / 96k 的源里放 1kHz 正弦，走**真实模型**产出伴奏缓存，
 * 再按源采样率读出来 —— **必须还是 1kHz**。出错时会量到 ~1088Hz / ~2176Hz。
 */
class KaraokeSampleRateDomainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repoRoot(): File {
        var d = File("").absoluteFile
        while (d.parentFile != null && !File(d, "app").isDirectory) d = d.parentFile
        return d
    }

    /** 写一段 [freq] Hz 的 48k/96k 正弦 PCM（交错小端 int16）。 */
    private fun sinePcm(rate: Int, seconds: Double, freq: Double): File {
        val frames = (rate * seconds).toInt()
        val f = File(tmp.root, "sine_${rate}.pcm")
        DataOutputStream(f.outputStream().buffered(1 shl 16)).use { out ->
            val buf = ByteArray(frames * 4)
            var b = 0
            for (i in 0 until frames) {
                val v = (0.5 * 32767.0 * sin(2.0 * PI * freq * i / rate)).toInt()
                buf[b++] = (v and 0xFF).toByte(); buf[b++] = ((v shr 8) and 0xFF).toByte()
                buf[b++] = (v and 0xFF).toByte(); buf[b++] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(buf)
        }
        return f
    }

    /** 读回交错小端 int16 PCM。 */
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

    private fun estimateHz(sig: ShortArray, from: Int, to: Int, rate: Int): Double {        var crossings = 0
        var prev = sig[from * 2]
        for (i in from + 1 until to) {
            val cur = sig[i * 2]
            if ((prev < 0 && cur >= 0) || (prev >= 0 && cur < 0)) crossings++
            prev = cur
        }
        return crossings / 2.0 / ((to - from).toDouble() / rate)
    }

    /**
     * 端到端：源（[srcRate]）→ 真实模型 → 伴奏缓存（44.1k 模型域）→ 按源采样率读回，
     * 断言音高不变、时长守恒。
     */
    private fun assertPitchPreservedThroughModel(srcRate: Int) {
        val root = repoRoot()
        val modelFile = File(root, ".karaoke/models/${MdxModel.M9482.assetName}")
        assumeTrue("需要 .karaoke/models/ 里的 9482 模型", modelFile.isFile)

        val seconds = 3.0
        val srcFrames = (srcRate * seconds).toInt()
        val pcm = sinePcm(srcRate, seconds, 1000.0)
        val info = AudioDecoder.PcmFileInfo(pcm, srcRate, srcFrames)

        // 模型域帧数 = ceil(源帧数 × 44100 / 源率)  ← 这正是被修掉的口径
        val modelFrames = ceil(srcFrames.toDouble() * 44100.0 / srcRate).toInt()
        assertEquals(
            "模型域帧数必须按源率折算；直接拿源帧数就是那个升调 bug",
            (44100 * seconds).toInt(), modelFrames,
        )
        // **时长比必须为 1**：模型域时长 = 源域时长（这是"不加速"的等价说法）
        val durSrc = srcFrames.toDouble() / srcRate
        val durModel = modelFrames.toDouble() / 44100.0
        assertTrue(
            "时长比必须是 1（源 ${"%.6f".format(durSrc)}s vs 模型域 ${"%.6f".format(durModel)}s）；" +
                "旧口径下模型域时长会是源的 ${"%.4f".format(srcRate / 44100.0)} 倍",
            abs(durSrc - durModel) < 0.001,
        )

        val cache = StemCache(File(tmp.root, "cache"))
        val key = StemCache.SourceKey("fp$srcRate", srcRate, 2, srcFrames)
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        env.createSession(modelFile.absolutePath, opts).use { session ->
            val job = SeparationJob(MdxModel.M9482, env, session, cache)
            val res = job.run(key, "1", AudioDecoder.PcmFileReader(info), modelFrames)
            assertTrue("分离应当跑完（未被取消）", !res.cancelled)

            val dir = cache.variantDir(key, MdxModel.M9482.id, "1")
            val m = cache.readManifest(dir)!!
            assertEquals("缓存内容自己必须是 44.1k（模型域）", 44100, m.sampleRate)
            assertEquals("清单要记下源采样率", srcRate, m.srcRate)
            assertEquals("清单要记下源帧数", srcFrames, m.srcFrames)

            // 按**源采样率**读出来 —— 这就是播放路径
            val acc = StemCacheAccompaniment(cache, dir)
            val want = srcFrames / 2
            val start = srcFrames / 8
            // ⚠️ **必须把真正的原曲喂进去**（e4 起）：播放侧合成的是 `原曲 − c×上采样(人声估计)`，
            // 传全零等于量"−c×人声估计"，那就不是这条链路了（旧口径下原曲不参与合成，所以以前能糊弄过去）。
            val src = readPcm(pcm, srcFrames)
            val orig = ShortArray(want * 2)
            System.arraycopy(src, start * 2, orig, 0, want * 2)
            val dst = ShortArray(want * 2)
            assertTrue(
                "按源帧号读伴奏必须命中（未命中说明块覆盖或域换算错了）",
                acc.readAbs(start.toLong(), srcRate, orig, dst, want),
            )
            val hz = estimateHz(dst, want / 10, want - want / 10, srcRate)
            assertTrue(
                "${srcRate}Hz 源走完真实模型后，伴奏仍必须是 1kHz" +
                    "（若量到约 ${"%.0f".format(1000.0 * srcRate / 44100.0)}Hz 就是升调 bug 复发）；" +
                    "实测 ${"%.1f".format(hz)}Hz",
                abs(hz - 1000.0) < 25.0,
            )
        }
    }

    @Test
    fun `44k1 源_走真实模型后伴奏音高不变`() = assertPitchPreservedThroughModel(44100)

    @Test
    fun `48k 源_走真实模型后伴奏音高不变`() = assertPitchPreservedThroughModel(48000)

    @Test
    fun `96k 源_走真实模型后伴奏音高不变`() = assertPitchPreservedThroughModel(96000)

    // ── 不需要模型的域换算测试（随时可跑）────────────────────────────────────

    @Test
    fun `44k1 源_同域直通_不经过重采样`() {
        // 44.1k 源必须走"直通"分支 —— 那是已经验收过的路径，不能被重采样改动。
        // ⚠️ e4 起缓存里存的是**模型那一轨**，伴奏由播放侧合成；这条测试要问的是**域映射有没有重采样**，
        // 所以把清单标成"伴奏模型那一轨"（`primaryIsVocals = false`）⇒ 读出来就是**原样**，
        // 不受合成公式干扰。人声模型那条合成路径由 `KaraokeAccompZeroVocalTest` 单独钉住。
        val cache = StemCache(File(tmp.root, "cache2"))
        val dir = cache.variantDir(StemCache.SourceKey("fp44", 44100, 2, 2000), "m", "1")
        val samples = ShortArray(2000 * 2) { (it % 1000).toShort() }
        cache.writeChunk(dir, 0, samples)
        cache.writeManifest(
            dir, "m", "1", "fp44", genSize = 2000, totalChunks = 1, complete = true,
            sampleRate = 44100, channels = 2, lastAccess = 0L,
            srcRate = 44100, srcFrames = 2000,
            primaryIsVocals = false, compensate = 1f,
        )
        val acc = StemCacheAccompaniment(cache, dir)
        val dst = ShortArray(500 * 2)
        assertTrue(acc.readAbs(100L, 44100, ShortArray(1000), dst, 500))
        for (i in 0 until 500) {
            assertEquals("同域必须逐位直通", samples[(100 + i) * 2], dst[i * 2])
        }
    }

    @Test
    fun `异域时源帧号按比例映射到缓存帧号`() {
        // 缓存（44.1k）里放"帧号阶梯"，按 48k 的源帧号读 ⇒ 每过一个源帧前进 44100/48000 个缓存帧。
        // 同上：标成"伴奏那一轨"以免被合成公式掺进来。
        val cache = StemCache(File(tmp.root, "cache3"))
        val dir = cache.variantDir(StemCache.SourceKey("fp48", 48000, 2, 48000), "m", "1")
        val gen = 4096
        val chunks = 4
        for (c in 0 until chunks) {
            val s = ShortArray(gen * 2)
            for (i in 0 until gen) {
                val frame = c * gen + i
                s[i * 2] = frame.toShort(); s[i * 2 + 1] = frame.toShort()
            }
            cache.writeChunk(dir, c, s)
        }
        cache.writeManifest(
            dir, "m", "1", "fp48", genSize = gen, totalChunks = chunks, complete = true,
            sampleRate = 44100, channels = 2, lastAccess = 0L,
            srcRate = 48000, srcFrames = 48000,
            primaryIsVocals = false, compensate = 1f,
        )
        val acc = StemCacheAccompaniment(cache, dir)
        val dst = ShortArray(4096 * 2)
        assertTrue(acc.readAbs(0L, 48000, ShortArray(8192), dst, 4096))
        // 输出第 1000 个源帧 ≈ 缓存第 1000×44100/48000 = 918.75 帧
        assertEquals("源帧 1000 应对应缓存帧 ≈919（容差 ±2）", 919.0, dst[1000 * 2].toDouble(), 2.0)
    }
}

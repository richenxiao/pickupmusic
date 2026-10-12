package com.shiyin.music.data.karaoke

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

/**
 * v1.5.0 P1：分离任务写缓存——**续算 / 取消 / 落盘内容正确**。
 * 用真实模型 + 现成对拍夹具（.karaoke/），缺失时跳过。
 */
class SeparationJobTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun repoRoot(): File {
        var f = File(System.getProperty("user.dir"))
        while (!File(f, "settings.gradle.kts").exists() && f.parentFile != null) f = f.parentFile
        return f
    }

    private fun readF32BE(f: File): FloatArray {
        val out = FloatArray((f.length() / 4).toInt())
        DataInputStream(f.inputStream().buffered()).use { d -> out.indices.forEach { out[it] = d.readFloat() } }
        return out
    }

    private fun snr(ref: FloatArray, got: FloatArray): Double {
        var sig = 0.0
        var err = 0.0
        for (i in ref.indices) {
            val e = got[i] - ref[i]
            sig += ref[i].toDouble().pow(2)
            err += e.toDouble().pow(2)
        }
        return if (err == 0.0) Double.POSITIVE_INFINITY else 10 * log10(sig / err)
    }

    private fun provider(root: File, input: FloatArray, n: Int): AudioDecoder.PcmFileReader {
        val pcm = File(root, "src.pcm")
        DataOutputStream(pcm.outputStream().buffered()).use { out ->
            for (i in 0 until n) {
                AudioDecoder.writeShortLE(out, (input[i].coerceIn(-1f, 1f) * 32767f).toInt())
                AudioDecoder.writeShortLE(out, (input[n + i].coerceIn(-1f, 1f) * 32767f).toInt())
            }
        }
        return AudioDecoder.PcmFileReader(AudioDecoder.PcmFileInfo(pcm, 44100, n))
    }

    @Test
    fun writesCacheResumesAndCancels() {
        val root = repoRoot()
        val fix = File(root, ".karaoke/fixtures")
        val models = File(root, ".karaoke/models")
        val model = MdxModel.M9482
        assumeTrue(
            "需要 .karaoke 夹具与模型",
            File(fix, "pipe_in.f32").exists() && File(models, model.assetName).exists(),
        )

        val input = readF32BE(File(fix, "pipe_in.f32"))
        val ref = readF32BE(File(fix, "pipe_acc_9482.f32"))
        val n = input.size / 2
        val reader = provider(tmp.root, input, n)
        val cache = StemCache(File(tmp.root, "cache"))

        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        env.createSession(File(models, model.assetName).absolutePath, opts).use { session ->
            val job = SeparationJob(model, env, session, cache)

            // ① 先取消：算几块就停 —— 已写块保留、manifest 不得谎报 complete
            var seen = 0
            val cancelled = job.run(
                1L, "1", "fp", n, reader,
                onProgress = { seen = it.chunkDone },
                isCancelled = { seen >= 3 },
            )
            assertTrue("应在第 3 块后取消", cancelled.cancelled)
            val dir = cancelled.dir
            assertTrue("取消后已写的块要保留", cache.doneChunks(dir) in 1..3)
            assertEquals("取消时 manifest 不能是 complete", false, cache.readManifest(dir)!!.complete)
            assertTrue("取消时按帧读整段必须失败（块不全）", cache.readFrames(dir, 0, n) == null)

            // ② 续算：只补缺的块，最终 complete 且内容与参考一致
            val resumed = job.run(1L, "1", "fp", n, reader)
            assertTrue("续算应复用已有块", resumed.resumedChunks >= 1)
            assertTrue("重跑后应完成", !resumed.cancelled)
            assertTrue("manifest 应标 complete", cache.readManifest(dir)!!.complete)
            assertEquals(job.totalChunksFor(n), cache.doneChunks(dir))

            val pcm = cache.readFrames(dir, 0, n)!!
            // 参考是扁平数组（前半左声道、后半右声道）→ 逐声道比对
            // （`FloatArray + FloatArray` 在 Kotlin 里是**拼接**不是相加，别再踩）
            val gotL = FloatArray(n) { pcm[it * 2] / 32768f }
            val gotR = FloatArray(n) { pcm[it * 2 + 1] / 32768f }
            val refL = ref.copyOfRange(0, n)
            val refR = ref.copyOfRange(n, 2 * n)
            var sig = 0.0; var err = 0.0
            for (i in 0 until n) {
                sig += refL[i].toDouble().pow(2) + refR[i].toDouble().pow(2)
                err += (gotL[i] - refL[i]).toDouble().pow(2) + (gotR[i] - refR[i]).toDouble().pow(2)
            }
            val s = if (err == 0.0) Double.POSITIVE_INFINITY else 10 * log10(sig / err)
            println("[P1] 缓存内容 vs 参考：SNR=${"%.1f".format(s)}dB（16bit 量化上限约 96dB）")
            assertTrue("缓存内容与参考差太多：$s", s >= 40.0)
        }
        reader.close()
    }
}

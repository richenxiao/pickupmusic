package com.shiyin.music.spike.karaoke

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

/**
 * P0 Spike 阶段 B：**手机侧同构的流式路径**端到端对拍——
 * PCM16 临时文件（PcmFileReader）→ MdxSeparator 流式 → 收集输出 → 与 python 参考比 SNR。
 *
 * 这是 2026-10-11 真机 `ArrayIndexOutOfBoundsException` 的事故防线：手机走的就是这条路，
 * 而此前只有"整首驻留内存"的路径有测试。
 */
class StreamingPipelineFileTest {

    private data class Case(val tag: String, val model: MdxModel)

    private val cases = listOf(
        Case("9482", MdxModel.M9482),
        Case("inst_hq_3", MdxModel.INST_HQ_3),
        Case("voc_ft", MdxModel.VOC_FT),
    )

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
        var sig = 0.0; var err = 0.0
        for (i in ref.indices) {
            val e = got[i] - ref[i]
            sig += ref[i].toDouble().pow(2); err += e.toDouble().pow(2)
        }
        return if (err == 0.0) Double.POSITIVE_INFINITY else 10 * log10(sig / err)
    }

    @Test
    fun streamingFilePathMatchesReference() {
        val root = repoRoot()
        val fix = File(root, ".karaoke/fixtures")
        val models = File(root, ".karaoke/models")
        assumeTrue(
            "需要 .karaoke 夹具与模型（py -3 tools/karaoke/make_pipeline_fixture.py）",
            File(fix, "pipe_in.f32").exists() && cases.all { File(models, it.model.assetName).exists() },
        )

        val input = readF32BE(File(fix, "pipe_in.f32"))
        val n = input.size / 2

        // 与手机一致：先落 16bit PCM 文件（这里用源采样率 44.1k，不触发重采样）
        val pcm = File.createTempFile("karaoke_stream", ".pcm").apply { deleteOnExit() }
        DataOutputStream(pcm.outputStream().buffered()).use { out ->
            for (i in 0 until n) {
                // 与生产端同一字节序（AudioDecoder.writeShortLE 小端）
                AudioDecoder.writeShortLE(out, (input[i].coerceIn(-1f, 1f) * 32767f).toInt())
                AudioDecoder.writeShortLE(out, (input[n + i].coerceIn(-1f, 1f) * 32767f).toInt())
            }
        }
        val info = AudioDecoder.PcmFileInfo(pcm, 44100, n)

        val env = OrtEnvironment.getEnvironment()
        try {
            for (c in cases) {
                val refAcc = readF32BE(File(fix, "pipe_acc_${c.tag}.f32"))
                val accL = FloatArray(n); val accR = FloatArray(n)

                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
                env.createSession(File(models, c.model.assetName).absolutePath, opts).use { session ->
                    val reader = AudioDecoder.PcmFileReader(info)
                    try {
                        val sink = object : MdxSeparator.ChunkSink {
                            override fun onChunk(
                                aL: FloatArray, aR: FloatArray,
                                vL: FloatArray, vR: FloatArray,
                                offsetInSong: Int, count: Int,
                            ) {
                                System.arraycopy(aL, 0, accL, offsetInSong, count)
                                System.arraycopy(aR, 0, accR, offsetInSong, count)
                            }
                        }
                        MdxSeparator(c.model, env, session).run(n, reader, sink)
                    } finally {
                        reader.close()
                    }
                }
                val got = accL + accR
                val s = snr(refAcc, got)
                println("[B-file] ${c.tag}: 伴奏 SNR=${"%.1f".format(s)}dB")
                assert(s >= 40.0) { "${c.tag} 流式文件路径 SNR 过低: $s" }
            }
        } finally {
            pcm.delete()
        }
    }
}

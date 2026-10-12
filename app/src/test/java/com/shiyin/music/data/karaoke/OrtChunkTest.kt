package com.shiyin.music.data.karaoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * P0 Spike A4：同一 chunk 在 **onnxruntime-java（JVM）** 上跑三个 MDX-Net 模型，
 * 与 onnxruntime-python 生成的参考输出对拍（tools/karaoke/make_chunk_fixture.py）。
 *
 * 夹具与模型在 .karaoke/（未入库）；缺失时整组测试跳过（不影响其它机器跑全量单测）。
 */
class OrtChunkTest {

    private data class Case(val tag: String, val modelFile: String, val dimF: Int)

    private val cases = listOf(
        Case("9482", "UVR_MDXNET_9482.onnx", 2048),
        Case("inst_hq_3", "UVR-MDX-NET-Inst_HQ_3.onnx", 3072),
        Case("voc_ft", "UVR-MDX-NET-Voc_FT.onnx", 3072),
    )

    private fun repoRoot(): File {
        var f = File(System.getProperty("user.dir"))
        while (!File(f, "settings.gradle.kts").exists() && f.parentFile != null) f = f.parentFile
        return f
    }

    /** 夹具是大端 float32（与 python 端约定一致）。 */
    private fun readF32(f: File): FloatArray {
        val out = FloatArray((f.length() / 4).toInt())
        DataInputStream(f.inputStream().buffered()).use { d -> out.indices.forEach { out[it] = d.readFloat() } }
        return out
    }

    @Test
    fun chunkOutput_matchesPythonReference() {
        val root = repoRoot()
        val fixDir = File(root, ".karaoke/fixtures")
        val modelDir = File(root, ".karaoke/models")
        assumeTrue("需要 .karaoke 夹具与模型（py -3 tools/karaoke/make_chunk_fixture.py）",
            cases.all { File(fixDir, "chunk_out_${it.tag}.f32").exists() && File(modelDir, it.modelFile).exists() })

        val env = OrtEnvironment.getEnvironment()
        for (c in cases) {
            val input = readF32(File(fixDir, "chunk_in_${c.tag}.f32"))
            val ref = readF32(File(fixDir, "chunk_out_${c.tag}.f32"))
            val shape = longArrayOf(1, 4, c.dimF.toLong(), 256)

            env.createSession(File(modelDir, c.modelFile).absolutePath).use { session ->
                OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(input), shape).use { tensor ->
                    val t0 = System.nanoTime()
                    val result = session.run(mapOf("input" to tensor))
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    result.use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val got = (r[0] as OnnxTensor).floatBuffer.let { buf ->
                            FloatArray(buf.remaining()).also { buf.get(it) }
                        }
                        var maxErr = 0f
                        var sigPow = 0.0
                        var errPow = 0.0
                        for (i in got.indices) {
                            val e = abs(got[i] - ref[i])
                            maxErr = maxOf(maxErr, e)
                            sigPow += ref[i].toDouble().pow(2)
                            errPow += e.toDouble().pow(2)
                        }
                        val snr = 10 * log10(sigPow / errPow)
                        println("[A4] ${c.tag}: maxErr=$maxErr snr=${"%.1f".format(snr)}dB infer=${ms}ms (desktop JVM)")
                        // 同引擎同版本族对拍，预期接近位级一致；1e-3 已是非常宽松的线
                        assert(maxErr < 1e-3f) { "${c.tag} maxErr=$maxErr" }
                    }
                }
            }
        }
    }
}

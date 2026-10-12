package com.shiyin.music.data.karaoke

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

/**
 * P0 Spike 阶段 B：整条流水线（分块→STFT→ORT→iSTFT→拼接→残差）与 python 参考对拍。
 *
 * 参考由 tools/karaoke/make_pipeline_fixture.py 生成（同一段 20s 合成音频、同一分块语义）。
 * 通过线：SNR ≥ 40 dB（dev-spec §12 S8 建议值）。
 * 夹具/模型在 .karaoke/（不入库），缺失时跳过。
 */
class MdxSeparatorJvmTest {

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

    private fun readF32(f: File): FloatArray {
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

    @Test
    fun pipelineMatchesPythonReference() {
        val root = repoRoot()
        val fix = File(root, ".karaoke/fixtures")
        val models = File(root, ".karaoke/models")
        assumeTrue(
            "需要 .karaoke 夹具与模型（py -3 tools/karaoke/make_pipeline_fixture.py）",
            File(fix, "pipe_in.f32").exists() && cases.all { File(models, it.model.assetName).exists() },
        )

        val input = readF32(File(fix, "pipe_in.f32"))
        val n = input.size / 2
        val stereo = AudioDecoder.Stereo(
            left = input.copyOfRange(0, n),
            right = input.copyOfRange(n, 2 * n),
            sampleRate = 44100,
        )

        val env = OrtEnvironment.getEnvironment()
        for (c in cases) {
            val refAcc = readF32(File(fix, "pipe_acc_${c.tag}.f32"))
            val refVoc = readF32(File(fix, "pipe_voc_${c.tag}.f32"))

            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            env.createSession(File(models, c.model.assetName).absolutePath, opts).use { session ->
                val stems = MdxSeparator(c.model, env, session).separate(stereo)
                val gotAcc = stems.accL + stems.accR
                val gotVoc = stems.vocL + stems.vocR
                val accSnr = snr(refAcc, gotAcc)
                val vocSnr = snr(refVoc, gotVoc)
                println("[B] ${c.tag}: 伴奏 SNR=${"%.1f".format(accSnr)}dB 人声 SNR=${"%.1f".format(vocSnr)}dB")

                // 两轨各自与参考对齐；40dB 是规格建议线
                assert(accSnr >= 40.0) { "${c.tag} 伴奏 SNR 过低: $accSnr" }
                assert(vocSnr >= 40.0) { "${c.tag} 人声 SNR 过低: $vocSnr" }
            }
        }
    }
}

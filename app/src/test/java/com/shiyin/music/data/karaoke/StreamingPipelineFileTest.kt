package com.shiyin.music.data.karaoke

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assume.assumeTrue
import com.shiyin.music.spike.karaoke.KaraokeStemRunner
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

    /** 与参考扁平数组（前半 L、后半 R）逐声道比 SNR。 */
    private fun snrPerChannel(refFlat: FloatArray, l: FloatArray, r: FloatArray, n: Int): Double {
        var sig = 0.0
        var err = 0.0
        for (i in 0 until n) {
            val eL = l[i] - refFlat[i]
            val eR = r[i] - refFlat[n + i]
            sig += refFlat[i].toDouble().pow(2) + refFlat[n + i].toDouble().pow(2)
            err += eL.toDouble().pow(2) + eR.toDouble().pow(2)
        }
        return if (err == 0.0) Double.POSITIVE_INFINITY else 10 * log10(sig / err)
    }

    /** 用生产代码写 WAV（头 + 小端 16bit），返回字节。 */
    private fun wavBytesOf(l: FloatArray, r: FloatArray, frames: Int): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.write(KaraokeStemRunner.wavHeader(frames))
            // 分块写，避免一次性大数组（与生产同路径）
            val step = 1 shl 16
            var i = 0
            while (i < frames) {
                val cnt = minOf(step, frames - i)
                KaraokeStemRunner.writeChunk(out, l.copyOfRange(i, i + cnt), r.copyOfRange(i, i + cnt), cnt)
                i += cnt
            }
        }
        return bos.toByteArray()
    }

    /** 解析 44 字节头 + 小端 16bit 交错 PCM → float（-1..1）。 */
    private fun parseWavToInterleaved(b: ByteArray): FloatArray {
        require(String(b, 0, 4, Charsets.US_ASCII) == "RIFF") { "不是 WAV（头缺失）" }
        require(String(b, 36, 4, Charsets.US_ASCII) == "data") { "不是 WAV（data 块缺失）" }
        val n = (b.size - 44) / 2
        val out = FloatArray(n)
        var o = 44
        for (i in 0 until n) {
            val lo = b[o].toInt() and 0xFF
            val hi = b[o + 1].toInt() and 0xFF
            val v = (lo or (hi shl 8)).toShort().toInt()   // 小端
            out[i] = v / 32768f
            o += 2
        }
        return out
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
                // 参考是扁平数组：前半左声道、后半右声道。逐声道比对
                // ⚠️ 注意 Kotlin 的 `FloatArray + FloatArray` 是**拼接**不是相加（本测试曾因此比错）
                val s = snrPerChannel(refAcc, accL, accR, n)
                println("[B-file] ${c.tag}: 伴奏 SNR=${"%.1f".format(s)}dB")
                assert(s >= 40.0) { "${c.tag} 流式文件路径 SNR 过低: $s" }

                // 端到端：按导出格式（WAV 头 + 小端 16bit）写出再读回，验证"耳朵听到的那份数据"
                val wavBytes = wavBytesOf(accL, accR, n)
                val back = parseWavToInterleaved(wavBytes)
                val wavL = FloatArray(n) { back[2 * it] }
                val wavR = FloatArray(n) { back[2 * it + 1] }
                val sw = snrPerChannel(refAcc, wavL, wavR, n)
                println("[B-wav] ${c.tag}: 导出 WAV 往返 SNR=${"%.1f".format(sw)}dB")
                assert(sw >= 40.0) { "${c.tag} 导出 WAV SNR 过低（字节序/格式问题）: $sw" }
            }
        } finally {
            pcm.delete()
        }
    }
}

package com.shiyin.music.spike.karaoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.shiyin.music.spike.karaoke.dsp.MdxStft
import java.nio.FloatBuffer

/**
 * P0 Spike 阶段 B：MDX-Net 分离流水线（整首、UVR 经典分块）。
 *
 * 分块方案对齐 UVR/uuv-r-mdx-infer 的经典做法：
 *   trim = n_fft/2；chunk = hop×(dim_t−1)；genSize = chunk − 2×trim
 *   两端补 trim 个零，按 genSize 步进取 chunk，输出掐掉两端 trim 后**首尾相接**。
 * （python-audio-separator 新版另有 overlap=0.25 + hanning 交叉淡化；两者的差异
 *   属阶段 B 待对拍项，见报告。spike 先用经典方案跑通并导出试听。）
 *
 * 人声/伴奏推导（PAS 语义）：
 *   伴奏模型(Inst_HQ_3)：伴奏 = 模型输出；人声 = 原曲 − 伴奏×compensate
 *   人声模型(9482/Voc_FT)：人声 = 模型输出；伴奏 = 原曲 − 人声×compensate
 *
 * 内存：整首驻留（4 分钟歌约 250MB 峰值）——spike 的取舍；正式实现按规格 §5.2
 * 必须改流式（按块解码/推理/写盘）。
 */
class MdxSeparator(
    private val model: MdxModel,
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {
    class Stems(val accL: FloatArray, val accR: FloatArray, val vocL: FloatArray, val vocR: FloatArray)

    class Progress(
        val chunkDone: Int, val chunkTotal: Int,
        val firstChunkMs: Long, val elapsedMs: Long,
        val inferMsTotal: Long,
    )

    fun separate(
        input: AudioDecoder.Stereo,
        onProgress: (Progress) -> Unit = {},
    ): Stems {
        val n = input.left.size
        val trim = model.nFft / 2
        val chunkSize = model.chunkSamples
        val genSize = chunkSize - 2 * trim
        val pad = if (n % genSize == 0) genSize else genSize - (n % genSize)
        val total = (n + pad) / genSize

        val stft = MdxStft(model.nFft, model.hop)
        val nBins = stft.nBins

        val outL = FloatArray(n)
        val outR = FloatArray(n)

        val chunkL = FloatArray(chunkSize)
        val chunkR = FloatArray(chunkSize)
        val tensorBuf = FloatArray(4 * model.dimF * model.dimT)
        val specL = FloatArray(model.dimT * nBins * 2)
        val specR = FloatArray(model.dimT * nBins * 2)
        val shape = longArrayOf(1, 4, model.dimF.toLong(), model.dimT.toLong())

        val t0 = System.nanoTime()
        var firstChunkMs = -1L
        var inferTotal = 0L

        for (chunkIdx in 0 until total) {
            val start = chunkIdx * genSize
            // 按块取样本：两侧共 trim 的零填充用越界判断表达（不整首复制）
            for (j in 0 until chunkSize) {
                val idx = start + j - trim
                val inRange = idx in 0 until n
                chunkL[j] = if (inRange) input.left[idx] else 0f
                chunkR[j] = if (inRange) input.right[idx] else 0f
            }

            packInput(stft, chunkL, chunkR, tensorBuf, model, nBins)

            val tInfer = System.nanoTime()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorBuf), shape).use { t ->
                session.run(mapOf("input" to t)).use { r ->
                    val buf = (r[0] as OnnxTensor).floatBuffer
                    val out = FloatArray(buf.remaining()).also { buf.get(it) }
                    unpackAndIstft(stft, out, specL, specR, model, nBins)
                }
            }
            inferTotal += (System.nanoTime() - tInfer) / 1_000_000

            val waveL = stft.inverse(specL, model.dimT)
            val waveR = stft.inverse(specR, model.dimT)

            // 掐掉两端 trim，首尾相接写入输出
            val base = start // 输出位置 = 该块起点（genSize 对齐）
            for (j in 0 until genSize) {
                val dst = base + j
                if (dst < n) {
                    outL[dst] = waveL[trim + j]
                    outR[dst] = waveR[trim + j]
                }
            }

            val elapsed = (System.nanoTime() - t0) / 1_000_000
            if (chunkIdx == 0) firstChunkMs = elapsed
            onProgress(Progress(chunkIdx + 1, total, firstChunkMs, elapsed, inferTotal))
        }

        // 推导两轨
        val c = model.compensate
        val accL: FloatArray
        val accR: FloatArray
        val vocL: FloatArray
        val vocR: FloatArray
        if (model.predictsInstrumental) {
            accL = outL; accR = outR
            vocL = FloatArray(n) { input.left[it] - outL[it] * c }
            vocR = FloatArray(n) { input.right[it] - outR[it] * c }
        } else {
            vocL = outL; vocR = outR
            accL = FloatArray(n) { input.left[it] - outL[it] * c }
            accR = FloatArray(n) { input.right[it] - outR[it] * c }
        }
        return Stems(accL, accR, vocL, vocR)
    }

    private fun packInput(
        stft: MdxStft,
        l: FloatArray, r: FloatArray,
        dst: FloatArray,
        m: MdxModel, nBins: Int,
    ) {
        val sl = stft.forward(l)
        val sr = stft.forward(r)
        for (t in 0 until m.dimT) {
            for (f in 0 until m.dimF) {
                val src = (t * nBins + f) * 2
                dst[(0 * m.dimF + f) * m.dimT + t] = sl[src]
                dst[(1 * m.dimF + f) * m.dimT + t] = sl[src + 1]
                dst[(2 * m.dimF + f) * m.dimT + t] = sr[src]
                dst[(3 * m.dimF + f) * m.dimT + t] = sr[src + 1]
            }
        }
    }

    private fun unpackAndIstft(
        stft: MdxStft,
        out: FloatArray,
        specL: FloatArray, specR: FloatArray,
        m: MdxModel, nBins: Int,
    ) {
        java.util.Arrays.fill(specL, 0f)
        java.util.Arrays.fill(specR, 0f)
        for (t in 0 until m.dimT) {
            for (f in 0 until m.dimF) {
                specL[(t * nBins + f) * 2] = out[(0 * m.dimF + f) * m.dimT + t]
                specL[(t * nBins + f) * 2 + 1] = out[(1 * m.dimF + f) * m.dimT + t]
                specR[(t * nBins + f) * 2] = out[(2 * m.dimF + f) * m.dimT + t]
                specR[(t * nBins + f) * 2 + 1] = out[(3 * m.dimF + f) * m.dimT + t]
            }
        }
    }
}

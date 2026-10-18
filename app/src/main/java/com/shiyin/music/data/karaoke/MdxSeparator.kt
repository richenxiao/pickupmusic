package com.shiyin.music.data.karaoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.shiyin.music.data.karaoke.dsp.MdxStft
import java.nio.FloatBuffer

/**
 * P0 Spike 阶段 B：MDX-Net 分离流水线（流式核心）。
 *
 * 分块方案对齐 UVR 经典做法：
 *   trim = n_fft/2；chunk = hop×(dim_t−1)；genSize = chunk − 2×trim
 *   两端补零，按 genSize 步进取 chunk，输出掐掉两端 trim 后**首尾相接**。
 *
 * 人声/伴奏（PAS 语义）：
 *   伴奏模型：伴奏 = 模型输出；人声 = 原曲 − 伴奏×compensate
 *   人声模型：人声 = 模型输出；伴奏 = 原曲 − 人声×compensate
 *
 * **流式**：输入由 [ChunkProvider] 按块供给、输出经 [ChunkSink] 逐块交出，
 * 整首不驻留内存（2026-10-11 真机 OOM 后改；规格 §5.2 本就要求如此）。
 * 内存占用 ≈ 一个块（chunk 261120 采样：5.9s 音频）的几个缓冲 ≈ 40~60MB。
 */
class MdxSeparator(
    private val model: MdxModel,
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {
    interface ChunkProvider {
        /**
         * 读入 [count] 帧（44.1k 帧号 [startFrame], 可越界——越界处必须补零，
         * 对应分块的"两端补零"），分别写入 left/right。
         */
        fun readChunk(startFrame: Int, count: Int, left: FloatArray, right: FloatArray)
    }

    interface ChunkSink {
        /** 交出一段成品：[count] 帧，对应歌曲内第 [offsetInSong] 帧起。 */
        fun onChunk(
            accL: FloatArray, accR: FloatArray,
            vocL: FloatArray, vocR: FloatArray,
            offsetInSong: Int, count: Int,
        )
    }

    class Stems(val accL: FloatArray, val accR: FloatArray, val vocL: FloatArray, val vocR: FloatArray)

    class Progress(
        val chunkDone: Int, val chunkTotal: Int,
        val firstChunkMs: Long, val elapsedMs: Long,
        val inferMsTotal: Long,
        /** 本块耗时（含 STFT/iSTFT/推理） */
        val lastChunkMs: Long,
        /** 已完成块的平均耗时 */
        val avgChunkMs: Long,
        /** 已完成块覆盖的音频秒数（= chunkDone × 5.92s） */
        val audioCoveredSec: Float,
        /** 追赶余量（秒）：覆盖的音频时长 − 已用总时长；为正=领先播放，为负=追不上 */
        val leadSec: Float,
    )

    /**
     * 流式跑完一首。
     *
     * @param shouldProcess 返回 false 的块直接跳过（**续算**：缓存里已有的块不重算），进度照常推进。
     * @param isCancelled 块间检查；返回 true 立即停止（已交给 sink 的块由调用方保留）。
     */
    fun run(
        totalFrames: Int,
        provider: ChunkProvider,
        sink: ChunkSink,
        shouldProcess: ((chunkIndex: Int) -> Boolean)? = null,
        isCancelled: (() -> Boolean)? = null,
        /**
         * P0-B：**处理顺序**（块号列表，由 [KaraokeChunkSchedule.order] 给出）。
         * 默认从头顺序；给了就让分离**先算播放头所在的那一块**，算完再绕回开头。
         * 长度与 `total` 不符时**退回从头顺序**（不猜）。
         */
        order: List<Int>? = null,
        // ⚠️ onProgress 必须留在**最后**：既有调用点都把回调写成尾随 lambda，
        // 一旦它后面还有参数，尾随 lambda 会绑到后面的参数上（编译期就炸）。
        onProgress: (Progress) -> Unit = {},
    ): RunSummary {
        val trim = model.nFft / 2
        val chunkSize = model.chunkSamples
        val genSize = chunkSize - 2 * trim
        val total = if (totalFrames <= 0) 0 else {
            val pad = if (totalFrames % genSize == 0) genSize else genSize - (totalFrames % genSize)
            (totalFrames + pad) / genSize
        }

        val stft = MdxStft(model.nFft, model.hop)
        val nBins = stft.nBins

        val chunkL = FloatArray(chunkSize)
        val chunkR = FloatArray(chunkSize)
        val tensorBuf = FloatArray(4 * model.dimF * model.dimT)
        val specL = FloatArray(model.dimT * nBins * 2)
        val specR = FloatArray(model.dimT * nBins * 2)
        val shape = longArrayOf(1, 4, model.dimF.toLong(), model.dimT.toLong())
        val accL = FloatArray(genSize)
        val accR = FloatArray(genSize)
        val vocL = FloatArray(genSize)
        val vocR = FloatArray(genSize)

        val t0 = System.nanoTime()
        var firstChunkMs = -1L
        var inferTotal = 0L
        val c = model.compensate
        // P0-B：按**处理顺序**追加（不是按块号落位）—— 顺序可被重排，只有追加序才是"第几个算完"。
        val chunkTimes = ArrayList<Long>(total)
        val chunkAudioSec = (chunkSize - 2 * trim) / 44100f

        // 顺序与块数不符（调用方传错）时退回从头顺序 —— 宁可慢，不要漏块。
        val seqOrder = order?.takeIf { it.size == total } ?: (0 until total).toList()
        for ((seq, chunkIdx) in seqOrder.withIndex()) {
            if (isCancelled?.invoke() == true) {
                return RunSummary(
                    firstChunkMs = firstChunkMs,
                    totalMs = (System.nanoTime() - t0) / 1_000_000,
                    inferMsTotal = inferTotal,
                    chunkTimesMs = chunkTimes.toLongArray(),
                    chunkAudioSec = chunkAudioSec,
                    cancelled = true,
                )
            }
            val tChunk = System.nanoTime()
            val start = chunkIdx * genSize
            if (shouldProcess?.invoke(chunkIdx) == false) {
                // 续算：缓存里已有这块 → 跳过计算，进度照常推进
                val cov = (seq + 1) * chunkAudioSec
                val el = (System.nanoTime() - t0) / 1_000_000
                onProgress(
                    Progress(
                        chunkDone = seq + 1, chunkTotal = total,
                        firstChunkMs = firstChunkMs, elapsedMs = el,
                        inferMsTotal = inferTotal, lastChunkMs = 0,
                        avgChunkMs = el / (seq + 1),
                        audioCoveredSec = cov, leadSec = cov - el / 1000f,
                    ),
                )
                continue
            }
            // 输入块 = 歌曲 [start − trim, start + genSize + trim)，越界补零
            provider.readChunk(start - trim, chunkSize, chunkL, chunkR)

            packInput(stft, chunkL, chunkR, tensorBuf, nBins)

            val tInfer = System.nanoTime()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorBuf), shape).use { t ->
                session.run(mapOf("input" to t)).use { r ->
                    val buf = (r[0] as OnnxTensor).floatBuffer
                    val out = FloatArray(buf.remaining()).also { buf.get(it) }
                    unpackOutput(out, specL, specR, nBins)
                }
            }
            inferTotal += (System.nanoTime() - tInfer) / 1_000_000

            val waveL = stft.inverse(specL, model.dimT)
            val waveR = stft.inverse(specR, model.dimT)

            // 掐掉两端 trim → genSize 段；同段的"原曲"就是输入块里对应位置
            for (j in 0 until genSize) {
                val wl = waveL[trim + j]
                val wr = waveR[trim + j]
                val ol = chunkL[trim + j]
                val or = chunkR[trim + j]
                if (model.predictsInstrumental) {
                    accL[j] = wl; accR[j] = wr
                    vocL[j] = ol - wl * c; vocR[j] = or - wr * c
                } else {
                    vocL[j] = wl; vocR[j] = wr
                    accL[j] = ol - wl * c; accR[j] = or - wr * c
                }
            }
            val n = minOf(genSize, totalFrames - start)
            if (n > 0) sink.onChunk(accL, accR, vocL, vocR, start, n)

            val elapsed = (System.nanoTime() - t0) / 1_000_000
            val chunkMs = (System.nanoTime() - tChunk) / 1_000_000
            chunkTimes.add(chunkMs)
            if (seq == 0) firstChunkMs = elapsed
            val coveredSec = (seq + 1) * chunkAudioSec
            onProgress(
                Progress(
                    chunkDone = seq + 1, chunkTotal = total,
                    firstChunkMs = firstChunkMs, elapsedMs = elapsed,
                    inferMsTotal = inferTotal,
                    lastChunkMs = chunkMs,
                    avgChunkMs = elapsed / (seq + 1),
                    audioCoveredSec = coveredSec,
                    leadSec = coveredSec - elapsed / 1000f,
                ),
            )
        }
        return RunSummary(
            firstChunkMs = firstChunkMs,
            totalMs = (System.nanoTime() - t0) / 1_000_000,
            inferMsTotal = inferTotal,
            chunkTimesMs = chunkTimes.toLongArray(),
            chunkAudioSec = chunkAudioSec,
        )
    }

    /** 一次跑批的汇总（含每块耗时，用于评估能否赶上播放）。 */
    class RunSummary(
        val firstChunkMs: Long,
        val totalMs: Long,
        val inferMsTotal: Long,
        val chunkTimesMs: LongArray,
        val chunkAudioSec: Float,
        /** 是否因取消提前结束（此时只处理了前 chunkTimesMs.size 块）。 */
        val cancelled: Boolean = false,
    ) {
        val chunkCount: Int get() = chunkTimesMs.size
        val avgChunkMs: Long get() = if (chunkCount == 0) 0 else chunkTimesMs.average().toLong()
        val maxChunkMs: Long get() = chunkTimesMs.maxOrNull() ?: 0
        val minChunkMs: Long get() = chunkTimesMs.minOrNull() ?: 0
        /** 单块耗时超过该块音频时长（即实时追不上）的块数。 */
        val overBudgetChunks: Int get() = chunkTimesMs.count { it > chunkAudioSec * 1000 }
    }

    /** 整首驻留内存的便捷包装（仅 JVM 对拍测试用；手机端走 [run] 流式）。 */
    fun separate(stereo: AudioDecoder.Stereo): Stems {
        val n = stereo.left.size
        val accL = FloatArray(n); val accR = FloatArray(n)
        val vocL = FloatArray(n); val vocR = FloatArray(n)
        val provider = object : ChunkProvider {
            override fun readChunk(startFrame: Int, count: Int, left: FloatArray, right: FloatArray) {
                for (i in 0 until count) {
                    val k = startFrame + i
                    val inRange = k in 0 until n
                    left[i] = if (inRange) stereo.left[k] else 0f
                    right[i] = if (inRange) stereo.right[k] else 0f
                }
            }
        }
        val sink = object : ChunkSink {
            override fun onChunk(
                aL: FloatArray, aR: FloatArray, vL: FloatArray, vR: FloatArray,
                offsetInSong: Int, count: Int,
            ) {
                System.arraycopy(aL, 0, accL, offsetInSong, count)
                System.arraycopy(aR, 0, accR, offsetInSong, count)
                System.arraycopy(vL, 0, vocL, offsetInSong, count)
                System.arraycopy(vR, 0, vocR, offsetInSong, count)
            }
        }
        run(n, provider, sink)
        return Stems(accL, accR, vocL, vocR)
    }

    private fun packInput(stft: MdxStft, l: FloatArray, r: FloatArray, dst: FloatArray, nBins: Int) {
        val sl = stft.forward(l)
        val sr = stft.forward(r)
        for (t in 0 until model.dimT) {
            for (f in 0 until model.dimF) {
                val src = (t * nBins + f) * 2
                dst[(0 * model.dimF + f) * model.dimT + t] = sl[src]
                dst[(1 * model.dimF + f) * model.dimT + t] = sl[src + 1]
                dst[(2 * model.dimF + f) * model.dimT + t] = sr[src]
                dst[(3 * model.dimF + f) * model.dimT + t] = sr[src + 1]
            }
        }
    }

    private fun unpackOutput(out: FloatArray, specL: FloatArray, specR: FloatArray, nBins: Int) {
        java.util.Arrays.fill(specL, 0f)
        java.util.Arrays.fill(specR, 0f)
        for (t in 0 until model.dimT) {
            for (f in 0 until model.dimF) {
                specL[(t * nBins + f) * 2] = out[(0 * model.dimF + f) * model.dimT + t]
                specL[(t * nBins + f) * 2 + 1] = out[(1 * model.dimF + f) * model.dimT + t]
                specR[(t * nBins + f) * 2] = out[(2 * model.dimF + f) * model.dimT + t]
                specR[(t * nBins + f) * 2 + 1] = out[(3 * model.dimF + f) * model.dimT + t]
            }
        }
    }
}

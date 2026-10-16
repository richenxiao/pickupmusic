package com.shiyin.music.data.karaoke

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * v1.5.0 P1：**一首歌的分离任务**——把 [MdxSeparator] 按块算出的伴奏写进 [StemCache]。
 *
 * 与调试页那套一次性跑批的区别（也就是正式实现要满足的四件事）：
 *  1. **可续算**：缓存里已有的块跳过（`shouldProcess`），中断后重跑只补缺的块；
 *  2. **可取消**：块间检查取消标志，立即停手——已写好的块**保留**（用户要求：切歌立即
 *     取消并丢弃半成品；"已完整生成的块保留"）；
 *  3. **原子落盘**：交给 [StemCache.writeChunk]（.tmp → rename），崩溃后读不到半写块；
 *  4. **诚实的状态**：开跑先写 `complete=false`，全部写完后才标 `true`——中途被杀时
 *     manifest 不会谎报可用。
 *
 * 缓存**只存伴奏一轨**（用户约定四）：人声在 P2 由播放侧用 `原曲 − 伴奏` 得到。
 */
class SeparationJob(
    private val model: MdxModel,
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val cache: StemCache,
) {

    class Progress(
        val chunkDone: Int,
        val chunkTotal: Int,
        /** 首块写盘完成时的累计耗时（= "首段可用"延迟，含解码之外的 STFT/推理/iSTFT/写盘）。 */
        val firstChunkMs: Long,
        val elapsedMs: Long,
    )

    class Result(
        val dir: File,
        val chunkCount: Int,
        val firstChunkMs: Long,
        val totalMs: Long,
        /** 本次直接复用、未重算的块数（续算时 > 0）。 */
        val resumedChunks: Int,
        val cancelled: Boolean,
    )

    /** 该模型一块覆盖的帧数（= hop×(dim_t−1) − 2·(n_fft/2)）。 */
    val genSize: Int get() = model.chunkSamples - 2 * (model.nFft / 2)

    fun totalChunksFor(totalFrames: Int): Int {
        if (totalFrames <= 0) return 0
        val gen = genSize
        val pad = if (totalFrames % gen == 0) gen else gen - (totalFrames % gen)
        return (totalFrames + pad) / gen
    }

    /**
     * 跑完（或续算完）一首歌的伴奏。**取消时返回 Result(cancelled = true)**，已写的块保留。
     */
    fun run(
        key: StemCache.SourceKey,
        modelVersion: String,
        provider: MdxSeparator.ChunkProvider,
        /**
         * **模型域帧数**（44.1k）：`ceil(源帧数 × 44100 / 源采样率)`。
         * ⚠️ 绝不能传源帧数 —— `PcmFileReader` 的索引空间是 44.1k，传源帧数会让非 44.1k 的歌
         * 被按 `源率/44100` 压缩（P0-1 升调事故的根因）。
         */
        modelFrames: Int,
        onProgress: (Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Result {
        val gen = genSize
        val total = totalChunksFor(modelFrames)
        val dir = cache.variantDir(key, model.id, modelVersion)
        // 开跑先落一份"未完成"的 manifest：中途被杀时状态是诚实的（不会谎报可用）。
        val already = cache.doneChunks(dir)
        cache.writeManifest(
            dir, model.id, modelVersion, key.fingerprint, gen, total,
            complete = false, lastAccess = System.currentTimeMillis(),
            srcRate = key.sampleRate, srcFrames = key.totalFrames,
        )

        val t0 = System.nanoTime()
        var firstChunkMs = -1L
        val sink = object : MdxSeparator.ChunkSink {
            override fun onChunk(
                accL: FloatArray, accR: FloatArray,
                vocL: FloatArray, vocR: FloatArray,
                offsetInSong: Int, count: Int,
            ) {
                val idx = offsetInSong / gen
                val n = minOf(count, gen)
                val shorts = ShortArray(n * 2)
                for (i in 0 until n) {
                    shorts[i * 2] = toPcm16(accL[i])
                    shorts[i * 2 + 1] = toPcm16(accR[i])
                }
                cache.writeChunk(dir, idx, shorts)
                if (firstChunkMs < 0) firstChunkMs = (System.nanoTime() - t0) / 1_000_000
            }
        }

        val summary = MdxSeparator(model, env, session).run(
            totalFrames = modelFrames,
            provider = provider,
            sink = sink,
            onProgress = { p ->
                onProgress(
                    Progress(
                        chunkDone = p.chunkDone,
                        chunkTotal = p.chunkTotal,
                        firstChunkMs = if (firstChunkMs < 0) -1 else firstChunkMs,
                        elapsedMs = p.elapsedMs,
                    ),
                )
            },
            shouldProcess = { idx -> !cache.hasChunk(dir, idx) },
            isCancelled = isCancelled,
        )

        val done = cache.doneChunks(dir)
        val complete = !summary.cancelled && done >= total
        cache.writeManifest(
            dir, model.id, modelVersion, key.fingerprint, gen, total,
            complete = complete, lastAccess = System.currentTimeMillis(),
            srcRate = key.sampleRate, srcFrames = key.totalFrames,
        )
        return Result(
            dir = dir,
            chunkCount = done,
            firstChunkMs = if (firstChunkMs < 0) summary.firstChunkMs else firstChunkMs,
            totalMs = (System.nanoTime() - t0) / 1_000_000,
            resumedChunks = already,
            cancelled = summary.cancelled,
        )
    }

    private fun toPcm16(v: Float): Short =
        (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
}

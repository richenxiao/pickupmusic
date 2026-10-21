package com.shiyin.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import org.junit.Test

/**
 * **验证"忙循环是极长但会退出，而不是真无限"**（用户 2026-10-21 的更正）。
 *
 * 背景：前两轮网格（1080 + 1350 组合）**一次超时都没有**。
 * `adjustRate` 的结构是
 * ```java
 * moveNewSamplesToPitchBuffer(originalOutputFrameCount);
 * for (int position = 0; position < pitchFrameCount - 1; position++) {
 *   while ((oldRatePosition + 1) * newSampleRate > newRatePosition * oldSampleRate) { ... newRatePosition++; }
 *   ...
 * }
 * ```
 * ⇒ **循环长度随 `pitchFrameCount`/`outputFrameCount`（即已累积的缓冲）增长**。
 *
 * 所以判据不是"会不会卡"，而是 **`queueEndOfStream` 的耗时随喂入总量的增长关系**：
 * - 若近似**线性** ⇒ 就是长循环，真实场景下喂得多就久到像卡死 → 与你"会退出但极长"一致；
 * - 若出现**超线性/不收敛** ⇒ 才是真 bug。
 *
 * 每例仍走守护线程 + 预算，只观测、不掩盖。
 */
class SonicAdjustRateScaleTest {

    private fun pcm(frames: Int, channels: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (sin(i * 0.05) * 12000).toInt().toShort()
            for (c in 0 until channels) b.putShort(v)
        }
        b.flip()
        return b
    }

    /** @return "elapsedMs / status" */
    private fun measure(
        pitch: Float,
        sampleRate: Int,
        channels: Int,
        totalFrames: Int,
        chunk: Int = 65536,
        budgetMs: Long = 20_000,
    ): String {
        val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "sonic").apply { isDaemon = true } }
        try {
            val task = Callable {
                val p = SonicAudioProcessor()
                val fmt = AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
                p.setPitch(pitch)
                p.configure(fmt)
                p.flush()

                var fed = 0
                while (fed < totalFrames) {
                    val n = minOf(chunk, totalFrames - fed)
                    val b = pcm(n, channels)
                    if (b.hasRemaining()) p.queueInput(b)
                    // 正常播放时下游一直在取，这里也持续 drain，避免只堆不取
                    var out = p.getOutput()
                    var spins = 0
                    while (out.hasRemaining() && spins < 200_000) { out = p.getOutput(); spins++ }
                    fed += n
                }
                val t0 = System.nanoTime()   // JVM 单测里 android.os.SystemClock 是 stub，会抛
                p.queueEndOfStream()
                var out = p.getOutput()
                var spins = 0
                while (out.hasRemaining() && spins < 5_000_000) { out = p.getOutput(); spins++ }
                val dt = (System.nanoTime() - t0) / 1_000_000
                "$dt ms (spins=$spins)"
            }
            val f = ex.submit(task)
            return try {
                f.get(budgetMs, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                f.cancel(true); "TIMEOUT >${budgetMs}ms"
            } catch (e: Exception) {
                "THREW ${e.cause?.javaClass?.simpleName}"
            }
        } finally {
            ex.shutdownNow()
        }
    }

    @Test
    fun `queueEndOfStream 耗时随喂入总量的增长关系`() {
        val pitch = 1.3348f   // +5 半音，用户实测的那个值
        println("[scale] pitch=$pitch sr=48000 ch=2 —— queueEndOfStream 耗时")
        for (total in listOf(100_000, 500_000, 2_000_000, 8_000_000, 20_000_000)) {
            val r = measure(pitch, 48000, 2, total)
            println("[scale] 喂入 ${total / 1_000_000.0} M 帧 → $r")
        }
    }

    @Test
    fun `不同 pitch 下同一总量的耗时`() {
        println("[scale] 喂入 8M 帧、sr=48000 ch=2，各 pitch 的 queueEndOfStream 耗时")
        for (hs in listOf(-12, -6, -3, 1, 3, 6, 10, 12)) {
            val pitch = Math.pow(2.0, hs / 24.0).toFloat()
            val r = measure(pitch, 48000, 2, 8_000_000)
            println("[scale] halfSteps=$hs pitch=${"%.4f".format(pitch)} → $r")
        }
    }
}

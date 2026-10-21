package com.shiyin.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * 探针：JVM 上驱动 `SonicAudioProcessor` 的**正确调用顺序**是什么。
 *
 * 第一次跑挂在这里：
 * ```
 * NullPointerException
 *   at Assertions.checkNotNull(Assertions.java:155)
 *   at SonicAudioProcessor.queueInput(SonicAudioProcessor.java:232)
 * ```
 * ⇒ `sonic` 是 null。原因是 `SonicAudioProcessor.onConfigure()` 在
 * **`isActive()` 为假时返回 `AudioFormat.NOT_SET`**（= 本处理器透传、不建 Sonic 实例），
 * 而 `isActive()` = `speed != 1f || pitch != 1f`。所以**先 configure 后 setPitch 会得到
 * 一个未激活的处理器**，再 `queueInput` 就 NPE。
 *
 * ⇒ 正确顺序是 **setPitch/setSpeed → configure → queueInput**。
 * 本探针把两种顺序都跑一遍，把这条**实测**记下来（不靠读文档）。
 */
class SonicProbeTest {

    private fun run(
        pitch: Float,
        frames: Int,
        sampleRate: Int,
        channels: Int,
        pitchBeforeConfigure: Boolean,
        budgetMs: Long = 3000,
    ): String {
        val ex = Executors.newSingleThreadExecutor { r ->
            Thread(r, "sonic-probe").apply { isDaemon = true }
        }
        try {
            val task = Callable {
                val p = SonicAudioProcessor()
                val fmt = AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
                val cfgResult: AudioProcessor.AudioFormat
                if (pitchBeforeConfigure) {
                    p.setPitch(pitch)
                    cfgResult = p.configure(fmt)
                } else {
                    cfgResult = p.configure(fmt)
                    p.setPitch(pitch)
                }
                val active = p.isActive()
                if (!active) return@Callable "INACTIVE(configure=$cfgResult active=$active) → queueInput 会 NPE"

                val bytes = frames * channels * 2
                val buf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
                buf.position(0).limit(bytes)
                p.queueInput(buf)
                p.queueEndOfStream()
                var out = p.getOutput()
                var total = 0
                var spins = 0
                while (out.hasRemaining() && spins < 100_000) {
                    total += out.remaining()
                    out = p.getOutput()
                    spins++
                }
                "OK active=$active outBytes=$total spins=$spins"
            }
            val f = ex.submit(task)
            return try {
                f.get(budgetMs, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                "TIMEOUT(${budgetMs}ms) —— 没返回"
            } catch (e: Exception) {
                "THREW ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}: ${e.cause?.message ?: e.message}"
            }
        } finally {
            ex.shutdownNow()
        }
    }

    @Test
    fun `两种调用顺序对比 —— 先 configure 还是先 setPitch`() {
        val bad = run(1.3348f, 8192, 48000, 2, pitchBeforeConfigure = false)
        val good = run(1.3348f, 8192, 48000, 2, pitchBeforeConfigure = true)
        println("[probe] 先configure后setPitch : $bad")
        println("[probe] 先setPitch后configure : $good")
    }
}

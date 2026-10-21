package com.shiyin.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.sin
import org.junit.Test

/**
 * **升降 Key 卡死的离线复现**（用户 2026-10-21 任务）。Media3 **1.7.1**。
 *
 * 真机根因栈（已取证）：`ExoPlayer:Playback` RUNNABLE 卡在
 * `Sonic.adjustRate ← Sonic.processStreamInput ← Sonic.queueEndOfStream
 *  ← SonicAudioProcessor.queueEndOfStream ← AudioProcessingPipeline.processData
 *  ← DefaultAudioSink.drainToEndOfStream`。
 *
 * ### 1.7.1 的真实状态机（读源码得出）
 * - `Sonic` 实例在 **`flush()`** 里创建，不在 `configure()`：
 *   `flush() { if (isActive()) { ... if (pendingSonicRecreation) sonic = new Sonic(...) ... } }`
 *   ⇒ 正确序列 **setPitch → configure → flush → queueInput → queueEndOfStream → getOutput**。
 * - `setPitch/setSpeed` 只置 `pendingSonicRecreation = true`，**不立刻重建**。
 * - `isActive()` = `pendingOutput.sampleRate != NO_VALUE &&
 *    (shouldBeActiveWithDefaultParameters || !areParametersSetToDefaultValues())`
 *   ⇒ **pitch 回到 1.0 会让处理器变 inactive，而 `sonic` 字段仍留着旧实例**；
 *   而 `queueEndOfStream()` 是 `if (sonic != null) sonic.queueEndOfStream()`。
 *   源码自带 `TODO(internal b/174554082)`，Google 也知道这块脆。
 *
 * ### 2. 第一版网格的教训（已修正）
 * 第一版 1080 组合 **0 超时 / 600 个 NPE** —— NPE 全是**我方用例顺序错**：
 * 变体 B/C 从 `pitch = 1.0` 起步 ⇒ 处理器 inactive ⇒ `sonic` 从未创建 ⇒
 * `queueInput` 里 `checkNotNull(sonic)` 抛 NPE。
 * **真机上起步时 Key 已经压着（pitch≠1），处理器是 active 的**
 * ⇒ 现在所有变体都从 **非默认 pitch 起步**，保证 `sonic` 已存在。
 *
 * ### 3. 新增变体 E（最贴近 ExoPlayer 真实行为）
 * ExoPlayer 在 `playbackParameters` 变化时会 **flush 音频处理器**；
 * 连点 Key = 反复 `setPitch + flush`。E 就是这条路径，重复 K 次。
 */
class SonicAdjustRateReproTest {

    private val budgetMs = 2500L

    enum class Variant {
        /** (a) 开始时 setPitch 再喂数据。 */
        A_SETPITCH_FIRST,

        /** (b) 喂一段后 setPitch（**不 flush**）再继续喂。 */
        B_SETPITCH_MIDSTREAM,

        /** (c) setPitch 后**立刻** queueEndOfStream。 */
        C_PITCH_THEN_EOS,

        /** (d) 从变调**切回 1.0**（原调）后 queueEndOfStream。 */
        D_BACK_TO_DEFAULT_THEN_EOS,

        /** (e) 贴近真机：每次 setPitch 后 **flush**（ExoPlayer 就是这么做的），反复多次。 */
        E_FLUSH_PER_CHANGE,
    }

    private data class Case(
        val pitch: Float,
        val pitch2: Float,
        val sampleRate: Int,
        val channels: Int,
        val frames: Int,
        val variant: Variant,
        val repeats: Int = 12,
    ) {
        fun label() = "p0=${"%.4f".format(pitch)} p1=${"%.4f".format(pitch2)} " +
            "sr=$sampleRate ch=$channels frames=$frames ${variant.name}" +
            if (variant == Variant.E_FLUSH_PER_CHANGE) " repeats=$repeats" else ""
    }

    private data class Outcome(val status: String, val detail: String = "")

    /** 有内容的 PCM（正弦），不用静音 —— 万一 findPitchPeriod 之类对内容敏感。 */
    private fun pcm(frames: Int, channels: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (sin(i * 0.05) * 12000).toInt().toShort()
            for (c in 0 until channels) b.putShort(v)
        }
        b.flip()
        return b
    }

    private fun drive(c: Case) {
        val p = SonicAudioProcessor()
        val fmt = AudioProcessor.AudioFormat(c.sampleRate, c.channels, C.ENCODING_PCM_16BIT)
        // 所有变体都从**非默认 pitch** 起步 ⇒ 处理器 active ⇒ sonic 一定已创建（修正第一版的错误）
        val p0 = if (c.variant == Variant.A_SETPITCH_FIRST) c.pitch else 1.3348f

        fun begin(pitchNow: Float) {
            p.setPitch(pitchNow)
            p.configure(fmt)
            p.flush()
        }
        fun feed(n: Int) {
            val b = pcm(n, c.channels)
            if (b.hasRemaining()) p.queueInput(b)
        }
        fun drain() {
            var out = p.getOutput()
            var spins = 0
            while (out.hasRemaining() && spins < 500_000) { out = p.getOutput(); spins++ }
        }

        begin(p0)
        feed(c.frames)

        when (c.variant) {
            Variant.A_SETPITCH_FIRST -> {
                p.queueEndOfStream(); drain()
            }
            Variant.B_SETPITCH_MIDSTREAM -> {
                p.setPitch(c.pitch2)          // 不 flush
                feed(c.frames); p.queueEndOfStream(); drain()
            }
            Variant.C_PITCH_THEN_EOS -> {
                p.setPitch(c.pitch2)
                p.queueEndOfStream(); drain()
            }
            Variant.D_BACK_TO_DEFAULT_THEN_EOS -> {
                p.setPitch(1.0f)              // 回原调 ⇒ isActive() 变假，sonic 仍非空
                p.queueEndOfStream(); drain()
            }
            Variant.E_FLUSH_PER_CHANGE -> {
                // ExoPlayer 路径：连点 = 反复 setPitch + flush
                for (i in 0 until c.repeats) {
                    p.setPitch(if (i % 2 == 0) c.pitch else c.pitch2)
                    p.flush()
                    feed(c.frames / 4 + 1)
                }
                p.queueEndOfStream(); drain()
            }
        }
    }

    private fun runCase(c: Case): Outcome {
        val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "sonic").apply { isDaemon = true } }
        return try {
            val f = ex.submit(Callable { drive(c); "OK" })
            try {
                Outcome(f.get(budgetMs, TimeUnit.MILLISECONDS))
            } catch (e: java.util.concurrent.TimeoutException) {
                f.cancel(true); Outcome("TIMEOUT", ">${budgetMs}ms 未返回")
            } catch (e: Exception) {
                val cause = e.cause
                Outcome("THREW", "${cause?.javaClass?.simpleName}: ${cause?.message}")
            }
        } finally {
            ex.shutdownNow()
        }
    }

    @Test
    fun `参数网格扫描_找出不返回的组合`() {
        val halfSteps = listOf(-12, -10, -6, -3, 0, 3, 6, 10, 12)
        val rates = listOf(44100, 48000, 96000)
        val chans = listOf(1, 2)
        val framesList = listOf(1, 32, 1024, 8192, 65536)

        var total = 0
        val byVariant = LinkedHashMap<String, IntArray>()   // [ok, timeout, threw]
        val bad = mutableListOf<Pair<Case, Outcome>>()
        val thrown = mutableListOf<Pair<Case, Outcome>>()

        for (hs in halfSteps) {
            val p1 = 2.0.pow(hs / 24.0).toFloat()
            for (sr in rates) for (ch in chans) for (fr in framesList) {
                for (v in Variant.entries) {
                    val c = Case(pitch = p1, pitch2 = if (hs >= 0) 0.7071f else 1.4142f, sampleRate = sr, channels = ch, frames = fr, variant = v)
                    total++
                    val o = runCase(c)
                    val slot = byVariant.getOrPut(v.name) { IntArray(3) }
                    when (o.status) {
                        "OK" -> slot[0]++
                        "TIMEOUT" -> { slot[1]++; bad += c to o }
                        else -> { slot[2]++; thrown += c to o }
                    }
                }
            }
        }

        println("[sonic] ==== 共跑 $total 个组合 ====")
        println("[sonic] 超时(不返回) ${bad.size} 个；抛异常 ${thrown.size} 个")
        byVariant.forEach { (k, v) -> println("[sonic]   $k: ok=${v[0]} timeout=${v[1]} threw=${v[2]}") }
        bad.take(60).forEach { (c, o) -> println("[sonic] TIMEOUT  ${c.label()}  ${o.detail}") }
        thrown.take(20).forEach { (c, o) -> println("[sonic] THREW    ${c.label()}  ${o.detail}") }
    }
}

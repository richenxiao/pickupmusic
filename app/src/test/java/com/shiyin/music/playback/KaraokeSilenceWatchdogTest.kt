package com.shiyin.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 闸门 3 / A74「伴奏突然放着就没声了」—— **静音看门狗的注入测试**。
 *
 * 一个只能"事后看日志"的看门狗，最怕的是**不响**或**乱响**。所以两类都要注入：
 *
 * | 用例 | 注入 | 期望 |
 * |---|---|---|
 * | 伴奏档 · 输出全零而原曲有声 | 假伴奏源恒返回 **全零** | **必须触发** |
 * | 真静音段 | 原曲峰值 ≤ 64（阈下） | 不得误报 |
 * | 人声档 · 输出为零 | 伴奏=原曲 ⇒ 人声恒 0 | 不得误报（**该档位零输出是正常的**） |
 * | 欠载回退成原曲 | 假伴奏源恒返回 **false**（欠载） | 不得误报（输出=原曲，听着有声） |
 * | 淡化途中 | rampMs=300、零伴奏，只喂到淡化结束前 | 不得误报 |
 * | 短于阈值 | 零输出只持续 ~280ms | 不得误报 |
 *
 * 同时断言告警**真的写进了日志文件**（看门狗的价值全在"事后能取到"）。
 */
class KaraokeSilenceWatchdogTest {

    private lateinit var logFile: File

    @After
    fun tearDown() {
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ORIGINAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        KaraokeMixControl.rampMs = 300
        KaraokeMixControl.realSource = null
    }

    private fun proc(rampMs: Int, log: File): KaraokeMixProcessor {
        KaraokeMixControl.rampMs = rampMs
        logFile = log
        return KaraokeMixProcessor(
            initialProbeMode = KaraokeAudioSpike.PROBE_OFF,
            logFile = log,
            captureFile = null,
            anchored = false,      // 锚定与否不影响看门狗
            mixing = true,
        )
    }

    private fun freshLog(): File =
        File.createTempFile("karaoke_watchdog", ".log").apply { delete() }

    /** 喂一块：`amp` = 每个采样的幅值（0 = 真静音）。 */
    private fun feed(p: KaraokeMixProcessor, blocks: Int, amp: Int, frames: Int = 4096) {
        repeat(blocks) {
            val b = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { b.putShort(amp.toShort()).putShort(amp.toShort()) }
            b.flip()
            p.queueInput(b)
            p.getOutput()
        }
    }

    private fun fmt() = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)

    private fun zeroAcc() = AccompanimentSource { _, _, _, dst, frames ->
        java.util.Arrays.fill(dst, 0, frames * 2, 0.toShort()); true
    }

    private fun underrunAcc() = AccompanimentSource { _, _, _, _, _ -> false }

    // ── 正例：必须触发 ──────────────────────────────────────────────────────

    @Test
    fun `伴奏档_输出全零而原曲有声_必须触发并落盘`() {
        val log = freshLog()
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ACCOMP
        KaraokeMixControl.realSource = zeroAcc()
        val p = proc(rampMs = 0, log = log)
        p.configure(fmt())

        feed(p, blocks = 8, amp = 1000)      // 8 块 ≈ 744ms > 500ms 阈值

        assertEquals("必须触发一次", 1, p.silenceEvents)
        val text = log.readText()
        assertTrue("告警必须写进日志文件（事后只剩这一条线索）", text.contains("静音看门狗"))
        assertTrue("告警要带上『真没声』这个判据", text.contains("真没声"))
        assertTrue("告警要带原曲峰值，便于判断阈值是否合理", text.contains("原曲峰值=1000"))
        log.delete()
    }

    // ── 反例：都不得误报 ────────────────────────────────────────────────────

    @Test
    fun `真静音段_不得误报`() {
        val log = freshLog()
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ACCOMP
        KaraokeMixControl.realSource = zeroAcc()
        val p = proc(rampMs = 0, log = log)
        p.configure(fmt())

        // 原曲本身就在阈值以下（歌里的静音段/间奏）—— 输出为 0 理所应当
        feed(p, blocks = 20, amp = 50)

        assertEquals(0, p.silenceEvents)
        assertFalse(log.exists() && log.readText().contains("静音看门狗"))
    }

    @Test
    fun `人声档_输出为零是正常的_不得误报`() {
        val log = freshLog()
        // 人声档 + 伴奏=原曲本身 ⇒ 人声 = 原曲 − 原曲 ≡ 0，而原曲很响。
        // 这正是"间奏没人声"的形态 —— 在那个档位上判定会满屏误报，所以看门狗只在伴奏档生效。
        KaraokeMixControl.mode = KaraokeMixControl.MODE_VOCAL
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_IDENTITY
        val p = proc(rampMs = 0, log = log)
        p.configure(fmt())

        feed(p, blocks = 20, amp = 1000)

        assertEquals("人声档的零输出是正常现象，绝不能报", 0, p.silenceEvents)
    }

    @Test
    fun `欠载回退成原曲_不得误报`() {
        val log = freshLog()
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ACCOMP
        KaraokeMixControl.realSource = underrunAcc()   // 恒欠载 ⇒ 内部退回原曲
        val p = proc(rampMs = 0, log = log)
        p.configure(fmt())

        feed(p, blocks = 20, amp = 1000)

        assertEquals("欠载时输出=原曲（听着还有声），不是『真没声』", 0, p.silenceEvents)
        assertEquals("但要记在欠载计数里", 1L, p.underrunCount)
    }

    @Test
    fun `淡化途中_不得误报`() {
        val log = freshLog()
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ACCOMP
        KaraokeMixControl.realSource = zeroAcc()
        val p = proc(rampMs = 300, log = log)      // 300ms 淡化
        p.configure(fmt())

        // 只喂 200ms：淡化还没走完，输出 = go·原曲 ≠ 0
        feed(p, blocks = 2, amp = 1000, frames = 4410)   // ≈200ms

        assertEquals("淡化途中输出不是 0，不该报", 0, p.silenceEvents)
    }

    @Test
    fun `零输出短于阈值_不得误报`() {
        val log = freshLog()
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ACCOMP
        KaraokeMixControl.realSource = zeroAcc()
        val p = proc(rampMs = 0, log = log)
        p.configure(fmt())

        feed(p, blocks = 3, amp = 1000)     // 3 块 ≈ 279ms < 500ms

        assertEquals(0, p.silenceEvents)
    }

    @Test
    fun `恢复有声之后_计数要归零而不是累加`() {
        val log = freshLog()
        KaraokeMixControl.mode = KaraokeMixControl.MODE_ACCOMP
        KaraokeMixControl.sourceKind = KaraokeMixControl.SRC_TONE   // 伴奏=220Hz 纯音（非零）
        val p = proc(rampMs = 0, log = log)
        p.configure(fmt())

        feed(p, blocks = 3, amp = 1000)    // 零伴奏前先来一段有声的
        assertEquals(0, p.silenceEvents)
        // 换成恒零伴奏 ⇒ 应当从"当前已连续 0 帧"重新起算，而不是把前面那几块也算进去
        KaraokeMixControl.realSource = zeroAcc()
        feed(p, blocks = 5, amp = 1000)    // 5 块 ≈ 465ms < 500ms（若把前面算进去就会误报）
        assertEquals("连续计数必须在恢复有声时清零", 0, p.silenceEvents)
    }
}

package com.shiyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 升降 Key 卡死看门狗的**判据**单测（用户 2026-10-20「修改0」）。
 *
 * 看门狗只诊断、不自愈，所以这里验证的是"什么时候该抓栈、什么时候**不该**误报" ——
 * 误报会把真卡死的信号淹没，漏报会让取证空手而归，两者都要挡住。
 *
 * 时钟由构造参数注入，所以整个测试是纯 JVM、无需设备。
 */
class KeyStallWatchTest {

    private var clock = 0L
    private fun watch() = KeyStallWatch { clock }

    private fun KeyStallWatch.tickAt(pos: Long, t: Long, playing: Boolean = true, ready: Boolean = true): Boolean {
        clock = t
        return tick(pos, playWhenReady = playing, ready = ready)
    }

    @Test
    fun `未武装时不触发_即使位置一直不动`() {
        val w = watch()
        assertFalse(w.tickAt(pos = 1000, t = 0))
        assertFalse(w.tickAt(pos = 1000, t = 10_000))
    }

    @Test
    fun `位置正常前进永远不触发`() {
        val w = watch()
        w.arm(0)
        var pos = 0L
        var t = 0L
        repeat(50) {
            pos += 300
            t += 300
            assertFalse("t=$t pos=$pos 不该触发", w.tickAt(pos, t))
        }
    }

    @Test
    fun `位置冻结满2秒触发一次`() {
        val w = watch()
        w.arm(-10)
        assertFalse(w.tickAt(pos = 5000, t = 0))       // 第一次见到该位置：开始计时
        assertFalse(w.tickAt(pos = 5000, t = 1000))    // 才 1 秒
        assertTrue(w.tickAt(pos = 5000, t = 2000))     // 满 2 秒 → 抓栈
    }

    @Test
    fun `同一个观测窗口只报一次`() {
        val w = watch()
        w.arm(-10)
        w.tickAt(5000, 0)
        assertTrue(w.tickAt(5000, 2000))
        assertFalse("已 fired，不该重复抓", w.tickAt(5000, 9000))
    }

    @Test
    fun `暂停时位置不动属于正常_不许误报`() {
        val w = watch()
        w.arm(-10)
        assertFalse(w.tickAt(5000, 0, playing = false))
        assertFalse(w.tickAt(5000, 10_000, playing = false))
    }

    @Test
    fun `引擎未就绪时位置不动属于正常_不许误报`() {
        val w = watch()
        w.arm(-10)
        assertFalse(w.tickAt(0, 0, ready = false))
        assertFalse(w.tickAt(0, 10_000, ready = false))
    }

    @Test
    fun `中途恢复前进_冻结计时重新开始_不因历史累积误报`() {
        val w = watch()
        w.arm(0)
        assertFalse(w.tickAt(1000, 0))       // 冻结开始
        assertFalse(w.tickAt(1000, 1500))    // 1.5s，还没到
        assertFalse(w.tickAt(1300, 1600))    // 恢复前进 → 重置
        assertFalse(w.tickAt(1300, 3000))    // 只冻了 1.4s
        assertTrue(w.tickAt(1300, 3600))     // 累计满 2s → 触发
    }

    @Test
    fun `暂停会重置窗口_恢复后重新计时`() {
        val w = watch()
        w.arm(0)
        w.tickAt(1000, 0)
        w.tickAt(1000, 1900, playing = false)   // 用户暂停 → 重置
        assertFalse(w.tickAt(1000, 3000))       // 恢复播放，重新计时
        assertTrue(w.tickAt(1000, 5000))
    }

    @Test
    fun `arm 会清掉上一次的 fired`() {
        val w = watch()
        w.arm(0)
        w.tickAt(5000, 0)
        assertTrue(w.tickAt(5000, 2000))
        w.arm(-12)                              // 又切了一次 Key（t=2000 重新计时）
        assertFalse("新窗口应重新计时", w.tickAt(5000, 2500))  // t=2500 首次见到 5000 → 起点
        assertTrue(w.tickAt(5000, 4600))        // 2500 + 2000 = 4500 ⇒ 4600 已过阈值
    }

    // ── 抓栈文本格式化 ───────────────────────────────────────────────

    private fun frames(vararg names: String) =
        names.map { StackTraceElement("C", it, "F.kt", 1) }.toTypedArray()

    @Test
    fun `ExoPlayer 线程排在其余线程之前`() {
        val a = Thread("main")
        val b = Thread("ExoPlayer:Playback")
        val c = Thread("Signal Catcher")
        val text = KeyStallWatch.formatThreadDump(
            "reason-x",
            mapOf(a to frames("f1"), b to frames("f2"), c to frames("f3")),
        )
        val iExo = text.indexOf("ExoPlayer:Playback")
        val iMain = text.indexOf("--- main")
        val iSig = text.indexOf("Signal Catcher")
        // 契约：ExoPlayer 线程**优先**，其余按名字排序。
        // 注意顺序不是 main 在前 —— 按名字比，"Signal Catcher"(S=0x53) 排在 "main"(m=0x6D) 之前。
        assertTrue("ExoPlayer 应在最前", iExo in 1 until iSig)
        assertTrue("其余线程按名字排序", iSig < iMain)
        assertTrue(text.contains("reason-x"))
        assertTrue(text.contains("线程总数: 3"))
    }

    @Test
    fun `超过上限的帧会被截断并标注`() {
        val t = Thread("t")
        val text = KeyStallWatch.formatThreadDump(
            "r",
            mapOf(t to frames(*Array(80) { "f$it" })),
            maxFramesPerThread = 10,
        )
        assertTrue(text.contains("f9"))
        assertFalse("第 10 帧之后不该出现", text.contains("f10"))
        assertTrue(text.contains("余 70 帧省略"))
    }
}

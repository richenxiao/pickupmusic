package com.shiyin.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 2026-10-19 报的 BUG 的回归测试：
 * **开着人声/伴奏听完 A，系统进到 B 时会先播出约 0.5 秒的 A 开头，像卡了一下。**
 *
 * 成因：`realSource` 是 app 线程写的，换曲那一刻**音频线程已经在跑新条目的前几个块**了 ——
 * `onTrackStarted` 还没来得及清掉源，那几块就按"新条目的第 0 帧"去读**上一首**的缓存。
 *
 * 修法：把"这个源还能不能用"交给音频线程自己判定（[MixArming]）——
 * 见条目切换就立刻解除武装，等 app 重新挂源。下面的用例把这条边界钉住。
 */
class MixArmingTest {

    @Test
    fun `初始未武装`() {
        assertFalse("没挂过源就不该出声", MixArming().armed)
    }

    @Test
    fun `app挂源后武装`() {
        val a = MixArming()
        a.onControl(token = 1)      // setter 里 arm() 把令牌 +1
        assertTrue("挂上源之后应当可用", a.armed)
    }

    @Test
    fun `令牌没变时不会被重复武装`() {
        val a = MixArming()
        a.onControl(1)
        a.onItemChange()
        // app **没有**再挂源（令牌还是 1）⇒ 必须一直是解除状态。
        // 这条正是"换曲后漏出上一首开头"的边界：解除之后不能自己好起来。
        repeat(5) { a.onControl(1) }
        assertFalse("没重新挂源就不能自己恢复", a.armed)
    }

    @Test
    fun `条目切换立刻解除武装`() {
        val a = MixArming()
        a.onControl(1)
        assertTrue(a.armed)
        // 换曲：音频线程看到条目切换 ⇒ 立刻弃用旧源（**不等 app 的 onTrackStarted**）
        a.onItemChange()
        assertFalse("条目切换后必须立刻弃用旧伴奏源", a.armed)
    }

    @Test
    fun `重新挂源后恢复`() {
        val a = MixArming()
        a.onControl(1)
        a.onItemChange()
        a.onControl(2)              // 单曲循环重播：app 重新 arm()
        assertTrue("重新挂源后应当恢复可用", a.armed)
    }

    @Test
    fun `换曲后立刻读到的是解除状态_哪怕源还没被清`() {
        // 模拟真实时序：条目切换（音频线程）发生在前，app 的 stopKaraoke（清 realSource）在后。
        val a = MixArming()
        a.onControl(1)              // A 在放，源已挂
        a.onItemChange()            // B 的第一个块来了 —— realSource 此刻**还指向 A**
        assertFalse(
            "B 的前几个块必须读不到 A 的缓存（这就是那 0.5 秒）",
            a.armed,
        )
    }
}

package com.shiyin.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 伴奏源武装仲裁的回归测试。两条真机报障各钉一组：
 *
 * 1. **换曲漏出上一首开头约 0.5 秒**（2026-10-19）：`realSource` 是 app 线程写的，换曲那一刻
 *    音频线程已经在跑新条目的前几个块 ⇒ 那几块会按新条目的第 0 帧去读上一首的缓存。
 *    修法：把"这个源还能不能用"交给音频线程判定 —— 见条目切换就解除武装，等 app 重新挂源。
 *
 * 2. **单曲循环时伴唱停在原曲、UI 却还"展开"**（2026-10-23）：「条目切换」发生在**每个循环
 *    开端**，而 app 的重新武装与它在时间上**顺序不定**；只比令牌新旧时，若 app 先武装、
 *    音频线程后解除，令牌不再变化 ⇒ 永久停在原曲。修法：武装时带上**条目身份**。
 */
class MixArmingTest {

    @Test
    fun `初始未武装`() {
        assertFalse("没挂过源就不该出声", MixArming().armed)
    }

    @Test
    fun `app挂源后武装`() {
        val a = MixArming()
        a.onControl(token = 1, armedForItemId = 7, currentItemId = 7)   // setter 里 arm() 把令牌 +1
        assertTrue("挂上源之后应当可用", a.armed)
    }

    @Test
    fun `令牌没变时不会被重复武装`() {
        val a = MixArming()
        a.onControl(1, 7, 7)
        a.onItemChange()
        // app **没有**再挂源（令牌还是 1）⇒ 必须一直是解除状态。
        // 这条正是"换曲后漏出上一首开头"的边界：解除之后不能自己好起来。
        repeat(5) { a.onControl(1, 7, 7) }
        assertFalse("没重新挂源就不能自己恢复", a.armed)
    }

    @Test
    fun `条目切换立刻解除武装`() {
        val a = MixArming()
        a.onControl(1, 7, 7)
        assertTrue(a.armed)
        // 换曲：音频线程看到条目切换 ⇒ 立刻弃用旧源（**不等 app 的 onTrackStarted**）
        a.onItemChange()
        assertFalse("条目切换后必须立刻弃用旧伴奏源", a.armed)
    }

    @Test
    fun `重新挂源后恢复`() {
        val a = MixArming()
        a.onControl(1, 7, 7)
        a.onItemChange()
        a.onControl(2, 9, 9)              // app 为新条目重新 arm(9)
        assertTrue("重新挂源后应当恢复可用", a.armed)
    }

    @Test
    fun `换曲后立刻读到的是解除状态_哪怕源还没被清`() {
        // 模拟真实时序：条目切换（音频线程）发生在前，app 的 stopKaraoke（清 realSource）在后。
        val a = MixArming()
        a.onControl(1, 7, 7)              // A 在放，源已挂
        a.onItemChange()                  // B 的第一个块来了 —— realSource 此刻**还指向 A**
        assertFalse(
            "B 的前几个块必须读不到 A 的缓存（这就是那 0.5 秒）",
            a.armed,
        )
    }

    // ── 2026-10-23 单曲循环 ──────────────────────────────────────────────
    //
    // 循环时「条目切换」发生在每个循环开端，而 app 的重新武装与它的先后**顺序不定**：
    // 若 app 先武装、音频线程后解除（本类的 onItemChange），令牌不会再变 ⇒ 单靠令牌
    // **必然卡死**在"原曲"，而 UI 仍显示"展开"。所以修法是两层：
    //   ① 武装带条目身份（本类）—— 保证"这份源属于哪一首"没有歧义；
    //   ② app 在重挂后**补一次延迟重挂**（MainViewModel 的循环分支）—— 与顺序无关。
    // 下面两条把 ① 的行为钉住；② 是 app 侧的一行，由真机回归覆盖。

    @Test
    fun `同曲循环_条目再切换后重新武装_恢复`() {
        val a = MixArming()
        a.onControl(1, 5, 5)              // A 在放、已武装
        a.onItemChange()                  // 循环：音频线程看到条目切换 → 解除
        assertFalse(a.armed)
        a.onControl(2, 5, 5)              // app 补的那次重挂（令牌 1→2，同一首）
        assertTrue("同曲重挂后必须恢复（否则伴奏静音、UI 却显示展开）", a.armed)
    }

    @Test
    fun `武装属于上一首而条目已换_应解除`() {
        val a = MixArming()
        a.onControl(1, 5, 5)              // A 的源
        a.onControl(1, 5, 9)              // 条目已换成 B，但这次武装仍是给 A 的
        assertFalse("源属于上一首时不能给新条目用", a.armed)
    }

    @Test
    fun `迟到的重挂带旧身份_不得重新武装`() {
        // 单曲循环的延迟重挂若与"用户已切歌"撞上，身份判据必须挡住它（否则又是上一首的缓存）
        val a = MixArming()
        a.onControl(1, 5, 5)              // A 在放
        a.onControl(2, 5, 9)              // 迟到重挂：武装身份=A(5)，但当前已是 B(9)
        assertFalse("身份不符的迟到重挂不能生效", a.armed)
    }

    @Test
    fun `条目身份相同则不因身份变化解除`() {
        val a = MixArming()
        a.onControl(1, 5, 5)
        repeat(3) { a.onControl(1, 5, 5) }
        assertTrue("同曲连续块不能被身份判据误解除", a.armed)
    }
}

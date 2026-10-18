package com.shiyin.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌词页「伴唱」按钮的状态机（闸门 3 / A75）。
 *
 * 这张表只有三态，但**每一条边都对应真机上出过的问题**：
 *  · 就绪回调**可能晚于取消**（分离是异步的）—— 那时必须留在 Idle，
 *    否则用户刚点的取消会被"拉回去"（真的发生过）；
 *  · Active 再点 = **淡回原曲**（Idle），不是"再加载一次"；
 *  · 换歌 / 关总开关 / 分离失败一律回 Idle。
 *
 * ⚠️ 说清楚边界：这张表**只管按钮长什么样**。"取消后再点要不要重新加载"是
 * `MainViewModel.selectKaraokeMix` 的快路径问题（缓存目录还在就不该重新分离），
 * 本文件用 [取消后再点_仍会走一次 Loading] 把这条**已知行为**钉住 ——
 * 改快路径时如果发现这里也该变，说明改错了地方。
 */
class KaraokeButtonMachineTest {

    private var s = KaraokeButtonState.Idle

    private fun tap() { s = KaraokeButtonMachine.onTap(s) }

    private fun ready() { s = KaraokeButtonMachine.onReady(s) }

    private fun abort() { s = KaraokeButtonMachine.onAbort(s) }

    @Test
    fun `首次点击_进入Loading`() {
        tap()
        assertEquals(KaraokeButtonState.Loading, s)
    }

    @Test
    fun `就绪后进入Active`() {
        tap(); ready()
        assertEquals(KaraokeButtonState.Active, s)
    }

    @Test
    fun `Active再点回原曲_不是再加载一次`() {
        tap(); ready()
        tap()
        assertEquals("Active 再点必须是 Idle（淡回原曲），不可回到 Loading", KaraokeButtonState.Idle, s)
    }

    @Test
    fun `Loading时点击取消_回Idle`() {
        tap()
        tap()
        assertEquals(KaraokeButtonState.Idle, s)
    }

    @Test
    fun `取消后迟到的就绪回调_必须保持Idle`() {
        // 分离是异步的：用户点取消后，onReady 仍可能到达。若此时进 Active，
        // 用户就会看到"我明明取消了，伴唱自己开起来了"。
        tap()                 // Loading
        tap()                 // 取消 → Idle
        ready()               // 迟到的就绪
        assertEquals("取消后迟到的 onReady 绝不能把按钮点亮", KaraokeButtonState.Idle, s)
    }

    @Test
    fun `取消后再点_仍会走一次Loading（已知行为，非缺陷）`() {
        // 这是**当前**行为：按钮本身不认识缓存。真正"不该再转圈"的部分在
        // MainViewModel 的快路径（karaokeReadyDir 还在就直接切档、不再分离）。
        tap()                 // Loading
        tap()                 // 取消 → Idle
        tap()                 // 再点 → Loading
        assertEquals(KaraokeButtonState.Loading, s)
        ready()
        assertEquals(KaraokeButtonState.Active, s)
    }

    @Test
    fun `Active时收到就绪回调_不变`() {
        tap(); ready()
        ready()
        assertEquals(KaraokeButtonState.Active, s)
    }

    @Test
    fun `换歌或关开关_一律回Idle`() {
        tap(); ready()
        abort()
        assertEquals(KaraokeButtonState.Idle, s)

        tap()                 // Loading 中换歌
        abort()
        assertEquals(KaraokeButtonState.Idle, s)
    }

    @Test
    fun `Idle时收到就绪回调_仍为Idle`() {
        // "缓存本来就全"时 onReady 可能几乎立刻到达，而用户还没点过 —— 不该自己点亮。
        ready()
        assertEquals(KaraokeButtonState.Idle, s)
    }

    // ── P2-D：面板里**每一档卡片**的视觉（用户 2026-10-18）────────────────────
    // 用户口径："状态动画不使用文字/秒数/百分比"——全靠图标与底色表达；
    // 且**只有一个状态源**（KaraokeButtonState，由音频线程的 KaraokeAccompState 驱动）。

    @Test
    fun `卡片_没选中就是灰的`() {
        assertEquals(
            KaraokeModeVisual.OFF,
            karaokeModeVisual(KaraokeButtonState.Loading, selectedIndex = 2, index = 0),
        )
    }

    @Test
    fun `卡片_选中人声但还没出声_转圈`() {
        assertEquals(
            KaraokeModeVisual.COMPUTING,
            karaokeModeVisual(KaraokeButtonState.Loading, selectedIndex = 0, index = 0),
        )
        assertEquals(
            "Idle（还没起分离）也算未就绪，仍是转圈",
            KaraokeModeVisual.COMPUTING,
            karaokeModeVisual(KaraokeButtonState.Idle, selectedIndex = 0, index = 0),
        )
    }

    @Test
    fun `卡片_出声了才是橙的`() {
        assertEquals(
            KaraokeModeVisual.READY,
            karaokeModeVisual(KaraokeButtonState.Active, selectedIndex = 0, index = 0),
        )
        assertEquals(
            KaraokeModeVisual.READY,
            karaokeModeVisual(KaraokeButtonState.Active, selectedIndex = 2, index = 2),
        )
    }

    @Test
    fun `卡片_原曲档立即算完成`() {
        // 「原曲」不需要伴奏，本来就一直在出声 —— 任何状态都必须是完成态。
        KaraokeButtonState.values().forEach { st ->
            assertEquals(
                "原曲档在 $st 下应是完成态",
                KaraokeModeVisual.READY,
                karaokeModeVisual(st, selectedIndex = KARAOKE_ORIGINAL_INDEX, index = KARAOKE_ORIGINAL_INDEX),
            )
        }
    }

    @Test
    fun `卡片_欠载回退时退回转圈`() {
        // MainViewModel 的轮询在 KaraokeAccompState.inFallback 时把 karaokeState 打回 Loading
        // ⇒ 卡片随之从"橙"退回"转圈"。这条钉住"回退要如实反映"。
        assertEquals(
            KaraokeModeVisual.READY,
            karaokeModeVisual(KaraokeButtonState.Active, 2, 2),
        )
        assertEquals(
            "回退后状态是 Loading ⇒ 必须退回转圈",
            KaraokeModeVisual.COMPUTING,
            karaokeModeVisual(KaraokeButtonState.Loading, 2, 2),
        )
    }
}

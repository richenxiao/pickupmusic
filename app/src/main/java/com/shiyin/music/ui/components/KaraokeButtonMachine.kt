package com.shiyin.music.ui.components

/**
 * v1.5.0 块 4：**伴唱按钮的状态机**（纯逻辑，可单测 —— 不测就只是"看着对"）。
 *
 * ```
 *   Idle ──点击──▶ Loading ──首段可用──▶ Active
 *                   │  ▲                  │
 *                   │  └──(就绪时已被取消不再拉回)
 *                   └──取消/失败/关开关/换歌──▶ Idle
 *   Active ──点击──▶ Idle（淡回原曲）
 * ```
 *
 * 两个容易写错的地方，被下面两条约定钉住：
 * - **就绪回调可能晚于取消**：分离是异步的，用户在转圈时点了取消 ⇒ 必须**留在 Idle**，
 *   否则会突然把伴唱打开（用户明确取消了却被拉回去）。
 * - **Active 再点 = 回 Idle**（淡回原曲），不是"再加载一次"。
 */
object KaraokeButtonMachine {

    /** 点击：Idle ⇄ Loading/Active → 见上表。 */
    fun onTap(cur: KaraokeButtonState): KaraokeButtonState =
        if (cur == KaraokeButtonState.Idle) KaraokeButtonState.Loading else KaraokeButtonState.Idle

    /** 分离"首段可用"：**只有还在 Loading 才进 Active**。 */
    fun onReady(cur: KaraokeButtonState): KaraokeButtonState =
        if (cur == KaraokeButtonState.Loading) KaraokeButtonState.Active else cur

    /** 取消 / 分离失败 / 关总开关 / 换歌 —— 一律回 Idle。 */
    fun onAbort(@Suppress("UNUSED_PARAMETER") cur: KaraokeButtonState): KaraokeButtonState =
        KaraokeButtonState.Idle
}

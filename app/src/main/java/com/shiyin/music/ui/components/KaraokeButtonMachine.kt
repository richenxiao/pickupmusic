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
 *   否则会突然把伴唱打开（需求方明确取消了却被拉回去）。
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

/** 伴唱面板三档的顺序：**人声 — 原曲（中立位）— 伴奏**（用户设计稿）。 */
const val KARAOKE_ORIGINAL_INDEX = 1

/**
 * P2-D（定稿）：伴唱面板里**每一档卡片此刻该怎么画**（纯逻辑，可单测）。
 *
 * 状态源**只有一个**：[KaraokeButtonState] —— 它由音频线程的 `KaraokeAccompState` 上报驱动
 * （见 `MainViewModel` 的 100ms 轮询）。这里只把它翻译成"**这一张卡**"的视觉，
 * 不引入第二套状态。
 *
 * 用户口径："状态动画不使用文字/秒数/百分比" —— 三种视觉全靠图标与底色表达。
 */
enum class KaraokeModeVisual {
    /** 没选中这一档：灰底灰图标。 */
    OFF,

    /** 选中了但还没出声：**图标边缘转圈**。欠载回退时也是它（如实退回未就绪）。 */
    COMPUTING,

    /** 正在出声：图标和底变橙，带一下短暂 settle。 */
    READY,
}

/**
 * @param state 当前伴唱状态（单一状态源）
 * @param selectedIndex 面板当前选中的档（0=人声 1=原曲 2=伴奏）
 * @param index 这一张卡代表哪一档
 */
fun karaokeModeVisual(
    state: KaraokeButtonState,
    selectedIndex: Int,
    index: Int,
): KaraokeModeVisual {
    if (index != selectedIndex) return KaraokeModeVisual.OFF
    // 「原曲」不需要伴奏 —— **立即算完成**（它本来就一直在出声）
    if (index == KARAOKE_ORIGINAL_INDEX) return KaraokeModeVisual.READY
    return if (state == KaraokeButtonState.Active) KaraokeModeVisual.READY
    else KaraokeModeVisual.COMPUTING
}

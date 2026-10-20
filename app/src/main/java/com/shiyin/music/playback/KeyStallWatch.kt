package com.shiyin.music.playback

import androidx.media3.common.Player

/**
 * 升降 Key 卡死取证 —— **被动看门狗**（用户 2026-10-20「修改0」P0）。
 *
 * ### 契约（用户原话）
 * > "默认关闭；通过现有的 karaoke-cmd 文件通道开启…开启后，每次 Key 变化起，若播放位置
 * >  超过 2 秒不前进，则调用 `Thread.getAllStackTraces()` 和 `ExoPlayer:Playback` 线程栈，
 * >  打印到 logcat（tag 用 `PickUpDiag`），同时落盘到 App 私有目录。关闭时对播放行为零影响。"
 *
 * ### 为什么叫"被动"
 * 用户明令：**不许用 try/catch、超时重置、延迟重试来掩盖**，看门狗**只用于诊断，不作为修复**。
 * 所以本类里**没有任何自愈动作** —— 判定成立时只做一件事：把证据抓下来交给调用方。
 *
 * ### 可测性
 * 判定逻辑是**纯函数**：输入只有「位置 / 是否在播 / 引擎是否 READY / 当前时钟」，
 * 时钟由构造参数注入（生产传 `SystemClock::elapsedRealtime`，单测传假时钟）。
 * 抓栈与落盘属 Android 侧，在 [PlayerController] 里。
 */
class KeyStallWatch(private val now: () -> Long) {

    /** 本观测窗口对应的 Key 值（半音步数），只用于报告。 */
    var halfSteps: Int = 0
        private set

    private var armed = false
    private var fired = false
    private var lastPos = -1L
    private var lastAdvanceAt = 0L

    val isArmed: Boolean get() = armed

    /** 每次 **Key 变化**调用一次：开始观测。 */
    fun arm(halfSteps: Int) {
        this.halfSteps = halfSteps
        armed = true
        fired = false
        lastPos = -1L
        lastAdvanceAt = now()
    }

    fun disarm() {
        armed = false
        fired = false
    }

    /**
     * 每个 tick（生产 :300ms）调一次。
     *
     * 判据（用户给的）：**位置超过 2 秒不前进**。
     *
     * ⚠️ **2026-10-20 真机实测校正**：本类第一版只盯 `STATE_READY`，**会漏报真正的卡死** ——
     * 卡住时 `dumpsys media_session` 报的是
     * `state=PlaybackState {state=BUFFERING(6), position=43263, speed=0.0}`，
     * 引擎停在 **BUFFERING**、根本没进 READY。旧的 `PlayerDiag` 看门狗
     * （`PlayerController` 里 `looksStalled`，要求 `playbackState == STATE_READY`）
     * 因此一直没打日志 —— 这就是"卡死却抓不到证据"的原因。
     *
     * ⇒ 现在只排除**本来就不该出声**的两种状态：`STATE_IDLE`（无媒体）与
     * `STATE_ENDED`（放完了）。**READY 与 BUFFERING 都盯**。
     * 用户暂停（`playWhenReady == false`）时位置不动是**正常**的，重置窗口而不误报。
     *
     * @param playbackState `Player.STATE_*`
     * @return `true` = 本拍判定卡死（**同一个窗口只报一次**），调用方去抓栈
     */
    fun tick(positionMs: Long, playWhenReady: Boolean, playbackState: Int): Boolean {
        if (!armed || fired) return false
        if (!playWhenReady ||
            playbackState == Player.STATE_IDLE ||
            playbackState == Player.STATE_ENDED
        ) {
            // 暂停 / 无媒体 / 已放完 —— 位置不动是正常的，重置窗口，避免误报。
            lastPos = -1L
            lastAdvanceAt = now()
            return false
        }
        val t = now()
        if (positionMs != lastPos) {
            lastPos = positionMs
            lastAdvanceAt = t
            return false
        }
        if (t - lastAdvanceAt >= STALL_MS) {
            fired = true
            return true
        }
        return false
    }

    companion object {
        /** 位置多久不前进算卡死 —— 用户指定 **2 秒**。 */
        const val STALL_MS = 2000L

        /**
         * 把 `Thread.getAllStackTraces()` 的结果排成可读文本。
         *
         * 顺序：**ExoPlayer 线程优先**（`ExoPlayer:Playback` 等，卡死基本都在这里），
         * 然后是其余线程按名字排序 —— 固定顺序才能让两次 dump 直接 diff。
         *
         * ⚠️ 纯函数：入参是 `Map<Thread, Array<StackTraceElement>>`，**不自己抓栈**，
         * 所以可以在 JVM 单测里喂假栈验证格式。
         */
        fun formatThreadDump(
            reason: String,
            stacks: Map<Thread, Array<StackTraceElement>>,
            maxFramesPerThread: Int = 64,
        ): String {
            val sb = StringBuilder()
            sb.append("==== PickUpDiag 卡死取证 ====\n")
            sb.append(reason).append('\n')
            sb.append("线程总数: ").append(stacks.size).append('\n')

            fun isExo(t: Thread) = t.name.contains("ExoPlayer", ignoreCase = true)
            val ordered = stacks.entries.sortedWith(
                compareByDescending<Map.Entry<Thread, Array<StackTraceElement>>> { isExo(it.key) }
                    .thenBy { it.key.name },
            )

            for ((thread, frames) in ordered) {
                sb.append("\n--- ").append(thread.name)
                    .append(" [").append(thread.state).append("]")
                    .append(" prio=").append(thread.priority)
                    .append(" daemon=").append(thread.isDaemon)
                    .append('\n')
                val n = minOf(frames.size, maxFramesPerThread)
                for (i in 0 until n) {
                    sb.append("    at ").append(frames[i]).append('\n')
                }
                if (frames.size > n) {
                    sb.append("    …（余 ").append(frames.size - n).append(" 帧省略）\n")
                }
            }
            return sb.toString()
        }
    }
}

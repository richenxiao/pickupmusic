package com.shiyin.music.playback

import kotlin.math.abs
import kotlin.math.pow

/**
 * v1.5.0 伴唱（开发文档 §8 升降 Key）——纯音高换算，便于 JVM 单测。
 *
 * 规格：范围 ±6 半音、**步进支持 0.5 半音**（2026-10-14 用户要求增大精度后加的）、
 * 音高系数 2^(n/12)、速度不变；总音高 = 加速模式自带音高 × 2^(n/12)
 * （复古模式 pitch 随 speed 联动，现代模式 pitch 恒 1）。
 *
 * ⚠️ **内部单位是「半音步数」（half-step）**，不是半音：`+1` 半音步 = `+0.5` 半音。
 * 之所以不改成 Float：整数的比较/相等判定精确、无浮点误差累积，UI 上的"是否原调"判断
 * (`== 0`) 也干净。对外换算一律走 [semitones] / [label]。
 *
 * 实现方式：本项目既有「加速播放」正是 PlaybackParameters(speed, pitch)（见
 * PlayerController.setPlaybackSpeed）。因此升降 Key 只改 pitch 分量，**完全不碰音频管线**。
 *
 * ⚠️ **音质注意（台账 A67）**：Media3 的 Sonic 变调是"线性插值重采样 + WSOLA 拉回"，升/降得越多
 * 混叠越明显 —— 这是**精度和音质两个问题**：加 0.5 步进解决"找调更细"，不解决"变调有噪声"。
 */
object KeyShift {

    /** 下限 / 上限（半音步数）：−12 = −6 半音，+12 = +6 半音。 */
    const val MIN = -12
    const val MAX = 12

    /** 一次"整半音"要走多少步。 */
    const val STEPS_PER_SEMITONE = 2

    fun clamp(halfSteps: Int): Int = halfSteps.coerceIn(MIN, MAX)

    /** 半音步数 → 半音（可含 0.5）。 */
    fun semitones(halfSteps: Int): Float = halfSteps / 2f

    /** 半音步数 → 音高系数 2^(半音/12)。0 时直接返回 1.0f，避免 pow 带来 0.9999999 这类噪声。 */
    fun factor(halfSteps: Int): Float =
        if (halfSteps == 0) 1.0f else 2.0.pow(halfSteps / (12.0 * STEPS_PER_SEMITONE)).toFloat()

    /** 叠加到加速模式自带音高之上。 */
    fun totalPitch(speed: Float, retroMode: Boolean, halfSteps: Int): Float =
        (if (retroMode) speed else 1.0f) * factor(halfSteps)

    /**
     * 面板显示：原调 / +2 / −3 / +1.5 / −0.5。
     * 负号用真正的减号（U+2212），避免与连字符混淆；整数不显示 `.0`。
     */
    fun label(halfSteps: Int): String {
        if (halfSteps == 0) return "原调"
        val s = semitones(halfSteps)
        val mag = abs(s)
        val num = if (mag == mag.toInt().toFloat()) mag.toInt().toString() else "%.1f".format(mag)
        return (if (s > 0) "+" else "−") + num
    }
}

package com.shiyin.music.playback

import kotlin.math.pow

/**
 * v1.5.0 伴唱（开发文档 §8 升降 Key）——纯音高换算，便于 JVM 单测。
 *
 * 规格：范围 ±6 半音、步进 1、音高系数 2^(n/12)、速度不变；
 * 总音高 = 加速模式自带音高 × 2^(n/12)（复古模式 pitch 随 speed 联动，现代模式 pitch 恒 1）。
 *
 * 实现方式：本项目既有「加速播放」正是 PlaybackParameters(speed, pitch)（见
 * PlayerController.setPlaybackSpeed）。因此升降 Key 只改 pitch 分量，**完全不碰音频管线** ——
 * 这很重要：PlaybackService 里两次装机实测证明本管线上任何 buildAudioSink 覆写都会让音频渲染失效。
 */
object KeyShift {

    const val MIN = -6
    const val MAX = 6

    fun clamp(semitones: Int): Int = semitones.coerceIn(MIN, MAX)

    /** 2^(n/12)。n=0 时直接返回 1.0f，避免 pow 带来的 0.9999999 这类噪声。 */
    fun factor(semitones: Int): Float =
        if (semitones == 0) 1.0f else 2.0.pow(semitones / 12.0).toFloat()

    /** 叠加到加速模式自带音高之上。 */
    fun totalPitch(speed: Float, retroMode: Boolean, semitones: Int): Float =
        (if (retroMode) speed else 1.0f) * factor(semitones)

    /** 面板显示：原调 / +2 / -3（负号用真正的减号，避免与连字符混淆）。 */
    fun label(semitones: Int): String = when {
        semitones == 0 -> "原调"
        semitones > 0 -> "+" + semitones
        else -> "−" + (-semitones)
    }
}

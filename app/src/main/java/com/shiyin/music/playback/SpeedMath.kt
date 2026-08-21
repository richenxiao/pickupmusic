package com.shiyin.music.playback

import kotlin.math.roundToInt

/**
 * 播放速度的输入解析与 0.05 步进量化。纯函数，便于 JVM 单测验证
 * 1.15 / 1.25 / 1.50 等值不会被四舍五入到 0.1 网格，也不会被任何
 * coerce/round 改成 1.1 / 1.2。
 */
private const val SPEED_STEP = 0.05f
private const val SPEED_MIN = 0.5f
private const val SPEED_MAX = 2.0f

/** 输入框用：解析任意合法浮点速度，只做范围/合法性校验，**不量化**。
 *  1.27→1.27、1.333→1.333、1.01→1.01。越界/非数字返回 null。 */
fun parseSpeedInput(input: String): Float? {
    val v = input.trim().toFloatOrNull() ?: return null
    if (!v.isFinite() || v < SPEED_MIN || v > SPEED_MAX) return null
    return v
}

/** 把任意浮点速度量化到 0.05 网格并夹回 [0.5, 2.0]（用于滑块拖动）。 */
fun quantizeSpeed(v: Float): Float =
    ((v / SPEED_STEP).roundToInt() * SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX)

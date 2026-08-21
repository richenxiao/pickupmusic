package com.shiyin.music.playback

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实验证播放速度输入：1.15 / 1.25 / 1.50 等能解析到对应浮点速度，
 * 不被四舍五入到 0.1 网格；越界与非法输入被拒。
 */
class SpeedMathTest {
    private fun approx(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.001f

    @Test fun parse_115() = assertTrue("1.15 → ~1.15", approx(parseSpeedInput("1.15")!!, 1.15f))
    @Test fun parse_125() = assertTrue("1.25 → ~1.25", approx(parseSpeedInput("1.25")!!, 1.25f))
    @Test fun parse_150() = assertTrue("1.5 → ~1.5", approx(parseSpeedInput("1.5")!!, 1.5f))
    @Test fun parse_150_trailing_zero() = assertTrue("1.50 → ~1.5", approx(parseSpeedInput("1.50")!!, 1.5f))
    @Test fun parse_115_in_range_and_on_grid() {
        val v = parseSpeedInput("1.15")!!
        assertTrue("in [0.5,2.0]", v in 0.5f..2.0f)
        // 量化结果应贴近 0.05 的整数倍
        assertTrue("on 0.05 grid", approx(v, 1.15f))
    }

    @Test fun reject_out_of_range_high() { assertNull(parseSpeedInput("3.0")) }
    @Test fun reject_out_of_range_low() { assertNull(parseSpeedInput("0.1")) }
    @Test fun reject_non_numeric() { assertNull(parseSpeedInput("abc")) }
    @Test fun reject_empty() { assertNull(parseSpeedInput("")) }

    @Test fun quantize_snaps_to_nearest_grid() {
        assertTrue("1.14→1.15", approx(quantizeSpeed(1.14f), 1.15f))
        assertTrue("1.16→1.15", approx(quantizeSpeed(1.16f), 1.15f))
        assertTrue("1.17→1.15", approx(quantizeSpeed(1.17f), 1.15f))
    }

    @Test fun quantize_stays_in_range() {
        for (i in 0..30) {
            val v = quantizeSpeed(0.5f + i * 0.05f)
            assertTrue("v=$v in [0.5,2.0]", v in 0.5f..2.0f)
        }
    }
}

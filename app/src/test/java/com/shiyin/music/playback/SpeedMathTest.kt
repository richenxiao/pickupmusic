package com.shiyin.music.playback

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证输入框允许任意合法小数（不吸附 0.05），Slider 仍 0.05 量化。
 * 1.27→1.27、1.333→1.333、1.01→1.01；2.01/0.49 越界拒绝。
 */
class SpeedMathTest {
    private fun approx(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.001f

    // ── 输入框：parseSpeedInput 不量化 ──
    @Test fun parse_127_kept() {
        val v = parseSpeedInput("1.27")!!
        assertTrue("1.27 原样", approx(v, 1.27f))
        assertTrue("1.27 不应被吸附到 1.25", !approx(v, 1.25f))
    }
    @Test fun parse_1333_kept() {
        val v = parseSpeedInput("1.333")!!
        assertTrue("1.333 原样", approx(v, 1.333f))
    }
    @Test fun parse_101_kept() = assertTrue("1.01", approx(parseSpeedInput("1.01")!!, 1.01f))
    @Test fun parse_148_kept() = assertTrue("1.48", approx(parseSpeedInput("1.48")!!, 1.48f))
    @Test fun parse_199_kept() = assertTrue("1.99", approx(parseSpeedInput("1.99")!!, 1.99f))
    @Test fun parse_grid_values_still_ok() {
        assertTrue(approx(parseSpeedInput("1.15")!!, 1.15f))
        assertTrue(approx(parseSpeedInput("1.5")!!, 1.5f))
        assertTrue(approx(parseSpeedInput("2.0")!!, 2.0f))
    }
    @Test fun parse_rejects_out_of_range() {
        assertNull(parseSpeedInput("2.01"))
        assertNull(parseSpeedInput("0.49"))
        assertNull(parseSpeedInput("3"))
        assertNull(parseSpeedInput("0"))
        assertNull(parseSpeedInput("abc"))
        assertNull(parseSpeedInput(""))
    }

    // ── Slider：quantizeSpeed 仍 0.05 网格 ──
    @Test fun quantize_127_snaps_to_125() = assertTrue(approx(quantizeSpeed(1.27f), 1.25f))
    @Test fun quantize_128_snaps_to_130() = assertTrue(approx(quantizeSpeed(1.28f), 1.3f))
    @Test fun quantize_grid_neighbors() {
        assertTrue(approx(quantizeSpeed(1.14f), 1.15f))
        assertTrue(approx(quantizeSpeed(1.16f), 1.15f))
    }
    @Test fun quantize_stays_in_range() {
        for (i in 0..30) {
            val v = quantizeSpeed(0.5f + i * 0.05f)
            assertTrue("v=$v in [0.5,2.0]", v in 0.5f..2.0f)
        }
    }
}

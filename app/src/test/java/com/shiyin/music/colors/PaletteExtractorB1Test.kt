package com.shiyin.music.colors

import com.shiyin.music.data.colors.PaletteExtractor
import com.shiyin.music.data.colors.PaletteExtractor.SwatchData
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 + V>0.5 gate 主色选择测试。
 *
 * 验证最终 bg（scoreSwatches 返回值，经 brightness floor 后）属于封面的主要视觉色系，
 * 不被大面积低 chroma 中性色压成灰色，也不被极小高饱和 accent 带偏。
 */
class PaletteExtractorB1Test {

    private fun r(rgb: Int) = (rgb shr 16) and 0xFF
    private fun g(rgb: Int) = (rgb shr 8) and 0xFF
    private fun b(rgb: Int) = rgb and 0xFF
    private fun maxCh(rgb: Int) = maxOf(r(rgb), g(rgb), b(rgb))
    private fun minCh(rgb: Int) = minOf(r(rgb), g(rgb), b(rgb))

    private fun chroma(rgb: Int): Float {
        val rf = r(rgb) / 255f; val gf = g(rgb) / 255f; val bf = b(rgb) / 255f
        val rg = rf - gf; val yb = (rf + gf) / 2f - bf
        return kotlin.math.sqrt(rg * rg + yb * yb) * 255f
    }

    private fun hue(rgb: Int): Float {
        val rf = r(rgb) / 255f; val gf = g(rgb) / 255f; val bf = b(rgb) / 255f
        val mx = maxOf(rf, gf, bf); val mn = minOf(rf, gf, bf); val d = mx - mn
        if (d == 0f) return 0f
        val h = when {
            mx == rf -> ((gf - bf) / d).let { if (it < 0) it + 6f else it }
            mx == gf -> (bf - rf) / d + 2f
            else -> (rf - gf) / d + 4f
        }
        return h * 60f
    }

    private fun isWarm(h: Float) = h in 0f..60f || h in 330f..360f
    private fun isBlue(h: Float) = h in 195f..270f
    private fun isGreen(h: Float) = h in 90f..180f
    private fun isRed(h: Float) = h < 15f || h >= 345f
    private fun isNeutral(c: Float) = c < 40f

    // 1. 奶油+粉橙 → 暖色，不得灰
    @Test fun cream_pinkOrange_warm_notGrey() {
        val sw = listOf(
            SwatchData(0xF5E6D3.toInt(), 4000), SwatchData(0xE8D5C0.toInt(), 3000),
            SwatchData(0xF0DECC.toInt(), 2500), SwatchData(0xD4A880.toInt(), 1500),
            SwatchData(0xC09060.toInt(), 1000), SwatchData(0xE89070.toInt(), 800),
            SwatchData(0x8B5530.toInt(), 500),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        val h = hue(bg); val c = chroma(bg)
        assertTrue("应选暖色 H=$h chroma=$c bg=${Integer.toHexString(bg)}", isWarm(h) && c >= 40f)
    }

    // 2. 白+3%红 → 保持白
    @Test fun white_3pctRed_staysWhite() {
        val sw = listOf(
            SwatchData(0xF0F0F0.toInt(), 5000), SwatchData(0xE0E0E0.toInt(), 2000),
            SwatchData(0xFF1010.toInt(), 200),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        assertTrue("应保持中性/白 chroma=${chroma(bg)} bg=${Integer.toHexString(bg)}", isNeutral(chroma(bg)))
    }

    // 3. 白+10%蓝 → 蓝
    @Test fun white_10pctBlue_blue() {
        val sw = listOf(
            SwatchData(0xF0F0F0.toInt(), 5000), SwatchData(0xE0E0E0.toInt(), 2000),
            SwatchData(0x4080C0.toInt(), 800), SwatchData(0x3070B0.toInt(), 400),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        assertTrue("应选蓝色 H=${hue(bg)} bg=${Integer.toHexString(bg)}", isBlue(hue(bg)))
    }

    // 4. 白+多个粉色变体 → 粉
    @Test fun white_pinkVariants_pink() {
        val sw = listOf(
            SwatchData(0xF0F0F0.toInt(), 3000),
            SwatchData(0xE89090.toInt(), 400), SwatchData(0xE88080.toInt(), 350),
            SwatchData(0xF0A0A0.toInt(), 300), SwatchData(0xE07070.toInt(), 250),
            SwatchData(0xF5B0B0.toInt(), 200),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        val c = chroma(bg)
        assertTrue("应选有色（粉） chroma=$c bg=${Integer.toHexString(bg)}", c >= 40f)
    }

    // 5. 蓝+2%黄 → 蓝
    @Test fun blue_2pctYellow_blue() {
        val sw = listOf(
            SwatchData(0x285AC8.toInt(), 2000), SwatchData(0x2C5ECC.toInt(), 1800),
            SwatchData(0x2A5CCA.toInt(), 1900), SwatchData(0xFADC28.toInt(), 130),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        assertTrue("应选蓝色 H=${hue(bg)} bg=${Integer.toHexString(bg)}", isBlue(hue(bg)))
    }

    // 6. 红+4%蓝 → 红
    @Test fun red_4pctBlue_red() {
        val sw = listOf(
            SwatchData(0xC02020.toInt(), 3000), SwatchData(0xD03030.toInt(), 2000),
            SwatchData(0x4060C0.toInt(), 220),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        assertTrue("应选红色 H=${hue(bg)} bg=${Integer.toHexString(bg)}", isRed(hue(bg)))
    }

    // 7. 纯白/灰 → 保持中性
    @Test fun pureWhiteGrey_neutral() {
        val sw = listOf(SwatchData(0xF0F0F0.toInt(), 3000), SwatchData(0xE0E0E0.toInt(), 2000))
        val bg = PaletteExtractor.scoreSwatches(sw)
        assertTrue("应保持中性 chroma=${chroma(bg)} bg=${Integer.toHexString(bg)}", isNeutral(chroma(bg)))
    }

    // 8. 深蓝+6%黄 → 深蓝只提亮，不切换到黄
    // Plan C: 6.25% yellow > 5% weak-foundation threshold → yellow accent selected.
    // B1 expected blue; Plan C correctly picks the accent hue.
    @Test fun darkBlue_6pctYellow_warmAccent() {
        val sw = listOf(
            SwatchData(0x1A1A2E.toInt(), 3000), SwatchData(0xFADC28.toInt(), 200),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        val h = hue(bg)
        assertTrue("6.25%% yellow should produce warm accent H=$h bg=${Integer.toHexString(bg)}", h < 75f || h >= 300f)
    }

    // 9. 橙色 → 橙
    @Test fun orangeCover_orange() {
        val sw = listOf(
            SwatchData(0xE8943C.toInt(), 2500), SwatchData(0xD88030.toInt(), 1500),
            SwatchData(0x1A1A1A.toInt(), 400),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        assertTrue("应选暖色 H=${hue(bg)} bg=${Integer.toHexString(bg)}", isWarm(hue(bg)) && hue(bg) in 0f..60f)
    }

    // 10. 绿色 → 绿
    @Test fun greenCover_green() {
        val sw = listOf(
            SwatchData(0x3CB371.toInt(), 3000), SwatchData(0x40A878.toInt(), 2000),
            SwatchData(0x1A2030.toInt(), 300),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        assertTrue("应选绿色 H=${hue(bg)} bg=${Integer.toHexString(bg)}", isGreen(hue(bg)))
    }
}

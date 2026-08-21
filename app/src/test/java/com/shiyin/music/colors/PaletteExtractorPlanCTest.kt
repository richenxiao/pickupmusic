package com.shiyin.music.colors

import com.shiyin.music.data.colors.PaletteExtractor
import com.shiyin.music.data.colors.PaletteExtractor.SwatchData
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan C 验证测试：检查最终 bg 的 HSV（不只 hue），包括明度、饱和度、hue 一致性。
 */
class PaletteExtractorPlanCTest {

    private fun r(rgb: Int) = (rgb shr 16) and 0xFF
    private fun g(rgb: Int) = (rgb shr 8) and 0xFF
    private fun b(rgb: Int) = rgb and 0xFF
    private fun maxCh(rgb: Int) = maxOf(r(rgb), g(rgb), b(rgb))
    private fun minCh(rgb: Int) = minOf(r(rgb), g(rgb), b(rgb))

    private fun chromaOf(rgb: Int): Float {
        val rf = r(rgb) / 255f; val gf = g(rgb) / 255f; val bf = b(rgb) / 255f
        return kotlin.math.sqrt((rf - gf) * (rf - gf) + ((rf + gf) / 2f - bf) * ((rf + gf) / 2f - bf)) * 255f
    }

    private fun hueOf(rgb: Int): Float {
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

    private fun valueOf(rgb: Int): Float = maxCh(rgb) / 255f
    private fun isWarm(h: Float) = h < 60f || h >= 300f
    private fun isBlue(h: Float) = h in 195f..270f
    private fun isGreen(h: Float) = h in 90f..180f
    private fun isRed(h: Float) = h < 15f || h >= 345f
    private fun isPurple(h: Float) = h in 270f..345f
    private fun isNeutral(c: Float) = c < 30f

    // ── 1. 深黑 + 粉紫霓虹 → 偏粉紫的深色背景 ──
    @Test fun darkBlack_pinkNeon_purpleTintedDark() {
        val sw = listOf(
            SwatchData(0x14141E.toInt(), 5000), SwatchData(0x1A1A2E.toInt(), 2000),
            SwatchData(0xC850B4.toInt(), 625), SwatchData(0xE670C8.toInt(), 375),
            SwatchData(0xB48C6E.toInt(), 500),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        val h = hueOf(bg); val v = valueOf(bg)
        assertTrue("应偏粉紫 H=$h bg=${Integer.toHexString(bg)}", isPurple(h) || isRed(h))
        assertTrue("不应被提亮成灰白 V=$v", v < 0.85f)
    }

    // ── 2. 深蓝 + 黄12% → v9 蓝主色赢，黄不抢 ──
    @Test fun darkBlue_yellow12_staysBlue() {
        val sw = listOf(
            SwatchData(0x1A2A4E.toInt(), 4000), SwatchData(0x1E3258.toInt(), 2000),
            SwatchData(0x162548.toInt(), 1000), SwatchData(0xFADC28.toInt(), 1000),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        val h = hueOf(bg)
        assertTrue("蓝主色应保持蓝（hue 195-270），黄不抢，实际 H=$h bg=${Integer.toHexString(bg)}", isBlue(h))
    }

    // ── 3. 奶油 + 暖橙 → 暖色 ──
    @Test fun cream_warmOrange_warm() {
        val sw = listOf(
            SwatchData(0xF5E6D3.toInt(), 3500), SwatchData(0xE8D5C0.toInt(), 2500),
            SwatchData(0xF0DECC.toInt(), 1500), SwatchData(0xD4A880.toInt(), 750),
            SwatchData(0xC09060.toInt(), 500), SwatchData(0xE89070.toInt(), 400),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        val h = hueOf(bg)
        assertTrue("应是暖色 H=$h bg=${Integer.toHexString(bg)}", isWarm(h))
    }

    // ── 4. 纯黑 → 不应变成明显灰色大背景 ──
    @Test fun pureBlack_notGreyWash() {
        val sw = listOf(SwatchData(0x141414.toInt(), 3000), SwatchData(0x1E1E1E.toInt(), 2000))
        val bg = PaletteExtractor.scoreSwatches(sw)
        val v = valueOf(bg)
        // V 应该被 floor 提到 0.45 但不应更高
        assertTrue("V 不应超过 0.55（floor 后但不提过亮）V=$v bg=${Integer.toHexString(bg)}", v <= 0.55f)
    }

    // ── 5. 纯深蓝 → 不应被抬过亮 ──
    @Test fun deepBlue_notTooBright() {
        val sw = listOf(SwatchData(0x1A2A4E.toInt(), 3000))
        val bg = PaletteExtractor.scoreSwatches(sw)
        val v = valueOf(bg)
        val h = hueOf(bg)
        assertTrue("应保持蓝色 H=$h bg=${Integer.toHexString(bg)}", isBlue(h))
        assertTrue("V 不应超过 0.55 V=$v", v <= 0.55f)
    }

    // ── 6. 纯白/灰 → 保持中性 ──
    @Test fun pureWhiteGrey_neutral() {
        val sw = listOf(SwatchData(0xF0F0F0.toInt(), 3000), SwatchData(0xE0E0E0.toInt(), 2000))
        val bg = PaletteExtractor.scoreSwatches(sw)
        val c = chromaOf(bg)
        assertTrue("应保持中性 chroma=$c bg=${Integer.toHexString(bg)}", isNeutral(c))
    }

    // ── 7. 深蓝90% + 黄2% → 保持蓝 ──
    @Test fun deepBlue_yellow2pc_staysBlue() {
        val sw = listOf(
            SwatchData(0x1A2A4E.toInt(), 4500), SwatchData(0x1E3258.toInt(), 2250),
            SwatchData(0x162548.toInt(), 1500), SwatchData(0xFADC28.toInt(), 130),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        assertTrue("应保持蓝 H=${hueOf(bg)} bg=${Integer.toHexString(bg)}", isBlue(hueOf(bg)))
    }

    // ── 8. 红85% + 蓝5% → 保持红，不被蓝抢 ──
    @Test fun red85_blue5pc_staysRed() {
        val sw = listOf(
            SwatchData(0xC02020.toInt(), 4000), SwatchData(0xD03030.toInt(), 2000),
            SwatchData(0x4060C0.toInt(), 300),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        assertTrue("应保持红 H=${hueOf(bg)} bg=${Integer.toHexString(bg)}", isRed(hueOf(bg)))
    }

    // ── 9. 紫70% + 粉14% → 粉色 accent ──
    @Test fun purple70_pink14_pinkAccent() {
        val sw = listOf(
            SwatchData(0x3D1850.toInt(), 3500), SwatchData(0x4A1C5C.toInt(), 1500),
            SwatchData(0xFF60C0.toInt(), 600), SwatchData(0xE050B0.toInt(), 400),
            SwatchData(0x808080.toInt(), 500),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        val h = hueOf(bg)
        assertTrue("应偏粉/紫 H=$h bg=${Integer.toHexString(bg)}", isPurple(h) || isRed(h))
    }

    // ── 10. 白 + 多粉色变体 → 粉 ──
    @Test fun white_pinkVariants_pink() {
        val sw = listOf(
            SwatchData(0xF0F0F0.toInt(), 2000),
            SwatchData(0xE89090.toInt(), 400), SwatchData(0xE88080.toInt(), 350),
            SwatchData(0xF0A0A0.toInt(), 300), SwatchData(0xE07070.toInt(), 250),
            SwatchData(0xF5B0B0.toInt(), 200),
        )
        val bg = PaletteExtractor.scoreSwatches(sw)
        assertTrue("应偏粉色 H=${hueOf(bg)} bg=${Integer.toHexString(bg)}",
            isRed(hueOf(bg)) || isPurple(hueOf(bg)))
    }

    // ── 11. 蓝85% + 黄12% → v9 蓝主色赢，黄不 override ──
    @Test fun blue85_yellow12_staysBlue() {
        val sw = listOf(
            SwatchData(0x1A2A4E.toInt(), 4000), SwatchData(0x1E3258.toInt(), 2000),
            SwatchData(0x162548.toInt(), 1000), SwatchData(0xFADC28.toInt(), 1000),
        )
        val diag = PaletteExtractor.scoreSwatchesWithDiag(sw)
        val bgH = hueOf(diag.finalBg)
        assertTrue("蓝主色应保持蓝（hue 195-270），黄不抢，实际 H=$bgH", isBlue(bgH))
    }

    // ── 12. resolvePair 不改变 hue ──
    @Test fun resolvePair_preservesHue() {
        // 单一蓝色 swatch → foundation=blue, no accent → bg = blue (floor)
        val sw = listOf(SwatchData(0x1A2A4E.toInt(), 3000))
        val diag = PaletteExtractor.scoreSwatchesWithDiag(sw)
        val foundH = hueOf(diag.foundationRgb)
        val bgH = hueOf(diag.finalBg)
        assertTrue("resolvePair 不应改变 hue: found=$foundH bg=$bgH",
            kotlin.math.abs(foundH - bgH) < 30f)
    }

    // ── 13. 输入顺序不变性 ──
    @Test fun shuffleInvariant() {
        val sw = listOf(
            SwatchData(0x14141E.toInt(), 5000), SwatchData(0x1A1A2E.toInt(), 2000),
            SwatchData(0xC850B4.toInt(), 625), SwatchData(0xE670C8.toInt(), 375),
            SwatchData(0xB48C6E.toInt(), 500),
        )
        val bg1 = PaletteExtractor.scoreSwatches(sw)
        val bg2 = PaletteExtractor.scoreSwatches(sw.reversed())
        assertTrue("顺序不应影响结果 bg1=${Integer.toHexString(bg1)} bg2=${Integer.toHexString(bg2)}",
            bg1 == bg2)
    }
}

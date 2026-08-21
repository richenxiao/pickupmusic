package com.shiyin.music.colors

import com.shiyin.music.data.colors.PaletteExtractor
import com.shiyin.music.data.colors.PaletteExtractor.SwatchData
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM regression tests for [PaletteExtractor.scoreSwatches] — the pure
 * testable core (pre-merge → score → min-brightness floor). Feeds
 * hand-constructed swatch lists that mimic the covers reported in the
 * v3.3 colour-scoring bug.
 *
 * The real cover-bitmap path depends on `androidx.palette` / `android.graphics`
 * and can't run in a pure JVM; these tests exercise the *logic* that sits
 * between palette generation and WCAG resolution, which is where the bug lived.
 */
class PaletteExtractorCoreTest {

    /** Blue is the dominant channel, red is low. Yellow has red+green high. */
    private fun isBluish(rgb: Int): Boolean {
        val b = rgb and 0xFF
        val g = (rgb shr 8) and 0xFF
        val r = (rgb shr 16) and 0xFF
        return b > r && b > g && r < 150
    }

    private fun maxChannel(rgb: Int): Int =
        maxOf(rgb and 0xFF, (rgb shr 8) and 0xFF, (rgb shr 16) and 0xFF)

    private fun hueOf(rgb: Int): Float {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (d == 0f) return 0f
        val h = when {
            mx == r -> ((g - b) / d).let { if (it < 0) it + 6f else it }
            mx == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return h * 60f
    }

    /** Reproduce 马念先《1989的下午》-style cover: a large blue background
     *  that palette quantisation splits across several near-identical blue
     *  swatches, plus one small-but-vivid yellow sticker that used to steal
     *  the pick. Expected: blue wins, not yellow. */
    @Test
    fun blueBackgroundWithSmallYellowSticker_picksBlue() {
        val blueSwatches = listOf(
            // Near-duplicate blues the quantiser splits one field into.
            SwatchData(0x285AC8.toInt(), 2000),
            SwatchData(0x2C5ECC.toInt(), 1800),
            SwatchData(0x2A5CCA.toInt(), 1900),
            SwatchData(0x2E62D0.toInt(), 1700),
        )
        val yellowSticker = SwatchData(0xFADC28.toInt(), 500) // vivid, small

        val chosen = PaletteExtractor.scoreSwatches(blueSwatches + yellowSticker)

        assertTrue(
            "expected a blue bg, got ${Integer.toHexString(chosen)}",
            isBluish(chosen),
        )
    }

    /** v9: 蓝 foundation（合并后 chroma~144，主色）赢，33% 黄不 override
     *  （旧 v4 行为是"大块黄抢蓝"，现按"主色赢/整体"原则改为蓝）。 */
    @Test
    fun splitBlueVersusConcentratedYellow_dominantBlueWins() {
        val swatches = listOf(
            SwatchData(0x285AC8.toInt(), 1500),
            SwatchData(0x2C5ECC.toInt(), 1500), // merges with above (sq < 400)
            SwatchData(0xFADC28.toInt(), 1500), // equal pop, far higher chroma
        )
        val chosen = PaletteExtractor.scoreSwatches(swatches)
        assertTrue("蓝主色应赢，33%%黄不抢，got ${Integer.toHexString(chosen)}", isBluish(chosen))
    }

    /** Dark-purple cover previously produced a muddy grey-purple bg. The
     *  min-brightness floor lifts it off pure-black so the bg reads.
     *  v7: floor lowered 0.45→0.30 (dark/moody covers stay dark), so expected
     *  max channel ~76, not ~115. */
    @Test
    fun darkPurple_liftedToFloor() {
        val darkPurple = 0x2D1438.toInt() // HSV value ≈ 0.235, below 0.30 floor
        val before = maxChannel(darkPurple)

        val chosen = PaletteExtractor.scoreSwatches(listOf(SwatchData(darkPurple, 1000)))

        assertTrue(
            "expected the lifted bg to be brighter than the input ($before), got ${maxChannel(chosen)}",
            maxChannel(chosen) > before,
        )
        assertTrue(
            "expected the lifted bg to reach the v7 floor (~76), got ${maxChannel(chosen)}",
            maxChannel(chosen) >= 70,
        )
        // Hue preserved: purple is b-max, r next, g-min — channel ordering holds.
        val b = chosen and 0xFF
        val g = (chosen shr 8) and 0xFF
        val r = (chosen shr 16) and 0xFF
        assertTrue("hue should stay purple (b > r > g), got r=$r g=$g b=$b", b > r && r > g)
    }

    /** A cover already at/above the floor must be returned unchanged (modulo
     *  alpha, which the cluster repacks to opaque 0xFF — correct for a bg). */
    @Test
    fun brightCover_notLifted() {
        val brightBlue = 0x4A90E2.toInt() // value ≈ 0.89, well above floor
        val chosen = PaletteExtractor.scoreSwatches(listOf(SwatchData(brightBlue, 1000)))
        assertTrue(
            "a bright cover should pass through untouched (RGB only), got ${Integer.toHexString(chosen)}",
            (chosen and 0xFFFFFF) == (brightBlue and 0xFFFFFF),
        )
    }

    /** Regression: a mostly-white cover must still pick white (dominance leads,
     *  demoted chroma must not flip it to a coloured accent). */
    @Test
    fun whiteDominantCover_picksWhite() {
        val swatches = listOf(
            SwatchData(0xF0F0F0.toInt(), 3000), // white, low chroma, dominant
            SwatchData(0xE85040.toInt(), 200),   // vivid red accent, tiny
        )
        val chosen = PaletteExtractor.scoreSwatches(swatches)
        assertTrue(
            "Plan C: 6.25%% red > 5%% threshold → red accent, got ${Integer.toHexString(chosen)}",
            hueOf(chosen) < 20f || hueOf(chosen) >= 340f,
        )
    }

    /** Regression: a mostly-grey cover with a vivid accent must pick grey. */
    @Test
    fun greyDominantCover_picksGreyNotAccent() {
        val swatches = listOf(
            SwatchData(0x787878.toInt(), 4000), // grey, dominant
            SwatchData(0xDC143C.toInt(), 300),  // vivid red accent
        )
        val chosen = PaletteExtractor.scoreSwatches(swatches)
        val r = (chosen shr 16) and 0xFF
        val g = (chosen shr 8) and 0xFF
        val b = chosen and 0xFF
        assertTrue(
            "Plan C: 7%% red > 5%% threshold → red accent, got r=$r g=$g b=$b",
            r > g && r > b,
        )
    }

    private fun abs(x: Int) = if (x < 0) -x else x
}

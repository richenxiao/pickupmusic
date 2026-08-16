package com.shiyin.music.data.colors

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v3.3: Extract a (background, foreground) color pair from an album-cover
 * bitmap for **adaptive-contrast** lyric/player tinting.
 *
 * ## The adaptive idea
 *
 * Earlier versions forced every background dark so white text was legible,
 * which turned light/gold covers into muddy grey-brown. v3.3 keeps the bg
 * true to the cover's main colour and instead picks the text colour to match:
 *
 *   - light cover  → light bg  → **dark text**  (dark-grey body, black active)
 *   - dark  cover  → dark bg    → **light text** (white body, bright active)
 *
 * The returned `fgArgb` is whichever of pure-black / pure-white clears WCAG
 * 4.5:1 against the chosen bg; the bg itself is nudged toward black or white
 * **only when neither end clears** (mid-tone backgrounds — medium greens,
 * dusty blues). So a gold cover stays gold, a white cover stays white; only
 * the genuinely awkward middles get pulled.
 *
 * ## Scoring (which swatch is "the cover's main colour")
 *
 *   score = 4.0 × dominance + 1.5 × chroma + 0.5 × darkness
 *
 *  - **dominance** (population share) — leading term; the bg is the cover's
 *    main colour, not a corner accent. Weight raised from 3.0 → 4.0 so a
 *    small-but-vivid sticker can no longer out-score the true majority.
 *  - **chroma** — keeps a vivid dominant colour; demoted 2.0 → 1.5 so high-
 *    saturation accents don't steal the pick. Still breaks near-ties.
 *  - **darkness** — tiny tiebreaker only; the picker never *prefers* dark.
 *
 * ## Swatch pre-merge (root-cause fix for the "blue cover → yellow bg" bug)
 *
 * `androidx.palette` quantises to ~16 swatches. A large blue background splits
 * across several near-identical blue swatches (each diluted population), while
 * a small-but-distinct yellow accent concentrates in one swatch with high
 * chroma — so per-swatch scoring let the accent win even though blue covered
 * most of the image. Before scoring we greedily cluster swatches whose RGB
 * distance is below [MERGE_SQ_DISTANCE] and sum their populations, defeating
 * the split. Dominance then reflects the *true* colour share.
 *
 * ## Minimum-brightness floor
 *
 * A chosen bg whose HSV value falls below [MIN_VALUE_FLOOR] is lifted to that
 * floor (hue + saturation preserved) **before** contrast resolution, so dark-
 * purple covers no longer read as muddy grey-purple. This runs before the WCAG
 * path, which is otherwise untouched: `resolvePair` still picks whichever text
 * clears 4.5:1 against the (possibly lifted) bg and still nudges mid-tones.
 *
 * ## Foreground / contrast guarantee
 *
 * `fgArgb` is chosen to clear 4.5:1 against `bgArgb`. The bg is nudged in HSV
 * (value only, ×0.94 per step, min amount) when neither black nor white text
 * would pass on the raw swatch — i.e. mid-tones only. Light + dark covers keep
 * their hue unchanged.
 */
object PaletteExtractor {

    /** (bg, fg) pair — fg is whichever end (black/white) is contrast-safe on bg. */
    data class ColorPair(val bgArgb: Int, val fgArgb: Int)

    /** Plain swatch input for the testable core — no Android types. */
    data class SwatchData(val rgb: Int, val population: Int)

    /** WCAG target the (bg, fg) pair must clear. */
    private const val MIN_CONTRAST = 4.5f

    /** Dark fallback (dark-bg, light-text mode) when nothing resolves. */
    private const val FALLBACK_BG_DARK = 0xFF2A2A2E.toInt()
    /** Light fallback (light-bg, dark-text mode) — unused currently but reserved. */
    private const val FALLBACK_BG_LIGHT = 0xFFEFEFEF.toInt()

    private const val BLACK_TEXT = 0xFF1A1A1E.toInt()
    private const val WHITE_TEXT = 0xFFFFFFFF.toInt()

    /** Scoring weights — dominance leads harder; chroma demoted so vivid
     *  accents can't steal the pick. Pre-merge makes dominance meaningful. */
    private const val W_DOMINANCE = 4.0f
    private const val W_CHROMA = 1.5f
    private const val W_DARKNESS = 0.5f

    /** Min HSV value floor. A chosen bg darker than this is lifted toward
     *  the floor (hue + saturation preserved) before contrast resolution. */
    private const val MIN_VALUE_FLOOR = 0.30f

    /** Squared-RGB distance under which two swatches are treated as the same
     *  colour split across palette quantisation and pre-merged. */
    private const val MERGE_SQ_DISTANCE = 400f

    /**
     * Extract a contrast-safe (bg, fg) pair. [fgArgb] is guaranteed to clear
     * ≥ [MIN_CONTRAST] against [bgArgb]. Returns null only when [bitmap] is
     * null; palette failures degrade to the dark fallback.
     */
    fun extract(
        bitmap: Bitmap?,
        @Suppress("UNUSED_PARAMETER") defaultFgArgb: Int = WHITE_TEXT,
    ): ColorPair? {
        if (bitmap == null) return null
        val palette = Palette.from(bitmap).generate()

        val swatches = palette.swatches
            .filter { it.population > 0 }
            .map { SwatchData(it.rgb, it.population) }
        if (swatches.isEmpty()) return ColorPair(FALLBACK_BG_DARK, WHITE_TEXT)

        val chosen = scoreSwatches(swatches)
        return resolvePair(chosen)
    }

    /**
     * Pure testable core — no Android dependencies. Pre-merge near-duplicate
     * swatches, score, pick the winner, apply the min-brightness floor. Feeds
     * straight RGB ints so JVM unit tests can assert behaviour directly.
     */
    internal fun scoreSwatches(swatches: List<SwatchData>): Int {
        if (swatches.isEmpty()) return FALLBACK_BG_DARK
        val merged = preMerge(swatches)
        val totalPop = merged.sumOf { it.population.toLong() }.coerceAtLeast(1).toFloat()

        val best = merged.maxByOrNull { sw ->
            val (r, g, b) = unpack(sw.rgb)
            val rg = r - g
            val yb = (r + g) / 2f - b
            val chroma = sqrt(rg * rg + yb * yb)
            val lum = sqrt(0.299f * r * r + 0.587f * g * g + 0.114f * b * b)
            val darkness = 1f - lum
            val dominance = sw.population / totalPop
            W_DOMINANCE * dominance + W_CHROMA * chroma + W_DARKNESS * darkness
        } ?: return FALLBACK_BG_DARK

        return applyBrightnessFloor(best.rgb)
    }

    /**
     * Greedy population-weighted clustering: each swatch (heaviest first) joins
     * the nearest existing cluster whose representative colour is within
     * [MERGE_SQ_DISTANCE] (squared RGB); otherwise seeds a new cluster. Members
     * are averaged weighted by population so the merged colour tracks the true
     * majority. Defeats palette quantisation splitting one colour across swatches.
     */
    private fun preMerge(swatches: List<SwatchData>): List<SwatchData> {
        val clusters = ArrayList<MutableCluster>()
        for (sw in swatches.sortedByDescending { it.population }) {
            val (r, g, b) = unpack(sw.rgb)
            val host = clusters.firstOrNull { sqDist(it.repRgb, r, g, b) < MERGE_SQ_DISTANCE }
            if (host != null) host.add(sw.rgb, sw.population)
            else clusters += MutableCluster(sw.rgb, sw.population)
        }
        return clusters.map { it.toSwatch() }
    }

    /** Population-weighted running cluster of near-duplicate colours. */
    private class MutableCluster(rgb: Int, population: Int) {
        private var popSum = population
        private var rAcc = channel(rgb, 16) * population
        private var gAcc = channel(rgb, 8) * population
        private var bAcc = channel(rgb, 0) * population
        /** Representative colour for distance checks (current weighted avg). */
        val repRgb: Int
            get() = pack(rAcc / popSum, gAcc / popSum, bAcc / popSum)
        fun add(rgb: Int, population: Int) {
            popSum += population
            rAcc += channel(rgb, 16) * population
            gAcc += channel(rgb, 8) * population
            bAcc += channel(rgb, 0) * population
        }
        fun toSwatch() = SwatchData(repRgb, popSum)
    }

    /** Lift HSV value up to [MIN_VALUE_FLOOR] when the chosen bg is darker;
     *  hue and saturation preserved. No-op when already bright enough. */
    private fun applyBrightnessFloor(rgb: Int): Int {
        val hsv = rgbToHsv(rgb)
        if (hsv[2] >= MIN_VALUE_FLOOR) return rgb
        hsv[2] = MIN_VALUE_FLOOR
        return hsvToRgb(hsv)
    }

    /**
     * Given a chosen bg, return a (bg, fg) pair that clears [MIN_CONTRAST].
     * - If white text passes on the raw bg → keep bg, fg = white (dark-cover mode).
     * - Else if black text passes on the raw bg → keep bg, fg = black (light-cover mode).
     * - Else (mid-tone) nudge bg toward whichever end lets opposite text pass,
     *   using the *minimum* HSV value move (×0.94/step toward black, ÷0.94 toward white).
     */
    private fun resolvePair(rawBg: Int): ColorPair {
        val contrastVsWhite = contrastRatioVs(rawBg, WHITE_TEXT)
        val contrastVsBlack = contrastRatioVs(rawBg, BLACK_TEXT)

        if (contrastVsWhite >= MIN_CONTRAST) return ColorPair(rawBg, WHITE_TEXT)
        if (contrastVsBlack >= MIN_CONTRAST) return ColorPair(rawBg, BLACK_TEXT)

        // Mid-tone: neither end passes on the raw colour. Try darkening first
        // (keeps coloured mood slightly richer than lightening to pastel); if
        // no amount of darkening lets white text pass, try lightening so black
        // text passes. Pick whichever resolves with the smaller value move.
        val darkened = nudgeToward(rawBg, towardBlack = true)
        if (darkened != null) {
            val cm = contrastRatioVs(darkened, WHITE_TEXT)
            if (cm >= MIN_CONTRAST) return ColorPair(darkened, WHITE_TEXT)
        }
        val lightened = nudgeToward(rawBg, towardBlack = false)
        if (lightened != null) {
            val cm = contrastRatioVs(lightened, BLACK_TEXT)
            if (cm >= MIN_CONTRAST) return ColorPair(lightened, BLACK_TEXT)
        }
        // Should be mathematically unreachable; dark fallback.
        return ColorPair(FALLBACK_BG_DARK, WHITE_TEXT)
    }

    /**
     * Nudge [rgb]'s HSV value in steps (×/÷0.94, ≤ 24 steps) until the opposite-
     * end text clears 4.5:1. Returns the minimal-move colour, or null if it
     * can't resolve. Hue and saturation are preserved.
     */
    private fun nudgeToward(rgb: Int, towardBlack: Boolean): Int? {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (rgb shr 16) and 0xFF,
            (rgb shr 8) and 0xFF,
            rgb and 0xFF,
            hsv,
        )
        var value = hsv[2]
        val targetText = if (towardBlack) WHITE_TEXT else BLACK_TEXT
        repeat(24) {
            value = if (towardBlack) value * 0.94f else value / 0.94f
            if (value <= 0.02f) value = 0.02f
            if (value >= 0.99f) value = 0.99f
            val c = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], value))
            if (contrastRatioVs(c, targetText) >= MIN_CONTRAST) return c
        }
        return null
    }

    private fun contrastRatioVs(rgb: Int, otherArgb: Int): Float =
        contrastRatio(luminance(rgb), luminance(otherArgb))

    /** Relative luminance per WCAG 2.1 — sRGB linearization. */
    private fun luminance(rgb: Int): Float {
        val r = linearize(((rgb shr 16) and 0xFF) / 255f)
        val g = linearize(((rgb shr 8) and 0xFF) / 255f)
        val b = linearize((rgb and 0xFF) / 255f)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun linearize(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f
        else java.lang.Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    /** WCAG contrast ratio: (L1+0.05) / (L2+0.05). Both values in [0,1]. */
    private fun contrastRatio(l1: Float, l2: Float): Float {
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    // ---- pure-Kotlin colour helpers (no Android) for the testable core ----

    private fun unpack(rgb: Int): Triple<Float, Float, Float> = Triple(
        ((rgb shr 16) and 0xFF) / 255f,
        ((rgb shr 8) and 0xFF) / 255f,
        (rgb and 0xFF) / 255f,
    )

    private fun channel(rgb: Int, shift: Int): Int = (rgb shr shift) and 0xFF

    private fun pack(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    private fun sqDist(rgb: Int, r: Float, g: Float, b: Float): Float {
        val dr = channel(rgb, 16) - r * 255f
        val dg = channel(rgb, 8) - g * 255f
        val db = channel(rgb, 0) - b * 255f
        return dr * dr + dg * dg + db * db
    }

    /** RGB→HSV (h 0..360, s/v 0..1). Pure Kotlin, no android.graphics.Color. */
    private fun rgbToHsv(rgb: Int): FloatArray {
        val r = channel(rgb, 16) / 255f
        val g = channel(rgb, 8) / 255f
        val b = channel(rgb, 0) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val v = max
        val s = if (max <= 0f) 0f else d / max
        val h = when {
            d == 0f -> 0f
            max == r -> ((g - b) / d).let { if (it < 0) it + 6f else it }
            max == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return floatArrayOf(h * 60f, s, v)
    }

    /** HSV→ARGB. Pure Kotlin, no android.graphics.Color. */
    private fun hsvToRgb(hsv: FloatArray): Int {
        val h = (hsv[0] / 60f).let { if (it >= 6f) 0f else it }
        val s = hsv[1]
        val v = hsv[2]
        val c = v * s
        val x = c * (1f - abs((h % 2f) - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 1f -> Triple(c, x, 0f)
            h < 2f -> Triple(x, c, 0f)
            h < 3f -> Triple(0f, c, x)
            h < 4f -> Triple(0f, x, c)
            h < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return pack(
            ((r + m) * 255f + 0.5f).toInt(),
            ((g + m) * 255f + 0.5f).toInt(),
            ((b + m) * 255f + 0.5f).toInt(),
        )
    }
}

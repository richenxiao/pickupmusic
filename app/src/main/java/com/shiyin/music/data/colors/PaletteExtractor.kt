package com.shiyin.music.data.colors

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
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
 *   score = 3.0 × dominance + 2.0 × chroma + 0.5 × darkness
 *
 *  - **dominance** (population share) — leading term; the bg is the cover's
 *    main colour, not a corner accent.
 *  - **chroma** — keeps a vivid dominant colour; grey-but-dominant (white bg)
 *    still wins but a vivid equal-share takes the tiebreak.
 *  - **darkness** — tiny tiebreaker only; the picker no longer *prefers* dark.
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

    /** WCAG target the (bg, fg) pair must clear. */
    private const val MIN_CONTRAST = 4.5f

    /** Dark fallback (dark-bg, light-text mode) when nothing resolves. */
    private const val FALLBACK_BG_DARK = 0xFF2A2A2E.toInt()
    /** Light fallback (light-bg, dark-text mode) — unused currently but reserved. */
    private const val FALLBACK_BG_LIGHT = 0xFFEFEFEF.toInt()

    private const val BLACK_TEXT = 0xFF1A1A1E.toInt()
    private const val WHITE_TEXT = 0xFFFFFFFF.toInt()

    /** Scoring weights — dominance leads so the bg is the cover's main colour. */
    private const val W_DOMINANCE = 3.0f
    private const val W_CHROMA = 2.0f
    private const val W_DARKNESS = 0.5f

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

        val candidates = palette.swatches.filter { it.population > 0 }
        if (candidates.isEmpty()) return ColorPair(FALLBACK_BG_DARK, WHITE_TEXT)

        val totalPop = candidates.sumOf { it.population.toLong() }.coerceAtLeast(1).toFloat()

        val best = candidates.maxByOrNull { sw ->
            val rgb = sw.rgb
            val r = ((rgb shr 16) and 0xFF) / 255f
            val g = ((rgb shr 8) and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            val rg = r - g
            val yb = (r + g) / 2f - b
            val chroma = sqrt(rg * rg + yb * yb)
            val lum = sqrt(0.299f * r * r + 0.587f * g * g + 0.114f * b * b)
            val darkness = 1f - lum
            val dominance = sw.population / totalPop
            W_DOMINANCE * dominance + W_CHROMA * chroma + W_DARKNESS * darkness
        }!!

        return resolvePair(best.rgb)
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
}

package com.shiyin.music.data.colors

import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Plan C — Visual Accent Color extraction.
 *
 * 不找"population 最大"也不找"chroma 最大"，而是找：
 * "面积足够 + 色彩足够明显 + 与整体背景有明显视觉对比"的真实颜色。
 *
 * ## 两步架构
 *
 * 1. **Background foundation**: preMerge 后 population 最大的 cluster。决定背景明度。
 * 2. **Accent color**: 对每个非 foundation cluster，计算 Visual Accent Score。
 *    accent 提供 hue/saturation；foundation 提供 value。
 *
 * ## Visual Accent Score（加权和，不乘积，避免 score 通缩）
 *
 * ```
 * raw_score = 0.40 × share + 0.35 × chromaNorm + 0.25 × brightnessContrast
 * score = raw_score × extremePenalty × areaGate
 * ```
 *
 * - share: population / totalPop [0,1]
 * - chromaNorm: chroma / 255 [0,1]
 * - brightnessContrast: |lum(swatch) - lum(foundation)| [0,1]
 * - extremePenalty: 0 if V<0.08 or (V>0.95 and S<0.05), else 1
 * - areaGate: hard gate — 1 if share >= pop_threshold, else 0
 *   - pop_threshold = 0.10 if foundation chroma >= 80 (strong color)
 *   - pop_threshold = 0.05 otherwise (weak/neutral foundation)
 *
 * ## 最终 bg 生成
 *
 * - 有 accent: bgH = accent hue, bgS = min(accent sat × 0.6, 0.85),
 *   bgV = max(foundation V × 0.7, 0.35) → brightness floor → resolvePair
 * - 无 accent: bg = foundation → brightness floor → resolvePair
 *
 * resolvePair 保证 fg 对比度 ≥ 4.5:1，只调 V 不调 hue/saturation。
 */
object PaletteExtractor {

    data class ColorPair(val bgArgb: Int, val fgArgb: Int)
    data class SwatchData(val rgb: Int, val population: Int)

    /** 诊断结果：含 foundation、accent、最终 bg 的完整 HSV 信息。 */
    data class ExtractionDiagnostic(
        val mergedSwatches: List<SwatchDetail>,
        val chosenRgb: Int,
        val chosenHsv: FloatArray,
        val finalBg: Int,
        val foundationRgb: Int = 0,
        val foundationHsv: FloatArray = floatArrayOf(0f, 0f, 0f),
        val accentRgb: Int = 0,
        val accentHsv: FloatArray = floatArrayOf(0f, 0f, 0f),
        val hasAccent: Boolean = false,
    )
    data class SwatchDetail(
        val rgb: Int,
        val population: Int,
        val populationShare: Float,
        val score: Float,
        val isChosen: Boolean,
    )

    private const val MIN_CONTRAST = 4.5f
    private const val FALLBACK_BG_DARK = 0xFF2A2A2E.toInt()
    private const val FALLBACK_BG_LIGHT = 0xFFEFEFEF.toInt()
    private const val BLACK_TEXT = 0xFF1A1A1E.toInt()
    private const val WHITE_TEXT = 0xFFFFFFFF.toInt()

    // ── Plan C constants ──
    /** preMerge: squared-RGB distance under which two swatches merge. */
    private const val MERGE_SQ_DISTANCE = 1500f

    /** Brightness floor: final bg V lifted to this if below (hue preserved). */
    private const val MIN_VALUE_FLOOR = 0.45f

    /** Accent score threshold: score must exceed this for accent to be selected. */
    private const val ACCENT_SCORE_THRESHOLD = 0.05f

    /** Foundation chroma at/above which the accent population threshold is raised. */
    private const val STRONG_FOUNDATION_CHROMA = 80f

    /** Population threshold for accent when foundation is weak/neutral. */
    private const val WEAK_FOUNDATION_POP_THRESHOLD = 0.05f

    /** Population threshold for accent when foundation is strong (vivid). */
    private const val STRONG_FOUNDATION_POP_THRESHOLD = 0.10f

    /** Minimum chroma for accent candidates — prevents white/grey from stealing accent. */
    private const val COLOR_CANDIDATE_CHROMA = 40f

    /** Visual Accent Score weights. */
    private const val W_SHARE = 0.40f
    private const val W_CHROMA = 0.35f
    private const val W_BRIGHTNESS_CONTRAST = 0.25f

    /** bg saturation cap (accent sat × 0.6, capped). */
    private const val BG_SAT_RATIO = 0.6f
    private const val BG_SAT_MAX = 0.85f

    /** bg value from foundation (foundation V × 0.7, min 0.35). */
    private const val BG_V_RATIO = 0.7f
    private const val BG_V_MIN = 0.35f

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

    internal fun scoreSwatches(swatches: List<SwatchData>): Int {
        return scoreSwatchesWithDiag(swatches).finalBg
    }

    internal fun scoreSwatchesWithDiag(swatches: List<SwatchData>): ExtractionDiagnostic {
        if (swatches.isEmpty()) {
            return ExtractionDiagnostic(emptyList(), FALLBACK_BG_DARK, floatArrayOf(0f, 0f, 0f), FALLBACK_BG_DARK)
        }
        val merged = preMerge(swatches)
        val totalPop = merged.sumOf { it.population.toLong() }.coerceAtLeast(1).toFloat()

        // ── Step 1: foundation = population largest cluster ──
        val foundation = merged.maxByOrNull { it.population } ?: return ExtractionDiagnostic(emptyList(), FALLBACK_BG_DARK, floatArrayOf(0f, 0f, 0f), FALLBACK_BG_DARK)
        val foundationRgb = foundation.rgb
        val foundationLum = luminance(foundationRgb)
        val foundationChroma = chromaOf(foundationRgb)
        val foundationHsv = rgbToHsv(foundationRgb)

        // ── Step 2: dynamic population threshold based on foundation chroma ──
        val popThreshold = if (foundationChroma >= STRONG_FOUNDATION_CHROMA)
            STRONG_FOUNDATION_POP_THRESHOLD else WEAK_FOUNDATION_POP_THRESHOLD

        // ── Step 3: score each non-foundation cluster for Visual Accent ──
        val details = ArrayList<SwatchDetail>()
        var bestAccent: SwatchData? = null
        var bestAccentScore = 0f

        for (sw in merged) {
            if (sw.rgb == foundationRgb) {
                details.add(SwatchDetail(sw.rgb, sw.population, sw.population / totalPop, 0f, false))
                continue
            }
            val share = (sw.population / totalPop).coerceIn(0f, 1f)
            val cn = (chromaOf(sw.rgb) / 255f).coerceIn(0f, 1f)
            val bc = abs(luminance(sw.rgb) - foundationLum).coerceIn(0f, 1f)
            val hsv = rgbToHsv(sw.rgb)
            val v = hsv[2]; val s = hsv[1]
            // Extreme penalty: near-black or near-white → 0
            val ep = if (v < 0.08f || (v > 0.95f && s < 0.05f)) 0f else 1f
            // Chroma gate: prevent white/grey from being selected as accent
            val cg = if (chromaOf(sw.rgb) >= COLOR_CANDIDATE_CHROMA) 1f else 0f
            // Hard area gate
            val ag = if (share >= popThreshold) 1f else 0f
            val rawScore = W_SHARE * share + W_CHROMA * cn + W_BRIGHTNESS_CONTRAST * bc
            val score = rawScore * ep * cg * ag
            details.add(SwatchDetail(sw.rgb, sw.population, share, score, false))
            if (score > bestAccentScore) {
                bestAccentScore = score
                bestAccent = sw
            }
        }

        val hasAccent = bestAccent != null && bestAccentScore > ACCENT_SCORE_THRESHOLD
        val accentRgb = bestAccent?.rgb ?: foundationRgb
        val accentHsv = rgbToHsv(accentRgb)

        // ── Step 4: generate final bg ──
        val chosenRgb: Int
        if (hasAccent) {
            // bg hue = accent hue, sat reduced, V from foundation (darker for readability)
            chosenRgb = generateBg(foundationRgb, accentRgb)
        } else {
            chosenRgb = foundationRgb
        }

        // Mark chosen in details
        for (i in details.indices) {
            if (details[i].rgb == chosenRgb || (!hasAccent && details[i].rgb == foundationRgb)) {
                details[i] = details[i].copy(isChosen = true); break
            }
        }

        val lifted = applyBrightnessFloor(chosenRgb)
        val hsv = rgbToHsv(lifted)
        return ExtractionDiagnostic(
            mergedSwatches = details,
            chosenRgb = chosenRgb,
            chosenHsv = hsv,
            finalBg = lifted,
            foundationRgb = foundationRgb,
            foundationHsv = foundationHsv,
            accentRgb = accentRgb,
            accentHsv = accentHsv,
            hasAccent = hasAccent,
        )
    }

    /** Generate bg from foundation (value) + accent (hue/sat). */
    private fun generateBg(foundationRgb: Int, accentRgb: Int): Int {
        val fHsv = rgbToHsv(foundationRgb)
        val aHsv = rgbToHsv(accentRgb)
        val bgH = aHsv[0]
        val bgS = (aHsv[1] * BG_SAT_RATIO).coerceAtMost(BG_SAT_MAX)
        val bgV = (fHsv[2] * BG_V_RATIO).coerceAtLeast(BG_V_MIN)
        return hsvToRgb(floatArrayOf(bgH, bgS, bgV))
    }

    // ── preMerge (fixed seed) — unchanged from v4 ──

    private fun preMerge(swatches: List<SwatchData>): List<SwatchData> {
        val clusters = ArrayList<MutableCluster>()
        for (sw in swatches.sortedByDescending { it.population }) {
            val (r, g, b) = unpack(sw.rgb)
            val host = clusters.firstOrNull { sqDist(it.seedRgb, r, g, b) < MERGE_SQ_DISTANCE }
            if (host != null) host.add(sw.rgb, sw.population)
            else clusters += MutableCluster(sw.rgb, sw.population)
        }
        return clusters.map { it.toSwatch() }
    }

    private class MutableCluster(seedRgb: Int, population: Int) {
        val seedRgb: Int = seedRgb
        private var popSum = population
        private var rAcc = channel(seedRgb, 16) * population
        private var gAcc = channel(seedRgb, 8) * population
        private var bAcc = channel(seedRgb, 0) * population
        fun add(rgb: Int, population: Int) {
            popSum += population
            rAcc += channel(rgb, 16) * population
            gAcc += channel(rgb, 8) * population
            bAcc += channel(rgb, 0) * population
        }
        fun toSwatch() = SwatchData(pack(rAcc / popSum, gAcc / popSum, bAcc / popSum), popSum)
    }

    // ── Color helpers ──

    private fun applyBrightnessFloor(rgb: Int): Int {
        val hsv = rgbToHsv(rgb)
        if (hsv[2] >= MIN_VALUE_FLOOR) return rgb
        hsv[2] = MIN_VALUE_FLOOR
        return hsvToRgb(hsv)
    }

    private fun chromaOf(rgb: Int): Float {
        val (r, g, b) = unpack(rgb)
        val rg = r - g
        val yb = (r + g) / 2f - b
        return sqrt(rg * rg + yb * yb) * 255f
    }

    private fun resolvePair(rawBg: Int): ColorPair {
        val contrastVsWhite = contrastRatioVs(rawBg, WHITE_TEXT)
        val contrastVsBlack = contrastRatioVs(rawBg, BLACK_TEXT)
        if (contrastVsWhite >= MIN_CONTRAST) return ColorPair(rawBg, WHITE_TEXT)
        if (contrastVsBlack >= MIN_CONTRAST) return ColorPair(rawBg, BLACK_TEXT)
        val darkened = nudgeToward(rawBg, towardBlack = true)
        if (darkened != null && contrastRatioVs(darkened, WHITE_TEXT) >= MIN_CONTRAST)
            return ColorPair(darkened, WHITE_TEXT)
        val lightened = nudgeToward(rawBg, towardBlack = false)
        if (lightened != null && contrastRatioVs(lightened, BLACK_TEXT) >= MIN_CONTRAST)
            return ColorPair(lightened, BLACK_TEXT)
        return ColorPair(FALLBACK_BG_DARK, WHITE_TEXT)
    }

    private fun nudgeToward(rgb: Int, towardBlack: Boolean): Int? {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, hsv,
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

    private fun luminance(rgb: Int): Float {
        val r = linearize(((rgb shr 16) and 0xFF) / 255f)
        val g = linearize(((rgb shr 8) and 0xFF) / 255f)
        val b = linearize((rgb and 0xFF) / 255f)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun linearize(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f
        else java.lang.Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun contrastRatio(l1: Float, l2: Float): Float {
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    // ── pure-Kotlin color helpers ──

    private fun unpack(rgb: Int): Triple<Float, Float, Float> = Triple(
        ((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f,
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

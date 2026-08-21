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
 * ## v6 Neon accent boost (weak foundation only)
 *
 * 弱/中性 foundation（奶白/深灰）下，封面里小面积但极鲜亮的"霓虹"簇
 * （封面的视觉主角，常仅 ~4% 面积）会卡在 5% areaGate 被杀，让面积更大
 * 但低饱和的"普通色调"（衣服、棕褐）抢走 accent。v7 在弱+暗 foundation 下对
 * 饱和度 ≥ 0.55 的霓虹簇：放宽 area 门槛到 3%、chroma 项 ×1.3，使其能竞争。
 * 仅弱+暗 foundation 触发（亮底不压暗）；v4"强彩色封面上的小贴纸"修复（10% 门槛）保持不动。
 *
 * ## 最终 bg 生成
 *
 * - 有 accent: bgH = accent hue, bgS = min(accent sat × 0.6, 0.85),
 *   bgV = foundation V (v7 mood-preserving, clamped [0.30, 0.93]) → resolvePair
 * - 无 accent: bg = foundation 原色（vivid 底保持其色，不被低 chroma accent 搅灰）→ resolvePair
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
    /**
     * 单簇诊断明细。除最终 [score] 外，携带三维度中间值与各 gate，
     * 供图像诊断 harness 打印"为什么这个簇赢/输"。不参与评分公式本身。
     */
    data class SwatchDetail(
        val rgb: Int,
        val population: Int,
        val populationShare: Float,
        val score: Float,
        val isChosen: Boolean,
        val isFoundation: Boolean = false,
        // 三维度（与顶部公式对应）：dominance / chroma / darkness(=亮度对比)
        val chromaNorm: Float = 0f,
        val brightnessContrast: Float = 0f,
        val rawScore: Float = 0f,
        // 各 gate（1=放行, 0=杀死）
        val extremePenalty: Float = 1f,
        val chromaGate: Float = 1f,
        val areaGate: Float = 1f,
        // 霓虹诊断：HSV 饱和度与是否命中 v6 霓虹加权
        val saturation: Float = 0f,
        val neonBoostApplied: Boolean = false,
    )

    private const val MIN_CONTRAST = 4.5f
    private const val FALLBACK_BG_DARK = 0xFF2A2A2E.toInt()
    private const val FALLBACK_BG_LIGHT = 0xFFEFEFEF.toInt()
    private const val BLACK_TEXT = 0xFF1A1A1E.toInt()
    private const val WHITE_TEXT = 0xFFFFFFFF.toInt()

    // ── Plan C constants ──
    /** preMerge: squared-RGB distance under which two swatches merge. */
    private const val MERGE_SQ_DISTANCE = 1500f

    /** Brightness floor: final bg V lifted to this if below (hue preserved).
     *  v7: lowered 0.45→0.30 so dark/moody covers stay dark ("该暗可偏暗"). */
    private const val MIN_VALUE_FLOOR = 0.30f

    /** Accent score threshold: score must exceed this for accent to be selected. */
    private const val ACCENT_SCORE_THRESHOLD = 0.05f

    /** Foundation chroma at/above which the accent population threshold is raised. */
    private const val STRONG_FOUNDATION_CHROMA = 80f

    /** v9: a foundation at/above this chroma is a "real color" (blue/gold/red…)
     *  and wins — an accent never overrides it (no small-accent hijacking: a 60%
     *  blue cover with a 15% yellow sticker stays blue). Below this the foundation
     *  is a neutral/colorless backdrop and a vivid accent provides the color. */
    private const val FOUNDATION_COLOR_THRESHOLD = 40f

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

    // ── v6 Neon accent boost (parameter-level patch, not an architecture change) ──
    // Problem: on weak/neutral foundations (cream, dark grey), a small but very
    // vivid "neon" cluster (the cover's visual star, often ~4% area) falls under
    // the 5% area gate and is killed, letting a larger but desaturated "ordinary"
    // color (clothes, tan) steal the accent. Symmetrically, the v4 fix for
    // "small yellow sticker on a strong blue cover" must still hold — so the
    // boost is gated to WEAK foundations only. There the yellow-sticker case
    // (strong blue foundation) is untouched (its 10% gate still kills the sticker).
    /** HSV saturation at/above which a cluster counts as "neon" (vivid). */
    private const val NEON_SAT_THRESHOLD = 0.55f
    /** Relaxed area floor for neon clusters when the foundation is weak (vs 5%). */
    private const val NEON_POP_THRESHOLD = 0.03f
    /** chroma term multiplier for neon clusters when the foundation is weak. */
    private const val NEON_CHROMA_BOOST = 1.3f
    /** v7: neon boost fires only on weak + DARK foundations (foundation V <= this).
     *  Light/pastel foundations keep their own color — the accent path darkens
     *  (foundationV * 0.7), which muddies a pretty light bg. */
    private const val NEON_DARK_FOUNDATION_V = 0.45f
    /** v7: on strong (vivid) foundations, an accent must clear this chroma bar
     *  (not the lenient 40) so a low-chroma cream/off-white can't steal the hue
     *  and muddy a vivid red/blue foundation to grey. */
    private const val STRONG_ACCENT_CHROMA = 80f

    /** bg saturation = accent sat × 0.7 (v8: slightly more vivid toward "neon"),
     *  capped. Accent path only — no-accent path keeps the foundation verbatim. */
    private const val BG_SAT_RATIO = 0.7f
    private const val BG_SAT_MAX = 0.85f

    /** v8: a bright accent (neon) lifts the bg V by this fraction of its own V,
     *  so a neon on a dark foundation glows instead of muddying to dark. */
    private const val NEON_GLOW_V_RATIO = 0.6f

    /** bg V clamped to [MIN_VALUE_FLOOR, BG_V_MAX]. */
    private const val BG_V_MAX = 0.93f

    fun extract(
        bitmap: Bitmap?,
        @Suppress("UNUSED_PARAMETER") defaultFgArgb: Int = WHITE_TEXT,
    ): ColorPair? {
        if (bitmap == null) return null
        val palette = Palette.from(bitmap).generate()
        val swatches = palette.swatches
            .filter { it.population > 0 }
            .map { SwatchData(it.rgb, it.population) }
        if (swatches.isEmpty()) {
            android.util.Log.d("PaletteTrace", "extract: no swatches → fallback")
            return ColorPair(FALLBACK_BG_DARK, WHITE_TEXT)
        }
        val diag = scoreSwatchesWithDiag(swatches)
        android.util.Log.d(
            "PaletteTrace",
            "extract swatches=${swatches.size} " +
                "foundation=${Integer.toHexString(diag.foundationRgb)}(${diag.foundationHsv.contentToString()}) " +
                "accent=${if (diag.hasAccent) Integer.toHexString(diag.accentRgb) else "NONE"}(${diag.accentHsv.contentToString()}) " +
                "chosen=${Integer.toHexString(diag.chosenRgb)} finalBg=${Integer.toHexString(diag.finalBg)}",
        )
        return resolvePair(diag.finalBg)
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
        // v6/v7: neon boost applies only on weak + DARK foundations.
        val weakFoundation = foundationChroma < STRONG_FOUNDATION_CHROMA
        val darkFoundation = foundationHsv[2] < NEON_DARK_FOUNDATION_V

        // ── Step 3: score each non-foundation cluster for Visual Accent ──
        val details = ArrayList<SwatchDetail>()
        var bestAccent: SwatchData? = null
        var bestAccentScore = 0f

        for (sw in merged) {
            if (sw.rgb == foundationRgb) {
                details.add(SwatchDetail(
                    rgb = sw.rgb, population = sw.population,
                    populationShare = sw.population / totalPop,
                    score = 0f, isChosen = false,
                    isFoundation = true,
                    chromaNorm = (chromaOf(sw.rgb) / 255f).coerceIn(0f, 1f),
                    brightnessContrast = 0f,
                    saturation = rgbToHsv(sw.rgb)[1],
                ))
                continue
            }
            val share = (sw.population / totalPop).coerceIn(0f, 1f)
            val cn = (chromaOf(sw.rgb) / 255f).coerceIn(0f, 1f)
            val bc = abs(luminance(sw.rgb) - foundationLum).coerceIn(0f, 1f)
            val hsv = rgbToHsv(sw.rgb)
            val v = hsv[2]; val s = hsv[1]
            // Extreme penalty: near-black or near-white → 0
            val ep = if (v < 0.08f || (v > 0.95f && s < 0.05f)) 0f else 1f
            // Chroma gate: weak foundations use the lenient 40; strong (vivid)
            // foundations raise to STRONG_ACCENT_CHROMA so a low-chroma cream can't
            // steal the hue and muddy a vivid red/blue foundation to grey.
            val accentChromaGate = if (weakFoundation) COLOR_CANDIDATE_CHROMA else STRONG_ACCENT_CHROMA
            val cg = if (chromaOf(sw.rgb) >= accentChromaGate) 1f else 0f
            // v6 neon boost (weak foundation only): a vivid "neon" cluster that
            // is the cover's visual star but small in area would otherwise fall
            // under the area gate and lose to a larger desaturated "ordinary"
            // color. Relax its area floor and boost its chroma term so it can
            // compete. Gated to weak foundations so the v4 fix for "small vivid
            // sticker on a strong-colored cover" (10% gate) is left untouched.
            val isNeon = weakFoundation && darkFoundation && ep == 1f && s >= NEON_SAT_THRESHOLD
            val areaThreshold = if (isNeon) NEON_POP_THRESHOLD else popThreshold
            val ag = if (share >= areaThreshold) 1f else 0f
            val chromaTerm = cn * (if (isNeon) NEON_CHROMA_BOOST else 1f)
            val rawScore = W_SHARE * share + W_CHROMA * chromaTerm + W_BRIGHTNESS_CONTRAST * bc
            val score = rawScore * ep * cg * ag
            details.add(SwatchDetail(
                rgb = sw.rgb, population = sw.population,
                populationShare = share, score = score, isChosen = false,
                chromaNorm = cn, brightnessContrast = bc, rawScore = rawScore,
                extremePenalty = ep, chromaGate = cg, areaGate = ag,
                saturation = s, neonBoostApplied = isNeon,
            ))
            if (score > bestAccentScore) {
                bestAccentScore = score
                bestAccent = sw
            }
        }

        val hasAccent = bestAccent != null && bestAccentScore > ACCENT_SCORE_THRESHOLD
        val accentRgb = bestAccent?.rgb ?: foundationRgb
        val accentHsv = rgbToHsv(accentRgb)

        // ── Step 4: generate final bg ──
        // v9: "dominant real color wins". The accent overrides the foundation's
        // hue ONLY when the foundation is a neutral/colorless backdrop (chroma <
        // FOUNDATION_COLOR_THRESHOLD). A colored foundation — blue, gold, red —
        // IS the cover's tone and wins regardless of a smaller accent (no more
        // small-accent hijacking: 60% blue + 15% yellow → blue). A neutral/dark
        // backdrop lets a vivid accent (neon) provide the color.
        val useAccent = hasAccent && foundationChroma < FOUNDATION_COLOR_THRESHOLD
        val chosenRgb = if (useAccent) generateBg(foundationRgb, accentRgb) else foundationRgb

        // Mark chosen in details (foundation when no override, else the accent)
        for (i in details.indices) {
            val sw = details[i]
            if ((!useAccent && sw.rgb == foundationRgb) ||
                (useAccent && bestAccent?.rgb == sw.rgb)) {
                details[i] = sw.copy(isChosen = true); break
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
    /** Generate bg: hue/sat from [hueRgb] (accent if present, else foundation).
     *  v8: bgV = max(foundationV, hueRgbV × NEON_GLOW_V_RATIO) — a bright accent
     *  (neon) lifts the bg into a glow instead of dragging it to the dark
     *  foundation's mood. A dark/moody accent or light foundation keeps mood. */
    private fun generateBg(foundationRgb: Int, hueRgb: Int): Int {
        val fHsv = rgbToHsv(foundationRgb)
        val hHsv = rgbToHsv(hueRgb)
        val bgH = hHsv[0]
        val bgS = (hHsv[1] * BG_SAT_RATIO).coerceAtMost(BG_SAT_MAX)
        val bgV = maxOf(fHsv[2], hHsv[2] * NEON_GLOW_V_RATIO).coerceIn(MIN_VALUE_FLOOR, BG_V_MAX)
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

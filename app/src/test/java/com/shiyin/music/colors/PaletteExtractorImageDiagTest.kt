package com.shiyin.music.colors

import com.shiyin.music.data.colors.PaletteExtractor
import com.shiyin.music.data.colors.PaletteExtractor.SwatchData
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 图像诊断 harness：从 Python(PIL) 预生成的中位切割 swatch fixture 读取簇列表，
 * 喂给真实 [PaletteExtractor.scoreSwatchesWithDiag]，打印每簇三维度
 * (dominance/chroma/darkness)、各 gate 与最终加权分。
 *
 * - 量化（PIL median cut，JVM 复现 androidx Palette 的近似，非 bit-identical）不是
 *   被调的部分；评分是被调的真实代码。
 * - 仅诊断用。回归断言见 [purpleNeon_case] 等。
 */
class PaletteExtractorImageDiagTest {

    /** 读取 Python 生成的 "r,g,b,pop" 文本 fixture（跳过 # 注释）。 */
    private fun loadSwatches(resource: String): List<SwatchData> {
        val out = ArrayList<SwatchData>()
        javaClass.getResourceAsStream(resource)!!.bufferedReader().use { r ->
            r.forEachLine { line ->
                val t = line.substringBefore('#').trim() // tolerate trailing "# share=.."
                if (t.isEmpty()) return@forEachLine
                val p = t.split(',')
                if (p.size < 4) return@forEachLine
                val red = p[0].trim().toInt(); val grn = p[1].trim().toInt(); val blu = p[2].trim().toInt()
                val pop = p[3].trim().toInt()
                out.add(SwatchData((0xFF shl 24) or (red shl 16) or (grn shl 8) or blu, pop))
            }
        }
        return out
    }

    // ── 颜色辅助（镜像 PaletteExtractor，仅用于打印/断言）──
    private fun chromaOf(rgb: Int): Float {
        val r = ((rgb shr 16) and 0xFF) / 255f; val g = ((rgb shr 8) and 0xFF) / 255f; val b = (rgb and 0xFF) / 255f
        val rg = r - g; val yb = (r + g) / 2f - b
        return sqrt(rg * rg + yb * yb) * 255f
    }

    private fun hueOf(rgb: Int): Float {
        val r = ((rgb shr 16) and 0xFF) / 255f; val g = ((rgb shr 8) and 0xFF) / 255f; val b = (rgb and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
        if (d == 0f) return 0f
        val h = when {
            mx == r -> ((g - b) / d).let { if (it < 0f) it + 6f else it }
            mx == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return h * 60f
    }

    private fun valueOf(rgb: Int): Float = maxOf(
        (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF,
    ) / 255f

    private fun hex(rgb: Int) = "#%06X".format(rgb and 0xFFFFFF)

    /** 紫色/品红系（含霓虹粉紫）：hue ∈ [255,345]。 */
    private fun isPurpleMagenta(h: Float) = h in 255f..345f

    private fun printDiag(label: String, d: PaletteExtractor.ExtractionDiagnostic) {
        println("\n══════ $label ══════")
        println("foundation=${hex(d.foundationRgb)} hue=${hueOf(d.foundationRgb).toInt()} " +
            "chroma=${chromaOf(d.foundationRgb).toInt()} hasAccent=${d.hasAccent} " +
            "accent=${hex(d.accentRgb)} accentHue=${hueOf(d.accentRgb).toInt()} " +
            "accentChroma=${chromaOf(d.accentRgb).toInt()}")
        println("finalBg=${hex(d.finalBg)} hue=${hueOf(d.finalBg).toInt()} V=${"%.2f".format(valueOf(d.finalBg))}")
        println()
        println("  hex      pop   share  hue   S    V    chroma chromaN darknss rawScr ep cg ag score   flags")
        d.mergedSwatches.sortedByDescending { it.population }.forEach { sw ->
            val vv = valueOf(sw.rgb)
            val flags = buildString {
                if (sw.isFoundation) append("F ")
                if (sw.isChosen) append("★ ")
                if (sw.neonBoostApplied) append("NEON ")
            }.trimEnd()
            println(
                "  %s %5d %5.1f%% %4d  %.2f %.2f  %5.0f  %5.2f  %5.2f  %5.2f  %d  %d  %d  %5.3f  %s".format(
                    hex(sw.rgb), sw.population, sw.populationShare * 100, hueOf(sw.rgb).toInt(),
                    sw.saturation, vv,
                    chromaOf(sw.rgb), sw.chromaNorm, sw.brightnessContrast, sw.rawScore,
                    sw.extremePenalty.toInt(), sw.chromaGate.toInt(), sw.areaGate.toInt(), sw.score,
                    flags,
                )
            )
        }
    }

    // ── 诊断：艺术区（排除歌词奶油区 + 黄底栏）──
    @Test
    fun diag_purpleNeon_artCrop() {
        val sw = loadSwatches("/colors/case_purple_neon_art.txt")
        val d = PaletteExtractor.scoreSwatchesWithDiag(sw)
        printDiag("紫色霓虹 艺术区 y[249,999] maxDim=100 (PIL median-cut16)", d)
    }

    @Test
    fun diag_purpleNeon_artCrop_full() {
        val sw = loadSwatches("/colors/case_purple_neon_art_full.txt")
        val d = PaletteExtractor.scoreSwatchesWithDiag(sw)
        printDiag("紫色霓虹 艺术区 full-res (scale-invariance)", d)
    }

    @Test
    fun diag_purpleNeon_wholeImage() {
        val sw = loadSwatches("/colors/case_purple_neon_whole.txt")
        val d = PaletteExtractor.scoreSwatchesWithDiag(sw)
        printDiag("紫色霓虹 全图(含UI) y[0,1999] maxDim=100", d)
    }

    @Test
    fun diag_purpleNeon_noscrim() {
        val sw = loadSwatches("/colors/case_purple_neon_art_noscrim.txt")
        val d = PaletteExtractor.scoreSwatchesWithDiag(sw)
        printDiag("紫色霓虹 艺术区(去深色scrim) y[249,832] maxDim=100", d)
    }

    // ── v6 回归断言（基于已提交的 PIL median-cut fixture）──

    /** 全图 fixture（亮奶白 foundation）：v7 不应在亮底触发 boost。
     *  最鲜亮的 #85344C(4.5%) 应被正常 5% 门槛挡住（areaGate=0），而非像
     *  v6 那样被放行抢戏把亮底压暗。这是"亮底保持淡色"的回归守卫。 */
    @Test
    fun wholeImage_lightFoundation_noNeonBoost() {
        val sw = loadSwatches("/colors/case_purple_neon_whole.txt")
        val d = PaletteExtractor.scoreSwatchesWithDiag(sw)
        printDiag("全图(亮底)回归", d)
        val neon = d.mergedSwatches.firstOrNull { chromaOf(it.rgb) >= 80f && it.saturation >= 0.55f }
        assertTrue("应存在鲜亮霓虹簇", neon != null)
        assertTrue("亮底不应触发 boost，霓虹应被 5% 门槛挡住（areaGate=0），实际 ag=${neon!!.areaGate}",
            neon.areaGate == 0f)
    }

    /** noscrim fixture：v6 前较淡的 #DC7DAB(chroma95) 胜过鲜亮 #CA4778(chroma132,被门槛杀)；
     *  v6 后鲜亮霓虹应成 accent（chroma≥100）。 */
    @Test
    fun noscrim_accentIsVividNeonAfterFix() {
        val sw = loadSwatches("/colors/case_purple_neon_art_noscrim.txt")
        val d = PaletteExtractor.scoreSwatchesWithDiag(sw)
        printDiag("noscrim回归", d)
        val ac = chromaOf(d.accentRgb)
        assertTrue("noscrim accent 应为鲜亮霓虹（chroma≥100），实际 ${hex(d.accentRgb)} chroma=$ac",
            ac >= 100f && isPurpleMagenta(hueOf(d.accentRgb)))
    }
}

package com.shiyin.music.colors

import com.shiyin.music.data.colors.PaletteExtractor
import com.shiyin.music.data.colors.PaletteExtractor.SwatchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v7 取色规则回归测试（mood-aware）：
 * 1. 弱+暗 foundation + 小面积霓虹(~4%) → v7 放行+加权，霓虹胜（暗底提色）。
 * 2. 弱+暗 foundation + 霓虹太小(<3% floor) → 仍被挡，不抢戏。
 * 3. 强 foundation + 小霓虹(<10%) → 守 10% 门槛，不触发 boost（v4 黄贴纸修复不破坏）。
 * 4. 弱+亮 foundation + 小霓虹 → 不触发 boost，保持淡色（亮底不压暗）。
 * 5. 强 vivid foundation + 低 chroma 奶白 → 挡住奶白，保持 foundation 色（不被搅灰）。
 */
class PaletteExtractorNeonBoostTest {

    private fun r(rgb: Int) = (rgb shr 16) and 0xFF
    private fun g(rgb: Int) = (rgb shr 8) and 0xFF
    private fun b(rgb: Int) = rgb and 0xFF

    private fun chromaOf(rgb: Int): Float {
        val rf = r(rgb) / 255f; val gf = g(rgb) / 255f; val bf = b(rgb) / 255f
        val rg = rf - gf; val yb = (rf + gf) / 2f - bf
        return sqrt(rg * rg + yb * yb) * 255f
    }

    private fun hueOf(rgb: Int): Float {
        val rf = r(rgb) / 255f; val gf = g(rgb) / 255f; val bf = b(rgb) / 255f
        val mx = maxOf(rf, gf, bf); val mn = minOf(rf, gf, bf); val d = mx - mn
        if (d == 0f) return 0f
        val h = when {
            mx == rf -> ((gf - bf) / d).let { if (it < 0f) it + 6f else it }
            mx == gf -> (bf - rf) / d + 2f
            else -> (rf - gf) / d + 4f
        }
        return h * 60f
    }

    private fun hex(rgb: Int) = "#%06X".format(rgb and 0xFFFFFF)
    private fun isPurpleMagenta(h: Float) = h in 255f..345f
    private fun isBlue(h: Float) = h in 195f..255f

    private fun diag(sw: List<SwatchData>) = PaletteExtractor.scoreSwatchesWithDiag(sw)

    private fun printDiag(label: String, d: PaletteExtractor.ExtractionDiagnostic) {
        println("\n=== $label ===")
        println("foundation=${hex(d.foundationRgb)} hue=${hueOf(d.foundationRgb).toInt()} " +
            "chroma=${chromaOf(d.foundationRgb).toInt()} accent=${hex(d.accentRgb)} " +
            "accentHue=${hueOf(d.accentRgb).toInt()} accentChroma=${chromaOf(d.accentRgb).toInt()} " +
            "hasAccent=${d.hasAccent} finalBg=${hex(d.finalBg)} bgHue=${hueOf(d.finalBg).toInt()}")
        d.mergedSwatches.sortedByDescending { it.population }.forEach { sw ->
            val flag = if (sw.neonBoostApplied) " NEON" else ""
            println("  ${hex(sw.rgb)} pop=${sw.population} share=${"%.2f".format(sw.populationShare * 100)}%% " +
                "hue=${hueOf(sw.rgb).toInt()} S=${"%.2f".format(sw.saturation)} " +
                "chroma=${chromaOf(sw.rgb).toInt()} rawScr=${"%.3f".format(sw.rawScore)} " +
                "cg=${sw.chromaGate.toInt()} ag=${sw.areaGate.toInt()} score=${"%.3f".format(sw.score)}$flag")
        }
    }

    // ── 1. 弱+暗 foundation + 小面积霓虹(3.7%) → v7 放行+加权，霓虹胜 ──
    @Test
    fun weakDarkFoundation_smallNeon_picksNeon() {
        val sw = listOf(
            SwatchData(0x101020, 3000),  // 深蓝灰 foundation（弱+暗，V~0.13）
            SwatchData(0x14142E, 1500),  // 深变体（合并）
            SwatchData(0xA07A6A, 500),   // 棕褐"衣服"（9.3%>5%，chroma~52，非霓虹）
            SwatchData(0xC847A0, 200),   // 粉品红霓虹（3.7%<5%，chroma~131，S~0.65）
            SwatchData(0x8A5A50, 150),   // 深棕（gated）
        )
        val d = diag(sw)
        printDiag("弱+暗foundation小霓虹", d)
        val h = hueOf(d.finalBg)
        assertTrue("暗底应让霓虹胜（紫/品红 hue 255-345），实际 bg=${hex(d.finalBg)} hue=$h", isPurpleMagenta(h))
    }

    // ── 2. 弱+暗 foundation + 霓虹太小(2.9% < 3% floor) → 仍被挡，不抢戏 ──
    @Test
    fun weakDarkFoundation_neonTooSmall_gated() {
        val sw = listOf(
            SwatchData(0x101020, 3000),  // 深蓝灰 foundation（弱+暗）
            SwatchData(0xA07A6A, 400),   // 棕褐 11.4%
            SwatchData(0xC847A0, 100),   // 霓虹 2.86% < 3% floor
        )
        val d = diag(sw)
        printDiag("弱+暗foundation霓虹太小", d)
        val neon = d.mergedSwatches.firstOrNull { chromaOf(it.rgb) >= 120f && it.saturation >= 0.55f }
        assertTrue("应存在霓虹簇", neon != null)
        assertEquals("2.9% 霓虹应被 3% neon floor 挡（areaGate=0）", 0f, neon!!.areaGate)
        val h = hueOf(d.finalBg)
        assertTrue("太小霓虹不应抢戏，bg 应非紫，实际 hue=$h", !isPurpleMagenta(h))
    }

    // ── 3. 强 foundation + 小霓虹(4.3%<10%) → 守 10% 门槛，不触发 boost ──
    @Test
    fun strongFoundation_smallNeon_staysFoundation() {
        val sw = listOf(
            SwatchData(0x285AC8, 3000),  // 蓝 foundation（强，chroma~144）
            SwatchData(0x2C5ECC, 1500),  // 蓝变体（合并）
            SwatchData(0xC847A0, 200),   // 粉品红霓虹 4.26% < 10%（强门槛）
        )
        val d = diag(sw)
        printDiag("强foundation小霓虹", d)
        val h = hueOf(d.finalBg)
        assertTrue("强 foundation 下霓虹应被 10% 门槛守住，bg 保持蓝（hue 195-255），实际 hue=$h", isBlue(h))
        val neon = d.mergedSwatches.firstOrNull { it.saturation >= 0.55f && chromaOf(it.rgb) >= 80f }
        assertTrue("霓虹簇不应被标记 NEON（强 foundation）", neon == null || !neon.neonBoostApplied)
    }

    // ── 4. 弱+亮 foundation + 小霓虹 → v7 不触发 boost，保持淡色 ──
    @Test
    fun weakLightFoundation_smallNeon_staysLight() {
        val sw = listOf(
            SwatchData(0xEFE6D2, 3000),  // 奶白 foundation（弱+亮，V~0.94）
            SwatchData(0xE5DBC8, 1500),  // 奶白变体
            SwatchData(0xC847A0, 200),   // 粉品红霓虹 3.7% < 5%
        )
        val d = diag(sw)
        printDiag("弱+亮foundation小霓虹", d)
        val neon = d.mergedSwatches.firstOrNull { chromaOf(it.rgb) >= 120f }
        assertTrue("亮底霓虹不应被 boost", neon == null || !neon.neonBoostApplied)
        val h = hueOf(d.finalBg)
        assertTrue("亮底应保持淡色（非紫），实际 hue=$h", !isPurpleMagenta(h))
    }

    // ── 5. 强红 foundation + 低 chroma 奶白 → 挡住奶白，保持红（非灰）──
    @Test
    fun strongRedFoundation_creamAccent_staysRed() {
        val sw = listOf(
            SwatchData(0xAF1020, 3000),  // 鲜红 foundation（强，chroma~171）
            SwatchData(0xF8E8C7, 600),   // 奶白（chroma~44，刚过旧 40 门槛，≥10%）
        )
        val d = diag(sw)
        printDiag("强红foundation+奶白", d)
        val h = hueOf(d.finalBg)
        assertTrue("强红底应保持红（hue<15或>=345），不被奶白搅灰，实际 hue=$h", h < 15f || h >= 345f)
        val cream = d.mergedSwatches.firstOrNull { chromaOf(it.rgb) in 35f..60f }
        assertTrue("低 chroma 奶白应被强底 80 门槛挡（cg=0）", cream == null || cream.chromaGate == 0f)
    }
}

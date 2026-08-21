package com.shiyin.music.colors

import com.shiyin.music.data.colors.PaletteExtractor
import com.shiyin.music.data.colors.PaletteExtractor.SwatchData
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4 颜色提取测试：验证主色选择代表封面整体视觉色调，不被少量深色元素抢走。
 * 覆盖：浅粉/奶油、蓝、绿、紫、深色、浅底+少量黑字、渐变近色。
 */
class PaletteExtractorV4Test {

    // ── helpers ──
    private fun r(rgb: Int) = (rgb shr 16) and 0xFF
    private fun g(rgb: Int) = (rgb shr 8) and 0xFF
    private fun b(rgb: Int) = rgb and 0xFF
    private fun isPinkish(rgb: Int): Boolean {
        val h = rgbToHue(rgb)
        return h in 300f..360f || h in 0f..30f  // pink = ~330-360, warm = 0-30
    }
    private fun isBluish(rgb: Int): Boolean = b(rgb) > r(rgb) && b(rgb) > g(rgb) && r(rgb) < 150
    private fun isGreenish(rgb: Int): Boolean = g(rgb) > r(rgb) && g(rgb) > b(rgb)
    private fun isPurplish(rgb: Int): Boolean = b(rgb) > r(rgb) && r(rgb) > g(rgb)
    private fun isDarkish(rgb: Int): Boolean = maxOf(r(rgb), g(rgb), b(rgb)) < 80
    private fun isBrightish(rgb: Int): Boolean = maxOf(r(rgb), g(rgb), b(rgb)) > 160
    private fun isGreyish(rgb: Int): Boolean =
        kotlin.math.abs(r(rgb) - g(rgb)) < 25 && kotlin.math.abs(g(rgb) - b(rgb)) < 25

    private fun rgbToHue(rgb: Int): Float {
        val r = r(rgb) / 255f; val g = g(rgb) / 255f; val b = b(rgb) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> ((g - b) / d).let { if (it < 0) it + 6f else it }
            max == g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return h * 60f
    }

    private fun diag(swatches: List<SwatchData>): PaletteExtractor.ExtractionDiagnostic {
        return PaletteExtractor.scoreSwatchesWithDiag(swatches)
    }

    private fun printDiag(label: String, d: PaletteExtractor.ExtractionDiagnostic) {
        println("\n=== $label ===")
        d.mergedSwatches.sortedByDescending { it.population }.forEach { sw ->
            val h = rgbToHue(sw.rgb)
            val chosen = if (sw.isChosen) " ← CHOSEN" else ""
            println("  RGB=${Integer.toHexString(sw.rgb)} H=%.0f S=? V=? pop=${sw.population} share=%.2f score=%.1f%s".format(h, sw.populationShare, sw.score, chosen))
        }
        println("  chosen RGB=${Integer.toHexString(d.chosenRgb)} H=%.0f V=%.2f".format(d.chosenHsv[0], d.chosenHsv[2]))
        println("  finalBg=${Integer.toHexString(d.finalBg)}")
    }

    // ── 1. 浅粉/奶油色封面（核心 case：之前变紫的根因）──
    @Test
    fun 浅粉奶油色封面_选粉色不选深色() {
        // 封面整体浅粉/奶油，渐变拆成多个 swatch + 少量深色阴影
        val pinkSwatches = listOf(
            SwatchData(0xFADCDC.toInt(), 1500),  // 浅粉
            SwatchData(0xF0C8C8.toInt(), 1200),  // 中粉
            SwatchData(0xF5D0D0.toInt(), 1000),  // 浅粉
            SwatchData(0xE8B8B8.toInt(), 800),   // 中深粉
            SwatchData(0x3D2030.toInt(), 400),  // 深暗紫（阴影/文字）
        )
        val d = diag(pinkSwatches)
        printDiag("浅粉奶油色封面", d)
        assertTrue("应选粉色系，实际 RGB=${Integer.toHexString(d.finalBg)}", isPinkish(d.finalBg))
        assertTrue("不应选深色，实际 max=${maxOf(r(d.finalBg), g(d.finalBg), b(d.finalBg))}",
            maxOf(r(d.finalBg), g(d.finalBg), b(d.finalBg)) > 120)
    }

    // ── 2. 蓝色封面 ──
    @Test
    fun 蓝色封面_选蓝色() {
        val swatches = listOf(
            SwatchData(0x285AC8.toInt(), 2000),
            SwatchData(0x2C5ECC.toInt(), 1800),
            SwatchData(0x2A5CCA.toInt(), 1900),
            SwatchData(0xFADC28.toInt(), 500),  // 黄色贴纸
        )
        val d = diag(swatches)
        printDiag("蓝色封面", d)
        assertTrue("应选蓝色，实际 RGB=${Integer.toHexString(d.finalBg)}", isBluish(d.finalBg))
    }

    // ── 3. 绿色封面 ──
    @Test
    fun 绿色封面_选绿色() {
        val swatches = listOf(
            SwatchData(0x3CB371.toInt(), 3000),  // 中绿
            SwatchData(0x40A878.toInt(), 2000),  // 浅绿变体
            SwatchData(0x1A2030.toInt(), 300),   // 深色文字
        )
        val d = diag(swatches)
        printDiag("绿色封面", d)
        assertTrue("应选绿色，实际 RGB=${Integer.toHexString(d.finalBg)}", isGreenish(d.finalBg))
    }

    // ── 4. 紫色封面 ──
    @Test
    fun 紫色封面_选紫色() {
        val swatches = listOf(
            SwatchData(0x6A3093.toInt(), 2500),  // 紫
            SwatchData(0x7035A0.toInt(), 2000),  // 紫变体
            SwatchData(0xF0F0F0.toInt(), 500),   // 白色
        )
        val d = diag(swatches)
        printDiag("紫色封面", d)
        assertTrue("应选紫色，实际 RGB=${Integer.toHexString(d.finalBg)}", isPurplish(d.finalBg))
    }

    // ── 5. 深色封面 ──
    @Test
    fun 深色封面_选深色且提亮() {
        val swatches = listOf(
            SwatchData(0x1A1A2E.toInt(), 3000),  // 深蓝灰
            SwatchData(0xFADC28.toInt(), 200),    // 黄色点缀
        )
        val d = diag(swatches)
        printDiag("深色封面", d)
        // 深色封面应提亮到 v7 floor=0.30（暗底允许偏暗）
        assertTrue("应提亮到 >= V=0.28，实际 V=${d.chosenHsv[2]}", d.chosenHsv[2] >= 0.28f)
    }

    // ── 6. 大面积浅色 + 少量黑色文字/人物 ──
    @Test
    fun 浅底少量黑字_选浅色() {
        val swatches = listOf(
            SwatchData(0xF5F0E8.toInt(), 4000),  // 奶油白（大面积）
            SwatchData(0x1A1A1A.toInt(), 800),    // 黑色文字
            SwatchData(0xE8DCC8.toInt(), 1200),  // 浅米色变体
        )
        val d = diag(swatches)
        printDiag("浅底+少量黑字", d)
        assertTrue("应选浅色，实际 RGB=${Integer.toHexString(d.finalBg)}", isBrightish(d.finalBg))
        assertTrue("不应选黑色，实际 max=${maxOf(r(d.finalBg), g(d.finalBg), b(d.finalBg))}",
            maxOf(r(d.finalBg), g(d.finalBg), b(d.finalBg)) > 150)
    }

    // ── 7. 多种相近渐变色（粉色系渐变被拆成 4 个 swatch）──
    @Test
    fun 粉色渐变多swatch_合并后选粉() {
        // 每个单独 pop=500，4 个合并后 2000 > 深色 800
        val swatches = listOf(
            SwatchData(0xFADCDC.toInt(), 500),
            SwatchData(0xF0C8C8.toInt(), 500),
            SwatchData(0xF5D0D0.toInt(), 500),
            SwatchData(0xE8B8B8.toInt(), 500),
            SwatchData(0x2D1438.toInt(), 800),  // 深紫（单独更大）
        )
        val d = diag(swatches)
        printDiag("粉色渐变多swatch", d)
        assertTrue("合并后粉色应胜出，实际 RGB=${Integer.toHexString(d.finalBg)}", isPinkish(d.finalBg))
    }

    // ── 8. 灰色封面 ──
    @Test
    fun 灰色封面_选灰色() {
        val swatches = listOf(
            SwatchData(0x787878.toInt(), 4000),
            SwatchData(0xDC143C.toInt(), 300),
        )
        val d = diag(swatches)
        printDiag("灰色封面", d)
        assertTrue("Plan C: 7%% red > 5%% threshold → red accent, not grey", !isGreyish(d.finalBg))
    }

    // ── 9. 白色封面 ──
    @Test
    fun 白色封面_选白() {
        val swatches = listOf(
            SwatchData(0xF0F0F0.toInt(), 3000),
            SwatchData(0xE85040.toInt(), 200),
        )
        val d = diag(swatches)
        printDiag("白色封面", d)
        assertTrue("应选浅色，实际 RGB=${Integer.toHexString(d.finalBg)}", isBrightish(d.finalBg))
    }

    // ── 10. 橙色暖色封面 ──
    @Test
    fun 橙色封面_选暖色() {
        val swatches = listOf(
            SwatchData(0xE8943C.toInt(), 2500),  // 橙
            SwatchData(0xD88030.toInt(), 1500),  // 深橙变体
            SwatchData(0x1A1A1A.toInt(), 400),   // 黑字
        )
        val d = diag(swatches)
        printDiag("橙色封面", d)
        val h = rgbToHue(d.finalBg)
        assertTrue("应选暖色 (H 0-60)，实际 H=$h RGB=${Integer.toHexString(d.finalBg)}", h in 0f..60f)
    }
}

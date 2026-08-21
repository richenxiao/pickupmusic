package com.shiyin.music.colors

import com.shiyin.music.data.colors.PaletteExtractor
import com.shiyin.music.data.colors.PaletteExtractor.SwatchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * preMerge 链式吸收 bug 回归测试。
 *
 * 旧 bug：cluster 用 repRgb（随合并变化的加权平均色）做距离判断，
 * 导致 A→B 合并后 rep 偏移，C 虽然离 A 和 B 都远但离偏移后的 rep 近，
 * 被错误吸收。修复后用固定 seed 做距离判断，C 不再被链式吸收。
 */
class PreMergeChainAbsorptionTest {

    /** 调用 preMerge（internal）并返回 merged swatch 列表。 */
    private fun merge(swatches: List<SwatchData>): List<SwatchData> {
        // scoreSwatchesWithDiag 内部调 preMerge，我们通过它的 mergedSwatches 间接验证
        val diag = PaletteExtractor.scoreSwatchesWithDiag(swatches)
        // diag.mergedSwatches 是评分后的列表，但不含原始 merge 结果。
        // 直接用 scoreSwatches 验证最终选色 + 通过 swatch 数量间接验证 merge 行为。
        return diag.mergedSwatches.map { SwatchData(it.rgb, it.population) }
    }

    // ── 反例 A/B/C ──
    // A=F5E6D3 (奶油), B=E8D5C0 (浅奶油), C=D4A880 (暖棕)
    // A-B sq=819 < 1500 → 应合并
    // A-C sq=11822 > 1500 → 不应合并
    // B-C sq=9476 > 1500 → 不应合并
    @Test
    fun ABC反例_AB合并_C不被链式吸收() {
        val a = SwatchData(0xF5E6D3.toInt(), 4000)
        val b = SwatchData(0xE8D5C0.toInt(), 3000)
        val c = SwatchData(0xD4A880.toInt(), 1500)
        val merged = merge(listOf(a, b, c))
        // A+B 合并成一个 cluster (pop=7000)，C 单独一个 cluster (pop=1500)
        assertEquals("应有 2 个 cluster（AB 合并 + C 单独）", 2, merged.size)
        // AB cluster pop=7000
        val abCluster = merged.maxByOrNull { it.population }!!
        assertEquals("AB cluster pop 应为 7000", 7000, abCluster.population)
        // C cluster pop=1500
        val cCluster = merged.minByOrNull { it.population }!!
        assertEquals("C cluster pop 应为 1500", 1500, cCluster.population)
        // C 的 RGB 应保持原样（未被 AB 吸收）— pack() adds 0xFF alpha, so mask
        assertEquals("C 应保持原始 RGB", 0xD4A880, cCluster.rgb and 0xFFFFFF)
    }

    // ── 明显不同色相不被链式合并 ──
    @Test
    fun 蓝黄红不互相合并() {
        val blue = SwatchData(0x285AC8.toInt(), 2000)
        val yellow = SwatchData(0xFADC28.toInt(), 1000)
        val red = SwatchData(0xC03020.toInt(), 800)
        val merged = merge(listOf(blue, yellow, red))
        assertEquals("蓝黄红应各自独立（3 cluster）", 3, merged.size)
    }

    // ── 相近渐变色应正常合并 ──
    @Test
    fun 蓝色渐变四变体合并为一个() {
        val blues = listOf(
            SwatchData(0x285AC8.toInt(), 2000),
            SwatchData(0x2C5ECC.toInt(), 1800),
            SwatchData(0x2A5CCA.toInt(), 1900),
            SwatchData(0x2E62D0.toInt(), 1700),
        )
        val merged = merge(blues)
        assertEquals("4 个相近蓝色应合并为 1 个 cluster", 1, merged.size)
        assertEquals("合并后 pop 应为 7400", 7400, merged[0].population)
    }

    // ── 黑白灰与彩色不被链式吞并 ──
    @Test
    fun 灰色与彩色不被链式吞并() {
        // 灰(128,128,128) sq to warmBrown(200,150,100) = 6452 > 1500 → 不合并
        // 两者之间没有能桥接的中间色 → 不应链式合并
        val grey = SwatchData(0x808080.toInt(), 3000)
        val warmBrown = SwatchData(0xC89664.toInt(), 500)
        val merged = merge(listOf(grey, warmBrown))
        assertEquals("灰与暖棕应各自独立（2 cluster）", 2, merged.size)
    }

    // ── cluster 最终代表色不影响 membership ──
    @Test
    fun 代表色计算不改变cluster成员() {
        // 两个相近的粉色 + 一个远处的蓝色
        val pink1 = SwatchData(0xF0C0C0.toInt(), 2000)
        val pink2 = SwatchData(0xE8B0B0.toInt(), 1500) // sq to pink1 = 400+256+144=800 < 1500 → merge
        val blue = SwatchData(0x4080C0.toInt(), 1000)
        val merged = merge(listOf(pink1, pink2, blue))
        assertEquals("粉合并 + 蓝独立 = 2 cluster", 2, merged.size)
        val pinkCluster = merged.find { it.population == 3500 }!!
        val blueCluster = merged.find { it.population == 1000 }!!
        // 蓝色 RGB 不应变（没被粉色吸收）— pack() adds 0xFF alpha, so mask
        assertEquals("蓝应保持原始 RGB", 0x4080C0, blueCluster.rgb and 0xFFFFFF)
        // 粉色 cluster 的代表色应是加权平均，不是某个原始 swatch
        val r = (pinkCluster.rgb shr 16) and 0xFF
        assertTrue("粉色 cluster 代表色 R 应在 224-240 之间（加权平均）", r in 224..240)
    }

    // ── 输入顺序不影响结果 ──
    @Test
    fun 输入顺序不影响cluster结果() {
        val a = SwatchData(0xF5E6D3.toInt(), 4000)
        val b = SwatchData(0xE8D5C0.toInt(), 3000)
        val c = SwatchData(0xD4A880.toInt(), 1500)
        // 正序
        val merged1 = merge(listOf(a, b, c))
        // 乱序（但 population 相同时排序后顺序可能变）
        val merged2 = merge(listOf(c, b, a))
        // cluster 数量应相同
        assertEquals("顺序不影响 cluster 数量", merged1.size, merged2.size)
        // 最大 cluster 的 population 应相同
        assertEquals("顺序不影响最大 cluster pop",
            merged1.maxByOrNull { it.population }!!.population,
            merged2.maxByOrNull { it.population }!!.population)
    }
}

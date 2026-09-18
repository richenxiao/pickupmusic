package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Token 成本估算的纯逻辑测试。
 *
 * 重点不是检验"价表是否永远正确"（价格会变，改表即可），而是检验计费数学：
 * 缓存命中是输入 token 的子集，必须先从输入扣掉再按折扣计，不能重复加算。
 */
class TokenPricingTest {

    @Test fun cnyProvider_usesCnyRate() {
        val r = TokenPricing.rateFor("deepseek")
        assertEquals("CNY", r.currency)
        assertEquals("¥", r.symbol)
        assertEquals(2.0, r.inputPerM, 0.0001)
        assertEquals(8.0, r.outputPerM, 0.0001)
    }

    @Test fun usdProvider_isConvertedToCny() {
        val r = TokenPricing.rateFor("openrouter")
        assertEquals("USD", r.currency)
        assertEquals("$", r.symbol)
        // 1M input + 1M output at $1/$3 = $4; × 7.2 = ¥28.8
        assertEquals(28.8, TokenPricing.estimateCny("openrouter", 1_000_000, 1_000_000, 0), 0.0001)
    }

    @Test fun cacheIsInputSubset_notAddedTwice() {
        // DeepSeek：输入 ¥2/M、输出 ¥8/M，缓存输入按 1/10 = ¥0.2/M。
        // 1M input 里 0.5M 是缓存，外加 1M output：
        // fresh input 0.5M×2=1.0 + cached 0.5M×0.2=0.1 + output 1M×8=8.0 = ¥9.1
        assertEquals(9.1, TokenPricing.estimateCny("deepseek", 1_000_000, 1_000_000, 500_000), 0.0001)
        // 没有缓存时则 ¥10；验证缓存不是在输入后额外叠加。
        assertEquals(10.0, TokenPricing.estimateCny("deepseek", 1_000_000, 1_000_000, 0), 0.0001)
    }

    @Test fun cacheIsClampedToInput() {
        // 上游偶发脏数据（cache > input）不能产生负 freshInput 或负成本。
        val v = TokenPricing.estimateCny("deepseek", 100, 0, 999_999)
        assertTrue(v >= 0.0)
        // 视为全部 100 input 命中缓存：100 / 1M × ¥0.2
        assertEquals(100.0 * 0.2 / 1_000_000.0, v, 0.0000001)
    }

    @Test fun unknownProvider_usesExplicitFallback() {
        val r = TokenPricing.rateFor("custom-42")
        assertEquals("USD", r.currency)
        assertTrue(TokenPricing.estimateCny("custom-42", 1_000_000, 0, 0) > 0)
    }

    @Test fun assumptionText_isTransparent() {
        val text = TokenPricing.assumptionText("deepseek")
        assertTrue(text.contains("输入"))
        assertTrue(text.contains("输出"))
        assertTrue(text.contains("估算"))
    }
}

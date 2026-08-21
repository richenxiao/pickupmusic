package com.shiyin.music.data.furigana

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ContextResolver 单测：验证「何」CONFLICT 时的可证明上下文消歧规则。
 * 仅测 provable 规则；语境歧义（何なん/何で/何か）须返回 null（不猜 → No Reading）。
 */
class ContextResolverTest {

    private val nan = listOf("なに", "なん") // 何 的两个合法读法

    @Test
    fun 何が_助词が_消歧为なに() = assertEquals("なに", ContextResolver.resolve("何", nan, "が"))

    @Test
    fun 何を_助词を_消歧为なに() = assertEquals("なに", ContextResolver.resolve("何", nan, "を"))

    @Test
    fun 何の_助词の_消歧为なに() = assertEquals("なに", ContextResolver.resolve("何", nan, "の"))

    @Test
    fun 何も_助词も_消歧为なに() = assertEquals("なに", ContextResolver.resolve("何", nan, "も"))

    @Test
    fun 何だ_系动词だ_消歧为なん() = assertEquals("なん", ContextResolver.resolve("何", nan, "だ"))

    @Test
    fun 何です_系动词です_消歧为なん() = assertEquals("なん", ContextResolver.resolve("何", nan, "です"))

    @Test
    fun 何でしょうか_消歧为なん() = assertEquals("なん", ContextResolver.resolve("何", nan, "でしょうか"))

    @Test
    fun 何だろう_消歧为なん() = assertEquals("なん", ContextResolver.resolve("何", nan, "だろう"))

    @Test
    fun 何なん_语境歧义_不猜返回null() = assertEquals(null, ContextResolver.resolve("何", nan, "なん"))

    @Test
    fun 何で_语境歧义_不猜返回null() = assertEquals(null, ContextResolver.resolve("何", nan, "で"))

    @Test
    fun 何か_语境歧义_不猜返回null() = assertEquals(null, ContextResolver.resolve("何", nan, "か"))

    @Test
    fun 何_句末无下文_不猜返回null() = assertEquals(null, ContextResolver.resolve("何", nan, null))

    @Test
    fun 非何的CONFLICT词_Phase1不猜返回null() = assertEquals(null, ContextResolver.resolve("二人", listOf("ふたり", "ににん"), "で"))

    @Test
    fun 单一读法_不进消歧返回null() = assertEquals(null, ContextResolver.resolve("何", listOf("なに"), "が"))
}

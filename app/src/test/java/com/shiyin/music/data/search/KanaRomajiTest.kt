package com.shiyin.music.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.10: 假名→罗马字表 + 搜索兜底的回归锁。
 *
 * 这张表是"用户按罗马字找日文歌,库里明明是假名却报没有"的直接修复点,错了就静默失效
 * (搜不到不报错,只表现为"库里没有"),所以必须逐条锁死。
 */
class KanaRomajiTest {

    @Test
    fun `片假名标题转罗马字 - 用户实际案例`() {
        // aiko《カブトムシ》:用户按 kabutomushi 搜必须能对上
        assertEquals("kabutomushi", KanaRomaji.toRomaji("カブトムシ"))
    }

    @Test
    fun `拗音按二连处理`() {
        // 拗音本身是一个音节;后面跟的 う 是长音写法,按元音照转(kyou)。
        // 用户只打 "kyo" 也能命中——匹配走 contains,是"罗马字写法包含查询串"。
        assertEquals("kyo", KanaRomaji.toRomaji("きょ"))
        assertEquals("kyou", KanaRomaji.toRomaji("きょう"))
        assertEquals("shashin", KanaRomaji.toRomaji("しゃしん"))
        assertEquals("ja", KanaRomaji.toRomaji("じゃ"))
        assertEquals("chu", KanaRomaji.toRomaji("ちゅ"))
    }

    @Test
    fun `促音双写下一音节辅音`() {
        assertEquals("gakkou", KanaRomaji.toRomaji("がっこう"))
        assertEquals("kitte", KanaRomaji.toRomaji("きって"))
    }

    @Test
    fun `长音符重复上一个元音`() {
        assertEquals("koohii", KanaRomaji.toRomaji("コーヒー"))
        assertEquals("sukii", KanaRomaji.toRomaji("スキー"))
    }

    @Test
    fun `非假名字符原样保留并小写`() {
        // 汉字无从确定读音 → 原样保留(该缺口已在文档里明确列出)
        val mixed = KanaRomaji.toRomaji("夜に駆ける")
        assertTrue(mixed.contains("ni"))
        assertTrue(mixed.contains("keru"))
        assertTrue(mixed.contains("夜"))
        assertEquals("abc-123", KanaRomaji.toRomaji("ABC-123"))
    }

    @Test
    fun `variantsOf 同时给出原文与罗马字`() {
        val v = KanaRomaji.variantsOf("カブトムシ")
        assertEquals(listOf("カブトムシ", "kabutomushi"), v)
    }

    @Test
    fun `空串与纯符号不崩`() {
        assertEquals("", KanaRomaji.toRomaji(""))
        assertEquals("!!", KanaRomaji.toRomaji("!!"))
    }
}

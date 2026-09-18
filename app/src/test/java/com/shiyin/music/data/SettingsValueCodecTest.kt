package com.shiyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v22: 设置项备份编解码的单测。
 *
 * 这块的关键风险是**类型丢失**：DataStore 里 `"1"` 是 Int、`"1"` 也可能是 String，
 * 导入时用错 `Preferences.Key<T>` 会直接抛 ClassCastException，整个恢复流程崩在最后一步。
 * 所以每个类型都要能精确往返，且解码必须容错（坏值跳过而不是抛）。
 */
class SettingsValueCodecTest {

    private fun roundTrip(v: Any?): String? = SettingsValueCodec.encode(v)

    @Test fun booleanRoundTrip() {
        assertEquals(true, SettingsValueCodec.boolOf(roundTrip(true)!!))
        assertEquals(false, SettingsValueCodec.boolOf(roundTrip(false)!!))
        assertEquals(SettingsValueCodec.BOOL, SettingsValueCodec.typeOf(roundTrip(true)!!))
    }

    @Test fun stringRoundTrip() {
        val encoded = roundTrip("deepseek-v4-flash")!!
        assertEquals(SettingsValueCodec.STR, SettingsValueCodec.typeOf(encoded))
        assertEquals("deepseek-v4-flash", SettingsValueCodec.stringOf(encoded))
    }

    /** 空串也要能往返 —— 项目里"未配置"就是用空串表示的。 */
    @Test fun emptyStringRoundTrip() {
        val encoded = roundTrip("")!!
        assertEquals(SettingsValueCodec.STR, SettingsValueCodec.typeOf(encoded))
        assertEquals("", SettingsValueCodec.stringOf(encoded))
    }

    /** 含引号 / 换行 / 反斜杠的值不能被破坏（API Key 和 JSON 配置里都可能出现）。 */
    @Test fun awkwardStringsSurvive() {
        val v = "a\"b\\c\nd\te\u0002f"
        assertEquals(v, SettingsValueCodec.stringOf(roundTrip(v)!!))
    }

    @Test fun numberRoundTrips() {
        assertEquals(1.5f, SettingsValueCodec.floatOf(roundTrip(1.5f)!!)!!, 1e-6f)
        assertEquals(0f, SettingsValueCodec.floatOf(roundTrip(0f)!!)!!, 1e-6f)
        assertEquals(42, SettingsValueCodec.intOf(roundTrip(42)!!))
        assertEquals(-7, SettingsValueCodec.intOf(roundTrip(-7)!!))
        assertEquals(1_700_000_000_000L, SettingsValueCodec.longOf(roundTrip(1_700_000_000_000L)!!))
    }

    /** 数字的"字符串形态"必须和数字区分开 —— 这正是加类型前缀的原因。 */
    @Test fun numericStringIsDistinguishableFromNumber() {
        val asString = roundTrip("1")!!
        val asInt = roundTrip(1)!!
        assertEquals(SettingsValueCodec.STR, SettingsValueCodec.typeOf(asString))
        assertEquals(SettingsValueCodec.INT, SettingsValueCodec.typeOf(asInt))
        assertTrue(asString != asInt)
        assertEquals("1", SettingsValueCodec.stringOf(asString))
        assertEquals(1, SettingsValueCodec.intOf(asInt))
    }

    @Test fun stringSetRoundTrip() {
        val encoded = roundTrip(setOf("lastfm", "fanart", "discogs"))!!
        assertEquals(SettingsValueCodec.STR_SET, SettingsValueCodec.typeOf(encoded))
        assertEquals(setOf("lastfm", "fanart", "discogs"), SettingsValueCodec.setOf(encoded))
    }

    @Test fun emptySetRoundTrip() {
        val encoded = roundTrip(emptySet<String>())!!
        assertEquals(SettingsValueCodec.STR_SET, SettingsValueCodec.typeOf(encoded))
        assertTrue(SettingsValueCodec.setOf(encoded).isEmpty())
    }

    /** 未知类型（比如以后加了新类型、被老版本读到）返回 null，调用方跳过该 key。 */
    @Test fun unknownTypeIsRejected() {
        assertNull(roundTrip(null))
        assertNull(roundTrip(listOf("x")))
        assertNull(roundTrip(ByteArray(1)))
    }

    // ── 解码容错 ────────────────────────────────────────────────────────────
    @Test fun decodeIsFaultTolerant() {
        assertNull(SettingsValueCodec.typeOf(""))
        assertNull(SettingsValueCodec.boolOf("b_maybe"))
        assertNull(SettingsValueCodec.floatOf("f_abc"))
        assertNull(SettingsValueCodec.intOf("i_abc"))
        assertNull(SettingsValueCodec.longOf("l_abc"))
        // 集合解码遇到垃圾不抛，只是元素被丢掉
        assertTrue(SettingsValueCodec.setOf("t").isEmpty())
        // 类型前缀不匹配时，stringOf 仍然安全（返回去掉首字符的原文）
        assertEquals("true", SettingsValueCodec.stringOf("btrue"))
    }
}

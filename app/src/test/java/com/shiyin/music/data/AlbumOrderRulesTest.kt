package com.shiyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.15:编辑发行日期后,歌手专辑栏的顺序该怎么处理。
 *
 * 真机反馈:「有一张 1999 的被识别成 2022,排在了 1997、2002 前面;我把它改成 1999 之后,
 * 它还是排在最前面。」根因是排序优先级 —— 歌手栏是"手动顺序 > 日期 > 名称",而 AI 按时间
 * 排序会把那一份顺序写进 artist_album_order;之后手工修正年份,顺序仍按旧的那份走,
 * 日期压根不参与。所以日期一旦变动,该歌手的手动顺序必须作废(回落到按日期排)。
 */
class AlbumOrderRulesTest {

    @Test
    fun `日期改动 - 只作废该专辑所属歌手的手动顺序`() {
        val orders = mapOf(
            "方大同" to listOf("a", "b"),
            "藤井风" to listOf("c"),
        )
        val next = AlbumOrderRules.invalidate(orders, listOf("方大同"))
        assertFalse("改动日期的那位歌手必须作废(否则日期永远压不过旧顺序)", next.containsKey("方大同"))
        assertEquals("其他歌手的顺序要原样保留", listOf("c"), next["藤井风"])
    }

    @Test
    fun `多歌手合作的专辑 - 涉及到的歌手全部作废`() {
        val orders = mapOf("A" to listOf("x"), "B" to listOf("y"), "C" to listOf("z"))
        val next = AlbumOrderRules.invalidate(orders, listOf("A", "B"))
        assertEquals(setOf("C"), next.keys)
    }

    @Test
    fun `没有涉及的歌手时不做任何改动`() {
        val orders = mapOf("A" to listOf("x"))
        assertEquals(orders, AlbumOrderRules.invalidate(orders, emptyList()))
    }

    @Test
    fun `没真改动的日期不触发作废 - 重存同一个日期不该丢掉拖拽顺序`() {
        assertFalse(AlbumOrderRules.dateChanged("1999", "1999"))
        assertFalse("无行(null)与空串等价:都表示没有日期", AlbumOrderRules.dateChanged(null, ""))
        assertTrue(AlbumOrderRules.dateChanged("2022", "1999"))
        assertTrue(AlbumOrderRules.dateChanged(null, "1999"))
        assertTrue("清除日期同样算改动", AlbumOrderRules.dateChanged("1999", ""))
    }
}

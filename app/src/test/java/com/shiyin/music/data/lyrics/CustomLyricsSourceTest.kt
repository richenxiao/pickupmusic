package com.shiyin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.10 需求 A29:自定义歌词源的**纯逻辑**回归锁(模板求值 / JSON 路径 / 编解码)。
 *
 * 这层错了用户不会看到报错,只会"加了源但搜不到歌词"——所以逐条钉死。
 */
class CustomLyricsSourceTest {

    private val def = CustomLyricsSourceDef(
        id = "custom-1",
        name = "我的源",
        searchUrl = "https://api.x.com/search?q={title}&a={artist}",
        idJsonPath = "data.items[0].id",
        lyricUrl = "https://api.x.com/lyric/{id}",
        lyricJsonPath = "data.lrc",
        headers = "Authorization: Bearer t\nX-Trace: 1",
    )

    @Test
    fun `编解码往返保持全部字段`() {
        val back = CustomLyricsSourceCodec.decode(CustomLyricsSourceCodec.encode(listOf(def)))
        assertEquals(1, back.size)
        assertEquals(def, back.single())
    }

    @Test
    fun `脏行被丢弃 - 半截配置不会进链路`() {
        val json = """[
            {"id":"a","name":"ok","searchUrl":"https://x?q={title}","idJsonPath":"d.id","lyricUrl":"https://x/{id}","lyricJsonPath":"d.lrc"},
            {"id":"b","name":"缺字段","searchUrl":"https://x?q={title}"},
            {"name":"缺 id","searchUrl":"https://x?q={title}","idJsonPath":"d.id","lyricUrl":"https://x/{id}","lyricJsonPath":"d.lrc"}
        ]"""
        val list = CustomLyricsSourceCodec.decode(json)
        assertEquals(1, list.size)
        assertEquals("ok", list.single().name)
    }

    @Test
    fun `空串或坏 JSON 解码成空列表而不是抛异常`() {
        assertEquals(0, CustomLyricsSourceCodec.decode("").size)
        assertEquals(0, CustomLyricsSourceCodec.decode("not json").size)
    }

    @Test
    fun `模板占位符被 URL 编码 - 未知识别原样保留`() {
        val url = CustomLyricsSourceCodec.fillTemplate(
            "https://api.x.com/s?t={title}&a={artist}&unknown={zzz}",
            mapOf("title" to "晴天 (Live)", "artist" to "周杰伦"),
        )
        assertTrue(url.contains("t=%E6%99%B4%E5%A4%A9+%28Live%29"))
        assertTrue(url.contains("a=%E5%91%A8%E6%9D%B0%E4%BC%A6"))
        // 未知占位符原样留着——让用户一眼看出自己写错了
        assertTrue(url.contains("{zzz}"))
    }

    @Test
    fun `JSON 路径 - 对象字段与数组下标`() {
        val json = com.google.gson.JsonParser.parseString(
            """{"data":{"items":[{"id":"s_1"},{"id":"s_2"}],"lrc":"[00:01.00]歌词"},"n":7}""",
        )
        assertEquals("s_1", CustomLyricsSourceCodec.resolvePath(json, "data.items[0].id"))
        assertEquals("s_2", CustomLyricsSourceCodec.resolvePath(json, "data.items[1].id"))
        assertEquals("[00:01.00]歌词", CustomLyricsSourceCodec.resolvePath(json, "data.lrc"))
        // 数字也能取(转字符串)
        assertEquals("7", CustomLyricsSourceCodec.resolvePath(json, "n"))
        // 取不到 → null(不抛)
        assertNull(CustomLyricsSourceCodec.resolvePath(json, "data.items[9].id"))
        assertNull(CustomLyricsSourceCodec.resolvePath(json, "nope"))
        assertNull(CustomLyricsSourceCodec.resolvePath(null, "a"))
    }

    @Test
    fun `请求头解析 - 忽略空行与无冒号行`() {
        val h = CustomLyricsSourceCodec.parseHeaders("Authorization: Bearer t\n\n乱写一行\nX-Trace:1")
        assertEquals(listOf("Authorization" to "Bearer t", "X-Trace" to "1"), h)
    }
}

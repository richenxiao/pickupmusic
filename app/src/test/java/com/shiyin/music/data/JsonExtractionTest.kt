package com.shiyin.music.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.3.8: JSON 抠取的回归测试。
 *
 * 背景——**这个 bug 为什么长期没被发现**:
 * 原实现用 `Regex("""\{.*}""", DOT_MATCHES_ALL)` 抠 JSON。裸 `}` 在 **Android** 的
 * java.util.regex 上编译即抛 `PatternSyntaxException`(真机日志实测),而 **JVM 单测**
 * 跑的是桌面 JDK 正则、接受裸 `}`——所以单测全绿、真机全炸,模型返回的合法 JSON
 * 被整段丢弃,表现为"AI 联网搜到资料却什么都不返回"。
 *
 * 因此**本文件无法覆盖"Android 正则语义"那一层**(JVM 里根本复现不了)。它覆盖的是
 * 换掉正则之后必须保证的两类行为:
 *  ① 正常抠取;② 贪婪匹配时代会吃掉整个 JSON 的两种形态(后缀带大括号的说明文字、
 *     字符串内部含大括号)。
 * 平台差异那一层只能靠真机日志验证——不要在报告里把单测绿说成"真机已验证"。
 */
class JsonExtractionTest {

    @Test
    fun `纯 JSON 原样抠出`() {
        val raw = """{"albums":[{"name":"Gen","date":"2025-05-14"}]}"""
        assertEquals(raw, firstBalancedJsonObject(raw))
    }

    @Test
    fun `前后带散文文字仍能抠出`() {
        val raw = "好的,结果如下:\n{\"albums\":[{\"name\":\"Gen\",\"date\":\"2025-05-14\"}]}\n希望有帮助!"
        val got = firstBalancedJsonObject(raw)!!
        assertEquals("""{"albums":[{"name":"Gen","date":"2025-05-14"}]}""", got)
        // 必须仍是可解析的 JSON
        assertEquals(1, JsonParser.parseString(got).asJsonObject.getAsJsonArray("albums").size())
    }

    @Test
    fun `后缀文字含大括号时不会多吃 — 贪婪正则的经典翻车`() {
        // 旧实现 \{.*} 会一直吃到最后一个 '}',把说明文字也包进来 → 整体不是合法 JSON
        val raw = """{"albums":[]} 说明:对象形如 {"name":"x"} ,用时请替换"""
        val got = firstBalancedJsonObject(raw)!!
        assertEquals("""{"albums":[]}""", got)
        assertEquals(0, JsonParser.parseString(got).asJsonObject.getAsJsonArray("albums").size())
    }

    @Test
    fun `字符串内部的大括号不当结构括号`() {
        val raw = """{"albums":[{"name":"A } B","date":"2019"}]}"""
        val got = firstBalancedJsonObject(raw)!!
        assertEquals(raw, got)
        assertEquals("A } B", JsonParser.parseString(got).asJsonObject
            .getAsJsonArray("albums")[0].asJsonObject.get("name").asString)
    }

    @Test
    fun `转义引号不影响配对`() {
        val raw = """{"a":"he said \"} \" ok","b":{"c":1}}"""
        val got = firstBalancedJsonObject(raw)!!
        assertEquals(raw, got)
        assertEquals(1, JsonParser.parseString(got).asJsonObject.getAsJsonObject("b").get("c").asInt)
    }

    @Test
    fun `嵌套对象按深度配对`() {
        val raw = """{"outer":{"inner":{"deep":1}},"other":2}"""
        assertEquals(raw, firstBalancedJsonObject(raw))
    }

    @Test
    fun `真机日志里的那种原始串 — 前导换行`() {
        // 实际 logcat 抓到的 raw 形态(含前导换行)
        val raw = "\n{\"albums\":[{\"name\":\"イエローダンサー\",\"date\":\"2015-12-02\"}" +
            ",{\"name\":\"ストレンジャー\",\"date\":\"2013-05-01\"}]}"
        val got = firstBalancedJsonObject(raw)!!
        assertEquals(2, JsonParser.parseString(got).asJsonObject.getAsJsonArray("albums").size())
    }

    @Test
    fun `没有大括号返回 null`() {
        assertNull(firstBalancedJsonObject("完全没有 JSON"))
        assertNull(firstBalancedJsonObject(""))
    }

    @Test
    fun `括号不配平返回 null`() {
        assertNull(firstBalancedJsonObject("{\"a\":1"))
        assertNull(firstBalancedJsonObject("{\"a\":{\"b\":1}"))
    }
}

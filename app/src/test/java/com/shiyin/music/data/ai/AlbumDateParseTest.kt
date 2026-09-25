package com.shiyin.music.data.ai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 真机日志复现:模型返回了合法 JSON 但解析报 parse empty。
 * 原始 raw(含前导空行):
 *   "\n{\"albums\":[{\"name\":\"Gen\",\"date\":\"2025-05-14\"},...]}"
 * 与 MainViewModel.fetchArtistAlbumDates 的解析段保持同一逻辑(含 jsonish 全角归一)。
 *
 * ⚠️ **这个测试曾经的盲区**(v1.3.8 才查明):原实现抠 JSON 用的是
 * `Regex("""\{.*}""", DOT_MATCHES_ALL)`,**裸 `}` 在 Android 的 java.util.regex 上
 * 编译即抛 PatternSyntaxException**;而 JVM 单测跑的是桌面 JDK 正则(接受裸 `}`)。
 * 于是本文件长期全绿,真机上却整段解析失败、模型返回的日期全丢——表现为
 * "AI 联网搜到了却什么都没返回"。
 *
 * 教训:**这类"平台正则语义差异"的 bug,纯 JVM 单测永远测不出来,只有在真机上跑
 * 才能暴露**。所以本文件改为直接调用生产代码的抠取函数(而不是在测试里复刻一份
 * 正则),至少保证测的是同一份实现;真正的验证靠真机日志。
 */
class AlbumDateParseTest {

    private fun norm(s: String) =
        s.replace(Regex("[(（\\[【].*?[)）\\]】]"), "").replace(Regex("""\s+"""), "").lowercase()

    private fun String.jsonish(): String = buildString(length) {
        for (ch in this@jsonish) when (ch) {
            '｛' -> append('{'); '｝' -> append('}')
            '“', '”', '＂' -> append('"')
            '，' -> append(','); '：' -> append(':')
            in '０'..'９' -> append('0' + (ch.code - '０'.code))
            else -> append(ch)
        }
    }

    private fun parse(respContent: String): Map<String, String> {
        val dateByName = mutableMapOf<String, String>()
        try {
            // 与生产代码同一函数(不再在测试里复刻正则——复刻份会掩盖实现被改坏)
            val obj = JsonParser.parseString(
                com.shiyin.music.data.firstBalancedJsonObject(respContent.jsonish()) ?: ""
            ).asJsonObject
            obj.getAsJsonArray("albums")?.forEach { el ->
                val o = el?.asJsonObject ?: return@forEach
                val n = o.get("name")?.asString ?: return@forEach
                val d = o.get("date")?.asString ?: return@forEach
                val m = Regex("""^(\d{4})(?:-(\d{2}))?(?:-(\d{2}))?""").find(d.trim())
                if (m != null) {
                    val y = m.groupValues[1]
                    val mo = m.groupValues[2].ifEmpty { "01" }
                    val da = m.groupValues[3].ifEmpty { "01" }
                    dateByName[norm(n)] = "$y-$mo-$da"
                }
            }
        } catch (e: Exception) {
            println("PARSE THREW: ${e.javaClass.simpleName}: ${e.message}")
        }
        return dateByName
    }

    @Test
    fun parsesRealLogcatRaw() {
        val raw = "\n{\"albums\":[{\"name\":\"Gen\",\"date\":\"2025-05-14\"},{\"name\":\"POP VIRUS\",\"date\":\"2018-12-19\"},{\"name\":\"イエローダンサー\",\"date\":\"2015-12-02\"}]}"
        val got = parse(raw)
        assertEquals(3, got.size)
        assertEquals("2025-05-14", got["gen"])
        assertEquals("2018-12-19", got["popvirus"])
    }

    @Test
    fun parsesFullWidthJson() {
        // CJK 语境模型偶发把结构符写成全角——归一后必须照常解析
        val raw = "｛＂albums＂：[｛＂name＂：＂Gen＂，＂date＂：＂２０２５-０５-１４＂｝]｝"
        val got = parse(raw)
        assertEquals("2025-05-14", got["gen"])
    }

    @Test
    fun parsesPartialDates() {
        val raw = "{\"albums\":[{\"name\":\"A\",\"date\":\"2019\"},{\"name\":\"B\",\"date\":\"2019-03\"},{\"name\":\"C\",\"date\":\"2019-03-05\"}]}"
        val got = parse(raw)
        assertEquals("2019-01-01", got["a"])
        assertEquals("2019-03-01", got["b"])
        assertEquals("2019-03-05", got["c"])
    }

    @Test
    fun normStripsBrackets() {
        assertEquals("gen", norm("Gen"))
        assertEquals("popvirus", norm("POP VIRUS"))
        assertEquals("gen", norm("Gen (Deluxe)"))
        assertEquals("gen", norm("Gen（豪华版）"))
    }
}

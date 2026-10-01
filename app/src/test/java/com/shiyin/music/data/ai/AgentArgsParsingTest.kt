package com.shiyin.music.data.ai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.15:回退链路(供应商不支持原生 tool calling)的**参数透传**契约。
 *
 * 背景:「推荐方大同适合睡觉的歌」的硬约束靠 `artist` 参数。原生 tool_calls 与回退路由
 * (`{"skill":…,"args":{…}}`)如果各写一套参数解析,就会漂移 —— 一旦回退链路按关键字
 * 白名单过滤,`artist/scene/album` 会在弱模型/老供应商上**静默丢失**,硬约束失效,
 * 而原生链路的单测全部通过,问题只在真机上出现。所以这里钉住"不做白名单"。
 */
class AgentArgsParsingTest {

    private fun args(json: String) = AgentTools.argsFromJson(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `artist scene album 原样透传 - 不做关键字白名单`() {
        val m = args("""{"artist":"方大同","scene":"睡觉","album":"危险世界","whatever":"随便加的参数"}""")
        assertEquals("方大同", m["artist"])
        assertEquals("睡觉", m["scene"])
        assertEquals("危险世界", m["album"])
        assertTrue("未知键也不该被丢掉(技能自己决定读不读)", m.containsKey("whatever"))
    }

    @Test
    fun `空值与非字符串按既有容错处理`() {
        val m = args("""{"artist":"","scene":"   ","album":"x","arr":["a","b"],"obj":{"k":1},"num":3}""")
        assertTrue("空白值不传", !m.containsKey("artist") && !m.containsKey("scene"))
        assertEquals("x", m["album"])
        assertEquals("数组拼成空格分隔串", "a b", m["arr"])
        assertEquals("数字转字符串", "3", m["num"])
        assertTrue("对象形态不传(技能只认标量与数组)", !m.containsKey("obj"))
    }

    @Test
    fun `args 缺失或为 null 时返回空表`() {
        assertTrue(AgentTools.argsFromJson(null).isEmpty())
        assertTrue(args("{}").isEmpty())
    }

    @Test
    fun `旧参数 mood 在回退链路上也原样到达技能`() {
        // 技能内部把 mood 当 scene 读(见 DiscoverMusicSkill)——这里只保证它没被解析层吃掉
        val m = args("""{"mood":"加班","artist":"方大同"}""")
        assertEquals("加班", m["mood"])
        assertEquals("方大同", m["artist"])
    }
}

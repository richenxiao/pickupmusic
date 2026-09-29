package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.12:联网搜索触发策略的回归测试。
 *
 * 真机两个反面:该搜的不搜(问"最近的新歌"凭记忆答)、不该搜的乱搜(聊感想也搜)。
 * 这里钉住:明确的联网要求能被**确定性**识别并抽出查询词(不依赖弱模型记得调工具),
 * 而观点/感想/闲聊不会被误判成"需要联网"。
 */
class WebSearchPolicyTest {

    // ── 明确要求联网:必须抽出查询词(回归测试 9) ────────────────────────

    @Test
    fun `明确要求联网时抽出查询词`() {
        assertEquals("藤井风最新消息", WebSearchPolicy.explicitQuery("帮我搜一下藤井风最新消息"))
        assertEquals("藤井风最新消息", WebSearchPolicy.explicitQuery("帮我搜一下藤井风最新消息。"))
        assertEquals("Pretender 是哪年发行的", WebSearchPolicy.explicitQuery("网上查一下 Pretender 是哪年发行的"))
        assertEquals("胡子男 新专辑", WebSearchPolicy.explicitQuery("给我搜一下 胡子男 新专辑"))
        assertEquals("藤井风", WebSearchPolicy.explicitQuery("搜一下藤井风"))
    }

    @Test
    fun `只说要搜却没给内容时不产出查询词`() {
        assertNull(WebSearchPolicy.explicitQuery("帮我搜一下"))
        assertNull(WebSearchPolicy.explicitQuery("查一下"))
        assertNull(WebSearchPolicy.explicitQuery(""))
    }

    @Test
    fun `没提联网就不该产出查询词`() {
        for (t in listOf(
            "藤井风最近有什么新歌?",
            "你觉得藤井风怎么样?",
            "秋天就喜欢听些苦情歌。",
            "播放 Pretender",
        )) {
            assertNull("「$t」没有明确要求联网", WebSearchPolicy.explicitQuery(t))
        }
    }

    // ── 时效性问法:提示联网(回归测试 10) ──────────────────────────────

    @Test
    fun `需要最新信息的问法被判为时效性`() {
        for (t in listOf(
            "藤井风最近有什么新歌?",
            "胡子男最新专辑什么时候出",
            "这首歌是哪一年发行的",
            "乐队现在怎么样了",
            "今年有什么巡演",
        )) {
            assertTrue("「$t」需要联网核实", WebSearchPolicy.looksTimeSensitive(t))
        }
    }

    // ── 观点 / 闲聊:不该被当成需要联网(回归测试 11、12) ───────────────

    @Test
    fun `观点与闲聊不是时效性问法`() {
        for (t in listOf(
            "你觉得藤井风怎么样?",
            "这首歌真的很适合秋天",
            "秋天就喜欢听些苦情歌。",
            "这首歌前奏很好听",
            "我很喜欢这首歌",
        )) {
            assertFalse("「$t」不该判为需要联网", WebSearchPolicy.looksTimeSensitive(t))
        }
    }

    @Test
    fun `音乐观点能被识别出来 - 供提示词提醒不要乱搜`() {
        for (t in listOf("你觉得 Pretender 怎么样", "这首歌很适合秋天", "我喜欢这首", "这首歌好伤感")) {
            assertTrue("「$t」是观点", WebSearchPolicy.looksOpinion(t))
        }
        assertFalse("「藤井风最近有什么新歌」是事实问题,不是观点",
            WebSearchPolicy.looksOpinion("藤井风最近有什么新歌?"))
    }
}

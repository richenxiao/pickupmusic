package com.shiyin.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * v1.3.9: 对话历史反序列化 —— 锁死「点击历史对话闪退」。
 *
 * 根因:Gson 用 Unsafe 分配对象、不执行构造器,JSON 里缺失的字段(旧构建写的行、
 * 或后加的 processHistory/steps)会被置成 null 塞进 Kotlin 非空属性;UI 直接读
 * `msg.processHistory.size` / `msg.text` 即 NPE 崩溃。decodeAgentMessages 负责净化。
 */
class AgentConversationCodecTest {

    @Test
    fun `缺少 processHistory 与 steps 的旧行不会产生 null 字段`() {
        val oldJson = """[{"role":"user","text":"你好"},{"role":"agent","text":"回复"}]"""
        val msgs = decodeAgentMessages(oldJson)
        assertEquals(2, msgs.size)
        assertEquals("你好", msgs[0].text)
        // 关键:非空,UI 读 .size 不会 NPE
        assertNotNull(msgs[0].processHistory)
        assertEquals(0, msgs[0].processHistory.size)
        assertNotNull(msgs[0].text)
    }

    @Test
    fun `非法或空 JSON 返回空列表而不是抛异常`() {
        assertEquals(0, decodeAgentMessages("not a json").size)
        assertEquals(0, decodeAgentMessages("").size)
    }

    @Test
    fun `字段缺失时给出兜底值`() {
        val msgs = decodeAgentMessages("""[{"thinkingMs":1234}]""")
        assertEquals(1, msgs.size)
        assertEquals("agent", msgs[0].role)
        assertEquals("", msgs[0].text)
        assertEquals(1234L, msgs[0].thinkingMs)
    }

    @Test
    fun `完整往返保留步骤与过程历史`() {
        val json = """[{"role":"agent","text":"ok","steps":[{"id":"fetch","label":"联网","status":"DONE"}],
            "processHistory":[{"type":"thinking","label":"已思考（用时 2 秒）","durationMs":2000,"summary":"s"}]}]"""
        val msgs = decodeAgentMessages(json)
        assertEquals(1, msgs.size)
        assertEquals(1, msgs[0].steps?.size)
        assertEquals("fetch", msgs[0].steps?.first()?.id)
        assertEquals(1, msgs[0].processHistory.size)
        assertEquals(2000L, msgs[0].processHistory[0].durationMs)
    }
}

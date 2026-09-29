package com.shiyin.music.data.ai

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.11:Persona 是**冻结资产**。
 *
 * 这是人工长期调整出来的东西,任何一次"顺手改一句措辞"都会让拾光变成另一个人
 * (用户对人格漂移极其敏感)。本测试锁住它的内容摘要 —— 谁改了 persona,这里立刻红。
 *
 * 刻意用**摘要**而不是复制一份原文:测试里存第二份 Persona 只会变成新的漂移源头
 * (两份都改才算"同步",迟早对不上)。摘要只回答"变没变",不含可被误当作真源的文案。
 *
 * 注意:persona 是 `AgentEngine.persona` 单一真源,运行时/工具分发两条链路都引用它
 * (见 AgentEngine.route 与 AgentTools.dispatchSystemPrompt);本测试直接验证该真源本身。
 */
class PersonaRegressionTest {

    @Test
    fun `persona 内容未被改动`() {
        assertEquals(
            "Persona 被改动了。若是有意修改人设,请同步更新本摘要;否则请还原。",
            EXPECTED_SHA256,
            sha256(AgentEngine.persona),
        )
    }

    @Test
    fun `persona 仍然是非空的一整段人设`() {
        assertTrue("人设不能变空", AgentEngine.persona.length > 500)
        assertTrue("必须仍以「你是「拾光」」开篇", AgentEngine.persona.startsWith("你是「拾光」"))
    }

    @Test
    fun `persona 不含记忆与领域上下文档 - 分层没被破坏`() {
        assertFalse("记忆块不属于人设", AgentEngine.persona.contains("[关于这位用户"))
        assertFalse("领域上下文不属于人设", AgentEngine.persona.contains("[当前上下文]"))
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** 若确认要改人设,把本值换成新摘要(先跑一次测试,失败信息里会给出实际值)。
         *  当前值 = v1.3.11 开工前的基线,人设在此之前已定稿;本次 Memory 改动**未触碰**
         *  persona(改动只涉及 route 签名、contextSections、routeUserMessage 与注入方式)。 */
        const val EXPECTED_SHA256 = "4320515c6f605cb9904fa26812c432672f467fefe3cca0d66b3c4b73c977dfe5"
    }
}

package com.shiyin.music.data.ai

import com.shiyin.music.data.ai.AgentEngine.Step
import com.shiyin.music.data.ai.AgentEngine.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.14:步骤面板**终态**规则的回归测试。
 *
 * 钉的是真机 bug:出现过"AI 核对生成顺序 ✓,但文案说失败"——面板与文案自相矛盾。
 * 规则现在只在 [finalizeStepStatuses] 一处定义,所以这里逐条钉死。
 */
class StepStatusFinalizerTest {

    private fun step(id: String, status: StepStatus, error: String? = null) = Step(id, id, status, error)

    @Test
    fun `成功时进行中的行标 DONE - 没跑过的行移除`() {
        val out = finalizeStepStatuses(
            listOf(
                step("tracks", StepStatus.DONE),
                step("search", StepStatus.RUNNING),
                step("writeback", StepStatus.PENDING),
            ),
            success = true,
        )
        assertEquals(listOf("tracks", "search"), out.map { it.id })
        assertTrue("RUNNING 应收成 DONE", out.none { it.status == StepStatus.RUNNING })
        assertEquals(StepStatus.DONE, out.last().status)
    }

    @Test
    fun `失败时已有真实失败行不被覆盖 - 其余进行中的行标 FAILED`() {
        val real = step("search", StepStatus.FAILED, error = "未搜到相关资料")
        val out = finalizeStepStatuses(
            listOf(step("tracks", StepStatus.DONE), real, step("analyze", StepStatus.RUNNING)),
            success = false,
        )
        val keptSearch = out.first { it.id == "search" }
        assertEquals("真实失败行仍是 FAILED", StepStatus.FAILED, keptSearch.status)
        assertEquals("真实失败行的原因必须原样保留", "未搜到相关资料", keptSearch.error)
        assertEquals("进行中的行应标 FAILED", StepStatus.FAILED, out.first { it.id == "analyze" }.status)
    }

    @Test
    fun `失败且没有失败行时 - 最后一行补标 FAILED 让失败可见`() {
        val out = finalizeStepStatuses(
            listOf(step("tracks", StepStatus.DONE), step("search", StepStatus.DONE), step("analyze", StepStatus.DONE)),
            success = false,
        )
        assertTrue("面板上必须至少有一行 FAILED", out.any { it.status == StepStatus.FAILED })
        assertEquals("补标的是最后一行", "analyze", out.last().id)
        assertEquals("前面的行不该被改动", StepStatus.DONE, out.first().status)
    }

    @Test
    fun `失败且只剩进行中的行时 - 直接标 FAILED(无需补标最后一行)`() {
        val out = finalizeStepStatuses(listOf(step("analyze", StepStatus.RUNNING)), success = false)
        assertEquals(1, out.size)
        assertEquals(StepStatus.FAILED, out.single().status)
    }

    @Test
    fun `失败时没有可标的行为就不硬造 - 空列表与全是 PENDING 都返回空`() {
        assertTrue("技能没声明步骤 → 不硬造行", finalizeStepStatuses(emptyList(), success = false).isEmpty())
        assertTrue(
            "还没跑任何步骤 → 移除后为空,同样不硬造行",
            finalizeStepStatuses(listOf(step("a", StepStatus.PENDING)), success = false).isEmpty(),
        )
    }

    @Test
    fun `失败时保留真实失败行 - 不会因为补标逻辑把它的状态改回去`() {
        // 技能只标了一行失败、没有进行中的行:应原样返回那一行
        val out = finalizeStepStatuses(listOf(step("search", StepStatus.FAILED, error = "HTTP 429")), success = false)
        assertEquals(1, out.size)
        assertEquals(StepStatus.FAILED, out.single().status)
        assertEquals("HTTP 429", out.single().error)
    }
}

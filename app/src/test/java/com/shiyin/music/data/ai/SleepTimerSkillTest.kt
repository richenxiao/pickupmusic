package com.shiyin.music.data.ai

import com.shiyin.music.data.ai.skills.SleepTimerSkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.8 睡眠定时技能的参数落地测试。
 *
 * 这个技能**不做 LLM 调用**——时长/mode 的抽取已由模型在分发那一次工具调用里完成,
 * execute 只是把结构化参数落到 PlayerController。所以能纯逻辑测完,不需要网络。
 *
 * 重点覆盖"模型参数不理想"的几种形态:没抽到参数、抽错数量级、同时给了互斥字段。
 */
class SleepTimerSkillTest {

    private class FakeCtx : FakeSkillContext() {
        var minutes: Int? = null
        var endOfTrack = false

        override fun setSleepTimer(minutes: Int) {
            this.minutes = minutes
            if (minutes > 0) endOfTrack = false
        }
        override fun setSleepTimerEndOfTrack() {
            endOfTrack = true
            minutes = null
        }
        override fun sleepTimerStatus(): String = when {
            endOfTrack -> "本曲结束后"
            (minutes ?: 0) > 0 -> "剩余 ${minutes}分钟"
            else -> "未开启"
        }
    }

    private val cfg = LlmProviderConfig("test", "test", "https://example.invalid", "", "")

    private fun run(args: Map<String, String>, ctx: FakeCtx) =
        runBlocking { SleepTimerSkill.execute(ctx, args, cfg, "") { _, _ -> } }

    @Test
    fun `分钟数直接落到睡眠定时`() {
        val ctx = FakeCtx()
        val r = run(mapOf("minutes" to "30"), ctx)
        assertEquals(30, ctx.minutes)
        assertTrue(r.success)
        assertTrue("回执应含时长", r.summary.contains("30分钟"))
    }

    @Test
    fun `小时换算在回执里口语化`() {
        val ctx = FakeCtx()
        val r = run(mapOf("minutes" to "90"), ctx)
        assertEquals(90, ctx.minutes)
        assertTrue("90 分钟应显示成 1小时30分钟,实际:${r.summary}", r.summary.contains("1小时30分钟"))
    }

    @Test
    fun `本曲结束后模式`() {
        val ctx = FakeCtx()
        val r = run(mapOf("end_of_track" to "true"), ctx)
        assertTrue(ctx.endOfTrack)
        assertTrue(r.success)
    }

    @Test
    fun `取消睡眠定时`() {
        val ctx = FakeCtx()
        val r = run(mapOf("cancel" to "true"), ctx)
        assertEquals("取消应落到 setSleepTimer(0)", 0, ctx.minutes)
        assertTrue(r.success)
    }

    @Test
    fun `取消优先于同时给出的时长`() {
        val ctx = FakeCtx()
        val r = run(mapOf("cancel" to "true", "minutes" to "30"), ctx)
        assertEquals("用户改主意时不能被 minutes 覆盖", 0, ctx.minutes)
        assertTrue(r.success)
    }

    @Test
    fun `宽松布尔解析`() {
        // 模型可能回 1 / 是 / yes,不能只认严格 "true"
        val ctx = FakeCtx()
        assertTrue(run(mapOf("end_of_track" to "1"), ctx).success)
        assertTrue(ctx.endOfTrack)
    }

    @Test
    fun `没抽到参数时不给默认值而是追问`() {
        val ctx = FakeCtx()
        val r = run(emptyMap(), ctx)
        assertFalse("不该瞎设默认时长", r.success)
        assertNull("不应写入任何定时", ctx.minutes)
        assertTrue("应引导用户补充说法", r.summary.contains("分钟"))
    }

    @Test
    fun `荒谬时长被拒绝`() {
        val ctx = FakeCtx()
        assertFalse("0 分钟应被拒", run(mapOf("minutes" to "0"), ctx).success)
        assertFalse("超过一天应被拒(多半是解析错)", run(mapOf("minutes" to "99999"), ctx).success)
        assertNull(ctx.minutes)
    }
}

package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.11:思考型模型输出预算规则的回归锁(用户报"翻译时好时坏、点两三次才成")。
 *
 * 这段判定的代价很高:判错一边(该重试却不重试)= 用户继续"等半天啥都没有";
 * 判错另一边(不该重试却重试)= 每次点击白烧一次调用。所以逐条钉死。
 */
class LlmBudgetTest {

    @Test
    fun `正文空加思考非空 - 判定为被预算截断`() {
        // 实证形态:content="" reasoning="We need translate Japanese lyrics..." finish=length
        assertTrue(
            shouldRetryWithBiggerBudget("", "We need translate Japanese lyrics...", "length", 2560),
        )
    }

    @Test
    fun `finish_reason 缺失也放行 - 部分网关不回该字段`() {
        assertTrue(shouldRetryWithBiggerBudget("", "长思考", null, 2048))
    }

    @Test
    fun `有正文就不重试 - 与思考长短无关`() {
        // 正常成功(思考长但正文也出来了)不能被当成截断
        assertFalse(shouldRetryWithBiggerBudget("1|译文", "很长的思考", "stop", 2560))
        assertFalse(shouldRetryWithBiggerBudget("1|译文", "很长的思考", "length", 2560))
    }

    @Test
    fun `思考也为空就不重试 - 那是空响应不是截断`() {
        // 两家都不给内容:重发同样的请求大概率还是空(且可能被服务端当异常请求),
        // 交给上层报"AI 未返回可解析的译文"。
        assertFalse(shouldRetryWithBiggerBudget("", "", "stop", 2560))
        assertFalse(shouldRetryWithBiggerBudget("", null, "stop", 2560))
        assertFalse(shouldRetryWithBiggerBudget(null, "", "length", 2560))
    }

    @Test
    fun `拒答不被当成截断`() {
        // 拒答 = 有正文("抱歉我无法提供歌词…")+ finish=stop → 不重试
        assertFalse(shouldRetryWithBiggerBudget("抱歉,我无法提供受版权保护的歌词。", null, "stop", 2560))
    }

    @Test
    fun `finish 为 stop 但有思考无正文 - 不重试`() {
        // 取舍(实测代价决定):`stop` + 正文空 是"模型把答案全放思考里"的既有形态
        // (少数推理模型就是这样,不是被截断)——这种再花 8-12 秒要一次**照样是空**,
        // 只是让用户多干等一轮("等半天啥都没有"正是这么来的)。
        // 所以只在**有截断证据**(length)或**网关没给该字段**时重试。
        assertFalse(shouldRetryWithBiggerBudget("", "思考", "stop", 2560))
    }

    @Test
    fun `纯空白正文也算空`() {
        assertTrue(shouldRetryWithBiggerBudget("   \n\n  ", "思考", "length", 2048))
    }

    @Test
    fun `已顶到上限不再重试`() {
        assertEquals(8192, LLM_MAX_OUTPUT_TOKENS)
        assertFalse(shouldRetryWithBiggerBudget("", "思考", "length", LLM_MAX_OUTPUT_TOKENS))
        // 超过上限(理论上不该发生)同样不重试
        assertFalse(shouldRetryWithBiggerBudget("", "思考", "length", 20000))
    }

    @Test
    fun `双倍预算并封顶`() {
        assertEquals(5120, doubledBudget(2560))
        assertEquals(4096, doubledBudget(2048))
        // 8192 是服务端硬上限(DeepSeek 等):再翻倍会被 HTTP 400 拒绝
        assertEquals(8192, doubledBudget(5000))
        assertEquals(8192, doubledBudget(8192))
    }

    @Test
    fun `两轮预算足以覆盖最坏情况 - 48 行`() {
        // 一次重试后的预算必须够"思考 + 48 行译文":这是修复有效的下限保证。
        val first = 2560
        val second = doubledBudget(first)
        assertTrue("重试后必须显著更大", second > first)
        assertTrue("重试后应能容纳 48 行译文(~1536)+ 充裕思考余量", second >= 5000)
    }

    // ── v1.3.15:关思考只发给 DeepSeek ───────────────────────────────────────

    @Test
    fun `thinking 字段只发给 DeepSeek 端点`() {
        // 发错供应商 = 未知字段 400 = 整次调用失败,比"不省预算"糟得多
        assertTrue(shouldSendNoThinking("https://api.deepseek.com", noThinking = true))
        assertTrue(shouldSendNoThinking("https://api.deepseek.com/v1", noThinking = true))
        assertFalse("别的供应商一律不发", shouldSendNoThinking("https://openrouter.ai/api/v1", noThinking = true))
        assertFalse(shouldSendNoThinking("https://open.bigmodel.cn/api/paas/v4", noThinking = true))
        assertFalse("没要求关思考就不发", shouldSendNoThinking("https://api.deepseek.com", noThinking = false))
    }
}

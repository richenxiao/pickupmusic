package com.shiyin.music.data.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 翻译编排器的完整链路单测。LLM 以假实现注入，不碰网络/Android。
 *
 * 重点覆盖"最容易出错的地方"——缺行补齐、截断、补齐失败不推翻成果、
 * 以及"毫无进展就别再烧 token"。
 */
class LyricsTranslationPipelineTest {

    /** 按顺序吐出预设响应；用完则返回 null（模拟调用失败）。 */
    private class FakeLlm(private vararg val responses: String?) {
        val calls = mutableListOf<Pair<List<Pair<String, String>>, Int>>()
        suspend fun call(messages: List<Pair<String, String>>, maxTokens: Int): String? {
            calls += messages to maxTokens
            if (calls.size > responses.size) return null
            return responses[calls.size - 1]
        }
    }

    private fun pipeline(vararg responses: String?) = FakeLlm(*responses).let {
        LyricsTranslationPipeline { m, t -> it.call(m, t) } to it
    }

    private val lines = listOf("第一行", "第二行", "第三行")

    // ── 一次成功 ─────────────────────────────────────────────────────────────

    @Test
    fun happyPathNeedsSingleRound() = runBlocking {
        val (p, llm) = pipeline("1|L1 译文\n2|L2 译文\n3|L3 译文")
        val out = p.translate("t", "a", lines)
        assertEquals(1, llm.calls.size)
        assertEquals(1, out.rounds)
        assertEquals(mapOf(0 to "L1 译文", 1 to "L2 译文", 2 to "L3 译文"), out.byLine)
        assertEquals(listOf(0, 1, 2), out.requested)
        assertEquals(1.0f, out.coverage, 0.001f)
        assertNull(out.failure)
    }

    @Test
    fun progressReportsZeroThenDone() = runBlocking {
        val (p, _) = pipeline("1|A\n2|B\n3|C")
        val seen = mutableListOf<Pair<Int, Int>>()
        p.translate("t", "a", lines) { d, t -> seen += Pair(d, t) }
        assertEquals(Pair(0, 3), seen.first())
        assertEquals(Pair(3, 3), seen.last())
    }

    // ── 缺行补齐 ─────────────────────────────────────────────────────────────

    @Test
    fun missingLinesTriggerOneCompletionRound() = runBlocking {
        // 第 1 轮漏了第 2 行；第 2 轮补上
        val (p, llm) = pipeline("1|L1\n3|L3", "2|L2 补译")
        val out = p.translate("t", "a", lines)
        assertEquals(2, llm.calls.size)
        assertEquals(2, out.rounds)
        assertEquals(mapOf(0 to "L1", 1 to "L2 补译", 2 to "L3"), out.byLine)
        assertEquals(1.0f, out.coverage, 0.001f)
        // 补齐轮只应请求缺失的那一行
        val completionUser = llm.calls[1].first[1].second
        assertTrue(completionUser.contains("只翻译这些行：2"))
    }

    @Test
    fun truncationIsRepairedByCompletionRound() = runBlocking {
        // 长歌被 max_tokens 截断：只回了前 2 行
        val (p, _) = pipeline("1|L1\n2|L2", "3|L3 补译")
        val out = p.translate("t", "a", lines)
        assertEquals(2, out.rounds)
        assertEquals(3, out.byLine.size)
        assertNull(out.failure)
    }

    @Test
    fun completionRoundFailureKeepsFirstRoundResults() = runBlocking {
        // 第 2 轮调用失败（null）→ 已有译文照常返回，不整体判失败
        val (p, _) = pipeline("1|L1\n3|L3", null)
        val out = p.translate("t", "a", lines)
        assertEquals(2, out.rounds)
        assertEquals(mapOf(0 to "L1", 2 to "L3"), out.byLine)
        assertNull("已有成果不该被判为整体失败", out.failure)
        assertTrue(out.coverage < 1f)
    }

    @Test
    fun noProgressInCompletionRoundStopsEarly() = runBlocking {
        // 补齐轮返回同样的残缺结果 → 不应再发第 3 轮（别白烧 token）
        val (p, llm) = pipeline("1|L1", "1|L1 又一次")
        val out = p.translate("t", "a", lines, maxRounds = 5)
        assertEquals("毫无进展就该跳出", 2, llm.calls.size)
        assertEquals(2, out.rounds)
    }

    @Test
    fun maxRoundsOneSkipsCompletionEntirely() = runBlocking {
        val (p, llm) = pipeline("1|L1")
        val out = p.translate("t", "a", lines, maxRounds = 1)
        assertEquals(1, llm.calls.size)
        assertEquals(1, out.rounds)
        assertEquals(mapOf(0 to "L1"), out.byLine)
    }

    // ── 失败与异常 ───────────────────────────────────────────────────────────

    @Test
    fun firstCallFailureYieldsFailureOutcome() = runBlocking {
        val (p, _) = pipeline(null)
        val out = p.translate("t", "a", lines)
        assertEquals(1, out.rounds)
        assertTrue(out.byLine.isEmpty())
        assertNotNull(out.failure)
        assertEquals(listOf(0, 1, 2), out.requested)
    }

    @Test
    fun refusalOutputYieldsFailureOutcome() = runBlocking {
        val (p, _) = pipeline("抱歉，我无法完成这个翻译请求。", "抱歉，我无法完成。")
        val out = p.translate("t", "a", lines)
        assertTrue(out.byLine.isEmpty())
        assertNotNull(out.failure)
        assertEquals(0f, out.coverage, 0.001f)
    }

    @Test
    fun llmExceptionIsContainedNotPropagated() = runBlocking {
        val p = LyricsTranslationPipeline { _, _ -> throw java.io.IOException("boom") }
        val out = p.translate("t", "a", lines)
        assertNotNull("异常应被兜住并转成失败结果", out.failure)
    }

    /**
     * 取消必须以 CancellationException 原样穿透。
     *
     * 若被当成"调用失败"吞掉，编排会继续往下跑补齐轮——用户已经取消了，却还在烧 token。
     */
    @Test
    fun cancellationPropagatesInsteadOfBeingTreatedAsFailure() {
        val p = LyricsTranslationPipeline { _, _ -> throw kotlinx.coroutines.CancellationException("cancelled") }
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { p.translate("t", "a", lines) }
        }
    }

    @Test
    fun cancellationDuringCompletionRoundAlsoPropagates() {
        var calls = 0
        val p = LyricsTranslationPipeline { _, _ ->
            calls++
            if (calls == 1) "1|L1" // 首轮成功但缺行 → 会进补齐轮
            else throw kotlinx.coroutines.CancellationException("cancelled mid-way")
        }
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { p.translate("t", "a", lines) }
        }
        assertEquals(2, calls)
    }

    // ── 行挑选与协议违规统计 ─────────────────────────────────────────────────

    @Test
    fun blankLinesAreNotRequestedButSymbolsAreRequestedAndKeepOriginalNumbering() = runBlocking {
        // v1.3.x 反馈⑥:符号行(♪)也送译——只过滤空白行。漏译比错译更糟。
        val withBlanks = listOf("A", "", "♪", "D")
        val (p, llm) = pipeline("1|A 译\n3|♪\n4|D 译")
        val out = p.translate("t", "a", withBlanks)
        assertEquals(listOf(0, 2, 3), out.requested)
        assertEquals(mapOf(0 to "A 译", 2 to "♪", 3 to "D 译"), out.byLine)
        // 提示词里行号应为 1、3、4(跳号)
        val user = llm.calls[0].first[1].second
        assertTrue(user.contains("1|A"))
        assertTrue(user.contains("3|♪"))
        assertTrue(user.contains("4|D"))
    }

    @Test
    fun emptyLyricsReturnsImmediatelyWithoutCallingLlm() = runBlocking {
        val (p, llm) = pipeline("should not be used")
        // 全是空白行/空格行——无内容可译,不调用 LLM
        val out = p.translate("t", "a", listOf("", "  ", "\t"))
        assertEquals(0, llm.calls.size)
        assertEquals(0, out.rounds)
        assertTrue(out.requested.isEmpty())
        assertNull(out.failure)
        assertEquals(1f, out.coverage, 0.001f)
    }

    @Test
    fun protocolViolationsAreAggregatedAcrossRounds() = runBlocking {
        // 第 1 轮：行号重复 + 编造行号 99；第 2 轮补齐
        val (p, _) = pipeline("1|首次\n1|二次\n99|编造\n2|L2\n3|L3", "1|补齐")
        val out = p.translate("t", "a", lines)
        assertEquals(1, out.duplicates)
        assertEquals(listOf(98), out.outOfRange)
    }

    @Test
    fun maxTokensBudgetIsPassedToLlm() = runBlocking {
        val (p, llm) = pipeline("1|A\n2|B\n3|C")
        p.translate("t", "a", lines)
        val expected = LyricsTranslationProtocol.suggestedMaxTokens(3)
        assertEquals(expected, llm.calls[0].second)
    }

    @Test
    fun progressIsMonotonic() = runBlocking {
        val (p, _) = pipeline("1|L1", "2|L2\n3|L3")
        val seen = mutableListOf<Int>()
        p.translate("t", "a", lines) { d, _ -> seen += d }
        assertFalse("进度不应回退", seen.zipWithNext().any { (a, b) -> b < a })
        assertEquals(3, seen.last())
    }

    // ── v1.3.11:正文全空(思考吃光预算)与"格式不符"必须区别对待 ──────────────

    @Test
    fun blankBodySkipsCompletionRoundAndReportsReason() = runBlocking {
        // 用户实证:思考型模型思考太久时 content 返回**空串**(不是格式问题)。
        // 补齐轮的请求形状与第一次相同 → 只会让用户再干等 8-12 秒后同样失败,必须跳掉。
        val (p, llm) = pipeline("", "")
        val out = p.translate("t", "a", lines)
        assertEquals("正文全空时应只发一次调用", 1, llm.calls.size)
        assertEquals(1, out.rounds)
        assertTrue(out.byLine.isEmpty())
        assertEquals("模型没有返回译文正文（思考占满了输出预算）", out.failure)
    }

    @Test
    fun nonBlankButUnparseableBodyStillTriesCompletionRound() = runBlocking {
        // 有正文但解析不出行号(如模型加了 markdown 或先说了段废话)是**另一类**问题:
        // 补齐轮带着语境重问是有意义的,不能跟"正文全空"一起跳掉。
        val (p, llm) = pipeline("这是一段没有行号的说明文字", "1|L1\n2|L2\n3|L3")
        val out = p.translate("t", "a", lines)
        assertEquals("有正文时应照常走补齐轮", 2, llm.calls.size)
        assertEquals(1.0f, out.coverage, 0.001f)
        assertNull(out.failure)
    }
}

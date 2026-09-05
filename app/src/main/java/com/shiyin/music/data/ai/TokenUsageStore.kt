package com.shiyin.music.data.ai

import android.content.Context
import com.shiyin.music.data.db.AppDatabase

/**
 * v1.2.2 Agent: LLM tokens 消耗记录与累计查询的薄封装。
 *
 * 每次 LLM 调用后调 [log] 记一行(写 token_usage_log 表);UI 调 [totals] 读累计
 * prompt/completion tokens 显示"累计消耗"。日志只追加,不广播给 Compose(不在主线程写),
 * 避免高频 LLM 调用触发 Flow 重绘。
 */
object TokenUsageStore {

    suspend fun log(
        context: Context,
        provider: String,
        skill: String,
        promptTokens: Int,
        completionTokens: Int,
        /** v1.3.6: 缓存命中 token(OpenAI prompt_tokens_details.cached_tokens)。 */
        cacheTokens: Int = 0,
    ) {
        runCatching {
            AppDatabase.get(context).dao().insertTokenUsage(
                com.shiyin.music.data.db.TokenUsageLogEntity(
                    timestamp = System.currentTimeMillis(),
                    provider = provider,
                    skill = skill,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    cacheTokens = cacheTokens,
                )
            )
        }
    }

    /** 累计 prompt/completion tokens。读失败 → (0,0)。 */
    suspend fun totals(context: Context): Pair<Long, Long> = runCatching {
        val t = AppDatabase.get(context).dao().tokenUsageTotals()
        t.promptTotal to t.completionTotal
    }.getOrDefault(0L to 0L)

    /**
     * v1.3.5: 近 N 天逐日 token 用量(设置页曲线)。返回 [days] 个点(含今天),
     * 从旧到新;没有记录的天补 0。天边界用 UTC epoch 天(timestamp/86400000)——
     * 与 SQL 分组一致,不引入时区换算的错位。
     */
    suspend fun daily(context: Context, days: Int = 14): List<Long> = runCatching {
        val dao = AppDatabase.get(context).dao()
        val today = System.currentTimeMillis() / 86_400_000L
        val first = today - (days - 1)
        val rows = dao.tokenUsageDaily(first * 86_400_000L).associate { it.day to it.tokens }
        (first..today).map { d -> rows[d] ?: 0L }
    }.getOrDefault(List(days) { 0L })

    /**
     * v1.3.6: 近 N 天逐日多指标(输入/输出/缓存命中 token;成本由 UI 按模型价表算)。
     * 返回 [days] 个点(含今天,从旧到新,空天补 0)。
     */
    suspend fun dailyMetrics(context: Context, days: Int): List<Triple<Long, Long, Long>> = runCatching {
        val dao = AppDatabase.get(context).dao()
        val today = System.currentTimeMillis() / 86_400_000L
        val first = today - (days - 1)
        val rows = dao.tokenUsageMetrics(first * 86_400_000L).associateBy { it.day }
        (first..today).map { d ->
            val r = rows[d]
            if (r == null) Triple(0L, 0L, 0L) else Triple(r.inputTokens, r.outputTokens, r.cacheTokens)
        }
    }.getOrDefault(List(days) { Triple(0L, 0L, 0L) })
}

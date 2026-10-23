package com.shiyin.music.data.ai

import android.content.Context
import com.shiyin.music.data.db.AppDatabase
import com.shiyin.music.data.db.SkillUsage

/** 逐日一个点(面板主图的输入/输出/缓存三条曲线)。 */
data class DailyPoint(val input: Long, val output: Long, val cache: Long)

/**
 * v1.3.10 需求 A19:用量面板一次要的全部数据。
 *
 * 一次拉齐(而不是让 UI 用三四个 remember 各自去查)——面板上"合计/曲线/功能榜/累计"
 * 必须是**同一时刻、同一时间窗口**的数字,分开查会出现自相矛盾的画面。
 */
data class UsageSnapshot(
    /** 逐日曲线(旧→新),与 [dayRange] 一一对应。 */
    val daily: List<DailyPoint>,
    /** 每个点对应的本地天序号(给 X 轴日期用)。 */
    val dayRange: List<Long>,
    /** 按功能归因(token 量降序)。 */
    val bySkill: List<SkillUsage>,
    /** 范围内的 LLM 调用次数——让"总量"可被核对(如 6 次 × 约 2k)。 */
    val callsInRange: Long,
    /** 累计至今(全部历史,不受范围影响)。 */
    val allTimeInput: Long,
    val allTimeOutput: Long,
    val tzOffsetMs: Long,
) {
    val sumInput: Long get() = daily.sumOf { it.input }
    val sumOutput: Long get() = daily.sumOf { it.output }
    val sumCache: Long get() = daily.sumOf { it.cache }
    val hasData: Boolean get() = sumInput + sumOutput > 0
}

/**
 * v1.2.2 Agent: LLM tokens 消耗记录与查询的薄封装。
 *
 * 每次 LLM 调用后调 [log] 记一行(写 token_usage_log 表);UI 调 [snapshot] 读面板数据。
 * 日志只追加,不广播给 Compose(不在主线程写),避免高频 LLM 调用触发 Flow 重绘。
 *
 * ⚠️ 消耗必须**每一处 LLM 调用都记**。v1.3.10 A19 发现振假名兜底层
 * (MainViewModel 的 furigana assist)调了 LlmClient 却从不 log —— 面板因此少算一块,
 * 这也是使用场景的"准度不够"。新增任何 LLM 调用点都要在这里补一行。
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
     * v1.3.10 A19: 面板数据快照(近 [days] 天,含今天)。
     *
     * 天边界按**本地零点**算(TokenUsageWindow 与本文件外的 SQL 同一套算术)。
     * 读失败 → 全空快照(面板显示"暂无使用记录",不崩)。
     */
    suspend fun snapshot(context: Context, days: Int): UsageSnapshot {
        val now = System.currentTimeMillis()
        val off = TokenUsageWindow.currentTzOffsetMs(now)
        val dayRange = TokenUsageWindow.dayRange(days, now, off)
        val empty = UsageSnapshot(
            daily = List(dayRange.size) { DailyPoint(0L, 0L, 0L) },
            dayRange = dayRange, bySkill = emptyList(), callsInRange = 0L,
            allTimeInput = 0L, allTimeOutput = 0L, tzOffsetMs = off,
        )
        return runCatching {
            val dao = AppDatabase.get(context).dao()
            val since = TokenUsageWindow.sinceFor(days, now, off)
            val rows = dao.tokenUsageMetrics(since, off).associateBy { it.day }
            val all = dao.tokenUsageTotals()
            empty.copy(
                daily = dayRange.map { d ->
                    val r = rows[d]
                    if (r == null) DailyPoint(0L, 0L, 0L)
                    else DailyPoint(r.inputTokens, r.outputTokens, r.cacheTokens)
                },
                bySkill = dao.tokenUsageBySkill(since),
                callsInRange = dao.tokenUsageCalls(since),
                allTimeInput = all.promptTotal,
                allTimeOutput = all.completionTotal,
            )
        }.getOrDefault(empty)
    }
}

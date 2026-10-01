package com.shiyin.music.data.ai

/**
 * v1.3.14:技能回合收尾时的**步骤面板终态**计算(纯函数,可单测)。
 *
 * 为什么单独抽出来:真机出现过"AI 核对生成顺序 ✓,但文案说失败"这种自相矛盾的面板。
 * 成因是两条链路各写一套终态逻辑 —— `AgentService` 的失败标记经 `mainHandler.post`
 * 投递、晚于 `seal()` 被丢弃,而收尾清扫却无条件把进行中的行标成功。规则收在这里以后,
 * "终态该怎么落"只有一处定义,也就能被测试钉住。
 *
 * 规则:
 *  1. 进行中(RUNNING)的行按**技能最终结果**落终态:成功 → DONE,失败 → FAILED;
 *  2. 没跑过的(PENDING)行移除 —— 不误导"还有事没做";
 *  3. 失败时**保证至少一行 FAILED**,让"失败"在 UI 上看得见;但**不覆盖**已经存在的
 *     真实失败行(技能/服务已标出的那行原样保留,不会被改)。
 *
 * 边界:没有可标的行为(技能没声明步骤,或步骤全被移除)→ **不硬造行**,返回原列表。
 */
internal fun finalizeStepStatuses(
    steps: List<AgentEngine.Step>,
    success: Boolean,
): List<AgentEngine.Step> {
    if (steps.isEmpty()) return steps
    val runningFinal = if (success) AgentEngine.StepStatus.DONE else AgentEngine.StepStatus.FAILED
    val mapped = steps
        .map { if (it.status == AgentEngine.StepStatus.RUNNING) it.copy(status = runningFinal) else it }
        .filter { it.status != AgentEngine.StepStatus.PENDING }
    if (!success && mapped.none { it.status == AgentEngine.StepStatus.FAILED } && mapped.isNotEmpty()) {
        return mapped.dropLast(1) + mapped.last().copy(status = AgentEngine.StepStatus.FAILED)
    }
    return mapped
}

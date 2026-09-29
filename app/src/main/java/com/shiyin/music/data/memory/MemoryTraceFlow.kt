package com.shiyin.music.data.memory

/**
 * v1.3.11 第二阶段:一轮 Agent Turn 的记忆状态机 —— **一轮最多一行**。
 *
 * 上一版的缺陷:保存与检索各持一个槽位,渲染时拼成 list,于是同一轮里
 * 「记忆已保存」和「已找到记忆」变成两行并列——它被做成了"事件列表",
 * 而用户需要的是"这一轮 Memory 现在走到哪一步"。
 *
 * 本类把"一行"变成**结构性保证**:UI 只渲染 [uiRows],而它最多一个元素。
 * 相位变化是**同一条状态被替换**,不是新增。
 *
 * 调用顺序由 MainViewModel 按真实执行结果驱动(先检索、再按 dedupe/update 决定保存),
 * 模型不参与、也不生成这些状态。
 */
class MemoryTraceFlow {

    /** 当前相位(UI 渲染的唯一来源)。null = 本轮没有记忆活动,不显示任何东西。 */
    var current: MemoryTrace? = null
        private set

    private val phases = mutableListOf<MemoryTrace.Phase>()

    /** 本轮**经历过**的相位序列。只为测试/诊断,不用于渲染。 */
    val history: List<MemoryTrace.Phase> get() = phases

    /** UI 渲染用:0 或 1 行。这一个属性就是"一轮一行"的全部保证。 */
    val uiRows: List<MemoryTrace> get() = listOfNotNull(current)

    /** 本轮是否有记忆活动。 */
    val active: Boolean get() = current != null

    /** 新一轮开始:清空状态与相位历史(历史按"本轮"计,便于诊断与断言)。 */
    fun reset(): MemoryTraceFlow = apply {
        current = null
        phases.clear()
    }

    fun begin(): MemoryTraceFlow = apply { move(MemoryTrace.checking) }

    fun found(count: Int): MemoryTraceFlow = apply { move(MemoryTrace.found(count)) }

    fun nothingFound(): MemoryTraceFlow = apply { move(MemoryTrace.notFound) }

    fun saving(): MemoryTraceFlow = apply { move(MemoryTrace.saving) }

    fun saved(): MemoryTraceFlow = apply { move(MemoryTrace.saved) }

    fun saveFailed(): MemoryTraceFlow = apply { move(MemoryTrace.saveFailed) }

    fun recallFailed(): MemoryTraceFlow = apply { move(MemoryTrace.recallFailed) }

    private fun move(t: MemoryTrace) {
        current = t
        phases += t.phase
    }
}

package com.shiyin.music.data.memory

/**
 * v1.3.11:一次 Agent 回合里「记忆」这件事的**运行状态**(可观测性)。
 *
 * 它表达的是**"当前这一轮 Memory 走到哪一步了"**,不是"发生过哪些记忆事件"。
 * 因此一轮只渲染**一行**,相位在同一条上更新(见 [MemoryTraceFlow])。
 *
 * 为什么需要它:记忆出问题时,从对话里根本看不出是哪一步断的——是没保存?保存失败?
 * 没检索到?检索异常?没注入 prompt?注入了模型没用?这几步各自对应的修法完全不同。
 *
 * 边界:
 *  - 这是 **UI runtime state**,属于**当前 Agent 回合**,不是记忆数据本身 → **不落库**、
 *    不进 messagesJson(它不该永久占据聊天记录)。
 *  - 文案只写用户看得懂的话。**绝不出现类名/表名/FTS/embedding** 这类内部名词。
 *  - [running] 是唯一"该放动画"的判据——只有真正进行中才动;终态一律静止。
 *  - **状态必须由程序真实执行结果驱动**,绝不由模型生成(模型只负责最终自然语言回复)。
 */
data class MemoryTrace(
    val phase: Phase,
    /** [Phase.FOUND] 时的命中条数(仅用于诊断,不在 UI 上展示条数)。 */
    val count: Int = 0,
) {
    enum class Phase {
        /** 没有记忆活动(等价于"没有这一行",保留此值只为语义完整)。 */
        IDLE,
        /** 正在检索与这条消息相关的记忆。 */
        CHECKING,
        /** 检索命中(记忆会被注入本轮上下文)。 */
        FOUND,
        /** 检索正常完成但**没有**相关记忆(**不是错误**)。 */
        NOT_FOUND,
        /** 正在把值得长期记住的信息写库(显式「记住」或自动提取出的候选)。 */
        SAVING,
        /** 写库成功。 */
        SAVED,
        /** 写库失败(异常或规则拒收)。 */
        SAVE_FAILED,
        /** 检索过程出异常(**与"没找到"必须分开**——两者修法不同)。 */
        RECALL_FAILED,
    }

    /** 进行中 → UI 放左→右光流动画。终态一律静止。 */
    val running: Boolean get() = phase == Phase.CHECKING || phase == Phase.SAVING

    /** 给用户看的一句话。IDLE 为空(不显示)。 */
    val label: String
        get() = when (phase) {
            Phase.IDLE -> ""
            Phase.CHECKING -> "查找记忆中…"
            Phase.FOUND -> "已找到相关记忆"
            Phase.NOT_FOUND -> "未找到相关记忆"
            Phase.SAVING -> "记忆中…"
            Phase.SAVED -> "记忆已保存"
            Phase.SAVE_FAILED -> "记忆保存失败"
            Phase.RECALL_FAILED -> "回忆失败"
        }

    companion object {
        val checking = MemoryTrace(Phase.CHECKING)
        val notFound = MemoryTrace(Phase.NOT_FOUND)
        val saving = MemoryTrace(Phase.SAVING)
        val saved = MemoryTrace(Phase.SAVED)
        val saveFailed = MemoryTrace(Phase.SAVE_FAILED)
        val recallFailed = MemoryTrace(Phase.RECALL_FAILED)
        fun found(count: Int) = MemoryTrace(Phase.FOUND, count)
    }
}

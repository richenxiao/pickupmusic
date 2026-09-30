package com.shiyin.music.data.memory

/**
 * v1.3.10 Memory P1:拾光的长期记忆**域模型**(纯数据,无 Android 依赖)。
 *
 * 与 Persona 的边界:Persona 是"拾光是谁"(AgentEngine.persona),Memory 是"拾光认识这个
 * 用户什么"。两者架构分离——记忆**永不写进人格提示词**,只作为运行时注入的动态块。
 *
 * 字段与用户 2026-09-28 定稿一致:id/type/content/createdAt/updatedAt/importance/source。
 */
data class Memory(
    val id: String,
    /** [MemoryType] 白名单之一。 */
    val type: String,
    /** 一句话陈述(人话,可直接展示给用户)。已归一化+截断。 */
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 1-3;越高越优先注入、越晚淘汰。 */
    val importance: Int,
    /** [MemorySource]:explicit(用户明说记住)/ inferred(运行时推断)/ manual(用户手加)。 */
    val source: String,
    /** 检索用关键词(从 content 抽)。 */
    val tags: List<String>,
    /** 冲突时旧条目指向新条目——保留历史,不硬删。null = 当前有效。 */
    val supersededBy: String? = null,
    /**
     * 预留:语义检索用的向量(将来接 embedding)。
     * **本阶段恒为 null、不写入、不读取**——先把列占住,免得以接语义检索时改表结构。
     */
    val embedding: String? = null,
) {
    /** 是否仍然有效(未被新条目取代)。检索只考虑有效条目。 */
    val active: Boolean get() = supersededBy == null
}

/** 记忆类型白名单。超出即拒——类型决定了将来"分组展示"与注入优先级。 */
object MemoryType {
    const val PREFERENCE = "preference"   // 稳定偏好(听歌习惯、交互偏好)
    const val PERSONAL = "personal"       // 个人背景(长期有意义的信息)
    const val EPISODIC = "episodic"       // 情景/共同回忆
    const val MUSIC = "music"             // 音乐记忆(喜欢的艺人/专辑/歌)
    const val RELATIONSHIP = "relationship" // 与拾光之间的关系

    val ALL = setOf(PREFERENCE, PERSONAL, EPISODIC, MUSIC, RELATIONSHIP)
}

object MemorySource {
    /** 用户明确要求记住("记住这个"/"别忘了")——优先级最高,且绕过一次性情绪拒表。 */
    const val EXPLICIT = "explicit"
    /** 运行时从对话里推断出来的。 */
    const val INFERRED = "inferred"
    /** 用户手动添加(将来的管理界面)。 */
    const val MANUAL = "manual"
}

/**
 * 写入候选(来自提取环节或用户明说)。
 *
 * @param userSaid 触发这条候选的**用户原话**。规则要据此判断"是不是明确要求记住"
 *   (仅看 content 不够:content 常被提炼过,原话里的"记住"必须能看见)。
 */
data class MemoryCandidate(
    val type: String,
    val content: String,
    val importance: Int = 2,
    val source: String = MemorySource.INFERRED,
    val userSaid: String = "",
)

/** 写入结果。失败与降级都是返回值的一部分——调用方不猜。 */
sealed interface RememberOutcome {
    /** 新增一条。 */
    data class Saved(val id: String) : RememberOutcome
    /** 与既有条目重复/相似 → 更新旧条目(不新增)。 */
    data class Updated(val id: String) : RememberOutcome
    /** 与既有条目冲突(同主题不同说法) → 新增 + 旧条目标记 supersededBy。 */
    data class Superseded(val oldId: String, val newId: String) : RememberOutcome
    /** 拒收,原因可诊断。 */
    data class Rejected(val reason: Reason) : RememberOutcome

    enum class Reason {
        /** 内容为空/全是标点。 */
        BLANK,
        /** type 不在白名单。 */
        BAD_TYPE,
        /** 一次性情绪("今天有点累")——默认不入长期记忆。 */
        TRANSIENT,
        /** v1.3.13:只是"用户让播放过 X"这类**指令流水**,不是偏好(会把对用户的印象记歪)。 */
        COMMAND_RECORD,
    }
}

/**
 * v1.3.14:写入结果的**稳定日志标签**。
 *
 * 为什么不直接用 `::class.simpleName`:release 包经 R8 会把 `Saved` / `Updated` 这类类名
 * 缩成单字母,使用日志里就成了 `memory C · preference` —— **诊断信息自己先失真**
 * (真机实测)。改成显式字符串,日志里的标签就不再随混淆变化。
 *
 * 放在类型旁边(而不是各调用处各自写)是为了:以后新增一种写入结果时,这里会**编译报错**,
 * 强制补上标签,不会有漏网的分支。
 */
val RememberOutcome.logTag: String
    get() = when (this) {
        is RememberOutcome.Saved -> "Saved"
        is RememberOutcome.Updated -> "Updated"
        is RememberOutcome.Superseded -> "Superseded"
        is RememberOutcome.Rejected -> "Rejected"
    }

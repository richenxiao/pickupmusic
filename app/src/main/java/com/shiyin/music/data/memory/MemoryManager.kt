package com.shiyin.music.data.memory

/**
 * v1.3.10 Memory P1:记忆管理器——**访问记忆的唯一入口**(Agent 不碰数据库)。
 *
 * 职责:写入裁决(校验/去重/冲突)、检索(门槛+top-k)、用户控制(改/删/列表)。
 * 所有**判断**都委托给纯函数 [MemoryRules];本类只做编排与落库顺序。
 *
 * 时钟与 id 生成注入(而非直接 `System.currentTimeMillis()`/`UUID.randomUUID()`):
 * 单测要能精确断言 createdAt/updatedAt/supersededBy,不能靠真实时间。
 */
class MemoryManager(
    private val store: MemoryStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {

    /**
     * 写入一条候选记忆。裁决顺序(**先校验、再去重、最后冲突**):
     *  1. 归一化+截断 → 空内容/非法 type 直接拒;
     *  2. 一次性情绪("今天有点累")默认拒——除非用户明确要求记住;
     *  3. 与既有**有效**条目重复(同一件事的换说法)→ **update 旧条目**,不新增;
     *  4. 与既有条目同主题但说法冲突(「喜欢 aiko」→「不再喜欢 aiko」)→ 新增,
     *     并把旧条目标 [Memory.supersededBy] 指向新条(保留历史,两条不再互相打架)。
     */
    suspend fun remember(candidate: MemoryCandidate): RememberOutcome {
        // v1.3.10 P3:「拾光记忆」管理页里**用户手加**的条目 = 明确指令,与"原话里有记住"
        // 同等对待(否则用户手打"今天有点累"会被一次性情绪拒表挡住——用户都点添加了,
        // 还被拒是产品逻辑说不通)。来源仍记为 manual,不冒充 explicit。
        val manual = candidate.source == MemorySource.MANUAL
        val explicit = manual || MemoryRules.isExplicitRemember(candidate.userSaid)
        val content = MemoryRules.normalizeAndLimit(candidate.content)
        if (!MemoryRules.isUsableContent(content)) return RememberOutcome.Rejected(RememberOutcome.Reason.BLANK)
        if (!MemoryRules.isValidType(candidate.type)) return RememberOutcome.Rejected(RememberOutcome.Reason.BAD_TYPE)
        if (!explicit && MemoryRules.isTransient(content)) {
            return RememberOutcome.Rejected(RememberOutcome.Reason.TRANSIENT)
        }

        val ts = now()
        // 明确要求记住 → importance 抬到最高、来源标 explicit(绕过拒表已在上面处理)
        val importance = if (explicit) maxOf(candidate.importance, 3) else candidate.importance.coerceIn(1, 3)
        val source = when {
            manual -> MemorySource.MANUAL
            explicit -> MemorySource.EXPLICIT
            else -> candidate.source
        }
        val tags = MemoryRules.tagsOf(content)
        val active = store.all().filter { it.active }

        // ③ 重复/换说法 → 更新旧条目(不新增,避免同一件事无限堆条目)
        active.firstOrNull { MemoryRules.isDuplicate(it.content, content) }?.let { dup ->
            // 来源只升不降:用户明说(explicit)/ 手加(manual)都是"用户亲自给的",
            // 不该因为撞上一条旧的推断条目就被降级成 inferred。
            val upgraded = when {
                dup.source == MemorySource.EXPLICIT || source == MemorySource.EXPLICIT -> MemorySource.EXPLICIT
                dup.source == MemorySource.MANUAL || source == MemorySource.MANUAL -> MemorySource.MANUAL
                else -> dup.source
            }
            store.update(
                dup.copy(
                    content = content,
                    updatedAt = ts,
                    importance = maxOf(dup.importance, importance),
                    source = upgraded,
                    tags = tags,
                ),
            )
            return RememberOutcome.Updated(dup.id)
        }

        // ④ 同主题修订 → 新增 + 旧条目标记被取代
        val conflicting = active.firstOrNull { MemoryRules.isSameTopicRevision(it.content, content) }
        val id = newId()
        store.insert(
            Memory(
                id = id, type = candidate.type, content = content,
                createdAt = ts, updatedAt = ts, importance = importance,
                source = source, tags = tags,
            ),
        )
        if (conflicting != null) {
            store.update(conflicting.copy(supersededBy = id, updatedAt = ts))
            return RememberOutcome.Superseded(oldId = conflicting.id, newId = id)
        }
        return RememberOutcome.Saved(id)
    }

    /**
     * 检索相关记忆(默认 top-3,带相关度门槛)。
     * 相关度不足时返回**空列表**——调用方据此说"没印象",绝不允许编造。
     */
    suspend fun recall(query: String, limit: Int = MemoryRules.DEFAULT_RECALL_LIMIT): List<Memory> =
        MemoryRules.rankAndFilter(query, store.candidates(query), limit)

    /** 用户编辑某条记忆的内容(重算标签、刷新 updatedAt)。返回是否命中。 */
    suspend fun update(id: String, content: String): Boolean {
        val old = store.byId(id) ?: return false
        val newContent = MemoryRules.normalizeAndLimit(content)
        if (!MemoryRules.isUsableContent(newContent)) return false
        store.update(old.copy(content = newContent, updatedAt = now(), tags = MemoryRules.tagsOf(newContent)))
        return true
    }

    /** 用户要求忘记某条(硬删除)。返回是否命中。 */
    suspend fun forget(id: String): Boolean {
        if (store.byId(id) == null) return false
        store.delete(id)
        return true
    }

    /** 供"拾光记忆"管理界面/诊断使用:有效条目(可按类型过滤),最近更新的在前。 */
    suspend fun list(type: String? = null, limit: Int = 50): List<Memory> =
        store.all()
            .filter { it.active && (type == null || it.type == type) }
            .sortedByDescending { it.updatedAt }
            .take(limit)
}

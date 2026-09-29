package com.shiyin.music.data.memory

import com.shiyin.music.data.db.AgentMemoryEntity
import com.shiyin.music.data.db.AgentMemoryFtsEntity
import com.shiyin.music.data.db.ShiyinDao

/**
 * v1.3.10 Memory P1:[MemoryStore] 的 Room 实现。
 *
 * 只做**存取**与 FTS 预筛,**不做任何业务判断**——校验/去重/冲突/相关度门槛全在
 * [MemoryRules] 与 [MemoryManager](纯逻辑,可纯 JVM 单测)。这条分界是整个 P1 的设计要点:
 * 数据质量规则必须能脱离 Android 运行时被验证。
 *
 * ⚠️ P1 阶段**不接入对话链路**(用户明确划界):本类只提供实现,谁都不 new 它。
 * 接线(回合后提取、回合前检索注入)属于 P2。
 */
class RoomMemoryStore(private val dao: ShiyinDao) : MemoryStore {

    override suspend fun all(): List<Memory> = dao.agentMemories().map { it.toDomain() }

    override suspend fun byId(id: String): Memory? = dao.agentMemory(id)?.toDomain()

    override suspend fun insert(m: Memory) {
        dao.upsertAgentMemory(m.toEntity())
        syncFts(m)
    }

    override suspend fun update(m: Memory) {
        dao.upsertAgentMemory(m.toEntity())
        syncFts(m)
    }

    override suspend fun delete(id: String) {
        dao.deleteAgentMemory(id)
        dao.deleteAgentMemoryFts(id)
    }

    /**
     * FTS 预筛。三处**刻意保守**的设计——因为 FTS 只是"收窄扫描面",正确性由纯逻辑兜底:
     *  1. 查询串抽不出可检索词(纯标点/空白)→ 直接返回全量;
     *  2. FTS 报错(表达式语法/SQLite 版本差异)→ 返回全量,不让预筛失败拖垮整条检索;
     *  3. FTS 没命中 → 返回全量。宁可多扫几行(记忆表规模是"几十到几百条"),
     *     也不要因为分词/切分没覆盖到就"检索不到"——那会表现为"她明明记得却说没印象"。
     */
    override suspend fun candidates(query: String): List<Memory> {
        val match = MemoryRules.ftsMatchQuery(query)
        if (match.isBlank()) return all()
        val ids = runCatching { dao.searchAgentMemoryFts(match, FTS_PREFILTER_LIMIT) }.getOrNull()
            ?: return all()
        if (ids.isEmpty()) return all()
        return ids.distinct().mapNotNull { dao.agentMemory(it)?.toDomain() }
    }

    /** FTS 行与主表同步:先删后插——正文/标签改过之后不会残留旧词的索引行。 */
    private suspend fun syncFts(m: Memory) {
        dao.deleteAgentMemoryFts(m.id)
        dao.insertAgentMemoryFts(
            AgentMemoryFtsEntity(
                memoryId = m.id,
                content = MemoryRules.ftsText(m.content),
                tags = MemoryRules.ftsText(m.tags.joinToString(" ")),
            ),
        )
    }

    private fun AgentMemoryEntity.toDomain() = Memory(
        id = id, type = type, content = content,
        createdAt = createdAt, updatedAt = updatedAt,
        importance = importance, source = source,
        tags = tags.split(',').map { it.trim() }.filter { it.isNotBlank() },
        supersededBy = supersededBy, embedding = embedding,
    )

    private fun Memory.toEntity() = AgentMemoryEntity(
        id = id, type = type, content = content,
        createdAt = createdAt, updatedAt = updatedAt,
        importance = importance, source = source,
        tags = tags.joinToString(","),
        supersededBy = supersededBy, embedding = embedding,
    )

    private companion object {
        /** FTS 预筛上限:只是收窄,不是最终结果集(纯逻辑再排序取 top-k)。 */
        const val FTS_PREFILTER_LIMIT = 60
    }
}

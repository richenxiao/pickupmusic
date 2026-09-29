package com.shiyin.music.data.memory

/**
 * v1.3.10 Memory P1:记忆的持久化接口。
 *
 * 为什么抽接口:[MemoryManager] 里的**数据质量规则**必须能纯 JVM 单测(本项目的单测跑在
 * 无 Android 运行时的 JVM 上,Room/Context 都不可用)。生产用 Room 实现
 * ([RoomMemoryStore],FTS 预筛),单测用内存实现。
 */
interface MemoryStore {

    /** 全部记忆(**含已被取代的**——是否有效由 [Memory] 的 active 与管理器判断)。 */
    suspend fun all(): List<Memory>

    suspend fun byId(id: String): Memory?

    suspend fun insert(m: Memory)

    suspend fun update(m: Memory)

    suspend fun delete(id: String)

    /**
     * 检索候选(**预筛**,允许返回比目标更多的行)。
     *
     * 相关度门槛与 top-k **不在这里**——它们由 [MemoryRules.rankAndFilter] 在管理器里做,
     * 这样规则本身可单测。FTS 实现用本方法把扫描面收窄;内存实现直接返回 all()。
     */
    suspend fun candidates(query: String): List<Memory>
}

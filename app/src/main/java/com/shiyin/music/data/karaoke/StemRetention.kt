package com.shiyin.music.data.karaoke

/**
 * v1.5.0 P1：**缓存保留策略与启动对账**（规格 P1 修正 二）——纯逻辑，可单测。
 *
 * 保留模式（用户拍板，设置页可调）：
 *  - [RetentionMode.COUNT]（默认 10 首）：保持最近访问的 N 首，其余按 **LRU** 淘汰；
 *  - [RetentionMode.SIZE_MB]：总占用超上限时按 LRU 淘汰到不超；
 *  - [RetentionMode.NONE]：不保留——**用完即删**（由调用方在播放结束后删除，这里不参与淘汰）。
 *
 * **受保护集合**（当前播放的歌 + 下一首）永不淘汰（用户要求 二.2）。
 */
enum class RetentionMode { COUNT, SIZE_MB, NONE }

object StemRetention {

    /** 一条缓存记录。身份是 [StemCache.SourceKey.dirName]（**物理文件维度**，v1.5.0 块 2 起不再是 songId）。 */
    data class Entry(val keyDirName: String, val bytes: Long, val lastAccess: Long)

    /**
     * 算出该淘汰哪些（返回 `keyDirName`）。
     * 均按 LRU（lastAccess 升序，最久未访问的先走）；受保护的一律保留。
     */
    fun planEviction(
        entries: List<Entry>,
        mode: RetentionMode,
        maxCount: Int = 10,
        maxMb: Int = 0,
        protectedKeys: Set<String> = emptySet(),
    ): List<String> {
        if (mode == RetentionMode.NONE) return emptyList()   // 由调用方"用完即删"
        val evictable = entries.filter { it.keyDirName !in protectedKeys }.sortedBy { it.lastAccess }
        val protectedBytes = entries.filter { it.keyDirName in protectedKeys }.sumOf { it.bytes }
        return when (mode) {
            RetentionMode.COUNT -> {
                // 保留数 = 受保护的 + 最近的 (maxCount - 受保护数)，下限 0
                val keepOthers = (maxCount - protectedKeys.size).coerceAtLeast(0)
                evictable.dropLast(keepOthers).map { it.keyDirName }
            }
            RetentionMode.SIZE_MB -> {
                val limit = maxMb.toLong() * 1024 * 1024
                var total = entries.sumOf { it.bytes }   // 含受保护占用
                val out = mutableListOf<String>()
                for (e in evictable) {                    // 最旧的先淘汰
                    if (total <= limit) break
                    out += e.keyDirName
                    total -= e.bytes
                }
                out
            }
            RetentionMode.NONE -> emptyList()
        }
    }

    // ── P2-A（用户 2026-10-18）：**两个上限同时生效** ──────────────────────────

    /**
     * 用户 2026-10-18 把策略从"二选一"改成"**两条线同时管**"：
     * "两项同时生效，谁先到上限先淘汰最久未用的；留空 = 这一项不限制"。
     *
     * 所以不再有"模式"这一说 —— [maxCount] 与 [maxMb] 各自独立，**0 = 该项不限**。
     * 淘汰顺序永远是**最久未用优先**；只要还有任一项超线就继续淘汰。
     *
     * @return 该淘汰的 `keyDirName`（按淘汰先后）
     */
    fun planEvictionBoth(
        entries: List<Entry>,
        maxCount: Int,
        maxMb: Int,
        protectedKeys: Set<String> = emptySet(),
    ): List<String> {
        if (maxCount <= 0 && maxMb <= 0) return emptyList()   // 两项都不限 = 全留
        val evictable = entries.filter { it.keyDirName !in protectedKeys }.sortedBy { it.lastAccess }
        val limitBytes = if (maxMb > 0) maxMb.toLong() * 1024 * 1024 else 0L
        var kept = entries.size
        var total = entries.sumOf { it.bytes }
        val out = mutableListOf<String>()
        for (e in evictable) {
            val overCount = maxCount > 0 && kept > maxCount
            val overSize = maxMb > 0 && total > limitBytes
            if (!overCount && !overSize) break
            out += e.keyDirName
            kept--
            total -= e.bytes
        }
        return out
    }

    // ── 启动对账 ──────────────────────────────────────────────────────────

    /**
     * 一个"变体目录"（歌 × 模型 × 指纹）的健康状态。
     * 缓存**没有独立索引**——文件系统就是索引，所以不存在"索引里有、磁盘上没有"这类
     * 双向孤儿；真正要收拾的只有**半成品**：
     *  - 没有 manifest 或块数为 0 → 整个目录删掉（重跑时会重建）
     *  - manifest 说 complete 但块数不足 → 标记为未完成（保留已写好的块，下次接着算）
     */
    data class VariantState(
        val path: String,
        val hasManifest: Boolean,
        val declaredComplete: Boolean,
        val declaredChunks: Int,
        val presentChunks: Int,
    )

    sealed interface Action {
        /** 整个变体删掉（空壳 / 无 manifest）。 */
        data class Delete(val path: String) : Action
        /** 降级为未完成（保留已写好的块，下次续算）。 */
        data class MarkIncomplete(val path: String) : Action
    }

    fun planReconcile(variants: List<VariantState>): List<Action> = variants.mapNotNull { v ->
        when {
            !v.hasManifest -> Action.Delete(v.path)
            v.presentChunks == 0 -> Action.Delete(v.path)
            v.declaredComplete && v.presentChunks < v.declaredChunks -> Action.MarkIncomplete(v.path)
            else -> null
        }
    }
}

package com.shiyin.music.data.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 P1：缓存保留策略（三种模式 + LRU + 保护当前/下一首）与启动对账。
 *
 * v1.5.0 块 2：身份由 songId 改为 **物理文件维度的目录名**（`StemCache.SourceKey.dirName`）。
 */
class StemRetentionTest {

    /** id 现在是目录名（物理文件维度）。 */
    private fun e(id: String, mb: Long, access: Long) = StemRetention.Entry(id, mb * 1024 * 1024, access)

    @Test
    fun countModeKeepsNewestAndEvictsOldestFirst() {
        val entries = listOf(e("k1", 40, 100), e("k2", 40, 200), e("k3", 40, 300), e("k4", 40, 400))
        // 保留最近 2 首 → 淘汰最旧的 k1、k2（LRU）
        assertEquals(listOf("k1", "k2"), StemRetention.planEviction(entries, RetentionMode.COUNT, maxCount = 2))
    }

    @Test
    fun countModeNeverEvictsProtectedSongs() {
        val entries = listOf(e("k1", 40, 100), e("k2", 40, 200), e("k3", 40, 400))
        // 保留 2 首，但最旧的 k1 是"当前播放"→ 受保护，不能被淘汰
        val evicted = StemRetention.planEviction(
            entries, RetentionMode.COUNT, maxCount = 2, protectedKeys = setOf("k1"),
        )
        assertTrue("受保护的 k1 不在淘汰名单里", "k1" !in evicted)
        assertEquals(listOf("k2"), evicted)
    }

    @Test
    fun sizeModeEvictsUntilUnderLimit() {
        val entries = listOf(e("k1", 40, 100), e("k2", 40, 200), e("k3", 40, 300))
        // 上限 100MB：120MB 总量 → 淘汰最旧的 k1 后剩 80MB，够了
        assertEquals(listOf("k1"), StemRetention.planEviction(entries, RetentionMode.SIZE_MB, maxMb = 100))
    }

    @Test
    fun sizeModeProtectsCurrentSongEvenIfOverLimit() {
        val entries = listOf(e("k1", 40, 100), e("k2", 200, 500))
        // 上限 50MB，k2 受保护（当前播放，200MB）→ 只能淘汰 k1；超限但不动受保护项
        val evicted = StemRetention.planEviction(
            entries, RetentionMode.SIZE_MB, maxMb = 50, protectedKeys = setOf("k2"),
        )
        assertEquals(listOf("k1"), evicted)
    }

    @Test
    fun noneModeEvictsNothingHere() {
        // NONE 是"用完即删"，由调用方在播放结束后处理 —— 保留策略本身不淘汰
        val entries = listOf(e("k1", 40, 100), e("k2", 40, 200))
        assertTrue(StemRetention.planEviction(entries, RetentionMode.NONE).isEmpty())
    }

    @Test
    fun countModeKeepsEverythingWhenUnderLimit() {
        val entries = listOf(e("k1", 40, 100), e("k2", 40, 200))
        assertTrue(StemRetention.planEviction(entries, RetentionMode.COUNT, maxCount = 10).isEmpty())
    }

    // ── 启动对账 ──────────────────────────────────────────────────────────

    @Test
    fun reconcileDeletesEmptyShellsAndDowngradesBrokenComplete() {
        val actions = StemRetention.planReconcile(
            listOf(
                StemRetention.VariantState("a", hasManifest = false, declaredComplete = false, declaredChunks = 0, presentChunks = 3),
                StemRetention.VariantState("b", hasManifest = true, declaredComplete = false, declaredChunks = 5, presentChunks = 0),
                StemRetention.VariantState("c", hasManifest = true, declaredComplete = true, declaredChunks = 5, presentChunks = 2),
                StemRetention.VariantState("d", hasManifest = true, declaredComplete = true, declaredChunks = 5, presentChunks = 5),
            ),
        )
        assertEquals(
            listOf(
                StemRetention.Action.Delete("a"),          // 无 manifest
                StemRetention.Action.Delete("b"),          // 一块都没有
                StemRetention.Action.MarkIncomplete("c"),  // 号称完整但缺块 → 降级续算
            ),
            actions,
        )
    }
}

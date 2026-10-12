package com.shiyin.music.data.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 P1：缓存保留策略（三种模式 + LRU + 保护当前/下一首）与启动对账。
 */
class StemRetentionTest {

    private fun e(id: Long, mb: Long, access: Long) = StemRetention.Entry(id, mb * 1024 * 1024, access)

    @Test
    fun countModeKeepsNewestAndEvictsOldestFirst() {
        val entries = listOf(e(1, 40, 100), e(2, 40, 200), e(3, 40, 300), e(4, 40, 400))
        // 保留最近 2 首 → 淘汰最旧的 1、2（LRU）
        assertEquals(listOf(1L, 2L), StemRetention.planEviction(entries, RetentionMode.COUNT, maxCount = 2))
    }

    @Test
    fun countModeNeverEvictsProtectedSongs() {
        val entries = listOf(e(1, 40, 100), e(2, 40, 200), e(3, 40, 400))
        // 保留 2 首，但最旧的 1 号是"当前播放"→ 受保护，不能被淘汰
        val evicted = StemRetention.planEviction(
            entries, RetentionMode.COUNT, maxCount = 2, protectedSongIds = setOf(1L),
        )
        assertTrue("受保护的 1 号不在淘汰名单里", 1L !in evicted)
        assertEquals(listOf(2L), evicted)
    }

    @Test
    fun sizeModeEvictsUntilUnderLimit() {
        val entries = listOf(e(1, 40, 100), e(2, 40, 200), e(3, 40, 300))
        // 上限 100MB：120MB 总量 → 淘汰最旧的 1 号后剩 80MB，够了
        assertEquals(listOf(1L), StemRetention.planEviction(entries, RetentionMode.SIZE_MB, maxMb = 100))
    }

    @Test
    fun sizeModeProtectsCurrentSongEvenIfOverLimit() {
        val entries = listOf(e(1, 40, 100), e(2, 200, 500))
        // 上限 50MB，2 号受保护（当前播放，200MB）→ 只能淘汰 1 号；超限但不动受保护项
        val evicted = StemRetention.planEviction(
            entries, RetentionMode.SIZE_MB, maxMb = 50, protectedSongIds = setOf(2L),
        )
        assertEquals(listOf(1L), evicted)
    }

    @Test
    fun noneModeEvictsNothingHere() {
        // NONE 是"用完即删"，由调用方在播放结束后处理 —— 保留策略本身不淘汰
        val entries = listOf(e(1, 40, 100), e(2, 40, 200))
        assertTrue(StemRetention.planEviction(entries, RetentionMode.NONE).isEmpty())
    }

    @Test
    fun countModeKeepsEverythingWhenUnderLimit() {
        val entries = listOf(e(1, 40, 100), e(2, 40, 200))
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

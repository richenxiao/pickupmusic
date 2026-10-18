package com.shiyin.music.data.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-A（用户 2026-10-18）：保存策略由"二选一"改成**两个上限同时生效**。
 *
 * 用户原话："两项同时生效，谁先到上限先淘汰最久未用的；留空 = 这一项不限制。"
 * 所以这里把每种组合都钉住，免得以后谁又把它退回成"模式二选一"。
 */
class StemRetentionBothTest {

    private fun e(name: String, mb: Long, last: Long) =
        StemRetention.Entry(name, mb * 1024 * 1024, last)

    /** 4 首、各 100MB，lastAccess 从小到大。 */
    private val four = listOf(
        e("a", 100, 1), e("b", 100, 2), e("c", 100, 3), e("d", 100, 4),
    )

    @Test
    fun `两项都不限_全留`() {
        assertEquals(emptyList<String>(), StemRetention.planEvictionBoth(four, maxCount = 0, maxMb = 0))
    }

    @Test
    fun `只限曲目数_淘汰最久未用的`() {
        // 保留 2 首 ⇒ 淘汰 a、b（最旧的两个）
        assertEquals(listOf("a", "b"), StemRetention.planEvictionBoth(four, maxCount = 2, maxMb = 0))
    }

    @Test
    fun `只限体积_淘汰到不超`() {
        // 上限 250MB，共 400MB ⇒ 淘汰 a、b 后剩 200MB ✓
        assertEquals(listOf("a", "b"), StemRetention.planEvictionBoth(four, maxCount = 0, maxMb = 250))
    }

    @Test
    fun `两项同时生效_谁先卡住按谁`() {
        // 上限：4 首 / 250MB ⇒ 体积先卡住（淘汰 a、b），个数并没超
        assertEquals(listOf("a", "b"), StemRetention.planEvictionBoth(four, maxCount = 4, maxMb = 250))
        // 上限：2 首 / 1000MB ⇒ 个数先卡住
        assertEquals(listOf("a", "b"), StemRetention.planEvictionBoth(four, maxCount = 2, maxMb = 1000))
    }

    @Test
    fun `受保护的永不淘汰`() {
        // 保留 1 首，但 d 受保护 ⇒ 淘汰 a、b、c，留下 d
        assertEquals(
            listOf("a", "b", "c"),
            StemRetention.planEvictionBoth(four, maxCount = 1, maxMb = 0, protectedKeys = setOf("d")),
        )
    }

    @Test
    fun `已经在上限内则什么都不淘汰`() {
        assertTrue(StemRetention.planEvictionBoth(four, maxCount = 10, maxMb = 1000).isEmpty())
    }

    @Test
    fun `上限为负视为不限`() {
        assertTrue(StemRetention.planEvictionBoth(four, maxCount = -1, maxMb = -1).isEmpty())
    }
}

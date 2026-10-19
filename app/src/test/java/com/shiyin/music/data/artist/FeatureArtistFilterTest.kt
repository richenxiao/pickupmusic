package com.shiyin.music.data.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 2026-10-19 定稿的「隐藏客串歌手」交互关系。
 *
 * 原话："关闭了隐藏客串歌手，那所有的也跟着关闭；打开了，所有跟着打开，用户就很清楚的知道
 * 是什么关系。然后就可以关掉特定的一些他不想隐藏的歌手。"
 *
 * 这一组用例把三条关系钉住，并**证明"界面上的开关"和"歌手栏实际过滤"用的是同一个判定**
 * （两者都走 [FeatureArtistFilter]）—— 分开写两份逻辑迟早分叉。
 */
class FeatureArtistFilterTest {

    private val all = listOf("A", "B", "C", "D")

    @Test
    fun `总开关开_名单为空_全部隐藏`() {
        // 默认就是这个状态：装好 App 什么都没拨过，客串歌手不该占位置。
        val hidden = FeatureArtistFilter.hiddenSet(all, master = true, exceptions = emptySet())
        assertEquals(all.toSet(), hidden)
        all.forEach { assertTrue("$it 默认应隐藏", FeatureArtistFilter.hidden(it, true, emptySet())) }
    }

    @Test
    fun `总开关关_不论名单里有什么_全部显示`() {
        val hidden = FeatureArtistFilter.hiddenSet(all, master = false, exceptions = setOf("A", "B"))
        assertTrue("总开关关 ⇒ 一个人都不隐藏", hidden.isEmpty())
        all.forEach { assertFalse(FeatureArtistFilter.hidden(it, false, setOf("A", "B"))) }
    }

    @Test
    fun `点名放行的那位不再隐藏_其余照旧`() {
        // 用户流程：总开关开着 ⇒ 全部打开 ⇒ "关掉特定的一些他不想隐藏的歌手"。
        // 关掉 D 的开关 = 把 D 放进放行名单。
        val exceptions = FeatureArtistFilter.toggleException(emptySet(), "D", hidden = false)
        assertEquals(setOf("D"), exceptions)
        val hidden = FeatureArtistFilter.hiddenSet(all, master = true, exceptions = exceptions)
        assertEquals(setOf("A", "B", "C"), hidden)
        assertFalse(FeatureArtistFilter.hidden("D", true, exceptions))
    }

    @Test
    fun `拨回隐藏_从名单里去掉`() {
        val exceptions = setOf("D")
        val next = FeatureArtistFilter.toggleException(exceptions, "D", hidden = true)
        assertTrue("拨回隐藏 ⇒ 名单里不该再留它", next.isEmpty())
        assertTrue(FeatureArtistFilter.hidden("D", true, next))
    }

    @Test
    fun `拨某一位只动那一位`() {
        val exceptions = setOf("A", "C")
        val next = FeatureArtistFilter.toggleException(exceptions, "B", hidden = false)
        assertEquals("只有 B 变了", setOf("A", "B", "C"), next)
    }

    @Test
    fun `开关显示与列表结果始终一致`() {
        // 「列表与开关一致」：逐位问 [hidden] 得到的集合，必须**恰好等于**整批算出来的隐藏集合。
        // 两者若分叉，用户就会看到"开关说隐藏、歌手栏里还在"这种鬼事。
        val cases = listOf(
            emptySet<String>(),
            setOf("A"),
            setOf("A", "C"),
            all.toSet(),
        )
        for (ex in cases) {
            val byBatch = FeatureArtistFilter.hiddenSet(all, master = true, exceptions = ex)
            val byEach = all.filter { FeatureArtistFilter.hidden(it, master = true, exceptions = ex) }.toSet()
            assertEquals("例外表=$ex 时逐位问与整批算必须一致", byBatch, byEach)
        }
    }

    @Test
    fun `总开关重新打开_回到全员隐藏`() {
        // setHideFeatureArtists(true) 会清空放行名单（见 MainViewModel）⇒ 效果就是这一条：
        // "打开了，所有跟着打开"（每一位的开关都回到"隐藏"）。
        val afterReopen = FeatureArtistFilter.hiddenSet(all, master = true, exceptions = emptySet())
        assertEquals(all.toSet(), afterReopen)
    }
}

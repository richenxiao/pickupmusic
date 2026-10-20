package com.shiyin.music.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新增图标**必须能被构造出来**（用户 2026-10-20 图标审计的配套防线）。
 *
 * ### 这条测试到底证明了什么
 * `Lucide` 里每个图标都是 `by lazy { stroke("name", "…path data…") }`，而 `stroke()`
 * 内部走 `addPathNodes()` **解析** SVG path data —— **畸形数据会抛异常**。
 * 又因为它是 `by lazy`，崩的时机是"**第一次打开那个页面**"，编译期看不出来，
 * 只有真机点进去才炸。本轮这几个图标是新写的 path（含手写的 arc 与圆角矩形），
 * 而我没有设备可以肉眼验收 ⇒ 至少把"**构造得出来 = path data 解析得动**"钉死。
 *
 * ### 它**不能**证明的（别把这条当渲染验收）
 * 形状是否正确、笔画粗细、视觉大小 —— 本测试一概不管。`VectorGroup.pathData` 是
 * `internal`，单测无法进一步数节点，所以这里只断言构造成功 + viewport/尺寸契约。
 * **像素级外观仍需真机截图。**
 */
class NewIconsParseTest {

    /** 逐个取用即触发 `by lazy`；path data 畸形会在这里抛出来。 */
    private fun all(): List<Pair<String, ImageVector>> = listOf(
        "UsersRound" to Lucide.UsersRound,
        "UserRoundX" to Lucide.UserRoundX,
        "Merge" to Lucide.Merge,
        "Photo" to Lucide.Photo,
    )

    @Test
    fun `歌手管理四件套的 path data 都能解析`() {
        for ((name, v) in all()) {
            assertTrue("$name 构造失败（path data 解析不了）", v.root != null)
        }
    }

    @Test
    fun `viewport 一律 24×24、默认 24dp —— 与既有 89 个图标同一套坐标系`() {
        for ((name, v) in all()) {
            assertEquals("$name viewportWidth", 24f, v.viewportWidth, 0f)
            assertEquals("$name viewportHeight", 24f, v.viewportHeight, 0f)
            assertEquals("$name defaultWidth(dp)", 24f, v.defaultWidth.value, 0f)
            assertEquals("$name defaultHeight(dp)", 24f, v.defaultHeight.value, 0f)
        }
    }
}

package com.shiyin.music.data.artist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户 2026-10-19 报的 bug：
 * **"专辑是 AAA/bbb 的专辑，我合并 bbb→BBB 就不会有任何反应，只能把专辑信息 AAA/bbb
 * 改为 AAA/BBB 才能识别出来这张专辑到 BBB。"**
 *
 * 原因就是别名表按**整串**查 —— 复合串查不到单名。
 */
class ArtistAliasResolverTest {

    @Test
    fun `复合串里的单名合并必须生效_用户报的那条`() {
        val aliases = mapOf("bbb" to "BBB")
        assertEquals("AAA, BBB", ArtistAliasResolver.resolve("AAA/bbb", aliases))
    }

    @Test
    fun `复合串里没有别名的那一段原样保留`() {
        val aliases = mapOf("bbb" to "BBB")
        assertEquals("AAA, BBB", ArtistAliasResolver.resolve("AAA / bbb", aliases))
    }

    @Test
    fun `整串合并仍然可用_兼容旧用法`() {
        val aliases = mapOf("AAA/bbb" to "BBB")
        assertEquals("BBB", ArtistAliasResolver.resolve("AAA/bbb", aliases))
    }

    @Test
    fun `两段都命中别名`() {
        val aliases = mapOf("aaa" to "AAA", "bbb" to "BBB")
        assertEquals("AAA, BBB", ArtistAliasResolver.resolve("aaa/bbb", aliases))
    }

    @Test
    fun `没有别名时只是规范化分隔符`() {
        assertEquals("张三, 李四", ArtistAliasResolver.resolve("张三、李四", emptyMap()))
    }

    @Test
    fun `单名合并照旧生效`() {
        assertEquals("BBB", ArtistAliasResolver.resolve("bbb", mapOf("bbb" to "BBB")))
    }

    @Test
    fun `别名指向复合名时目标也会被拆开`() {
        val aliases = mapOf("bbb" to "BBB/CCC")
        assertEquals("aaa, BBB, CCC", ArtistAliasResolver.resolve("aaa/bbb", aliases))
    }

    @Test
    fun `空串原样返回_不崩`() {
        assertEquals("", ArtistAliasResolver.resolve("", mapOf("a" to "b")))
    }
}

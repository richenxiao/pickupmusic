package com.shiyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * name-keyed 专辑（无 MediaStore albumId）的封面覆盖合成 id。
 *
 * 2026-10-22 用户报「更换专辑封面一点反应都没有」：这类专辑的 albumKey 形如
 * `"$album|$artist"`，解析不出真实 albumId → 旧代码传入 0 → 三个封面入口
 * 全部 `return` 静默失败。修复改用本函数派生的**负 id**（真实 albumId 恒正，
 * 永不碰撞），写入/读取两侧必须得到同一个值。
 */
class AlbumOverrideIdTest {

    @Test
    fun `aid 形式的 key 直接解析为真实 albumId`() {
        assertEquals(123L, overrideIdForAlbumKey("aid:123"))
        assertEquals(
            5294258755895964803L,
            overrideIdForAlbumKey("aid:5294258755895964803"),
        )
        assertEquals(0L, overrideIdForAlbumKey("aid:not_a_number"))
    }

    @Test
    fun `name-keyed 专辑得到稳定的负 id —— 且不同专辑不碰撞`() {
        val k1 = albumKeyOf("专辑A", "歌手A", 0)
        val k2 = albumKeyOf("专辑B", "歌手B", 0)
        val id1 = overrideIdForAlbumKey(k1)
        val id2 = overrideIdForAlbumKey(k2)
        assertTrue("必须是负值（真实 albumId 恒 > 0）：$id1", id1 < 0)
        assertTrue("必须是负值：$id2", id2 < 0)
        assertNotEquals(id1, id2)
        // 同一 key 反复调用必须等值（读写两侧靠它对上）
        assertEquals(id1, overrideIdForAlbumKey(k1))
    }

    @Test
    fun `空 key 返回 0（调用方按「无标识」处理）`() {
        assertEquals(0L, overrideIdForAlbumKey(""))
    }
}

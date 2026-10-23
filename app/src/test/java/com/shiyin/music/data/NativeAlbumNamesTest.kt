package com.shiyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [nativeAlbumNames]：迁入曲显示名跟随目标专辑原生曲目。 */
class NativeAlbumNamesTest {

    // 不用构造器：Track.uri 是 android.net.Uri，JVM 单测里 Uri.EMPTY 是 null，
    // 构造器非空校验会 NPE（同 PlaybackSkillsTest 的既有做法，走 Gson 绕过）。
    private fun track(id: Long, albumId: Long, album: String): Track =
        com.google.gson.Gson().fromJson(
            """{"id":$id,"albumId":$albumId,"album":"$album","title":"t$id","artist":"a"}""",
            Track::class.java,
        )

    @Test
    fun `无迁移时不计算`() {
        val tracks = listOf(track(1, 10, "秘密基地"))
        assertTrue(nativeAlbumNames(tracks, emptyMap()).isEmpty())
    }

    @Test
    fun `目标专辑显示名取原生曲目多数票`() {
        val tracks = listOf(
            track(1, 10, "秘密基地"),
            track(2, 10, "秘密基地"),
            track(3, 10, "秘密基地"),
            track(99, 20, "滚石精选"), // 迁入曲,原生在专辑 20
        )
        val moves = mapOf(99L to 10L)
        assertEquals("秘密基地", nativeAlbumNames(tracks, moves)[10L])
    }

    @Test
    fun `已迁出的曲目不给原专辑投票`() {
        val tracks = listOf(
            track(1, 10, "旧标签"), // 已被迁出专辑 10
            track(2, 10, "正确名"),
        )
        val moves = mapOf(1L to 20L)
        assertEquals("正确名", nativeAlbumNames(tracks, moves)[10L])
    }

    @Test
    fun `整专迁空则无该专辑条目`() {
        val tracks = listOf(track(1, 10, "A"))
        val moves = mapOf(1L to 20L)
        assertTrue(nativeAlbumNames(tracks, moves).isEmpty())
    }

    @Test
    fun `并列时先到先得`() {
        val tracks = listOf(
            track(1, 10, "先到的名"),
            track(2, 10, "后到的名"),
        )
        val moves = mapOf(99L to 10L) // 任意迁移触发计算
        assertEquals("先到的名", nativeAlbumNames(tracks, moves)[10L])
    }
}

package com.shiyin.music.data.artist

import com.shiyin.music.data.UNKNOWN_ARTIST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 A62：客串歌手判定。
 * 判据 = 「从没当过主歌手」且「从没出现在专辑作者里」。
 */
class ArtistRoleTest {

    /** 直接构造 (曲目作者, 专辑作者) 对——不依赖 Track（其 uri 需要 Android API）。 */
    private fun credits(vararg pairs: Pair<String, String>): List<Pair<String, String>> = pairs.toList()

    private fun roles(vararg pairs: Pair<String, String>) =
        ArtistRole.featureOnlyArtists(pairs.asSequence())

    @Test
    fun featuredArtistWithoutOwnWorkIsHidden() {
        // 「周杰伦/AAA」：AAA 只在合作位出现 → 客串
        assertEquals(setOf("AAA"), roles("周杰伦/AAA" to "周杰伦"))
    }

    @Test
    fun primaryArtistIsNeverFeatured() {
        // 同一人：一首当主歌手 → 不算客串（即使另一首里是第二顺位）
        assertTrue(roles("AAA" to "AAA", "周杰伦/AAA" to "周杰伦").isEmpty())
    }

    @Test
    fun albumArtistProtectsCollaboratorOnWholeAlbum() {
        // 整张专辑都是「A & B」：B 从没当过第一顺位，但是专辑作者之一 → 不该被藏
        assertTrue(roles("A & B" to "A & B").isEmpty())
    }

    @Test
    fun albumArtistSignalHidesSingleTrackFeatureOnAlbum() {
        // 专辑作者是「周杰伦」，其中一首 feat AAA → AAA 客串
        assertEquals(setOf("AAA"), roles("周杰伦" to "周杰伦", "周杰伦/AAA" to "周杰伦"))
    }

    @Test
    fun unknownArtistIsNotFeatured() {
        assertTrue(roles(UNKNOWN_ARTIST to "").isEmpty())
    }

    @Test
    fun variousSeparatorsOnlyFirstCountsAsPrimary() {
        // feat. / 、/ × 都算合作位；只有第一个名字是主歌手
        assertEquals(setOf("A", "B", "C"), roles("主唱 feat. A、B × C" to "主唱"))
    }

    @Test
    fun emptyAlbumArtistFallsBackToPrimaryRule() {
        // 文件没写 ALBUM_ARTIST（空串）→ 退回"只看主歌手"的判据
        assertEquals(setOf("乙"), roles("甲/乙" to ""))
    }

    @Test
    fun unrelatedArtistsAreNotMarked() {
        assertEquals(setOf("丙"), roles("甲" to "甲", "乙/丙" to "乙"))
    }
}

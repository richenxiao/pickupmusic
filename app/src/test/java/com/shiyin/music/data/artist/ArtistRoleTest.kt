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
        // 整张专辑都是「A & B」：B 从没当过第一顺位，但是专辑作者之一 → 不该被藏。
        // ⚠️ 必须给**多首**才算"整张专辑" —— 判据②2026-10-18 起有这道闸门（见下一条用例）。
        assertTrue(roles("A & B" to "A & B", "A & B" to "A & B").isEmpty())
    }

    /**
     * **2026-10-18 修的回归**：单曲的 `ALBUM_ARTIST` 常常就是把曲目作者原样抄一遍（含 feat），
     * 旧判据②会据此把 feat 歌手"救回" ⇒ 歌手栏冒出大量只 feat 一两首的歌手（用户实测）。
     * 现在②只在"同一组合 ≥2 首"时生效，所以单曲里的 feat 照样藏得掉。
     */
    @Test
    fun singleTrackAlbumArtistCopyDoesNotRescueFeature() {
        assertEquals(
            "单曲的专辑作者只是把曲目作者抄了一遍，不该因此把 AAA 放回歌手栏",
            setOf("AAA"),
            roles("周杰伦/AAA" to "周杰伦/AAA"),
        )
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

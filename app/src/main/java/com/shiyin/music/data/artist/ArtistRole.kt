package com.shiyin.music.data.artist

import com.shiyin.music.data.MediaScanner
import com.shiyin.music.data.Track
import com.shiyin.music.data.UNKNOWN_ARTIST

/**
 * v1.5.0 A62：判断「客串歌手」——只参与合作、没有自己作品的歌手。
 *
 * 需求（用户 2026-10-11）：这类歌手不该在**歌手栏**占一个位置（点进去看、搜索都照常），
 * 一旦有了自己的作品就自动按正常歌手显示。
 *
 * 判据（两条任一成立即算"有自己的作品"，都不是才是客串）：
 *  ① 担任过某首曲目的**主歌手**——曲目 artist 串经 [MediaScanner.splitArtists] 拆分后
 *    的**第一个名字**（如「周杰伦/AAA」→ 周杰伦是主，AAA 是合作）；
 *  ② 出现在某张相关专辑的**专辑作者**里（MediaStore `ALBUM_ARTIST`）——用户指出的信号：
 *    "只 feat 一首单曲的歌手，专辑作者字段里不会有他的名字"。
 *    这条同时救回一种误判：整张专辑都是「A & B」的双人合作里，B 从没当过第一顺位，
 *    但他是专辑作者之一，不该被当成客串藏起来。
 *
 * 注意：本判定**只决定"要不要在歌手栏占位"**，不改写、不合并任何作者信息——
 * 曲目里的作者明细原样保留（用户明确要求：feat 歌手不能被吃掉）。
 */
object ArtistRole {

    /** 返回「只以合作身份出现」的歌手名集合（客串歌手）。 */
    fun featureOnlyArtists(tracks: List<Track>): Set<String> =
        featureOnlyArtists(tracks.asSequence().map { it.artist to it.albumArtist })

    /**
     * 核心判定。入参是 (曲目作者串, 专辑作者串) 序列——不依赖 Track/Android API，
     * 便于纯 JVM 单测（Track 里带 android.net.Uri，单测环境是桩）。
     */
    fun featureOnlyArtists(credits: Sequence<Pair<String, String>>): Set<String> {
        val credited = LinkedHashSet<String>()
        val hasOwnWork = HashSet<String>()
        for ((artist, albumArtist) in credits) {
            val names = MediaScanner.splitArtists(artist)
            credited += names
            names.firstOrNull()?.let { hasOwnWork += it }
            if (albumArtist.isNotBlank()) {
                hasOwnWork += MediaScanner.splitArtists(albumArtist)
            }
        }
        credited -= UNKNOWN_ARTIST
        hasOwnWork -= UNKNOWN_ARTIST
        return credited - hasOwnWork
    }
}

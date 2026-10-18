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
 *    ⚠️ **2026-10-18 给②加闸门**：只有该「曲目作者/专辑作者」组合出现在 **≥2 首**曲目上时，
 *    ②才生效。否则单曲的 `ALBUM_ARTIST`（常常就是把曲目作者原样抄一遍）会把 feat 歌手
 *    全部"救回"，①自废武功 —— 用户实测"歌手栏冒出大量只 feat 一两首的歌手"就是这个。
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
        val list = credits.toList()
        // 同一个「曲目作者 / 专辑作者」组合出现几次 —— 用来区分
        // **"整张专辑的双人合作"** 与 **"单曲 feat"**（前者才是判据②要救的对象）。
        val pairCount = list.groupingBy { it }.eachCount()

        val credited = LinkedHashSet<String>()
        val hasOwnWork = HashSet<String>()
        for ((artist, albumArtist) in list) {
            val names = MediaScanner.splitArtists(artist)
            credited += names
            names.firstOrNull()?.let { hasOwnWork += it }

            val albumNames = if (albumArtist.isBlank()) emptyList() else MediaScanner.splitArtists(albumArtist)
            // ⚠️ **2026-10-18 修**：判据②只在**该组合确实多首**（≥2）时才生效。
            //
            // 事故：用户在歌手栏看到大量"只 feat 一两首"的歌手没被藏掉。
            // 原因是②在**单曲**上自废武功 —— 单曲的 `ALBUM_ARTIST` 往往就是把曲目作者原样
            // 抄一遍（含 feat），于是 feat 歌手立刻被"救回"，判据①白做。
            // 而②本来要救的是「**整张专辑**都是 A & B 的双人合作、B 从没当过第一顺位」——
            // 那种情况一定是**多首**。所以用"同一作者组合的曲目数 ≥2"当闸门，两边都对。
            if (albumNames.isNotEmpty() && (pairCount[artist to albumArtist] ?: 0) >= 2) {
                hasOwnWork += albumNames
            }
        }
        credited -= UNKNOWN_ARTIST
        hasOwnWork -= UNKNOWN_ARTIST
        return credited - hasOwnWork
    }
}

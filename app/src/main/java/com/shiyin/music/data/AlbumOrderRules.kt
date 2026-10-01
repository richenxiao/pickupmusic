package com.shiyin.music.data

/**
 * v1.3.15:歌手专辑栏(artist_album_order)的顺序规则。
 *
 * 排序优先级是**手动顺序 > 发行日期 > 专辑名**(见 ArtistAlbumList),而"AI 按时间排序"
 * 会把结果同时写进日期与手动顺序。于是手工修正某张专辑的年份时,那份旧顺序仍然压着日期
 * ——真机反馈「把 2022 改成 1999 后它还排在最前」的直接原因。
 */
internal object AlbumOrderRules {

    /**
     * 日期变动后,把 [artists] 的手动顺序作废(回落到按日期排),其余歌手原样保留。
     *
     * 为什么整体作废而不是"把这张挪到正确位置":顺序里位置本身就是依据旧日期定下的,
     * 只挪一张会留下别的错位;而"按日期排"正是顺序缺失时的既有行为(用户要的就是这个)。
     */
    fun invalidate(orders: Map<String, List<String>>, artists: List<String>): Map<String, List<String>> =
        if (artists.isEmpty()) orders else orders - artists.toSet()

    /** 日期是否**真的**变了。(没变就不作废 —— 否则用户只是重存同一个日期也会丢掉拖拽顺序。) */
    fun dateChanged(oldIso: String?, newIso: String): Boolean = oldIso.orEmpty() != newIso
}

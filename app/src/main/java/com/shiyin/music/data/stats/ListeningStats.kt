package com.shiyin.music.data.stats

import com.shiyin.music.data.Track
import com.shiyin.music.data.albumKeyOf
import com.shiyin.music.data.db.PlayEventEntity
import com.shiyin.music.data.split.PlayKind

/**
 * v1.3.8: 收听统计的**计算口径**从 ListeningStatsScreen 的 Composable 里抽出来,
 * 成为统计页与 Agent 共用的唯一实现。
 *
 * 为什么必须共用而不是各写一份:这套口径是多轮打磨出来的,规则彼此牵连——
 * 30 秒规则、总时长用 playedSec 而非曲目总长、专辑按循环次数折算、排除 Single、
 * 单曲榜排除 interlude 但 interlude 仍参与专辑与艺人榜、多歌手串要再拆回单名。
 * 两处实现一旦分裂,会出现"统计页说 10 次、Agent 说 12 次"这类**用户不会主动
 * 对比、但会先怀疑 App 有 bug** 的不一致,排查成本远高于做这次抽取。
 *
 * 抽取是**逐行搬运**,不改变任何计算结果——characterization 测试以原实现为
 * oracle 覆盖各口径分支(见 ListeningStatsTest)。
 */
data class WeeklyStats(
    val totalSeconds: Long = 0,
    /** albumKey -> 循环次数(已按曲目数折算) */
    val topAlbums: List<Pair<String, Int>> = emptyList(),
    /** 歌手名 -> 次数 */
    val topArtists: List<Pair<String, Int>> = emptyList(),
    /** mediaId -> 次数 */
    val topTracks: List<Pair<Long, Int>> = emptyList(),
)

/**
 * 本周起点(周一 00:00)。
 *
 * ⚠️ 保持与旧实现**逐字一致**:`Calendar.getInstance()` 之后只 set DAY_OF_WEEK=MONDAY。
 * 注意这在中文 locale 下(每周首日为周日)对"今天是周日"会落到**前一天那个周一**——
 * 这是既有行为,不要顺手"修正",否则统计页与 Agent 的数字会跟用户历史看到的不一致。
 */
fun currentWeekStartMillis(now: Long = System.currentTimeMillis()): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    cal.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * 本周收听统计。
 *
 * @param events  本周**有效播放**(completed=1)事件——跳过/误触(completed=0)不计入。
 * @param lib     音乐库(lib())。
 * @param trackById 按 mediaId 取曲目。必须包含「完整专辑」容器曲目(它们只在 libById
 *        里,不在 lib()),否则专辑播放会被误算成"未知"艺人。
 * @param isInterlude 是否过渡曲(≤60s 短曲)。
 * @param albumTypeFor 按 albumId 取专辑类型("Single"/"Album"/…)。
 */
fun computeWeeklyStats(
    events: List<PlayEventEntity>,
    lib: List<Track>,
    trackById: (Long) -> Track?,
    isInterlude: (Track) -> Boolean,
    albumTypeFor: (Long) -> String?,
    topN: Int = 5,
): WeeklyStats {
    if (events.isEmpty()) return WeeklyStats()

    val totalSec = events.sumOf { it.playedSec.toLong() }
    // v5.2 #52: 专辑按"循环次数"计——累计原始完成播放数,最后 ÷ 该专辑曲目数(整除
    // 向下取整)。听完一整张 10 曲专辑 = 10 次播放 ÷ 10 = 1 次(而非 10 次)。
    // v5.2 #64: 排除单曲(Single)——只有1首且标注单曲的合集不算专辑。
    val albumTrackCounts = lib
        .groupBy { albumKeyOf(it.album, it.artist, it.albumId) }
        .mapValues { it.value.size }
    // 预算每个 albumKey 是否为单曲:取该 album 任一 track 的 albumId 查 type
    val singleKeys = lib
        .groupBy { albumKeyOf(it.album, it.artist, it.albumId) }
        .mapValues { (_, ts) -> ts.firstOrNull()?.albumId }
        .filterValues { id -> id != null && albumTypeFor(id) == "Single" }
        .keys
    val albumPlayMap = mutableMapOf<String, Int>()
    val artistMap = mutableMapOf<String, Int>()
    val trackMap = mutableMapOf<Long, Int>()
    for (ev in events) {
        // v22: 必须用 trackById 而不是 lib() 映射 —— 「完整专辑」容器曲目不在 lib() 里。
        val t = trackById(ev.mediaId)
        val isAlbumPlay = ev.kind == PlayKind.ALBUM
        if (t != null) {
            val key = albumKeyOf(t.album, t.artist, t.albumId)
            if (key !in singleKeys) {   // 跳过单曲
                // v22: 「完整专辑」一次播放 = 一个专辑循环。折进同一个公式:
                // 补上 trackCount 次,÷ trackCount 后正好贡献 1 个循环。
                val weight = if (isAlbumPlay) (albumTrackCounts[key] ?: 1) else 1
                albumPlayMap[key] = (albumPlayMap[key] ?: 0) + weight
            }
        }
        // v1.3.1: 艺人聚合口径与 artistsMap()/歌手页一致——先拆分多歌手串,否则 key 是
        // "周杰伦, 费玉清" 整串,写真表里只有单名,头像永远落空。
        // v22: 完整专辑播放也算这位歌手的一次收听,否则总时长里有播放、艺人榜里看不到。
        for (n in (t?.let { com.shiyin.music.data.MediaScanner.splitArtists(it.artist) } ?: listOf("未知"))) {
            artistMap[n] = (artistMap[n] ?: 0) + 1
        }
        // v22: 「最常听单曲」榜 = 只收单曲播放,且排除过渡曲。过渡曲**要计数**,但不算「歌」,
        // 不该挤掉真歌。注意它仍参与上面的专辑循环数与艺人榜。
        if (!isAlbumPlay && (t == null || !isInterlude(t))) {
            trackMap[ev.mediaId] = (trackMap[ev.mediaId] ?: 0) + 1
        }
    }
    val albumCycles = albumPlayMap.mapValues { (key, plays) ->
        plays / (albumTrackCounts[key] ?: 1)
    }
    return WeeklyStats(
        totalSeconds = totalSec,
        topAlbums = albumCycles.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(topN).map { it.key to it.value },
        topArtists = artistMap.entries.sortedByDescending { it.value }.take(topN).map { it.key to it.value },
        topTracks = trackMap.entries.sortedByDescending { it.value }.take(topN).map { it.key to it.value },
    )
}

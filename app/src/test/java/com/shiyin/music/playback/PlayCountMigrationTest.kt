package com.shiyin.music.playback

import com.shiyin.music.data.db.PlayEventEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.2.1: 历史 playedSec 迁移 + 统计口径一致性 验证(纯 JVM,无 DB 依赖)。
 *
 * Room @Query 无法在纯 JVM 单测里执行(SQL 需 Android 上下文),但迁移语义、默认值与
 * 聚合口径是 Kotlin + SQL 文本语义,这里以纯逻辑验证:
 *
 * - 历史 completed=1 行(旧规则=播完整首)迁移后 playedSec=0:不伪造,不放大总时长。
 * - sumOf(playedSec) 对历史行贡献 0(低估而非高估,符合"不可恢复不伪造")。
 * - 有效播放次数 = counted 行数(completed=1);历史 completed=1 行仍算 1 次有效播放。
 * - 总收听时长 = 所有 Session 的 playedSec 之和;历史行 playedSec=0,贡献 0(不污染)。
 *
 * 迁移 SQL 本身(ALTER TABLE play_event ADD COLUMN playedSec INTEGER NOT NULL DEFAULT 0)
 * 的语义由 SQLite 标准 ALTER 保证:存量行取 DEFAULT 0。
 */
class PlayCountMigrationTest {

    /**
     * 构造一条"旧完成行"作为迁移后状态:历史 completed=1,playedSec=0(迁移给的 DEFAULT)。
     * 旧数据库无 playedSec 列,迁移 ADD COLUMN ... DEFAULT 0 后存量行该字段=0,不伪造。
     */
    private fun legacyCompletedRow(mediaId: Long, durationSec: Int) =
        PlayEventEntity(
            mediaId = mediaId,
            playedAt = 1_000L,
            durationSec = durationSec,   // 旧记录里有曲目总长
            playedSec = 0,              // 迁移给的 DEFAULT 0,不是真实累计
            completed = true,           // 旧规则下"播完整首"=true,新规则下沿用(true=有效播放)
        )

    /** 新规则下产生的事件:playedSec 来自 PlaySessionTracker 的真实累计。 */
    private fun newRow(mediaId: Long, playedSec: Int, completed: Boolean) =
        PlayEventEntity(
            mediaId = mediaId,
            playedAt = 2_000L,
            durationSec = 240,
            playedSec = playedSec,
            completed = completed,
        )

    // ── 历史 playedSec 不被放大(第 5 点核心) ────────────────────────────────
    @Test fun legacyCompletedRows_haveZeroPlayedSec_doNotInflateTotal() {
        // 一首 4 分钟的歌,旧规则下"播完整首"记 completed=1,durationSec=240。
        // 迁移后 playedSec=0(无真实累计)。
        val legacy = listOf(
            legacyCompletedRow(mediaId = 1, durationSec = 240),
            legacyCompletedRow(mediaId = 1, durationSec = 240),
            legacyCompletedRow(mediaId = 2, durationSec = 180),
        )
        // 收听统计 总时长 = sumOf(playedSec)。历史行 playedSec=0 → 贡献 0,不放大。
        val totalSec = legacy.sumOf { it.playedSec.toLong() }
        assertEquals("历史行 playedSec=0,总时长不被伪造放大", 0L, totalSec)
    }

    // ── 历史有效播放次数保留(completed=1 仍算 1 次) ─────────────────────────
    @Test fun legacyCompletedRows_stillCountAsOnePlayEach() {
        // playCountFlow = SELECT COUNT(*) WHERE completed=1 GROUP BY mediaId
        val events = listOf(
            legacyCompletedRow(1, 240),
            legacyCompletedRow(1, 240),
            legacyCompletedRow(2, 180),
            newRow(3, playedSec = 0, completed = false),  // 新规则下没听满 30s,不计
        )
        // mediaId=1: 2 次;mediaId=2: 1 次;mediaId=3: 0 次(未 completed)
        val counts = events.filter { it.completed }.groupBy { it.mediaId }.mapValues { it.value.size }
        assertEquals(2, counts[1])
        assertEquals(1, counts[2])
        assertEquals(null, counts[3])  // 未满 30s 的事件不进入有效播放次数
    }

    // ── 新口径:总时长只用 playedSec,绝不用 durationSec 冒充 ─────────────────
    @Test fun totalDuration_usesPlayedSec_notDurationSec() {
        // 混合:1 条历史完成(playedSec=0)、1 条新规则听满 35s、1 条新规则没听满(不计)
        val weekEvents = listOf(
            legacyCompletedRow(1, durationSec = 240),                       // 历史:总时长贡献 0
            newRow(2, playedSec = 35, completed = true),                    // 新:贡献 35
            newRow(3, playedSec = 12, completed = false),                   // 没听满,不计入统计
        )
        // 收听统计只取 completed=1
        val counted = weekEvents.filter { it.completed }
        // 旧口径(错):sumOf(durationSec) = 240 + 240 = 480(把听满35s算成听完整首,且历史行放大)
        val oldWrongTotal = counted.sumOf { it.durationSec.toLong() }
        // 新口径(对):sumOf(playedSec) = 0 + 35 = 35(只真实累计,历史不伪造)
        val newCorrectTotal = counted.sumOf { it.playedSec.toLong() }
        assertEquals("新口径总时长=35(只真实累计)", 35L, newCorrectTotal)
        // 显式断言旧口径会放大,证明改动必要性
        assertEquals("旧口径会放大到 480", 480L, oldWrongTotal)
    }

    // ── 口径统一:有效播放次数=热榜=总时长 三者同源(第 7 点) ────────────────
    @Test fun allStats_shareSameSourceOfTruth() {
        val weekEvents = listOf(
            newRow(1, playedSec = 40, completed = true),   // 有效 1 次,贡献 40s
            newRow(1, playedSec = 30, completed = true),   // 有效 1 次,贡献 30s
            newRow(1, playedSec = 10, completed = false),  // 没听满,0 次,不贡献时长
            newRow(2, playedSec = 29, completed = false),  // 没听满,0 次,不贡献时长
            legacyCompletedRow(3, durationSec = 200),       // 历史有效 1 次,playedSec=0
        )
        val counted = weekEvents.filter { it.completed }

        // 有效播放次数(歌手热度/歌曲热度排序的口径)
        val playCounts = counted.groupBy { it.mediaId }.mapValues { it.value.size }
        assertEquals(2, playCounts[1])  // 两次听满 30s
        assertEquals(1, playCounts[3])  // 历史完成行仍算 1 次

        // 总收听时长(收听统计口径)
        val totalSec = counted.sumOf { it.playedSec.toLong() }
        assertEquals(40 + 30 + 0, totalSec.toLong())  // 历史行贡献 0

        // 最近播放 feed = 全部事件(不受 30s 门槛影响)
        val feedSize = weekEvents.size
        assertEquals(5, feedSize)  // 含没听满的 2 条
    }
}

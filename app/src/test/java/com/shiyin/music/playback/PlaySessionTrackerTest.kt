package com.shiyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.1: PlaySessionTracker 纯逻辑单测 —— Spotify 式 30 秒有效播放计数。
 *
 * 覆盖用户要求的全场景:
 * - 20s + 暂停 + 10s = 1 次;暂停期间 playedSec 不增长
 * - 30s 连续播放 = 1 次
 * - 29.9s 不计
 * - seek 不增加 playedSec(0:00 播 5s → seek 1:30 → 播 30s;向后 seek;≤2s 小跳;>2s 大跳;连续 seek)
 * - 切歌后重播原歌是新 Session(可重新累计)
 * - 单曲循环每轮独立(第二轮可再计一次)
 * - 满 30s 后继续播不重复计数
 *
 * 纯 JVM,无 Android/media3 依赖:tracker 只接收 (pos, isPlaying, stateReady) 采样。
 * 每拍假设 300ms,1 拍前进 300ms 位置 ≈ 1x 实时播放(简化:位置增量=时间增量)。
 */
class PlaySessionTrackerTest {

    private val T = PlaySessionTracker.COUNT_THRESHOLD_MS          // 30000
    private val MAX = PlaySessionTracker.MAX_SAMPLE_DELTA_MS        // 2000
    private val TICK = 300L                                         // 每拍 300ms

    /** 记录 onCounted 触发次数与对应 mediaId。回调=(rowId, mediaId),测试只关心 mediaId。 */
    private class CountRecorder {
        val counted = mutableListOf<Long>()
        var onCounted: ((Long, Long) -> Unit)? = { _, mediaId -> counted.add(mediaId) }
    }
    /** 记录 onFinalize 的 (mediaId, playedSec) 序列。回调=(rowId, mediaId, playedSec),测试只关心后两者。 */
    private class FinRecorder {
        val finalized = mutableListOf<Pair<Long, Int>>()
        var onFinalize: ((Long, Long, Int) -> Unit)? = { _, mediaId, sec -> finalized.add(mediaId to sec) }
    }

    private fun newTracker(c: CountRecorder, f: FinRecorder) = PlaySessionTracker().apply {
        onCounted = c.onCounted
        onFinalize = f.onFinalize
    }

    // ── 1. 暂停/恢复:20s + 暂停 + 10s 累计 30s 只算 1 次 ─────────────────────
    @Test fun pauseResume_accumulatesAcrossPause_countsOnce() {
        val c = CountRecorder(); val f = FinRecorder(); val t = newTracker(c, f)
        t.start(100L)
        // 播 20s = 20000ms,1x 速度下每拍 +300ms
        playMs(t, 20_000, isPlaying = true, stateReady = true, perTick = TICK)
        // 暂停期间:位置不动,模拟暂停 5s(对 tracker 不可见,只表现为持续 pos 不变)
        repeat(20) { t.sample(pos = 20_000, isPlaying = false, stateReady = false) }
        assertEquals("暂停期间不计", 0, c.counted.size)
        assertEquals("暂停前累计 20s(未满30s)", 20, (t.currentAccumMs() / 1000).toInt())
        // 继续播 10s → 累计 30s
        playMs(t, 10_000, isPlaying = true, stateReady = true, perTick = TICK, startFrom = 20_000)
        assertEquals("累计满 30s 计 1 次", 1, c.counted.size)
        assertEquals(100L, c.counted[0])
        // finalize 回填 playedSec 应为 30
        val res = t.finalize()!!
        assertEquals(100L to 30, res)
        assertEquals(1, f.finalized.size)
    }

    // ── 2. 30s 连续播放计 1 次 ───────────────────────────────────────────────
    @Test fun continuous30s_countsOnce() {
        val c = CountRecorder(); val f = FinRecorder(); val t = newTracker(c, f)
        t.start(1L)
        playMs(t, 30_000, isPlaying = true, stateReady = true, perTick = TICK)
        assertEquals(1, c.counted.size)
        assertTrue(t.currentCounted())
        val res = t.finalize()!!
        assertEquals(1L to 30, res)
    }

    // ── 3. 29.9s 不计 ───────────────────────────────────────────────────────
    @Test fun justUnderThreshold_notCounted() {
        val c = CountRecorder(); val t = newTracker(c, FinRecorder())
        t.start(1L)
        playMs(t, 29_900, isPlaying = true, stateReady = true, perTick = TICK)
        assertEquals("29.9s 未满阈值不计", 0, c.counted.size)
        assertFalse(t.currentCounted())
        // playedSec 累计 29(向下取整 29900/1000)
        val res = t.finalize()!!
        assertEquals(1L to 29, res)
    }

    // ── 4a. seek 不增加 playedSec:0:00 播 5s → seek 1:30 → 播 30s ────────────
    @Test fun seekForward_doesNotAddPlayTime() {
        val c = CountRecorder(); val f = FinRecorder(); val t = newTracker(c, f)
        t.start(1L)
        // 播 5s(真实)
        playMs(t, 5_000, isPlaying = true, stateReady = true, perTick = TICK)
        // seek 1:30 = 90000ms(位置从 5000 突跳到 90000,delta=85000>MAX,不累加)
        t.sample(pos = 90_000, isPlaying = true, stateReady = true)
        assertEquals("seek 大跳后 playedSec 仍只来自 5s 真实播放", 5, (t.currentAccumMs() / 1000).toInt())
        // 从 90000 继续播 30s(真实,累计应 35000ms)
        playMs(t, 30_000, isPlaying = true, stateReady = true, perTick = TICK, startFrom = 90_000)
        // 累计真实播放 5+30=35s ≥30,计 1 次
        assertEquals("真实累计 35s 计 1 次(seek 距离不计)", 1, c.counted.size)
        val res = t.finalize()!!
        assertEquals(1L to 35, res)
    }

    // ── 4b. 向后 seek 不计入 ────────────────────────────────────────────────
    @Test fun seekBackward_doesNotSubtractNorAdd() {
        val c = CountRecorder(); val t = newTracker(c, FinRecorder())
        t.start(1L)
        playMs(t, 40_000, isPlaying = true, stateReady = true, perTick = TICK)
        // 已计 1 次(40s)
        assertEquals(1, c.counted.size)
        val beforeSeek = t.currentAccumMs()
        // 向后 seek 到 10s(位置从 40000 倒退到 10000,delta=-30000,不累加)
        t.sample(pos = 10_000, isPlaying = true, stateReady = true)
        assertEquals("向后 seek 不改变累计", beforeSeek, t.currentAccumMs())
        // 从 10000 继续播 5s 真实(累计应 +5000)
        playMs(t, 5_000, isPlaying = true, stateReady = true, perTick = TICK, startFrom = 10_000)
        assertEquals("向后 seek 后真实播放照常累计", beforeSeek + 5_000, t.currentAccumMs())
        // 仍只计 1 次(同 session 不重复)
        assertEquals(1, c.counted.size)
    }

    // ── 4c. ≤2s 小跳被当成正常播放累加(临界:刚好 2000ms) ───────────────────
    @Test fun smallJumpWithin2s_isCountedAsNormal() {
        val c = CountRecorder(); val t = newTracker(c, FinRecorder())
        t.start(1L)
        // 正常每拍 300ms 前进;模拟某拍多走了一点但 ≤2000ms(如 1500ms)
        playMs(t, 3_000, isPlaying = true, stateReady = true, perTick = TICK)
        t.sample(pos = 3_000 + 1_500, isPlaying = true, stateReady = true)
        // 1500ms ≤ MAX,被当成正常播放累加
        assertEquals(3_000 + 1_500, t.currentAccumMs())
        // 刚好 2000ms(临界含)也累加
        t.sample(pos = 3_000 + 1_500 + 2_000, isPlaying = true, stateReady = true)
        assertEquals(3_000 + 1_500 + 2_000, t.currentAccumMs())
        // 2001ms(超限)不累加
        val before = t.currentAccumMs()
        t.sample(pos = t.currentAccumMs() + 9_500 + 2_001, isPlaying = true, stateReady = true)
        // 注意:这里 pos 直接累加 2001,应被剔除
        assertEquals("2001ms 超限不累加", before, t.currentAccumMs())
    }

    // ── 4d. 播放过程中连续 seek 不增加 playedSec ────────────────────────────
    @Test fun consecutiveSeeks_doNotAccumulate() {
        val c = CountRecorder(); val t = newTracker(c, FinRecorder())
        t.start(1L)
        playMs(t, 5_000, isPlaying = true, stateReady = true, perTick = TICK)
        // 连续 3 次大跳 seek,每次 +30000
        var pos = 5_000L
        repeat(3) {
            pos += 30_000
            t.sample(pos = pos, isPlaying = true, stateReady = true)
        }
        assertEquals("连续 seek 不增 playedSec,仍只 5s", 5, (t.currentAccumMs() / 1000).toInt())
        assertFalse(t.currentCounted())
    }

    // ── 5. 切歌后重播原歌是新 Session,可重新累计 ───────────────────────────
    @Test fun replayAfterSwitch_isNewSession_canRecount() {
        val c = CountRecorder(); val f = FinRecorder(); val t = newTracker(c, f)
        t.start(1L)
        playMs(t, 30_000, isPlaying = true, stateReady = true, perTick = TICK)
        assertEquals(1, c.counted.size)
        // 切到 B(不同 mediaId)→ 上一轮 finalize
        val sid1 = f.finalized.lastOrNull()
        t.start(2L)
        playMs(t, 5_000, isPlaying = true, stateReady = true, perTick = TICK)
        // 再切回 A(原歌)= 新 session,从 0 重新累计
        t.start(1L)
        assertEquals("切回 A 触发 B 的 finalize", 2L, f.finalized.last().first)
        playMs(t, 30_000, isPlaying = true, stateReady = true, perTick = TICK)
        assertEquals("原歌新 session 重新满 30s 再计 1 次", 2, c.counted.size)
        assertEquals(1L, c.counted[1])
        t.finalize()
    }

    // ── 6. 单曲循环每轮独立:第二轮可再计一次 ───────────────────────────────
    @Test fun repeatLoop_eachRoundIndependent() {
        val c = CountRecorder(); val f = FinRecorder(); val t = newTracker(c, f)
        // 第一轮:同 mediaId=7,播满 30s
        t.start(7L)
        playMs(t, 35_000, isPlaying = true, stateReady = true, perTick = TICK)
        assertEquals("第一轮满 30s 计 1 次", 1, c.counted.size)
        // 单曲循环 AUTO 推进到下一轮:同 mediaId 但 start 会 finalize 上一轮 + 新 session
        val r1 = t.start(7L)
        assertEquals("上一轮被 finalize", 7L to 35, f.finalized.last())
        // counted 重置
        assertFalse("新 session counted 重置", t.currentCounted())
        // 第二轮再播满 30s
        playMs(t, 30_000, isPlaying = true, stateReady = true, perTick = TICK)
        assertEquals("第二轮可再计 1 次(不被同 mediaId 锁死)", 2, c.counted.size)
        t.finalize()
    }

    // ── 7. 满 30s 后继续播放不重复计数 ─────────────────────────────────────
    @Test fun afterCounted_noDuplicate() {
        val c = CountRecorder(); val t = newTracker(c, FinRecorder())
        t.start(1L)
        playMs(t, 30_000, isPlaying = true, stateReady = true, perTick = TICK)
        assertEquals(1, c.counted.size)
        // 继续播 60s
        playMs(t, 60_000, isPlaying = true, stateReady = true, perTick = TICK, startFrom = 30_000)
        assertEquals("满阈值后继续播不重复计数", 1, c.counted.size)
        val res = t.finalize()!!
        assertEquals("playedSec 记录全部真实累计 90s", 1L to 90, res)
    }

    // ── 8. 后台暂停:暂停期间 playedSec 不增长 ──────────────────────────────
    @Test fun backgroundPause_doesNotGrow() {
        val c = CountRecorder(); val t = newTracker(c, FinRecorder())
        t.start(1L)
        playMs(t, 10_000, isPlaying = true, stateReady = true, perTick = TICK)
        val before = t.currentAccumMs()
        // 后台暂停:isPlaying=false,stateReady=false,位置冻结;持续采样
        repeat(50) { t.sample(pos = 10_000, isPlaying = false, stateReady = false) }
        assertEquals("后台暂停期间 playedSec 不增长", before, t.currentAccumMs())
        // 恢复后继续累计
        playMs(t, 20_000, isPlaying = true, stateReady = true, perTick = TICK, startFrom = 10_000)
        assertEquals("恢复后累计达 30s 计 1 次", 1, c.counted.size)
        assertEquals("playedSec = 10+20 = 30", 30, (t.currentAccumMs() / 1000).toInt())
    }

    // ── 9. 未 start 时 sample / finalize 为 no-op ──────────────────────────
    @Test fun noActiveSession_sampleAndFinalizeAreNoOp() {
        val c = CountRecorder(); val f = FinRecorder(); val t = newTracker(c, f)
        assertFalse(t.sample(pos = 5_000, isPlaying = true, stateReady = true))
        assertNull(t.finalize())
        assertEquals(0, c.counted.size)
        assertEquals(0, f.finalized.size)
    }

    // ── 10. start 依次 finalize 上一首:切歌链 ─────────────────────────────
    @Test fun chainingTracks_finalizesEach() {
        val c = CountRecorder(); val f = FinRecorder(); val t = newTracker(c, f)
        t.start(1L); playMs(t, 10_000, isPlaying = true, stateReady = true, perTick = TICK)
        t.start(2L); playMs(t, 12_000, isPlaying = true, stateReady = true, perTick = TICK)
        t.start(3L); playMs(t, 8_000, isPlaying = true, stateReady = true, perTick = TICK)
        t.finalize()
        // 三首依次 finalize,playedSec 各为 10/12/8
        assertEquals(listOf(1L to 10, 2L to 12, 3L to 8), f.finalized)
        // 都没满 30s
        assertEquals(0, c.counted.size)
    }

    /**
     * 模拟 [durationMs] 的真实播放:每拍 [perTick]ms,位置前进 perTick,采样累计。
     * @param startFrom 起始位置(用于恢复/seek 后继续的真实播放)
     */
    private fun playMs(
        t: PlaySessionTracker,
        durationMs: Long,
        isPlaying: Boolean,
        stateReady: Boolean,
        perTick: Long = TICK,
        startFrom: Long = 0L,
    ) {
        var pos = startFrom
        var elapsed = 0L
        while (elapsed < durationMs) {
            val step = minOf(perTick, durationMs - elapsed)
            pos += step
            elapsed += step
            t.sample(pos = pos, isPlaying = isPlaying, stateReady = stateReady)
        }
    }
}

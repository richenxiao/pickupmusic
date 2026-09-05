package com.shiyin.music.playback

/**
 * v1.2.1: 纯逻辑的"有效播放"计数追踪器 —— Spotify 式 30 秒阈值。
 *
 * 设计目标:把"累计真实播放满 30 秒才算一次有效播放"这段逻辑从 PlayerController
 * (强依赖 androidx.media3 Player / Android 框架)里抽出来,使其可以用纯 JVM 单测
 * 全场景覆盖。PlayerController 每拍只负责喂数据进来,不做计数判断。
 *
 * === Session 生命周期(显式,不靠隐含状态推断) ===
 * 一个 Session = 一次连续的"对某首 mediaId 的播放尝试"。Session 在 [start] 时建立、
 * 在下一次 [start](切歌/单曲循环新一轮/重播)或 [finalize] 时结束并回填 playedSec。
 *
 * - 播 20s → 暂停 → 再播 10s:同一 Session,累计 30s → 计 1 次。✓
 * - 播 20s → 切到 B → 再播 A 20s:A 的两次是不同 Session,第二次从 0 重新累计,可再计。✓
 * - 单曲循环(AUTO 推进到下一轮,同 mediaId):新 Session,上一轮 finalized,
 *   第二轮重新累计,满 30s 可再计 1 次。✓
 * - 同一 Session 满 30s 后继续播:[counted] 置 true 后不再重复触发,只计 1 次。✓
 *
 * === Seek 处理 ===
 * [sample] 只累加"播放器实际自然播放经过的时间"。每拍取位置增量 delta:
 * - delta in 1..MAX_SAMPLE_DELTA_MS(正常前进,≤2s/拍≈6x 速上限)→ 累加;
 * - delta > MAX(大跳/seek 向前)→ 不累加(跳转距离不是听歌时间),但同步基线;
 * - delta ≤ 0(倒退/原位/暂停)→ 不累加,同步基线。
 * 这样 seek 产生的位置差永远不会被当成真实播放时长。
 *
 * === 暂停 / 恢复 ===
 * 只在 [sample] 传入 isPlaying=true 且处于"就绪播放"状态时累加。暂停期间 isPlaying=false,
 * 每拍 delta=0(位置不动)也不累加 → playedSec 不增长。后台暂停同理(播放器暂停即 isPlaying=false)。
 * 恢复后 isPlaying=true,从上次基线继续累加,自然衔接。
 */
internal class PlaySessionTracker {

    companion object {
        /** 有效播放阈值:累计满此毫秒数计为一次有效播放。Spotify 式 30 秒。 */
        const val COUNT_THRESHOLD_MS = 30_000L
        /** 单拍累加上限:超过视为 seek 大跳而非自然播放,不累加。2s/拍 ≈ 6x 速上限。 */
        const val MAX_SAMPLE_DELTA_MS = 2_000L
    }

    /** 自增的 Session 标识,每个 Session 唯一,用于日志/调试,不参与计数判断。 */
    private var nextSessionId = 0L

    private data class Session(
        val id: Long,
        val mediaId: Long,
        var accumMs: Long,
        var counted: Boolean,
        var lastPosMs: Long,
        var rowId: Long = 0L, // v1.2.1: 对应 play_event 行的主键 id,由 onTrackStarted 插入后回填。按行 id 定位 markCounted/finalize,避免"按 mediaId 最新行"子查询写错行。
    )

    /** 当前活跃 Session,null 表示尚未 start(未在播放任何曲目)。 */
    private var current: Session? = null

    /** 累计有效播放跨过 30s 阈值时回调一次(同 Session 只触发一次),参数=(rowId, mediaId)。 */
    var onCounted: ((Long, Long) -> Unit)? = null

    /** Session 结束时回调,参数=(rowId, mediaId, 累计有效秒数)。 */
    var onFinalize: ((Long, Long, Int) -> Unit)? = null

    /** v1.2.1: onTrackStarted 插入 play_event 后,把返回的行 id 回填到该 session,
     *  使后续 onCounted/onFinalize 携带正确的行 id 供 DAO 精确定位。 */
    fun setRowId(sessionId: Long, rowId: Long) {
        val s = current
        if (s != null && s.id == sessionId) s.rowId = rowId
    }

    /**
     * 开始对 [mediaId] 的一次新播放 Session。若当前已有 Session,先结束它并回填 playedSec
     * (无论新旧 mediaId 是否相同 —— 这保证单曲循环"同 mediaId 新一轮"也能 finalize 上一轮、
     * 重置 counted,使新一轮可独立计数)。返回新 Session 的 id。
     */
    fun start(mediaId: Long): Long {
        endCurrent()
        val sid = nextSessionId++
        current = Session(
            id = sid,
            mediaId = mediaId,
            accumMs = 0L,
            counted = false,
            lastPosMs = 0L,
        )
        return sid
    }

    /**
     * 每拍采样。仅在 [isPlaying]=true 且 stateReady=true(播放器就绪、playWhenReady)
     * 时累加自然前进的位置增量;其余情况(暂停/未就绪)只同步基线不累加。返回本拍是否
     * 刚跨过 30s 阈值(便于调用方触发 [onCounted],内部已保证同 Session 只触发一次)。
     *
     * @param pos 当前播放位置(ms),调用方 coerceAtLeast(0) 后传入
     * @param isPlaying playWhenReady
     * @param stateReady playbackState == STATE_READY
     */
    fun sample(pos: Long, isPlaying: Boolean, stateReady: Boolean): Boolean {
        val s = current ?: return false
        var justCounted = false
        if (isPlaying && stateReady) {
            val delta = pos - s.lastPosMs
            if (delta in 1..MAX_SAMPLE_DELTA_MS) {
                s.accumMs += delta
                if (!s.counted && s.accumMs >= COUNT_THRESHOLD_MS) {
                    s.counted = true
                    justCounted = true
                    onCounted?.invoke(s.rowId, s.mediaId)
                }
            }
            // delta 超出 [1, MAX] (seek 大跳/倒退/原位):不累加,仅同步基线
        }
        // 暂停时也同步基线:恢复时不会把暂停期间的位置差算成播放时长
        s.lastPosMs = pos
        return justCounted
    }

    /** 当前 Session 已累计的有效播放毫秒数(供调试/UI,不参与判断)。 */
    fun currentAccumMs(): Long = current?.accumMs ?: 0L

    /** 当前 Session 是否已计数(满 30s)。 */
    fun currentCounted(): Boolean = current?.counted ?: false

    /** 当前 Session 的 mediaId。 */
    fun currentMediaId(): Long? = current?.mediaId

    /**
     * 结束当前 Session 并回填 playedSec。队列末尾播完(STATE_ENDED 无自动推进)/
     * stopAndClear 时调用。返回 (mediaId, playedSec),无 Session 时返回 null。
     */
    fun finalize(): Pair<Long, Int>? = endCurrent()

    private fun endCurrent(): Pair<Long, Int>? {
        val s = current ?: return null
        current = null
        val playedSec = (s.accumMs / 1000L).toInt()
        onFinalize?.invoke(s.rowId, s.mediaId, playedSec)
        return s.mediaId to playedSec
    }
}

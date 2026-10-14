package com.shiyin.music.playback

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.shiyin.music.data.Track
import com.shiyin.music.data.split.SplitRunCollapse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * App-side handle on the playback service. Owns the queue semantics from the
 * handoff: normal queue = whole library in order, album queue = "album:key"
 * (fixed order, gapless, shuffle ignored).
 */
class PlayerController(context: Context, private val scope: CoroutineScope) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    var currentId by mutableStateOf<Long?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF); private set
    var shuffleFlag by mutableStateOf(false); private set
    var queueKey by mutableStateOf<String?>(null); private set
    var sleepLeftSec by mutableLongStateOf(0L); private set
    var sleepChosenMin by mutableStateOf(0); private set
    /** v5.2: sleep mode — 0=off, 1=timed countdown, 2=end-of-current-track. */
    var sleepMode by mutableStateOf(0); private set
    /** v5.2: true while the fade-out curve is actively ramping volume down. */
    var sleepFading by mutableStateOf(false); private set
    /** v5.2: guards the end-of-track fade so it only fires once per track. */
    private var endOfTrackFadeStarted = false

    /** Manual "play next" queue (v1.7 qNext), consumed before the context queue. */
    val manualQueue = androidx.compose.runtime.mutableStateListOf<Track>()
    /** Bumped on any queue mutation so snapshots recompose. */
    var queueVersion by mutableStateOf(0); private set

    /** Kept in sync with the DataStore setting by the ViewModel. */
    var gaplessEnabled: Boolean = true

    private var sleepJob: Job? = null
    private var gapJob: Job? = null
    /** v5.2 Bug9: fade-out coroutine for sleep-timer expiry. */
    private var fadeJob: Job? = null
    private var onTrackStarted: ((Long, Long) -> Unit)? = null

    // v1.2.1: 纯逻辑的"有效播放"计数追踪器(Spotify 式 30 秒阈值)。Session 生命周期、
    // seek 剔除、暂停/恢复累计都在 tracker 内部,可用纯 JVM 单测覆盖;本类每拍只喂数据。
    private val playTracker = PlaySessionTracker().apply {
        onCounted = { rowId, id -> onPlayCounted?.invoke(rowId, id) }
        onFinalize = { rowId, id, sec -> onPlayFinalized?.invoke(rowId, id, sec) }
    }
    /** v1.2.1: syncFromPlayer 重连恢复分支只在冷启动首次跑一次,防 STATE_ENDED 后误开幽灵 session。 */
    private var trackerInitialized = false
    /** v1.2.1: 累计有效播放满 30 秒时触发,参数=(play_event 行 id, mediaId),DAO 按行 id 定位。 */
    private var onPlayCounted: ((Long, Long) -> Unit)? = null
    /** v1.2.1: 切歌/播完时触发,参数=(行 id, mediaId, 累计有效秒数)。 */
    private var onPlayFinalized: ((Long, Long, Int) -> Unit)? = null

    fun connect(
        context: Context,
        onStarted: (Long, Long) -> Unit,
        onPlayCounted: ((Long, Long) -> Unit)? = null,
        onPlayFinalized: ((Long, Long, Int) -> Unit)? = null,
    ) {
        onTrackStarted = onStarted
        this.onPlayCounted = onPlayCounted
        this.onPlayFinalized = onPlayFinalized
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            controller = c
            c.addListener(listener)
            syncFromPlayer()
            startTicker()
        }, { r -> r.run() })
    }

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    /**
     * v1.3.9: 控制器是否已连上服务。Agent 播放技能用它做前置判断——
     * 此前 controller 为 null 时 playQueue 是**无声 no-op**,技能却回报"开始播放",
     * 用户看到"我帮你放"却没有声音,排查不到原因。
     */
    val isReady: Boolean get() = controller != null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId?.toLongOrNull()
            // v1.3.10 诊断(A23):切歌时把"这首到底是不是拆分单曲 + 落在哪一段 + 当前文件位置"
            // 一并留痕。排查"某一首特定歌在固定秒数出问题"时,必须先确认它走的是整轨分段
            // 坐标系还是普通单曲坐标系(两者的 seek 行为完全不同)。
            run {
                val carry = SplitCarry.read(mediaItem)
                val c = controller
                android.util.Log.i(
                    "PlayerDiag",
                    "媒体项切换 reason=$reason title=${mediaItem?.mediaMetadata?.title} " +
                        "分裂=${carry != null} " +
                        (carry?.let { " idx=${it.index}/${it.plan.tracks.size} 段=[${it.current?.startMs},${it.current?.endMs})" } ?: "") +
                        " pos=${c?.currentPosition} dur=${c?.duration}",
                )
            }
            // v1.2.1: 任何切歌原因(手动跳过/AUTO 自动推进/单曲循环新一轮)都经 startTracking
            // → tracker.start:先 finalize 上一首/上一轮 playedSec,再为新 session 重置累计与
            // counted。单曲循环同 mediaId 的新一轮因此能独立计数(上一轮已 finalize)。
            // v5.2: end-of-track sleep mode (mode 2) bookkeeping.
            // AUTO = the previous track played to its end. The ticker's
            // fade normally pauses before this point, but if it missed
            // (very short track / late poll / unknown duration) the player
            // auto-advanced to the next track — stop that now and exit
            // mode 2. A non-AUTO transition is a manual skip: cancel any
            // in-progress fade, restore full volume, and let the new track
            // fade fresh near its own end (mode 2 stays armed).
            var sleepStopped = false
            if (sleepMode == 2 && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                fadeJob?.cancel()
                sleepFading = false
                endOfTrackFadeStarted = false
                sleepMode = 0
                sleepLeftSec = 0
                try { controller?.volume = 1f } catch (_: Exception) { }
                controller?.pause()
                sleepStopped = true
            } else if (sleepMode == 2) {
                fadeJob?.cancel()
                if (sleepFading) {
                    sleepFading = false
                    try { controller?.volume = 1f } catch (_: Exception) { }
                }
                endOfTrackFadeStarted = false
            }
            if (id != null) {
                // v1.3.4: replaceMediaItem(封面推送/队列微调)同样会以 PLAYLIST_CHANGED
                // 触发 transition,且 media3 对"内容相同"的替换不做抑制——无条件
                // startTracking 会与封面推送互为因果成死环(实测 78 次/秒,伴随封面抖动)。
                // 同 id 且 reason=PLAYLIST_CHANGED 即该次自触发替换,跳过:封面已推无需重推,
                // 播放计数也无需重启(同一首同一 session 内)。playQueue 起播时的重复触发
                // 同样被本守卫吸收,playQueue 末尾自有直调 startTracking,不会漏计。
                val isSelfReplace = reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                    id == currentId
                if (!isSelfReplace) {
                    manualQueue.removeAll { it.id == id }
                    // v22: 把回调带来的 mediaItem 一起传进去 —— 它是这次切歌最权威的
                    // 描述（含拆分方案），短曲阈值据此取分段时长。
                    startTracking(id, mediaItem)
                    alignToSplitStartIfNeeded()
                }
            } else {
                // v1.3.3b review#B5: 过渡到 null(移除当前项/清空队列)——id!=null 分支的
                // startTracking 会先 finalize 上一 session,id==null 直接跳过会让当前
                // session 的 playedSec 永不回填(removeMediaItem 移除当前项后 STATE_ENDED
                // 未必发),该次播放时长丢失。这里补 finalize;后续若过渡回新曲目,
                // trackerInitialized 守卫的恢复分支照常开新 session。
                finalizeCurrent()
            }
            queueVersion++
            // "常规间隔": with gapless off, album playback pauses ~1s between tracks.
            // Skip when a sleep-stop just paused (mode 2 fell through to AUTO).
            if (!sleepStopped && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                queueKey?.startsWith("album:") == true && !gaplessEnabled
            ) {
                val c = controller ?: return
                gapJob?.cancel()
                gapJob = scope.launch {
                    c.pause()
                    delay(1000)
                    if (isActive) c.play()
                }
            }
        }

        /**
         * v1.3.10 诊断(用户反馈 A23「藤井风《罪の香り》播到 1:37 就静音」):
         * 此前**没有实现 onPlayerError**——解码/音轨错误被完全静默吞掉,表现就是
         * "到某一秒突然静音、无提示、日志里也查不到"。这里把错误码、当前位置与
         * cause 链完整打印出来,便于定位"某一秒静音"这类问题。
         */
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val c = controller
            android.util.Log.e(
                "PlayerDiag",
                "onPlayerError code=${error.errorCode} name=${error.errorCodeName} " +
                    "pos=${c?.currentPosition} idx=${c?.currentMediaItemIndex} " +
                    "state=${c?.playbackState} playing=${c?.playWhenReady} msg=${error.message}",
            )
            var cause: Throwable? = error.cause
            var depth = 0
            while (cause != null && depth < 6) {
                android.util.Log.e("PlayerDiag", "  cause[$depth] ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                depth++
            }
        }

        /**
         * v1.3.10 诊断(A23「罪の香り 播到 1:37 静音、位置在 1:37↔1:40 之间循环」):
         *
         * 实测日志显示位置每 ~3 秒从 ~100.17s **无故回退**到 ~97.46s、且 state=READY/
         * playing=true(播放器自认为正常)→ 既没有 onPlayerError,也没有我们自己 seek 的日志。
         * 说明有**未记录的 seek 路径**在反复拉回位置(服务端 MediaSession.Callback 的
         * onPlay/onNext 也会 seek,不经过本类)。
         *
         * 所以这里挂上 Media3 官方的"位置不连续"回调:**它带 reason 和旧/新位置**,
         * 并附一段调用栈——谁在 seek 一目了然(见 logcat 里 PlayerDiag 的堆栈)。
         */
        override fun onPositionDiscontinuity(
            oldPosition: androidx.media3.common.Player.PositionInfo,
            newPosition: androidx.media3.common.Player.PositionInfo,
            reason: Int,
        ) {
            val reasonName = when (reason) {
                androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                androidx.media3.common.Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
                androidx.media3.common.Player.DISCONTINUITY_REASON_SILENCE_SKIP -> "SILENCE_SKIP"
                else -> "OTHER($reason)"
            }
            android.util.Log.w(
                "PlayerDiag",
                "位置不连续 reason=$reasonName " +
                    "old(idx=${oldPosition.mediaItemIndex}, pos=${oldPosition.positionMs}) → " +
                    "new(idx=${newPosition.mediaItemIndex}, pos=${newPosition.positionMs})",
                Throwable("discontinuity caller"),
            )
            // v1.4.0: 立即纠正「重启落回文件 0」（单曲循环），不等下一拍 ticker。
            realignIfWrappedBeforeSegment()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                // v1.2.1: 队列末尾(REPEAT_MODE_OFF)播完无自动推进,回填当前曲 playedSec。
                // REPEAT_MODE_ALL/ONE 不发 STATE_ENDED,其 playedSec 由切歌时 startTracking 回填。
                finalizeCurrent()
                // End of queue with repeat off: stop at pos 0, stay on last track.
                controller?.let {
                    it.pause()
                    it.seekTo(it.currentMediaItemIndex, 0L)
                }
            }
            // v1.3.x 播放设置:READY 时 audioSessionId 才有效,补发一次(见
            // applyAudioSettings 的注释——服务端 sessionId<=0 时只记不生效)。
            if (state == Player.STATE_READY) lastAudioSettings?.let { sendAudioSettings(it) }
        }
    }

    private fun syncFromPlayer() {
        val c = controller ?: return
        currentId = c.currentMediaItem?.mediaId?.toLongOrNull()
        isPlaying = c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.mediaItemCount > 0
        repeatMode = c.repeatMode
        val dur = c.duration
        if (dur > 0) durationMs = dur
        positionMs = absOf(c.currentPosition.coerceAtLeast(0))
        // v1.2.1: 重连恢复分支——仅冷启动首次同步(controller 刚连上、服务已在播但 tracker 无 session)
        // 跑一次,补插 play_event 行。用 trackerInitialized 守卫:STATE_ENDED 后 finalizeCurrent 把
        // tracker 置 null,随后 controller.pause()/seekTo 触发 onEvents→syncFromPlayer,若不守卫会
        // 给已结束的曲误开幽灵 session+插第二条 play_event→污染"最近播放"+虚增计数(每首队尾都中招)。
        if (!trackerInitialized && playTracker.currentMediaId() == null && currentId != null) {
            startTracking(currentId!!)
        }
        trackerInitialized = true
    }

    /**
     * v1.3.x: 帧级精确播放位置。
     *
     * [positionMs] 是 ticker 每 300ms 刷一次的**状态**——歌词跟唱高亮读它最多滞后
     * 一个 tick(实测"唱到该行了还慢半拍才亮")。ExoPlayer 的 `currentPosition`
     * 自带系统时钟插值(基于上次内部更新外推),帧级调用拿到的是当下精确位置,
     * 无 tick 粒度延迟。仅歌词高亮这类"需要跟帧"的场景用它;进度条/时间显示
     * 继续读 [positionMs](300ms 粒度足够,且避免全页重组)。
     */
    fun framePositionMs(): Long =
        controller?.let { absOf(it.currentPosition.coerceAtLeast(0)) } ?: positionMs

    /**
     * v23: 与 [framePositionMs] 同源（帧级插值），但换算到**当前曲目**坐标。
     *
     * 歌词时间戳、进度条时长都是"当前曲目"坐标系；而 `currentPosition` 是**整轨文件**
     * 的绝对位置。拆分单曲下两者差一个 [virtualStartMs] —— 拿文件坐标去比歌词时间戳，
     * 高亮会整体偏移（这首的区间起点越靠后，偏得越多）。普通单曲两者重合。
     */
    fun frameDisplayPositionMs(): Long =
        if (virtualDurationMs > 0L) (framePositionMs() - virtualStartMs).coerceAtLeast(0L)
        else framePositionMs()

    private var tickerStarted = false
    private fun startTicker() {
        if (tickerStarted) return
        tickerStarted = true
        scope.launch {
            while (isActive) {
                controller?.let { c ->
                    // v22: 先检查拆分区间边界 —— 越过时无缝换 mediaId（引擎层零动作）。
                    // 放在取 positionMs 之前，使这一拍的统计采样落在新曲目的 session 上。
                    checkVirtualBoundary()
                    // v1.4.0: 单曲循环重启若落回文件 0（裁剪窗口起点），立刻拨回本段起点。
                    realignIfWrappedBeforeSegment()
                    val newPos = absOf(c.currentPosition.coerceAtLeast(0))
                    // v1.3.10 诊断(A23):位置**无故后退**时留痕(没有任何 seek 请求却退回去)。
                    // 用户实测「1:37 静音 → 1:40 → 又跳回 1:37」正是这类症状。
                    if (newPos + 1500L < positionMs) {
                        android.util.Log.w(
                            "PlayerDiag",
                            "位置后退: ${positionMs} → $newPos state=${c.playbackState} playing=${c.playWhenReady}",
                        )
                    }
                    positionMs = newPos
                    // v1.3.10 A23(《罪の香り》1:37 静音、位置 1:37↔1:40 循环)**封存**时留下的
                    // 最后一件零风险的事:把"引擎自认为在播、实际没输出"这个判据留成**纯日志**。
                    //
                    // 已确证的事实(实测日志):循环期间 ①无 onPlayerError ②无媒体项切换
                    // ③Media3 层面没有报"位置不连续" ④没有任何我们自己的 seek
                    // ⑤state=READY、playing=true。最自洽的解释是"音频管线卡住不出声,
                    // 位置回退只是上报值的假象(controller 外推 → 被 PlayerInfo 刷新拉回,
                    // 周期 3010ms 实测吻合)"——但**尚未确证**,所以只观测、不做任何自动恢复。
                    //
                    // 判据用 Media3 现成语义:playWhenReady && STATE_READY && !isPlaying 且
                    // **无音频焦点抑制**(抑制时 !isPlaying 属正常,不能误判)。
                    // (曾实现过"卡住就前推 300ms"的自动恢复:未能确证是根因、且会改播放行为,
                    //  已按"封存时不留风险"撤掉。)
                    val suppressed = c.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
                    val looksStalled = c.playWhenReady && c.playbackState == Player.STATE_READY &&
                        !c.isPlaying && !suppressed
                    if (looksStalled) {
                        stalledMs += TICK_MS
                        if (stalledMs == STALL_LOG_MS) {
                            android.util.Log.w(
                                "PlayerDiag",
                                "疑似引擎卡死 ${stalledMs}ms: pos=$newPos state=${c.playbackState} " +
                                    "playing=${c.playWhenReady} suppressed=$suppressed",
                            )
                        }
                    } else {
                        if (stalledMs >= STALL_LOG_MS) {
                            android.util.Log.i("PlayerDiag", "卡死已解除(持续 ${stalledMs}ms) pos=$newPos")
                        }
                        stalledMs = 0
                    }
                    val dur = c.duration
                    if (dur > 0) durationMs = dur
                    // v1.2.1: 累计当前曲目有效播放时长,满 30 秒计一次有效播放。
                    playTracker.sample(positionMs, c.playWhenReady, c.playbackState == Player.STATE_READY)
                    // v5.2: end-of-track sleep mode — kick the fade once we're
                    // inside the last ~20% (clamped 6..20s) of the track.
                    // v22: 拆分模式下"本曲结束"指的是**当前虚拟曲目**的终点，不是整轨
                    // 文件末尾 —— 用显示坐标（虚拟区间内）而不是文件坐标。
                    if (sleepMode == 2 && !endOfTrackFadeStarted) {
                        val vDur = displayDurationMs()
                        if (vDur > 0) {
                            val fadeMs = (vDur * 0.2f).toLong().coerceIn(6000L, 20000L)
                            if (displayPositionMs() >= vDur - fadeMs) {
                                endOfTrackFadeStarted = true
                                sleepFading = true
                                fadeOutAndPause(fadeMs)
                            }
                        }
                    }
                }
                delay(300)
            }
        }
    }

    /**
     * v22: 取某曲目的「有效时长」用于短曲阈值，三级回退。
     *
     * 拆分曲目的切换有 4 条来源 —— App 内按钮、边界自动切换、通知栏/蓝牙耳机/车机
     * （由 PlaybackService 直接改 player）。后两者发生时 `durationById` 里根本没有新
     * 曲目的条目（没人调过 buildItem），若回落到 0 则阈值变 30s，十几秒的 intro/outro
     * 永远到不了阈值、**静默不计次**。
     *
     * 所以按可靠性从高到低取：
     *  1. [item] —— onMediaItemTransition 回调带来的 MediaItem（最权威，任何来源都对得上）
     *  2. 当前条目的 extras —— 方案就写在里面
     *  3. [durationById] —— 兜底
     */
    private fun durationForThreshold(id: Long, item: MediaItem?): Long {
        SplitCarry.read(item)?.current?.takeIf { it.id == id }?.let { return it.durationMs }
        currentCarry()?.current?.takeIf { it.id == id }?.let { return it.durationMs }
        return durationById[id] ?: 0L
    }

    /** v1.2.1: 新曲目开始播放时调用——tracker.start 会先 finalize 当前 session(回填
     *  上一首/上一轮 playedSec),再为新曲开新 session。单曲循环"同 mediaId 新一轮"也
     *  走这里:上一轮被 finalize、counted 重置,第二轮可独立计数。返回 session id,
     *  onTrackStarted 带 (mediaId, sessionId) 让 VM 插行后回填 rowId。
     *
     *  v22: 带上曲目时长 —— 拆分出来的 intro/outro 常常只有十几二十秒，固定 30 秒阈值
     *  会让它们永远不可能被计数。tracker 对 ≤60s 的短曲改用「半程」阈值。 */
    private fun startTracking(id: Long, item: MediaItem? = null) {
        // 换曲即重置卡死观测状态(见 ticker;纯观测)
        stalledMs = 0L
        val sid = playTracker.start(id, durationForThreshold(id, item))
        onTrackStarted?.invoke(id, sid)
    }

    /** v1.2.1: 供 MainViewModel.onTrackStarted 在插 play_event 后回填该 session 的行 id,
     *  使后续 markCounted/finalize 按主键 id 精确定位(不靠"mediaId 最新行"子查询)。 */
    internal fun setSessionRowId(sessionId: Long, rowId: Long) {
        playTracker.setRowId(sessionId, rowId)
    }

    /** v1.2.1: 队列末尾播完(STATE_ENDED,无自动推进)时 finalize 当前 session 回填 playedSec。
     *  也供 MainViewModel.onCleared 在进程关停(sliding-away)时 flush,避免暂停离开丢时长。 */
    internal fun finalizeCurrent() {
        playTracker.finalize()
    }

    // ── v22: 整轨拆分 —— 无缝虚拟切歌 ────────────────────────────────────────

    /** 当前条目的拆分信息（普通曲目 / 容器模式返回 null）。 */
    private fun currentCarry(): PlanCarry? = SplitCarry.read(controller?.currentMediaItem)

    // ── v1.4.0: 运行区裁剪的坐标换算 ──────────────────────────────────────
    // 条目被裁成 [运行区起点, 运行区末] 后，播放器给的位置是**运行区相对坐标**；
    // 而歌词时间戳/进度条/播放统计全都建立在**文件绝对坐标**上。统一在这里换算，
    // 下游一律读 positionMs（= 绝对坐标），对裁剪无感知。

    /** 当前条目裁剪窗口的文件绝对起点（未裁剪 = 0）。 */
    private val currentClipStart: Long get() = currentCarry()?.clipStartMs ?: 0L

    /** 播放器相对位置 → 文件绝对位置。 */
    private fun absOf(rel: Long): Long = currentClipStart + rel

    /** 文件绝对位置 → 播放器相对位置。 */
    private fun relOf(abs: Long): Long = (abs - currentClipStart).coerceAtLeast(0L)

    /** 用**文件绝对坐标**发起 seek（内部换算成裁剪后的相对坐标）。 */
    private fun seekToAbsolute(abs: Long): Boolean {
        val c = controller ?: return false
        val rel = relOf(abs)
        // v1.5.0 P2 spike 步骤 2b：把**本次请求的目标位置**告诉播放链路里的探针
        // （服务与本类同进程 → 进程内 @Volatile 就够）。**只写两个 volatile 字段，
        // 不改变任何播放行为**；档位 0 时处理器根本不在链上，这两句等于空转。
        KaraokeSeekAnchor.set(abs, rel)
        c.seekTo(rel)
        return true
    }

    /**
     * 进入一个拆分单曲条目时，把播放位置对齐到它的区间起点。
     *
     * 为什么需要：队列里同一个整轨文件可能出现多次（歌单里同时有第 1 首和第 5 首），
     * 而 `setMediaItems(items, index, startPositionMs)` 的起始位置只作用于**首个**条目 ——
     * 后续条目会从 uri 的 0 开始播，落进错误的曲目区间。
     *
     * 无缝边界切换时位置本就在新曲区间内 → 这里不会触发 seek，因此不影响无缝性。
     */
    private fun alignToSplitStartIfNeeded() {
        val c = controller ?: return
        val carry = currentCarry() ?: return
        val t = carry.current ?: return
        val pos = absOf(c.currentPosition)
        if (pos < t.startMs || pos >= t.endMs) {
            seekToAbsolute(t.startMs)
        }
    }

    /**
     * v1.4.0: 把「重启落回文件 0」的位置拨回本段起点。
     *
     * 裁剪窗口是 `[0, 段末]`（startPositionMs 保持 0 以保住文件绝对坐标），因此条目里
     * **仍包含它前面那些段的音频**。单曲循环重启时 Media3 回到窗口起点 = 文件 0 秒，
     * 音频就跑回前面的段去了（真机：对 2.BBB 开单曲循环，BBB 放完接着响 1.AAA，
     * 页面不变——因为媒体项没换）。
     *
     * 刻意**不依赖媒体项切换回调**：单曲循环重启不保证发 onMediaItemTransition，
     * 所以同时挂在「位置不连续」（立即）与 300ms ticker（兜底）上。
     */
    private fun realignIfWrappedBeforeSegment() {
        val c = controller ?: return
        val carry = currentCarry() ?: return
        if (!carry.needsClip) return
        val t = carry.current ?: return
        // 裁剪窗口起点 = 本条运行区起点。单曲循环重启会回到窗口起点：
        //  - 本条只含一段（歌单里的单首拆分曲目）→ 起点就是本段起点，无需动作；
        //  - 本条含多段（整轨专辑）→ 起点是第 1 段，需拨回**当前段**起点。
        val segRel = relOf(t.startMs)
        if (c.currentPosition < segRel) {
            android.util.Log.i(
                "PlayerDiag",
                "重启落回 ${c.currentPosition}ms（本段相对起点 ${segRel}ms）→ 拨回段首",
            )
            seekToAbsolute(t.startMs)
        }
    }

    /**
     * v22: 每拍检查是否越过了拆分单曲的区间边界。
     *
     * 越过时**只做一次 replaceMediaItem**（换 mediaId + 标题/歌手，uri 与
     * LocalConfiguration 不变）：
     * - 引擎层不 seek、不 reload、不重建 MediaSource → 音频流完全连续，无卡顿无爆音；
     * - Media3 会以 reason=PLAYLIST_CHANGED 触发 onMediaItemTransition，而 mediaId 变了，
     *   于是既有的 `isSelfReplace` 守卫放行 → `startTracking(newId)` 自动完成
     *   「上一首 finalize 回填 playedSec + 新一首插 play_event」，统计口径无需另写逻辑。
     *
     * 位置下标**纯由 positionMs 派生**，所以用户拖进度条跨越边界、通知栏 seek、蓝牙
     * 耳机切歌等任何来源的位置变化都会自洽归位。
     */
    private fun checkVirtualBoundary() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val carry = SplitCarry.read(item) ?: return
        val pos = absOf(c.currentPosition)
        val k = carry.plan.indexAt(pos)
        val cur = carry.current
        // v1.3.10 诊断(A23):位置落在**当前分段之外**时留痕。
        // "某一秒静音 + 位置来回跳"的第一嫌疑就是边界判定与真实位置不一致
        // (分段之间有缝/重叠,或 indexAt 算法在边界上的取整)。这类问题以前完全没有日志。
        if (cur != null && (pos < cur.startMs || pos >= cur.endMs)) {
            android.util.Log.w(
                "PlayerDiag",
                "boundary 位置越界: pos=$pos 当前段=[${cur.startMs},${cur.endMs}) idx=${carry.index} k=$k",
            )
        }
        if (k < 0 || k == carry.index) return
        // v1.4.0: 越出本条负责的分段区间 → **不动作**，把收尾交给引擎的结束裁剪。
        // 若这里替到区间外的段，就会和引擎的裁剪转场**重复推进** → 一次跳掉一整首。
        if (k !in carry.segStart..carry.segLast) {
            android.util.Log.i(
                "PlayerDiag",
                "boundary 越界不动作: k=$k 区间=[${carry.segStart},${carry.segLast}] " +
                    "idx=${carry.index} pos=$pos 已裁剪=${carry.needsClip}",
            )
            return
        }
        android.util.Log.i(
            "PlayerDiag",
            "boundary 换段: idx=${carry.index} → $k pos=$pos 区间=[${carry.segStart},${carry.segLast}]",
        )
        val next = carry.plan.tracks.getOrNull(k) ?: return
        // durationById 只作兜底（durationForThreshold 优先读方案里的分段时长）；
        // 这里顺手登记，供方案被清掉后的降级路径使用。
        durationById[next.id] = next.durationMs
        android.util.Log.w("PlayerDiag", "replaceMediaItem @boundaryCarry pos=${c.currentPosition}")
        c.replaceMediaItem(c.currentMediaItemIndex, SplitCarry.itemFor(item, carry.copy(index = k)))
        // 兜底：Media3 契约上 onMediaItemTransition 早于 onEvents（此时 currentId 还是旧值，
        // isSelfReplace 守卫放行 → startTracking 已开好新 session）。万一这个顺序变了或被
        // 守卫吞掉，就会出现"切了歌却没有 play_event"的统计漏洞，这里补一次。
        if (playTracker.currentMediaId() != next.id) startTracking(next.id)
    }

    /** v22: mediaId → 该曲目时长。buildItem 时登记，供 startTracking 取短曲阈值。
     *  必须走这里而不是 controller.duration：拆分模式下后者是整轨文件的时长。 */
    private val durationById = HashMap<Long, Long>()

    /**
     * v22: VM 注入的拆分方案提供者 —— 给定一个**拆分单曲**曲目，返回它的方案与下标；
     * 普通曲目与「完整专辑」容器返回 null。
     *
     * 由曲目本身就能判断走哪种模式（见 SplitIds）：拆分单曲的 id 落在 TRACK 段、
     * 容器的 id 落在 CONTAINER 段 —— 因此不需要为 playQueue / syncQueue /
     * addToQueueNext 各加一个参数，所有既有调用点原样可用。
     */
    var planProvider: ((Track) -> PlanCarry?)? = null

    /**
     * v1.4.0: trackId → 本条队列条目负责的分段区间 `(startSeg, lastSeg)`。
     *
     * 队列压缩（[com.shiyin.music.data.split.SplitRunCollapse]）把"同源 + 下标连续"的几首
     * 合成一条时，只留下了代表那首；**这条到底管到第几首为止**必须在这里按**队列顺序**
     * 重新算出来，否则段内推进无从知道该在哪里停手（真机 bug：歌单里放一首拆分单曲，
     * 播完接着播同轨的下一段，而不是歌单的下一首）。
     */
    private var runBounds: Map<Long, Pair<Int, Int>> = emptyMap()

    private fun computeRunBounds(tracks: List<Track>) {
        val slots = tracks.map { t ->
            planProvider?.invoke(t)?.let { SplitRunCollapse.Slot(it.plan.sourceMediaId, it.index) }
        }
        runBounds = SplitRunCollapse.runs(slots)
            .filter { it.startSegment >= 0 }
            .associate { r -> tracks[r.repIndex].id to (r.startSegment to r.lastSegment) }
    }

    private fun buildItem(t: Track): MediaItem {
        durationById[t.id] = t.durationMs
        val mb = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
            .setAlbumTitle(t.album)
            .setArtworkUri(
                ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"), t.albumId,
                )
            )
        // v22: 拆分单曲带上方案（含各曲起点/终点/曲名），供边界无缝切歌与通知栏切歌。
        // v1.4.0: 一并带上本条负责的分段区间，并按需设置结束裁剪。
        var clipStartMs = -1L
        var clipEndMs = -1L
        planProvider?.invoke(t)?.let { raw ->
            val b = runBounds[t.id]
            val carry = if (b != null) raw.copy(startSeg = b.first, lastSeg = b.second) else raw
            SplitCarry.put(mb, carry)
            if (carry.needsClip) {
                clipStartMs = carry.clipStartMs
                clipEndMs = carry.plan.tracks.getOrNull(carry.segLast)?.endMs ?: -1L
            }
        }
        val builder = MediaItem.Builder()
            .setMediaId(t.id.toString())
            .setUri(t.uri)
            .setMediaMetadata(mb.build())
        // v1.4.0: 把条目裁成 `[本运行区起点, 本运行区末]`。
        //  - 时长、通知栏进度 = 本条真实长度（不再是被裁掉前缀的绝对时间）；
        //  - 单曲循环重启天然回到本条起点（不再跑回文件 0 秒去放前面的段）。
        // 位置因此变成运行区相对坐标，由 absOf/relOf 统一换算回文件绝对坐标交给下游。
        if (clipStartMs >= 0 && clipEndMs > clipStartMs) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clipStartMs)
                    .setEndPositionMs(clipEndMs)
                    .build()
            )
        }
        return builder.build()
    }

    /** v2.0: push album art bitmap data to the current MediaItem's metadata so
     *  the system MediaSession (notification pill, lock screen, quick settings)
     *  can display the cover art. Uses setArtworkData (byte[]) instead of
     *  setArtworkUri (content://) because the system MediaController doesn't
     *  have permission to read arbitrary content:// URIs.
     *  v1.2.2: ColorOS 灵动岛用 UriArtworkLoader 从 artworkUri 读封面(不读 artworkData)。
     *  因此 [artworkUri] 传入 FileProvider 暴露的 content:// URI(指向 App 私有封面文件,
     *  系统可读、不进相册),同时仍设 artworkData 兜底。两者都设,兼容标准通知与 ColorOS。
     *  v5: [forMediaId] pins the update to a specific track so that if the user
     *  skipped past it before the (slow) bitmap load landed, we don't stamp the
     *  old track's cover onto the now-playing track's metadata. */
    fun updateArtworkData(data: ByteArray, forMediaId: Long? = null, artworkUri: android.net.Uri? = null) {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        if (idx < 0 || idx >= c.mediaItemCount) return
        val item = c.getMediaItemAt(idx)
        if (forMediaId != null && item.mediaId.toLongOrNull() != forMediaId) return
        val mb = item.mediaMetadata.buildUpon()
            .setArtworkData(data, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        if (artworkUri != null) mb.setArtworkUri(artworkUri)
        val newItem = item.buildUpon()
            .setMediaMetadata(mb.build())
            .build()
        // 仅日志(零行为):A23 封存后保留的定位线索之一
        android.util.Log.w("PlayerDiag", "replaceMediaItem @artworkPush pos=${c.currentPosition}")
        c.replaceMediaItem(idx, newItem)
    }

    /**
     * 卡死观测状态(见 ticker):累计"自认为在播、实际没输出"的时长。
     * **纯观测,不改变任何播放行为**。每次换曲重置。
     */
    private var stalledMs = 0L

    private companion object {
        /** ticker 周期(与协程里的 delay 一致)。 */
        const val TICK_MS = 300L
        /** 卡满这么久打一条日志(仅观测)。 */
        const val STALL_LOG_MS = 1500L
    }

    /** Starts playback of [tracks] at [startId]. [key] null = normal queue. */
    fun playQueue(tracks: List<Track>, startId: Long, key: String?) {
        // v23:空队列必须是**完整 no-op**。抽 loadQueue 时把原来开头的
        // `if (tracks.isEmpty()) return` 一起搬走了,只剩 loadQueue 内部的早退,
        // 而 playQueue 会继续执行到下面的 play() —— 结果 playQueue(emptyList())
        // 会把**上一次装载的队列**接着播起来(调用方以为静默什么都不做)。
        if (tracks.isEmpty()) return
        loadQueue(tracks, startId, key)
        // playQueue = 装载 + 立即开播；loadQueue 只装载(拆分编辑页"不自动播放"用)。
        controller?.play()
    }

    /**
     * 装载队列但**不播放**——停在暂停态。与 playQueue 同一套装载逻辑:
     * repeatMode/queueKey/起点/manual queue/统计口径全部一致,唯一差异是不调 play。
     * 拆分编辑页用它:进页面只装载整轨文件(进度条可拖、时长可读),用户自己点播放。
     */
    fun loadQueue(tracks: List<Track>, startId: Long, key: String?) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        gapJob?.cancel()
        queueKey = key
        val index = tracks.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        // v4.3: 播放专辑自动整张循环——专辑有明确首尾，播完自动回到第一首。
        // 只自动开启，不强制重置（用户随后仍可在播放页手动改回）。
        if (key?.startsWith("album:") == true && c.repeatMode != Player.REPEAT_MODE_ALL) {
            repeatMode = Player.REPEAT_MODE_ALL
            c.repeatMode = Player.REPEAT_MODE_ALL
        }
        // v1.4.0: 先按队列顺序算出每条负责的分段区间，buildItem 才能带上界/设裁剪。
        computeRunBounds(tracks)
        // v22: 拆分单曲从它自己的区间起点开始播（整轨文件坐标）。
        // v1.4.0: 被裁剪的条目，播放器时间轴从裁剪起点算起 → 相对起点恒为 0；
        // 未裁剪（普通曲目，或拆分但没标注区间）沿用文件绝对起点。
        val startCarry = planProvider?.invoke(tracks[index])
        val startPos = if (startCarry != null && runBounds.containsKey(tracks[index].id)) {
            0L
        } else {
            startCarry?.let { SplitCarry.startMsOf(it) } ?: 0L
        }
        c.setMediaItems(tracks.map(::buildItem), index, startPos)
        // Pending manual-queue items survive a context switch (v1.7 semantics).
        manualQueue.removeAll { it.id == startId }
        manualQueue.forEachIndexed { i, t -> c.addMediaItem(index + 1 + i, buildItem(t)) }
        c.shuffleModeEnabled = shuffleFlag && key == null
        c.prepare()
        currentId = startId
        queueVersion++
        startTracking(tracks[index].id)
    }

    /** v1.7 "添加到播放队列": inserts right after current + pending manual items. */
    fun addToQueueNext(track: Track) {
        val c = controller ?: return
        if (manualQueue.any { it.id == track.id }) return
        if (c.mediaItemCount == 0) {
            playQueue(listOf(track), track.id, null)
            return
        }
        val insertAt = (c.currentMediaItemIndex + 1 + manualQueue.size).coerceAtMost(c.mediaItemCount)
        // v1.4.0: 单条追加时这条**自己就是区间**（不吞并同轨后续段）；否则会退回"整张方案"，
        // 播完它还会继续播同一整轨文件的下一段。
        planProvider?.invoke(track)?.let { runBounds = mapOf(track.id to (it.index to it.index)) }
        c.addMediaItem(insertAt, buildItem(track))
        manualQueue.add(track)
        queueVersion++
    }

    data class QueueEntry(val id: Long, val index: Int, val isManual: Boolean)

    /** v1.3.x 需求4:当前曲的相邻曲 id(尊重 shuffle 顺序与循环模式)。
     *  next 的语义与 next() 方法一致(seekToNextMediaItem,Media3 内部处理
     *  shuffle/循环下的"下一首是谁");prev 同 seekToPrevious。供迷你播放条
     *  滑动切歌与封面预加载共用的预测入口——预加载目标必须与手势/按钮实际
     *  切到的曲一致,否则预了也白预。队尾/队首在 REPEAT_MODE_OFF 下 Media3
     *  返回自身(next() 方法的自定义回绕到第 0 首在这里看不到——预加载
     *  miss 一次,无功能影响,只少一次命中)。 */
    fun nextTrackId(): Long? = controller?.let { c ->
        val i = c.nextMediaItemIndex
        if (i >= 0 && i < c.mediaItemCount) c.getMediaItemAt(i).mediaId.toLongOrNull() else null
    }

    fun prevTrackId(): Long? = controller?.let { c ->
        val i = c.previousMediaItemIndex
        if (i >= 0 && i < c.mediaItemCount) c.getMediaItemAt(i).mediaId.toLongOrNull() else null
    }

    /** v1.3.x 横向分页切歌:nextTrackId() 在 REPEAT_OFF 队尾返回 null,但 next()
     *  在同场景会回绕到第 0 首(见 next() 的 hasNextMediaItem 分支)——分页视图
     *  必须预知"下一首是谁"才能预渲染右页,故在此补同样的回绕语义。REPEAT_ALL
     *  下 Media3 自身的 nextMediaItemIndex 已回绕,不触发本兜底。 */
    fun wrappedNextTrackId(): Long? = nextTrackId() ?: controller?.let { c ->
        if (c.mediaItemCount > 1) c.getMediaItemAt(0).mediaId.toLongOrNull() else null
    }

    /** Circular upcoming list after the current item (manual items lead). */
    fun upcomingEntries(): List<QueueEntry> {
        val c = controller ?: return emptyList()
        val n = c.mediaItemCount
        if (n <= 1) return emptyList()
        val cur = c.currentMediaItemIndex
        val manualIds = manualQueue.map { it.id }.toSet()
        val order = (cur + 1 until n) + (0 until cur)
        return order.map { i ->
            val id = c.getMediaItemAt(i).mediaId.toLongOrNull() ?: -1L
            QueueEntry(id, i, id in manualIds)
        }
    }

    fun currentQueueEntry(): QueueEntry? {
        val c = controller ?: return null
        val id = currentId ?: return null
        return QueueEntry(id, c.currentMediaItemIndex, false)
    }

    fun seekToQueueIndex(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0L)
            c.play()
        }
    }

    fun removeQueueEntry(entry: QueueEntry) {
        val c = controller ?: return
        if (entry.index !in 0 until c.mediaItemCount) return
        if (entry.index == c.currentMediaItemIndex) return
        c.removeMediaItem(entry.index)
        manualQueue.removeAll { it.id == entry.id }
        queueVersion++
    }

    /** v1.1: 按 track id 移除队列项（右滑移除用）。重新解析当前 index，避免队列
     *  漂移导致 entry.index 过期删错项；跳过当前正在播放项。 */
    fun removeQueueEntryById(id: Long) {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        var idx = -1
        for (i in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(i).mediaId.toLongOrNull() == id) { idx = i; break }
        }
        if (idx < 0 || idx == cur) return
        c.removeMediaItem(idx)
        manualQueue.removeAll { it.id == id }
        queueVersion++
    }

    /**
     * v5.2 Bug7: move an upcoming queue entry from one position to another.
     * Drives `MediaController.moveMediaItem` so the underlying
     * MediaSession+ExoPlayer actually reorders the playback. The caller
     * passes the *original* `entry` (from the latest `upcomingEntries()`
     * snapshot) and the new absolute target index.
     */
    fun moveQueueEntry(entry: QueueEntry, toIndex: Int) {
        val c = controller ?: return
        if (entry.index !in 0 until c.mediaItemCount) return
        if (entry.index == c.currentMediaItemIndex) return
        val target = toIndex.coerceIn(0, c.mediaItemCount - 1)
        if (target == entry.index) return
        try {
            c.moveMediaItem(entry.index, target)
            queueVersion++
        } catch (_: Exception) { }
    }

    /**
     * v5.2 Bug6: drag-reorder helper keyed by **track id** instead of frozen
     * `QueueEntry.index`. Both arguments are current track ids visible in the
     * upcoming-rows list. Resolves the dragged track's *current* absolute
     * media-item index and the target row's absolute index freshly from the
     * underlying MediaController — so chained swaps during one drag work
     * without the caller needing to track how `entry.index` shifts after each
     * prior `moveMediaItem`. Idempotent on either side being the current track
     * or failing to resolve.
     */
    fun moveQueueEntryById(dragId: Long, targetId: Long) {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        var dragIdx = -1
        var targetIdx = -1
        for (i in 0 until c.mediaItemCount) {
            val id = c.getMediaItemAt(i).mediaId.toLongOrNull() ?: continue
            if (id == dragId) dragIdx = i
            if (id == targetId) targetIdx = i
        }
        if (dragIdx < 0 || targetIdx < 0) return
        if (dragIdx == cur || targetIdx == cur) return
        if (dragIdx == targetIdx) return
        try {
            c.moveMediaItem(dragIdx, targetIdx)
            queueVersion++
        } catch (_: Exception) { }
    }

    /**
     * Rebuilds the queue in place after the library changes (trash/ignore/reorder).
     * No-ops when the item list is unchanged, so startup rescans never stomp
     * an already-playing queue.
     */
    fun syncQueue(tracks: List<Track>) {
        val c = controller ?: return
        val cur = currentId ?: return
        if (tracks.none { it.id == cur }) return
        val newIds = tracks.map { it.id.toString() }
        val oldIds = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        if (newIds == oldIds) return
        val index = tracks.indexOfFirst { it.id == cur }
        // v1.4.0: 新旧条目都可能是**运行区裁剪**条目，位置要按各自裁剪起点换算，
        // 否则重建队列后会跳位置。先取文件绝对坐标，再换算到新条目的相对坐标。
        val absPos = absOf(c.currentPosition)
        val wasPlaying = c.playWhenReady
        computeRunBounds(tracks)
        val newClipStart = runBounds[tracks[index].id]?.let { (segStart, _) ->
            planProvider?.invoke(tracks[index])?.plan?.tracks?.getOrNull(segStart)?.startMs
        } ?: 0L
        c.setMediaItems(tracks.map(::buildItem), index, (absPos - newClipStart).coerceAtLeast(0L))
        manualQueue.removeAll { m -> m.id == cur }
        manualQueue.forEachIndexed { i, t -> c.addMediaItem(index + 1 + i, buildItem(t)) }
        c.prepare()
        c.playWhenReady = wasPlaying
        queueVersion++
    }

    fun stopAndClear() {
        gapJob?.cancel()
        manualQueue.clear()
        // v1.2.1: 清空前 finalize 当前 session 回填 playedSec(若有)。
        finalizeCurrent()
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        currentId = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
        queueKey = null
        queueVersion++
    }

    fun toggle() {
        val c = controller ?: return
        gapJob?.cancel()
        if (c.playWhenReady) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        gapJob?.cancel()
        val c = controller ?: return
        // v22: 拆分模式下「下一首」是**同一个整轨文件里的下一首**，而不是下一个队列
        // 条目（整个队列可能只有一个容器条目）。两次调用在同一主线程块内完成，ticker
        // 无法插入其间；且这是用户主动操作，seek 是可接受的。
        val carry = currentCarry()
        val item = c.currentMediaItem
        if (carry != null && item != null) {
            val nextIdx = carry.index + 1
            // v1.4.0: 只在**本条区间内**前进。到上界就落到下面的常规"下一个队列条目"，
            // 不再一路走完整张方案（真机 bug：歌单里放一首拆分单曲，播完接同轨下一段）。
            if (nextIdx <= carry.segLast) {
                val nc = carry.copy(index = nextIdx)
                android.util.Log.i(
                    "PlayerDiag",
                    "splitNext 段内: ${carry.index} → $nextIdx 区间=[${carry.segStart},${carry.segLast}]",
                )
                android.util.Log.w("PlayerDiag", "replaceMediaItem @splitNext pos=${c.currentPosition}")
                c.replaceMediaItem(c.currentMediaItemIndex, SplitCarry.itemFor(item, nc))
                seekToAbsolute(SplitCarry.startMsOf(nc))
                c.play()
                return
            }
            android.util.Log.i(
                "PlayerDiag",
                "splitNext 已到区间上界: idx=${carry.index} last=${carry.segLast} → 转队列下一首",
            )
            // 已经是本条最后一首 → 落到下面的常规"下一个队列条目"逻辑
        }
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
        } else if (c.mediaItemCount > 0) {
            // End of queue: wrap to the first item, like the prototype's modulo step.
            c.seekTo(0, 0L)
        }
        c.play()
    }

    fun prev() {
        gapJob?.cancel()
        val c = controller ?: return
        val carry = currentCarry()
        val item = c.currentMediaItem
        if (carry != null && item != null) {
            val cur = carry.current
            // 与 setMaxSeekToPreviousPositionMs(4000) 的既有语义一致：
            // 本曲已播过 3 秒以上 → 回本曲开头；否则 → 上一首的起点。
            if (cur != null && c.currentPosition - relOf(cur.startMs) > 3000L) {
                seekToAbsolute(cur.startMs)
                c.play()
                return
            }
            val prevIdx = carry.index - 1
            // v1.4.0: 只在**本条区间内**回退。已在区间起点 → 落到下面的 c.seekToPrevious()
            // （队列上一首）。此前用 coerceAtLeast(0)，导致拆分条目第一首永远只重播本曲、
            // 与 next() 的"到上界转队列"不对称（台账第 17 条）。
            if (prevIdx >= carry.segStart) {
                android.util.Log.i(
                    "PlayerDiag",
                    "splitPrev 段内: ${carry.index} → $prevIdx 区间=[${carry.segStart},${carry.segLast}]",
                )
                android.util.Log.w("PlayerDiag", "replaceMediaItem @splitPrev pos=${c.currentPosition}")
                c.replaceMediaItem(c.currentMediaItemIndex, SplitCarry.itemFor(item, carry.copy(index = prevIdx)))
                seekToAbsolute(SplitCarry.startMsOf(carry.copy(index = prevIdx)))
                c.play()
                return
            }
            android.util.Log.i(
                "PlayerDiag",
                "splitPrev 已到区间起点: idx=${carry.index} start=${carry.segStart} → 转队列上一首",
            )
        }
        c.seekToPrevious()
    }

    // ── v22: 显示坐标 ────────────────────────────────────────────────────────
    // 拆分模式下 player.positionMs / durationMs 是**整轨文件**的坐标（可能 45 分钟），
    // 而播放页进度条、迷你条、时间文字都该显示"当前这首"的坐标。下面三个函数是 UI
    // 唯一该用的入口；歌词本不需要改（歌词按整轨文件的绝对时间戳存，跨边界继续走反而
    // 更自然）。

    /** 当前虚拟曲目的起点（整轨文件坐标）；非拆分模式为 0。 */
    val virtualStartMs: Long get() = currentCarry()?.current?.startMs ?: 0L

    /** 当前虚拟曲目的时长；非拆分模式为 0。 */
    val virtualDurationMs: Long get() = currentCarry()?.current?.durationMs ?: 0L

    /** 进度条/时间显示用的位置（相对当前曲目）。 */
    fun displayPositionMs(): Long =
        if (virtualDurationMs > 0L) (positionMs - virtualStartMs).coerceAtLeast(0L) else positionMs

    /** 进度条/时间显示用的时长。 */
    fun displayDurationMs(): Long =
        if (virtualDurationMs > 0L) virtualDurationMs else durationMs

    /** 拖进度条：把"当前曲目内的比例"映射回整轨文件坐标。 */
    fun seekToDisplayFraction(fraction: Float) {
        val c = controller ?: return
        val t = currentCarry()?.current
        if (t == null) {
            seekToFraction(fraction)
            return
        }
        val f = fraction.coerceIn(0f, 1f)
        val target = t.startMs + ((t.endMs - t.startMs) * f).toLong()
        // v1.3.10 诊断(A23):seek 留痕(用户实测"拖到 1:38 就正常"→ 需要知道 seek 落在哪)
        android.util.Log.i(
            "PlayerDiag",
            "seek displayFraction=$f → file=$target (段=[${t.startMs},${t.endMs}) 段内=${(target - t.startMs)})",
        )
        seekToAbsolute(target)
        c.play()
    }

    fun seekToFraction(fraction: Float) {
        val c = controller ?: return
        val dur = if (c.duration > 0) c.duration else durationMs
        if (dur > 0) c.seekTo((fraction.coerceIn(0f, 1f) * dur).toLong())
    }

    fun seekToMs(ms: Long) {
        if (seekToAbsolute(ms)) controller?.play()
    }

    // ── v1.5.0 P2 spike 专用（随 spike 一起删）──────────────────────────────
    // 真值测试文件不在曲库里，需要一个直接播本地文件的入口，免得每换一个测试文件都要
    // 推曲库 + 重扫 + 在列表里翻。仅 spike 分支存在，正式功能不依赖。

    /** 直接播放一个本地测试文件（`file://` URI）。 */
    fun playSpikeFile(uri: android.net.Uri, title: String) {
        val c = controller ?: return
        c.setMediaItem(
            androidx.media3.common.MediaItem.Builder()
                .setUri(uri)
                .setMediaId("spike-file")
                .setTag(title)
                .build(),
        )
        c.prepare()
        c.play()
    }

    /** 起「从头采集」：置待命旗标 → 回到 0 → 播放（处理器在落到开头时开始采 PCM）。 */
    fun spikeCaptureFromStart() {
        val c = controller ?: return
        KaraokeAudioSpike.captureArmed = true
        c.seekTo(0L)
        c.play()
    }

    /** 扫描中的"暂停恢复"覆盖用（暂停本身不产生 flush，但恢复后每个 buffer 仍会被时间戳锚定）。 */
    fun spikePause() {
        controller?.pause()
    }

    /**
     * **spike 专用**：按"**当前曲目内**的毫秒"定位，内部换算成整轨文件绝对坐标后走
     * [seekToAbsolute]（唯一 seek 漏斗 ⇒ 锚点一定会被写）。
     *
     * 为什么必须有它：[seekToMs] 收的是**整轨文件**坐标。拆分单曲的条目被裁剪过、播放器时间轴
     * 从裁剪点起算，拿文件坐标去 seek 会算出负数 → 被夹到段首 → 越界检查判"越界" → **跳到别的曲目**。
     * 普通曲目 [virtualStartMs] = 0，行为与 [seekToMs] 完全相同。
     */
    fun spikeSeekWithinTrack(ms: Long): Boolean = seekToAbsolute(virtualStartMs + ms)

    fun spikeResume() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    /**
     * v23: 按**当前曲目内的时间**定位（拆分单曲会自动映射回整轨文件坐标）。
     *
     * 与 [seekToMs] 的区别就是坐标系:[seekToMs] 收的是**整轨文件**的绝对毫秒,
     * 而歌词时间戳、进度条显示的时长都是**当前曲目**坐标系。
     *
     * 修的是一个真实事故:拆分专辑里点击歌词行会"退出播放并跳到专辑第一首"——
     * 点击处理按 [displayDurationMs] 算出的是曲目内坐标(如 30s),却被当成文件坐标
     * 交给 [seekToMs],于是 seek 到整轨文件第 30 秒。那个位置落在该单曲区间**之前**,
     * 播放服务的虚拟边界检查判定"越界"→ 切到包含第 30 秒的那首(即第一首),
     * 连带把刚导入、还没时间戳的歌词"弄没了"(其实只是换了曲目)。
     * 普通单曲两个坐标系重合,所以此前没暴露。
     */
    fun seekToDisplayMs(ms: Long) {
        val c = controller ?: return
        val t = currentCarry()?.current
        if (t == null) {
            c.seekTo(ms.coerceAtLeast(0))
            c.play()
            return
        }
        val span = (t.endMs - t.startMs).coerceAtLeast(0L)
        val target = t.startMs + ms.coerceIn(0L, span)
        android.util.Log.i("PlayerDiag", "seek displayMs=$ms → file=$target (段=[${t.startMs},${t.endMs}))")
        seekToAbsolute(target)
        c.play()
    }

    fun toggleShuffle() {
        shuffleFlag = !shuffleFlag
        controller?.shuffleModeEnabled = shuffleFlag && queueKey == null
    }

    fun setShuffle(on: Boolean) {
        shuffleFlag = on
        controller?.shuffleModeEnabled = on && queueKey == null
    }

    fun cycleRepeat() {
        setRepeat(
            when (repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            },
        )
    }

    /** v1.3.8: 直接设定循环模式(Agent 的 playback_mode 技能用)——cycleRepeat 只能轮流切,
     *  用户说"单曲循环"时必须能一步到位,不能连点两次碰运气。 */
    fun setRepeat(mode: Int) {
        repeatMode = mode
        controller?.repeatMode = mode
    }

    /** v1.5.0 伴唱 §8：升降 Key，单位是**半音步数**（+1 步 = +0.5 半音），见 [KeyShift]。 */
    private var keyShiftHalfSteps: Int = 0
    private var lastSpeed: Float = 1.0f
    private var lastRetroMode: Boolean = true

    /**
     * v2: 播放速度调节。retroMode=true 时 pitch 随 speed 联动（复古磁带效果）；
     * retroMode=false 时 pitch 固定 1.0（现代变速不变调）。
     */
    fun setPlaybackSpeed(speed: Float, retroMode: Boolean) {
        lastSpeed = speed
        lastRetroMode = retroMode
        applyPlaybackParameters()
    }

    /** v1.5.0 伴唱 §8：设置升降 Key（**半音步数**，+1 步 = +0.5 半音）。只改音高，速度不动。 */
    fun setKeyShift(halfSteps: Int) {
        keyShiftHalfSteps = KeyShift.clamp(halfSteps)
        applyPlaybackParameters()
    }

    private fun applyPlaybackParameters() {
        val c = controller ?: return
        c.playbackParameters = androidx.media3.common.PlaybackParameters(
            lastSpeed,
            KeyShift.totalPitch(lastSpeed, lastRetroMode, keyShiftHalfSteps),
        )
    }

    fun currentSpeed(): Float = controller?.playbackParameters?.speed ?: 1.0f

    /**
     * v5.2: timed sleep mode (mode 1). Counts down the chosen minutes, then
     * fades out on an exponential curve and pauses. Passing minutes <= 0
     * cancels any active sleep mode and restores full volume.
     */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        fadeJob?.cancel()
        sleepFading = false
        endOfTrackFadeStarted = false
        sleepChosenMin = minutes
        if (minutes <= 0) {
            sleepLeftSec = 0
            sleepMode = 0
            try { controller?.volume = 1f } catch (_: Exception) { }
            return
        }
        sleepMode = 1
        sleepLeftSec = minutes * 60L
        sleepJob = scope.launch {
            while (isActive && sleepLeftSec > 0) {
                delay(1000)
                sleepLeftSec--
            }
            if (isActive && sleepLeftSec <= 0) {
                fadeOutAndPause(30000L)
            }
        }
    }

    /**
     * v5.2: end-of-track sleep mode (mode 2). The ticker watcher ramps
     * volume down over the last ~20% (clamped 6..20s) of the track and
     * pauses before the track reaches its end, so no auto-advance fires.
     * A fallback in onMediaItemTransition catches the edge case (very
     * short track / missed poll / unknown duration) where the track ends
     * before the fade can catch it.
     */
    fun setSleepEndOfTrack() {
        sleepJob?.cancel()
        fadeJob?.cancel()
        sleepFading = false
        endOfTrackFadeStarted = false
        sleepChosenMin = 0
        sleepLeftSec = 0
        sleepMode = 2
        try { controller?.volume = 1f } catch (_: Exception) { }
    }

    /**
     * v5.2: Spotify-style fade-out — ramp volume from 1.0 down to 0 on a
     * perceptually-even EXPONENTIAL curve. Loudness is logarithmic, so a
     * linear amplitude ramp sounds like it lingers then drops off a cliff;
     * `Math.pow(0.02, progress)` decays evenly to ~0.02 (-34dB) at the end,
     * which reads as a constant-rate "exit" to the ear. After the fade:
     * mute, pause, restore full volume (so next manual play isn't silent),
     * reset mode.
     *
     * Uses `controller.volume` (PLAYER_COMMAND_SET_DEVICE_VOLUME) so the ramp
     * is smooth and audible-controlled.
     */
    private fun fadeOutAndPause(fadeMs: Long = 30000L) {
        val c = controller ?: run {
            sleepLeftSec = 0
            sleepMode = 0
            sleepFading = false
            endOfTrackFadeStarted = false
            return
        }
        fadeJob?.cancel()
        sleepFading = true
        fadeJob = scope.launch {
            val steps = 60
            val stepMs = (fadeMs / steps).coerceAtLeast(40L)
            for (i in 0 until steps) {
                if (!isActive) return@launch
                val progress = (i + 1).toFloat() / steps
                val vol = Math.pow(0.02, progress.toDouble()).toFloat()
                try { c.volume = vol.coerceIn(0f, 1f) } catch (_: Exception) { }
                delay(stepMs)
            }
            try { c.volume = 0f } catch (_: Exception) { }
            c.pause()
            // restore to full so next manual play isn't silent
            try { c.volume = 1f } catch (_: Exception) { }
            sleepLeftSec = 0
            sleepMode = 0
            endOfTrackFadeStarted = false
            sleepFading = false
        }
    }

    /**
     * v5.2: single source of truth for the sleep-timer status label shown in
     * the player menu and the settings row.
     */
    fun sleepStatusText(): String {
        if (sleepMode == 2) return "本曲结束后"
        if (sleepFading) return "淡出中…"
        if (sleepLeftSec > 0) {
            val h = sleepLeftSec / 3600
            val m = ((sleepLeftSec % 3600) + 59) / 60
            return if (h > 0) "剩余 ${h}小时${m}分" else "剩余 ${m}分钟"
        }
        return "未开启"
    }

    /**
     * v5.2 Bug1: app-side facade that forwards the user's device selection to
     * the PlaybackService via the custom ROUTE_DEVICE SessionCommand. The
     * service resolves the address back to an AudioDeviceInfo and applies
     * `MediaCodecAudioRenderer.MSG_SET_PREFERRED_AUDIO_DEVICE` to the
     * underlying ExoPlayer — true in-app audio routing, no system-panel
     * detour. [address] = the BT MAC ("xx:xx:...") or "" to clear routing
     * back to the built-in speaker. Delivery is fire-and-forget: the service
     * callback returns afresh and the UI updates via DeviceRouter flow.
     */
    fun requestDeviceRouting(address: String) {
        val c = controller ?: return
        val args = Bundle().apply {
            putString(PlaybackService.KEY_ADDRESS, address)
        }
        val cmd = SessionCommand(PlaybackService.ACTION_ROUTE_DEVICE, args)
        try {
            // Custom commands are only valid against the registered set, so
            // no-op if the controller hasn't finished connecting — the next
            // call after connection succeeds will re-send.
            c.sendCustomCommand(cmd, args)
        } catch (_: Exception) { }
    }

    // ── v1.3.x 播放设置(音量均衡/单声道/同时播放/均衡器)──────────────────────
    /** 最近一次下发的播放设置——READY 后补发用(audioSessionId 就绪前服务端只记不生效)。 */
    private var lastAudioSettings: Bundle? = null
    /** 幂等守卫用的"上次已下发"快照:值没变就**不重复下发**。
     *  设置流在每次 DataStore 写入都会发射(播放一首歌就会写 recentIds!),若无条件
     *  下发,服务端会反复 setAudioAttributes → **反复重申请音频焦点** → 刚起的播放
     *  被打断(实测"点播放播不动"的根因)。 */
    private var lastSentAudioKey: String? = null

    /** app 侧 facade:把「播放」页设置下发到服务进程的 ExoPlayer 上生效。 */
    fun applyAudioSettings(
        normalize: Boolean,
        mono: Boolean,
        concurrent: Boolean,
        equalizerOn: Boolean,
        equalizerBands: List<Int> = emptyList(),
    ) {
        val key = "$normalize|$mono|$concurrent|$equalizerOn|${equalizerBands.joinToString(",")}"
        if (key == lastSentAudioKey) return
        val args = Bundle().apply {
            putBoolean(PlaybackService.KEY_VOLUME_NORMALIZE, normalize)
            putBoolean(PlaybackService.KEY_MONO_AUDIO, mono)
            putBoolean(PlaybackService.KEY_ALLOW_CONCURRENT, concurrent)
            putBoolean(PlaybackService.KEY_EQUALIZER, equalizerOn)
            putIntArray(PlaybackService.KEY_EQ_BANDS, equalizerBands.toIntArray())
        }
        lastAudioSettings = args
        if (sendAudioSettings(args)) lastSentAudioKey = key
    }

    /** @return true = 命令已发出(用于幂等快照只在真正送达时更新)。 */
    private fun sendAudioSettings(args: Bundle): Boolean {
        val c = controller ?: return false
        val cmd = SessionCommand(PlaybackService.ACTION_AUDIO_SETTINGS, args)
        return try {
            c.sendCustomCommand(cmd, args)
            true
        } catch (_: Exception) { false }
    }
}

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
    private var onTrackStarted: ((Long) -> Unit)? = null

    // v1.2.1: 纯逻辑的"有效播放"计数追踪器(Spotify 式 30 秒阈值)。Session 生命周期、
    // seek 剔除、暂停/恢复累计都在 tracker 内部,可用纯 JVM 单测覆盖;本类每拍只喂数据。
    private val playTracker = PlaySessionTracker().apply {
        onCounted = { id -> onPlayCounted?.invoke(id) }
        onFinalize = { id, sec -> onPlayFinalized?.invoke(id, sec) }
    }
    /** v1.2.1: 累计有效播放满 30 秒时触发(把该次播放计为一次有效播放)。 */
    private var onPlayCounted: ((Long) -> Unit)? = null
    /** v1.2.1: 切歌/播完时触发,回传该次播放的累计有效秒数(写 playedSec)。 */
    private var onPlayFinalized: ((Long, Int) -> Unit)? = null

    fun connect(
        context: Context,
        onStarted: (Long) -> Unit,
        onPlayCounted: ((Long) -> Unit)? = null,
        onPlayFinalized: ((Long, Int) -> Unit)? = null,
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

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId?.toLongOrNull()
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
                manualQueue.removeAll { it.id == id }
                startTracking(id)
                onTrackStarted?.invoke(id)
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
        }
    }

    private fun syncFromPlayer() {
        val c = controller ?: return
        currentId = c.currentMediaItem?.mediaId?.toLongOrNull()
        isPlaying = c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.mediaItemCount > 0
        repeatMode = c.repeatMode
        val dur = c.duration
        if (dur > 0) durationMs = dur
        positionMs = c.currentPosition.coerceAtLeast(0)
        // v1.2.1: 重连/进程恢复时若 tracker 尚无活跃 session(如服务仍在播但控制器是新建的),
        // 以当前曲 start 一个新 session 并补插一条 play_event 行(onTrackStarted)。补插行很关键:
        // 否则该 session 在内存里有累计、DB 里却无对应行,后续 markPlayCounted/finalizePlayEvent
        // 的子查询会命中该 mediaId 的历史旧行,污染历史数据并可能错记计数。重启前已播时长
        // 无法恢复(accumMs 从 0 起),但后续播放会继续累计,满 30s 仍会计数。
        if (playTracker.currentMediaId() == null && currentId != null) {
            playTracker.start(currentId!!)
            onTrackStarted?.invoke(currentId!!)
        }
    }

    private var tickerStarted = false
    private fun startTicker() {
        if (tickerStarted) return
        tickerStarted = true
        scope.launch {
            while (isActive) {
                controller?.let { c ->
                    positionMs = c.currentPosition.coerceAtLeast(0)
                    val dur = c.duration
                    if (dur > 0) durationMs = dur
                    // v1.2.1: 累计当前曲目有效播放时长,满 30 秒计一次有效播放。
                    playTracker.sample(positionMs, c.playWhenReady, c.playbackState == Player.STATE_READY)
                    // v5.2: end-of-track sleep mode — kick the fade once we're
                    // inside the last ~20% (clamped 6..20s) of the track.
                    if (sleepMode == 2 && !endOfTrackFadeStarted && dur > 0) {
                        val fadeMs = (dur * 0.2f).toLong().coerceIn(6000L, 20000L)
                        if (positionMs >= dur - fadeMs) {
                            endOfTrackFadeStarted = true
                            sleepFading = true
                            fadeOutAndPause(fadeMs)
                        }
                    }
                }
                delay(300)
            }
        }
    }

    /** v1.2.1: 新曲目开始播放时调用——tracker.start 会先 finalize 当前 session(回填
     *  上一首/上一轮 playedSec),再为新曲开新 session。单曲循环"同 mediaId 新一轮"也
     *  走这里:上一轮被 finalize、counted 重置,第二轮可独立计数。 */
    private fun startTracking(id: Long) {
        playTracker.start(id)
    }

    /** v1.2.1: 队列末尾播完(STATE_ENDED,无自动推进)时 finalize 当前 session 回填 playedSec。
     *  也供 MainViewModel.onCleared 在进程关停(sliding-away)时 flush,避免暂停离开丢时长。 */
    internal fun finalizeCurrent() {
        playTracker.finalize()
    }

    private fun buildItem(t: Track): MediaItem = MediaItem.Builder()
        .setMediaId(t.id.toString())
        .setUri(t.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setArtworkUri(
                    ContentUris.withAppendedId(
                        android.net.Uri.parse("content://media/external/audio/albumart"), t.albumId,
                    )
                )
                .build()
        )
        .build()

    /** v2.0: push album art bitmap data to the current MediaItem's metadata so
     *  the system MediaSession (notification pill, lock screen, quick settings)
     *  can display the cover art. Uses setArtworkData (byte[]) instead of
     *  setArtworkUri (content://) because the system MediaController doesn't
     *  have permission to read arbitrary content:// URIs.
     *  v5: [forMediaId] pins the update to a specific track so that if the user
     *  skipped past it before the (slow) bitmap load landed, we don't stamp the
     *  old track's cover onto the now-playing track's metadata. */
    fun updateArtworkData(data: ByteArray, forMediaId: Long? = null) {
        val c = controller ?: return
        val idx = c.currentMediaItemIndex
        if (idx < 0 || idx >= c.mediaItemCount) return
        val item = c.getMediaItemAt(idx)
        if (forMediaId != null && item.mediaId.toLongOrNull() != forMediaId) return
        val newItem = item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setArtworkData(data, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
            )
            .build()
        c.replaceMediaItem(idx, newItem)
    }

    /** Starts playback of [tracks] at [startId]. [key] null = normal queue. */
    fun playQueue(tracks: List<Track>, startId: Long, key: String?) {
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
        c.setMediaItems(tracks.map(::buildItem), index, 0L)
        // Pending manual-queue items survive a context switch (v1.7 semantics).
        manualQueue.removeAll { it.id == startId }
        manualQueue.forEachIndexed { i, t -> c.addMediaItem(index + 1 + i, buildItem(t)) }
        c.shuffleModeEnabled = shuffleFlag && key == null
        c.prepare()
        c.play()
        currentId = startId
        queueVersion++
        startTracking(tracks[index].id)
        onTrackStarted?.invoke(tracks[index].id)
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
        c.addMediaItem(insertAt, buildItem(track))
        manualQueue.add(track)
        queueVersion++
    }

    data class QueueEntry(val id: Long, val index: Int, val isManual: Boolean)

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
        val pos = c.currentPosition
        val wasPlaying = c.playWhenReady
        c.setMediaItems(tracks.map(::buildItem), index, pos)
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
        controller?.seekToPrevious()
    }

    fun seekToFraction(fraction: Float) {
        val c = controller ?: return
        val dur = if (c.duration > 0) c.duration else durationMs
        if (dur > 0) c.seekTo((fraction.coerceIn(0f, 1f) * dur).toLong())
    }

    fun seekToMs(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
        controller?.play()
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
        val next = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        repeatMode = next
        controller?.repeatMode = next
    }

    /**
     * v2: 播放速度调节。retroMode=true 时 pitch 随 speed 联动（复古磁带效果）；
     * retroMode=false 时 pitch 固定 1.0（现代变速不变调）。
     */
    fun setPlaybackSpeed(speed: Float, retroMode: Boolean) {
        val c = controller ?: return
        val pitch = if (retroMode) speed else 1.0f
        c.playbackParameters = androidx.media3.common.PlaybackParameters(speed, pitch)
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
}

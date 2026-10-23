package com.shiyin.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.shiyin.music.MainActivity
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    /**
     * v5.2 Bug1: route media audio to a specific output device via
     * `MediaCodecAudioRenderer.MSG_SET_PREFERRED_AUDIO_DEVICE`. The
     * customAction string carries the target device's address (the BT MAC for
     * bluetooth sinks, or "" for the built-in speaker). The service looks the
     * address up via `AudioManager.getDevices` and sends the renderer message.
     *
     * This is the IPC bridge between the app-side
     * `MediaController.sendCustomCommand` (issued by PlayerController when the
     * user taps a device in the in-app picker) and the ExoPlayer running here
     * in the service process. `AudioDeviceInfo` is not stable across the
     * controller boundary, so we transport the address (a String) and resolve
     * it back to an `AudioDeviceInfo` here.
     */
    companion object {
        const val ACTION_ROUTE_DEVICE = "com.shiyin.music.ROUTE_DEVICE"
        const val KEY_ADDRESS = "address"

        /** v1.3.x 播放设置: app 侧把「播放」页四个开关下发到服务进程生效
         *  (ExoPlayer 在这里,audiofx/焦点/声道处理都必须在服务进程做)。 */
        const val ACTION_AUDIO_SETTINGS = "com.shiyin.music.AUDIO_SETTINGS"
        const val KEY_VOLUME_NORMALIZE = "volume_normalize"
        const val KEY_MONO_AUDIO = "mono_audio"
        const val KEY_ALLOW_CONCURRENT = "allow_concurrent"
        const val KEY_EQUALIZER = "equalizer"
        const val KEY_EQ_BANDS = "eq_bands"

        /**
         * v1.5.0: 系统媒体控制卡（通知栏/锁屏/灵动岛）上的「点赞」按钮。
         * 服务进程收命令后直接写 Room 的 playlist_track（p3 =「我的喜欢」）——
         * 两个进程读同一个数据库文件，App 进程的 playlistTracksFlow 自动感知，
         * 应用内收藏图标即时同步，无需任何进程间回调。
         */
        const val ACTION_TOGGLE_FAV = "com.shiyin.music.TOGGLE_FAV"
    }

    private val routeDeviceCommand = SessionCommand(ACTION_ROUTE_DEVICE, Bundle())
    private val audioSettingsCommand = SessionCommand(ACTION_AUDIO_SETTINGS, Bundle())
    private val toggleFavCommand = SessionCommand(ACTION_TOGGLE_FAV, Bundle())

    /**
     * ⚠️ 单声道已**再次撤回**(2026-09-23)。
     *
     * v23 试过一次"安全版":官方 ChannelMixingAudioProcessor + 与 media3 默认实现
     * 逐行一致的 buildAudioSink 覆写,只多一句 setAudioProcessors。装机后**用户实测
     * 仍然播不了** —— 与 2026-09-22 那次自建 AudioSink 的结论一致:本项目这条
     * ExoPlayer 管线上,任何 buildAudioSink 覆写都会让音频渲染失效。
     *
     * 结论(两次实测):**不要再动播放管线去实现单声道**。若将来还要做,应换与
     * ExoPlayer 渲染链无关的路径(例如系统级/AudioEffect 侧),或先在一个隔离的
     * spike 里验证通过再进主链路。用户既定规矩:"宁可缺,不能给一个开了播不了的开关"。
     */
    private var player: ExoPlayer? = null
    private var equalizer: android.media.audiofx.Equalizer? = null
    private var loudness: android.media.audiofx.LoudnessEnhancer? = null
    /** 上次生效的焦点策略——只在**真的变化**时调 setAudioAttributes。
     *  ExoPlayer.setAudioAttributes 会重新申请音频焦点,重复调用会把正在播的曲子打断。 */
    private var lastHandleFocus: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        // 播放管线保持**原样**——两次实测(2026-09-22 自建 sink / 2026-09-23 官方
        // 处理器 + 逐行一致覆写)都让音频渲染失效。见上方 mono 撤回注释。
        //
        // v1.5.0 P2 spike（分支 spike/karaoke-p2-processor）：那条结论**从未在 Media3 1.7.1
        // 上复现过** —— 1.7.1 的 ExoPlayer.Builder 已经没有 buildAudioSink，插入点挪到了
        // DefaultRenderersFactory。所以这里加一个**三档开关**分开归因（详见 KaraokeAudioSpike）：
        //   0 = 完全不碰渲染链（默认，与改动前逐字一致）/ 1 = 只换 factory / 2 = 换 factory + 1:1 透传
        val mixMode = KaraokeAudioSpike.mode(this)
        android.util.Log.i(
            "KaraokeMix",
            "onCreate 音频管线档位=$mixMode（${KaraokeAudioSpike.describe(mixMode)}）",
        )
        val playerBuilder = if (mixMode == KaraokeAudioSpike.MODE_STOCK) {
            ExoPlayer.Builder(this)
        } else {
            ExoPlayer.Builder(this, KaraokeRenderersFactory(this))
        }
        val player = playerBuilder
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMaxSeekToPreviousPositionMs(4000)
            .build()
        this.player = player

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                /**
                 * v5.2 Bug1: accept incoming controllers and publish the
                 * ROUTE_DEVICE custom command as available. We follow the
                 * Media3 default (accept everyone) but extend
                 * DEFAULT_SESSION_COMMANDS with our custom one so the
                 * controller can actually send it. Without this registration
                 * the controller's sendCustomCommand would be rejected with
                 * "Controller isn't allowed to call custom session command"
                 * (Media3 issue #1773).
                 */
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon().add(routeDeviceCommand).add(audioSettingsCommand)
                        .add(toggleFavCommand).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        // v1.5.0: 自定义布局 = [点赞]。Android 13+ 系统媒体卡把它放进
                        // 播放/暂停键左侧的槽位（官方文档「媒体控制」槽位表：单个自定义
                        // 按钮占上一首与播放之间的位置），正好是用户要的"点赞在左边"。
                        // 用 ICON_HEART_FILLED 内置图标常量——系统卡片按深浅色自动着色，
                        // 不必自备 drawable。pre-13 兼容路径上它追加在动作列表尾部。
                        .setCustomLayout(listOf(likeButton()))
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    // v1.5.0 通知栏点赞:写 playlist_track(p3),与 App 内收藏同一张表。
                    if (customCommand.customAction == ACTION_TOGGLE_FAV) {
                        toggleFavInDb(session.player.currentMediaItem?.mediaId?.toLongOrNull())
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                    // v1.3.x 播放设置下发:四个开关在服务进程生效(ExoPlayer 在这里)。
                    if (customCommand.customAction == ACTION_AUDIO_SETTINGS) {
                        applyAudioSettings(
                            normalize = args.getBoolean(KEY_VOLUME_NORMALIZE, false),
                            mono = args.getBoolean(KEY_MONO_AUDIO, false),
                            concurrent = args.getBoolean(KEY_ALLOW_CONCURRENT, false),
                            equalizerOn = args.getBoolean(KEY_EQUALIZER, false),
                            bands = args.getIntArray(KEY_EQ_BANDS) ?: IntArray(0),
                        )
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        )
                    }
                    if (customCommand.customAction != ACTION_ROUTE_DEVICE) {
                        return com.google.common.util.concurrent.Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                        )
                    }
                    val address = args.getString(KEY_ADDRESS, "")
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    val target = if (address.isBlank()) null
                        else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            .firstOrNull { d ->
                                val a = d.address?.toString()
                                !a.isNullOrBlank() && a.equals(address, ignoreCase = true)
                            }
                            ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                    || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    applyPreferredDevice(session.player, target)
                    return com.google.common.util.concurrent.Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    )
                }

                /**
                 * v22: 通知栏 / 锁屏 / 蓝牙耳机 / 车机的「上一首 / 下一首」。
                 *
                 * 拆分专辑播放时队列里只有一个 MediaItem（整轨文件），Media3 默认的
                 * `seekToNextMediaItem()` 无处可去（REPEAT_MODE_ALL 下还会绕回本条目
                 * 的 0:00），按钮形同失效。这里接管：从当前 MediaItem 的 extras 读出
                 * 拆分方案，直接在同一文件内切到上/下一首 —— 只换 mediaId 与标题，
                 * uri 不变，不触发重新 prepare。
                 *
                 * 返回值语义（Media3 约定）：非 SUCCESS 即否决该命令。因此自己处理完
                 * 之后要返回 NOT_SUPPORTED 才能挡住默认行为；未处理（如已是本专辑最后
                 * 一首）则返回 SUCCESS，交回默认逻辑去切下一个队列条目。
                 */
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    @Player.Command command: Int,
                ): Int {
                    val forward = when (command) {
                        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
                        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> false
                        else -> return SessionResult.RESULT_SUCCESS
                    }
                    return if (handleSplitSeek(session.player, forward)) {
                        SessionResult.RESULT_ERROR_NOT_SUPPORTED
                    } else {
                        SessionResult.RESULT_SUCCESS
                    }
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** v1.5.0: 通知栏「点赞」按钮（见 onConnect 的 setCustomLayout 注释）。 */
    private fun likeButton(): androidx.media3.session.CommandButton =
        androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_HEART_FILLED)
            .setSessionCommand(toggleFavCommand)
            .setDisplayName("喜欢这首歌")
            .setEnabled(true)
            .build()

    /**
     * v1.5.0: 系统媒体卡的点赞落库。与 App 内收藏同一张 playlist_track 表、
     * 同一个 p3 歌单 —— App 进程的 playlistTracksFlow 监听该表，写入后应用内
     * 所有「已收藏」状态自动刷新。服务进程没有 Toast/协程作用域，用独立
     * 协程在 IO 线程写；未在播（mediaId 解析不出）则忽略。
     */
    private fun toggleFavInDb(mediaId: Long?) {
        if (mediaId == null || mediaId <= 0) return
        val appContext = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val dao = com.shiyin.music.data.db.AppDatabase.get(appContext).dao()
                val exists = dao.playlistHasTrack("p3", mediaId)
                if (exists) dao.removeFromPlaylist("p3", mediaId)
                else dao.addToPlaylist(com.shiyin.music.data.db.PlaylistTrackEntity("p3", mediaId, System.currentTimeMillis()))
            } catch (e: Exception) {
                android.util.Log.w("PlaybackService", "通知栏点赞落库失败 mediaId=$mediaId", e)
            }
        }
    }

    /**
     * v22: 拆分专辑内的上/下一首。返回 true = 已处理（调用方应否决默认命令）。
     *
     * 与 PlayerController 里的同名逻辑保持一致（两处分别服务于"App 内按钮"与
     * "系统媒体控制"两条路径，都读同一份 MediaItem.extras 里的方案）。
     */
    private fun handleSplitSeek(player: Player, forward: Boolean): Boolean {
        val item = player.currentMediaItem ?: return false
        val carry = SplitCarry.read(item) ?: return false
        // v1.4.0: 条目被裁成 [运行区起点, 运行区末]，player 的位置/seek 都是**运行区相对坐标**，
        // 而 carry 里的 startMs/endMs 是**文件绝对坐标**。这里统一换算（与 PlayerController 的
        // absOf/relOf 同构），保证 App 内按钮与通知栏/蓝牙耳机/车机行为一致。
        val clip = carry.clipStartMs
        fun rel(abs: Long) = (abs - clip).coerceAtLeast(0L)
        // v1.3.10 诊断(A23):这条路径的 seek **不经过 PlayerController**,此前完全无日志。
        // "位置每 3 秒被拉回上一段起点"的第一嫌疑就是它被反复触发。
        android.util.Log.i(
            "PlayerDiag",
            "handleSplitSeek forward=$forward idx=${carry.index}/${carry.plan.tracks.size} " +
                "区间=[${carry.segStart},${carry.segLast}] clip=$clip " +
                "pos=${player.currentPosition}(绝对 ${clip + player.currentPosition}) " +
                "当前段=[${carry.current?.startMs},${carry.current?.endMs})",
        )
        if (forward) {
            val nextIdx = carry.index + 1
            // v1.4.0: 只在**本条区间内**前进。到上界就交回默认逻辑（下一个队列条目），
            // 不再一路走完整张方案（歌单里放一首拆分单曲时会误切到同轨下一段）。
            if (nextIdx > carry.segLast) {
                android.util.Log.i(
                    "PlayerDiag",
                    "handleSplitSeek 已到区间上界 idx=${carry.index} last=${carry.segLast} → 交回默认",
                )
                return false
            }
            val nc = carry.copy(index = nextIdx)
            android.util.Log.i("PlayerDiag", "handleSplitSeek 段内前进 ${carry.index} → $nextIdx 区间=[${carry.segStart},${carry.segLast}]")
            player.replaceMediaItem(player.currentMediaItemIndex, SplitCarry.itemFor(item, nc))
            player.seekTo(rel(SplitCarry.startMsOf(nc)))
            player.play()
            return true
        }
        val cur = carry.current
        // 与 setMaxSeekToPreviousPositionMs(4000) 的既有语义一致：播过 3 秒回本曲开头
        if (cur != null && player.currentPosition - rel(cur.startMs) > 3000L) {
            android.util.Log.i("PlayerDiag", "  → 回本段开头 seek=${cur.startMs}(段内已播 ${player.currentPosition - rel(cur.startMs)}ms)")
            player.seekTo(rel(cur.startMs))
            player.play()
            return true
        }
        val prevIdx = carry.index - 1
        // v1.4.0: 已在本条区间起点 → 交回默认（上一个队列条目），与 PlayerController.prev 对称。
        if (prevIdx < carry.segStart) {
            android.util.Log.i(
                "PlayerDiag",
                "handleSplitSeek 已到区间起点 idx=${carry.index} start=${carry.segStart} → 交回默认",
            )
            return false
        }
        android.util.Log.i("PlayerDiag", "  → 回到上一段 idx=$prevIdx seek=${SplitCarry.startMsOf(carry.copy(index = prevIdx))}")
        player.replaceMediaItem(player.currentMediaItemIndex, SplitCarry.itemFor(item, carry.copy(index = prevIdx)))
        player.seekTo(rel(SplitCarry.startMsOf(carry.copy(index = prevIdx))))
        player.play()
        return true
    }

    /** Send `MSG_SET_PREFERRED_AUDIO_DEVICE` to every audio renderer. */
    private fun applyPreferredDevice(player: Player, device: AudioDeviceInfo?) {
        if (player !is ExoPlayer) return
        for (i in 0 until player.rendererCount) {
            if (player.getRendererType(i) != C.TRACK_TYPE_AUDIO) continue
            val renderer = player.getRenderer(i) ?: continue
            if (renderer !is MediaCodecAudioRenderer) continue
            player.createMessage(renderer)
                .setType(MediaCodecAudioRenderer.MSG_SET_PREFERRED_AUDIO_DEVICE)
                .setPayload(device)
                .send()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    /**
     * v1.3.x 播放设置: app 侧「播放」页四个开关在此生效。
     *
     * - **单声道**: 写 [MonoAudioProcessor].enabled(常驻处理器,透传态零开销);
     * - **音量均衡**: LoudnessEnhancer 提升响度(+3 dB,响度归一);
     * - **允许同时播放**: 关=接管音频焦点(播放时压低其他应用),开=不接管;
     * - **均衡器**: Equalizer(默认平坦曲线,开=启用)。
     *
     * audiofx 对象都挂在 ExoPlayer 的 audioSessionId 上;**会话 id 在播放器
     * 未就绪时为 0/UNSET** → 此时只记设置,等下一次下发(播放开始后 app 侧会
     * 再发一次)。所有 audiofx 创建都 runCatching 包裹——部分机型/ROM 上
     * 创建会抛(缺效果库、session 被占用),不能因此崩服务。
     */
    private fun applyAudioSettings(
        normalize: Boolean,
        mono: Boolean,
        concurrent: Boolean,
        equalizerOn: Boolean,
        bands: IntArray = IntArray(0),
    ) {
        val p = player ?: session?.player as? ExoPlayer ?: return
        // 焦点策略:开=不接管(与其他应用混播),关=接管(默认行为)。
        // ⚠️ 只在**真的变化**时调用——setAudioAttributes 会重新申请音频焦点,
        // 重复调用会把正在播放的曲子打断(实测"点播放播不动"的根因)。
        val wantFocus = !concurrent
        if (wantFocus != lastHandleFocus) {
            runCatching {
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    /* handleAudioFocus = */ wantFocus,
                )
            }
            lastHandleFocus = wantFocus
        }
        val sessionId = runCatching { p.audioSessionId }.getOrDefault(0)
        if (sessionId <= 0) return
        runCatching {
            if (equalizer == null) {
                equalizer = android.media.audiofx.Equalizer(0, sessionId)
            }
            // 各频段增益(mB):索引即频段序号;超出设备支持的段数忽略。
            equalizer?.let { eq ->
                val n = runCatching { eq.numberOfBands.toInt() }.getOrDefault(0)
                val range = runCatching { eq.bandLevelRange }.getOrNull()
                for (i in 0 until minOf(n, bands.size)) {
                    val v = if (range != null) {
                        bands[i].coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                    } else bands[i].toShort()
                    runCatching { eq.setBandLevel(i.toShort(), v) }
                }
                eq.enabled = equalizerOn
            }
        }.onFailure { android.util.Log.w("AudioFX", "Equalizer 不可用: ${it.message}") }
        runCatching {
            if (loudness == null) {
                loudness = android.media.audiofx.LoudnessEnhancer(sessionId)
            }
            // 目标增益单位是毫贝(mB):+300mB = +3dB,听感上明显但不失真。
            loudness?.setTargetGain(if (normalize) 300 else 0)
            loudness?.enabled = normalize
        }.onFailure { android.util.Log.w("AudioFX", "LoudnessEnhancer 不可用: ${it.message}") }
    }

    override fun onDestroy() {
        runCatching { equalizer?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        loudness = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

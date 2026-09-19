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
    }

    private val routeDeviceCommand = SessionCommand(ACTION_ROUTE_DEVICE, Bundle())

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
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
                        .buildUpon().add(routeDeviceCommand).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
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

    /**
     * v22: 拆分专辑内的上/下一首。返回 true = 已处理（调用方应否决默认命令）。
     *
     * 与 PlayerController 里的同名逻辑保持一致（两处分别服务于"App 内按钮"与
     * "系统媒体控制"两条路径，都读同一份 MediaItem.extras 里的方案）。
     */
    private fun handleSplitSeek(player: Player, forward: Boolean): Boolean {
        val item = player.currentMediaItem ?: return false
        val carry = SplitCarry.read(item) ?: return false
        if (forward) {
            val nextIdx = carry.index + 1
            if (nextIdx >= carry.plan.tracks.size) return false   // 已是最后一首 → 交回默认
            val nc = carry.copy(index = nextIdx)
            player.replaceMediaItem(player.currentMediaItemIndex, SplitCarry.itemFor(item, nc))
            player.seekTo(SplitCarry.startMsOf(nc))
            player.play()
            return true
        }
        val cur = carry.current
        // 与 setMaxSeekToPreviousPositionMs(4000) 的既有语义一致：播过 3 秒回本曲开头
        if (cur != null && player.currentPosition - cur.startMs > 3000L) {
            player.seekTo(cur.startMs)
            player.play()
            return true
        }
        val prevIdx = (carry.index - 1).coerceAtLeast(0)
        if (prevIdx != carry.index) {
            player.replaceMediaItem(player.currentMediaItemIndex, SplitCarry.itemFor(item, carry.copy(index = prevIdx)))
        }
        player.seekTo(SplitCarry.startMsOf(carry.copy(index = prevIdx)))
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

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

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
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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

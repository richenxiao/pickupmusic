package com.shiyin.music.playback

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothA2dp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Reports the REAL media-output state via public Android APIs (API 31+).
 *
 * ⚠️  THE BIG PICTURE
 * Android's public API for THIRD-PARTY apps does NOT expose a method to
 * programmatically route media playback to a specific output device.
 *   - `setCommunicationDevice` is voice-only (no effect on music).
 *   - `setPreferredDeviceForStrategy` is a hidden @SystemApi.
 *
 * Therefore the app relies on the **system media output switcher** (available
 * in the media notification, volume panel, and quick settings). This is the
 * same mechanism Spotify uses on Android.
 *
 * What this class DOES provide:
 *   1. Active-device detection via [getAudioDevicesForAttributes] — the
 *      actual device USAGE_MEDIA is routed to RIGHT NOW.
 *   2. Device enumeration via [getDevices(GET_DEVICES_OUTPUTS)].
 *   3. A reactive [deviceChanges] flow that syncs when connections change.
 *   4. Dynamic icon kind mapping for the UI.
 *
 * All data comes directly from AudioManager — no synthetic entries, no
 * hardcoded "builtin" device.
 */
class DeviceRouter(private val context: Context) {

    data class DeviceInfo(
        val id: String,          // stable id from "dev:<AudioDeviceInfo.id>"
        val name: String,
        val sub: String,
        val kind: String,        // "phone" | "headphone" | "bluetooth" | "tv"
        val native: AudioDeviceInfo? = null,
        /** Bluetooth MAC for BT devices (used for sticky-device re-routing).
         *  Empty for non-bluetooth sinks. */
        val address: String = "",
    )

    /** id of the device media IS routed to right now (null until first refresh). */
    var activeDeviceId by mutableStateOf<String?>(null); private set

    /** All real output devices, enumerated straight from AudioManager. */
    var availableDevices by mutableStateOf<List<DeviceInfo>>(emptyList()); private set

    /**
     * Sticky-address preference for media routing — the user's last manually
     * selected Bluetooth device's MAC. v5.2: Bug 1 option (b). When the same
     * BT device reconnects (its address reappears in availableDevices), we
     * reapply the routing to it instead of falling back to the speaker.
     * Persisted in SharedPreferences so it survives cold start. Cleared only
     * when the user explicitly picks a different device.
     */
    var stickyAddress by mutableStateOf("")
        private set

    private val prefs by lazy { context.getSharedPreferences("device_router", Context.MODE_PRIVATE) }

    private fun loadSticky() {
        stickyAddress = prefs.getString("sticky_addr", "").orEmpty()
    }

    private fun saveSticky(addr: String) {
        stickyAddress = addr
        prefs.edit().putString("sticky_addr", addr).apply()
    }

    private val mediaAttributes =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

    private val am: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init { loadSticky() }

    /** Re-read the actual routing state for USAGE_MEDIA playback. */
    fun refreshActiveDevice() {
        val routed = try {
            am.getAudioDevicesForAttributes(mediaAttributes)
        } catch (_: Exception) {
            emptyList()
        }
        // Prefer a non-builtin output (headphone / BT / TV) when available;
        // fall back to the built-in speaker.
        val chosen = routed.firstOrNull { it.isSink && kindOf(it) != "phone" }
            ?: routed.firstOrNull { it.isSink }
            ?: builtinSpeaker()
        activeDeviceId = chosen?.let { idOf(it) }
    }

    /**
     * v1.2.0 #9: 延迟再刷一次活跃设备。系统 Output Switcher 返回后（ON_RESUME）
     * 音频路由可能尚未就绪——即时 [refreshActiveDevice] 会拿到切换前的旧值，
     * 表现为「切回本机后播放页仍显示蓝牙」。这里延迟重读，让音频栈落定后再取
     * 真实路由。与 [deviceChanges] 里 350ms defer 同源（均为路由滞后补偿）。
     * 切回本机不触发 BT 断连、deviceChanges 不 fire，故 ON_RESUME 是唯一触发点。
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    fun refreshActiveDeviceSoon(delayMs: Long = 500L) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ refreshActiveDevice() }, delayMs)
    }

    /** Enumerate every real output device in the system. */
    fun refreshDeviceList() {
        val out = ArrayList<DeviceInfo>()
        val seen = HashSet<Int>()
        val seenBuiltinProductName = HashSet<String>()   // Bug1: collapse phantom
                                                            // builtin-speaker duplicate
                                                            // rows that share productName
                                                            // with the real primary speaker
        // Capture the canonical primary speaker productName up-front — any OTHER
        // sink whose productName matches AND whose kind is NOT phone is treated
        // as a phantom of the builtin speaker and dropped. This catches the
        // Huawei "PKG110蓝牙设备" + "PKG110本机扬声器" + "HUAWEI SOUND" case where
        // the system mirrors the speaker productName onto a bogus BT entry.
        val primarySpeakerName = builtinSpeaker()?.productName?.toString()
        try {
            for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                val kind = kindOf(d)
                if (kind == "ignore") continue
                if (!seen.add(d.id)) continue
                // Bug1: drop phantom BT-shaped entries that mirror the builtin
                // speaker's productName — these are not real sinks. Wait only
                // for kinds != "phone" so we keep the genuine speaker row.
                if (primarySpeakerName != null && kind != "phone") {
                    val pn = d.productName?.toString()
                    if (pn != null && pn == primarySpeakerName) continue
                }
                out += deviceInfoOf(d)
            }
            // Guarantee the built-in speaker is always present, but deduped to
            // exactly one "phone" row. Some Huawei devices expose the speaker
            // under multiple AudioDeviceInfo entries (e.g. a normal speaker +
            // a phantom BT-shaped "PKG110蓝牙设备" entry whose productName
            // happens to match a paired BT alias). The kindOf whitelist below
            // already filters earpiece/mic/etc.; here we additionally collapse
            // any duplicate builtin-speaker rows down to the first one.
            val phones = out.filter { it.kind == "phone" }
            if (phones.isEmpty()) {
                builtinSpeaker()?.let { out.add(0, deviceInfoOf(it)) }
            } else if (phones.size > 1) {
                // keep first, drop the rest
                val firstPhone = phones.first()
                val pruned = ArrayList<DeviceInfo>(out.size)
                var keptFirst = false
                for (d in out) {
                    if (d.kind == "phone") {
                        if (!keptFirst) { pruned.add(d); keptFirst = true }
                    } else pruned.add(d)
                }
                out.clear(); out.addAll(pruned)
                // touch firstPhone so kotlin doesn't warn unused
                @Suppress("UNUSED_EXPRESSION") firstPhone
            }
            // Bug1 dedupe by productName across non-phone sinks too — same
            // productName appearing twice (e.g. "PKG110" speaker + "PKG110"
            // BT phantom that slipped past the primarySpeakerName filter
            // because primarySpeakerName was null on this device) collapses
            // to the first one. Keeps the real Bluetooth entry ("HUAWEI
            // SOUND") which has a distinct productName.
            if (primarySpeakerName == null && out.size > 1) {
                val pruned = ArrayList<DeviceInfo>(out.size)
                val seenNames = HashSet<String>()
                for (d in out) {
                    val key = if (d.kind == "bluetooth") d.address else d.name
                    if (key.isNotEmpty() && !seenNames.add(key)) continue
                    pruned.add(d)
                }
                if (pruned.isNotEmpty()) {
                    out.clear(); out.addAll(pruned)
                }
            }
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT not granted yet — BT devices just won't appear.
        }
        availableDevices = out
        refreshActiveDevice()
        // Bug1 option (b): sticky-restore. If the user previously selected a BT
        // device by address and that device is currently in the list, reapply
        // routing to it. The PlaybackService consumes `pendingRoutingAddress`
        // via the SessionCommand path; here we just flag what should be sent.
        applyStickyIfPresent()
    }

    /**
     * Bug1 option (b): if [stickyAddress] is set and a device whose address
     * matches appears in `availableDevices`, flag [pendingRoutingAddress] so
     * PlaybackService can re-send the preferred-device message after BT
     * reconnect. No-op when the sticky device isn't currently connected (the
     * system falls back to the built-in speaker automatically).
     */
    private fun applyStickyIfPresent() {
        val addr = stickyAddress.takeIf { it.isNotBlank() } ?: return
        val match = availableDevices.firstOrNull { it.address == addr }
        pendingRoutingAddress = match?.address
    }

    /**
     * Bug1: a pending address that PlaybackService should re-apply. Read &
     * cleared through [consumePendingRoutingAddress]. Set by [selectDevice]
     * (explicit user choice) and by [applyStickyIfPresent] (auto-restore on
     * reconnect).
     */
    @Volatile private var pendingRoutingAddress: String? = null
    fun consumePendingRoutingAddress(): String? {
        val addr = pendingRoutingAddress
        pendingRoutingAddress = null
        return addr
    }

    /**
     * Bug1 option (b): explicit user selection of a device by address. Stores
     * the address as the sticky preference (survives cold start, auto-restores
     * on reconnect) and flags [pendingRoutingAddress] so PlaybackService will
     * route media to it on the next command round-trip. Pass "" or null to
     * clear the preference and return to the built-in speaker.
     */
    fun selectDevice(address: String?) {
        if (address.isNullOrBlank()) {
            saveSticky("")
            pendingRoutingAddress = null
        } else {
            saveSticky(address)
            pendingRoutingAddress = address
        }
    }

    /**
     * Active device's icon category, for dynamic icon selection.
     * Falls back to "phone" (smartphone icon) when no device is active yet.
     */
    fun activeKind(): String = availableDevices
        .firstOrNull { it.id == activeDeviceId }?.kind ?: "phone"

    /**
     * Emits on device / connection changes so the UI stays in sync.
     * The system output switcher (media notification / volume panel) is the
     * actual switching mechanism; this flow just mirrors the real state.
     *
     * Bug1 fix: previously we only listened to the top-level BluetoothAdapter
     * ACL broadcasts and the generic DEVICE_CHANGED intent. On many devices
     * (esp. Huawei), the audio routing flips BEFORE the ACL disconnect fires,
     * so the UI showed BT-icon for several seconds after the user walked out
     * of range. We now also subscribe to the profile-specific connection
     * state broadcasts (A2DP + Headset) and **defer the refresh by 350ms** so
     * the audio stack has time to settle before re-reading
     * `getAudioDevicesForAttributes` — otherwise the still-disconnecting sink
     * is reported as the active output.
     */
    fun deviceChanges(): Flow<String> = callbackFlow {
        val mainHandler = Handler(Looper.getMainLooper())
        val refreshSoon = {
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({
                refreshDeviceList()
                // Bug1: send the pending routing address (if any) so the UI
                // reflects the sticky restore decision. Falls back to the
                // activeDeviceId otherwise. PlaybackService picks this up via
                // the SessionCommand path separately.
                val pending = consumePendingRoutingAddress()
                if (pending != null) trySend("addr:$pending") else activeDeviceId?.let { trySend(it) }
            }, 350)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                refreshSoon()
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.media.action.DEVICE_CHANGED")
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            // Profile-specific connection state is the authoritative signal
            // for A2DP / Headset audio routing changes.
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (_: SecurityException) {
            close()
            return@callbackFlow
        }
        awaitClose {
            mainHandler.removeCallbacksAndMessages(null)
            context.unregisterReceiver(receiver)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun idOf(d: AudioDeviceInfo) = "dev:${d.id}"

    private fun deviceInfoOf(d: AudioDeviceInfo): DeviceInfo {
        val kind = kindOf(d)
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: btName(d)
            ?: when (kind) {
                "phone" -> "本机扬声器"
                "headphone" -> "有线耳机"
                "bluetooth" -> "蓝牙设备"
                else -> "输出设备"
            }
        val sub = when (kind) {
            "phone" -> "本机扬声器"
            "headphone" -> "有线耳机"
            "bluetooth" -> "蓝牙设备"
            "tv" -> "HDMI / 底座"
            else -> "输出设备"
        }
        // Bug1: capture BT MAC for sticky routing. Only BT sinks have a real
        // address (the wired/headphone built-in entry reports null/empty).
        val addr = if (kind == "bluetooth") {
            d.address?.toString()?.takeIf { it.isNotBlank() }.orEmpty()
        } else ""
        return DeviceInfo(id = idOf(d), name = name, sub = sub, kind = kind, native = d, address = addr)
    }

    /** A2DP devices often report an empty productName — backfill from the bonded
     *  Bluetooth device's name via its address. */
    private fun btName(d: AudioDeviceInfo): String? {
        val addr = d.address?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices
                ?.firstOrNull { it.address.equals(addr, ignoreCase = true) }?.name
        } catch (_: SecurityException) { null }
    }

    private fun builtinSpeaker(): AudioDeviceInfo? = try {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    } catch (_: SecurityException) { null }

    private fun kindOf(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "headphone"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_DOCK -> "tv"
        else -> "ignore"   // earpiece, mic, submix, etc. — not a media sink
    }
}
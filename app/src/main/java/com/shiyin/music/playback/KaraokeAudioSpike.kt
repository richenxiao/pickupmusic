package com.shiyin.music.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.io.File
import java.nio.ByteBuffer

/**
 * v1.5.0 P2 spike（分支 `spike/karaoke-p2-processor`）· **只做验证，不进正式功能**。
 *
 * 目标路线：**单 ExoPlayer + 自定义 `AudioProcessor`，在音频管线内按源帧序号混合**
 * （原曲档直通 / 伴奏档输出缓存伴奏 / 人声档逐采样 `原曲 − 伴奏`，切档 300ms 交叉淡化）。
 * 但在混合之前必须先回答一个问题：**这条管线到底能不能插处理器？**
 *
 * 背景：2026-09-23 试过一次（官方 `ChannelMixingAudioProcessor` + 逐行一致的 sink 覆写），
 * 装机后播不了 —— 代码里写成「任何 `buildAudioSink` 覆写都会让音频渲染失效」
 * （`PlaybackService.kt:58-69` / `SettingsScreens.kt:1561`）。但那次实验有**两个可分的混杂因素**：
 *
 *  1. **Media3 版本差异**：2026-10-12 用 javap 查得 **Media3 1.7.1 的 `ExoPlayer.Builder`
 *     根本没有 `buildAudioSink` 方法** —— 插入点已挪到
 *     `DefaultRenderersFactory.buildAudioSink(Context, boolean, boolean)`。当年覆写的那个方法
 *     在现在的版本里不存在，所以"当年那次失败"**不能直接外推到今天的代码**。
 *  2. **处理器自身的配置**：`ChannelMixingAudioProcessor.onConfigure` 的字节码是
 *     `matrix = matrixByInputChannelCount.get(inputFormat.channelCount);
 *      if (matrix == null) throw new UnhandledAudioFormatException(...)`
 *     —— **没给该采样格式的实际声道数注册混音矩阵就直接抛异常**。处理器在 configure 抛异常 →
 *     `DefaultAudioSink.configure()` 返回 false → **整条音频输出配不起来 = 一点声音都没有**，
 *     与当年"播不了"的症状完全一致。也就是说那更可能是一次**处理器配置错误**，而不是管线不可用。
 *
 * 所以本 spike 用一个**格式不变的 1:1 透传处理器**（一个字节都不改）来单独检验"管线能不能插"，
 * 并用一个三档开关把三个假设分开归因：
 *
 * | [MODE] | 条件 | 能区分什么 |
 * |---|---|---|
 * | `0` | **完全不换** factory（原样默认） | 基线：确认设备/版本本身正常 |
 * | `1` | 换 factory，但 `buildAudioSink` 里**不挂** processor | 换 RenderersFactory 本身有没有副作用 |
 * | `2` | 换 factory + 挂 1:1 透传 processor | **processor 机制本身能不能用**（本次要问的） |
 *
 * 默认 `0`（基线）—— **不会把用户正在用的播放搞坏**。
 *
 * ### 三档开关怎么切
 * `getExternalFilesDir(null)/karaoke-mix-mode` 里写 `0`/`1`/`2`（文件不存在 = 0）。可由 adb 直接改，
 * 不必重装：
 * ```
 * adb shell "echo 2 > /sdcard/Android/data/com.shiyin.music/files/karaoke-mix-mode"
 * adb shell "echo 0 > /sdcard/Android/data/com.shiyin.music/files/karaoke-mix-mode"
 * adb shell rm       /sdcard/Android/data/com.shiyin.music/files/karaoke-mix-mode
 * ```
 * ⚠️ **改档后必须在播放器重建时才生效**（ExoPlayer 在 `PlaybackService.onCreate` 里建一次）——
 * 杀掉 App 重进即可。
 */
/** logcat tag，本文件三个类共用（`adb logcat -s KaraokeMix`）。 */
private const val TAG = "KaraokeMix"

object KaraokeAudioSpike {

    private const val MODE_FILE = "karaoke-mix-mode"

    /** 基线：完全不碰渲染链。 */
    const val MODE_STOCK = 0
    /** 只换 RenderersFactory，不挂处理器。 */
    const val MODE_FACTORY_ONLY = 1
    /** 换 factory + 挂 1:1 透传处理器。 */
    const val MODE_PASSTHROUGH = 2

    private fun modeFile(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, MODE_FILE) }

    /** 读当前档位；文件缺失/非法一律回落到基线，**绝不因为开关读不出来就把播放弄坏**。 */
    fun mode(context: Context): Int = runCatching {
        val f = modeFile(context) ?: return MODE_STOCK
        if (!f.isFile) return MODE_STOCK
        f.readText().trim().toIntOrNull()?.coerceIn(0, 2) ?: MODE_STOCK
    }.getOrDefault(MODE_STOCK)

    /** 供调试面板写档位（spike 用）。 */
    fun setMode(context: Context, mode: Int): Boolean = runCatching {
        val f = modeFile(context) ?: return false
        f.parentFile?.mkdirs()
        f.writeText(mode.coerceIn(0, 2).toString())
        true
    }.getOrDefault(false)

    fun describe(mode: Int): String = when (mode) {
        MODE_STOCK -> "0 · 基线（不碰渲染链）"
        MODE_FACTORY_ONLY -> "1 · 只换 RenderersFactory（不挂处理器）"
        else -> "2 · 换 factory + 1:1 透传处理器"
    }
}

/**
 * **格式不变的 1:1 透传处理器**：输入什么就吐什么，一个字节不改。
 *
 * 为什么故意什么都不做：这样"能播/不能播"的结论才干净 —— 若它挂了，就是这条管线/这个机制的问题，
 * 与"混合算法写错了"无关。后续步骤 2/3 才往里加帧号计数与混合。
 *
 * ⚠️ **缓冲契约（本次专门用 javap 反汇编核实过，写错就会完全没声音——正好是 v23 的症状）**：
 *  - `BaseAudioProcessor.getOutput()` **只是把 `outputBuffer` 交出去，不会替你 `flip()`**；
 *  - `replaceOutputBuffer(size)` 在**复用**旧缓冲时走 `clear()` → `limit = capacity`（**可能大于本次 size**）；
 *  - Media3 自己的处理器（`ToInt16PcmAudioProcessor`、`ChannelMixingAudioProcessor`）
 *    都是填完**显式 `flip()`**。漏掉 → 新建缓冲时 `remaining()==0`（全静音）、
 *    复用较大缓冲时是静音+垃圾（"杂音"）。
 */
class KaraokeMixProcessor : BaseAudioProcessor() {

    /** configure 时的输入格式，供后续步骤的帧号换算用（本步只记录）。 */
    var inputSampleRate: Int = 0; private set
    var inputChannelCount: Int = 0; private set

    /** 流过的输入帧数（步骤 2 的帧号对拍要拿它当计数器）。 */
    var framesSeen: Long = 0L; private set

    var flushCount: Int = 0; private set
    private var bytesPerFrame = 0
    /** 心跳节流：上一次打日志时的"秒数"。 */
    private var heartbeatSec = -1

    /**
     * 只接 PCM 16bit / float（用户步骤 4 定的边界）。其它格式**抛
     * `UnhandledAudioFormatException` 让 Media3 明确报"不支持"**，而不是静默透传一个
     * 它其实处理不了的格式。
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val bytes = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputSampleRate = inputAudioFormat.sampleRate
        inputChannelCount = inputAudioFormat.channelCount
        bytesPerFrame = inputAudioFormat.channelCount * bytes
        framesSeen = 0
        flushCount = 0
        Log.i(
            TAG,
            "onConfigure in=${inputAudioFormat.sampleRate}Hz/${inputAudioFormat.channelCount}ch/" +
                "enc=${inputAudioFormat.encoding} → 1:1 透传（bytesPerFrame=$bytesPerFrame）",
        )
        // 返回**同一个** AudioFormat：输出格式与输入完全一致 = 真正的 1:1
        return inputAudioFormat
    }

    /**
     * 强制 true：本处理器的意义就是**让音频真的从它中间过一遍**。若让 Media3 判定"未激活"而跳过它，
     * 这个 spike 就什么都没验到（`queueInput` 不会被调用）。
     */
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return
        if (bytesPerFrame > 0) framesSeen += size / bytesPerFrame
        // 每约 1 秒音频打一次心跳，用来**证明音频确实穿过了这里**（而不是被旁路）。
        if (framesSeen / maxOf(1, inputSampleRate) > heartbeatSec) {
            heartbeatSec = (framesSeen / maxOf(1, inputSampleRate)).toInt()
            Log.i(TAG, "透传心跳：已过 ${framesSeen} 帧（≈${heartbeatSec}s）flush=$flushCount")
        }
        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        // ⚠️ 必须 flip —— 见类注释的缓冲契约
        out.flip()
    }

    /** `flush()` 只在**不连续**（seek / 换源）时被调用，且**不告诉你跳到哪了** —— 步骤 2 的核心难点。 */
    override fun onFlush() {
        flushCount++
        Log.i(TAG, "onFlush #$flushCount（此前已过 $framesSeen 帧）——flush 后帧号必须由 app 侧重新锚定")
    }

    override fun onReset() {
        Log.i(TAG, "onReset（释放）")
        inputSampleRate = 0
        inputChannelCount = 0
        bytesPerFrame = 0
        framesSeen = 0
        flushCount = 0
        heartbeatSec = -1
    }
}

/**
 * 只覆写 [buildAudioSink] 的 `DefaultRenderersFactory`。
 *
 * 为什么是这里而不是 `ExoPlayer.Builder`：Media3 **1.7.1 的 `ExoPlayer.Builder` 没有
 * `buildAudioSink`**（javap 核实），插入点在这个 factory 上。
 *
 * `buildAudioSink` 的实现**刻意与 Media3 默认逐行一致，只多一句 `setAudioProcessors`** ——
 * 任何其它偏离都会成为新的混杂因素，让"挂了到底是谁的锅"说不清。
 */
class KaraokeRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    private val appContext = context.applicationContext

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val mode = KaraokeAudioSpike.mode(appContext)
        val base = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        return if (mode == KaraokeAudioSpike.MODE_PASSTHROUGH) {
            Log.i("KaraokeMix", "buildAudioSink：挂 1:1 透传处理器（mode=2）")
            base.setAudioProcessors(arrayOf(KaraokeMixProcessor())).build()
        } else {
            Log.i("KaraokeMix", "buildAudioSink：不挂处理器（mode=$mode）")
            base.build()
        }
    }
}

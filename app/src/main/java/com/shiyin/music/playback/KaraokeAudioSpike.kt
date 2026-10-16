package com.shiyin.music.playback

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.shiyin.music.data.karaoke.StemCache
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import kotlin.math.abs

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
 *     `DefaultRenderersFactory.buildAudioSink(Context, boolean, boolean)`。
 *  2. **处理器自身的配置**：`ChannelMixingAudioProcessor.onConfigure` 只要没给该声道数注册矩阵就
 *     直接抛 `UnhandledAudioFormatException` → sink 配不起来 → 完全静音。那更可能是一次
 *     **处理器配置错误**，而不是管线不可用。
 *
 * 四档开关（把三个假设分开归因）：
 *
 * | [MODE] | 条件 | 能区分什么 |
 * |---|---|---|
 * | `0` | **完全不换** factory（原样默认） | 基线：确认设备/版本本身正常 |
 * | `1` | 换 factory，但 `buildAudioSink` 里**不挂** processor | 换 RenderersFactory 本身有没有副作用 |
 * | `2` | 换 factory + 挂 1:1 透传 processor | **processor 机制本身能不能用** |
 * | `3` | 档 2 + 外面再包一层 `ForwardingAudioSink` | **时间戳锚定**（每个 buffer 拿 `presentationTimeUs`） |
 *
 * 默认 `0`（基线）—— **不会把用户正在用的播放搞坏**。
 *
 * ### 开关怎么切
 * `getExternalFilesDir(null)/karaoke-mix-mode` 里写 `0`/`1`/`2`/`3`（文件不存在 = 0）：
 * ```
 * adb shell "echo 3 > /sdcard/Android/data/com.shiyin.music/files/karaoke-mix-mode"
 * adb shell "echo 0 > /sdcard/Android/data/com.shiyin.music/files/karaoke-mix-mode"
 * ```
 * ⚠️ **改档后必须在播放器重建时才生效**（ExoPlayer 在 `PlaybackService.onCreate` 里建一次）——
 * 杀掉 App 重进即可。
 *
 * ### 探针开关（步骤 2b/2c）
 * `getExternalFilesDir(null)/karaoke-mix-probe` **的内容**决定探针模式：
 * | 内容 | 模式 | 用途 |
 * |---|---|---|
 * | 文件不存在 | 关 | — |
 * | 空 / `1` / `fn` | 帧号真值 | 从音频数据里解出绝对帧号（`make_framenum_fixture.py` 生成的文件） |
 * | `2` / `click` | 咔哒 | 找缓冲内首个 `|采样|>阈值` 的偏移（**有损格式**：MP3 只能这么测） |
 * | `3` / `capture` | PCM 采集 | 把处理器收到的 PCM 原样落盘（端到端对齐用的互相关底料） |
 * ```
 * adb shell "echo fn      > /sdcard/Android/data/com.shiyin.music/files/karaoke-mix-probe"
 * adb shell "echo click   > /sdcard/Android/data/com.shiyin.music/files/karaoke-mix-probe"
 * adb shell "echo capture > /sdcard/Android/data/com.shiyin.music/files/karaoke-mix-probe"
 * adb shell rm            /sdcard/Android/data/com.shiyin.music/files/karaoke-mix-probe
 * ```
 */
/** logcat tag，本文件几个类共用（`adb logcat -s KaraokeMix`）。 */
private const val TAG = "KaraokeMix"

/** 探针：flush 之后逐块打印几个 buffer（最先几个最能看出重锚对不对）。 */
private const val PROBE_POST_FLUSH = 5

/**
 * sink 诊断（闸门 1 准备项 a）：**换条目 / seek / configure 之后**详打几个 buffer。
 * 边界附近的头几块才是有信息量的（能看到新条目的 pts 起点）；稳态每块都打会淹掉日志。
 */
private const val BUFFER_LOGS_AT_BOUNDARY = 5

/** 咔哒检测阈值：咔哒幅度 0.5×32767≈16383，帧号真值文件只有 ±2048 ⇒ 6000 两边都安全。 */
private const val CLICK_THRESHOLD = 6000

/** 采集模式默认采多少秒（端到端互相关只要开头一段就够）。 */
private const val CAPTURE_SECONDS = 10

object KaraokeAudioSpike {

    private const val MODE_FILE = "karaoke-mix-mode"
    /** 真值探针开关文件（**内容**决定模式）。只在档 2/3（挂处理器）下有意义。 */
    private const val PROBE_FILE = "karaoke-mix-probe"

    /** 基线：完全不碰渲染链。 */
    const val MODE_STOCK = 0
    /** 只换 RenderersFactory，不挂处理器。 */
    const val MODE_FACTORY_ONLY = 1
    /** 换 factory + 挂 1:1 透传处理器。 */
    const val MODE_PASSTHROUGH = 2
    /** 档 2 + 外层 `ForwardingAudioSink`：**用每个 buffer 的 `presentationTimeUs` 锚定**。 */
    const val MODE_ANCHORED = 3
    /** 档 3 + **按系数混音**（步骤 3：`out = g_o·原曲 + g_a·伴奏`，切换 300ms 系数插值）。 */
    const val MODE_MIX = 4

    /** 探针关闭。 */
    const val PROBE_OFF = 0
    /** 帧号真值（无损文件，`make_framenum_fixture.py`）。 */
    const val PROBE_FN = 1
    /** 咔哒（有损文件，`--mode click`）。 */
    const val PROBE_CLICK = 2
    /** 采集处理器收到的 PCM（端到端互相关）。 */
    const val PROBE_CAPTURE = 3

    private fun modeFile(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, MODE_FILE) }

    private fun probeFile(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, PROBE_FILE) }

    /** 读当前档位；文件缺失/非法一律回落到基线，**绝不因为开关读不出来就把播放弄坏**。 */
    /**
     * 读当前档位。
     *
     * ⚠️ **2026-10-14 起：文件不存在时默认 = [MODE_ANCHORED]（处理器常驻）**，不再是 0。
     * 原因（外部复核 + 用户 2026-10-14 拍板）：正式设计是**处理器常驻在播放链路里、原曲档原样通过**，
     * 点伴唱只切**内部模式**、**不重建播放器**（重建会打断正在播的歌）。
     * 文件仍可作为**调试覆盖**（0/1/2 用于 A/B 与"无处理器逐位对比"）。
     * 文件缺失/非法一律回落到常驻 —— 常驻态在原曲档下与无处理器**逐位一致**（有单测钉住）。
     */
    fun mode(context: Context): Int = runCatching {
        val f = modeFile(context) ?: return MODE_ANCHORED
        if (!f.isFile) return MODE_ANCHORED
        f.readText().trim().toIntOrNull()?.coerceIn(0, MODE_MIX) ?: MODE_ANCHORED
    }.getOrDefault(MODE_ANCHORED)

    /** 供调试面板写档位（spike 用）。 */
    fun setMode(context: Context, mode: Int): Boolean = runCatching {
        val f = modeFile(context) ?: return false
        f.parentFile?.mkdirs()
        f.writeText(mode.coerceIn(0, MODE_MIX).toString())
        true
    }.getOrDefault(false)

    /**
     * **运行期**探针模式（调试面板改档后不必重进 App）。
     *
     * 为什么需要：探针模式原先在 `buildAudioSink` 时被读进处理器构造参数，而**换歌不会重建
     * 音频 sink** ⇒ 一个 App 会话里只能测一种模式（要测 MP3 咔哒就得杀 App 重进）。
     * 全自动扫描要在一个会话里同时跑「帧号真值」和「咔哒」两类文件，所以改成处理器每次
     * 都能读到的最新值。UI 线程写、audio 线程读。
     */
    @Volatile
    var liveProbeMode: Int = PROBE_OFF

    /**
     * 读探针模式。**空文件 = 帧号真值**（向后兼容 2b 之前 `touch` 出来的开关）。
     */
    fun probeMode(context: Context): Int = runCatching {
        val f = probeFile(context) ?: return PROBE_OFF
        if (!f.isFile) return PROBE_OFF
        when (f.readText().trim().lowercase()) {
            "", "1", "fn" -> PROBE_FN
            "2", "click" -> PROBE_CLICK
            "3", "capture" -> PROBE_CAPTURE
            else -> PROBE_FN
        }
    }.getOrDefault(PROBE_OFF).also { liveProbeMode = it }

    /** 供调试面板写探针模式（spike 用）。**立即生效**（处理器读 [liveProbeMode]）。 */
    fun setProbeMode(context: Context, m: Int): Boolean = runCatching {
        val f = probeFile(context) ?: return false
        f.parentFile?.mkdirs()
        when (m.coerceIn(PROBE_OFF, PROBE_CAPTURE)) {
            PROBE_OFF -> f.delete()
            PROBE_FN -> f.writeText("fn")
            PROBE_CLICK -> f.writeText("click")
            else -> f.writeText("capture")
        }
        liveProbeMode = m.coerceIn(PROBE_OFF, PROBE_CAPTURE)
        true
    }.getOrDefault(false)

    fun probeEnabled(context: Context): Boolean = probeMode(context) != PROBE_OFF

    /**
     * 采集「从头开始」的待命旗标（步骤 2c 端到端对齐）。
     *
     * 用法：面板置 true → 把当前曲目 seek 到 0 并播放 → 处理器**只在落到文件开头 0.5s 内**的
     * 那个 buffer 上开始采集（不依赖 flush：同一位置反复 seek 时 ExoPlayer 可能不发 flush）。
     * UI 线程写、audio 线程读，故 `@Volatile`。
     */
    @Volatile
    var captureArmed: Boolean = false

    /** 给人看的说法——不要在界面上出现"档位 / factory / 透传"这类黑话。 */
    fun describe(mode: Int): String = when (mode) {
        MODE_STOCK -> "不启用 —— 与改动前完全一样"
        MODE_FACTORY_ONLY -> "只换输出组件 —— 换了音频输出这一环，但不加任何处理"
        MODE_PASSTHROUGH -> "启用处理 —— 把伴唱用的处理器接进播放链路"
        MODE_ANCHORED -> "启用处理 + 时间戳锚定 —— 每个数据块都用播放器时间戳对位"
        else -> "启用混音 —— 原曲 / 伴奏 / 人声 三档，切换时 300ms 系数插值"
    }

    fun describeProbe(mode: Int): String = when (mode) {
        PROBE_OFF -> "关闭"
        PROBE_FN -> "帧号真值（无损测试文件）"
        PROBE_CLICK -> "咔哒（有损格式，如 MP3）"
        else -> "采集 PCM（端到端对齐用）"
    }
}

/**
 * v1.5.0 P2 spike 步骤 2b：**app 侧 → 处理器**的「本次 seek 请求的目标位置」投递口。
 *
 * 为什么需要：探针能读出"flush 后第一个采样的真实帧号"，但**没有基准可比** ——
 * 要回答「重锚准不准」，必须知道 **app 请求的是哪个位置**。`PlayerController.seekToAbsolute`
 * 是唯一 seek 入口，在那里写；处理器在 `flush()` 里读。
 *
 * 服务与 app **同进程**（清单里没有 `android:process`）⇒ 进程内 `@Volatile` 就够。
 * **只写两个 volatile 字段，不改变任何播放行为**；档位 0 时处理器不在链上，这两句等于空转。
 *
 * ⚠️ spike 专用：随 spike 一起删。
 */
object KaraokeSeekAnchor {
    /** 递增序号：用来把"哪几次 flush 属于同一次 seek"分开。 */
    @Volatile var seq: Int = -1
        private set

    /** 请求的**绝对**位置（ms）。 */
    @Volatile var absMs: Long = -1L
        private set

    /** 请求在**当前媒体条目内**的相对位置（ms）——对拍用这个。 */
    @Volatile var relMs: Long = -1L
        private set

    fun set(abs: Long, rel: Long) {
        absMs = abs
        relMs = rel
        seq += 1
    }
}

/**
 * v1.5.0 P2 spike 步骤 2c：**播放链路 → 处理器**的「本 buffer 的播放器时间戳」投递口。
 *
 * 为什么需要：2b 实测「app 请求的位置」与「真实落点」最多差 ~100ms（会被对齐到解码缓冲边界），
 * 拿请求位置当锚点会直接造成伴奏/原曲错位。**每个 buffer 的 `presentationTimeUs` 才是权威**
 * ——它每个 buffer 都有，不依赖有没有 seek，换曲 / 暂停恢复 / gapless 全都覆盖。
 *
 * ### 数据流与线程
 * `KaraokeAnchoringSink.handleBuffer(buf, ptsUs, n)`（ExoPlayer 播放线程）先记录
 * `(处理器当时的已过帧数, ptsUs)`，再交给真正的 sink；真正的 sink 在**同一次调用内**把 buffer
 * 喂给处理器（`queueInput` 也是播放线程）。所以**不需要锁**。
 *
 * ### 为什么记「处理器当时的已过帧数」而不是简单 FIFO
 * - sink 在 AudioTrack 缓冲满时**可以返回 false 且不消费 buffer**，renderer 会**拿同一个 buffer
 *   重调**（pts 相同）⇒ sink 侧用 `lastPts` 去重，避免重复入队；
 * - `TrimmingAudioProcessor` 可能把**一个**输入 buffer 拆成**多次** `queueInput`
 *   （起始填充裁剪要等多攒够帧才吐）⇒ 不能假设"一次 handleBuffer ↔ 一次 queueInput"。
 *
 * 于是锚点用**严格相等**匹配：只有当某条锚点的 `hookFrames` **正好等于**处理器当前的已过帧数时，
 * 才认为它就是本 buffer 的锚点。`hookFrames` 取的是**处理器自己的计数器在 sink 收到该 buffer 时的快照**，
 * 而该计数器只在处理器真正消费数据时才前进 —— 所以搬运过程中的拆分/合并都不会错配。
 *
 * ⚠️ spike 专用：随 spike 一起删。
 */
object KaraokeBufferAnchor {

    /** 队列上限（正常情况最多 1 条待处理，留余量防异常路径堆积）。 */
    private const val CAP = 16

    /** 每条 = [hookFrames, ptsUs]。 */
    private val queue = ArrayDeque<LongArray>()

    /** 供报告：最近一次被采用的 pts（-1 = 还没有）。 */
    @Volatile var lastPtsUs: Long = -1L
        private set

    /** 因 `hookFrames` 对不上而被丢弃的锚点数（>0 说明匹配规则有问题，报告里要写）。 */
    @Volatile var dropped: Int = 0
        private set

    /**
     * Media3 通过 `AudioSink.setOutputStreamOffsetUs` 告知的「流时间 → 渲染器时间」偏移（µs）。
     * 0 = 播放器没给过（或还没给）。**只当线索用**，取舍见 [ptsOffsetFramesOf]。
     */
    @Volatile var outputOffsetUs: Long = 0L
        private set

    fun setOutputOffset(us: Long) {
        outputOffsetUs = us
    }

    /** sink 侧调用：记录「处理器当时已过 hookFrames 帧时收到 ptsUs」。 */
    fun push(hookFrames: Long, ptsUs: Long) {
        val head = queue.firstOrNull()
        if (head != null && head[0] == hookFrames) {
            // 上一条锚点还没被消费，说明它对应的输入 buffer 没走到处理器（被裁剪吃掉了）
            // —— 后到的这条才是有效的，替换掉它。
            queue.removeFirst()
        }
        if (queue.size >= CAP) queue.removeFirst()
        queue.addLast(longArrayOf(hookFrames, ptsUs))
    }

    /**
     * 处理器侧调用：取「属于本 buffer」的锚点。
     * 严格相等才命中；比当前已过帧数更早的锚点一律作废（它们对应的 buffer 已经过去了）。
     * @return ptsUs，没命中返回 -1。
     */
    fun take(framesSeen: Long): Long {
        while (true) {
            val head = queue.firstOrNull() ?: return -1L
            if (head[0] < framesSeen) {
                queue.removeFirst()
                dropped++
                continue
            }
            if (head[0] == framesSeen) {
                queue.removeFirst()
                lastPtsUs = head[1]
                return head[1]
            }
            return -1L
        }
    }

    fun clear() {
        queue.clear()
        lastPtsUs = -1L
    }
}

/** 渲染器 epoch 的**帧数**：`1e12 µs × rate / 1e6`（44100 → 44,100,000,000 帧）。 */
fun epochFramesOf(rate: Int): Long = rate.toLong() * 1_000_000L

/**
 * **渲染器 pts → 源文件帧号** 的重锚状态机（纯逻辑、无 Android 依赖，**可 JVM 单测**）。
 *
 * ### 为什么需要减偏移
 * Media3 递给 sink 的 pts 是**渲染器时间**，等于「条目内时间 + 渲染器偏移」，而偏移含
 * `MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US = 1e12 µs` 的 epoch，
 * **并在播放列表里逐条目累加**。真机实测（帧号真值 fixture）：`戳差(P−T)` 恒为
 * 44,100,000,000 = epoch 的帧数；seek 到 30s 后偏移不变（仍精确）。
 * 不扣它的后果：`absFrame` ≈ 441 亿 ≫ 缓存总帧数 ⇒ `readFrames` 永远 null ⇒ 一直回退原曲。
 *
 * ### 偏移从哪来（Media3 1.7.1 源码实证，行号见 `KARAOKE-PROGRESS.md`）
 * · `DecoderAudioRenderer.onStreamChanged`（`:720-741`）拿到**本条目**的 `offsetUs`：
 *   首条立即下发；后续条目**排队**进 `pendingOutputStreamOffsetsUs`。
 * · `DecoderAudioRenderer.processFirstSampleOfStream()`（`:516-527`）——由
 *   `drainOutputBuffer()`（`:458`，条件 `outputBuffer.isFirstSample()`）在**本流第一个采样**时调用——
 *   先 `audioSink.handleDiscontinuity()`（`:517`）**再** `setOutputStreamOffsetUs(pending[0])`（`:519`）；
 *   同一 `drainOutputBuffer()` 稍后才 `audioSink.handleBuffer(...)`（`:501`）。
 *   ⇒ **换条目时偏移一定先于该条目第一个 buffer 到达 sink**，这就是"**用播放器给的偏移重锚**
 *   （候选 1）"能成立的依据 —— 不必再自己猜 `itemOffset`。
 * · ⚠️ **真机实测（2026-10-16，三条目 44.1k→48k FLAC→44.1k）看到的实际顺序是**
 *   `setOutputStreamOffsetUs → handleDiscontinuity → configure → handleBuffer`：
 *   偏移**先**到、不连续事件在后（与上面那个方法的书写顺序不同 ⇒ 偏移不是只从那条路径来）。
 *   **结论不变**：偏移在任何**被消费的** buffer 之前到位，这正是设计依赖的性质。
 * · ⚠️ `MediaCodecAudioRenderer.onOutputStreamOffsetUsChanged`（`:891`）在 1.7.1 里**没有任何调用方**，
 *   偏移实际走 `DecoderAudioRenderer` 的 `setOutputStreamOffsetUs`（`:530-535`）——上一轮"日志没打出来
 *   所以它从不被调用"的结论**是错的**（错在把"没打日志"当成"没走到"）。
 *
 * ### 兜底
 * 偏移**按 µs 存**、用时才按**当前采样率**换算 —— 这样 44.1k→48k 换条目时不会拿旧采样率算。
 * 播放器还没报过偏移（理论上只在首个 buffer 之前）才退回 **epoch 常量**。
 */
class PtsReanchor {

    /** 播放器报过的偏移（µs）；-1 = 还不知道。**存 µs 而不是帧**，换采样率时不必重报。 */
    var offsetUs: Long = -1L
        private set

    /** 下一条待入样本的**绝对源帧号**（-1 = 尚未锚定）。 */
    var absNext: Long = -1L
        private set

    /** **本条目内**「戳算出来的帧号」与「自数预测」差 > 1 帧的次数（验收指标；换条目不计）。 */
    var realignCount: Int = 0
        private set

    /** 检测到的**条目切换**次数（播放器报来的偏移变了）。 */
    var itemChangeCount: Int = 0
        private set

    /** sink 报来的 `handleDiscontinuity` 次数。 */
    var discontinuityCount: Int = 0
        private set

    /** 拿不到锚点的 buffer 次数。 */
    var anchorMissCount: Int = 0
        private set

    /** 播放器报过几次偏移（报告用；首条 1 次，之后每换一条 +1）。 */
    var offsetReportCount: Int = 0
        private set

    /**
     * 新流开始（`onConfigure`）：**每流**的指标归零，但**保留偏移**（偏移在 configure 之前就到，
     * 别把它丢了）与 [itemChangeCount] / [offsetReportCount]（这两个是**会话级**累计 ——
     * 换采样率的条目边界恰好会触发 configure，把它清掉就等于把最该看的那次切换抹掉）。
     */
    fun onConfigureReset() {
        absNext = -1L
        realignCount = 0
        anchorMissCount = 0
        discontinuityCount = 0
    }

    /** 处理器被释放（`onReset`）：连同偏移一起清干净 —— 下次是新 sink / 新渲染器，会重新报。 */
    fun onRelease() {
        onConfigureReset()
        offsetUs = -1L
        itemChangeCount = 0
        offsetReportCount = 0
    }

    /** `flush`（seek / 换源）：自数帧作废，等下一个时间戳重新锚定。 */
    fun onFlush() {
        absNext = -1L
    }

    /** 播放器报来新的「流时间 → 渲染器时间」偏移（µs）。**偏移变了 = 换条目。** */
    fun onSinkOffset(us: Long) {
        if (us <= 0L) return
        offsetReportCount++
        if (us == offsetUs) return
        offsetUs = us
        if (absNext >= 0L) {
            itemChangeCount++
            absNext = -1L     // 自数帧跨条目不作数，等本条第一个 buffer 用新偏移重新锚定
        }
    }

    /** sink 报来 pts 不连续。**只计数**：不重置 [absNext]，因为非锚定档没有 pts 可重新锚定。 */
    fun onDiscontinuity() {
        discontinuityCount++
    }

    /** 本块要减掉的偏移（帧），按**当前**采样率换算。 */
    fun offsetFramesFor(rate: Int): Long =
        if (offsetUs > 0L) Math.round(offsetUs.toDouble() * rate / 1_000_000.0)
        else epochFramesOf(rate)

    /**
     * 处理一个 buffer（**必须在把帧数累加之前调**）。
     *
     * @param ptsUs 本 buffer 的渲染器时间戳；< 0 = 拿不到
     * @param rate 当前采样率
     * @param frames 本 buffer 的帧数
     * @param countMiss 拿不到 pts 时是否计入 [anchorMissCount]（非锚定档不记，免得每块都 +1）
     * @return 本块**第一帧**的绝对源帧号；-1 = 无可用锚点 ⇒ 调用方按欠载处理（退回原曲）
     */
    fun onBuffer(ptsUs: Long, rate: Int, frames: Int, countMiss: Boolean): Long {
        if (ptsUs < 0L || rate <= 0) {
            if (countMiss) anchorMissCount++
            val a = absNext
            if (a >= 0L) absNext = a + frames
            return a
        }
        val ptsFrame = Math.round(ptsUs.toDouble() * rate / 1_000_000.0)
        val src = ptsFrame - offsetFramesFor(rate)
        if (absNext >= 0L) {
            val d = src - absNext
            if (d > 1L || d < -1L) realignCount++   // 用户定的判据：> 1 个采样才叫"重锚"
        }
        absNext = src + frames
        return src
    }
}

/**
 * v1.5.0 P2 spike 步骤 3：**混音档位**（原曲 / 伴奏 / 人声）与**占位伴奏源**的运行时开关。
 *
 * 系数向量（用户定的）：
 * | 档位 | `g_o`（原曲） | `g_a`（伴奏） | 听感 |
 * |---|---|---|---|
 * | 原曲 | 1 | 0 | 只有原曲 |
 * | 伴奏 | 0 | 1 | 只有伴奏 |
 * | 人声 | 1 | −1 | `原曲 − 伴奏` = 人声 |
 *
 * 切换时**在系数空间做线性插值**（约 300ms），不是把输出音量拉低再拉高 ⇒ **音频全程不断**。
 *
 * ⚠️ 占位伴奏：分离引擎还没接进来，所以"伴奏"轨的内容由 [sourceKind] 决定：
 * - [SRC_IDENTITY]：伴奏 = 原曲本身 ⇒ **人声档应该是"完全静音"**（完美的零拍检测！
 *   差一个采样就会漏出梳状声，耳朵和数据分析都立刻发现）；
 * - [SRC_TONE]：220Hz 正弦（能直接听出"混进去的东西"和系数比例）；
 * - [SRC_SILENCE]：静音（人声档 = 原曲，伴奏档 = 静音）。
 */
object KaraokeMixControl {

    const val MODE_ORIGINAL = 0
    const val MODE_ACCOMP = 1
    const val MODE_VOCAL = 2

    const val SRC_IDENTITY = 0
    const val SRC_TONE = 1
    const val SRC_SILENCE = 2

    /** 当前档位（UI 线程写、audio 线程读）。 */
    @Volatile var mode: Int = MODE_ORIGINAL

    /** 占位伴奏源类型。 */
    @Volatile var sourceKind: Int = SRC_IDENTITY

    /** 交叉淡化时长（毫秒）。用户定 300。 */
    @Volatile var rampMs: Int = 300

    /**
     * **真伴奏源**（块 3）。非 null 时**优先于** [sourceKind] 的占位源。
     * 由 app 在"点伴唱、且缓存已就绪"时设为 [StemCacheAccompaniment]；取消伴唱时置回 null。
     * 音频线程每个块读一次；读不到（欠载）由处理器退回原曲。
     */
    @Volatile
    var realSource: AccompanimentSource? = null

    fun coeffOf(m: Int): FloatArray = when (m) {
        MODE_ORIGINAL -> floatArrayOf(1f, 0f)
        MODE_ACCOMP -> floatArrayOf(0f, 1f)
        else -> floatArrayOf(1f, -1f)
    }

    fun label(m: Int): String = when (m) {
        MODE_ORIGINAL -> "原曲"
        MODE_ACCOMP -> "伴奏"
        else -> "人声（原曲−伴奏）"
    }

    fun sourceLabel(k: Int): String = when (k) {
        SRC_IDENTITY -> "占位=原曲本身 ⇒ 人声档(原曲−原曲)应**完全无声**（唯一能证明对位的档）"
        // ⚠️ 别写"人声档=歌里减掉纯音"——那是错的：纯音本来不在原曲里，
        // 减一个独立纯音 = 加一份**反相**纯音，反相只差 180° 相位，**听起来还是同一个音**。
        SRC_TONE -> "占位=220Hz 纯音 ⇒ 原曲档=歌 / 伴奏档=纯音 / 人声档=歌+反相纯音（听着仍有纯音，正常）"
        else -> "占位=静音 ⇒ 伴奏档无声 / 人声档=原曲"
    }
}

/**
 * 系数在 [rampFrames] 帧内从当前值**沿直线**走到目标值（"在系数空间做线性插值"的字面意思）。
 *
 * ⚠️ **每个分量用自己的速率**（`Δ_i / rampFrames`），不是共用一个按最大分量归一化的速率。
 * 两者对 (1,0)→(0,1) 这种等距切换没区别，但 (0,1)→(1,−1)（伴奏→人声）不一样：
 *  - 各走各的速率 ⇒ 路径是系数空间的**直线**，中点 (0.5, 0)；
 *  - 共用一个速率 ⇒ 走得近的那个**先到并停住**，路径在拐点处**折弯**，中点 (1, 0)。
 * 用户规格说的是"系数空间线性插值"，所以取前者。
 * （这条是 `CoefficientRampTest` 抓出来的：我先写成了后者，测试直接失败。）
 *
 * 纯逻辑，无 Android 依赖。
 */
class CoefficientRamp(go: Float, ga: Float) {
    var curGo: Float = go; private set
    var curGa: Float = ga; private set
    private var tgtGo: Float = go
    private var tgtGa: Float = ga
    private var stepGo = 0f
    private var stepGa = 0f

    fun moveTo(go: Float, ga: Float, rampFrames: Int) {
        tgtGo = go; tgtGa = ga
        if (rampFrames <= 0) {
            stepGo = 0f; stepGa = 0f
            return
        }
        stepGo = (tgtGo - curGo) / rampFrames
        stepGa = (tgtGa - curGa) / rampFrames
    }

    val atTarget: Boolean get() = curGo == tgtGo && curGa == tgtGa

    /** 前进 [frames] 帧的插值量（各自在目标处截断）。 */
    fun advance(frames: Int) {
        if (stepGo == 0f && stepGa == 0f) { curGo = tgtGo; curGa = tgtGa; return }
        curGo = approach(curGo, tgtGo, abs(stepGo) * frames)
        curGa = approach(curGa, tgtGa, abs(stepGa) * frames)
    }

    private fun approach(c: Float, t: Float, d: Float): Float {
        val diff = t - c
        return if (abs(diff) <= d) t else c + if (diff > 0) d else -d
    }
}

/**
 * 伴奏源：把 [frames] 帧伴奏（交错 L,R）写进 [dst]。
 *
 * **返回 false = 这段还没算好**（欠载）——调用方会退回原曲并记录，而不是拿半截数据出声。
 * 占位源一律返回 true。
 */
fun interface AccompanimentSource {
    fun readAbs(absFrame: Long, sampleRate: Int, srcOrig: ShortArray, dst: ShortArray, frames: Int): Boolean
}

/** 伴奏 = 原曲本身 ⇒ 人声档 `原曲 − 伴奏` = **完全静音**（算术检查）。 */
val IdentityAccompaniment = AccompanimentSource { _, _, srcOrig, dst, frames ->
    System.arraycopy(srcOrig, 0, dst, 0, frames * 2)
    true
}

/** 伴奏 = 静音 ⇒ 伴奏档静音、人声档 = 原曲。 */
val SilenceAccompaniment = AccompanimentSource { _, _, _, dst, frames ->
    java.util.Arrays.fill(dst, 0, frames * 2, 0.toShort())
    true
}

/** 伴奏 = 220Hz 正弦（幅度 0.4）——能直接听出"混进来的东西"与系数比例。 */
val ToneAccompaniment = AccompanimentSource { absFrame, sampleRate, _, dst, frames ->
    val w = 2.0 * Math.PI * 220.0 / sampleRate
    for (i in 0 until frames) {
        val v = (0.4 * 32767.0 * Math.sin(w * (absFrame + i))).toInt()
            .coerceIn(-32768, 32767).toShort()
        dst[2 * i] = v
        dst[2 * i + 1] = v
    }
    true
}

/**
 * **真伴奏源（块 3）**：按**绝对源帧号**去 [StemCache] 取已经算好的块。
 *
 * 关键点：
 * - 坐标是**源文件帧号**（不是"第几首歌"）—— 见块 2 把缓存键改成物理文件维度；
 * - **只读已完成的块**：`readFrames` 对缺块返回 null（绝不返回半截）⇒ 这里直接报欠载；
 * - 一次读要的东西可能跨块边界，`readFrames` 已经处理了。
 *
 * 线程：只在音频线程调用（`readFrames` 是 RandomAccessFile 随机读，块是 6 秒 PCM ≈ 1MB，
 * 小读一次 ~几十微秒，不会拖垮音频线程）。
 */
class StemCacheAccompaniment(
    private val cache: StemCache,
    private val dir: java.io.File,
) : AccompanimentSource {

    /** 欠载次数（报告用）。 */
    @Volatile var underruns: Long = 0L; private set
    /** 成功读数（报告用）。 */
    @Volatile var hits: Long = 0L; private set
    /** 命中但**全是零**的次数（与"未命中"必须分开看：前者=缓存内容错，后者=帧号/范围错）。 */
    @Volatile var zeroHits: Long = 0L; private set
    /** 已打印的详细读日志条数（音频线程上不打太多）。 */
    private var readLogs = 0

    private var scratch = ShortArray(0)

    override fun readAbs(
        absFrame: Long, sampleRate: Int, srcOrig: ShortArray, dst: ShortArray, frames: Int,
    ): Boolean {
        // 帧号无效（例如"没有时间戳可锚定"导致 absNext 仍是 -1）一律当**欠载**处理 ⇒ 退回原曲。
        // 绝不能拿一个瞎猜的帧号去取伴奏 —— 那才是真的会出错误声音。
        if (absFrame < 0L) {
            underruns++
            return false
        }
        val got = cache.readFrames(dir, absFrame, frames)
        if (got == null) {
            underruns++
            logRead(absFrame, frames, null, -1)
            return false
        }
        // 先把缓存内容搬进 dst（长度可能是 frames*2 也可能是拼接的 —— 只取需要的部分）
        val n = minOf(got.size, frames * 2)
        if (scratch.size < n) scratch = ShortArray(n)
        System.arraycopy(got, 0, scratch, 0, n)
        java.util.Arrays.fill(dst, 0, frames * 2, 0.toShort())
        System.arraycopy(scratch, 0, dst, 0, n)
        hits++
        if (n > 0 && (0 until n).none { got[it] != 0.toShort() }) zeroHits++
        logRead(absFrame, frames, got, n)
        return true
    }

    /**
     * **块 3 定位日志**（用户 2026-10-14 要求）：每次读缓存打印
     * 请求帧范围 / 命中与否 / 缓存现有范围 / 返回采样数 / 非零采样数，
     * 并把「**未命中 ⇒ 回退原曲**」与「**命中但读回来全是零**」明确分开
     * （两者的修法完全不同：前者是帧号或范围错，后者是缓存内容错）。
     *
     * 只在音频线程上调；限**前 30 次**（再往后每 500 次一条）——避免 I/O 打日志拖累音频线程。
     */
    private fun logRead(absFrame: Long, frames: Int, got: ShortArray?, n: Int) {
        if (readLogs >= 30 && readLogs % 500 != 0) { readLogs++; return }
        readLogs++
        val m = cache.readManifest(dir)
        val range = if (m == null) "无 manifest" else "块 0..${m.totalChunks - 1}（gen=${m.genSize}，≈[0,${m.totalChunks.toLong() * m.genSize})）已落盘${cache.doneChunks(dir)}块"
        val nz = if (got == null) -1 else (0 until n).count { got[it] != 0.toShort() }
        val verdict = when {
            got == null -> "**未命中 ⇒ 回退原曲**"
            nz == 0 -> "**命中但读回来全是零**（缓存内容错）"
            else -> "命中且有数据"
        }
        Log.i(
            TAG,
            "读缓存#$readLogs 请求帧[$absFrame,${absFrame + frames}) 命中=${got != null} " +
                "缓存范围=$range 返回采样数=${got?.size ?: 0} 非零采样数=$nz ⇒ $verdict",
        )
    }
}

/**
 * **格式不变的 1:1 透传处理器** + 探针（步骤 2 / 2b / 2c）。
 *
 * ⚠️ **缓冲契约（用 javap 反汇编核实过，写错就会完全没声音——正好是 v23 的症状）**：
 *  - `BaseAudioProcessor.getOutput()` **只是把 `outputBuffer` 交出去，不会替你 `flip()`**；
 *  - `replaceOutputBuffer(size)` 在**复用**旧缓冲时走 `clear()` → `limit = capacity`（**可能大于本次 size**）；
 *  - Media3 自己的处理器（`ToInt16PcmAudioProcessor`、`ChannelMixingAudioProcessor`）都显式 `flip()`。
 *
 * ### 它在管线里的位置（javap 反汇编 `DefaultAudioSink` 构造器得出，**不是猜的**）
 * ```
 * ToInt16PcmAudioProcessor → ChannelMappingAudioProcessor → TrimmingAudioProcessor
 *   → ★本处理器★ → SonicAudioProcessor(变速) → SilenceSkippingAudioProcessor
 * ```
 * 两条推论（都写进了报告）：
 *  1. **`TrimmingAudioProcessor`（gapless 起始填充裁剪）在我们之前** ⇒ 我们看到的 PCM
 *     是**裁剪之后**的，处理器里的"第 0 帧 ≠ 文件的第 0 帧"（差值 = `Format.encoderDelay`）；
 *  2. **`SonicAudioProcessor` 在我们之后** ⇒ 变速不改变我们看到的帧率，
 *     "按源帧序号混合"在变速下依然成立（这是这条路线的一条好消息）。
 */
class KaraokeMixProcessor(
    /** 探针模式（[KaraokeAudioSpike.PROBE_OFF] / FN / CLICK / CAPTURE）。构造时给初值。 */
    initialProbeMode: Int = KaraokeAudioSpike.PROBE_OFF,
    /**
     * 探针**写文件**（步骤 2b 修正）：上一次只靠 logcat，缓冲区一滚数据就没了。
     * 一行一条、追加写，不再赌 logcat。
     */
    private val logFile: File? = null,
    /** 采集模式的落盘文件（PI 用同一个目录）。 */
    private val captureFile: File? = null,
    /**
     * 档 3 才有外层 [KaraokeAnchoringSink] 往 [KaraokeBufferAnchor] 投递时间戳。
     * 档 2 没有 ⇒ 整套时间戳记账都不该跑（否则每块都会被记成"拿不到锚点"）。
     */
    private val anchored: Boolean = false,
    /** 步骤 3：把 1:1 透传换成**按系数混音**（`out = g_o·原曲 + g_a·伴奏`）。 */
    private val mixing: Boolean = false,
) : BaseAudioProcessor() {

    init {
        KaraokeAudioSpike.liveProbeMode = initialProbeMode
    }

    /** **每次读都取最新值** —— 调试面板换模式立即生效，不必重进 App（换歌不重建 sink）。 */
    private val probeMode: Int get() = KaraokeAudioSpike.liveProbeMode

    /** configure 时的输入格式，供后续步骤的帧号换算用。 */
    var inputSampleRate: Int = 0; private set
    var inputChannelCount: Int = 0; private set
    var inputEncoding: Int = 0; private set

    /** 流过的输入帧数（自本实例开始；**flush 不归零**，见 2b 实测）。 */
    var framesSeen: Long = 0L; private set

    var flushCount: Int = 0; private set
    private var bytesPerFrame = 0
    /** 心跳节流：上一次打日志时的"秒数"。 */
    private var heartbeatSec = -1
    /** 探针自己的节流预算（与心跳**各自记账**，否则心跳会把预算吃光）。 */
    private var lastProbeSec = -1
    /** 探针：flush 之后还要逐块打印几个 buffer。 */
    private var probeBuffersAfterFlush = 0
    /** flush 那一刻从 app 侧拿到的"请求目标"（步骤 2b 的对拍基准）。 */
    private var flushSeq: Int = -1
    private var flushReqAbsMs: Long = -1L
    private var flushReqRelMs: Long = -1L

    // ── 步骤 2c：时间戳锚定 ────────────────────────────────────────────────
    /**
     * 重锚状态机（见 [PtsReanchor]）。本类**不再自己维护 `absNext`** —— 那套算术有真机踩过的坑
     * （epoch 未扣、多条目偏移累加），抽成纯逻辑后才好在 JVM 上按"三条目序列"逐事件测。
     */
    val reanchor = PtsReanchor()

    /** 时间戳与自数帧偏差 > 1 个采样而重锚的次数（验收指标之一）。 */
    val realignCount: Int get() = reanchor.realignCount
    /** 拿不到锚点的 buffer 次数（正常情况下应恒为 0）。 */
    val anchorMissCount: Int get() = reanchor.anchorMissCount

    /**
     * sink 报来**新的渲染器偏移**（µs）。换条目时它**先于该条目第一个 buffer** 到达
     * （`DecoderAudioRenderer.drainOutputBuffer`：`:519` 早于 `:501`）⇒ 拿它当权威即可。
     */
    fun onSinkOffset(us: Long) = reanchor.onSinkOffset(us)

    /** sink 报来 pts 不连续（换条目 / seek 预滚动丢弃）。 */
    fun onSinkDiscontinuity() = reanchor.onDiscontinuity()

    /**
     * 供 sink 诊断把日志写进**同一个探针文件**（`karaoke_probe.log`）。
     * logcat 缓冲区会滚，长时间连播时**文件才是权威**。
     */
    fun emitDiag(line: String) = emit(line)

    // ── 采集模式 ───────────────────────────────────────────────────────────
    private var captureOut: BufferedOutputStream? = null
    private var capturedFrames: Long = 0L
    private var captureFirstPtsFrame: Long = -1L
    private var captureNo = 0
    private var captureBytes: ByteArray = ByteArray(0)

    /** 探针输出：logcat **加**写文件（文件是权威 —— logcat 缓冲区一滚数据就没了）。 */
    private fun emit(line: String) {
        Log.i(TAG, line)
        logFile?.let { f -> runCatching { f.appendText(line + "\n") } }
    }

    private fun encodingName(enc: Int): String = when (enc) {
        C.ENCODING_PCM_16BIT -> "PCM_16BIT"
        C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        else -> "enc=$enc"
    }

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
        inputEncoding = inputAudioFormat.encoding
        bytesPerFrame = inputAudioFormat.channelCount * bytes
        framesSeen = 0
        flushCount = 0
        probeBuffersAfterFlush = 0
        lastProbeSec = -1
        reanchor.onConfigureReset()
        // ⚠️ 必须清锚点队列：`configure` 把 `framesSeen` 归零（换采样率的条目边界会发生），
        // 而队里可能还压着**上一条目**的锚点（例如被 Trimming 拆分、最终没走到处理器的那个 buffer）。
        // 不清的话 `take(0)` 会撞上 `head[0]=1024` ⇒ 直接不命中 ⇒ 本块退回原曲。
        // 清掉是安全的：`configure` 一定发生在该条目第一个 `handleBuffer` **之前**（源码顺序）。
        KaraokeBufferAnchor.clear()
        mixBlockLogged = 0
        mixDiagLogged = 0
        endCapture("onConfigure")
        val msg = "onConfigure ← 输入格式 ${inputAudioFormat.sampleRate}Hz / " +
            "${inputAudioFormat.channelCount}ch / ${encodingName(inputAudioFormat.encoding)}" +
            " → ${if (mixing) "混音（out = g_o·原曲 + g_a·伴奏）" else "1:1 透传"}，" +
            "bytesPerFrame=$bytesPerFrame"
        Log.i(TAG, msg)
        if (probeMode != KaraokeAudioSpike.PROBE_OFF) {
            emit("[探针] === configure 模式=${KaraokeAudioSpike.describeProbe(probeMode)} $msg ===")
        }
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
        val frames = if (bytesPerFrame > 0) size / bytesPerFrame else 0

        // ── 步骤 2c：**每个 buffer** 都用 presentationTimeUs 换算帧号，与自数帧对拍 ──
        // 偏移与重锚全部交给 [PtsReanchor]（纯逻辑，有 JVM 单测钉住"三条目 gapless"逐事件序列）。
        // 它按 `pts − 播放器报来的偏移` 得到源文件帧号 —— 偏移含 1e12µs 的 epoch **并逐条目累加**，
        // 不扣就会去查 441 亿帧处的伴奏（`readFrames` 永远 null ⇒ 一直回退原曲，块 3 失败真因）。
        //
        // ⚠️ **本块第一帧的绝对源帧**必须在帧数累加**之前**取：混音就是按这个帧号去伴奏缓存取数的，
        // 晚一步就整体错开一整块（4096 帧 ≈ 93ms）。**保留 -1**（不兜成 0）：帧号无效时伴奏源
        // 按欠载处理 ⇒ 退回原曲，绝不会拿"第 0 帧的伴奏"去跟中间某处混（那才会出错误的声音）。
        val predicted = reanchor.absNext
        val ptsUs = if (anchored) KaraokeBufferAnchor.take(framesSeen) else -1L
        val ptsFrame = if (ptsUs >= 0) {
            Math.round(ptsUs.toDouble() * inputSampleRate / 1_000_000.0)
        } else -1L
        val srcFrame = reanchor.onBuffer(
            ptsUs = ptsUs,
            rate = inputSampleRate,
            frames = frames,
            countMiss = anchored && probeMode != KaraokeAudioSpike.PROBE_OFF,
        )

        // ── 采集模式：从"落到文件开头"的那个 buffer 开始，把收到的 PCM 原样落盘 ──
        // 两个起采条件（任一条满足即可）：① 时间戳落在开头 0.5s 内（档 3）；
        // ② 本块是 flush 后第一块（档 2 没有时间戳时靠这个）。
        if (probeMode == KaraokeAudioSpike.PROBE_CAPTURE && KaraokeAudioSpike.captureArmed) {
            val byPts = srcFrame in 0..(inputSampleRate / 2L)
            val byFlush = probeBuffersAfterFlush == PROBE_POST_FLUSH
            if (byPts || byFlush) {
                KaraokeAudioSpike.captureArmed = false
                startCapture(if (byPts) srcFrame else -1L)
            }
        }
        // （采集在下面、输出算好之后再做 —— 采的是**处理器的输出**。）

        // ── 探针（步骤 2 / 2b）──
        val probeable = probeMode != KaraokeAudioSpike.PROBE_OFF && bytesPerFrame >= 4 && frames >= 1
        if (probeable) {
            val sec = (framesSeen / maxOf(1, inputSampleRate)).toInt()
            val postFlush = probeBuffersAfterFlush > 0
            if (postFlush || sec > lastProbeSec) {
                lastProbeSec = sec
                val where = if (postFlush) {
                    "flush 后第 ${PROBE_POST_FLUSH - probeBuffersAfterFlush + 1} 块"
                } else "稳态"
                emit("[探针] $where：${probeLine(inputBuffer, frames, ptsUs, srcFrame, predicted, postFlush, ptsFrame)}")
            }
            if (postFlush) probeBuffersAfterFlush--
        }

        framesSeen += frames

        // 每约 1 秒音频打一次心跳，用来**证明音频确实穿过了这里**（而不是被旁路）。
        if (framesSeen / maxOf(1, inputSampleRate) > heartbeatSec) {
            heartbeatSec = (framesSeen / maxOf(1, inputSampleRate)).toInt()
            Log.i(TAG, "透传心跳：已过 $framesSeen 帧（≈${heartbeatSec}s）flush=$flushCount")
        }
        val out = replaceOutputBuffer(size)
        // 混音按 **(L,R) 成对**取数 ⇒ 只有立体声才敢走。
        // ⚠️ 并且**整段包 try/catch**：处理器在音频线程上抛异常 = 整条播放链路报废
        // （真机上表现为"卡住/反复念同一小段"）。这里宁可**退回透传**，也不能让播放断掉——
        // 混音是加法功能，绝不能因为它的 bug 把"正常听歌"一起带走。
        // 运行期决定走哪条路（不再需要重启）：
        //  - **原曲档 ⇒ 透传分支**：`out.put(inputBuffer)`，与"链路上没有处理器"逐位一致
        //    （这是块 1 的闸门，有单测钉住）；
        //  - 伴奏/人声档 ⇒ 混音。
        // 临时诊断（块 3 定位用）：把处理器**实际读到**的值打出来 ——
        // 真机上"设了但没生效"这种问题，只有把读到的值打出来才能定性。
        if (mixDiagLogged < 3) {
            mixDiagLogged++
            Log.i(
                TAG,
                "混音诊断#$mixDiagLogged mixing=$mixing anchored=$anchored ch=$inputChannelCount " +
                    "ctrlMode=${KaraokeMixControl.mode} " +
                    "wantMix=${mixing && KaraokeMixControl.mode != KaraokeMixControl.MODE_ORIGINAL} " +
                    "realSource=${KaraokeMixControl.realSource != null} srcKind=${KaraokeMixControl.sourceKind}",
            )
        }
        val wantMix = mixing && KaraokeMixControl.mode != KaraokeMixControl.MODE_ORIGINAL
        var mixed = false
        if (wantMix && inputChannelCount == 2) {
            mixed = try {
                mixInto(inputBuffer, out, frames, srcFrame)
                true
            } catch (t: Throwable) {
                mixFailCount++
                if (mixFailCount == 1L) Log.w(TAG, "混音失败 → 本块退回透传", t)
                false
            }
        } else if (wantMix) {
            nonStereoPassCount++
            if (nonStereoPassCount == 1L) {
                Log.i(TAG, "混音跳过：非立体声（ch=$inputChannelCount）→ 退回透传")
            }
        }
        if (!mixed) {
            // 混音可能只写了一半 —— clear() 后重新按透传写，保证字节数正确
            out.clear()
            out.put(inputBuffer)
        }
        // ⚠️ 必须 flip —— 见类注释的缓冲契约
        out.flip()
        // 采集：采**处理器的输出**（不是输入）—— 抓到的就是真正送去出声的东西，
        // 可直接与源文件／独立解码结果逐位对比，验证整条链（含本处理器）是否透明。
        if (probeMode == KaraokeAudioSpike.PROBE_CAPTURE) writeCapture(out, frames)
    }

    // ── 步骤 3：混音 ────────────────────────────────────────────────────────
    private val ramp = CoefficientRamp(1f, 0f)
    private var lastMixMode = -1
    private var mixSrc = ShortArray(0)
    private var mixAcc = ShortArray(0)
    /** 一次淡化的累计帧数（报告用）。 */
    var fadeFrames: Long = 0L; private set
    /** 因**非立体声**而退回透传的块数（报告用；>0 说明这个文件混音没生效）。 */
    var nonStereoPassCount: Long = 0L; private set
    /** 混音抛异常而退回透传的块数（>0 = 混音有 bug，但播放没断）。 */
    var mixFailCount: Long = 0L; private set
    /** 欠载（这段伴奏还没算好）退回原曲的次数 —— 块 3 的验收要看它。 */
    var underrunCount: Long = 0L; private set
    /** 欠载是否已锁存（避免每个块都 +1）。 */
    private var underrunLatched = false
    /** 临时诊断用：每个流前几块打日志的计数。 */
    private var mixBlockLogged = 0
    /** 临时诊断用：确认处理器读到的 mixing/mode 是什么（块 3 定位）。 */
    private var mixDiagLogged = 0

    private fun sourceOf(kind: Int): AccompanimentSource = when (kind) {
        KaraokeMixControl.SRC_TONE -> ToneAccompaniment
        KaraokeMixControl.SRC_SILENCE -> SilenceAccompaniment
        else -> IdentityAccompaniment
    }

    /**
     * `out[i] = g_o·原曲[i] + g_a·伴奏[i]`，系数逐帧按 [CoefficientRamp] 走（切换档位时约 300ms）。
     *
     * ⚠️ 写完必须把 `out.position()` 推到 `size` —— `flip()` 是拿 position 当 limit 的，
     * 只写内容不动 position 会输出 0 字节（= 静音），正好是 v23 那类症状。
     */
    private fun mixInto(inputBuffer: ByteBuffer, out: ByteBuffer, frames: Int, startFrame: Long) {
        val n = frames * 2
        if (mixSrc.size < n) {
            mixSrc = ShortArray(n)
            mixAcc = ShortArray(n)
        }
        inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(mixSrc, 0, n)

        val mode = KaraokeMixControl.mode
        val rf = (KaraokeMixControl.rampMs.toLong() * inputSampleRate / 1000L).toInt()
        if (mode != lastMixMode) {
            val c = KaraokeMixControl.coeffOf(mode)
            ramp.moveTo(c[0], c[1], rf)
            fadeFrames += rf
            underrunLatched = false
            Log.i(
                TAG,
                "混音切换 → ${KaraokeMixControl.label(mode)} 系数=(${c[0]},${c[1]}) " +
                    "在 $rf 帧（${KaraokeMixControl.rampMs}ms）内线性插值" +
                    " 伴奏源=${if (KaraokeMixControl.realSource != null) "真缓存" else KaraokeMixControl.sourceLabel(KaraokeMixControl.sourceKind)}",
            )
            lastMixMode = mode
        }

        // 真伴奏（块 3）优先；没有就退回占位源（调试用）。
        val src = KaraokeMixControl.realSource ?: sourceOf(KaraokeMixControl.sourceKind)
        val ready = src.readAbs(startFrame, inputSampleRate, mixSrc, mixAcc, frames)

        if (!ready) {
            // **欠载**（这段还没算好）：退回**原曲**，而不是拿半截数据出声。
            // 做法：把系数目标拉回 (1,0)，并把"伴奏"当成原曲 ⇒ 输出 = 原曲，且**无跳变**
            // （从伴奏档 (0,1) 出发时 go+ga 恒为 1）。算好之后会自动淡回目标档。
            if (!underrunLatched) { underrunLatched = true; underrunCount++ }
            ramp.moveTo(1f, 0f, rf)
            System.arraycopy(mixSrc, 0, mixAcc, 0, frames * 2)
        } else if (underrunLatched) {
            underrunLatched = false
            val c = KaraokeMixControl.coeffOf(mode)
            ramp.moveTo(c[0], c[1], rf)   // 恢复：淡回目标档
        }

        // 临时诊断（2026-10-14 排查"混音档卡住"）：每个流的前 3 块把**输入/伴奏/系数**打出来。
        // 真机上"卡住"看不出是哪一步错，这三行能直接判断是系数、还是伴奏源、还是缓冲的问题。
        if (mixBlockLogged < 3) {
            mixBlockLogged++
            Log.i(
                TAG,
                "混音块#$mixBlockLogged 档=${KaraokeMixControl.label(mode)} " +
                    "源=${KaraokeMixControl.sourceLabel(KaraokeMixControl.sourceKind)} " +
                    "go=${ramp.curGo} ga=${ramp.curGa} frames=$frames " +
                    "in=[${mixSrc[0]},${mixSrc[1]},${mixSrc[2]}] " +
                    "acc=[${mixAcc[0]},${mixAcc[1]},${mixAcc[2]}]",
            )
        }

        val oS = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until frames) {
            ramp.advance(1)
            val go = ramp.curGo
            val ga = ramp.curGa
            val l = (go * mixSrc[2 * i] + ga * mixAcc[2 * i]).toInt()
            val r = (go * mixSrc[2 * i + 1] + ga * mixAcc[2 * i + 1]).toInt()
            oS.put(2 * i, l.coerceIn(-32768, 32767).toShort())
            oS.put(2 * i + 1, r.coerceIn(-32768, 32767).toShort())
        }
        out.position(out.position() + size2(frames))

        // ⚠️⚠️ **必须消费输入缓冲**（2026-10-14 真机事故的根因）：
        // `AudioProcessingPipeline.processData` 是个 `while (isProcessed)` 循环，判据是
        // 「某个处理器的输入 `hasRemaining()`」——只要还剩字节它就**再喂一遍同一块**。
        // 透传分支用 `out.put(inputBuffer)` 天然消费掉了；而这里是 `duplicate()` 读的，
        // 原缓冲 position 没动 ⇒ 管线认为没处理完 ⇒ 无限重喂 ⇒ 听感是"同一小段被反复念"，
        // 音频线程原地打转。**放在最后**（不是读之前）：万一中途抛异常，输入还没被消费，
        // 上面的 try/catch 兜底走 `out.put(inputBuffer)` 透传仍然成立。
        inputBuffer.position(inputBuffer.limit())
    }

    private fun size2(frames: Int): Int = frames * bytesPerFrame

    /**
     * 一行探针内容。字段名保持稳定（`tools/karaoke/analyze_probe.py` 按名字解析）：
     * - `计数器` / `真值首帧` / `差(真值首帧−请求帧)`：2b 的口径（与 app 请求位置对拍）
     * - `锚点pts帧` / `自数预测` / `锚偏差` / `重锚`：2c 的口径（**时间戳 vs 真值**，验收指标）
     */
    private fun probeLine(
        buf: ByteBuffer,
        frames: Int,
        ptsUs: Long,
        ptsFrame: Long,
        predicted: Long,
        withAnchor: Boolean,
        rawPtsFrame: Long,
    ): String {
        val start = buf.position()
        val end = buf.limit() - bytesPerFrame
        // `ptsFrame` 名字沿用（`tools/karaoke/analyze_*.py` 按名字解析），但自 2026-10-14 起
        // 装的是**扣掉渲染器 epoch 之后的源文件帧号** —— 也就是"戳差(P−T)"该对比的那个东西。
        // 原始（含 epoch）的值放 `原始pts帧=`，只为留证。
        val anchor = if (ptsFrame >= 0) {
            "锚点ptsμs=$ptsUs 锚点pts帧=$ptsFrame 原始pts帧=$rawPtsFrame" +
                " 自数预测=$predicted 锚偏差=${ptsFrame - predicted}" +
                " 重锚=$realignCount 锚缺=$anchorMissCount"
        } else {
            "锚点pts帧=无 重锚=$realignCount 锚缺=$anchorMissCount 队列丢弃=${KaraokeBufferAnchor.dropped}"
        }
        // 三种模式的"真值首帧"（本块第一个采样在源文件里的帧号）——统一成一个变量，
        // 后面的 `差(真值首帧−请求帧)` 与 `戳差(P−T)` 都用它，避免各写各的写错。
        var truth = -1L
        val body = when (probeMode) {
            KaraokeAudioSpike.PROBE_FN -> {
                if (end < start) "$anchor 空块"
                else {
                    // 真值走 KaraokeFrameCodec（唯一实现，内部强制小端；那条规则已被回归测试钉住）。
                    truth = KaraokeFrameCodec.decodeFrameAt(buf, start).toLong()
                    val last = KaraokeFrameCodec.decodeFrameAt(buf, end)
                    // 原始采样值只用于诊断 —— ⚠️ 也必须小端，否则打印出来的是"调换字节"的假象。
                    val d = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    val n = minOf(6, (end - start) / bytesPerFrame + 1)
                    val raw = StringBuilder()
                    for (i in 0 until n) {
                        raw.append(d.getShort(start + i * bytesPerFrame).toInt()).append(' ')
                    }
                    "$anchor 真值首帧=$truth 末帧=$last 差=${truth - framesSeen}" +
                        "（本块 $frames 帧）原始左声道前$n=[${raw.toString().trim()}]"
                }
            }
            KaraokeAudioSpike.PROBE_CLICK -> {
                val click = firstClickOffset(buf, start, end)
                if (click < 0) {
                    "$anchor 首咔哒偏移=无（本块 $frames 帧）"
                } else if (ptsFrame >= 0) {
                    // 真值文件每秒 0 帧处一个咔哒 ⇒ 咔哒的绝对帧号必是整数秒；
                    // 相位 = 落点相对网格的余数（MP3 的编码器前置延迟会体现为一个恒定相位）。
                    val at = ptsFrame + click
                    val sr = inputSampleRate.toLong()
                    val phase = ((at % sr) + sr) % sr
                    truth = at - phase
                    "$anchor 首咔哒偏移=$click 相位=$phase 真值首帧=$truth 差=${truth - framesSeen}" +
                        "（本块 $frames 帧）"
                } else {
                    "$anchor 首咔哒偏移=$click（本块 $frames 帧，无锚点算不出相位）"
                }
            }
            else -> {
                truth = ptsFrame
                "$anchor 已采=${capturedFrames}帧（本块 $frames 帧）"
            }
        }
        // 步骤 2c 验收指标：**时间戳换算的帧号 − 真值帧号**，|·| ≤ 1 才算通过。
        val ptsDiff = if (ptsFrame >= 0 && truth >= 0) " 戳差(P−T)=${ptsFrame - truth}" else ""
        // 步骤 2b：flush 之后那几块带上"app 请求的目标帧号"（由 ms 换算，可能差 ±1 帧 —— 那是
        // ms→帧 的取整，不是错位）。
        val req = if (withAnchor) {
            val reqFrame = if (flushReqRelMs >= 0 && inputSampleRate > 0) {
                flushReqRelMs * inputSampleRate / 1000
            } else -1L
            " 请求#${flushSeq} abs=${flushReqAbsMs}ms rel=${flushReqRelMs}ms 请求帧=$reqFrame" +
                " 差(真值首帧−请求帧)=${if (reqFrame >= 0 && truth >= 0) truth - reqFrame else 0L}"
        } else ""
        return "计数器=$framesSeen $body$ptsDiff$req"
    }

    /** 缓冲内首个 `|左声道| > 阈值` 的**帧偏移**（相对缓冲起点）；找不到返回 -1。 */
    private fun firstClickOffset(buf: ByteBuffer, start: Int, end: Int): Int {
        if (end < start) return -1
        val d = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var off = start
        while (off <= end) {
            if (abs(d.getShort(off).toInt()) > CLICK_THRESHOLD) return (off - start) / bytesPerFrame
            off += bytesPerFrame
        }
        return -1
    }

    /** `flush()` 只在**不连续**（seek / 换源）时被调用，且**不告诉你跳到哪了**。 */
    override fun onFlush() {
        flushCount++
        Log.i(TAG, "onFlush #$flushCount（此前已过 $framesSeen 帧）——flush 后帧号必须由外部重新锚定")
        reanchor.onFlush()               // 不连续 ⇒ 自数帧作废，等下一个时间戳
        // ⚠️⚠️ **这里绝不能 `KaraokeBufferAnchor.clear()`**（2026-10-16 真机实测抓到的缺陷）：
        // 流水线在**每条流第一个 `handleBuffer` 内部**会再 flush 一次（configure 之后紧接着），
        // 而 sink 是**先 push 锚点、再进 handleBuffer** 的 —— 在这里清就会把**刚推的那个**锚点抹掉，
        // 于是每条的第一个块 `take(0)` 落空 ⇒ `srcFrame=-1` ⇒ 欠载退回原曲（≈100ms 少一段伴奏）。
        // 真机日志：`sink#4 handleBuffer pts=1e12 …` → `onFlush #1` → `[探针] … 锚缺=1`。
        // 清锚点改在**流边界**做（`onConfigure`）与 sink 层的 `flush()`/`reset()`（真正的 seek）做；
        // seek 时同格式不重建 pipeline，旧锚点会被 `take()` 的「`head[0] < framesSeen` 即作废」规则淘汰。
        if (probeMode != KaraokeAudioSpike.PROBE_OFF) {
            // 步骤 2b：**在 flush 这一刻把 app 请求的目标抄下来**（之后 app 可能又 seek 一次）。
            flushSeq = KaraokeSeekAnchor.seq
            flushReqAbsMs = KaraokeSeekAnchor.absMs
            flushReqRelMs = KaraokeSeekAnchor.relMs
            emit(
                "[探针] flush #$flushCount 计数器=$framesSeen 重锚=$realignCount 锚缺=$anchorMissCount" +
                    " 请求#${flushSeq} abs=${flushReqAbsMs}ms rel=${flushReqRelMs}ms",
            )
            probeBuffersAfterFlush = PROBE_POST_FLUSH
        }
    }

    override fun onReset() {
        Log.i(TAG, "onReset（释放）重锚=$realignCount 锚缺=$anchorMissCount")
        endCapture("onReset")
        inputSampleRate = 0
        inputChannelCount = 0
        inputEncoding = 0
        bytesPerFrame = 0
        framesSeen = 0
        flushCount = 0
        heartbeatSec = -1
        probeBuffersAfterFlush = 0
        lastProbeSec = -1
        reanchor.onRelease()
    }

    // ── 采集（步骤 2c 端到端对齐）────────────────────────────────────────────

    private fun startCapture(firstPtsFrame: Long) {
        val f = captureFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            captureOut = BufferedOutputStream(FileOutputStream(f, false), 1 shl 16)
            capturedFrames = 0L
            captureFirstPtsFrame = firstPtsFrame
            emit(
                "[探针] capture 开始：sampleRate=$inputSampleRate ch=$inputChannelCount " +
                    "encoding=${encodingName(inputEncoding)} 首块pts帧=$firstPtsFrame → ${f.name}",
            )
        }.onFailure { Log.w(TAG, "capture 打开失败", it) }
    }

    private fun writeCapture(buf: ByteBuffer, frames: Int) {
        val out = captureOut ?: return
        val limitFrames = inputSampleRate.toLong() * CAPTURE_SECONDS
        if (capturedFrames >= limitFrames) return
        val take = minOf(frames.toLong(), limitFrames - capturedFrames).toInt()
        val bytes = take * bytesPerFrame
        if (captureBytes.size < bytes) captureBytes = ByteArray(bytes)
        val d = buf.duplicate()
        d.position(buf.position())
        d.get(captureBytes, 0, bytes)
        runCatching { out.write(captureBytes, 0, bytes) }.onFailure { endCapture("写入失败") }
        capturedFrames += take
        if (capturedFrames >= limitFrames) endCapture("采满 ${CAPTURE_SECONDS}s")
    }

    /** 关闭采集并写旁车文件（速率/声道/帧数/首块 pts —— 互相关要用）。 */
    private fun endCapture(reason: String) {
        val out = captureOut ?: return
        captureOut = null
        runCatching { out.flush(); out.close() }
        emit("[探针] capture 结束（$reason）：共 $capturedFrames 帧，首块pts帧=$captureFirstPtsFrame")
        val f = captureFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            File(f.parentFile, f.nameWithoutExtension + ".txt").writeText(
                "sampleRate=$inputSampleRate\nchannels=$inputChannelCount\n" +
                    "encoding=${encodingName(inputEncoding)}\nframes=$capturedFrames\n" +
                    "firstPtsFrame=$captureFirstPtsFrame\nreason=$reason\n" +
                    "bytesPerFrame=$bytesPerFrame\n",
            )
        }
    }
}

/**
 * 档 3 的**时间戳锚定 + 诊断**：包住真正 sink，把每个 buffer 的 `presentationTimeUs` 交给
 * [KaraokeBufferAnchor]，并把播放器报来的**渲染器偏移**交给 [KaraokeMixProcessor.onSinkOffset]，
 * 其余逐字转发。
 *
 * 为什么用 `ForwardingAudioSink` 而不是自己实现 `AudioSink`：`AudioSink` 的缓冲契约
 * （pending / 容量 / `getCurrentPositionUs` 语义）写错会产生**与 v23 同类**的症状；这个类
 * 只多覆写几个方法，其余逐字转发 ⇒ 风险面最小。
 *
 * ### 闸门 1 准备项 a：诊断日志（**带单调序号**）
 * 覆盖 `configure` / `handleBuffer` / `setOutputStreamOffsetUs` / `flush` / `reset` /
 * `handleDiscontinuity`，每条带 `sink#N` 单调序号 —— 把 sink 侧事件与处理器侧日志按时间排成**一条线**。
 * `handleBuffer` 只在**边界附近**详打（[BUFFER_LOGS_AT_BOUNDARY] 块），稳态不打，免得淹没日志。
 *
 * 要回答的两个问题（都靠这套日志）：
 *  1. `setOutputStreamOffsetUs` 到底会不会被调用、每次换条目的值是多少（**累加**才说明能当权威）；
 *  2. `handleDiscontinuity` 与偏移更新、与"新条目第一个 buffer"的**先后顺序**。
 *
 * ⚠️ 去重：`handleBuffer` 在 AudioTrack 缓冲满时可以**返回 false 且不消费**，renderer 会拿
 * **同一个 buffer（同一 pts）** 重调；不去重就会重复入队。`flush`/`reset` 时清空并重置去重。
 */
class KaraokeAnchoringSink(
    inner: AudioSink,
    private val processor: KaraokeMixProcessor,
    /**
     * 真正在链上的 sink 类名。`ForwardingAudioSink` 的 `sink` 字段是 **private**，子类取不到，
     * 只能由构造方（[KaraokeRenderersFactory]）传进来 —— 报告里要写清"实际是哪个 sink"。
     */
    private val innerSinkName: String = inner.javaClass.simpleName,
) : ForwardingAudioSink(inner) {

    /** 单调事件序号：sink 侧事件与处理器侧日志靠它排成一条时间线。 */
    private var seq = 0

    /** `handleBuffer` 的详细打印预算（每次 configure / 偏移变化 / flush 后重新给满）。 */
    private var bufferLogsLeft = 0

    private var lastPtsUs = Long.MIN_VALUE

    private fun log(msg: String) {
        seq++
        val line = "sink#$seq $msg"
        Log.i(TAG, line)
        processor.emitDiag(line)      // 同时进探针文件，长时间连播也不怕 logcat 滚掉
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        lastPtsUs = Long.MIN_VALUE
        bufferLogsLeft = BUFFER_LOGS_AT_BOUNDARY
        log(
            "configure ${inputFormat.sampleRate}Hz ${inputFormat.channelCount}ch " +
                "enc=${inputFormat.sampleMimeType} 内层sink=$innerSinkName " +
                "（处理器此前已过帧=${processor.framesSeen}）",
        )
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (bufferLogsLeft > 0) {
            bufferLogsLeft--
            val bpf = processor.inputChannelCount * 2
            val frames = if (bpf > 0) buffer.remaining() / bpf else -1
            log(
                "handleBuffer pts=${presentationTimeUs}µs 剩余字节=${buffer.remaining()} 帧≈$frames " +
                    "（边界后第 ${BUFFER_LOGS_AT_BOUNDARY - bufferLogsLeft} 块）" +
                    " 处理器已过帧=${processor.framesSeen}",
            )
        }
        if (presentationTimeUs != lastPtsUs) {
            lastPtsUs = presentationTimeUs
            KaraokeBufferAnchor.push(processor.framesSeen, presentationTimeUs)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    /**
     * Media3 用这个告诉我们「流时间 → 渲染器时间」的偏移 —— 含 1e12µs 的 epoch，**并逐条目累加**。
     * 这是 [PtsReanchor] 的权威输入；**不改变任何播放行为**（只记录 + 打日志）。
     */
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        val prev = KaraokeBufferAnchor.outputOffsetUs
        KaraokeBufferAnchor.setOutputOffset(outputStreamOffsetUs)
        processor.onSinkOffset(outputStreamOffsetUs)
        bufferLogsLeft = BUFFER_LOGS_AT_BOUNDARY
        log(
            "setOutputStreamOffsetUs=${outputStreamOffsetUs}µs（上一条=${prev}µs，Δ=${outputStreamOffsetUs - prev}µs）" +
                " ⇒ 条目切换累计 ${processor.reanchor.itemChangeCount} 次",
        )
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    /**
     * 渲染器侧发现"时间不连续"（换条目 / seek 预滚动丢弃）。
     * 日志里把**当时的**渲染器偏移打出来：真机实测的顺序是
     * `setOutputStreamOffsetUs → handleDiscontinuity → configure → handleBuffer`，
     * 所以这里打出来的通常已经是**新条目**的偏移 —— 打它就是为了留证这个先后。
     */
    override fun handleDiscontinuity() {
        processor.onSinkDiscontinuity()
        log(
            "handleDiscontinuity（pts 不连续）处理器已过帧=${processor.framesSeen} " +
                "此刻偏移=${KaraokeBufferAnchor.outputOffsetUs}µs",
        )
        super.handleDiscontinuity()
    }

    override fun flush() {
        lastPtsUs = Long.MIN_VALUE
        bufferLogsLeft = BUFFER_LOGS_AT_BOUNDARY
        log("flush 处理器已过帧=${processor.framesSeen} 偏移=${KaraokeBufferAnchor.outputOffsetUs}µs")
        KaraokeBufferAnchor.clear()
        super.flush()
    }

    override fun reset() {
        lastPtsUs = Long.MIN_VALUE
        log("reset")
        KaraokeBufferAnchor.clear()
        super.reset()
    }
}

/**
 * 只覆写 [buildAudioSink] 的 `DefaultRenderersFactory`。
 *
 * 为什么是这里而不是 `ExoPlayer.Builder`：Media3 **1.7.1 的 `ExoPlayer.Builder` 没有
 * `buildAudioSink`**（javap 核实），插入点在这个 factory 上。
 *
 * `buildAudioSink` 的实现**刻意与 Media3 默认逐行一致，只多 `setAudioProcessors`（档 3 再多一层
 * 包装 sink）** —— 任何其它偏离都会成为新的混杂因素，让"挂了到底是谁的锅"说不清。
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
        if (mode != KaraokeAudioSpike.MODE_PASSTHROUGH && mode != KaraokeAudioSpike.MODE_ANCHORED &&
            mode != KaraokeAudioSpike.MODE_MIX
        ) {
            Log.i("KaraokeMix", "buildAudioSink：不挂处理器（mode=$mode）")
            return base.build()
        }
        val probe = KaraokeAudioSpike.probeMode(appContext)
        // 探针写文件（步骤 2b）：logcat 会滚、监听也只是部分流，文件才是权威。
        // **总是准备好文件**（哪怕当前探针是关）——探针模式可在运行期切，换歌不重建 sink，
        // 若此刻不给文件，运行期从「关」切到「帧号真值」就写不出东西了。
        val dir = appContext.getExternalFilesDir(null)
        val logFile = File(dir, "karaoke_probe.log")
        val captureFile = File(dir, "karaoke_capture.pcm")
        val anchored = mode == KaraokeAudioSpike.MODE_ANCHORED || mode == KaraokeAudioSpike.MODE_MIX
        // **常驻处理器一律"可以混音"**：真正要不要混由运行期 `KaraokeMixControl.mode` 每块决定，
        // 这样点伴唱只切内部模式、不用重建播放器（重建会打断正在播的歌）。
        // 原曲档走的是**透传分支**（不是算 1×原曲+0×伴奏），保证与无处理器逐位一致。
        val mixing = anchored
        val proc = KaraokeMixProcessor(
            initialProbeMode = probe,
            logFile = logFile,
            captureFile = captureFile,
            anchored = anchored,
            mixing = mixing,
        )
        val sink = base.setAudioProcessors(arrayOf(proc)).build()
        return if (anchored) {
            Log.i(
                "KaraokeMix",
                "buildAudioSink：挂处理器 + 时间戳锚定（mode=$mode, 混音=$mixing, " +
                    "探针=${KaraokeAudioSpike.describeProbe(probe)}）",
            )
            KaraokeAnchoringSink(sink, proc, sink.javaClass.simpleName)
        } else {
            Log.i("KaraokeMix", "buildAudioSink：挂 1:1 透传处理器（mode=2, 探针=${KaraokeAudioSpike.describeProbe(probe)}）")
            sink
        }
    }

    /**
     * 闸门 1 准备项 a：**只加一行日志**地打出实际渲染器类名（报告要写"FLAC/MP3 到底走的哪个渲染器"）。
     *
     * 实现刻意是「`super` 原样 + 打日志」——**不增删任何渲染器**，避免成为新的混杂因素。
     * 这是唯一能拿到渲染器类名的地方：`ExoPlayer` 不对外暴露 `audioRenderers`。
     */
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val before = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        Log.i(
            "KaraokeMix",
            "buildAudioRenderers：sink=${audioSink.javaClass.simpleName} " +
                "新增渲染器=${(before until out.size).joinToString { out[it].javaClass.simpleName }}",
        )
    }
}

package com.shiyin.music.spike.karaoke

import android.content.Context
import com.shiyin.music.data.Track
import com.shiyin.music.data.karaoke.AudioDecoder
import com.shiyin.music.data.karaoke.AudioFingerprint
import com.shiyin.music.data.karaoke.MdxModel
import com.shiyin.music.data.karaoke.SeparationJob
import com.shiyin.music.data.karaoke.StemCache
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v1.5.0 块 3：**把分离产物按块灌进缓存**，供播放链路按**绝对源帧号**读取。
 *
 * 与 [KaraokeStemRunner] 的区别：那个是"整首导出 WAV"的调试工具；这个是**正式路径** ——
 * 产物进 [StemCache]，处理器按帧号读，**读不到就退回原曲**（欠载回退在处理器里）。
 *
 * 为什么"按物理文件"：拆分单曲的各段共用同一个整轨文件，缓存键必须由**文件**决定
 * （块 2 已改），否则同一份产出会被算 N 遍、而且拖动跨段时认不出是同一块。
 */
object KaraokeCacheJob {

    /** 模型版本：算法/参数变化时 +1（旧缓存自然失效）。 */
    const val MODEL_VERSION = "1"

    class Handle(val key: StemCache.SourceKey, val dir: File, val job: Job)

    /**
     * 起一次"灌缓存"。**首段可用**（[onReady]）在"播放头所在那块写好"或"缓存本来就全"时触发一次。
     *
     * @param playFrame 播放头（**源文件帧号**）——用它决定"先算哪块"和"什么时候算可用"
     */
    suspend fun start(
        context: Context,
        track: Track,
        cache: StemCache,
        playFrame: () -> Long,
        model: MdxModel = MdxModel.M9482,
        threads: Int = 4,
        onStatus: (String) -> Unit = {},
        onReady: (File) -> Unit,
    ): Handle = withContext(Dispatchers.Default) {
        val tmp = File(context.cacheDir, "karaoke_src.pcm")
        onStatus("准备中…")
        val info = AudioDecoder.decodeToPcm16File(context, track.uri, tmp)
        val fp = fingerprintOf(tmp, info)
        val key = StemCache.SourceKey(fp, info.srcRate, 2, info.frames)

        // ⚠️⚠️ **两个帧域必须分清**（P0-1「伴奏升调/加速」的根因）：
        //  · **源域**：`info.srcRate` / `info.frames` —— 播放器与伴奏读取侧用；
        //  · **模型域**：恒 **44100**，帧数 = `ceil(源帧数 × 44100 / 源率)` —— 分离引擎与缓存块用。
        // 分离引擎只在 44.1k 里工作（模型的输入必须是 44.1k），而 `AudioDecoder.PcmFileReader`
        // 的索引空间也是 44.1k。此前把**源帧数**直接喂给它 ⇒ 48k 歌的伴奏被压成 `48/44.1 = 1.0884`
        // 倍速（+1.47 半音）；44.1k 歌比值恰好为 1，所以一直没暴露。
        val modelFrames = Math.ceil(info.frames.toDouble() * 44100.0 / info.srcRate).toInt()

        onStatus("加载模型…")
        val modelFile = KaraokeStemRunner.ensureModelFile(context, model)
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
        val session = env.createSession(modelFile.absolutePath, opts)
        val job = SeparationJob(model, env, session, cache)
        val gen = job.genSize
        val dir = cache.variantDir(key, model.id, MODEL_VERSION)

        // 缓存本来就全 → 立刻可用
        if (cache.findComplete(key, model.id, MODEL_VERSION) != null) {
            onStatus("伴奏已就绪")
            onReady(dir)
            return@withContext Handle(key, dir, Job().apply { complete() })
        }

        val work = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            var fired = false
            onStatus("分离中…")
            job.run(
                key = key,
                modelVersion = MODEL_VERSION,
                provider = AudioDecoder.PcmFileReader(info),
                modelFrames = modelFrames,
                onProgress = { p ->
                    // "首段可用"= 播放头所在那块已经写好（不是"第一块写好"——用户可能从中间点伴唱）
                    if (!fired && cache.hasChunk(dir, (playFrame() / gen).toInt())) {
                        fired = true
                        onReady(dir)
                    }
                    if (!fired && p.chunkDone >= p.chunkTotal) {
                        fired = true
                        onReady(dir)
                    }
                    if (!fired) {
                        onStatus("分离中 ${p.chunkDone}/${p.chunkTotal}")
                    }
                },
            )
            if (!fired) onReady(dir)
        }
        Handle(key, dir, work)
    }

    /**
     * 指纹只吃**前 20 秒**（`AudioFingerprint.LEAD_FRAMES`）——
     * 整首 PCM 读进内存会撞破 Java 堆上限（85MB，2026-10-11 真机 OOM 实测）。
     */
    private fun fingerprintOf(pcmFile: File, info: AudioDecoder.PcmFileInfo): String {
        val frames = minOf(info.frames, AudioFingerprint.LEAD_FRAMES)
        val bytes = ByteArray(frames * 4)
        java.io.RandomAccessFile(pcmFile, "r").use { raf ->
            var read = 0
            while (read < bytes.size) {
                val n = raf.read(bytes, read, bytes.size - read)
                if (n <= 0) break
                read += n
            }
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(bytes.size / 2)
        buf.get(pcm)
        return AudioFingerprint.of(pcm, frames)
    }
}

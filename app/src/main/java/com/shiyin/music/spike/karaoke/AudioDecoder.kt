package com.shiyin.music.spike.karaoke

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * P0 Spike 阶段 B：把曲库里的歌解码成 44.1kHz 立体声 float PCM。
 *
 * 用 MediaExtractor + MediaCodec（与 ExoPlayer 不同的解码路径——规格 §5.3 提醒
 * 这可能带来 encoder delay/padding 偏移，S5 要专门测；本阶段只用于"跑通 + 导出听"）。
 *
 * 非 44.1k 源用线性插值重采样（spike 够用；正式实现要换高质量重采样）。
 */
object AudioDecoder {

    class Pcm(val left: FloatArray, val right: FloatArray, val srcRate: Int)

    class Stereo(val left: FloatArray, val right: FloatArray, val sampleRate: Int)

    fun decodeToStereo44k(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): Stereo {
        val pcm = decodePcm(context, uri, onProgress)
        return if (pcm.srcRate == 44100) {
            Stereo(pcm.left, pcm.right, 44100)
        } else {
            val ratio = 44100.0 / pcm.srcRate
            Stereo(
                resampleLinear(pcm.left, ratio),
                resampleLinear(pcm.right, ratio),
                44100,
            )
        }
    }

    /** 解码为源采样率的立体声 float（-1..1）。 */
    private fun decodePcm(context: Context, uri: Uri, onProgress: (Float) -> Unit): Pcm {
        val extractor = MediaExtractor()
        val codec: MediaCodec
        val trackIndex: Int
        try {
            extractor.setDataSource(context, uri, null)
            trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("文件里没有音频轨")
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (t: Throwable) {
            extractor.release()
            throw t
        }

        val outL = FloatArrayList()
        val outR = FloatArrayList()
        var srcRate = 44100
        var channels = 2
        val info = MediaCodec.BufferInfo()

        // 估算总时长用于进度（format 的 KEY_DURATION 单位 µs）
        val durationUs = runCatching {
            extractor.getTrackFormat(trackIndex).getLong(MediaFormat.KEY_DURATION)
        }.getOrDefault(0L)

        try {
            var inputDone = false
            var outputDone = false
            var isFloat = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4 // ENCODING_PCM_FLOAT
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (info.size > 0) {
                            appendFrames(buf, channels, isFloat, outL, outR)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }

        val l = outL.toArray()
        val r = outR.toArray()
        // 单声道 → 复制成立体声
        return Pcm(l, r, srcRate)
    }

    private fun appendFrames(
        buf: ByteBuffer,
        channels: Int,
        isFloat: Boolean,
        outL: FloatArrayList,
        outR: FloatArrayList,
    ) {
        if (isFloat) {
            val fb = buf.asFloatBuffer()
            while (fb.remaining() >= channels) {
                val l = fb.get()
                val r = if (channels >= 2) fb.get() else l
                for (c in 2 until channels) fb.get()
                outL.add(l); outR.add(r)
            }
        } else {
            val sb = buf.asShortBuffer()
            while (sb.remaining() >= channels) {
                val l = sb.get() / 32768f
                val r = if (channels >= 2) sb.get() / 32768f else l
                for (c in 2 until channels) sb.get()
                outL.add(l); outR.add(r)
            }
        }
    }

    private fun resampleLinear(x: FloatArray, ratio: Double): FloatArray {
        val n = (x.size * ratio).roundToInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val pos = i / ratio
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val a = x[i0.coerceIn(0, x.size - 1)]
            val b = x[(i0 + 1).coerceIn(0, x.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /** 简易可增长 float 数组（避免装箱）。 */
    private class FloatArrayList(initial: Int = 1 shl 20) {
        private var a = FloatArray(initial)
        private var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): FloatArray = a.copyOf(n)
    }
}

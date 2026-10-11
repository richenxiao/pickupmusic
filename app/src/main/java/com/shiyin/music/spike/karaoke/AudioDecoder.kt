package com.shiyin.music.spike.karaoke

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * P0 Spike 阶段 B：音频解码 + 逐块读取（流式）。
 *
 * 为什么落盘：整首歌 float 数组（4 分钟 ≈ 2×10.6M×4B ≈ 85MB）加上输出两条轨会撞破
 * 256MB 的 Java 堆上限（2026-10-11 真机 OOM 实测）。故解码后写成 16bit PCM 临时文件，
 * 处理时按块随机读——峰值内存只剩"一个块"的量级。
 *
 * 采样率非 44.1k 时在读取阶段按比例线性插值（spike 够用；正式实现换高质量重采样）。
 */
object AudioDecoder {

    class PcmFileInfo(val file: File, val srcRate: Int, val frames: Int) {
        val durationSec: Float get() = frames / srcRate.toFloat()
    }

    /** 整首驻留内存的立体声（仅 JVM 对拍测试用；手机端走文件流式）。 */
    class Stereo(val left: FloatArray, val right: FloatArray, val sampleRate: Int)

    /** 解码 → 交错 16bit PCM 临时文件。返回源采样率与帧数。 */
    fun decodeToPcm16File(
        context: Context,
        uri: Uri,
        outFile: File,
        onProgress: (Float) -> Unit = {},
    ): PcmFileInfo {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var srcRate = 44100
        var channels = 2
        var framesWritten = 0
        var isFloat = false

        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("文件里没有音频轨")
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            val info = MediaCodec.BufferInfo()

            DataOutputStream(outFile.outputStream().buffered(1 shl 16)).use { out ->
                var inputDone = false
                var outputDone = false
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
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outIdx >= 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (info.size > 0) {
                                framesWritten += writeFrames(out, buf, channels, isFloat)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (durationUs > 0) {
                                onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
        return PcmFileInfo(outFile, srcRate, framesWritten)
    }

    /**
     * 交错写 [L, R] 16bit（**小端**），返回写入帧数。
     *
     * ⚠️ 2026-10-11 真机事故：这里原来用 `DataOutputStream.writeShort`（**大端**），
     * 而读取器按小端解析 → 字节错位 → 输出全是噪声（SNR −3.7dB）。
     * 临时 PCM 文件是内部格式，两端必须显式约定字节序；此处为小端。
     */
    private fun writeFrames(out: DataOutputStream, buf: ByteBuffer, channels: Int, isFloat: Boolean): Int {
        var n = 0
        if (isFloat) {
            val fb = buf.asFloatBuffer()
            while (fb.remaining() >= channels) {
                val l = fb.get(); val r = if (channels >= 2) fb.get() else l
                for (c in 2 until channels) fb.get()
                writeShortLE(out, toPcm16(l)); writeShortLE(out, toPcm16(r)); n++
            }
        } else {
            val sb = buf.asShortBuffer()
            while (sb.remaining() >= channels) {
                val l = sb.get(); val r = if (channels >= 2) sb.get() else l
                for (c in 2 until channels) sb.get()
                writeShortLE(out, l.toInt()); writeShortLE(out, r.toInt()); n++
            }
        }
        return n
    }

    /** 交错 PCM 临时文件的小端写入；测试与生产共用（internal 以免两边字节序再分叉）。 */
    internal fun writeShortLE(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun toPcm16(v: Float): Int = (v.coerceIn(-1f, 1f) * 32767f).toInt()

    /** 从 PCM16 临时文件按 44.1k 帧号读块（越界补零；必要时线性重采样）。 */
    class PcmFileReader(private val info: PcmFileInfo, targetRate: Int = 44100) : MdxSeparator.ChunkProvider {
        private val ratio = info.srcRate.toDouble() / targetRate
        private val raf = RandomAccessFile(info.file, "r")
        private var srcBuf = ShortArray(0)
        private var srcBufStart = 0

        override fun readChunk(startFrame: Int, count: Int, left: FloatArray, right: FloatArray) {
            // 需要的源帧范围（含插值所需的前后各 1 帧）
            val srcFrom = floor(startFrame * ratio).toInt() - 1
            val srcTo = ceil((startFrame + count) * ratio).toInt() + 1
            val need = srcTo - srcFrom
            // srcBuf 是**交错 L,R**，容量必须是帧数的 2 倍（2026-10-11 越界崩溃的根因：
            // 原按帧数分配，却用 idx*2+1 索引）
            if (srcBuf.size < need * 2) srcBuf = ShortArray(need * 2)
            readSource(srcFrom, need)
            srcBufStart = srcFrom

            for (i in 0 until count) {
                val pos = (startFrame + i) * ratio
                val i0 = floor(pos).toInt()
                val frac = (pos - i0).toFloat()
                val aL = sample(i0, 0); val aR = sample(i0, 1)
                val bL = sample(i0 + 1, 0); val bR = sample(i0 + 1, 1)
                left[i] = aL + (bL - aL) * frac
                right[i] = aR + (bR - aR) * frac
            }
        }

        /** srcFrame 越界返回 0（等价于两端补零）。 */
        private fun sample(srcFrame: Int, ch: Int): Float {
            val idx = srcFrame - srcBufStart
            if (srcFrame < 0 || srcFrame >= info.frames) return 0f
            if (idx < 0 || idx * 2 + 1 >= srcBuf.size) return 0f
            return srcBuf[idx * 2 + ch] / 32768f
        }

        private fun readSource(fromFrame: Int, count: Int) {
            val bytes = ByteArray(count * 4)
            val first = fromFrame.coerceAtLeast(0)
            val last = (fromFrame + count).coerceAtMost(info.frames)
            java.util.Arrays.fill(srcBuf, 0, count * 2, 0)
            if (last <= first) return
            raf.seek(first.toLong() * 4)
            var off = 0
            var got = 0
            val want = (last - first) * 4
            while (got < want) {
                val r = raf.read(bytes, got, want - got)
                if (r < 0) break
                got += r
            }
            var p = (first - fromFrame) * 2
            // ⚠️ 必须 & 0xFF：Kotlin 的 Byte.toInt() 会符号扩展（0xFF → -1），
            // 不掩码会把低位字节污染成 0xFFFFFFFF（真机实测 0.5 被读成 −1 LSB）。
            for (i in 0 until (last - first)) {
                val l = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
                val r = (bytes[off + 2].toInt() and 0xFF) or ((bytes[off + 3].toInt() and 0xFF) shl 8)
                srcBuf[p++] = l.toShort()
                srcBuf[p++] = r.toShort()
                off += 4
            }
        }

        fun close() {
            runCatching { raf.close() }
        }
    }
}

package com.shiyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v1.5.0 P2 spike 步骤 2：**帧号编解码的跨语言一致性**。
 *
 * 两条独立的检查，缺一不可：
 *  1. 纯向量往返（本类自身不矛盾）；
 *  2. **直接读 [tools/karaoke/make_framenum_fixture.py] 生成的文件**解码，与"文件里本来
 *     写的是第几帧"对拍 —— 这才叫跨语言对拍；否则两边各自自说自话，谁错了都不知道。
 *
 * 夹具缺失时跳过（`.karaoke/` 是 gitignored，别的机器上不会有）——与 `SeparationJobTest` 同一约定。
 */
class KaraokeFrameCodecTest {

    private fun repoRoot(): File {
        var f = File(System.getProperty("user.dir"))
        while (!File(f, "settings.gradle.kts").exists() && f.parentFile != null) f = f.parentFile
        return f
    }

    @Test
    fun `纯向量往返`() {
        for (bits in intArrayOf(8, 10, 12)) {
            val span = 1 shl (2 * bits)
            val probes = listOf(0, 1, 2, (1 shl bits) - 1, 1 shl bits, 100_000, span / 2, span - 1)
            for (raw in probes) {
                val idx = raw % span
                val (l, r) = KaraokeFrameCodec.encodeFrame(idx, bits)
                // 采样值必须落在 int16 范围内，否则写进 PCM 会被截断
                for (v in intArrayOf(l, r)) {
                    assertTrue("bits=$bits idx=$idx 采样值越界: $v", v in -32768..32767)
                }
                assertEquals("bits=$bits idx=$idx", idx, KaraokeFrameCodec.decodeFrame(l, r, bits))
            }
        }
    }

    @Test
    fun `负半轴也要正确（掩码与偏置最容易在这里错）`() {
        // 帧号低 12 位为 0 时 L = -2048；高位为 0 时 R = -2048 —— 负数路径必须一致
        val (l0, r0) = KaraokeFrameCodec.encodeFrame(0)
        assertEquals(-2048, l0)
        assertEquals(-2048, r0)
        assertEquals(0, KaraokeFrameCodec.decodeFrame(l0, r0))
        // 4095 → 低 12 位全 1 → L = +2047；4096 → 低 12 位归零、进位到 R
        assertEquals(2047, KaraokeFrameCodec.encodeFrame(4095).first)
        assertEquals(4095, KaraokeFrameCodec.decodeFrame(2047, -2048))
        assertEquals(4096, KaraokeFrameCodec.decodeFrame(-2048, -2047))
    }

    /**
     * **字节序回归测试（本项目第 4 个字节序坑，2026-10-13 真机实测抓出）**。
     *
     * 现场：探针里 `buf.duplicate()` 出来的 `ByteBuffer` **没设小端**（Java 默认大端）→
     * 每个采样的两个字节被调换 → 测试文件的斜坡步长 1 变成 256、还溢出回绕 →
     * 解出来的"真值帧号"全是天文数字（9,406,712 / 15,726,592 …），害我一度以为管线把数据 ×256 了。
     *
     * 这条测试**不依赖真机**：直接把 fixture 的**原始字节**喂进缓冲，钉住三件事——
     * ① 正确做法（强制小端）解出真值 0,1,2,…；
     * ② 真值本身是 −2048,−2047,−2046（小端读）；
     * ③ **默认大端读会得到 248,504,760**（= 当初探针打印的东西，调换字节的产物）。
     */
    @Test
    fun `帧号必须按小端读缓冲`() {
        val f = File(File(repoRoot(), ".karaoke/fixtures"), "fixture_fn_44100.wav")
        assumeTrue("需要夹具 ${f.path}", f.isFile)
        val bytes = readWavDataBytes(f)

        // ★ 现场复刻：ByteBuffer 的默认字节序就是大端
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // ① 正确路径：强制小端 → 解出真值
        for (i in 0 until 6) {
            assertEquals(
                "第 $i 帧：强制小端应解出真值（若这里失败，说明解码规则或字节序处理不对）",
                i,
                KaraokeFrameCodec.decodeFrameAt(buf, i * 4),
            )
        }
        // ② 原始左声道（小端读）应是 −2048 起的斜坡
        val le = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(
            "夹具左声道前 3 个采样（小端）",
            listOf(-2048, -2047, -2046),
            (0 until 3).map { le.getShort(it * 4).toInt() },
        )
        // ③ 默认大端读 = 当初的 bug 现场（字节调换）
        assertEquals(
            "默认大端读同一批字节 → 就是当初打印的 248/504/760",
            listOf(248, 504, 760),
            (0 until 3).map { buf.duplicate().getShort(it * 4).toInt() },
        )
    }

    @Test
    fun `读 Python 生成的真值文件解码（跨语言对拍）`() {
        val dir = File(repoRoot(), ".karaoke/fixtures")
        val checked = ArrayList<String>()
        for (rate in intArrayOf(44100, 48000, 96000)) {
            val f = File(dir, "fixture_fn_$rate.wav")
            assumeTrue("需要夹具 ${f.path}（用 make_framenum_fixture.py 生成）", f.isFile)
            val samples = readWavStereo16(f)
            val frames = samples.size / 2
            val probes = listOf(0, 1, 4095, 4096, 100_000, frames / 2, frames - 1)
            for (idx in probes) {
                assertEquals(
                    "rate=$rate 第 $idx 帧自带的帧号解错（两侧规则不一致）",
                    idx,
                    KaraokeFrameCodec.decodeAt(samples, idx),
                )
            }
            checked += "${rate}Hz(${frames}帧×${probes.size}点)"
        }
        println("[步骤2] 跨语言对拍通过：${checked.joinToString(" ")}")
    }

    /** 取 WAV 里 `data` 块的**原始字节**（不解码）——字节序回归测试要的就是"原样的字节"。 */
    private fun readWavDataBytes(f: File): ByteArray {
        val b = f.readBytes()
        fun u32(off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
        fun tag(off: Int) = String(b, off, 4, Charsets.US_ASCII)
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = tag(pos)
            val size = u32(pos + 4)
            val body = pos + 8
            if (id == "data") return b.copyOfRange(body, minOf(body + size, b.size))
            pos = body + size + (size and 1)
        }
        error("WAV 里没找到 data 块：${f.path}")
    }

    /**
     * 读 PCM 16bit 立体声 WAV → 交错 ShortArray。
     *
     * **自己解 RIFF 头**，不用 `javax.sound.sampled`：Android 的单测对着 `android.jar` 编译，
     * 编译类路径上没有完整 JDK，`javax.sound.*` 直接 Unresolved（实测踩过）。
     * 夹具由 Python `wave` 模块生成，是标准 44 字节头的 PCM，解析足够简单。
     */
    private fun readWavStereo16(f: File): ShortArray {
        val b = f.readBytes()
        fun u32(off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
        fun tag(off: Int) = String(b, off, 4, Charsets.US_ASCII)
        assertEquals("应为 RIFF", "RIFF", tag(0))
        assertEquals("应为 WAVE", "WAVE", tag(8))

        var pos = 12
        var channels = 0
        var bits = 0
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = tag(pos)
            val size = u32(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = (b[body + 2].toInt() and 0xFF) or ((b[body + 3].toInt() and 0xFF) shl 8)
                    bits = (b[body + 14].toInt() and 0xFF) or ((b[body + 15].toInt() and 0xFF) shl 8)
                }
                "data" -> {
                    dataOff = body
                    dataLen = minOf(size, b.size - body)
                }
            }
            pos = body + size + (size and 1) // 块按偶数字节对齐
        }
        assertEquals("夹具应为 16bit", 16, bits)
        assertEquals("夹具应为立体声", 2, channels)
        check(dataOff >= 0) { "WAV 里没找到 data 块：${f.path}" }
        return ShortArray(dataLen / 2).also { out ->
            for (i in out.indices) {
                val o = dataOff + i * 2
                out[i] = (((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort())
            }
        }
    }
}

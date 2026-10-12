package com.shiyin.music.data.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v1.5.0 P1：音频内容指纹（缓存身份的一部分）。
 *
 * 用户的关键要求：指纹**只能依赖音频内容**——本应用会往音频文件里写封面/歌词标签，
 * 写完标签后缓存必须仍然命中。指纹直接从**解码后的 PCM** 计算，容器元数据的任何变化
 * （标签、封面、文件大小、mtime）都不参与，故天然与"写标签"无关；这里把它钉死。
 */
class AudioFingerprintTest {

    private fun pcm(frames: Int, seed: Int) =
        ShortArray(frames * 2) { i -> ((i * 7 + seed) % 30000 - 15000).toShort() }

    @Test
    fun sameAudioSameFingerprint() {
        val a = pcm(1000, 42)
        val b = pcm(1000, 42)   // 同样内容、不同数组实例（模拟"重新解码同一段音频"）
        assertEquals(AudioFingerprint.of(a, 1000), AudioFingerprint.of(b, 1000))
    }

    @Test
    fun differentAudioDifferentFingerprint() {
        assertNotEquals(
            AudioFingerprint.of(pcm(1000, 1), 1000),
            AudioFingerprint.of(pcm(1000, 2), 1000),
        )
    }

    /** 指纹只看前 LEAD_FRAMES：尾部（此处超出 frames 的部分）不参与。 */
    @Test
    fun tailBeyondLeadFramesDoesNotMatter() {
        val lead = AudioFingerprint.LEAD_FRAMES
        val head = pcm(lead, 5)
        val extraTailChanged = ShortArray(lead * 2 + 200) { i -> if (i < lead * 2) head[i] else 12345 }
        assertEquals(
            AudioFingerprint.of(head, lead),
            AudioFingerprint.of(extraTailChanged, lead),
        )
    }

    /** 单声道/立体声互串（L 与 R 互换）必须得到不同指纹。 */
    @Test
    fun channelSwapChangesFingerprint() {
        val a = ShortArray(200) { i -> if (i % 2 == 0) 100 else 200 }
        val b = ShortArray(200) { i -> if (i % 2 == 0) 200 else 100 }
        assertNotEquals(AudioFingerprint.of(a, 100), AudioFingerprint.of(b, 100))
    }

    @Test
    fun shortAudioIsHandled() {
        // 不足 20 秒的音频：按实际帧数算，不越界
        val s = pcm(10, 3)
        assertEquals(AudioFingerprint.of(s, 10), AudioFingerprint.of(s, 10))
    }
}

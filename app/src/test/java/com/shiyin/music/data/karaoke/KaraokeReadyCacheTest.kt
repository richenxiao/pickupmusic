package com.shiyin.music.data.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/**
 * A75（用户：中途取消伴奏，再点按钮又开始加载）—— **不重新解码整首 / 不重建 ONNX 会话** 的证明。
 *
 * `KaraokeCacheJob.start` 的顺序是「解码整首 → 建 ORT 会话 → 才轮到 `findComplete`」，
 * 所以"能不能复用目录"直接决定了用户会不会再看到一次转圈。
 * 这里把那条寿命规则钉死：
 *  · 取消 / 回原曲 ⇒ **仍可复用**（否则就是重新解码那一路）；
 *  · 换曲         ⇒ 不可复用（目录是上一首的）；
 *  · 源文件变了   ⇒ 不可复用（廉价身份对不上）。
 */
class KaraokeReadyCacheTest {

    private val dirA = File("/tmp/karaoke/A")
    private val dirB = File("/tmp/karaoke/B")
    private val srcA = "/music/a.flac|1000|111"
    private val srcB = "/music/b.flac|2000|222"

    @Test
    fun `取消后再点_仍可复用同一个目录`() {
        val c = KaraokeReadyCache()
        c.mark(dirA, trackId = 7L, sourceIdentity = srcA)

        // 用户点了取消 / 切回原曲
        c.keepAcrossStop()

        assertSame(
            "取消绝不能丢掉目录 —— 丢了他下次点就要重新解码整首 + 重建会话",
            dirA, c.reusableFor(7L, srcA),
        )
    }

    @Test
    fun `同一首歌反复取消再点_始终命中`() {
        val c = KaraokeReadyCache()
        c.mark(dirA, 7L, srcA)
        repeat(5) {
            c.keepAcrossStop()
            assertSame(dirA, c.reusableFor(7L, srcA))
        }
    }

    @Test
    fun `换曲_必须丢掉上一首的目录`() {
        val c = KaraokeReadyCache()
        c.mark(dirA, 7L, srcA)
        c.onTrackChanged(8L)                 // 换曲
        assertNull("换曲后绝不能拿上一首的目录（那会按绝对帧号读错伴奏）", c.reusableFor(7L, srcA))
        assertNull(c.reusableFor(8L, srcB))
    }

    @Test
    fun `单曲循环重播_同 id 不算换曲_仍可复用`() {
        val c = KaraokeReadyCache()
        c.mark(dirA, 7L, srcA)
        c.onTrackChanged(7L)                 // 同一个 id（重播）
        assertSame(dirA, c.reusableFor(7L, srcA))
    }

    @Test
    fun `源文件变了_不得复用陈旧目录`() {
        val c = KaraokeReadyCache()
        c.mark(dirA, 7L, srcA)
        // 同一个曲目 id，但文件被替换过（路径/字节数/加入时间任一不同）
        assertNull(
            "id 相同但源身份变了 ⇒ 必须当作新的一首，重新走分离",
            c.reusableFor(7L, "/music/a.flac|9999|999"),
        )
        assertSame("原身份仍可用", dirA, c.reusableFor(7L, srcA))
    }

    @Test
    fun `还没标记过_一律不可复用`() {
        val c = KaraokeReadyCache()
        assertNull(c.reusableFor(7L, srcA))
        c.keepAcrossStop()
        assertNull(c.reusableFor(7L, srcA))
    }

    @Test
    fun `显式 clear 之后不可复用`() {
        val c = KaraokeReadyCache()
        c.mark(dirB, 7L, srcA)
        c.clear()
        assertNull(c.reusableFor(7L, srcA))
    }

    @Test
    fun `重新标记会覆盖owner`() {
        val c = KaraokeReadyCache()
        c.mark(dirA, 7L, srcA)
        c.mark(dirB, 8L, srcB)
        assertEquals(dirB, c.reusableFor(8L, srcB))
        assertNull(c.reusableFor(7L, srcA))
    }
}

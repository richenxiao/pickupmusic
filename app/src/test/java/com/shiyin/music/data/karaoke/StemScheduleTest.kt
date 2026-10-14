package com.shiyin.music.data.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.5.0 块 2 的闸门测试：**缓存键以物理文件为准** + **"切到哪算到哪"的调度**。
 *
 * 为什么这两件事必须一起测：它们都建立在同一个前提上 —— **坐标系是源文件帧号**，
 * 而不是"哪首歌"。拆分单曲的各段共用同一个整轨文件，帧号才是同一套坐标；
 * 按 songId 挂缓存会让同一份产出被算 N 遍，而且拖动跨段时认不出是同一块。
 */
class StemScheduleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun key(fingerprint: String = "ab12cd34", rate: Int = 44100, ch: Int = 2, frames: Int = 2_646_000) =
        StemCache.SourceKey(fingerprint, rate, ch, frames)

    // ── 缓存键：物理文件维度 ───────────────────────────────────────────────

    @Test
    fun `同一个物理文件的多个片段_缓存键完全相同（拆分母曲共享一份产出）`() {
        // 拆分单曲：同一个整轨文件 → 同一份分离产出。三段"歌"的帧号区间不同，
        // 但传进来的 SourceKey 由**文件**决定，所以必须相等。
        val a = key()   // 第 1 段
        val b = key()   // 第 5 段（同一个文件、同一批参数）
        assertEquals(a, b)
        assertEquals(a.dirName, b.dirName)
    }

    @Test
    fun `文件换了或参数变了_缓存键必须不同`() {
        val base = key()
        assertNotEquals("指纹变了（换了文件/文件内容变了）", base, key(fingerprint = "ffffffff"))
        assertNotEquals("总帧数变了（文件被裁剪/替换）", base, key(frames = 2_646_001))
        assertNotEquals("采样率变了", base, key(rate = 48000))
        assertNotEquals("声道数变了", base, key(ch = 1))
    }

    @Test
    fun `不同文件的缓存目录不会撞在一起`() {
        val cache = StemCache(tmp.newFolder("cache"))
        val d1 = cache.variantDir(key(fingerprint = "aaaa1111"), "mdx_9482", "v1")
        val d2 = cache.variantDir(key(fingerprint = "bbbb2222"), "mdx_9482", "v1")
        assertNotEquals(d1.absolutePath, d2.absolutePath)
        // 且都在同一个 modelId 下、彼此不嵌套
        assertTrue(d1.absolutePath.contains("mdx_9482"))
        assertTrue(d2.absolutePath.contains("mdx_9482"))
    }

    @Test
    fun `模型或版本不同_目录也不同（换模型不会读到旧产物）`() {
        val cache = StemCache(tmp.newFolder("cache2"))
        val k = key()
        val v1 = cache.variantDir(k, "mdx_9482", "v1")
        val v2 = cache.variantDir(k, "mdx_9482", "v2")
        val other = cache.variantDir(k, "mdx_inst_hq_3", "v1")
        assertNotEquals(v1.absolutePath, v2.absolutePath)
        assertNotEquals(v1.absolutePath, other.absolutePath)
    }

    // ── 调度：切到哪算到哪 ────────────────────────────────────────────────

    private val gen = 44100 * 6   // 约 6 秒一块（与实现的 genSize 同量级）

    @Test
    fun `全齐了就返回空_不会无谓地重算`() {
        val total = 10
        assertEquals(emptyList<Int>(), StemSchedule.nextChunks(0, gen, total, { true }))
    }

    @Test
    fun `从播放头开始往前派_而且是按顺序`() {
        val total = 10
        val done = setOf(0, 1, 2)              // 播放头在块 4
        val got = StemSchedule.nextChunks(gen * 4L + 123, gen, total, { it in done }, batch = 5)
        assertEquals(listOf(4, 5, 6, 7, 8), got)
    }

    @Test
    fun `播放头在最后一块的末尾_也要能把前面没算的补上`() {
        val total = 10
        val done = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8)   // 只差最后一块
        val got = StemSchedule.nextChunks(gen * 9L, gen, total, { it in done }, batch = 5)
        assertEquals(listOf(9), got)
    }

    @Test
    fun `往回拖_立刻改道到拖到的那一块`() {
        val total = 10
        val done = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        // 全齐时拖动不该派活
        assertEquals(emptyList<Int>(), StemSchedule.nextChunks(gen * 2L, gen, total, { it in done }))
        // 缺块时按新位置派
        val missing = done - 3
        assertEquals(listOf(3), StemSchedule.nextChunks(gen * 2L, gen, total, { it in missing }, batch = 5))
    }

    @Test
    fun `前置缺块没凑满一批时_回头去补前面的`() {
        val total = 10
        val done = setOf(4, 5, 6, 7, 8, 9)     // 0..3 缺
        val got = StemSchedule.nextChunks(gen * 6L, gen, total, { it in done }, batch = 8)
        // 从块 6 起只有 0 个缺块，于是回头补 0..3
        assertEquals(listOf(0, 1, 2, 3), got)
    }

    @Test
    fun `边界：负帧号当 0、单块、非法参数都不崩`() {
        assertEquals(0, StemSchedule.chunkOf(-999, gen, 10))
        assertEquals(0, StemSchedule.chunkOf(0, gen, 1))
        assertEquals("超出总块数 → 夹到最后一块", 9, StemSchedule.chunkOf(gen * 99L, gen, 10))
        assertEquals(emptyList<Int>(), StemSchedule.nextChunks(0, 0, 10, { false }))
        assertEquals(emptyList<Int>(), StemSchedule.nextChunks(0, gen, 0, { false }))
    }

    @Test
    fun `批量上限被遵守（不会一次派太多块）`() {
        val got = StemSchedule.nextChunks(0, gen, 100, { false }, batch = 3)
        assertEquals(3, got.size)
    }

    // ── 保留策略也换成了物理文件维度 ───────────────────────────────────────

    @Test
    fun `保留策略按目录名淘汰_不再依赖 songId`() {
        val entries = listOf(
            StemRetention.Entry("aaaa1111-r44100-c2-n100", bytes = 10, lastAccess = 1),
            StemRetention.Entry("bbbb2222-r44100-c2-n200", bytes = 10, lastAccess = 2),
            StemRetention.Entry("cccc3333-r44100-c2-n300", bytes = 10, lastAccess = 3),
        )
        val evict = StemRetention.planEviction(
            entries, RetentionMode.COUNT, maxCount = 2, protectedKeys = emptySet(),
        )
        assertEquals(listOf("aaaa1111-r44100-c2-n100"), evict)
    }

    @Test
    fun `受保护的目录不淘汰_但它会占掉保留配额`() {
        val entries = listOf(
            StemRetention.Entry("old-r44100-c2-n100", bytes = 10, lastAccess = 1),
            StemRetention.Entry("new-r44100-c2-n200", bytes = 10, lastAccess = 9),
        )
        // 保留 1 份、其中那份旧的受保护 ⇒ 配额被它占满 ⇒ 新的反而要淘汰。
        // （语义：保留数 = 受保护的 + 最近的 (maxCount − 受保护数)，下限 0。）
        val evict = StemRetention.planEviction(
            entries, RetentionMode.COUNT, maxCount = 1, protectedKeys = setOf("old-r44100-c2-n100"),
        )
        assertEquals(listOf("new-r44100-c2-n200"), evict)

        // 把配额放大到 2 ⇒ 谁都不用淘汰
        assertEquals(
            emptyList<String>(),
            StemRetention.planEviction(
                entries, RetentionMode.COUNT, maxCount = 2, protectedKeys = setOf("old-r44100-c2-n100"),
            ),
        )
    }
}

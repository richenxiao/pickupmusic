package com.shiyin.music.data.normalize

import com.google.gson.Gson
import com.shiyin.music.data.Track
import com.shiyin.music.data.split.SplitIds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.5.0：专辑内"同一文件"折叠。用例来自用户真机反馈（2026-10-10）：
 * 一张 EP 的 YOU / YOU（feat.UMI） / YOU-Instrumental 三个文件 TITLE 都是 "YOU"，
 * 旧的"按标题折叠"把整张 EP 折叠成一首。
 */
class AlbumDedupeTest {

    private fun track(
        id: Long,
        title: String,
        dataPath: String = "",
        durationMs: Long = 200_000,
        sizeBytes: Long = 5_000_000,
    ): Track = Gson().fromJson(
        """{"id":$id,"title":"$title","dataPath":"$dataPath","durationMs":$durationMs,"sizeBytes":$sizeBytes}""",
        Track::class.java,
    )

    @Test
    fun `同标题但不同文件_全部保留`() {
        val ts = listOf(
            track(1, "YOU", "/Music/YOU.mp3", 200_000, 5_000_000),
            track(2, "YOU", "/Music/YOU-acapella.mp3", 205_000, 5_100_000),
            track(3, "YOU", "/Music/YOU-instrumental.mp3", 201_000, 4_900_000),
        )
        assertEquals(3, AlbumDedupe.dedupeSameFile(ts).size)
    }

    @Test
    fun `同路径_视为重复收录_只留一条`() {
        val ts = listOf(
            track(1, "YOU", "/Music/YOU.mp3"),
            track(2, "YOU", "/Music/YOU.mp3"),
        )
        assertEquals(1, AlbumDedupe.dedupeSameFile(ts).size)
    }

    @Test
    fun `路径为空时_按标题+时长+大小全等折叠`() {
        val same = listOf(
            track(1, "Track", "", 100_000, 1_000_000),
            track(2, "Track", "", 100_000, 1_000_000),
        )
        assertEquals(1, AlbumDedupe.dedupeSameFile(same).size)

        val differentLength = listOf(
            track(1, "Track", "", 100_000, 1_000_000),
            track(2, "Track", "", 120_000, 1_000_000),
        )
        assertEquals(2, AlbumDedupe.dedupeSameFile(differentLength).size)
    }

    @Test
    fun `不同标题互不影响_顺序保持`() {
        val ts = listOf(
            track(1, "A", "/a.mp3"),
            track(2, "B", "/b.mp3"),
            track(3, "A", "/c.mp3"),
        )
        val out = AlbumDedupe.dedupeSameFile(ts)
        assertEquals(listOf(1L, 2L, 3L), out.map { it.id })
    }

    @Test
    fun `拆分单曲共享同一文件_不能折叠`() {
        // 2026-10-12 用户真机反馈：整轨拆完后**歌曲列表有 N 首、专辑页却只剩 1 条**。
        // 根因：拆分单曲是 src.copy(id = 合成 id) 出来的，dataPath 与原文件**完全相同**，
        // 于是被当成"MediaStore 把同一文件收录了两遍"折叠掉了。物理上确实只有一个文件，
        // 但它们是**用户显式拆出来的不同曲目**，必须全留。
        val src = "/Music/整轨.flac"
        val ts = listOf(
            track(SplitIds.TRACK_BASE + 1, "第一首", src, 180_000, 0),
            track(SplitIds.TRACK_BASE + 2, "第二首", src, 210_000, 0),
            track(SplitIds.TRACK_BASE + 3, "第三首", src, 195_000, 0),
        )
        val out = AlbumDedupe.dedupeSameFile(ts)
        assertEquals(3, out.size)
        assertEquals(
            listOf("第一首", "第二首", "第三首"),
            out.map { it.title },
        )
    }

    @Test
    fun `拆分单曲与真实重复收录混在一起_只折叠真重复`() {
        // 真实场景：整轨文件本身被 MediaStore 重复收录成两行(真重复,要折叠)，
        // 同时它又被拆成了两首(不能折叠)。
        val src = "/Music/整轨.flac"
        val ts = listOf(
            track(7, "整轨", src, 400_000, 30_000_000),
            track(8, "整轨", src, 400_000, 30_000_000),          // ← MediaStore 重复收录
            track(SplitIds.TRACK_BASE + 1, "上", src, 180_000, 0),
            track(SplitIds.TRACK_BASE + 2, "下", src, 220_000, 0),
        )
        val out = AlbumDedupe.dedupeSameFile(ts)
        assertEquals(3, out.size)
        assertEquals(listOf(7L, SplitIds.TRACK_BASE + 1, SplitIds.TRACK_BASE + 2), out.map { it.id })
    }
}

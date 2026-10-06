package com.shiyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistWritePlannerTest {

    @Test
    fun `filters blank UNKNOWN and album-colliding names, dedups names`() {
        val pairs = listOf(
            1L to "Aimer",
            2L to "",
            3L to "   ",
            4L to UNKNOWN_ARTIST,
            5L to "Greatest Hits", // 与专辑同名 → 跳过(疑似 MediaStore 元数据错误)
            6L to "Aimer",         // 同一歌手多首歌 → 对保留,名字去重
            7L to "YOASOBI",
        )
        val albums = setOf("greatest hits")
        val (kept, names) = ArtistWritePlanner.filterPairs(pairs, albums)
        assertEquals(
            listOf(1L to "Aimer", 6L to "Aimer", 7L to "YOASOBI"),
            kept,
        )
        assertEquals(listOf("Aimer", "YOASOBI"), names)
    }

    @Test
    fun `album-collision check is case-insensitive and trims artist name`() {
        val (kept, _) = ArtistWritePlanner.filterPairs(
            listOf(1L to "  everglow  "), // trim + lowercase 后撞上专辑名
            setOf("everglow"),
        )
        assertEquals(emptyList<Pair<Long, String>>(), kept)
    }

    @Test
    fun `empty input yields empty plan`() {
        val (kept, names) = ArtistWritePlanner.filterPairs(emptyList(), emptySet())
        assertEquals(emptyList<Pair<Long, String>>(), kept)
        assertEquals(emptyList<String>(), names)
    }

    @Test
    fun `buildLinks maps names to ids and skips missing`() {
        val links = ArtistWritePlanner.buildLinks(
            listOf(1L to "Aimer", 2L to "不存在", 3L to "YOASOBI"),
            mapOf("Aimer" to 10, "YOASOBI" to 20),
        )
        assertEquals(listOf(1L to 10, 3L to 20), links)
    }
}

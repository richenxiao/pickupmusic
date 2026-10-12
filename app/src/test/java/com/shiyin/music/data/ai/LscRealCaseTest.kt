package com.shiyin.music.data.ai

import org.junit.Test

/**
 * 真实案例（用户 2026-10-12）：ラブリーサマーちゃん《LSC》。
 * 本地 12 首 vs 官方 13 首（iTunes 接口取的官方曲序）。
 * 这不是验收测试，是**把"排序到底成不成"用真数据跑出来**。
 */
class LscRealCaseTest {

    private val local = listOf(
        "あなたは煙草　私はシャボン",   // ← 全角空格 U+3000
        "PART-TIME ROBOT",
        "青い瞬きの途中で",
        "202 feat. 泉まくら",
        "私の好きなもの",
        "月の光り方",
        "水星",
        "わたしのうた",
        "魚の目シンパシー",
        "ベッドルームの夢(New Recording)",
        "天国はまだ遠い",
        "僕らなら",
    )
    /** MusicBrainz 返回的**原始字符串**（原样抄下来，不做人工清洗）：
     *  - 第 1 首是全角空格（U+3000），本地也是全角 → 一致
     *  - 第 2 首是 U+2010 连字符（`PART‐TIME`），本地是 ASCII `-` → 归一化必须抹平
     *  - 第 4/5 首带 "(New Mix)"、第 10 首带 "(New Recording)" → 括号后缀要能剥
     *  - 共 12 首，与本地同数量（通常盘；初回盘是 21 首，选错版本会把顺序带偏）
     */
    private val official = listOf(
        "あなたは煙草 私はシャボン",
        "PART‐TIME ROBOT",
        "青い瞬きの途中で",
        "202 (New Mix)",
        "私の好きなもの (New Mix)",
        "月の光り方",
        "水星",
        "わたしのうた",
        "魚の目シンパシー",
        "ベッドルームの夢 (New Recording)",
        "天国はまだ遠い",
        "僕らなら",
    )

    @Test
    fun realAlbumMapping() {
        val idx = AgentService.officialToLocalIndices(official, local)
        println("LSC：官方 ${official.size} 首 → 认出本地下标 $idx")
        println("     覆盖 ${idx.size}/${local.size} 首")
        val ordered = idx.map { local[it] }
        println("     排出来的顺序：")
        ordered.forEachIndexed { i, t -> println("       ${i + 1}. $t") }
        println("     与本地的原顺序${if (ordered == local) "【一致】（这张专辑本地本来就是官方序）" else "【不一致】"}")
        org.junit.Assert.assertEquals("应认出全部 12 首", local.size, idx.size)
        org.junit.Assert.assertEquals("排出的顺序应与官方一致（= 本地顺序）", local, ordered)
    }
}

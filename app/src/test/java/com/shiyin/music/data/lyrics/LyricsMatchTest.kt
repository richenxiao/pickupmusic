package com.shiyin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.3.9: 网易云歌词匹配的**回归锁定**。
 *
 * 用例数据全部来自 2026-09-27 真机复现时抓到的网易云真实返回(Album《富豪岛》,
 * 用户反馈「网易云明明有歌词却识别不出」)。此前的匹配逻辑只能靠线上复现,
 * 修一次回归一次;现在固化成测试。
 *
 * 关键回归:
 *  · 歌手名变体(「play J」vs 网易云的「Player J」)此前让歌手闸门拒光所有候选 → 报无歌词;
 *  · 受控兜底必须**不**破坏「同名不同歌」的拒绝能力(阴三儿/大张伟《爽歪歪》类)。
 */
class LyricsMatchTest {

    /** 真实返回:搜索「古惑仔 play J/步步高先生」的前几条(节选,字段与接口一致)。 */
    private fun guHuoZaiCands(artist: String, title: String = "古惑仔", durationSec: Long = 215) = listOf(
        LyricsFetcher.candidateOf(2130715019, listOf("Player J", "步步高先生"), "古惑仔", 215, artist, title, durationSec),
        LyricsFetcher.candidateOf(2726604057, listOf("K.C", "PlayerJ", "步步高先生"), "PlayerJ-古惑仔(BAILE FUNK K.C REMIX)", 127, artist, title, durationSec),
        LyricsFetcher.candidateOf(2130715020, listOf("Player J", "阿牛", "步步高先生"), "开波", 174, artist, title, durationSec),
        LyricsFetcher.candidateOf(2130716685, listOf("步步高先生", "Player J", "阿牛"), "有钱有权有势", 208, artist, title, durationSec),
    )

    @Test
    fun `歌手名写成语变体 play J 时仍能命中正确歌词`() {
        // 网易云返回的歌手是「Player J」,本地 tag 写成「play J」——归一化后
        // playj / playerj 双向包含都不成立,原本会整首判「无歌词」。
        val picked = LyricsFetcher.selectNeteaseCandidate(guHuoZaiCands("play J"), "play J", 215)
        assertEquals(2130715019L, picked?.id)
    }

    @Test
    fun `歌手大小写与空格差异不影响命中`() {
        for (variant in listOf("PlayerJ", "PLAY J", "player j")) {
            val picked = LyricsFetcher.selectNeteaseCandidate(guHuoZaiCands(variant), variant, 215)
            assertEquals("变体「$variant」应命中", 2130715019L, picked?.id)
        }
    }

    @Test
    fun `多歌手串的分隔符差异不影响命中`() {
        // 本地「PlayerJ/阿牛/步步高先生」vs 返回「Player J / 阿牛 / 步步高先生」
        val cands = listOf(
            LyricsFetcher.candidateOf(2130715022, listOf("Player J", "阿牛", "步步高先生"), "富豪岛", 199,
                "PlayerJ/阿牛/步步高先生", "富豪岛", 199),
        )
        val picked = LyricsFetcher.selectNeteaseCandidate(cands, "PlayerJ/阿牛/步步高先生", 199)
        assertEquals(2130715022L, picked?.id)
    }

    @Test
    fun `常规情况按歌手加歌名命中`() {
        val picked = LyricsFetcher.selectNeteaseCandidate(guHuoZaiCands("PlayerJ"), "PlayerJ", 215)
        assertEquals(2130715019L, picked?.id)
    }

    @Test
    fun `时长差超过十秒时拒绝 live 混音版风险`() {
        // 本地 275s,网易云正版 215s → 差 60s,不接受(可能是 live/加长版)
        val picked = LyricsFetcher.selectNeteaseCandidate(
            guHuoZaiCands("PlayerJ", durationSec = 275), "PlayerJ", 275,
        )
        assertNull(picked)
    }

    @Test
    fun `同名不同歌且歌手对不上时拒绝而不是瞎配`() {
        // 阴三儿《爽歪歪》vs 大张伟《爽歪歪》:本地歌手名与两边都不相似 →
        // 受控兜底要求"歌手有公共前缀",全部落空 → 必须返回 null(宁缺勿错)
        val cands = listOf(
            LyricsFetcher.candidateOf(1, listOf("大张伟"), "爽歪歪", 212, "完全陌生的歌手", "爽歪歪", 210),
            LyricsFetcher.candidateOf(2, listOf("阴三儿"), "爽歪歪", 210, "完全陌生的歌手", "爽歪歪", 210),
        )
        assertNull(LyricsFetcher.selectNeteaseCandidate(cands, "完全陌生的歌手", 210))
    }

    @Test
    fun `同名多候选即使歌手相似也拒绝`() {
        // 歌手名相似(公共前缀 PlayerJ)但双向都不包含 → 不属于"歌手命中";
        // 走受控兜底时两个精确同名候选都贴合 → 判歧义,不能赌
        val cands = listOf(
            LyricsFetcher.candidateOf(1, listOf("PlayerJane"), "古惑仔", 215, "PlayerJackson", "古惑仔", 215),
            LyricsFetcher.candidateOf(2, listOf("PlayerJohn"), "古惑仔", 214, "PlayerJackson", "古惑仔", 215),
        )
        assertNull(LyricsFetcher.selectNeteaseCandidate(cands, "PlayerJackson", 215))
    }

    @Test
    fun `本地无歌手信息时按歌名兜底`() {
        // 历史回归:本地 tag 没填歌手时若仍硬性要求歌手命中,整首永远搜不到
        val cands = listOf(
            LyricsFetcher.candidateOf(2130715019, listOf("Player J", "步步高先生"), "古惑仔", 215, "", "古惑仔", 215),
        )
        val picked = LyricsFetcher.selectNeteaseCandidate(cands, "", 215)
        assertEquals(2130715019L, picked?.id)
    }

    @Test
    fun `候选 ID 非法时返回空`() {
        val cands = listOf(
            LyricsFetcher.candidateOf(-1, listOf("Player J"), "古惑仔", 215, "PlayerJ", "古惑仔", 215),
        )
        assertNull(LyricsFetcher.selectNeteaseCandidate(cands, "PlayerJ", 215))
    }
}

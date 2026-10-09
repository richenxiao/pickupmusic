package com.shiyin.music.data.search

import com.google.gson.Gson
import com.shiyin.music.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 搜索第二轮（A43 修正）——用户对第一轮的纠偏：
 *
 * > 「搜索太严格了，我是允许模糊搜索的，只是反对模糊搜索后的结果标注太散开。
 * >    比如【那天】可以搜出 那天下雨了、天天、天生疯塔儿、天气显示，但模糊命中
 * >    只能标注搜索词中的"天"，而不是 天天/天生/天气 整段。」
 * > 「搜 You，排最前面的居然是 揽佬SKAIISYOURGOD、SAVE YOUR Tears、YOUR EYES，
 * >    很后面才是 You」
 *
 * 三条修复（本类各对应一组用例，当前应红）：
 *  A. 撤销「2 字中文查询不容错」——恢复模糊搜索（cap 与英文一致）。
 *  B. 模糊命中的高亮收窄到与 token 逐字相等的位置（不再是整个模糊窗口、也不是空）。
 *  C. 排序：标题精确子串命中必须压过「多字段弱命中累加」（如 title="You" 必须排第一）。
 */
class FuzzySearchRound2Test {

    private fun track(id: Long, title: String, artist: String, album: String = "X"): Track =
        Gson().fromJson(
            """{"id":$id,"title":"$title","artist":"$artist","album":"$album","folder":"/Music","dataPath":"/Music/x.mp3","durationMs":180000,"sizeBytes":5000000,"dateAdded":1700000000,"albumId":1,"trackNo":1}""",
            Track::class.java,
        )

    // ── A. 恢复模糊搜索（撤销 65e4c08 的过严收紧） ───────────────────────────

    @Test
    fun `两字中文查询恢复容错_用户明确允许模糊搜索`() {
        // 用户要求保留模糊：搜「那天」应能搜出「天天」之类
        assertNotNull(FuzzySearch.match("那天", "天天"))
        assertNotNull(FuzzySearch.match("那天", "天气显示"))
        assertNotNull(FuzzySearch.match("那天", "那天下雨了"))
        assertNotNull(FuzzySearch.match("那天", "天生疯塔儿"))
    }

    @Test
    fun `单字查询仍不容错`() {
        assertEquals(0, FuzzySearch.maxEdits("天"))
    }

    // ── B. 模糊命中的高亮收窄到真正相等的字 ─────────────────────────────────

    @Test
    fun `模糊命中_高亮只标相等的字_天天对那天`() {
        val nf = FuzzySearch.norm("天天")
        val m = FuzzySearch.matchTokenInField("那天", nf)
        assertNotNull("模糊命中应保留", m)
        // 「天天」与「那天」的 LCS 只有一个「天」→ 只标一个单字区间（哪个「天」都行）
        assertEquals(1, m!!.ranges.size)
        val r = m.ranges[0]
        assertEquals("只标一个字", r.first, r.last)
        assertTrue("标的必须是「天」的位置", r.first in 0..1)
    }

    @Test
    fun `模糊命中_高亮只标相等的字_天气对那天`() {
        val nf = FuzzySearch.norm("天气显示")
        val m = FuzzySearch.matchTokenInField("那天", nf)
        assertNotNull(m)
        // 「天气」窗内只有「天」与查询的「天」相等 → 只标「天」
        assertEquals(listOf(0..0), m!!.ranges)
    }

    @Test
    fun `模糊命中_高亮只标相等的字_社会天使轮对那天`() {
        val nf = FuzzySearch.norm("社会天使轮")
        val m = FuzzySearch.matchTokenInField("那天", nf)
        assertNotNull(m)
        // 「会天」窗内只有「天」相等 → 非连续,不相邻,拆开单字区间
        assertEquals(listOf(2..2), m!!.ranges)
    }

    @Test
    fun `英文模糊命中高亮收窄_Weeknd对weekend`() {
        val nf = FuzzySearch.norm("Weeknd")
        val m = FuzzySearch.matchTokenInField("weekend", nf)
        assertNotNull(m)
        // weekend(7) vs Weeknd(6):窗内逐字符相等的位置是 W-e-e-k-n-d → 0..5(整窗)
        // 因为 dl 最优对齐里多出来的 e 是插入,落点的每个字符都相等
        assertEquals(listOf(0..5), m!!.ranges)
    }

    @Test
    fun `精确子串命中照常给整段高亮`() {
        val nf = FuzzySearch.norm("那天下雨了")
        val m = FuzzySearch.matchTokenInField("那天", nf)
        assertNotNull(m)
        assertEquals(listOf(0..1), m!!.ranges)
    }

    // ── C. 排序:标题精确命中必须压过多字段弱命中累加 ─────────────────────────

    @Test
    fun `标题精确命中_You_必须排第一`() {
        val lib = listOf(
            track(1, "揽佬", "SKAIISYOURGOD"),              // artist 子串"you"
            track(2, "SAVE YOUR Tears", "The Weeknd"),     // title 子串 your(you 前缀不命中) 加 artist/album
            track(3, "YOUR EYES", "X"),                    // title 子串 your
            track(4, "You", "Somebody"),                   // 标题恰是查询
        )
        val hits = FuzzySearch.search(lib, "You")
        assertEquals("标题恰为 You 的歌必须排第一", 4L, hits.first().track.id)
    }

    @Test
    fun `标题精确子串_压过_纯歌手和专辑命中`() {
        val lib = listOf(
            track(1, "别的歌", "YouTube"),          // artist 含 you
            track(2, "那首", "A", "My You Collection"), // album 含 you
            track(3, "You Belong With Me", "A"),    // title 子串 you
        )
        val hits = FuzzySearch.search(lib, "you")
        assertEquals(3L, hits.first().track.id)
    }

    @Test
    fun `多字段强命中仍能排前_同有精确标题命中时按强度`() {
        val lib = listOf(
            track(1, "You", "X"),                 // 仅标题精确
            track(2, "You", "YouTuber", "YouTube Album"), // 标题精确 + 歌手 + 专辑
        )
        val hits = FuzzySearch.search(lib, "you")
        // 两条都是标题精确命中,排量更多字段命中的第 2 首在前
        assertEquals(2L, hits.first().track.id)
    }

    @Test
    fun `模糊命中_标亮字与查询对应位置逐字相等`() {
        // 通用不变式:标亮的每个字符必须等于查询相应位置上"能对上的"那个字
        // （用「那天」对「天天」验证——标亮的必须是「天」而不是「天天」整段）
        val nf = FuzzySearch.norm("天天")
        val m = FuzzySearch.matchTokenInField("那天", nf)!!
        for (r in m.ranges) {
            for (i in r) {
                assertEquals('天', nf.text[i])
            }
        }
    }

    // ── 第三轮：强相关才给专辑推荐（模糊命中不出专辑卡） ─────────────────────

    @Test
    fun `专辑字段强命中_albumBestScore为800以上`() {
        val lib = listOf(track(1, "别的歌", "A", "You Belong Album"))
        val hits = FuzzySearch.search(lib, "You")
        assertEquals(1, hits.size)
        // 专辑名含子串 "You" → albumBestScore 应为 800（子串档）
        assertTrue("专辑字段子串命中应 ≥800", hits[0].albumBestScore >= 800)
    }

    @Test
    fun `专辑字段仅模糊命中_albumBestScore为600`() {
        // 专辑名「Tou Project」对查询「You」只有模糊命中(t→y 一个替换) → 600
        val lib = listOf(track(1, "别的歌", "A", "Tou Project"))
        val hits = FuzzySearch.search(lib, "You")
        assertEquals(1, hits.size)
        assertEquals(600, hits[0].albumBestScore)
    }
}

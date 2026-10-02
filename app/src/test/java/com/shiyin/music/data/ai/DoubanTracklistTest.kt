package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.15:豆瓣路线的纯函数回归。
 *
 * **测试向量是真实抓取文本**(2026-10-02 Tavily 抽取 `music.douban.com/subject/37372564`
 * 的原文,只截了曲目段与紧随其后的短评段)——这条路线要能用,就必须:
 *  ① 只把"曲目"段里的 14 行当曲目,**不能把下面的短评当曲目**(短评里全是人名和句子);
 *  ② 认得出移动域名 `m.douban.com/music/subject/<id>`(实测搜索命中的就是它);
 *  ③ 多个候选页面里挑"与本地曲目重合最多"的那份。
 */
class DoubanTracklistTest {

    /** 真实抓取文本(节选:曲目段 + 紧随的短评段)。 */
    private val realPage = """
        这是华云龙KLE的首张录音室专辑《龙年》。
        2025年5月23日,华云龙KLE首张录音室专辑《龙年》。“在轨道上获得一切”。

        ## 曲目 ······

           枣庄市马思唯
           轨道飞虎
           山东王FREESTYLE
           底层特律
           老乡日
           爽文
           十男九痔
           常胜将军
           顶男
           SHXT CHANGE
           过肺
           枣庄一夜
           川渝女孩
           天下谁人不识君

        ## 短评 ······ ( 全部 569 条 )
           ### 0有用 呆萌读书四眼仔 2026-08-22 16:42:46 浙江
           作为新人,而且现在短视频上火起来,却交出了一份很不错的专辑
           ### 7有用 音楽分かるのか 2025-06-04 22:32:18 浙江
           这....尊重,祝福吧
    """.trimIndent()

    private val expected14 = listOf(
        "枣庄市马思唯", "轨道飞虎", "山东王FREESTYLE", "底层特律", "老乡日", "爽文", "十男九痔",
        "常胜将军", "顶男", "SHXT CHANGE", "过肺", "枣庄一夜", "川渝女孩", "天下谁人不识君",
    )

    @Test
    fun `真实页面的曲目段解析出 14 首且顺序正确`() {
        assertEquals(expected14, DoubanTracklist.parseTracklist(realPage))
    }

    @Test
    fun `短评段不会被当成曲目`() {
        val got = DoubanTracklist.parseTracklist(realPage)
        assertTrue("短评里的人名/句子混进来了:$got", got.none { it.contains("有用") || it.contains("尊重") })
        assertEquals(14, got.size)
    }

    @Test
    fun `没有曲目段时返回空 - 不乱猜`() {
        assertTrue(DoubanTracklist.parseTracklist("只有简介和短评,没有曲目段 ## 短评 ···").isEmpty())
        assertTrue(DoubanTracklist.parseTracklist(null).isEmpty())
    }

    @Test
    fun `两种域名形态的专辑页 URL 都能认出来`() {
        val text = "结果: https://m.douban.com/music/subject/37372564 以及 " +
            "https://music.douban.com/subject/37372564 还有 " +
            "https://music.douban.com/tag/%E5%8D%8E%E8%AF%AD"
        assertEquals(
            listOf("https://m.douban.com/music/subject/37372564", "https://music.douban.com/subject/37372564"),
            DoubanTracklist.subjectUrls(text),
        )
    }

    @Test
    fun `多个候选里挑与本地曲目重合最多的那份`() {
        // 当天的翻车资料(B 站同名作品曲目)作为干扰候选
        val wrong = listOf("Pain Wit Da Beatz", "Cash Money 2", "Sin House", "盆满钵满", "你要遭")
        val local = expected14 + listOf("多出来的一首")
        val picked = DoubanTracklist.pickBest(listOf(wrong, expected14), local)
        assertEquals("应挑中豆瓣那份(14 首里全重合)", expected14, picked)
    }

    @Test
    fun `全都对不上时挑不出东西 - 交给上层如实说`() {
        val wrong = listOf("Pain Wit Da Beatz", "Cash Money 2")
        assertEquals(null, DoubanTracklist.pickBest(listOf(wrong), expected14))
        assertEquals(null, DoubanTracklist.pickBest(emptyList(), expected14))
    }

    @Test
    fun `重合度按归一口径算 - 繁简全半角副标题不挡`() {
        assertEquals(2, DoubanTracklist.overlap(listOf("枣庄市马思唯", "轨道飞虎 (Live)"), expected14))
    }

    @Test
    fun `移动版地址会被改写成桌面版 - 这是曲目能不能抽出来的关键`() {
        // 实测:移动版 m.douban.com 的"曲目"段是 JS 渲染的,抽出来是空的;桌面版才有曲目表。
        assertEquals(
            "https://music.douban.com/subject/37372564/",
            DoubanTracklist.desktopUrl("https://m.douban.com/music/subject/37372564"),
        )
        // 已经是桌面版 → 保持(只需补尾斜杠)
        assertEquals(
            "https://music.douban.com/subject/37372564/",
            DoubanTracklist.desktopUrl("https://music.douban.com/subject/37372564"),
        )
        // 认不出来 → 原样返回(不去瞎改)
        assertEquals("https://example.com/x", DoubanTracklist.desktopUrl("https://example.com/x"))
    }
}

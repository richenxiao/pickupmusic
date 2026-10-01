package com.shiyin.music.data.ai

import com.shiyin.music.data.ai.skills.DiscoverCopy
import org.junit.Assert.assertEquals

import org.junit.Test

/**
 * v1.3.15:推荐卡**文案模板**(文档 §4.2.8)的逐条回归。
 *
 * 为什么值得单测:这些句子是用户**第一眼**看到的东西,而它们完全由情形决定
 * (有没有场景/歌手/专辑、模型判定的契合度、是否解析失败) —— 用纯函数写出来就能把
 * 每种情形的措辞钉住,不必靠真机一次次试。
 *
 * 三条不可退让的纪律:
 *  ① 标题**必须在整段话最前面**(它是主句);
 *  ② `fit=strong` 仍要能出现"我挑不出"的措辞 —— 那就是**显式降级**,不许假装推荐;
 *  ③ 副文案:有理由用理由,**没理由用专辑名**。
 */
class DiscoverCopyTest {

    private fun title(scene: String? = null, artist: String? = null, album: String? = null, fit: Fit? = null, failed: Boolean = false) =
        DiscoverCopy.title(scene = scene, artistLabel = artist, album = album, fit = fit, parseFailed = failed)

    // ── 有场景:模型判定的三种契合度 ────────────────────────────────────────

    @Test
    fun `strong + 歌手 - 文档模板原样`() {
        assertEquals("以下是方大同适合睡觉的歌曲，想听哪一首？", title("睡觉", "方大同", fit = Fit.STRONG))
    }

    @Test
    fun `strong + 多歌手 - 并列写进同一句`() {
        assertEquals(
            "以下是方大同、藤井风适合睡觉的歌曲，想听哪一首？",
            title("睡觉", "方大同、藤井风", fit = Fit.STRONG),
        )
    }

    @Test
    fun `strong + 无歌手 - 不含歌手时用"我觉得"句式`() {
        assertEquals("下面这几首我觉得适合睡觉，想听哪一首？", title("睡觉", fit = Fit.STRONG))
    }

    @Test
    fun `weak 与 none 都要说清楚是降级 - 不能装成推荐`() {
        assertEquals(
            "方大同里没有太贴合睡觉的，这几首稍微接近，想听哪一首？",
            title("睡觉", "方大同", fit = Fit.WEAK),
        )
        assertEquals("方大同里没有适合睡觉的，这是最常听的几首：", title("睡觉", "方大同", fit = Fit.NONE))
        assertEquals("库里没有适合睡觉的，这是你平时最常听的几首：", title("睡觉", fit = Fit.NONE))
    }

    @Test
    fun `解析两次都失败 - 显式降级且仍写明范围与场景`() {
        assertEquals(
            "这次没能挑出适合睡觉的，先给你方大同最常听的几首：",
            title("睡觉", "方大同", fit = null, failed = true),
        )
        assertEquals(
            "这次没能挑出适合睡觉的，先给你库里最常听的几首：",
            title("睡觉", fit = null, failed = true),
        )
    }

    // ── 没有场景:没调模型,只按最常听给 ────────────────────────────────────

    @Test
    fun `仅歌手 - 文档模板原样`() {
        assertEquals("这是方大同你最常听的几首：", title(artist = "方大同", fit = null))
    }

    @Test
    fun `仅专辑与歌手+专辑`() {
        assertEquals("这是《危险世界》里的几首：", title(album = "危险世界", fit = null))
        assertEquals("这是方大同的《危险世界》里的几首：", title(artist = "方大同", album = "危险世界", fit = null))
        assertEquals("这是按平时常听挑的几首：", title(fit = null))
    }

    @Test
    fun `有专辑限定时场景文案带上专辑`() {
        assertEquals(
            "以下是方大同的《危险世界》适合睡觉的歌曲，想听哪一首？",
            title("睡觉", "方大同", "危险世界", fit = Fit.STRONG),
        )
    }

    // ── 副文案 ─────────────────────────────────────────────────────────────

    @Test
    fun `副文案 - 有理由用理由,没理由用专辑名`() {
        assertEquals("很安静", DiscoverCopy.note("很安静", "危险世界"))
        assertEquals("危险世界", DiscoverCopy.note(null, "危险世界"))
        assertEquals("危险世界", DiscoverCopy.note("   ", "危险世界"))
        assertEquals("", DiscoverCopy.note(null, null))
    }

    // ── 歌手不在库 ─────────────────────────────────────────────────────────

    @Test
    fun `歌手不在库 - 如实说,有近似才给建议`() {
        assertEquals("库里没有「陳奕迅」的歌。", DiscoverCopy.artistMissing("陳奕迅", emptyList()))
        assertEquals(
            "库里没有「陳奕迅」的歌。你是不是想找：方大同 / 周杰伦？",
            DiscoverCopy.artistMissing("陳奕迅", listOf("方大同", "周杰伦")),
        )
    }
}

package com.shiyin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.10 需求 A30:LrcShare 署名清洗的回归锁。
 *
 * 重点是**不误删真歌词**——歌词正文出现"来自"两个字是常事;只允许匹配明确的
 * LrcShare attribution pattern 的行被删。
 */
class LrcShareAttributionTest {

    private val RAW = """
[ti: 老师好]
[ar: 阴三儿]
[al: 未知艺术家]
[length: 05:15.48]
[00:00.000] 老师好-阴三儿
[00:00.320] 词曲:阴三儿
[00:00.680] LRC:X2ISPANDA[https://lrcshare.com]
[00:00.970]
[00:02.060] 胜利就在眼前 而你却看不到胜利!
[00:08.860] 他来自远方
[00:16.120] 二排长面对高高举起的屠刀
本内容来自LrcShare
链接:https://lrcshare.com/song/s_in3_004
""".trimIndent()

    @Test
    fun `metadata 标签全部剔除`() {
        val out = LrcShareAttribution.clean(RAW, "老师好", "阴三儿")
        assertFalse(out.contains("[ti:"))
        assertFalse(out.contains("[ar:"))
        assertFalse(out.contains("[al:"))
        assertFalse(out.contains("[length:"))
    }

    @Test
    fun `各种署名行都剔除 - 任何位置`() {
        val out = LrcShareAttribution.clean(RAW, "老师好", "阴三儿")
        assertFalse(out.contains("LRC:X2ISPANDA"))
        assertFalse(out.contains("本内容来自LrcShare"))
        assertFalse(out.contains("链接:https://lrcshare.com"))
        assertFalse(out.contains("lrcshare.com"))
        // credits 行(头部区域)
        assertFalse(out.contains("词曲:阴三儿"))
        // 「标题-歌手」回显行
        assertFalse(out.contains("老师好-阴三儿"))
    }

    @Test
    fun `真正歌词保留 - 含"来自"的歌词绝不误删`() {
        val out = LrcShareAttribution.clean(RAW, "老师好", "阴三儿")
        assertTrue("真歌词必须保留", out.contains("胜利就在眼前"))
        assertTrue("含「来自」的真歌词不能删", out.contains("他来自远方"))
        assertTrue("时间戳要保留", out.contains("[00:16.120] 二排长面对高高举起的屠刀"))
    }

    @Test
    fun `offset 保留 - 它是时间同步机制不是署名`() {
        val out = LrcShareAttribution.clean("[offset:+500]\n[00:01.000] 你好", "你好", "某人")
        assertTrue(out.contains("[offset:+500]"))
    }

    @Test
    fun `大小写与全角冒号变体都能认出`() {
        for (s in listOf(
            "本内容来自LRCSHARE", "本内容来自Lrcshare",
            "链接：https://lrcshare.com/song/x", "LINK: https://lrcshare.com/x",
            "LRC:X2ISPANDA", "lrc：x2ispanda",
        )) {
            val out = LrcShareAttribution.clean("[00:00.000] $s\n[00:10.000] 正文", "老师好", "阴三儿")
            assertFalse("「$s」应被剔除: $out", out.contains(s))
            assertTrue(out.contains("正文"))
        }
    }

    @Test
    fun `头部之外的 credits 行不误删(那可能就是歌词)`() {
        // credits 只在头部 8 行内判定;更靠后的"词曲:…"可能是歌词的一部分
        val lines = (1..10).joinToString("\n") { "[00:0$it.000] 正文$it" } + "\n[00:11.000] 词曲:某某"
        val out = LrcShareAttribution.clean(lines, "老师好", "阴三儿")
        assertTrue(out.contains("词曲:某某"))
    }

    @Test
    fun `同名行不等于标题歌手回显时不误删`() {
        // 只有恰好"标题-歌手"(或反过来)才算回显;歌词正文"老师好!"不是
        val out = LrcShareAttribution.clean("[00:00.000] 老师好!\n[00:02.000] 正文", "老师好", "阴三儿")
        assertTrue(out.contains("老师好!"))
    }

    @Test
    fun `空输入与纯署名输入都干净`() {
        assertEquals("", LrcShareAttribution.clean("", "老师好", "阴三儿"))
        assertEquals("", LrcShareAttribution.clean("本内容来自LrcShare", "老师好", "阴三儿"))
    }

    @Test
    fun `署名提取 - 贡献者与来源 URL 单独留存(不进正文)`() {
        val attr = LrcShareAttribution.extract(RAW)
        assertEquals("X2ISPANDA", attr?.contributor)
        assertEquals("https://lrcshare.com/song/s_in3_004", attr?.url)
    }
}

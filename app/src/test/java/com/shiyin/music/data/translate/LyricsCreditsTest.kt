package com.shiyin.music.data.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A56「歌词制作人行略过翻译」。用例来自用户真机截图（now.png 里"作词：友成空 /
 * 作曲：友成空"两行被翻译了）。
 */
class LyricsCreditsTest {

    @Test
    fun `中文制作人行_识别`() {
        assertTrue(LyricsCredits.isCreditLine("作词：友成空"))
        assertTrue(LyricsCredits.isCreditLine("作曲：友成空"))
        assertTrue(LyricsCredits.isCreditLine("編曲：山田太郎"))
        assertTrue(LyricsCredits.isCreditLine("制作人: 张三"))
        assertTrue(LyricsCredits.isCreditLine("混音：李四"))
        assertTrue(LyricsCredits.isCreditLine("母带：王五"))
        assertTrue(LyricsCredits.isCreditLine("吉他：佐藤"))
        assertTrue(LyricsCredits.isCreditLine("钢琴演奏：铃木"))
    }

    @Test
    fun `日文与英文制作人行_识别`() {
        assertTrue(LyricsCredits.isCreditLine("作詞：友成空"))
        assertTrue(LyricsCredits.isCreditLine("Lyrics by John Smith"))
        assertTrue(LyricsCredits.isCreditLine("Music by: Someone"))
        assertTrue(LyricsCredits.isCreditLine("Produced by: Team"))
        assertTrue(LyricsCredits.isCreditLine("Guitar: K. Tanaka"))
    }

    @Test
    fun `行首有装饰也能识别`() {
        assertTrue(LyricsCredits.isCreditLine("【作词】友成空"))
        assertTrue(LyricsCredits.isCreditLine("- 作曲：友成空"))
        assertTrue(LyricsCredits.isCreditLine("※编曲：山田"))
    }

    @Test
    fun `歌词正文中的同名字词_不能误伤`() {
        // 没有"标签：名字"形态 → 不是制作人行
        assertFalse(LyricsCredits.isCreditLine("我为你作曲"))
        assertFalse(LyricsCredits.isCreditLine("作曲是我唯一的语言"))
        assertFalse(LyricsCredits.isCreditLine("作词"))
        assertFalse(LyricsCredits.isCreditLine("作曲："))
        assertFalse(LyricsCredits.isCreditLine(""))
    }

    @Test
    fun `未知标签与长句_不误伤`() {
        assertFalse(LyricsCredits.isCreditLine("今天：天气很好"))
        assertFalse(LyricsCredits.isCreditLine("时间：2026 年 10 月"))
        // 超长行（歌词正文）即使以标签开头也不丢
        val long = "作词：" + "啊".repeat(80)
        assertFalse(LyricsCredits.isCreditLine(long))
    }
}

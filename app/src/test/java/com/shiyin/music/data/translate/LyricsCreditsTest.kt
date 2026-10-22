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
        // ⚠️ 2026-10-22 口径变化：`作曲：` 这种"只有标签没有内容"的行按制作人行处理
        // （真实歌词源里值常写在下一行，如 `錄音師（銅管）Recording Engineer (Brass)：`）
        assertTrue(LyricsCredits.isCreditLine("作曲："))
        assertFalse(LyricsCredits.isCreditLine(""))
    }

    @Test
    fun `未知标签与长句_不误伤`() {
        assertFalse(LyricsCredits.isCreditLine("今天：天气很好"))
        assertFalse(LyricsCredits.isCreditLine("时间：2026 年 10 月"))
        // 超长行（歌词正文）即使以标签开头也不丢（阈值 160 字符）
        val long = "作词：" + "啊".repeat(170)
        assertFalse(LyricsCredits.isCreditLine(long))
    }

    /**
     * 2026-10-22 大扩围：张震岳《浪人的…》（真实平台署名）。
     * 旧实现只认"标签完全相等"⇒ 这些中英双语行几乎全部漏识 ⇒ 英文署名被计入
     * 拉丁字母占比 ⇒ 中文歌被判欧美歌 ⇒ 翻译按钮出现后又提示"不需要翻译"。
     */
    @Test
    fun `浪人的真实署名_识别`() {
        val credits = listOf(
            "詞 Lyricist：張震嶽 Ayal Komod",
            "曲 Composer：張震嶽 Ayal Komod",
            "編曲 Music Arranger：張震嶽 Ayal Komod、黃冠豪 H.K.H",
            "製作人 Music Producer：黃冠豪 H.K.H",
            "製作總監 Executive Director ：賈敏恕 Jeff Chia",
            "製作助理 Production Assistant：林清智 Zachary Lin",
            "鼓 Drums：許沐恩 Samuel Hsu",
            "低音吉他 Bass Guitar：林奧迪 OD Lin",
            "電吉他 Electirc Guitar：翁光煒 Wico Weng、張震嶽 Ayal Komod、黃冠豪 H.K.H",
            "鍵盤 Keyboard：黃冠豪 H.K.H",
            "鼓技師 Drum Tech：莊開旭 Cash Chuang",
            "和聲 Backing Vocals：薛詒丹 Dan Hsueh",
            "和聲編寫 Backing Vocal Arranger：薛詒丹 Dan Hsueh",
            "管樂編寫 Brass Section Arranger：謝燕輝 Terence Hsieh",
            "小號 Trumpet：何浩城",
            "次中音薩克斯風 Tenor Saxophone：吳樂天",
            "錄音師（鼓、低音吉他、吉他）Recording Engineer (Drums.Bass Guitar.Electric Guitar)：蔡周翰 Chou Han Tsay",
            "錄音助理（鼓、低音吉他、吉他）Recording Assistants：于世政 Shih Cheng Yu",
            "錄音室（鼓、低音吉他、吉他）Recording Studio：享亮音樂國際有限公司 Lights Up Studio Taipei",
            "錄音師（銅管）Recording Engineer (Brass)：",
            "錄音室（銅管）：咚西 Dongxi Studio",
            "混音工程師 Mixing Engineer：陳文駿 AJ Chen",
            "混音工作室 Mixing Studio：強力錄音室 Mega Force Studio",
            "母帶後期工程師 Mastering Engineer：林清智 Zachary Lin",
            "母帶後期工作室 Mastering Studio：全區通行錄音室 All Access Studio",
            "杜比全景聲混音師 Dolby Atmos Mixing Engineer｜高許畯 Xu-Jun Kao",
            "杜比全景聲混音室 Dolby Atmos Mixing Studio｜放浪錄音室 Swinger Studio",
            "歌詞英文翻譯：老莫 ILL MO",
            "OP: 本色股份有限公司",
            "SP: Rock Music Publishing Co., Ltd.",
            "導演 DIRECTOR｜體育老師/ZC",
            "副導 ASSISTANT DIRECTOR｜呂昆霖 KUN-LIN LU",
            "攝影師 DIRECTOR OF PHOTOGRAPHY | 蔡宜倫 EN TSAI",
            "攝大助 FOCUS PULLER | 侯杰良 TONY",
            "燈光師 GAFFER｜林宏洋 LIN HONG YANG",
            "製片 PRODUCER｜ 李婷婷 Ting Ting Li",
            "美術指導 ART DIRECTOR｜鄭鉫叡 JIA RUEI JEN",
            "特別感謝 SPECIAL THANKS ｜",
            "© 2025 Rock Records Co., Ltd.",
            "℗ 2025 Rock Records Co., Ltd.",
        )
        for (line in credits) {
            assertTrue("应为制作人行：$line", LyricsCredits.isCreditLine(line))
        }
        // 歌词正文不能误伤
        assertFalse(LyricsCredits.isCreditLine("沙灘黃昏的色溫 即將入夜的時分"))
        assertFalse(LyricsCredits.isCreditLine("是我太認真 無法像你一樣 隨心所欲到處混"))
        assertFalse(LyricsCredits.isCreditLine("小屋裡歡樂氣氛 男男女女的笑聲"))
    }
}

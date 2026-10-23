package com.shiyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TitleResolver.resolveTitle] 的单元测试。
 * 背景：下载站把版本注记只写进文件名、内嵌标签只留基础歌名，
 * 导致同歌多版本塌成同名（真机实测「爱的初体验_(Remix)…」标签就是「爱的初体验」）。
 */
class TitleResolverTest {

    @Test
    fun `标签缺括号注记时从文件名补回`() {
        assertEquals(
            "爱的初体验 (Remix)",
            TitleResolver.resolveTitle(
                "爱的初体验",
                "/storage/emulated/0/Download/KuwoMusic/music/爱的初体验_(Remix)-张震岳-156943-2000.flac",
            ),
        )
    }

    @Test
    fun `标签已含同样注记时不重复补`() {
        assertEquals(
            "爱的初体验 (Remix)",
            TitleResolver.resolveTitle(
                "爱的初体验 (Remix)",
                "/music/爱的初体验_(Remix)-张震岳-156943-2000.flac",
            ),
        )
    }

    @Test
    fun `文件名没有注记时保持标签原标题`() {
        assertEquals(
            "爱的初体验",
            TitleResolver.resolveTitle("爱的初体验", "/music/爱的初体验-张震岳-282804-2000.flac"),
        )
    }

    @Test
    fun `注记前不是标题本身时不补（歌手-歌名形态）`() {
        // 「张震岳 - 爱的初体验(Remix)」—— 注记前是「张震岳 - 爱的初体验」，不等于标题，
        // 也不以「分隔符+标题」收尾（结尾是标题但前面是" - "，endsWith(" 爱的初体验") 命中空格形态）。
        // 这个形态其实该补——用 endsWith(" $tag") 覆盖了。换个真不命中：标题与文件名无关。
        assertEquals(
            "晴天",
            TitleResolver.resolveTitle("晴天", "/music/爱的初体验_(Remix)-张震岳-2000.flac"),
        )
    }

    @Test
    fun `歌手-歌名(注记)形态也补`() {
        assertEquals(
            "爱的初体验 (Remix)",
            TitleResolver.resolveTitle("爱的初体验", "/music/张震岳 - 爱的初体验 (Remix).flac"),
        )
    }

    @Test
    fun `标签为空白时用文件名兜底`() {
        assertEquals(
            "某首歌_(Live)-某歌手-2021",
            TitleResolver.resolveTitle("", "/music/某首歌_(Live)-某歌手-2021.flac"),
        )
    }

    @Test
    fun `标签为 null 时用文件名兜底`() {
        assertEquals(
            "某首歌",
            TitleResolver.resolveTitle(null, "/music/某首歌.flac"),
        )
    }

    @Test
    fun `全角括号注记也补`() {
        assertEquals(
            "爱的初体验 （现场版）",
            TitleResolver.resolveTitle("爱的初体验", "/music/爱的初体验（现场版）-张震岳-2000.flac"),
        )
    }

    @Test
    fun `dataPath 为空时保持标签`() {
        assertEquals("晴天", TitleResolver.resolveTitle("晴天", ""))
    }
}

package com.shiyin.music.data.karaoke

import com.shiyin.music.spike.karaoke.DebugExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 P1：调试导出的**纯逻辑**部分。
 *
 * 目录/MediaStore 那半边只能在真机上验（P1 验收项），但这两件事在 JVM 上就能钉死：
 *  - 文件名随机段确实是随机的（别被谁换成固定串，导出会互相覆盖、也难以区分轮次）；
 *  - 对照表字段里的 tab/换行会被抹掉（否则一行会被读成多列，谁也读不出来）。
 */
class DebugExportTest {

    @Test
    fun tokenIsRandomHexOfFixedWidth() {
        val tokens = List(200) { DebugExport.newToken() }
        assertTrue("随机段应为 8 位", tokens.all { it.length == 8 })
        assertTrue(
            "只能是 [0-9a-f]",
            tokens.all { t -> t.all { it in "0123456789abcdef" } },
        )
        // 200 次里撞上几乎不可能（16^8 ≈ 43 亿）；真撞了说明实现被换成了常量/时间戳
        assertEquals("随机段不该重复", 200, tokens.toSet().size)
    }

    @Test
    fun cellStripsColumnSeparators() {
        assertEquals("a b", DebugExport.cell("a\tb"))
        assertEquals("a b", DebugExport.cell("a\nb"))
        assertEquals("a b", DebugExport.cell("a\rb"))
        assertEquals("普通的歌名 - 演唱者", DebugExport.cell("普通的歌名 - 演唱者"))
    }
}

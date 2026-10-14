package com.shiyin.music.data.karaoke

import com.shiyin.music.ui.components.KaraokeButtonMachine
import com.shiyin.music.ui.components.KaraokeButtonState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 块 4 的闸门测试：**格式闸门** + **按钮状态机**。
 *
 * 这两件事都容易"看着对、实际错"：
 *  - 格式闸门写松了 → 点了才发现播不出来；写严了 → 明明能播的格式被挡；
 *  - 状态机最容易错的是**竞态**：分离是异步的，"就绪"回调可能**晚于用户取消**，
 *    若不加判断就会把用户已经取消掉的伴唱又打开。
 */
class KaraokeGateTest {

    // ── 格式闸门 ───────────────────────────────────────────────────────────

    @Test
    fun `FLAC 与 MP3 放行_大小写都认`() {
        for (p in listOf(
            "/sdcard/Music/a.flac", "/sdcard/Music/a.FLAC", "/sdcard/Music/a.Flac",
            "/sdcard/Music/a.mp3", "/sdcard/Music/a.MP3",
        )) {
            assertNull("应放行：$p", KaraokeFormatGate.reasonFor(p))
            assertTrue(KaraokeFormatGate.isSupported(p))
        }
    }

    @Test
    fun `其余格式一律挡下_并给出**能看懂的原因**`() {
        for (p in listOf(
            "/sdcard/Music/a.wav", "/sdcard/Music/a.m4a", "/sdcard/Music/a.aac",
            "/sdcard/Music/a.ogg", "/sdcard/Music/a.ape", "/sdcard/Music/a.wma",
        )) {
            val why = KaraokeFormatGate.reasonFor(p)
            assertNotNull("应挡下：$p", why)
            // 原因必须让用户看懂"为什么不行 + 怎么办"，不能只说"不支持"
            assertTrue("原因里要写清楚只支持 FLAC 与 MP3：$why", why!!.contains("FLAC") && why.contains("MP3"))
        }
    }

    @Test
    fun `识别不出扩展名时也给原因_而不是静默放行`() {
        val why = KaraokeFormatGate.reasonFor("/sdcard/Music/noext")
        assertNotNull(why)
        assertTrue("不能泄露内部术语，要说人话：$why", why!!.contains("格式"))
    }

    // ── 按钮状态机 ─────────────────────────────────────────────────────────

    @Test
    fun `点击在 Idle 与其它状态之间来回`() {
        assertEquals(KaraokeButtonState.Loading, KaraokeButtonMachine.onTap(KaraokeButtonState.Idle))
        assertEquals(KaraokeButtonState.Idle, KaraokeButtonMachine.onTap(KaraokeButtonState.Loading))
        // Active 再点 = 淡回原曲（回 Idle），不是"再加载一次"
        assertEquals(KaraokeButtonState.Idle, KaraokeButtonMachine.onTap(KaraokeButtonState.Active))
    }

    @Test
    fun `首段可用_只有仍在 Loading 时才进 Active`() {
        assertEquals(KaraokeButtonState.Active, KaraokeButtonMachine.onReady(KaraokeButtonState.Loading))
        assertEquals("已经是 Active，保持", KaraokeButtonState.Active, KaraokeButtonMachine.onReady(KaraokeButtonState.Active))
    }

    @Test
    fun `竞态_用户先取消_晚到的就绪回调不得把伴唱又打开`() {
        // 用户点 → Loading；等不及又点 → Idle；此时分离才报"首段可用"
        var s = KaraokeButtonMachine.onTap(KaraokeButtonState.Idle)   // Loading
        s = KaraokeButtonMachine.onTap(s)                             // 取消 → Idle
        s = KaraokeButtonMachine.onReady(s)                           // 晚到的就绪
        assertEquals("用户已取消，必须留在 Idle", KaraokeButtonState.Idle, s)
    }

    @Test
    fun `取消与失败一律回 Idle`() {
        for (s in KaraokeButtonState.entries) {
            assertEquals(KaraokeButtonState.Idle, KaraokeButtonMachine.onAbort(s))
        }
    }
}

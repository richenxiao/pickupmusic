package com.shiyin.music.data.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P1（用户 2026-10-18）：**"删除模型删不掉"** —— 删除必须真删、状态必须立刻变"未下载"。
 *
 * 事故根因不在 `delete()` 本身（它一直是 `File.delete()`），而在 VM 的
 * `refreshKaraokeSettings()` 里调了一段"**外部目录有文件就自动装上**"的自动导入
 * ⇒ 删完一刷新又被装回来，看起来就是删不掉。那段已删除。
 * 这里把"删完就是没了"这条语义钉在仓库层。
 */
class KaraokeModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = KaraokeModelStore(File(tmp.root, "karaoke-models"))

    /** 用一个**大小必然不对**的文件冒充"有文件"——不必真造 29MB。 */
    private fun fakeFile(s: KaraokeModelStore, m: MdxModel): File =
        s.fileOf(m).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64))
        }

    @Test
    fun `大小对不上不算已安装_但算损坏`() {
        val s = store()
        fakeFile(s, MdxModel.M9482)
        assertFalse(s.isInstalled(MdxModel.M9482))
        assertTrue(s.isBroken(MdxModel.M9482))
    }

    @Test
    fun `删除之后状态立刻变成未下载`() {
        val s = store()
        val f = fakeFile(s, MdxModel.M9482)
        assertTrue("前置条件：文件确实在", f.isFile)

        assertTrue(s.delete(MdxModel.M9482))

        assertFalse("删完文件必须没了", f.isFile)
        assertFalse("删完必须显示未下载（用户报的就是删完还显示已下载）", s.isInstalled(MdxModel.M9482))
        assertFalse(s.isBroken(MdxModel.M9482))
    }

    @Test
    fun `删除不存在的模型是幂等的`() {
        val s = store()
        assertTrue(s.delete(MdxModel.M9482))
        assertFalse(s.isInstalled(MdxModel.M9482))
    }

    /** 删掉一个不影响另一个。 */
    @Test
    fun `删除只影响目标模型`() {
        val s = store()
        fakeFile(s, MdxModel.INST_HQ_3)
        fakeFile(s, MdxModel.VOC_FT)
        s.delete(MdxModel.INST_HQ_3)
        assertFalse(s.fileOf(MdxModel.INST_HQ_3).isFile)
        assertTrue("别的模型不该被连坐", s.fileOf(MdxModel.VOC_FT).isFile)
    }

    @Test
    fun `sha256 校验_大小写不敏感_不符即false`() {
        val f = File(tmp.root, "x.bin").apply { writeBytes("hello".toByteArray()) }
        assertEquals(
            true,
            KaraokeModelStore.verifySha256(f, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
        )
        assertEquals(
            "大小写不敏感（官方清单里可能大小写混杂）",
            true,
            KaraokeModelStore.verifySha256(f, "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824"),
        )
        assertEquals(false, KaraokeModelStore.verifySha256(f, "deadbeef"))
    }
}

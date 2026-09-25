package com.shiyin.music.data.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v23: WebDAV 根地址规范化的单测。
 *
 * 坚果云官方文档把「服务器地址」(dav.jianguoyun.com) 与「路径」(/dav/) 分成两个
 * 字段给用户，而本应用只提供一个 URL 输入框 —— 用户只填主机名时 PUT 会落到主机根，
 * 服务端返回 404（实测："成功登录坚果云之后发现 404 无法备份"）。这里锁死自动补齐。
 */
class WebDavClientTest {

    @Test
    fun `补结尾斜杠`() {
        assertEquals("https://dav.x.com/dav/", WebDavClient.normalizeRoot("https://dav.x.com/dav"))
        assertEquals("https://dav.x.com/dav/", WebDavClient.normalizeRoot("https://dav.x.com/dav/"))
    }

    @Test
    fun `坚果云只填主机名时自动补 dav 路径`() {
        assertEquals("https://dav.jianguoyun.com/dav/", WebDavClient.normalizeRoot("https://dav.jianguoyun.com"))
        assertEquals("https://dav.jianguoyun.com/dav/", WebDavClient.normalizeRoot("https://dav.jianguoyun.com/"))
        assertEquals("https://dav.jianguoyun.com/dav/", WebDavClient.normalizeRoot("dav.jianguoyun.com"))
    }

    @Test
    fun `已带 dav 路径的坚果云地址不被重复拼接`() {
        assertEquals("https://dav.jianguoyun.com/dav/", WebDavClient.normalizeRoot("https://dav.jianguoyun.com/dav"))
        assertEquals("https://dav.jianguoyun.com/dav/", WebDavClient.normalizeRoot("https://dav.jianguoyun.com/dav/"))
    }

    @Test
    fun `用户自建的子目录不被改写`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/PickUpMusic/",
            WebDavClient.normalizeRoot("https://dav.jianguoyun.com/dav/PickUpMusic"),
        )
    }

    @Test
    fun `非坚果云地址只补斜杠`() {
        assertEquals("https://cloud.example.com/remote.php/dav/", WebDavClient.normalizeRoot("https://cloud.example.com/remote.php/dav"))
        assertEquals("http://192.168.1.9:8080/", WebDavClient.normalizeRoot("http://192.168.1.9:8080"))
    }

    @Test
    fun `缺 scheme 时补 https`() {
        assertEquals("https://dav.x.com/dav/", WebDavClient.normalizeRoot("dav.x.com/dav"))
    }

    @Test
    fun `首尾空白被裁掉`() {
        assertEquals("https://dav.jianguoyun.com/dav/", WebDavClient.normalizeRoot("  https://dav.jianguoyun.com/dav/  "))
    }

    @Test
    fun `空串原样返回`() {
        assertEquals("", WebDavClient.normalizeRoot(""))
        assertEquals("", WebDavClient.normalizeRoot("   "))
    }

    // ── v24: 实际读写目录 ────────────────────────────────────────────────────
    // 「测试连接成功、备份一直 404」的根因：文件被 PUT 到账户根 /dav/ 而不是
    // 应用专属子文件夹。以下锁死解析规则。

    @Test
    fun `只填挂载点时自动落到应用专属文件夹`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/PickUpMusic/",
            WebDavClient.resolveWorkDir("https://dav.jianguoyun.com/dav/"),
        )
        assertEquals(
            "https://dav.x.com/PickUpMusic/",
            WebDavClient.resolveWorkDir("https://dav.x.com/"),
        )
    }

    @Test
    fun `用户自建的子目录原样使用`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/我的备份/",
            WebDavClient.resolveWorkDir("https://dav.jianguoyun.com/dav/我的备份/"),
        )
        // 已经指向应用文件夹时不会被拼成 PickUpMusic/PickUpMusic
        assertEquals(
            "https://dav.jianguoyun.com/dav/PickUpMusic/",
            WebDavClient.resolveWorkDir("https://dav.jianguoyun.com/dav/PickUpMusic/"),
        )
    }

    @Test
    fun `Nextcloud 这类深路径不被擅自加层`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/me/",
            WebDavClient.resolveWorkDir("https://cloud.example.com/remote.php/dav/files/me/"),
        )
    }
}

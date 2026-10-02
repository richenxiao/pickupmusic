package com.shiyin.music.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0：使用日志里错误信息的**脱敏归类**。
 *
 * 测试向量取自**真实错误文案**（`WebDavClient` 里那些），因为这次要防的正是它们：
 * 原文里带远端地址（`https://dav.jianguoyun.com/dav/`）与目录路径（`/dav/xxx`），
 * 而日志会随云备份上移、也会被用户导出分享。核心断言不是"类别对不对"，
 * 而是**输出的字符串里绝不能出现原文里的地址、路径或模型输出片段**。
 */
class UsageLogErrorTest {

    private fun assertNoLeak(original: String, out: String) {
        assertFalse("输出不得包含原文:$out", out.contains(original))
        // 真正的泄漏标志是 URL / 路径 / 域名 —— 而"HTTP 404"这类类别本身就含 HTTP 字样,
        // 所以不能拿子串 "http" 当判据(第一版就是这么写错的)。
        assertFalse("输出不得带 URL:$out", out.contains("://"))
        assertFalse("输出不得带远端路径:$out", out.contains("/dav"))
        assertFalse("输出不得带域名:$out", out.contains("jianguoyun") || out.contains(".com") || out.contains(".cn"))
        assertTrue("类别应当是短串:$out", out.length <= 12)
    }

    @Test
    fun `WebDAV 的带 URL 文案只留状态码`() {
        val raw = "服务器返回 404：地址可能不对（坚果云一般是 https://dav.jianguoyun.com/dav/）"
        val out = UsageLogError.reasonOf(raw)
        assertEquals("HTTP 404", out)
        assertNoLeak(raw, out)
    }

    @Test
    fun `WebDAV 的带远端路径文案只留状态码`() {
        val raw = "目录不存在且无法创建：/dav/PickUpMusic/backup.zip（HTTP 409）"
        val out = UsageLogError.reasonOf(raw)
        assertEquals("HTTP 409", out)
        assertNoLeak(raw, out)
    }

    @Test
    fun `连接失败只留类别 - 不带主机名与端口`() {
        val raw = "连接失败：Failed to connect to dav.example.com/1.2.3.4:443"
        val out = UsageLogError.reasonOf(raw)
        assertEquals("连接失败", out)
        assertNoLeak(raw, out)
    }

    @Test
    fun `认证失败与写入失败的分类`() {
        assertEquals("鉴权失败", UsageLogError.reasonOf("认证失败：账号或密码不对，或没有该目录的写入权限"))
        assertEquals("HTTP 403", UsageLogError.reasonOf("写入失败：HTTP 403（/dav/xxx）"))
        // 带状态码时**状态码优先**（比"鉴权失败"更精确）；不带码的才落到关键词分类
        assertEquals("HTTP 401", UsageLogError.reasonOf("HTTP 401 Unauthorized"))
        assertEquals("鉴权失败", UsageLogError.reasonOf("Unauthorized: 请检查账号"))
    }

    @Test
    fun `模型与管线的错误文本也只留类别`() {
        assertEquals("HTTP 500", UsageLogError.reasonOf("LLM 调用失败:HTTP 500 服务器内部错误"))
        assertEquals("超时", UsageLogError.reasonOf("SocketTimeoutException: read timed out"))
        // 认不出的文本一律归"其他"——**不复制原文**（模型输出可能含对话内容）
        assertEquals("其他", UsageLogError.reasonOf("译文第 3 行解析失败：模型返回了「某个曲名」"))
        assertEquals("其他", UsageLogError.reasonOf("未知错误"))
    }

    @Test
    fun `空与非字符串输入`() {
        assertEquals("未知", UsageLogError.reasonOf(null))
        assertEquals("未知", UsageLogError.reasonOf("   "))
    }
}

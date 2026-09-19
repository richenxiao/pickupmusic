package com.shiyin.music.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v22: WebDAV 响应解析的单测。
 *
 * 用**真实服务的响应形状**做样例（坚果云 / Nextcloud 都返回 `207 Multi-Status`，
 * 但命名空间前缀、属性顺序、是否带 `d:` 前缀各不相同）—— 正则方案必然翻车，
 * 所以这里用 DOM 解析并按命名空间取元素，测试要钉死这一点。
 */
class WebDavXmlTest {

    /** 典型响应：一个目录自身 + 两个文件（含中文名，被百分号编码）。 */
    private val multiStatus = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/</D:href>
            <D:propstat><D:prop>
              <D:resourcetype><D:collection/></D:resourcetype>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/pickupmusic-backup-20260919-0130.zip</D:href>
            <D:propstat><D:prop>
              <D:resourcetype/>
              <D:getcontentlength>2411724</D:getcontentlength>
              <D:getlastmodified>Sat, 19 Sep 2026 01:30:00 GMT</D:getlastmodified>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/%E5%A4%87%E4%BB%BD%20%E4%B8%AD%E6%96%87.zip</D:href>
            <D:propstat><D:prop>
              <D:resourcetype/>
              <D:getcontentlength>1024</D:getcontentlength>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test fun parsesEntries() {
        val list = WebDavXml.parseMultiStatus(multiStatus)
        assertEquals(3, list.size)

        val dir = list[0]
        assertTrue("带 collection 的应是目录", dir.isDirectory)

        val f1 = list[1]
        assertEquals("pickupmusic-backup-20260919-0130.zip", f1.name)
        assertEquals(2411724L, f1.size)
        assertEquals("Sat, 19 Sep 2026 01:30:00 GMT", f1.lastModified)
        assertFalse(f1.isDirectory)
    }

    /** 中文/空格文件名会被百分号编码，必须解码回来才能正确请求下载。 */
    @Test fun decodesPercentEncodedNames() {
        val list = WebDavXml.parseMultiStatus(multiStatus)
        assertEquals("备份 中文.zip", list[2].name)
    }

    /** 有的服务用不带前缀的默认命名空间，也要能解析。 */
    @Test fun handlesDefaultNamespace() {
        val xml = """
            <?xml version="1.0"?>
            <multistatus xmlns="DAV:">
              <response>
                <href>/dav/a.zip</href>
                <propstat><prop><resourcetype/><getcontentlength>7</getcontentlength></prop></propstat>
              </response>
            </multistatus>
        """.trimIndent()
        val list = WebDavXml.parseMultiStatus(xml)
        assertEquals(1, list.size)
        assertEquals("a.zip", list[0].name)
        assertEquals(7L, list[0].size)
    }

    @Test fun garbageReturnsEmpty() {
        assertTrue(WebDavXml.parseMultiStatus("").isEmpty())
        assertTrue(WebDavXml.parseMultiStatus("not xml").isEmpty())
        assertTrue(WebDavXml.parseMultiStatus("<html><body>404</body></html>").isEmpty())
    }

    /** 缺 getcontentlength 时不该崩，按 0 处理。 */
    @Test fun missingSizeIsZero() {
        val xml = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
            <D:response><D:href>/dav/x.zip</D:href></D:response></D:multistatus>"""
        assertEquals(0L, WebDavXml.parseMultiStatus(xml)[0].size)
    }

    // ── URL 拼接 / 末段提取 ────────────────────────────────────────────────
    @Test fun lastSegmentStripsQueryAndTrailingSlash() {
        assertEquals("a.zip", WebDavXml.lastSegment("/dav/a.zip"))
        assertEquals("a.zip", WebDavXml.lastSegment("/dav/a.zip?x=1"))
        assertEquals("dav", WebDavXml.lastSegment("/dav/"))
        assertEquals("a b.zip", WebDavXml.lastSegment("/dav/a%20b.zip"))
    }

    @Test fun joinUrlNormalisesSlashes() {
        assertEquals("https://dav.x.com/dav/a.zip", WebDavXml.joinUrl("https://dav.x.com/dav/", "a.zip"))
        assertEquals("https://dav.x.com/dav/a.zip", WebDavXml.joinUrl("https://dav.x.com/dav", "a.zip"))
        assertEquals("https://dav.x.com/dav/a.zip", WebDavXml.joinUrl("https://dav.x.com/dav/", "/a.zip"))
    }

    /** 恶意 XML 里的外部实体（XXE）必须被拒绝，解析降级为空而不是去读本地文件。 */
    @Test fun xxeIsNotResolved() {
        val evil = """<?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/&xxe;</D:href></D:response>
            </D:multistatus>"""
        val list = WebDavXml.parseMultiStatus(evil)
        assertTrue("不应解析出 /etc/passwd 的内容",
            list.none { it.href.contains("root:") })
    }
}

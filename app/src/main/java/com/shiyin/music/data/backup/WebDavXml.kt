package com.shiyin.music.data.backup

/**
 * v22: WebDAV `PROPFIND` 多状态响应的解析 —— **纯逻辑，可单测**。
 *
 * 为什么用 `DocumentBuilderFactory` 而不是正则：各家 WebDAV 服务（坚果云 / Nextcloud /
 * 群晖）的 XML 前缀、属性顺序、是否带命名空间前缀都不一样，正则必然翻车。
 * 这个类是 JDK 自带的解析器，单元测试里也能直接跑。
 *
 * 安全：显式关掉外部实体（XXE）—— 远端返回的 XML 是不可信输入。
 */
object WebDavXml {

    private const val DAV = "DAV:"

    data class Entry(
        val href: String,
        /** href 末段并做过 URL 解码（中文文件名会被百分号编码）。 */
        val name: String,
        val size: Long,
        val lastModified: String,
        val isDirectory: Boolean,
    )

    /** 解析失败返回空列表（不抛）—— 调用方按"列不出文件"处理即可。 */
    fun parseMultiStatus(xml: String): List<Entry> {
        val doc = try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // 防 XXE
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        } catch (_: Exception) {
            return emptyList()
        }

        val out = ArrayList<Entry>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val el = responses.item(i) as? org.w3c.dom.Element ?: continue
            val href = firstText(el, "href") ?: continue
            val isDir = el.getElementsByTagNameNS(DAV, "collection").length > 0
            out += Entry(
                href = href,
                name = lastSegment(href),
                size = firstText(el, "getcontentlength")?.toLongOrNull() ?: 0L,
                lastModified = firstText(el, "getlastmodified").orEmpty(),
                isDirectory = isDir,
            )
        }
        return out
    }

    private fun firstText(parent: org.w3c.dom.Element, localName: String): String? {
        val list = parent.getElementsByTagNameNS(DAV, localName)
        if (list.length == 0) return null
        return list.item(0)?.textContent?.trim()
    }

    /** href 的末段 + URL 解码。`/dav/a%20b.zip` → `a b.zip`。 */
    fun lastSegment(href: String): String {
        val noQuery = href.substringBefore('?').substringBefore('#').trimEnd('/')
        val seg = noQuery.substringAfterLast('/')
        return try {
            java.net.URLDecoder.decode(seg, "UTF-8")
        } catch (_: Exception) {
            seg
        }
    }

    /** 把 base 与相对路径拼成完整 URL（保证只有一个斜杠交界）。 */
    fun joinUrl(base: String, name: String): String {
        val b = if (base.endsWith("/")) base else "$base/"
        val n = name.removePrefix("/")
        return b + n
    }
}

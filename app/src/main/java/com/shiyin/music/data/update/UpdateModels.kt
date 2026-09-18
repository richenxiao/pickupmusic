package com.shiyin.music.data.update

import com.google.gson.Gson

/** 一次「发现新版本」的结果。 */
data class UpdateInfo(
    /** 去掉 v 前缀的版本号，如 "1.3.1"。 */
    val version: String,
    /** 原始 tag，如 "v1.3.1"（拼下载地址要用）。 */
    val tag: String = "",
    /** Release 说明正文（可能为空）。 */
    val notes: String = "",
    /** Release 页面地址（兜底跳转用）。 */
    val pageUrl: String = "",
    /** .apk 资产的直链；拿不到时为空串，此时退回 [pageUrl]。 */
    val apkUrl: String = "",
) {
    /** 点「下载新版本」时该打开哪个地址。 */
    val downloadUrl: String get() = apkUrl.ifBlank { pageUrl }
}

/**
 * 版本号比较 —— 纯逻辑，可单测。
 *
 * 必须按**数值**逐段比较，不能按字符串：`1.10.0` > `1.9.0`，而字符串比较会得出
 * 相反的结论（"1.1" < "1.9"）。
 */
object VersionCompare {

    /** 取出 `1.3.0` / `v1.3.0` / `1.3.0-beta.2` / `1.3.0+build5` 里的数字段。
     *  一个数字都没有的字符串（`""` / `"abc"` / `"latest"`）返回空列表 —— 它不是版本号，
     *  交给调用方走"不提示更新"，而不是被当成 0.0.0。 */
    fun parts(raw: String): List<Int> {
        val s = raw.trim().removePrefix("v").removePrefix("V")
        val core = s.substringBefore('-').substringBefore('+')
        if (core.isBlank()) return emptyList()
        val segs = core.split('.')
        if (segs.none { seg -> seg.any { it.isDigit() } }) return emptyList()
        return segs.map { seg ->
            val digits = seg.takeWhile { it.isDigit() }
            digits.toIntOrNull() ?: 0
        }
    }

    /** a > b → 正数；a < b → 负数；相等 → 0。无法解析时按 0 处理。 */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    /**
     * [latest] 是否比 [current] 新。
     *
     * 两侧任一解析不出数字段都返回 false —— **宁可不提示，也不要弹一个可能是假的更新**。
     * （把空的当前版本当成 0.0.0 会让"有更新"永远为真，用户会一直被骚扰。）
     */
    fun isNewer(latest: String, current: String): Boolean {
        if (parts(latest).isEmpty()) return false
        if (parts(current).isEmpty()) return false
        return compare(latest, current) > 0
    }
}

/**
 * 「检查更新」的持久化状态 —— 存成一条 DataStore 字符串（JSON），避免为它加 6 个 key。
 *
 * [dismissedVersion] 是"忽略此版本"：用户点过之后同一版本不再提示，直到出现更新的版本。
 * [lastCheckAt] 用于节流：打开关于页时不每次都打网络。
 */
data class UpdateState(
    val lastCheckAt: Long = 0,
    val latestVersion: String = "",
    val latestTag: String = "",
    val notes: String = "",
    val apkUrl: String = "",
    val pageUrl: String = "",
    val dismissedVersion: String = "",
) {
    /** 点「下载新版本」该打开的地址。 */
    val downloadUrl: String get() = apkUrl.ifBlank { pageUrl }

    /** 相对 [current] 是否值得提示（有更新 且 不是用户已忽略的那个版本）。 */
    fun shouldPrompt(current: String): Boolean =
        VersionCompare.isNewer(latestVersion, current) &&
            VersionCompare.compare(latestVersion, dismissedVersion) != 0
}

object UpdateStateCodec {
    private val gson = Gson()

    fun encode(s: UpdateState): String = gson.toJson(s)

    /**
     * 逐字段防御式读取。
     *
     * **刻意不用 `gson.fromJson(raw, UpdateState::class.java)`**：Gson 对 Kotlin data class
     * 走 `UnsafeAllocator` 绕开构造器，JSON 里缺字段时字段会保持 **Java 默认值 null**，
     * 而不是 Kotlin 的默认值 `""`/`0`。于是 `latestVersion` 变成 null，随后
     * `VersionCompare.parts(null)` 直接 NPE —— 整个关于页崩掉。
     * （同一份 JSON 里只缺一个字段也会这样。）
     *
     * 这里按 key 逐个取并给兜底：缺字段、类型不符、值为 null 都退回默认值，最坏情况是
     * 丢掉一次检查结果，绝不抛异常。
     */
    fun decode(raw: String): UpdateState {
        if (raw.isBlank()) return UpdateState()
        return try {
            val o = com.google.gson.JsonParser.parseString(raw).asJsonObject
            UpdateState(
                lastCheckAt = o.long("lastCheckAt"),
                latestVersion = o.str("latestVersion"),
                latestTag = o.str("latestTag"),
                notes = o.str("notes"),
                apkUrl = o.str("apkUrl"),
                pageUrl = o.str("pageUrl"),
                dismissedVersion = o.str("dismissedVersion"),
            )
        } catch (_: Exception) {
            UpdateState()
        }
    }

    private fun com.google.gson.JsonObject.str(key: String): String =
        try { get(key)?.takeIf { !it.isJsonNull }?.asString ?: "" } catch (_: Exception) { "" }

    private fun com.google.gson.JsonObject.long(key: String): Long =
        try { get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L } catch (_: Exception) { 0L }
}

/**
 * 解析各条回退链路的响应 —— 纯逻辑，可单测。
 *
 * 三条链路的产出精度不同：
 * | 链路 | 版本 | 更新说明 | APK 直链 |
 * |---|---|---|---|
 * | GitHub Releases API（JSON） | ✓ | ✓ | ✓（有 .apk 资产时）|
 * | releases.atom（静态 XML） | ✓ | ✓ | ✗ |
 * | releases/latest 的 302 跳转 | ✓ | ✗ | ✗ |
 */
object UpdateParsers {

    private val gson = Gson()

    /** `GET /repos/{owner}/{repo}/releases/latest` 的响应。 */
    fun parseGithubJson(body: String?): UpdateInfo? {
        if (body.isNullOrBlank()) return null
        return try {
            val o = com.google.gson.JsonParser.parseString(body).asJsonObject
            val tag = o.get("tag_name")?.asString ?: return null
            val page = o.get("html_url")?.asString ?: ""
            val notes = o.get("body")?.asString ?: ""
            // 优先取 .apk 资产的直链；没有资产就退回 release 页面
            val apk = o.getAsJsonArray("assets")?.mapNotNull { el ->
                val a = el.asJsonObject
                val name = a.get("name")?.asString ?: return@mapNotNull null
                if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
                a.get("browser_download_url")?.asString
            }?.firstOrNull() ?: ""
            val v = tag.removePrefix("v").removePrefix("V")
            if (v.isBlank()) null
            else UpdateInfo(version = v, tag = tag, notes = notes, pageUrl = page, apkUrl = apk)
        } catch (_: Exception) {
            null
        }
    }

    /** `GET /releases.atom` 的响应（静态 feed，无 API 限额）。取最新一条 entry。 */
    fun parseAtom(body: String?): UpdateInfo? {
        if (body.isNullOrBlank()) return null
        return try {
            val firstEntry = body.substringAfter("<entry>", "").substringBefore("</entry>")
            if (firstEntry.isBlank()) return null
            // <link rel="alternate" type="text/html" href=".../releases/tag/v1.3.1"/>
            val href = Regex("""<link[^>]*rel="alternate"[^>]*href="([^"]+)"""")
                .find(firstEntry)?.groupValues?.get(1)
                ?: Regex("""<link[^>]*href="([^"]+)"""")
                    .find(firstEntry)?.groupValues?.get(1)
                ?: return null
            val tag = href.substringAfterLast("/releases/tag/", "").ifBlank {
                firstEntry.substringAfter("<title>", "").substringBefore("</title>").trim()
            }
            if (tag.isBlank()) return null
            val notes = firstEntry.substringAfter("<content", "")
                .substringAfter(">", "")
                .substringBefore("</content>")
            UpdateInfo(
                version = tag.removePrefix("v").removePrefix("V"),
                tag = tag,
                notes = unescapeHtml(notes).trim(),
                pageUrl = href,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 `https://github.com/{owner}/{repo}/releases/latest` 的**最终跳转地址**里取 tag。
     * GitHub 会把 `/releases/latest` 302 到 `/releases/tag/v1.3.1`，因此一次 HEAD 请求
     * 就能拿到版本号 —— 不碰 api.github.com，也不吃 API 限额。
     */
    fun tagFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val tag = url.substringAfterLast("/releases/tag/", "").substringBefore('?').substringBefore('#')
        if (tag.isBlank() || tag == url) return null
        return tag
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")
}

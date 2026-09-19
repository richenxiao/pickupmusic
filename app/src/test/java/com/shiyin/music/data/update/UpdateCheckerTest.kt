package com.shiyin.music.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v22: 「检查更新」的纯逻辑单测。
 *
 * 这三块都不依赖 Android/网络，但错了都会静默失效：
 * 版本比较错了 → 要么永远提示"有更新"（烦人），要么永远提示"已是最新"（用户永远升不了级）；
 * 解析错了 → 三条回退链路全部白搭；状态编解码错了 → "忽略此版本"不生效。
 */
class UpdateVersionTest {

    // ── 必须按数值比较，不能按字符串 ────────────────────────────────────────
    @Test fun comparesNumericallyNotLexicographically() {
        assertTrue(VersionCompare.isNewer("1.10.0", "1.9.0"))   // 字符串比较会得出相反结论
        assertTrue(VersionCompare.isNewer("1.3.10", "1.3.9"))
        assertTrue(VersionCompare.isNewer("2.0", "1.9.9"))
        assertTrue(VersionCompare.isNewer("10.0.0", "9.9.9"))
    }

    @Test fun stripsVPrefix() {
        assertEquals(listOf(1, 3, 0), VersionCompare.parts("v1.3.0"))
        assertEquals(listOf(1, 3, 0), VersionCompare.parts("V1.3.0"))
        assertEquals(0, VersionCompare.compare("v1.3.0", "1.3.0"))
        assertFalse(VersionCompare.isNewer("v1.3.0", "1.3.0"))
    }

    @Test fun ignoresPreReleaseAndBuildSuffix() {
        assertEquals(listOf(1, 3, 0), VersionCompare.parts("1.3.0-beta.2"))
        assertEquals(listOf(1, 3, 0), VersionCompare.parts("1.3.0+build5"))
        // 1.3.0-beta 与 1.3.0 视为同版本（不搞 pre-release 语义，够用且不会误报）
        assertFalse(VersionCompare.isNewer("1.3.0-beta.2", "1.3.0"))
    }

    @Test fun missingSegmentsCountAsZero() {
        assertEquals(0, VersionCompare.compare("1.3", "1.3.0"))
        assertTrue(VersionCompare.isNewer("1.3.1", "1.3"))
        assertTrue(VersionCompare.isNewer("1.4", "1.3.9"))
    }

    @Test fun garbageNeverReportsAnUpdate() {
        assertTrue(VersionCompare.parts("").isEmpty())
        assertTrue(VersionCompare.parts("abc").isEmpty())
        // 远端版本解析不出来 → 不提示
        assertFalse(VersionCompare.isNewer("", "1.3.0"))
        assertFalse(VersionCompare.isNewer("abc", "1.3.0"))
        // 当前版本解析不出来 → 也不提示（把空值当 0.0.0 会让"有更新"永远为真，一直骚扰用户）
        assertFalse(VersionCompare.isNewer("1.3.0", ""))
        assertFalse(VersionCompare.isNewer("1.3.0", "abc"))
        // compare 本身仍按 0 补齐（这是纯数学行为，供调用方自行决定）
        assertEquals(1, VersionCompare.compare("1.3.0", ""))
    }

    @Test fun equalVersionsAreNotNewer() {
        assertFalse(VersionCompare.isNewer("1.3.0", "1.3.0"))
        assertEquals(0, VersionCompare.compare("1.3.0", "1.3.0"))
    }
}

class UpdateParsersTest {

    @Test fun githubJson_withApkAsset() {
        val body = """
            {"tag_name":"v1.3.1","html_url":"https://github.com/o/r/releases/tag/v1.3.1",
             "body":"## 更新内容\n- 修了 A\n- 加了 B",
             "assets":[
               {"name":"pickupmusic-1.3.1.apk","browser_download_url":"https://github.com/o/r/releases/download/v1.3.1/pickupmusic-1.3.1.apk"},
               {"name":"mapping.txt","browser_download_url":"https://example.com/mapping.txt"}
             ]}
        """.trimIndent()
        val info = UpdateParsers.parseGithubJson(body)!!
        assertEquals("1.3.1", info.version)
        assertEquals("v1.3.1", info.tag)
        assertTrue(info.notes.contains("修了 A"))
        assertEquals(
            "https://github.com/o/r/releases/download/v1.3.1/pickupmusic-1.3.1.apk",
            info.apkUrl,
        )
        assertEquals(info.apkUrl, info.downloadUrl)
    }

    @Test fun githubJson_withoutApkAsset_fallsBackToPage() {
        val body = """{"tag_name":"v2.0.0","html_url":"https://github.com/o/r/releases/tag/v2.0.0","assets":[]}"""
        val info = UpdateParsers.parseGithubJson(body)!!
        assertEquals("2.0.0", info.version)
        assertEquals("", info.apkUrl)
        // 没有 apk 直链时 downloadUrl 退回 release 页面
        assertEquals("https://github.com/o/r/releases/tag/v2.0.0", info.downloadUrl)
    }

    @Test fun githubJson_onlyNonApkAssets_apkUrlEmpty() {
        val body = """{"tag_name":"v2.0.0","html_url":"https://x","assets":[{"name":"a.zip","browser_download_url":"https://x/a.zip"}]}"""
        assertEquals("", UpdateParsers.parseGithubJson(body)!!.apkUrl)
    }

    @Test fun githubJson_garbage_returnsNull() {
        assertNull(UpdateParsers.parseGithubJson(null))
        assertNull(UpdateParsers.parseGithubJson(""))
        assertNull(UpdateParsers.parseGithubJson("not json"))
        assertNull(UpdateParsers.parseGithubJson("""{"message":"Not Found"}"""))
    }

    /** atom feed 是第 2 条回退链路：无 API 限额，但拿不到 apk 直链。 */
    @Test fun atomFeed_takesNewestEntry() {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Release notes from pickupmusic</title>
              <entry>
                <id>tag:github.com,2008:Repository/1/v1.4.0</id>
                <link rel="alternate" type="text/html" href="https://github.com/o/r/releases/tag/v1.4.0"/>
                <title>v1.4.0</title>
                <content type="html">修了 X &amp; 加了 Y</content>
              </entry>
              <entry>
                <id>tag:github.com,2008:Repository/1/v1.3.1</id>
                <link rel="alternate" type="text/html" href="https://github.com/o/r/releases/tag/v1.3.1"/>
                <title>v1.3.1</title>
                <content type="html">旧版本</content>
              </entry>
            </feed>
        """.trimIndent()
        val info = UpdateParsers.parseAtom(body)!!
        assertEquals("1.4.0", info.version)
        assertEquals("v1.4.0", info.tag)
        assertEquals("https://github.com/o/r/releases/tag/v1.4.0", info.pageUrl)
        assertTrue("HTML 实体应被还原: ${info.notes}", info.notes.contains("修了 X & 加了 Y"))
        assertEquals("", info.apkUrl)
    }

    @Test fun atomFeed_garbage_returnsNull() {
        assertNull(UpdateParsers.parseAtom(null))
        assertNull(UpdateParsers.parseAtom(""))
        assertNull(UpdateParsers.parseAtom("<feed></feed>"))
    }

    /** 第 3 条回退链路：从 /releases/latest 的 302 目标里抠 tag。 */
    @Test fun tagFromRedirectUrl() {
        assertEquals("v1.3.1", UpdateParsers.tagFromUrl("https://github.com/o/r/releases/tag/v1.3.1"))
        assertEquals("v1.3.1", UpdateParsers.tagFromUrl("https://github.com/o/r/releases/tag/v1.3.1?x=1"))
        assertNull(UpdateParsers.tagFromUrl("https://github.com/o/r/releases/latest"))
        assertNull(UpdateParsers.tagFromUrl(null))
        assertNull(UpdateParsers.tagFromUrl(""))
    }
}

class UpdateStateTest {

    @Test fun roundTrip() {
        val s = UpdateState(
            lastCheckAt = 123L, latestVersion = "1.3.1", latestTag = "v1.3.1",
            notes = "含换行\n与引号\"的说明", apkUrl = "https://a/b.apk",
            pageUrl = "https://a", dismissedVersion = "1.3.0",
        )
        assertEquals(s, UpdateStateCodec.decode(UpdateStateCodec.encode(s)))
    }

    @Test fun blankOrGarbage_fallsBackToDefault() {
        assertEquals(UpdateState(), UpdateStateCodec.decode(""))
        assertEquals(UpdateState(), UpdateStateCodec.decode("not json"))
        assertEquals(UpdateState(), UpdateStateCodec.decode("{}"))
        assertEquals(UpdateState(), UpdateStateCodec.decode("[]"))
    }

    /**
     * 关键回归：缺字段 / 字段为 null / 类型不符都不能让字段变成 null。
     *
     * Gson 的 `fromJson(..., UpdateState::class.java)` 会走 UnsafeAllocator 绕开 Kotlin
     * 构造器，缺字段 → Java 默认值 null → 之后 VersionCompare.parts(null) NPE 崩掉关于页。
     * 所以 codec 必须逐字段兜底。
     */
    @Test fun missingOrNullFields_neverProduceNulls() {
        val partial = UpdateStateCodec.decode("""{"latestVersion":"1.3.1"}""")
        assertEquals("1.3.1", partial.latestVersion)
        assertEquals("", partial.notes)
        assertEquals("", partial.apkUrl)
        assertEquals(0L, partial.lastCheckAt)
        // 不崩、且能正常参与比较
        assertTrue(partial.shouldPrompt("1.3.0"))

        val nulls = UpdateStateCodec.decode(
            """{"latestVersion":null,"notes":null,"apkUrl":null,"pageUrl":null,"lastCheckAt":null,"dismissedVersion":null}"""
        )
        assertEquals("", nulls.latestVersion)
        assertEquals("", nulls.notes)
        assertEquals(0L, nulls.lastCheckAt)
        assertFalse(nulls.shouldPrompt("1.3.0"))

        // 类型不符 → 该字段退回默认，不影响其他字段
        val wrongType = UpdateStateCodec.decode("""{"lastCheckAt":"oops","latestVersion":"1.4.0"}""")
        assertEquals(0L, wrongType.lastCheckAt)
        assertEquals("1.4.0", wrongType.latestVersion)
    }

    @Test fun shouldPrompt_onlyForRealUndismissedUpdates() {
        val base = UpdateState(latestVersion = "1.3.1")
        assertTrue(base.shouldPrompt("1.3.0"))
        assertFalse("同版本不提示", base.shouldPrompt("1.3.1"))
        assertFalse("更旧不提示", base.shouldPrompt("1.4.0"))
        assertFalse("已忽略该版本不提示", base.copy(dismissedVersion = "1.3.1").shouldPrompt("1.3.0"))
        // 忽略的是旧版本 → 出现更新的版本仍要提示
        assertTrue(base.copy(latestVersion = "1.4.0", dismissedVersion = "1.3.1").shouldPrompt("1.3.0"))
        // 没有任何结果时不提示
        assertFalse(UpdateState().shouldPrompt("1.3.0"))
    }

    @Test fun downloadUrl_prefersApkThenPage() {
        assertEquals("https://a/b.apk", UpdateState(apkUrl = "https://a/b.apk", pageUrl = "https://a").downloadUrl)
        assertEquals("https://a", UpdateState(pageUrl = "https://a").downloadUrl)
        assertEquals("", UpdateState().downloadUrl)
    }
}

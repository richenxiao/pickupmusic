package com.shiyin.music.data.image

import com.shiyin.music.testing.NetworkTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * v1.2.0 #6: 歌手写真自动源集成测试。直接调 [ArtistImageSources.fetch]（无 DAO，
 * 纯 JVM + 真实网络），验证 shipped 代码确实能从 Discogs 取到人物照 URL。
 *
 * 实时网络测试:依赖外部 API,Discogs 未鉴权按 IP 限流会偶发失败。
 * @see com.shiyin.music.testing.NetworkTest —— 默认 testDebugUnitTest 排除本类,
 * 单独运行用 `gradle testNetwork`。
 */
@Category(NetworkTest::class)
class ArtistImageSourcesTest {

    @Test fun adele_resolvesFromDiscogs() = runBlocking {
        val img = ArtistImageSources.fetch("Adele", personOnly = true)
        assertNotNull("Adele 应能解析到写真", img)
        assertEquals("discogs", img!!.source)
        assertTrue("URL 应指向 discogs 图床", img.url.contains("i.discogs.com"))
    }

    @Test fun cjkArtist_resolves() = runBlocking {
        val img = ArtistImageSources.fetch("周杰伦", personOnly = true)
        assertNotNull("周杰伦 应能解析到写真（CJK）", img)
        assertTrue("URL 非空", img!!.url.isNotBlank())
    }

    @Test fun baike_openApi_resolvesCjkPortrait() = runBlocking {
        // v1.2.1: 百科主路径改为官方开放接口 BaikeLemmaCardApi(网页 /item/ 已普遍 403 验证码)。
        // 验证摘要肖像可取 + 图床域在百度 CDN。
        val imgs = BaiduBaikeSource.fetchAll("周杰伦", personOnly = false)
        assertTrue("百科开放 API 应取到周杰伦摘要肖像", imgs.isNotEmpty())
        assertTrue(
            "URL 应在百度图床(bkimg/bcebos): ${imgs.firstOrNull()?.url?.take(80)}",
            imgs.first().url.contains("bkimg") || imgs.first().url.contains("bcebos"),
        )
    }

    @Test fun baike_nonexistentLemma_returnsEmpty() = runBlocking {
        // 不存在词条:开放接口返回 {} → 必须空列表落下一源(而不是拿别的东西将就)。
        val imgs = BaiduBaikeSource.fetchAll("本词条必然不存在测试xyzq875", personOnly = false)
        assertTrue("不存在词条应返回空", imgs.isEmpty())
    }

    @Test fun baike_latinName_resolvesJapaneseArtist() = runBlocking {
        // v1.2.1.1: 去掉"仅 CJK"门后,纯拉丁名日系歌手(aiko)应经开放接口命中摘要肖像
        // (bk_key=aiko → 百科词条 /item/Aiko/5352130, key/title 均为 "aiko")。
        val imgs = BaiduBaikeSource.fetchAll("aiko", personOnly = false)
        assertTrue("百科应取到 aiko 摘要肖像", imgs.isNotEmpty())
        assertTrue(
            "URL 应在百度图床(bkimg/bcebos): ${imgs.firstOrNull()?.url?.take(80)}",
            imgs.first().url.contains("bkimg") || imgs.first().url.contains("bcebos"),
        )
        // fetchAll 会追加移动页图集(概述图/人物照等)。注意:桌面 JVM 的 JSSE TLS 指纹会被
        // 百度 WAF 403(实测),图集因此拿不到是环境性的;Android 上 OkHttp 走 Conscrypt,
        // 指纹不同可能放行——图集是 best-effort 增强,测试只断言必有摘要,不断言张数。
        assertTrue("fetchAll 应含 aiko 摘要, 实际=${imgs.size}", imgs.size >= 1)
    }
}

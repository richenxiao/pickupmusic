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
}

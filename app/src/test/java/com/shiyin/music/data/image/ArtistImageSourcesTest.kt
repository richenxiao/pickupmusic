package com.shiyin.music.data.image

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 #6: 歌手写真自动源集成测试。直接调 [ArtistImageSources.fetch]（无 DAO，
 * 纯 JVM + 真实网络），验证 shipped 代码确实能从 Discogs 取到人物照 URL。
 *
 * 注意：联网测试。Discogs 未鉴权按 IP 限流，偶发失败重跑即可。
 */
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

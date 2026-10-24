package com.shiyin.music.data.lyrics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LrcShare 协议层（[LrcShareApi]）的回归测试。
 *
 * 这里只测**能测的那一半**——请求本身要联网（在 LyricsFetcher 里），但真正容易错、
 * 且错起来用户能感知的部分全是纯逻辑：
 *  · **负向过滤的松紧方向**：判"库里没有"会让一首歌**永远搜不到**，判"可能没有"只是多发
 *    一次请求。所以过滤只能松、不能紧 —— 下面几条专门钉住"该放行的必须放行"。
 *  · `feat.` 写法差异（站方记 `AAA（feat.XXX）`、本机 tag 只有 `AAA`）不能导致错过。
 *  · 会话/目录的 24h 有效期与失效后的行为（令牌可丢、目录文本别跟着丢）。
 */
class LrcShareApiTest {

    @After
    fun tearDown() {
        LrcShareApi.reset()
        LrcShareApi.apiKey = ""
    }

    // ── Key 闸门 ──────────────────────────────────────────────────────────

    @Test
    fun `未配置 Key 时视为不可用`() {
        assertFalse("空串就是没配（该源整源不可用、一个请求都不发）", LrcShareApi.hasKey())
        LrcShareApi.apiKey = "   "
        assertFalse("只有空白也算没配", LrcShareApi.hasKey())
    }

    @Test
    fun `配置 Key 后可用`() {
        LrcShareApi.apiKey = "ls_0123456789abcdef0123456789abcdef"
        assertTrue(LrcShareApi.hasKey())
    }

    // ── 负向过滤 ──────────────────────────────────────────────────────────

    @Test
    fun `没拉过目录时一律放行_过滤不能变成准入闸门`() {
        assertTrue(
            "目录没就绪还拦请求，等于把源直接废掉",
            LrcShareApi.catalogMayContain("老师好", "阴三儿", nowMs = 1_000),
        )
    }

    @Test
    fun `目录里有这首歌就放行`() {
        putCatalog("老师好\n阴三儿\nsome other song")
        assertTrue(LrcShareApi.catalogMayContain("老师好", "阴三儿", nowMs = 1_000))
    }

    @Test
    fun `目录里连一个字都沾不上才跳过`() {
        putCatalog("老师好\n阴三儿")
        assertFalse(
            "确定库里没有 ⇒ 不发请求（站方给的保证：查询串不在快照里必然 0 结果）",
            LrcShareApi.catalogMayContain("完全不相干的歌名", "完全不相干的歌手", nowMs = 1_000),
        )
    }

    @Test
    fun `只有歌手能对上也要放行`() {
        putCatalog("阴三儿")
        assertTrue(
            "歌名没对上、歌手对上了 = 可能只是写法不同，必须放行（宁可多发一次）",
            LrcShareApi.catalogMayContain("北京晚报（现场版）", "阴三儿", nowMs = 1_000),
        )
    }

    @Test
    fun `本机带 feat 而目录是干净写法_放行`() {
        putCatalog("老师好\n阴三儿")
        assertTrue(
            "本机 tag 常写成「老师好 (feat. XXX)」，剥掉 feat. 后才对得上目录里那条干净的",
            LrcShareApi.catalogMayContain("老师好 (feat. 某人)", "阴三儿", nowMs = 1_000),
        )
    }

    @Test
    fun `目录带 feat 而本机是干净写法_放行`() {
        putCatalog("老师好（feat. 某人）\n阴三儿")
        assertTrue(
            "反向也要成立：目录里记的是带 feat. 的写法时，本机的干净歌名必须照样命中",
            LrcShareApi.catalogMayContain("老师好", "阴三儿", nowMs = 1_000),
        )
    }

    @Test
    fun `全角与大小写差异不影响命中`() {
        putCatalog("right now\nyoungqueenz")
        assertTrue(LrcShareApi.catalogMayContain("Right Now", "YoungQueenz", nowMs = 1_000))
    }

    @Test
    fun `目录过期后回到一律放行`() {
        val t0 = 1_000L
        putCatalog("老师好", nowMs = t0)
        assertFalse("有效期内：确实能判定", LrcShareApi.catalogMayContain("别的歌", "别的歌手", nowMs = t0))
        assertTrue(
            "过期后：不能再拿旧目录判'没有'（这一天里新入库的歌会被漏掉）",
            LrcShareApi.catalogMayContain("别的歌", "别的歌手", nowMs = t0 + LrcShareApi.CATALOG_TTL_MS),
        )
    }

    // ── 匹配键 ────────────────────────────────────────────────────────────

    @Test
    fun `匹配键覆盖 feat 的两种写法`() {
        val keys = LrcShareApi.matchKeys("老师好 (feat. 某人)", "阴三儿")
        assertTrue("干净歌名必须是键之一", "老师好" in keys)
        assertTrue("歌手名必须是键之一", "阴三儿" in keys)
        assertTrue("歌名+歌手拼一起也要给（库里可能把 feat 记进歌手）", "老师好阴三儿" in keys)
    }

    @Test
    fun `不给空键`() {
        assertTrue(LrcShareApi.matchKeys("", "").isEmpty())
        assertTrue(LrcShareApi.matchKeys("   ", "（feat. X）").isEmpty())
    }

    // ── 会话与目录缓存 ────────────────────────────────────────────────────

    @Test
    fun `会话在24小时内有效_过后失效`() {
        val t0 = 1_000_000L
        LrcShareApi.putCatalog("abc", "tok", t0)
        assertTrue(LrcShareApi.sessionValid(t0))
        assertTrue(LrcShareApi.sessionValid(t0 + LrcShareApi.CATALOG_TTL_MS - 1))
        assertFalse(LrcShareApi.sessionValid(t0 + LrcShareApi.CATALOG_TTL_MS))
    }

    @Test
    fun `没拉过目录时没有会话`() {
        assertFalse(LrcShareApi.sessionValid(1_000))
        assertNull(LrcShareApi.sessionTokenOrNull())
    }

    @Test
    fun `419 只丢令牌_目录文本留着`() {
        val t0 = 1_000L
        LrcShareApi.putCatalog("老师好\n阴三儿", "tok", t0)
        LrcShareApi.invalidateSession()
        assertFalse("令牌必须丢，否则会拿旧令牌再撞一次 419", LrcShareApi.sessionValid(t0))
        assertTrue(
            "目录文本还得能用 —— 负向过滤是省请求的关键，不能因为换了令牌就一起废掉",
            LrcShareApi.catalogMayContain("老师好", "阴三儿", t0),
        )
        assertEquals(1, LrcShareApi.sessionExpiredCount)
    }

    // ── 限速 ──────────────────────────────────────────────────────────────

    @Test
    fun `请求间隔不足时要等`() {
        val t = 10_000L
        assertEquals("第一次请求不用等", 0L, LrcShareApi.throttleWaitMs(t))
        LrcShareApi.markRequest(t)
        assertEquals(
            "刚发过请求 ⇒ 补足站方要求的间隔",
            LrcShareApi.MIN_GAP_MS,
            LrcShareApi.throttleWaitMs(t),
        )
        assertEquals("间隔够久了就不用等", 0L, LrcShareApi.throttleWaitMs(t + LrcShareApi.MIN_GAP_MS))
    }

    // ── 目录解析 ──────────────────────────────────────────────────────────

    @Test
    fun `解析目录响应_取文本与令牌并转小写`() {
        val body = """
            {"code":200,"data":{"generated_at":"2026-08-26T12:00:00.000Z",
             "songs":1234,"artists":567,"albums":89,
             "text":"Right Now\nYoungQueenz","session_token":"eyJhbGciOi.abc.def"}}
        """.trimIndent()
        val parsed = LrcShareApi.parseCatalog(body)
        assertNotNull(parsed)
        assertEquals("right now\nyoungqueenz", parsed!!.first)
        assertEquals("eyJhbGciOi.abc.def", parsed.second)
    }

    @Test
    fun `目录响应缺字段或不是 JSON 时返回 null`() {
        assertNull(LrcShareApi.parseCatalog(""))
        assertNull(LrcShareApi.parseCatalog("not json"))
        assertNull("缺 session_token", LrcShareApi.parseCatalog("""{"data":{"text":"abc"}}"""))
        assertNull("缺 text", LrcShareApi.parseCatalog("""{"data":{"session_token":"t"}}"""))
        assertNull("空 text", LrcShareApi.parseCatalog("""{"data":{"text":"","session_token":"t"}}"""))
        assertNull("缺 data", LrcShareApi.parseCatalog("""{"code":200}"""))
        assertNull("401 的响应体", LrcShareApi.parseCatalog("""{"code":401,"message":"missing X-API-Key header"}"""))
    }

    private fun putCatalog(text: String, nowMs: Long = 1_000L, token: String = "tok") {
        LrcShareApi.putCatalog(text, token, nowMs)
    }
}

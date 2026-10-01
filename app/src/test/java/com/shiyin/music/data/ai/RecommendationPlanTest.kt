package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.14:推荐选曲**纯逻辑层**的回归测试。
 *
 * 这一层决定三件事,错了都会直接伤到用户:
 *  ① 歌手限制是否真的生效(真机 bug:说"方大同的",推荐里一首方大同都没有);
 *  ② 候选池的规模与多样性(送模型太多 = 慢又贵;全同一人 = 选择没意义);
 *  ③ 模型输出解析(坏了要能给出**可诊断的原因**,而不是静默降级成热度排序)。
 */
class RecommendationPlanTest {

    // ── ① resolveArtists ──────────────────────────────────────────────────

    private val lib = listOf(
        1L to "方大同",
        2L to "藤井风",
        3L to "周杰倫",   // 繁体写法:归一化后应与"周杰伦"等价
        4L to "Vaundy",
        5L to "万青",     // 2 字歌手
    )

    @Test
    fun `模型参数优先 - 繁简与大小写差异都能命中`() {
        assertEquals(listOf("方大同"), RecommendationPlan.resolveArtists("方大同", "", lib).artists.map { it.name })
        // 繁体输入 → 库内简体
        assertEquals(listOf("周杰倫"), RecommendationPlan.resolveArtists("周杰伦", "", lib).artists.map { it.name })
        // 大小写
        assertEquals(listOf("Vaundy"), RecommendationPlan.resolveArtists("vaundy", "", lib).artists.map { it.name })
    }

    @Test
    fun `2 字歌手只能通过模型参数命中 - 不参与原话扫描`() {
        // 模型明确给了参数 → 认
        assertEquals(listOf("万青"), RecommendationPlan.resolveArtists("万青", "", lib).artists.map { it.name })
        // 只出现在用户原话里 → 不扫(长度 2 < 3),避免「夜的第七章」里的"夜"这类误命中
        assertTrue(
            "2 字歌手不该被原话扫描命中",
            RecommendationPlan.resolveArtists(null, "推荐万青的歌", lib).artists.isEmpty(),
        )
        // ≥3 字的歌手照常扫描
        assertEquals(
            listOf("方大同"),
            RecommendationPlan.resolveArtists(null, "推荐方大同适合睡觉的歌", lib).artists.map { it.name },
        )
    }

    @Test
    fun `原话同时提到多个歌手 - 取并集且不超过 3 个`() {
        val r = RecommendationPlan.resolveArtists(null, "推荐方大同和藤井风还有 Vaundy 的歌", lib)
        assertEquals(3, r.artists.size)
        assertTrue(r.artists.map { it.name }.containsAll(listOf("方大同", "藤井风", "Vaundy")))
    }

    @Test
    fun `库里没有这个歌手 - 如实 unresolved 并给近似建议,不换成别的歌手`() {
        val r = RecommendationPlan.resolveArtists("周杰伦", "", listOf(1L to "周杰倫"))
        // 「周杰伦」归一化后 == 「周杰倫」→ 这其实能命中,所以换一个真的没有的:
        val r2 = RecommendationPlan.resolveArtists("陳奕迅", "", listOf(1L to "方大同", 2L to "Vaundy"))
        assertEquals("应如实报告库里没有", "陳奕迅", r2.unresolved)
        assertTrue("不该拿别的歌手顶上", r2.artists.isEmpty())
        assertEquals("能命中时不该报 unresolved", 1, r.artists.size)
        assertNull(r.unresolved)
    }

    // ── ② buildCandidatePool ──────────────────────────────────────────────

    private fun pool(n: Int, artist: String = "方大同", album: String = "回到未来", startId: Long = 0) =
        (0 until n).map { PoolTrack(startId + it, "曲${it}", artist, album, playCount = n - it) }

    @Test
    fun `有歌手限定时按上限截断 - 同歌手不设多样性上限`() {
        // 100 首同一歌手:用户点名的就是他,不该被"同歌手最多 N 首"削掉
        val out = RecommendationPlan.buildCandidatePool(pool(100), cap = 80)
        assertEquals(80, out.size)
        assertTrue("编号应从 1 连续到 N", out.map { it.no } == (1..80).toList())
        assertTrue("同一歌手的歌应被保留", out.all { it.artist == "方大同" })
    }

    @Test
    fun `仅场景时启用同歌手上限 - 且不超过 150`() {
        val many = (0 until 40).flatMap { i -> pool(10, artist = "歌手$i", album = "专辑$i", startId = i * 10L) }
        val out = RecommendationPlan.buildCandidatePool(many, cap = 150, perArtistCap = 2)
        assertTrue("候选不超过上限", out.size <= 150)
        assertTrue("每个歌手最多 2 首", out.groupingBy { it.artist }.eachCount().values.all { it <= 2 })
    }

    @Test
    fun `同一首歌不会重复进池`() {
        val dup = listOf(PoolTrack(7, "唯一", "A", "X", 5), PoolTrack(7, "唯一", "A", "X", 5))
        val out = RecommendationPlan.buildCandidatePool(dup, cap = 10)
        assertEquals(1, out.size)
    }

    @Test
    fun `超出上限时最常听优先 - 其余按专辑均匀补齐`() {
        // 前 40 首是"最常听"(playCount 递减),后面 60 首来自 6 张专辑
        val top = (0 until 40).map { PoolTrack(it.toLong(), "热$it", "A", "热专", playCount = 1000 - it) }
        val rest = (0 until 60).map { PoolTrack(100 + it.toLong(), "冷$it", "A", "专${it % 6}", playCount = 10) }
        val out = RecommendationPlan.buildCandidatePool(top + rest, cap = 50)
        assertEquals(50, out.size)
        assertTrue("最常听的 40 首必须全在", out.take(40).all { it.title.startsWith("热") })
        // 补进来的 10 首应来自多张专辑(均匀抽样),而不是同一张
        assertTrue("尾部应跨专辑", out.drop(40).mapNotNull { it.album }.distinct().size > 1)
    }

    @Test
    fun `空输入与非法上限返回空`() {
        assertTrue(RecommendationPlan.buildCandidatePool(emptyList(), cap = 10).isEmpty())
        assertTrue(RecommendationPlan.buildCandidatePool(pool(3), cap = 0).isEmpty())
    }

    // ── ③ parsePickResult ─────────────────────────────────────────────────

    @Test
    fun `带围栏与多余文字也能解析`() {
        val raw = """
            好的,我的判断如下:
            ```json
            {"fit":"strong","picks":[{"i":1,"why":"很安静"},{"i":3,"why":"舒缓"}]}
            ```
        """.trimIndent()
        val out = RecommendationPlan.parsePickResult(raw, candidateCount = 5)
        assertTrue(out is PickOutcome.Ok)
        val ok = out as PickOutcome.Ok
        assertEquals(Fit.STRONG, ok.fit)
        assertEquals(listOf(1, 3), ok.picks.map { it.no })
    }

    @Test
    fun `序号越界与重复都被丢掉 - 越界导致无有效选择时判失败`() {
        // 重复:只留一条
        val dup = RecommendationPlan.parsePickResult("""{"fit":"weak","picks":[{"i":2,"why":"a"},{"i":2,"why":"b"}]}""", 5)
        assertEquals(listOf(2), (dup as PickOutcome.Ok).picks.map { it.no })
        // 全部越界 → fit 非 none 却没有有效 picks → 失败(可诊断)
        val oob = RecommendationPlan.parsePickResult("""{"fit":"strong","picks":[{"i":99,"why":"a"}]}""", 5)
        assertTrue(oob is PickOutcome.Fail)
        assertEquals("EMPTY_PICKS", (oob as PickOutcome.Fail).reason)
    }

    @Test
    fun `fit 非法或 JSON 坏掉都给出可诊断的原因`() {
        assertEquals("BAD_FIT", (RecommendationPlan.parsePickResult("""{"fit":"maybe"}""", 5) as PickOutcome.Fail).reason)
        assertEquals("BAD_FIT", (RecommendationPlan.parsePickResult("""{"picks":[]}""", 5) as PickOutcome.Fail).reason)
        assertEquals("INVALID_JSON", (RecommendationPlan.parsePickResult("模型说了一堆话", 5) as PickOutcome.Fail).reason)
    }

    @Test
    fun `fit 为 none 时允许空 picks`() {
        val out = RecommendationPlan.parsePickResult("""{"fit":"none","picks":[]}""", 5)
        assertTrue(out is PickOutcome.Ok)
        assertEquals(Fit.NONE, (out as PickOutcome.Ok).fit)
        assertTrue(out.picks.isEmpty())
    }

    @Test
    fun `理由会被截断 - 选择条数不超过 5`() {
        val many = (1..8).joinToString(",") { """{"i":$it,"why":"${"很".repeat(50)}"}""" }
        val out = RecommendationPlan.parsePickResult("""{"fit":"weak","picks":[$many]}""", 8) as PickOutcome.Ok
        assertEquals("最多 5 条", 5, out.picks.size)
        assertTrue("理由应被截断", out.picks.all { it.why.length <= RecommendationPlan.WHY_MAX_CHARS })
    }

    // ── ④ mergeIntent ─────────────────────────────────────────────────────

    private val t0 = 1_700_000_000_000L
    private fun prev(scene: String? = "睡觉", artist: String? = null, turn: Int = 1, ts: Long = t0) =
        RecIntent(scene, artist, null, turn, ts)

    @Test
    fun `仅歌手加短消息且上一轮有场景 - 代码兜底继承场景`() {
        val m = RecommendationPlan.mergeIntent(
            prev = prev("睡觉"), turnIndex = 2, nowMs = t0 + 60_000,
            userMessage = "方大同的", scene = null, artist = "方大同", album = null,
        )
        assertEquals("睡觉", m.scene)
        assertTrue("应标记为继承来的", m.inheritedScene)
        assertEquals("方大同", m.artist)
    }

    @Test
    fun `TTL 按回合数过期 - 超过 4 轮不继承`() {
        val m = RecommendationPlan.mergeIntent(
            prev = prev("睡觉", turn = 1), turnIndex = 6, nowMs = t0 + 60_000,
            userMessage = "方大同的", scene = null, artist = "方大同", album = null,
        )
        assertNull("超过 4 轮应失效", m.scene)
        assertFalse(m.inheritedScene)
    }

    @Test
    fun `TTL 按时间过期 - 超过 10 分钟不继承`() {
        val m = RecommendationPlan.mergeIntent(
            prev = prev("睡觉", turn = 1), turnIndex = 2, nowMs = t0 + RecommendationPlan.INTENT_TTL_MS + 1,
            userMessage = "方大同的", scene = null, artist = "方大同", album = null,
        )
        assertNull("超过 10 分钟应失效", m.scene)
    }

    @Test
    fun `长消息不触发兜底继承 - 那更像一个全新请求`() {
        val m = RecommendationPlan.mergeIntent(
            prev = prev("睡觉"), turnIndex = 2, nowMs = t0 + 60_000,
            userMessage = "换个歌单吧我想听点别的完全不一样的东西", scene = null, artist = "方大同", album = null,
        )
        assertNull(m.scene)
    }

    @Test
    fun `本轮只给场景时不继承歌手 - 无法可靠区分"还是他"与"换话题"`() {
        val m = RecommendationPlan.mergeIntent(
            prev = prev("睡觉", artist = "方大同"), turnIndex = 2, nowMs = t0 + 60_000,
            userMessage = "换个适合跑步的", scene = "跑步", artist = null, album = null,
        )
        assertEquals("跑步", m.scene)
        assertNull("歌手不该被继承", m.artist)
        assertFalse(m.inheritedScene)
    }

    @Test
    fun `本轮给了场景时以本轮为准 - 不标记继承`() {
        val m = RecommendationPlan.mergeIntent(
            prev = prev("睡觉"), turnIndex = 2, nowMs = t0 + 60_000,
            userMessage = "换成跑步的", scene = "跑步", artist = null, album = null,
        )
        assertEquals("跑步", m.scene)
        assertFalse(m.inheritedScene)
    }

    @Test
    fun `没有上一轮意图时不继承`() {
        val m = RecommendationPlan.mergeIntent(
            prev = null, turnIndex = 2, nowMs = t0,
            userMessage = "方大同的", scene = null, artist = "方大同", album = null,
        )
        assertNull(m.scene)
    }
}

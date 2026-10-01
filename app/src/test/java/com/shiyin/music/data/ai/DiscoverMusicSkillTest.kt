package com.shiyin.music.data.ai

import com.google.gson.Gson
import com.shiyin.music.data.Track
import com.shiyin.music.data.ai.skills.DiscoverMusicSkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.15:`discover_music`(按场景/歌手/专辑挑歌)的回归测试。
 *
 * 锁的是三条真机缺陷:
 *  ① 「推荐方大同适合睡觉的歌」→ 推荐里一首方大同都没有(歌手硬约束没在代码里落地);
 *  ② 用户补一句「方大同的」→ 场景被丢掉,降级成按热度排(没有槽位继承);
 *  ③ 挑不出贴合的就静默降级(不告诉用户"这几首是按最常听给的")。
 *
 * 另锁两条契约:文本清单条数 == 可点选项条数;旧参数名 `mood` 当 `scene` 处理。
 */
class DiscoverMusicSkillTest {

    /** 未配置 key 的供应商:技能里的模型调用会直接跳过(不联网),正好用来测"没有模型"的路径。 */
    private val cfg = LlmProviderConfig(key = "test", displayName = "测试", baseUrl = "https://example.invalid")

    /** Track 用 Gson 反序列化造(Uri 是 android 桩,构造器在 JVM 单测里会 NPE —— 沿用既有做法)。 */
    private fun track(id: Long, title: String, artist: String, album: String = "专辑X"): Track =
        Gson().fromJson(
            """{"id":$id,"title":"$title","artist":"$artist","album":"$album"}""",
            Track::class.java,
        )

    private class Fake(
        val tracks: List<Track>,
        val albums: Map<String, List<Track>> = emptyMap(),
        val turn: RecTurnContext = RecTurnContext(),
    ) : FakeSkillContext() {
        override fun listAllTracks(): List<Track> = tracks
        override fun albumTracksOf(key: String): List<Track> = albums[key].orEmpty()
        override fun findAlbum(query: String): String? = albums.keys.firstOrNull { it == query }
        override fun recTurnContext(): RecTurnContext = turn
        val recorded = mutableListOf<Triple<String?, String?, String?>>()
        override fun recordRecIntent(scene: String?, artist: String?, album: String?) {
            recorded += Triple(scene, artist, album)
        }
    }

    private fun run(ctx: Fake, args: Map<String, String>) = runBlocking {
        DiscoverMusicSkill.execute(ctx, args, cfg, "") { _, _ -> }
    }

    private fun diagOf(ctx: Fake, stage: String) = ctx.diag.firstOrNull { it.contains("/$stage/") }.orEmpty()

    /** 摘要里的曲目行数(首行是标题,不计)。 */
    private fun itemLines(summary: String) = summary.lines().count { it.startsWith("《") }

    private val fangdaTong = (1..30).map { track(it.toLong(), "方大同曲$it", "方大同") }
    private val others = (101..120).map { track(it.toLong(), "别人曲$it", "藤井风") }

    // ── ① 歌手硬约束:在代码里落地,不靠模型自觉 ──────────────────────────────

    @Test
    fun `限定歌手时候选池不削同歌手 - 30 首全进池`() {
        val ctx = Fake(fangdaTong + others)
        val r = run(ctx, mapOf("artist" to "方大同"))
        val poolDiag = diagOf(ctx, "pool")
        assertTrue("候选池应是该歌手全部 30 首(而非被同歌手上限砍到 2):$poolDiag", poolDiag.contains("pool=30"))
        assertTrue("限定歌手时应关闭同歌手上限:$poolDiag", poolDiag.contains("restricted=1"))
        assertTrue("首行标题要写明是谁的歌", r.summary.lines().first().contains("方大同"))
    }

    @Test
    fun `只给歌手时不走模型 - 因此文案不是降级文案`() {
        val ctx = Fake(fangdaTong + others)
        val r = run(ctx, mapOf("artist" to "方大同"))
        assertFalse("没有场景时不存在'贴合度'可判,不该出现降级措辞", r.summary.contains("没有明显贴合"))
        assertTrue("应给可点选项", r.options.isNotEmpty())
        assertTrue("不该记录 pick_parse 阶段(根本没调模型)", diagOf(ctx, "pick_parse").isEmpty())
    }

    @Test
    fun `文本清单条数与可点选项条数一致`() {
        val ctx = Fake(fangdaTong + others)
        val r = run(ctx, mapOf("artist" to "方大同"))
        assertEquals("列出来的每一条都要能点到,否则用户以为卡片坏了", itemLines(r.summary), r.options.size)
        assertTrue("选项要写成能被当成指令的句子", r.options.all { it.startsWith("播放《") })
    }

    // ── ② 场景:老参数名兼容 + 槽位继承 ──────────────────────────────────────

    @Test
    fun `旧参数 mood 按 scene 处理`() {
        val ctx = Fake(fangdaTong + others)
        // 未配置模型 → 场景路径必然走到"显式降级",而降级文案里会念出场景词
        val r = run(ctx, mapOf("mood" to "睡觉", "artist" to "方大同"))
        assertTrue("mood 应被当成 scene(降级文案里要出现「睡觉」):${r.summary}", r.summary.contains("睡觉"))
        assertTrue("降级文案必须说清楚这是按最常听给的", r.summary.contains("没有明显贴合"))
        assertTrue("降级也要在限定范围内(不能越出方大同)", r.summary.contains("方大同"))
    }

    @Test
    fun `补一句歌手+上一轮有场景 - 代码继承场景并记进槽位`() {
        val ctx = Fake(
            fangdaTong + others,
            turn = RecTurnContext(
                prev = RecIntent("睡觉", null, null, turnIndex = 1, timestampMs = 1_000L),
                turnIndex = 2, nowMs = 2_000L, userMessage = "方大同的",
            ),
        )
        run(ctx, mapOf("artist" to "方大同"))
        assertEquals(
            "继承来的场景必须写回槽位,否则下一轮就丢了",
            Triple("睡觉", "方大同", null), ctx.recorded.single(),
        )
    }

    @Test
    fun `长消息不触发继承 - 那更像换了话题`() {
        val ctx = Fake(
            fangdaTong + others,
            turn = RecTurnContext(
                prev = RecIntent("睡觉", null, null, turnIndex = 1, timestampMs = 1_000L),
                turnIndex = 2, nowMs = 2_000L,
                userMessage = "换个完全不搭界的东西我们来聊聊别的吧",
            ),
        )
        run(ctx, mapOf("artist" to "方大同"))
        assertEquals(Triple(null, "方大同", null), ctx.recorded.single())
    }

    // ── ③ 诚实:库里没有这个歌手时不许拿别人顶上 ─────────────────────────────

    @Test
    fun `库里没有这个歌手 - 如实说且不给任何歌`() {
        val ctx = Fake(fangdaTong + others)
        val r = run(ctx, mapOf("artist" to "陳奕迅"))
        assertTrue("要如实说库里没有:${r.summary}", r.summary.contains("库里没有"))
        assertFalse("不许拿别的歌手的歌顶上", r.options.any { it.startsWith("播放《") })
        assertFalse("这是失败,不是成功", r.success)
        assertTrue("诊断里要能看出没解析到歌手", diagOf(ctx, "artist_resolve").contains("UNRESOLVED_ARTIST"))
    }

    @Test
    fun `一个参数都没给 - 问清楚,不硬猜`() {
        val ctx = Fake(fangdaTong + others)
        val r = run(ctx, mapOf())
        assertFalse(r.success)
        assertTrue("应反问用户要什么", r.summary.contains("?"))
        assertTrue("诊断要能看出是缺参数", diagOf(ctx, "args").contains("MISSING_ARGS"))
    }

    @Test
    fun `库里没有这张专辑 - 如实说`() {
        val ctx = Fake(fangdaTong + others, albums = mapOf("真专辑" to fangdaTong.take(3)))
        val r = run(ctx, mapOf("album" to "不存在的专辑"))
        assertFalse(r.success)
        assertTrue(r.summary.contains("不存在的专辑"))
        assertTrue(diagOf(ctx, "pool").contains("ALBUM_NOT_FOUND"))
    }

    @Test
    fun `限定专辑时候选只来自该专辑`() {
        val albumTracks = (201..206).map { track(it.toLong(), "专辑曲$it", "方大同", album = "真专辑") }
        val ctx = Fake(fangdaTong + others, albums = mapOf("真专辑" to albumTracks))
        val r = run(ctx, mapOf("album" to "真专辑"))
        assertTrue(r.summary.lines().first().contains("真专辑"))
        assertTrue("选项只能来自该专辑", r.options.size <= 5 && r.options.isNotEmpty())
        assertTrue("诊断里池子应就是该专辑的 6 首:${diagOf(ctx, "pool")}", diagOf(ctx, "pool").contains("pool=6"))
    }
}

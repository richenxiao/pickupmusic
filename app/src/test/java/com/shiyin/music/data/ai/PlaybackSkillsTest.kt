package com.shiyin.music.data.ai

import com.shiyin.music.data.Track
import com.shiyin.music.data.ai.skills.PlaybackControlSkill
import com.shiyin.music.data.ai.skills.PlaybackModeSkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.8 播放控制 / 播放模式技能的纯逻辑测试。
 *
 * 这两个技能零 LLM、零网络,参数已由模型在分发时抽好,所以能纯逻辑测完。
 *
 * 重点:
 *  1. **不误报**——"现在没在播放"时说"已暂停"是假确认;没找到歌时说"正在播放"更糟。
 *  2. **不产生半落地状态**——速度非法时不能把随机播放先改了(用户会看到状态只改一半)。
 *  3. 只动播放状态:这两个技能不允许触碰音乐库写回(不可逆操作永不接入 Agent)。
 */
class PlaybackSkillsTest {

    /** 播放态可控的假上下文;同时记录"是否发生了改库写回"以便断言越界。 */
    private class PlaybackFake(
        private val albumHits: Map<String, List<Track>> = emptyMap(),
        private val libraryHits: List<Track> = emptyList(),
        private var playing: Boolean = false,
        private var current: Track? = null,
    ) : FakeSkillContext() {
        var played: List<Track>? = null
        var playedStart: Long? = null
        var pausedCount = 0
        var resumedCount = 0
        var nextCount = 0
        var prevCount = 0
        var shuffle: Boolean? = null
        var repeat: AgentEngine.RepeatMode? = null
        var speed: Float? = null
        var wroteLibrary = false

        override fun currentTrack(): Track? = current
        override fun searchTracks(query: String, limit: Int): List<Track> = libraryHits
        override fun findAlbum(query: String): String? = albumHits.keys.firstOrNull { it == query }
        override fun albumTracksOf(key: String): List<Track> = albumHits[key] ?: emptyList()

        override fun playTracks(tracks: List<Track>, startId: Long) {
            played = tracks
            playedStart = startId
            current = tracks.firstOrNull()
            playing = true
        }
        override fun pausePlayback() { pausedCount++; playing = false }
        override fun resumePlayback() { resumedCount++; playing = true }
        override fun nextTrack() { nextCount++ }
        override fun previousTrack() { prevCount++ }
        override fun isPlaying(): Boolean = playing
        override fun setShuffle(on: Boolean) { shuffle = on }
        override fun isShuffleOn(): Boolean = shuffle == true
        override fun setRepeatMode(mode: AgentEngine.RepeatMode) { repeat = mode }
        override fun repeatMode(): AgentEngine.RepeatMode = repeat ?: AgentEngine.RepeatMode.OFF
        override fun setPlaybackSpeed(speed: Float) { this.speed = speed }

        // 一旦被调用就说明越界(这两个技能不该改库)
        override fun saveTrackInfo(mediaId: Long, title: String, artist: String) { wroteLibrary = true }
        override fun applyAlbumOrderIndices(key: String, indices: List<Int>) { wroteLibrary = true }
    }

    private val cfg = LlmProviderConfig("test", "test", "https://example.invalid", "", "")

    /**
     * 构造测试用 Track。
     *
     * **为什么不用构造器**:`Track.uri` 是 `android.net.Uri`,而 JVM 单测里 android.jar
     * 是桩——`Uri.EMPTY` 和 `Uri.parse()` 在 `returnDefaultValues` 下都返回 null,
     * 构造器的非空校验直接 NPE(实测踩到:"EMPTY must not be null")。项目只有 JUnit,
     * 没有 Mockito/Robolectric。改用 Gson 反序列化:它走 Unsafe 分配、绕开构造器,
     * 技能只读 id/title/artist/album,不碰 uri。
     */
    private fun track(id: Long, title: String, artist: String = "周杰伦", album: String = "叶惠美"): Track =
        com.google.gson.Gson().fromJson(
            """{"id":$id,"title":"$title","artist":"$artist","album":"$album"}""",
            Track::class.java,
        )

    private fun ctl(ctx: PlaybackFake, args: Map<String, String>) =
        runBlocking { PlaybackControlSkill.execute(ctx, args, cfg, "") { _, _ -> } }

    private fun mode(ctx: PlaybackFake, args: Map<String, String>) =
        runBlocking { PlaybackModeSkill.execute(ctx, args, cfg, "") { _, _ -> } }

    // ── playback_control ──────────────────────────────────────────────────

    // ── v1.3.12 真机 bug:同名专辑 vs 单曲 ───────────────────────────────
    // 「给我播放藤井风的 prema」库里既有单曲 prema 又有专辑 prema,旧实现**先找专辑**,
    // 于是整张 9 首被播了 —— 用户随后只能补一句「我说的是单曲」。

    @Test
    fun `点名作品名时默认放单曲 - 同名专辑不再被抢先播整张`() {
        val single = track(1, "prema")
        val ctx = PlaybackFake(
            albumHits = mapOf("prema" to listOf(single, track(2, "别的"))),
            libraryHits = listOf(single),
        )
        val r = ctl(ctx, mapOf("action" to "play", "target" to "prema"))
        assertTrue("应成功:${r.summary}", r.success)
        assertEquals("只应放那一首,不能播整张", 1, ctx.played?.size)
        assertEquals(single.id, ctx.played?.first()?.id)
        assertFalse("不该说「开始播放专辑」:${r.summary}", r.summary.contains("专辑"))
    }

    @Test
    fun `明确要整张专辑时才放整张 - scope=album`() {
        val ctx = PlaybackFake(
            albumHits = mapOf("prema" to listOf(track(1, "prema"), track(2, "二"), track(3, "三"))),
            libraryHits = listOf(track(1, "prema")),
        )
        val r = ctl(ctx, mapOf("action" to "play", "target" to "prema", "scope" to "album"))
        assertEquals("scope=album 时应播整张", 3, ctx.played?.size)
        assertTrue(r.summary.contains("专辑"))
    }

    @Test
    fun `库里只有同名专辑时兜底放整张 - 并如实说明`() {
        val ctx = PlaybackFake(
            albumHits = mapOf("prema" to listOf(track(1, "别的歌"), track(2, "另一首"))),
            libraryHits = emptyList(),
        )
        val r = ctl(ctx, mapOf("action" to "play", "target" to "prema"))
        assertEquals("单曲里没有 → 放同名专辑", 2, ctx.played?.size)
        assertTrue("要如实说明放的是专辑:${r.summary}", r.summary.contains("专辑"))
    }

    @Test
    fun `同一首已经在放时不重新从头放 - 由技能说这句人话`() {
        val single = track(1, "prema")
        val ctx = PlaybackFake(libraryHits = listOf(single), playing = true, current = single)
        val r = ctl(ctx, mapOf("action" to "play", "target" to "prema"))
        assertNull("不该重新播放", ctx.played)
        assertTrue("应说明已经在放:${r.summary}", r.summary.contains("已经在放"))
    }

    @Test
    fun `明确要求重启时才从头放 - restart=true`() {
        val single = track(1, "prema")
        val ctx = PlaybackFake(libraryHits = listOf(single), playing = true, current = single)
        val r = ctl(ctx, mapOf("action" to "play", "target" to "prema", "restart" to "true"))
        assertEquals("明确要重来 → 真的重放", single.id, ctx.played?.first()?.id)
        assertTrue(r.success)
    }

    @Test
    fun `没在播放时同一首照常播放 - 幂等只在"正在放"时生效`() {
        val single = track(1, "prema")
        val ctx = PlaybackFake(libraryHits = listOf(single), playing = false, current = single)
        ctl(ctx, mapOf("action" to "play", "target" to "prema"))
        assertEquals("暂停状态下的播放请求必须真的放", single.id, ctx.played?.first()?.id)
    }

    @Test
    fun `没在播放时说暂停不谎报已暂停`() {
        val ctx = PlaybackFake(playing = false, current = track(1, "晴天"))
        val r = ctl(ctx, mapOf("action" to "pause"))
        assertEquals("不该调用 pause", 0, ctx.pausedCount)
        assertTrue("应如实说明没在播放,实际:${r.summary}", r.summary.contains("没有在播放"))
    }

    @Test
    fun `暂停与继续各自落到对应操作`() {
        val playing = PlaybackFake(playing = true, current = track(1, "晴天"))
        assertTrue(ctl(playing, mapOf("action" to "pause")).success)
        assertEquals(1, playing.pausedCount)

        val paused = PlaybackFake(playing = false, current = track(1, "晴天"))
        assertTrue(ctl(paused, mapOf("action" to "resume")).success)
        assertEquals(1, paused.resumedCount)
    }

    @Test
    fun `已经在播放时 continue 不重复触发`() {
        val ctx = PlaybackFake(playing = true, current = track(1, "晴天"))
        val r = ctl(ctx, mapOf("action" to "play"))
        assertEquals("已在播放时不该再 resume", 0, ctx.resumedCount)
        assertTrue(r.success)
    }

    @Test
    fun `没有任何曲目时不假装切歌`() {
        val ctx = PlaybackFake(current = null)
        assertFalse(ctl(ctx, mapOf("action" to "next")).success)
        assertEquals(0, ctx.nextCount)
        assertFalse(ctl(ctx, mapOf("action" to "previous")).success)
        assertEquals(0, ctx.prevCount)
    }

    @Test
    fun `点名专辑时播放整张`() {
        val tracks = listOf(track(1, "以父之名"), track(2, "懦夫"))
        val ctx = PlaybackFake(albumHits = mapOf("叶惠美" to tracks))
        val r = ctl(ctx, mapOf("action" to "play", "target" to "叶惠美"))
        assertEquals(tracks, ctx.played)
        assertEquals("应从第一首开始", 1L, ctx.playedStart)
        assertTrue(r.success)
    }

    @Test
    fun `点名歌名时精确命中只放这一首`() {
        val hits = listOf(track(2, "晴天"), track(3, "晴天娃娃"))
        val ctx = PlaybackFake(libraryHits = hits)
        val r = ctl(ctx, mapOf("action" to "play", "target" to "晴天"))
        assertEquals("精确命中应只放一首", 1, ctx.played?.size)
        assertEquals("晴天", ctx.played?.first()?.title)
        assertTrue(r.summary.contains("晴天"))
    }

    @Test
    fun `点名歌手时播放这批而不是空手而归`() {
        val hits = listOf(track(2, "晴天"), track(3, "七里香"))
        val ctx = PlaybackFake(libraryHits = hits)
        val r = ctl(ctx, mapOf("action" to "play", "target" to "周杰伦"))
        assertEquals(2, ctx.played?.size)
        assertTrue(r.success)
    }

    @Test
    fun `库里找不到时明说而不是假装播放`() {
        val ctx = PlaybackFake(libraryHits = emptyList())
        val r = ctl(ctx, mapOf("action" to "play", "target" to "不存在的歌"))
        assertNull(ctx.played)
        assertFalse(r.success)
        assertTrue("应带上是哪个名字没找到,实际:${r.summary}", r.summary.contains("不存在的歌"))
    }

    @Test
    fun `无法识别的动作给引导而不是静默成功`() {
        val ctx = PlaybackFake()
        val r = ctl(ctx, mapOf("action" to "dance"))
        assertFalse(r.success)
        assertTrue(r.summary.contains("暂停") || r.summary.contains("下一首"))
    }

    @Test
    fun `播放控制不触碰音乐库写回`() {
        val ctx = PlaybackFake(libraryHits = listOf(track(2, "晴天")))
        ctl(ctx, mapOf("action" to "play", "target" to "晴天"))
        assertFalse("playback_control 绝不能改库(不可逆约定)", ctx.wroteLibrary)
    }

    // ── playback_mode ─────────────────────────────────────────────────────

    @Test
    fun `随机播放开关两个方向都能设`() {
        val on = PlaybackFake()
        assertTrue(mode(on, mapOf("shuffle" to "true")).success)
        assertEquals(true, on.shuffle)

        val off = PlaybackFake()
        assertTrue(mode(off, mapOf("shuffle" to "false")).success)
        assertEquals(false, off.shuffle)
    }

    @Test
    fun `循环模式一步到位而不是循环切换`() {
        val ctx = PlaybackFake()
        assertTrue(mode(ctx, mapOf("repeat" to "one")).success)
        assertEquals("单曲循环必须一次设定,不能靠 cycle 碰运气", AgentEngine.RepeatMode.ONE, ctx.repeat)
    }

    @Test
    fun `速度落到量化档位`() {
        val ctx = PlaybackFake()
        assertTrue(mode(ctx, mapOf("speed" to "1.5")).success)
        assertTrue("速度应被量化到应用支持的档位,实际:${ctx.speed}", ctx.speed != null && ctx.speed!! in 0.5f..2.0f)
    }

    @Test
    fun `速度超出范围时不做半落地`() {
        // 关键:速度和随机同时给出、速度非法 → 不能把随机先改了(用户会看到状态只改一半)
        val ctx = PlaybackFake()
        val r = mode(ctx, mapOf("speed" to "5.0", "shuffle" to "true"))
        assertFalse(r.success)
        assertNull("速度被拒时不应已改随机", ctx.shuffle)
        assertNull(ctx.speed)
    }

    @Test
    fun `没给任何字段时追问而不是瞎设`() {
        val ctx = PlaybackFake()
        val r = mode(ctx, emptyMap())
        assertFalse(r.success)
        assertNull(ctx.shuffle)
        assertNull(ctx.repeat)
        assertNull(ctx.speed)
    }

    @Test
    fun `非法循环值被拒`() {
        val ctx = PlaybackFake()
        assertFalse(mode(ctx, mapOf("repeat" to "sometimes")).success)
        assertNull(ctx.repeat)
    }

    @Test
    fun `回执带上真实当前态便于用户核对`() {
        val ctx = PlaybackFake()
        val r = mode(ctx, mapOf("shuffle" to "true", "repeat" to "all"))
        assertTrue("回执应含当前态,实际:${r.summary}", r.summary.contains("随机播放开"))
        assertTrue("回执应含循环模式,实际:${r.summary}", r.summary.contains("列表循环"))
    }
}

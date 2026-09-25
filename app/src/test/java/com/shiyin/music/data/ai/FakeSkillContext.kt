package com.shiyin.music.data.ai

import com.shiyin.music.data.Track

/**
 * v1.3.8: 技能单测共用的 SkillContext 空实现。
 *
 * [AgentEngine.SkillContext] 每加一个桥方法(每个新的副作用类别都会加),所有实现类都要补。
 * 分散在各个测试类里逐个补很容易漏——集中在这里,加桥方法时只需要改这一处,
 * 各测试类只覆写自己关心的方法。
 */
open class FakeSkillContext : AgentEngine.SkillContext {
    override val albumKey: String? = null
    override val artistKey: String? = null
    override fun currentTrack(): Track? = null
    override fun albumTracks(): List<Track> = emptyList()
    override fun albumTracksOf(key: String): List<Track> = emptyList()
    override fun findAlbum(query: String): String? = null
    override fun searchTracks(query: String, limit: Int): List<Track> = emptyList()
    override fun searchArtists(query: String, limit: Int): List<String> = emptyList()
    override fun applyAlbumOrderText(key: String, text: String) = Unit
    override fun applyAlbumOrderIndices(key: String, indices: List<Int>) = Unit
    override fun saveTrackInfo(mediaId: Long, title: String, artist: String) = Unit
    override fun hasPendingOrder(): Boolean = false
    override fun setSleepTimer(minutes: Int) = Unit
    override fun setSleepTimerEndOfTrack() = Unit
    override fun sleepTimerStatus(): String = "未开启"
    override fun playTracks(tracks: List<Track>, startId: Long) = Unit
    override fun pausePlayback() = Unit
    override fun resumePlayback() = Unit
    override fun nextTrack() = Unit
    override fun previousTrack() = Unit
    override fun isPlaying(): Boolean = false
    override fun setShuffle(on: Boolean) = Unit
    override fun isShuffleOn(): Boolean = false
    override fun setRepeatMode(mode: AgentEngine.RepeatMode) = Unit
    override fun repeatMode(): AgentEngine.RepeatMode = AgentEngine.RepeatMode.OFF
    override fun setPlaybackSpeed(speed: Float) = Unit
    override suspend fun weeklyListeningStats(): com.shiyin.music.data.stats.WeeklyStats =
        com.shiyin.music.data.stats.WeeklyStats()
    override fun trackById(id: Long): Track? = null
    override suspend fun sortArtistAlbumsByDate(artistName: String): AgentEngine.AlbumDateOutcome =
        AgentEngine.AlbumDateOutcome(0, "fake")
    override fun logUsage(promptTokens: Int, completionTokens: Int, cacheTokens: Int) = Unit
}

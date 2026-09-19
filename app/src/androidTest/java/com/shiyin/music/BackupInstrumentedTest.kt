package com.shiyin.music

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shiyin.music.data.db.AppDatabase
import com.shiyin.music.data.db.BackupManager
import com.shiyin.music.data.db.AlbumInfoOverrideEntity
import com.shiyin.music.data.db.TrackInfoOverrideEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.2.0 阶段三：在模拟器/真机上、走 release(R8) 变体验证修正数据导出/导入。
 *
 * 流程：插测试修正 → export 成 JSON → clearAllTables 模拟数据丢失 → import 恢复 →
 * 验证修正回来。覆盖 album_info_override / track_info_override / artist 等。
 */
@RunWith(AndroidJUnit4::class)
class BackupInstrumentedTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = AppDatabase.get(ctx)
    private val dao = db.dao()
    private val mgr = BackupManager(dao)

    @Before
    fun setup() = runBlocking {
        db.clearAllTables()
        // 模拟用户修正：改专辑名、改曲目名、合并歌手别名
        dao.upsertAlbumInfoOverride(AlbumInfoOverrideEntity(albumId = 99999L, albumName = "测试专辑名", artistName = "测试艺术家"))
        dao.upsertTrackInfoOverride(TrackInfoOverrideEntity(mediaId = 88888L, title = "我的曲目", artist = "改过的艺术家", note = "测试备注"))
        dao.upsertArtist(com.shiyin.music.data.db.ArtistEntity(name = "测试歌手", aliases = "别名1,别名2"))
    }

    @Test
    fun exportImportRoundTrip_restoresOverrides() = runBlocking {
        // 1) 导出
        val json = mgr.export()
        assertTrue("export JSON 非空", json.isNotEmpty())
        assertTrue("export 含专辑名修正", json.contains("测试专辑名"))
        assertTrue("export 含曲目名修正", json.contains("我的曲目"))
        assertTrue("export 含歌手合并", json.contains("测试歌手"))

        // 2) 模拟数据丢失（卸载/重装/清数据）
        db.clearAllTables()
        // 确认已清空
        assertTrue("清表后专辑修正应空", dao.albumInfoOverride(99999L) == null)

        // 3) 导入恢复
        val stats = mgr.import(json)
        assertTrue("import 统计非空", stats.toString().isNotEmpty())

        // 4) 验证修正回来
        val album = dao.albumInfoOverride(99999L)
        assertTrue("专辑名修正恢复: ${album?.albumName}", album?.albumName == "测试专辑名")
        val track = dao.trackInfoOverride(88888L)
        assertTrue("曲目名修正恢复: ${track?.title}", track?.title == "我的曲目")
        val artist = dao.artistByName("测试歌手")
        assertTrue("歌手合并恢复: ${artist?.aliases}", artist?.aliases?.contains("别名1") == true)
    }

    /**
     * v1.3.x 需求6:译文随备份走 —— 走**真实 DAO + export/import 全程**验证。
     *
     * 重点盯 `payload`：它是**嵌套的 JSON 字符串**（`{"0":"…"}`），
     * 在 BackupData 里又是一层 JSON 的字段值，**两层转义必须都原样保住**——
     * 丢一层，恢复时 Gson 解析失败，整批译文静默消失（用户看到"翻译没保存"）。
     * 单测只验了 Gson 数据形状，这里验的是"真的存进库、清空、再导回来"。
     */
    @Test
    fun exportImportRoundTrip_restoresLyricsTranslation() = runBlocking {
        val payload = """{"0":"第一行","1":"含\"引号\"的行","5":"第六行"}"""
        dao.upsertLyricsTranslation(
            com.shiyin.music.data.db.LyricsTranslationEntity(
                mediaId = 77777L,
                lyricsHash = "testhash12345678",
                payload = payload,
                source = "ai",
                translatedAt = 1_700_000_000_000L,
            ),
        )

        // 1) 导出
        val json = mgr.export()
        assertTrue("export 应含译文行", json.contains("testhash12345678"))

        // 2) 模拟数据丢失
        db.clearAllTables()
        assertTrue("清表后译文应空", dao.lyricsTranslation(77777L, "testhash12345678") == null)

        // 3) 导入恢复
        mgr.import(json)

        // 4) 逐字段核对（payload 转义是重点）
        val back = dao.lyricsTranslation(77777L, "testhash12345678")
        assertTrue("译文行应恢复", back != null)
        assertTrue("payload 转义必须原样保住: ${back?.payload}", back?.payload == payload)
        assertTrue("mediaId 应恢复", back?.mediaId == 77777L)
        assertTrue("source 应恢复: ${back?.source}", back?.source == "ai")
        assertTrue("translatedAt 应恢复", back?.translatedAt == 1_700_000_000_000L)
    }
}

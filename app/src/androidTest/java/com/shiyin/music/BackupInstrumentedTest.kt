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
}

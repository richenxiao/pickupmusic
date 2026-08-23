package com.shiyin.music.data.db

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.2.0 #6: 验证 BackupManager.BackupData 的 JSON 往返——新加的 artist_image_cache /
 * artist_image_override 字段（含 width/height/aspectRatio/imageType 元数据）能正确
 * 序列化/反序列化，且旧备份（缺这两个字段）反序列化得 null（import 的 orEmpty 兜底不崩）。
 *
 * 只测 Gson 数据形状（最易静默坏的部分）；DAO 写入走与既有表同样的 @Insert REPLACE，
 * 真机导出/导入时验证。
 */
class BackupDataRoundTripTest {

    private val fullJson = """
        {"version":1,"app":"com.shiyin.music","exportedAt":123,
         "albumOverride":[],"albumInfoOverride":[],"trackInfoOverride":[],"trackAlbumMove":[],
         "artists":[],"songArtist":[],"readingOverride":[],"externalReadingEvidence":[],
         "artistImageCache":[{"name":"Adele","url":"https://i.discogs.com/x.jpg","source":"discogs",
           "fetchedAt":10,"failUntilTs":0,"width":600,"height":399,"aspectRatio":1.50,"imageType":"photo"}],
         "artistImageOverride":[{"name":"Adele","url":"https://override/x.jpg","chosenAt":20}]}
    """.trimIndent()

    private val oldJson = """
        {"version":1,"app":"com.shiyin.music","exportedAt":123,
         "albumOverride":[],"albumInfoOverride":[],"trackInfoOverride":[],"trackAlbumMove":[],
         "artists":[],"songArtist":[],"readingOverride":[],"externalReadingEvidence":[]}
    """.trimIndent()

    @Test fun imageFields_surviveRoundTrip() {
        val back = Gson().fromJson(fullJson, BackupManager.BackupData::class.java)
        assertEquals(1, back.artistImageCache!!.size)
        val c = back.artistImageCache!![0]
        assertEquals("discogs", c.source)
        assertEquals(600, c.width)
        assertEquals(399, c.height)
        assertEquals(1.5f, c.aspectRatio, 0.001f)
        assertEquals("photo", c.imageType)
        assertEquals(1, back.artistImageOverride!!.size)
        assertEquals("https://override/x.jpg", back.artistImageOverride!![0].url)
        // 再序列化→反序列化一次，确认二次往返不丢
        val back2 = Gson().fromJson(Gson().toJson(back), BackupManager.BackupData::class.java)
        assertEquals("discogs", back2.artistImageCache!![0].source)
        assertEquals(1.5f, back2.artistImageCache!![0].aspectRatio, 0.001f)
    }

    @Test fun oldBackup_missingImageFields_isNullAndOrEmptySafe() {
        val back = Gson().fromJson(oldJson, BackupManager.BackupData::class.java)
        // 旧备份无 image 字段 → Gson 得 null
        assertNull(back.artistImageCache)
        assertNull(back.artistImageOverride)
        // import 的 orEmpty() 兜底：null → 空 list，不崩
        assertEquals(0, back.artistImageCache.orEmpty().size)
        assertEquals(0, back.artistImageOverride.orEmpty().size)
    }
}

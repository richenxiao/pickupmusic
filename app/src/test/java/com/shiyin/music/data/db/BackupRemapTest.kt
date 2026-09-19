package com.shiyin.music.data.db

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v22: 备份导入时 id 重解析的单测 —— **这是跨设备可用的地基**。
 *
 * 关键性质（错了都会静默毁数据）：
 *  1. mediaId / albumId 必须按"路径 / 名字"重新映射到新设备的 id
 *  2. **映射不到的行必须丢弃并计数** —— 宁可不恢复，也不能挂到错误的歌上
 *  3. 复合键里的 id（`aid:NNN`、逗号分隔的 id 列表）也要替换，不能只改字段
 *  4. 没有重解析表的 v1 老备份必须**原样通过**（行为与旧版一致）
 *  5. `sourceMediaId` 映射不到**不能丢行**（它有 sourcePath 兜底）
 */
class BackupRemapTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    /** 一个最小的 v2 备份：两条曲目记录 + 一条找不到文件的记录。 */
    private fun v2Backup(): String = """
        {
          "version": 2,
          "exportedAt": 1,
          "mediaIdPaths": {"101":"/m/a.flac","102":"/m/b.flac","103":"/m/gone.flac"},
          "albumIdKeys": {"201":"专辑A|歌手X","202":"专辑B|歌手Y"},
          "trackInfoOverride": [
            {"mediaId":101,"title":"改过的A"},
            {"mediaId":102,"title":"改过的B"},
            {"mediaId":103,"title":"文件没了"}
          ],
          "albumInfoOverride": [
            {"albumId":201,"albumName":"专辑A"},
            {"albumId":202,"albumName":"专辑B"}
          ],
          "artists": [{"id":1,"name":"歌手X","aliases":""}]
        }
    """.trimIndent()

    private val pathToId = mapOf("/m/a.flac" to 9001L, "/m/b.flac" to 9002L) // gone.flac 不在
    private val nameToAlbum = mapOf("专辑A|歌手X" to 7001L, "专辑B|歌手Y" to 7002L)

    // ── 1/2. 曲目与专辑重映射 + 丢行 ────────────────────────────────────────
    @Test fun remapsMediaIdAndDropsMissing() {
        val r = BackupRemap.apply(v2Backup(), pathToId, nameToAlbum)
        assertFalse(r.skipped)
        val root = obj(r.json)
        val tio = root.getAsJsonArray("trackInfoOverride")
        assertEquals("文件没了的记录应被丢弃", 2, tio.size())
        assertEquals(9001L, tio[0].asJsonObject.get("mediaId").asLong)
        assertEquals(9002L, tio[1].asJsonObject.get("mediaId").asLong)
        assertEquals(2, r.tracksRemapped)
        assertEquals(1, r.tracksDropped)
        assertTrue(r.hasLoss)
    }

    @Test fun remapsAlbumId() {
        val r = BackupRemap.apply(v2Backup(), pathToId, nameToAlbum)
        val aio = obj(r.json).getAsJsonArray("albumInfoOverride")
        assertEquals(2, aio.size())
        assertEquals(7001L, aio[0].asJsonObject.get("albumId").asLong)
        assertEquals(7002L, aio[1].asJsonObject.get("albumId").asLong)
        assertEquals(2, r.albumsRemapped)
        assertEquals(0, r.albumsDropped)
    }

    /** 没有 id 的表（歌手、设置等）必须原样保留。 */
    @Test fun tablesWithoutIdsUntouched() {
        val r = BackupRemap.apply(v2Backup(), pathToId, nameToAlbum)
        val artists = obj(r.json).getAsJsonArray("artists")
        assertEquals(1, artists.size())
        assertEquals("歌手X", artists[0].asJsonObject.get("name").asString)
    }

    /** 专辑找不到（新设备上没这张专辑）→ 丢行并计数。 */
    @Test fun dropsAlbumWhenNotFound() {
        val r = BackupRemap.apply(v2Backup(), pathToId, mapOf("专辑A|歌手X" to 7001L)) // 缺 专辑B
        val aio = obj(r.json).getAsJsonArray("albumInfoOverride")
        assertEquals(1, aio.size())
        assertEquals(1, r.albumsDropped)
    }

    // ── 3. 复合键 ──────────────────────────────────────────────────────────
    /** `album_order.albumKey` 是主键且形如 `aid:NNN` → 必须换成新 id。 */
    @Test fun remapsAidFormAlbumKey() {
        val json = """
            {"version":2,
             "mediaIdPaths":{},
             "albumIdKeys":{"201":"专辑A|歌手X"},
             "albumOrder":[{"albumKey":"aid:201","orderedIds":"101,102"}]}
        """.trimIndent()
        val r = BackupRemap.apply(json, mapOf("/m/a.flac" to 9001L, "/m/b.flac" to 9002L), mapOf("专辑A|歌手X" to 7001L))
        val row = obj(r.json).getAsJsonArray("albumOrder")[0].asJsonObject
        assertEquals("aid:7001", row.get("albumKey").asString)
    }

    /** 名字形式的专辑键与设备无关，必须原样保留。 */
    @Test fun keepsNameFormAlbumKey() {
        val json = """
            {"version":2,
             "mediaIdPaths":{},
             "albumIdKeys":{},
             "albumOrder":[{"albumKey":"某专辑|某人","orderedIds":""}]}
        """.trimIndent()
        val r = BackupRemap.apply(json, emptyMap(), emptyMap())
        val row = obj(r.json).getAsJsonArray("albumOrder")[0].asJsonObject
        assertEquals("某专辑|某人", row.get("albumKey").asString)
        assertEquals(0, r.albumsDropped)
    }

    /** `orderedIds` 是逗号分隔的 mediaId 列表：逐个替换，找不到的丢掉。 */
    @Test fun remapsOrderedIdList() {
        val json = """
            {"version":2,
             "mediaIdPaths":{"101":"/m/a.flac","102":"/m/b.flac","103":"/m/gone.flac"},
             "albumIdKeys":{},
             "albumOrder":[{"albumKey":"某专辑|某人","orderedIds":"101,103,102"}]}
        """.trimIndent()
        val r = BackupRemap.apply(json, mapOf("/m/a.flac" to 9001L, "/m/b.flac" to 9002L), emptyMap())
        val row = obj(r.json).getAsJsonArray("albumOrder")[0].asJsonObject
        assertEquals("103 找不到 → 丢掉，顺序保持", "9001,9002", row.get("orderedIds").asString)
        assertEquals(2, r.tracksRemapped)
        assertEquals(1, r.tracksDropped)
    }

    /** `artist_album_order.albumKeys` 同样是逗号分隔的专辑键列表。 */
    @Test fun remapsAlbumKeyList() {
        val json = """
            {"version":2,
             "mediaIdPaths":{},
             "albumIdKeys":{"201":"专辑A|歌手X","202":"专辑B|歌手Y"},
             "artistAlbumOrder":[{"artistName":"歌手X","albumKeys":"aid:201,aid:202,某专辑|某人"}]}
        """.trimIndent()
        val r = BackupRemap.apply(json, emptyMap(), mapOf("专辑A|歌手X" to 7001L, "专辑B|歌手Y" to 7002L))
        val row = obj(r.json).getAsJsonArray("artistAlbumOrder")[0].asJsonObject
        assertEquals("aid:7001,aid:7002,某专辑|某人", row.get("albumKeys").asString)
    }

    /** 专辑键映射不到 → `album_order` 整行丢弃（主键就是它，留着等于挂错专辑）。 */
    @Test fun dropsRowWhenAlbumKeyUnresolvable() {
        val json = """
            {"version":2,
             "mediaIdPaths":{},
             "albumIdKeys":{"201":"专辑A|歌手X"},
             "albumOrder":[{"albumKey":"aid:201","orderedIds":"1"}]}
        """.trimIndent()
        val r = BackupRemap.apply(json, emptyMap(), emptyMap()) // 专辑查不到
        assertEquals(0, obj(r.json).getAsJsonArray("albumOrder").size())
        assertEquals(1, r.albumsDropped)
    }

    // ── 5. sourceMediaId 特例 ─────────────────────────────────────────────
    /** 整轨拆分方案：sourceMediaId 映射不到**不能丢行**（lib() 会用 sourcePath 兜底）。 */
    @Test fun sourceMediaIdRemappedButRowKeptWhenMissing() {
        val json = """
            {"version":2,
             "mediaIdPaths":{"101":"/m/album.flac"},
             "albumIdKeys":{},
             "albumSplit":[{"sourceMediaId":101,"sourcePath":"/m/album.flac","status":"done"},
                           {"sourceMediaId":999,"sourcePath":"/m/other.flac","status":"done"}],
             "splitTrack":[{"id":1,"sourceMediaId":101,"ordinal":1,"title":"第一首","startMs":0}]}
        """.trimIndent()
        val r = BackupRemap.apply(json, mapOf("/m/album.flac" to 9001L), emptyMap())
        val root = obj(r.json)
        val splits = root.getAsJsonArray("albumSplit")
        assertEquals("映射不到也不能丢行（有 sourcePath 兜底）", 2, splits.size())
        assertEquals(9001L, splits[0].asJsonObject.get("sourceMediaId").asLong)
        assertEquals(999L, splits[1].asJsonObject.get("sourceMediaId").asLong)
        assertEquals(9001L, root.getAsJsonArray("splitTrack")[0].asJsonObject.get("sourceMediaId").asLong)
        assertEquals(0, r.tracksDropped)
    }

    // ── 4. 向后兼容 ────────────────────────────────────────────────────────
    /** v1 老备份没有重解析表 → 原样返回，id 不动（老备份都是同机导出，id 本来就对）。 */
    @Test fun v1BackupPassesThroughUntouched() {
        val v1 = """{"version":1,"exportedAt":1,"trackInfoOverride":[{"mediaId":101,"title":"x"}]}"""
        val r = BackupRemap.apply(v1, pathToId, nameToAlbum)
        assertTrue(r.skipped)
        assertEquals(101L, obj(r.json).getAsJsonArray("trackInfoOverride")[0].asJsonObject.get("mediaId").asLong)
        assertEquals(0, r.tracksDropped)
        assertFalse(r.hasLoss)
    }

    /** 重解析表存在但为空 → 仍然算"做了重解析"，所有带 id 的行都会被丢掉（如实报告）。 */
    @Test fun emptyRemapTablesDropAllIdRows() {
        val json = """{"version":2,"mediaIdPaths":{},"albumIdKeys":{},
            "trackInfoOverride":[{"mediaId":101,"title":"x"}],"artists":[{"id":1,"name":"n"}]}"""
        val r = BackupRemap.apply(json, pathToId, nameToAlbum)
        assertFalse(r.skipped)
        assertEquals(0, obj(r.json).getAsJsonArray("trackInfoOverride").size())
        assertEquals(1, r.tracksDropped)
        assertEquals("没有 id 的表不受影响", 1, obj(r.json).getAsJsonArray("artists").size())
    }

    @Test fun garbageInputIsSkipped() {
        val r = BackupRemap.apply("not json", pathToId, nameToAlbum)
        assertTrue(r.skipped)
        assertEquals("not json", r.json)
    }

    /** `albumId = 0` 表示"无专辑"，不该被当成"找不到"而丢行。 */
    @Test fun zeroAlbumIdIsKept() {
        val json = """{"version":2,"mediaIdPaths":{"101":"/m/a.flac"},"albumIdKeys":{},
            "trackAlbumMove":[{"mediaId":101,"albumId":0}]}"""
        val r = BackupRemap.apply(json, mapOf("/m/a.flac" to 9001L), emptyMap())
        assertEquals(1, obj(r.json).getAsJsonArray("trackAlbumMove").size())
        assertEquals(0, r.albumsDropped)
    }
}

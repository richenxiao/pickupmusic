package com.shiyin.music.data.db

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * v22: 备份导入时的 **id 重解析**（纯 JSON 变换，可单测，不需要 Android）。
 *
 * === 为什么必须有这一步 ===
 * 备份里大量记录按 MediaStore 的 `_ID`（mediaId）或专辑 `albumId` 存，而这两个 id 都是
 * **设备本地的自增主键**：换机 / 恢复出厂 / 清空媒体库重扫之后，同一个文件会拿到完全
 * 不同的 id。不做重解析的话，恢复出来的修正会**全部挂到错误的歌上**（或变成孤儿）。
 *
 * === 做法：导出时附带两张"身份证"表 ===
 *  - `mediaIdPaths`：`mediaId → 文件绝对路径`（与设备无关的标识）
 *  - `albumIdKeys`：`albumId → "专辑名|歌手"`（**刻意存名字形式而不是 `aid:NNN`**，
 *    因为 aid 形式本身就含 id，换机后同样失效）
 *
 * 导入时用**当前库**反查（`路径 → 新 mediaId`、`名字键 → 新 albumId`），
 * 得到 `旧id → 新id` 两张映射，然后：
 *  - 所有对象里的 `mediaId` / `albumId` 字段就地改写
 *  - **映射不到的行直接丢弃**并在结果里计数（如实报告，不静默错挂）
 *  - `sourceMediaId`（整轨拆分）映射不到时**不丢行** —— 它有 `sourcePath` 兜底
 *  - 复合键里的 id 也要替换：
 *    · `album_order.albumKey`：`"aid:123"` → `"aid:<新id>"`；名字形式原样保留
 *    · `album_order.orderedIds`：逗号分隔的 mediaId 列表，逐个替换、丢掉映射不到的
 *    · `artist_album_order.albumKeys`：逗号分隔的专辑键，逐个替换
 *
 * === 向后兼容 ===
 * v1 老备份没有这两张表 → [Result.skipped] 为 true，**原样返回、行为与旧版完全一致**
 * （老备份都是同机导出，id 本来就对得上）。
 */
object BackupRemap {

    const val KEY_MEDIA_PATHS = "mediaIdPaths"
    const val KEY_ALBUM_KEYS = "albumIdKeys"

    /** `albumKeyOf` 里 aid 形式的前缀。 */
    private const val AID_PREFIX = "aid:"

    data class Result(
        val json: String,
        /** 成功改写的曲目引用数。 */
        val tracksRemapped: Int = 0,
        /** 因为找不到对应文件而丢弃的行数。 */
        val tracksDropped: Int = 0,
        /** 成功改写的专辑引用数。 */
        val albumsRemapped: Int = 0,
        /** 因为找不到对应专辑而丢弃的行数。 */
        val albumsDropped: Int = 0,
        /** 备份里没有重解析表（v1 老备份）→ 原样返回。 */
        val skipped: Boolean = false,
    ) {
        val hasLoss: Boolean get() = tracksDropped > 0 || albumsDropped > 0
    }

    /**
     * @param pathToMediaId 当前库：文件绝对路径 → mediaId
     * @param nameKeyToAlbumId 当前库：`"专辑名|歌手"` → albumId
     */
    fun apply(
        rawJson: String,
        pathToMediaId: Map<String, Long>,
        nameKeyToAlbumId: Map<String, Long>,
    ): Result {
        val root = try {
            JsonParser.parseString(rawJson).asJsonObject
        } catch (_: Exception) {
            return Result(rawJson, skipped = true)
        }
        val paths = root.getAsJsonObject(KEY_MEDIA_PATHS)
        val albumKeys = root.getAsJsonObject(KEY_ALBUM_KEYS)
        if (paths == null && albumKeys == null) return Result(rawJson, skipped = true)

        val mediaMap = HashMap<Long, Long>()
        paths?.entrySet()?.forEach { (k, v) ->
            val old = k.toLongOrNull() ?: return@forEach
            val path = v.asStringOrNull() ?: return@forEach
            pathToMediaId[path]?.let { mediaMap[old] = it }
        }
        val albumMap = HashMap<Long, Long>()
        albumKeys?.entrySet()?.forEach { (k, v) ->
            val old = k.toLongOrNull() ?: return@forEach
            val key = v.asStringOrNull() ?: return@forEach
            nameKeyToAlbumId[key]?.let { albumMap[old] = it }
        }

        var tracksRemapped = 0
        var tracksDropped = 0
        var albumsRemapped = 0
        var albumsDropped = 0

        // 先收集 key 再改值：避免在 entrySet 迭代中 add 触发 ConcurrentModification
        for (name in root.keySet().toList()) {
            if (name == KEY_MEDIA_PATHS || name == KEY_ALBUM_KEYS) continue
            val arr = root.get(name) as? JsonArray ?: continue
            val keep = JsonArray()
            for (item in arr) {
                val obj = item as? JsonObject
                if (obj == null) {
                    keep.add(item)
                    continue
                }
                var drop = false

                // ── mediaId 字段 ──
                val mediaEl = obj.get("mediaId")?.takeIf { !it.isJsonNull }
                if (mediaEl != null) {
                    val nw = mediaMap[mediaEl.asLong]
                    if (nw == null) {
                        drop = true
                        tracksDropped++
                    } else {
                        obj.addProperty("mediaId", nw)
                        tracksRemapped++
                    }
                }

                // ── albumId 字段（0/负值表示"无专辑"，原样保留）──
                if (!drop) {
                    val albumEl = obj.get("albumId")?.takeIf { !it.isJsonNull }
                    if (albumEl != null && albumEl.asLong > 0) {
                        val nw = albumMap[albumEl.asLong]
                        if (nw == null) {
                            drop = true
                            albumsDropped++
                        } else {
                            obj.addProperty("albumId", nw)
                            albumsRemapped++
                        }
                    }
                }

                // ── sourceMediaId：整轨拆分用。映射不到**不丢行**（sourcePath 兜底）──
                if (!drop) {
                    val srcEl = obj.get("sourceMediaId")?.takeIf { !it.isJsonNull }
                    if (srcEl != null) {
                        mediaMap[srcEl.asLong]?.let { obj.addProperty("sourceMediaId", it) }
                    }
                }

                // ── album_order：albumKey 是主键，orderedIds 是 mediaId 列表 ──
                if (!drop && obj.has("albumKey")) {
                    val key = obj.get("albumKey").asStringOrNull()
                    val nk = key?.let { remapAlbumKey(it, albumMap) }
                    if (nk == null) {
                        drop = true
                        albumsDropped++
                    } else {
                        obj.addProperty("albumKey", nk)
                        albumsRemapped++
                    }
                }
                if (!drop && obj.has("orderedIds")) {
                    val ids = obj.get("orderedIds").asStringOrNull().orEmpty()
                    val r = remapIdList(ids, mediaMap)
                    obj.addProperty("orderedIds", r.kept)
                    tracksRemapped += r.mapped
                    tracksDropped += r.lost
                }

                // ── artist_album_order：albumKeys 是逗号分隔的专辑键列表 ──
                if (!drop && obj.has("albumKeys")) {
                    val keys = obj.get("albumKeys").asStringOrNull().orEmpty()
                    val r = remapAlbumKeyList(keys, albumMap)
                    obj.addProperty("albumKeys", r.kept)
                    albumsRemapped += r.mapped
                    albumsDropped += r.lost
                }

                if (!drop) keep.add(obj)
            }
            root.add(name, keep)
        }

        return Result(
            json = root.toString(),
            tracksRemapped = tracksRemapped,
            tracksDropped = tracksDropped,
            albumsRemapped = albumsRemapped,
            albumsDropped = albumsDropped,
        )
    }

    /**
     * 单个专辑键：`aid:NNN` 需要换成新 id；`专辑名|歌手` 形式与设备无关，原样保留。
     * 返回 null 表示**这个键在当前库里找不到**（调用方据此丢行）。
     */
    private fun remapAlbumKey(key: String, albumMap: Map<Long, Long>): String? {
        if (!key.startsWith(AID_PREFIX)) return key          // 名字形式：与设备无关
        val old = key.removePrefix(AID_PREFIX).toLongOrNull() ?: return key
        val nw = albumMap[old] ?: return null
        return "$AID_PREFIX$nw"
    }

    /** 列表重映射结果：[kept] 新串，[mapped] 成功替换个数，[lost] 丢弃个数。 */
    private data class ListRemap(val kept: String, val mapped: Int, val lost: Int)

    /** 逗号分隔的 id 列表：逐个替换，映射不到的丢掉。 */
    private fun remapIdList(s: String, map: Map<Long, Long>): ListRemap {
        if (s.isBlank()) return ListRemap("", 0, 0)
        var lost = 0
        val out = ArrayList<String>()
        for (part in s.split(',')) {
            val id = part.trim().toLongOrNull() ?: continue
            val nw = map[id]
            if (nw == null) lost++ else out += nw.toString()
        }
        return ListRemap(out.joinToString(","), out.size, lost)
    }

    /** 逗号分隔的专辑键列表：aid 形式替换，名字形式保留，找不到的丢掉。 */
    private fun remapAlbumKeyList(s: String, map: Map<Long, Long>): ListRemap {
        if (s.isBlank()) return ListRemap("", 0, 0)
        var lost = 0
        val out = ArrayList<String>()
        for (part in s.split(',')) {
            val k = part.trim()
            if (k.isEmpty()) continue
            val nk = remapAlbumKey(k, map)
            if (nk == null) lost++ else out += nk
        }
        return ListRemap(out.joinToString(","), out.size, lost)
    }

    private fun com.google.gson.JsonElement.asStringOrNull(): String? =
        try {
            if (isJsonNull) null else asString
        } catch (_: Exception) {
            null
        }
}

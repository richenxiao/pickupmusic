package com.shiyin.music.data.karaoke

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile

/**
 * v1.5.0 P1：**伴奏块缓存**（规格 P1 修正 一）。
 *
 * 位置（用户指定）：`filesDir/pickup/accompaniment-cache/`
 *   `<songId>/<modelId>/<modelVersion>-e<engineVersion>-<指纹>/`
 *     ├── c00000.pcm …（**原始 int16 交错立体声**，一块一文件）
 *     └── manifest.json（genSize / 总块数 / 采样率 / complete / lastAccess）
 *
 * 为什么**不用 .wav/.mp3 等音频扩展名**：避免被系统或别的 app 当成音乐收录（用户要求）。
 *
 * 三条硬约束（用户要求）：
 *  1. **原子写入**：先写 `cXXXXX.tmp` 再 rename —— rename 是原子的，所以
 *     "块文件存在"就等于"该块完整"，崩溃/强杀后**不可能读到半写块**（.tmp 一律不读）。
 *     **块的完成状态就由文件存在性承载**，不再单独维护位图（少一处可不同步的状态）。
 *  2. **按帧随机读取**：固定 genSize，帧号 → 块号 + 块内偏移直接 seek。
 *  3. **可重试**：缺块时 [readFrames] 返回 null，调用方重算缺的部分即可。
 *
 * manifest 用 Gson 的 JsonObject + **字面键名**读写（不用反射绑定），与项目既有约定一致
 * （R8 下 toJson 反射绑定会丢泛型/字段名）。
 */
class StemCache(private val root: File) {

    /** 缓存根目录（取证/统计用；`root` 本身是私有的，不要外泄可变引用）。 */
    val rootDir: File get() = root

    companion object {
        /**
         * 引擎版本：算法/参数变化时 +1，让旧缓存自然失效。
         *
         * **2（2026-10-16，P0-1 升调）**：修掉了"源帧数被当成 44.1k 模型域帧数"的口径错误。
         * 1 版本缓存里的伴奏对**非 44.1k 的源**是**错的**（按 源率/44100 倍速），必须整体作废。
         */
        const val ENGINE_VERSION = 2

        fun defaultRoot(filesDir: File): StemCache =
            StemCache(File(filesDir, "pickup/accompaniment-cache"))
    }

    data class Manifest(
        val modelId: String,
        val modelVersion: String,
        val fingerprint: String,
        val genSize: Int,
        /** **缓存内容自己的采样率** —— 恒为模型域 44100（分离引擎只在 44.1k 里工作）。 */
        val sampleRate: Int,
        val channels: Int,
        val totalChunks: Int,
        val complete: Boolean,
        val lastAccess: Long,
        /**
         * **源文件**的采样率与帧数（0 = 老缓存没写，按"与 [sampleRate] 相同"处理）。
         * 播放按源帧号取伴奏，所以要靠这两个字段把源帧号换算到缓存（模型）帧号。
         */
        val srcRate: Int = 0,
        val srcFrames: Int = 0,
    )

    /**
     * 一份产出（用于保留策略 / 占用统计）。
     * `keyDirName` = [SourceKey.dirName]（物理文件维度；不再是 songId）。
     */
    data class CachedSong(val keyDirName: String, val bytes: Long, val lastAccess: Long, val complete: Boolean)

    /**
     * **缓存身份：以物理文件为准**（v1.5.0 块 2，去掉 songId 那一层）。
     *
     * 为什么必须去掉：拆分单曲是**同一个整轨文件**被切成多段、每段一个 songId，
     * 但它们要的是**同一份**分离产出 —— 按 songId 会重复算 N 遍，而且拖动跨段时
     * 也认不出"这是同一个文件的同一块"。
     *
     * 目录名 = 文件指纹 + 采样率 + 声道数 + 总帧数：**四项任一变化都必须重算**
     * （换文件、重采样、立体声↔单声道、文件被裁剪/替换）。
     */
    data class SourceKey(
        val fingerprint: String,
        val sampleRate: Int,
        val channels: Int,
        val totalFrames: Int,
    ) {
        /** 目录名（文件系统安全、可读；保留策略与统计都按它认一份产出）。 */
        val dirName: String get() = "$fingerprint-r$sampleRate-c$channels-n$totalFrames"
    }

    fun sourceDir(key: SourceKey): File = File(root, key.dirName)

    fun modelDir(key: SourceKey, modelId: String): File = File(sourceDir(key), modelId)

    /** 变体目录只带模型版本 + 引擎版本 —— **指纹已在 [SourceKey.dirName] 里**，不再重复。 */
    fun variantDir(
        key: SourceKey, modelId: String, modelVersion: String,
        engineVersion: Int = ENGINE_VERSION,
    ): File = File(modelDir(key, modelId), "$modelVersion-e$engineVersion")

    private fun chunkFile(dir: File, index: Int) = File(dir, "c%05d.pcm".format(index))

    private fun chunkTemp(dir: File, index: Int) = File(dir, "c%05d.tmp".format(index))

    // ── 读 ────────────────────────────────────────────────────────────────

    fun readManifest(dir: File): Manifest? {
        val f = File(dir, "manifest.json")
        if (!f.isFile) return null
        return runCatching {
            val o = JsonParser.parseString(f.readText()).asJsonObject
            Manifest(
                modelId = o.get("modelId").asString,
                modelVersion = o.get("modelVersion").asString,
                fingerprint = o.get("fingerprint").asString,
                genSize = o.get("genSize").asInt,
                sampleRate = o.get("sampleRate").asInt,
                channels = o.get("channels").asInt,
                totalChunks = o.get("totalChunks").asInt,
                complete = o.get("complete").asBoolean,
                lastAccess = o.get("lastAccess").asLong,
                // 老缓存（e1）没有这两个字段：0 表示"与缓存采样率相同"（那时 44.1k 源确实同域）
                srcRate = o.get("srcRate")?.asInt ?: 0,
                srcFrames = o.get("srcFrames")?.asInt ?: 0,
            )
        }.getOrNull()
    }

    /** 可直接使用的缓存目录（manifest 完整且标记 complete）——命中判定。 */
    fun findComplete(key: SourceKey, modelId: String, modelVersion: String): File? {
        val dir = variantDir(key, modelId, modelVersion)
        val m = readManifest(dir) ?: return null
        return if (m.complete && m.totalChunks > 0) dir else null
    }

    fun hasChunk(dir: File, index: Int): Boolean = chunkFile(dir, index).isFile

    /** 已完成块数（按文件存在性统计，无需位图）。 */
    fun doneChunks(dir: File): Int {
        val files = dir.listFiles() ?: return 0
        return files.count { it.isFile && it.name.startsWith("c") && it.name.endsWith(".pcm") }
    }

    /** 首个未完成的块号；全部完成返回 -1。 */
    fun firstMissingChunk(dir: File, totalChunks: Int): Int {
        for (i in 0 until totalChunks) if (!hasChunk(dir, i)) return i
        return -1
    }

    /**
     * 按帧随机读取交错 PCM。任何一块缺失即返回 null（调用方按"未就绪"处理，
     * 或退化成"只算缺失块"的重试）。
     */
    fun readFrames(dir: File, startFrame: Long, frames: Int): ShortArray? {
        val m = readManifest(dir) ?: return null
        if (m.genSize <= 0 || m.channels != 2 || frames <= 0 || startFrame < 0) return null
        val out = ShortArray(frames * 2)
        var frame = startFrame
        var written = 0
        while (written < frames) {
            val chunkIdx = (frame / m.genSize).toInt()
            if (!hasChunk(dir, chunkIdx)) return null
            val inChunk = (frame % m.genSize).toInt()
            val file = chunkFile(dir, chunkIdx)
            // ⚠️ **最后一块是短块**（总帧数不整除 genSize）——"块文件存在"不等于"块里有 genSize 帧"。
            // 按实际文件长度算这块还剩几帧，否则 readFully 会读过头抛 EOFException
            // （混音路径会把它 catch 成"退回透传"，于是**歌曲末尾几秒伴奏静默消失**）。
            val haveInChunk = (file.length() / 4).toInt()
            if (inChunk >= haveInChunk) return null      // 这一读服务不了 ⇒ 交由调用方当欠载处理
            val take = minOf(frames - written, haveInChunk - inChunk)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(inChunk.toLong() * 4)
                val bytes = ByteArray(take * 4)
                raf.readFully(bytes)
                var b = 0
                for (i in 0 until take) {
                    val l = (bytes[b].toInt() and 0xFF) or ((bytes[b + 1].toInt() and 0xFF) shl 8)
                    val r = (bytes[b + 2].toInt() and 0xFF) or ((bytes[b + 3].toInt() and 0xFF) shl 8)
                    out[(written + i) * 2] = l.toShort()
                    out[(written + i) * 2 + 1] = r.toShort()
                    b += 4
                }
            }
            frame += take
            written += take
        }
        return out
    }

    // ── 写 ────────────────────────────────────────────────────────────────

    /** 原子写一块（先 .tmp 再 rename）。 */
    fun writeChunk(dir: File, index: Int, samples: ShortArray) {
        dir.mkdirs()
        val tmp = chunkTemp(dir, index)
        val dst = chunkFile(dir, index)
        tmp.outputStream().buffered(1 shl 16).use { out ->
            val buf = ByteArray(samples.size * 2)
            var b = 0
            for (s in samples) {
                val v = s.toInt()
                buf[b++] = (v and 0xFF).toByte()
                buf[b++] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(buf)
        }
        if (dst.exists()) dst.delete()
        check(tmp.renameTo(dst)) { "缓存块重命名失败：${dst.path}" }
    }

    /** 全部块写完后标记 complete（manifest 本身也走 tmp+rename）。 */
    fun writeManifest(
        dir: File,
        modelId: String,
        modelVersion: String,
        fingerprint: String,
        genSize: Int,
        totalChunks: Int,
        complete: Boolean,
        sampleRate: Int = 44100,
        channels: Int = 2,
        lastAccess: Long,
        /** 源文件采样率/帧数（播放按源帧号取伴奏，靠这两个字段做域换算）。 */
        srcRate: Int = sampleRate,
        srcFrames: Int = 0,
    ) {
        dir.mkdirs()
        val o = JsonObject().apply {
            addProperty("schema", 1)
            addProperty("modelId", modelId)
            addProperty("modelVersion", modelVersion)
            addProperty("fingerprint", fingerprint)
            addProperty("engineVersion", ENGINE_VERSION)
            addProperty("genSize", genSize)
            addProperty("sampleRate", sampleRate)
            addProperty("channels", channels)
            addProperty("totalChunks", totalChunks)
            addProperty("complete", complete)
            addProperty("lastAccess", lastAccess)
            addProperty("srcRate", srcRate)
            addProperty("srcFrames", srcFrames)
        }
        val tmp = File(dir, "manifest.json.tmp")
        val dst = File(dir, "manifest.json")
        tmp.writeText(o.toString())
        if (dst.exists()) dst.delete()
        check(tmp.renameTo(dst)) { "manifest 重命名失败：${dst.path}" }
    }

    /** 访问时间（LRU 依据）。用目录 mtime，避免每次读都重写 manifest。 */
    fun touch(dir: File) {
        runCatching { dir.setLastModified(System.currentTimeMillis()) }
    }

    // ── 删 / 统计 ─────────────────────────────────────────────────────────

    /** 删一份产出（物理文件维度）。 */
    fun deleteSource(key: SourceKey): Boolean = sourceDir(key).deleteRecursively()

    fun deleteModel(key: SourceKey, modelId: String): Boolean = modelDir(key, modelId).deleteRecursively()

    /** 按**目录名**删（保留策略只拿到名字，不重建整条 key）。 */
    fun deleteByDirName(dirName: String): Boolean = File(root, dirName).deleteRecursively()

    /**
     * **清空全部伴奏缓存**（设置页的"删除缓存"）。
     *
     * 只删 `root` 下的一级目录（每个源文件一个），不动模型仓库 —— 那在另一个目录、另一个仓库里。
     * @return 删掉的一级目录数
     */
    fun deleteAll(): Int {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return 0
        var n = 0
        for (d in dirs) {
            if (d.deleteRecursively()) n++
        }
        return n
    }

    fun usageBytes(): Long {
        var total = 0L
        root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /** 全部缓存（按"歌 + 模型变体"聚合），供保留策略与设置页显示。 */
    fun cachedSongs(): List<CachedSong> {
        val songs = root.listFiles() ?: return emptyList()
        return songs.filter { it.isDirectory }.mapNotNull { sd ->
            // 身份就是**目录名**（物理文件维度）。不再按 Long 解析：块 2 起目录名是
            // `<指纹>-r<采样率>-c<声道>-n<帧数>`，旧的 songId 数字目录会被当作孤儿条目，
            // 由保留策略按 lastAccess 自然淘汰。
            val keyDirName = sd.name
            var bytes = 0L
            var last = 0L
            var anyComplete = false
            var hasVariant = false
            sd.walkTopDown().forEach { f ->
                if (f.isFile) bytes += f.length()
            }
            sd.listFiles()?.forEach { modelDir ->
                modelDir.listFiles()?.forEach { variant ->
                    if (variant.isDirectory) {
                        last = maxOf(last, variant.lastModified())
                        if (readManifest(variant) != null) {
                            hasVariant = true
                            if (readManifest(variant)?.complete == true) anyComplete = true
                        }
                    }
                }
            }
            // 空壳目录（删完模型后残留的歌目录）不算缓存 —— 否则设置页会显示"0MB 的一首"
            if (!hasVariant || bytes == 0L) return@mapNotNull null
            CachedSong(keyDirName, bytes, last, anyComplete)
        }
    }

    /** 清理残留的 .tmp（半写块）——启动对账用；变体级的处置见 StemRetention.planReconcile。 */
    fun purgeTempFiles(): Int {
        var n = 0
        root.walkTopDown().forEach { f ->
            if (f.isFile && f.name.endsWith(".tmp")) {
                if (f.delete()) n++
            }
        }
        return n
    }

    fun rootDir(): File = root
}

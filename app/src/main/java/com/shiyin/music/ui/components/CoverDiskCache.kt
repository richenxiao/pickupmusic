package com.shiyin.music.ui.components

import java.io.File
import java.security.MessageDigest

/**
 * v1.2.0 阶段二：iTunes 下载封面字节的磁盘缓存（filesDir/cover_cache/）。
 *
 * v1.2.0 修正：key 必须含分辨率 bucket（1024/384/160），不同分辨率分开存文件。
 * 之前 key 不分 bucket → 首次小图(160)下载的低分辨率字节被大图(1024)请求当
 * 命中读回 → 大图用了 160 低分辨率（封面糊）。现在各分辨率独立，大图永远高清，
 * 小图单独存（多占点空间无所谓，质量优先）。
 *
 * 无 albumId↔artUrl 映射——invalidateAlbum 后旧 artUrl 文件成孤儿，由大小上限
 * + LRU 淘汰。上限 100MB（容量优先，质量不下调）。
 */
internal class CoverDiskCache {

    private val maxBytes = 100L * 1024 * 1024

    /** 所有分辨率 bucket(须与 ArtCache.bucket() 的取值一致)。文件名是 sha1("$key@$bucket"),
     *  哈希不可逆,删除某 key 的全部 bucket 文件时只能枚举这些 bucket 逐个删。 */
    private val BUCKETS = listOf(160, 384, 1024)

    private fun dirOf(ctx: android.content.Context): File =
        File(ctx.filesDir, "cover_cache").apply { if (!exists()) mkdirs() }

    private fun keyFile(d: File, artUrl: String, bucket: Int): File =
        File(d, sha1("$artUrl@$bucket") + ".bin")

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    /** 命中返回字节，未命中或读失败返回 null。touch lastModified 作 LRU 访问时间。 */
    fun read(ctx: android.content.Context, artUrl: String, bucket: Int): ByteArray? = synchronized(LOCK) {
        val f = keyFile(dirOf(ctx), artUrl, bucket)
        if (!f.exists()) return null
        try {
            f.setLastModified(System.currentTimeMillis())
            f.readBytes()
        } catch (_: Exception) { null }
    }

    /** 写入字节并触发可能的大小清理。失败静默。 */
    fun write(ctx: android.content.Context, artUrl: String, bucket: Int, bytes: ByteArray) = synchronized(LOCK) {
        try {
            keyFile(dirOf(ctx), artUrl, bucket).writeBytes(bytes)
            trimIfNeeded(ctx)
        } catch (_: Exception) { }
    }

    // ── v1.2.2: 持久化落盘双 key(专辑级 / 曲目级) ─────────────────────────────
    // 目标:"只要成功获取过一次封面,就必须永久本地化,断网也可读"。
    // 旧 artUrl key 只覆盖"走完 iTunes 下载末尾才落盘"的局部路径,且内嵌/custom 从不落盘。
    // 新方案:fetch 任意路径成功拿图后,按 scope 落盘——
    //   专辑级 album:<albumId>@<bucket> : albumId>0 非合集正常专辑(多首共享一张,跨 track 复用)
    //   曲目级 track:<mediaId>@<bucket>  : 孤立单曲(albumId<=0)与合集(避免同 albumId 封面互相覆盖)
    // 旧 artUrl key 缓存作废(不再读写,由大小上限+LRU 自然淘汰,不做兼容迁移)。

    fun readAlbum(ctx: android.content.Context, albumId: Long, bucket: Int): ByteArray? =
        readKey(ctx, "album:$albumId", bucket)
    fun writeAlbum(ctx: android.content.Context, albumId: Long, bucket: Int, bytes: ByteArray) =
        writeKey(ctx, "album:$albumId", bucket, bytes)
    fun deleteAlbum(ctx: android.content.Context, albumId: Long) = deleteKeyPrefix(ctx, "album:$albumId")

    fun readTrack(ctx: android.content.Context, mediaId: Long, bucket: Int): ByteArray? =
        readKey(ctx, "track:$mediaId", bucket)
    fun writeTrack(ctx: android.content.Context, mediaId: Long, bucket: Int, bytes: ByteArray) =
        writeKey(ctx, "track:$mediaId", bucket, bytes)
    fun deleteTrack(ctx: android.content.Context, mediaId: Long) = deleteKeyPrefix(ctx, "track:$mediaId")

    private fun readKey(ctx: android.content.Context, key: String, bucket: Int): ByteArray? = synchronized(LOCK) {
        val f = File(dirOf(ctx), sha1("$key@$bucket") + ".bin")
        if (!f.exists()) return null
        try {
            f.setLastModified(System.currentTimeMillis())
            f.readBytes()
        } catch (_: Exception) { null }
    }

    private fun writeKey(ctx: android.content.Context, key: String, bucket: Int, bytes: ByteArray) = synchronized(LOCK) {
        try {
            File(dirOf(ctx), sha1("$key@$bucket") + ".bin").writeBytes(bytes)
            trimIfNeeded(ctx)
        } catch (_: Exception) { }
    }

    /** 删除某 key 的所有 bucket 文件(供 invalidateAlbum / 单曲失效时清残留)。
     *  文件名 = sha1("$key@$bucket")+".bin",两个不同输入的 SHA-1 无前缀对应关系,
     *  不能用 sha1("$key@") 做前缀匹配(那会一个都删不掉)。枚举 BUCKETS 逐个删。 */
    private fun deleteKeyPrefix(ctx: android.content.Context, key: String) = synchronized(LOCK) {
        try {
            val d = dirOf(ctx)
            for (b in BUCKETS) {
                File(d, sha1("$key@$b") + ".bin").takeIf { it.exists() }?.delete()
            }
        } catch (_: Exception) { }
    }

    /** 全清（清理识别缓存时与 ArtCache.clearAll 配合）。 */
    fun clear(ctx: android.content.Context) = synchronized(LOCK) {
        dirOf(ctx).listFiles()?.forEach { it.delete() }
    }

    private fun trimIfNeeded(ctx: android.content.Context) {
        val d = dirOf(ctx)
        val files = d.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortBy { it.lastModified() } // 旧→新
        val it = files.iterator()
        while (it.hasNext() && total > maxBytes * 9 / 10) {
            val f = it.next()
            val sz = f.length()
            if (f.delete()) total -= sz
        }
    }

    companion object { private val LOCK = Any() }
}

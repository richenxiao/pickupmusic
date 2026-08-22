package com.shiyin.music.ui.components

import java.io.File
import java.security.MessageDigest

/**
 * v1.2.0 阶段二：iTunes 下载封面字节的磁盘缓存（filesDir/cover_cache/）。
 *
 * 设计：
 * - 键 = artUrl 的 SHA-1 hex（稳定，不受 px 变化影响）；值 = 原始下载字节
 *   （iTunes hqUrl 上采样后的高清字节，不同 px 请求同一 artUrl 共享一份，
 *    调用方解码后按需 scale，命中率高）。
 * - 不维护 albumId↔artUrl 映射（重启会丢）。[ArtCache.invalidateAlbum]
 *   失效内存/颜色缓存时不动磁盘——旧 artUrl 文件成孤儿，由大小上限 + LRU
 *   自然淘汰，保持简单、不耦合 albumId。
 * - 上限 ~50MB，超限时按 lastModified（访问时间）淘汰最旧文件。磁盘缓存本就是
 *   “尽力而为”的加速层，淘汰不影响正确性（未命中则联网下）。
 *
 * 线程安全：所有方法内部 synchronized。ArtCache 在 IO 调度器调用，传 Context。
 */
internal class CoverDiskCache {

    private val maxBytes = 50L * 1024 * 1024

    private fun dirOf(ctx: android.content.Context): File =
        File(ctx.filesDir, "cover_cache").apply { if (!exists()) mkdirs() }

    private fun keyFile(d: File, artUrl: String): File =
        File(d, sha1(artUrl) + ".bin")

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    /** 命中返回字节，未命中或读失败返回 null。命中会 touch lastModified 作为
     *  LRU 访问时间，供淘汰参考。 */
    fun read(ctx: android.content.Context, artUrl: String): ByteArray? = synchronized(LOCK) {
        val f = keyFile(dirOf(ctx), artUrl)
        if (!f.exists()) return null
        try {
            f.setLastModified(System.currentTimeMillis())
            f.readBytes()
        } catch (_: Exception) { null }
    }

    /** 写入字节并触发可能的大小清理。失败静默（磁盘缓存是尽力而为）。 */
    fun write(ctx: android.content.Context, artUrl: String, bytes: ByteArray) = synchronized(LOCK) {
        try {
            val f = keyFile(dirOf(ctx), artUrl)
            f.writeBytes(bytes)
            trimIfNeeded(ctx)
        } catch (_: Exception) { }
    }

    /** 全清（设置页“清理识别缓存”可调用，与 ArtCache.clearAll 配合）。 */
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

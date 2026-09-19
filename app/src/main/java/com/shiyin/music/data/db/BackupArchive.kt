package com.shiyin.music.data.db

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * v22: 备份归档（zip 容器）—— **纯逻辑，可单测**。
 *
 * === 为什么从"单个 JSON"升级成 zip ===
 * 用户手选的**本地图片**（歌手写真、专辑封面）会被拷进 app 私有目录，
 * 而数据库里只存了 `file://<私有目录路径>`。原来只导出 JSON 的话：
 * 换机恢复后**路径字符串还在、文件没了** → 写真/封面变成裂图。
 * 所以图片文件必须跟着一起走。
 *
 * 顺带好处：JSON 里的歌词文本压得动，归档整体更小；以后要加"本地歌词 .lrc 原文件"
 * 之类的附件也不用再改格式。
 *
 * === 向后兼容 ===
 * [read] 会**自动识别**：不是 zip 就整体当 JSON 解析（v1/v2 的老 `.json` 备份照常能读）。
 *
 * === 安全 ===
 * 条目名来自外部文件，可能被手工改成 `../../` 之类。这里**一律拒绝**绝对路径、
 * 盘符、以及任何含 `..` 的条目名（zip-slip），并且只接受 [DIR_FILES] 前缀下的条目。
 */
object BackupArchive {

    const val ENTRY_JSON = "backup.json"

    /** 图片等附属文件的目录前缀。 */
    const val DIR_FILES = "files/"

    data class Archive(
        val json: String,
        /** 相对路径 → 字节。key 形如 `artist_image_override/x_1.jpg`。 */
        val files: Map<String, ByteArray>,
        /** 是否来自 zip（false = 直接是 JSON，即旧格式）。 */
        val isZip: Boolean,
    )

    /** 打成 zip。`files` 的 key 是**相对路径**（不含 [DIR_FILES] 前缀）。 */
    fun write(json: String, files: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_JSON))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((rel, bytes) in files) {
                val safe = sanitize(rel) ?: continue
                zip.putNextEntry(ZipEntry(DIR_FILES + safe))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /**
     * 读回。自动识别 zip / 纯 JSON。
     *
     * @throws IllegalArgumentException 既不是 zip 也没有可读的 `backup.json`
     */
    fun read(bytes: ByteArray): Archive {
        if (!isZip(bytes)) {
            return Archive(json = bytes.toString(Charsets.UTF_8), files = emptyMap(), isZip = false)
        }
        var json: String? = null
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e: ZipEntry? = zip.nextEntry
            while (e != null) {
                val name = e.name
                if (!e.isDirectory) {
                    if (name == ENTRY_JSON) {
                        json = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (name.startsWith(DIR_FILES)) {
                        val rel = name.removePrefix(DIR_FILES)
                        // zip-slip 防护：只接受干净的相对路径
                        sanitize(rel)?.let { files[it] = zip.readBytes() }
                    }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        val body = json ?: throw IllegalArgumentException("备份包里没有 $ENTRY_JSON")
        return Archive(json = body, files = files, isZip = true)
    }

    /** zip 本地文件头魔数 `PK\u0003\u0004`。 */
    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /**
     * 只允许干净的相对路径：拒绝绝对路径、盘符、`..`、空名、控制字符。
     * 返回规范化后的相对路径；不安全则返回 null（调用方跳过该条目）。
     */
    fun sanitize(rel: String): String? {
        if (rel.isBlank()) return null
        if (rel.startsWith("/") || rel.startsWith("\\")) return null
        if (rel.contains(':')) return null                       // 盘符 / scheme
        if (rel.any { it.code < 0x20 }) return null
        val parts = rel.split('/', '\\').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." || it == "." }) return null
        return parts.joinToString("/")
    }
}

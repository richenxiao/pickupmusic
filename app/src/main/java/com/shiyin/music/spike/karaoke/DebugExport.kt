package com.shiyin.music.spike.karaoke

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.OutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.5.0 P1：**调试导出隔离**（用户 2026-10-12 P1 修正）。
 *
 * 原来调试页把分离出来的 WAV 直接写进共享的 `Music/PickUpMusic/`——等于往用户的
 * 真实音乐目录里丢调试产物，会被系统媒体库和 App 自己的曲库扫进去。改成：
 *
 *  - 目录固定 [DIR]（`Download/PickUpMusic-debug/`），**不再往 `Music/` 写任何东西**；
 *  - 文件名随机（`dbg-<8位随机>-acc.wav` / `-voc.wav`），一眼看不出是歌，也不会重名覆盖；
 *  - 同目录一份 `_map.tsv` 对照表：哪个文件 ↔ 哪个模型/后端/线程/哪首歌；
 *  - 调试页可一键清空（[clear]）。
 *
 * ⚠️ **这些 WAV 一定会进系统媒体库 —— 2026-10-12 真机实测确认，且是用户拍板接受的取舍**：
 *  1. **`.nomedia` 建不出来**：MediaProvider 不接受点开头的文件名，insert 时把 `.nomedia`
 *     改名成 `_.nomedia`（files 表 media_type=0），而这个名字**不被媒体扫描认作** no-media
 *     标记 —— 随后写入的两个 WAV 照样出现在 `content://media/external/audio/media`。
 *  2. **改 MIME 也没用**：实测 MediaProvider **无视** ContentValues 里的
 *     `application/octet-stream`，按**文件扩展名**重新推导 → `mime_type=audio/x-wav`、
 *     `media_type=2`、`is_music=1`。对照 `_map.tsv` → `text/tab-separated-values`。
 *     **真正的杠杆是扩展名，不是 MIME**：名字带 `.wav` 就必然归入 `MediaStore.Audio`。
 *  3. `IS_MUSIC=0` 拦不住**本 App**：`MediaScanner.scan()` 是 `MediaStore.Audio` 上的
 *     **裸查询**（无 selection），落在该表里的行都会进曲库。
 *  4. 绕过 MediaStore 直接写文件需要 `MANAGE_EXTERNAL_STORAGE` —— App 没有，也不该加。
 *
 *  **用户拍板（2026-10-12）**：位置与文件名**都不动**，**只挡拾音自己** —— 靠
 *  [com.shiyin.music.data.MediaScanner] 里按路径的过滤把本 App 曲库挡干净；
 *  **接受系统音乐 App / 别的播放器仍会列出它们**。（判据：调试导出只是开发期产物，
 *  用户明确说过"不能播不是需求"，不值得为它改目录结构。）
 *
 *  产品侧的伴奏/人声（P2 及以后）**不走这里** —— 那是 `filesDir/pickup/accompaniment-cache/`，
 *  永远只待在拾音自己的地盘内，从不进共享存储。
 *
 * 本对象只管**存哪儿、叫什么、怎么记账**，不碰音频字节——WAV 头与采样由
 * [KaraokeStemRunner] 写进 [Sink.out]。
 */
object DebugExport {

    /** 相对内部存储根的目录（MediaStore 的 `RELATIVE_PATH` 用的就是这种形式）。 */
    const val DIR = "Download/PickUpMusic-debug"

    /**
     * 导出文件的 MIME。**刻意不是 `audio/wav`** —— 这是「不进曲库」的实现手段，别改回音频类型
     * （改回去 = 立刻回到"重新扫描后曲库里多两条 WAV"的老问题，2026-10-12 实测）。
     */
    private const val WAV_MIME = "application/octet-stream"

    private const val REL = "$DIR/"
    private const val MAP = "_map.tsv"
    private const val MAP_HEADER = "file\tmodel\tbackend\tthreads\tsong\tseconds\tstatus\ttime"
    private const val NAME_CHARS = "0123456789abcdef"

    private val rnd = SecureRandom()

    /**
     * 导出开关。
     *
     * ⚠️ 为什么不写 `BuildConfig.DEBUG`：本机日常测试装的是 **release 签名**的包
     * （debug 变体签名不同，装到同一台手机上会 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
     * 而卸载会清空用户数据——已经出过一次全量丢失事故）。按 debug 变体门控等于让
     * 调试导出永远不可用。所以这里跟「调试面板是否编进包里」对齐：面板还在（当前
     * spike 分支），导出就还在；**正式版移除调试面板时，这个开关连同本文件一并删掉**。
     */
    const val ENABLED = true

    /** 一个已建好的导出文件。关流用 [out]，失败要回滚用 [uri]。 */
    class Sink(val name: String, val uri: Uri, val out: OutputStream)

    /** 8 位十六进制随机段，一次运行的两个文件共用。 */
    fun newToken(): String =
        buildString(8) { repeat(8) { append(NAME_CHARS[rnd.nextInt(NAME_CHARS.length)]) } }

    /**
     * 建伴奏 / 人声两个导出文件。
     * 任一侧失败返回 null——调用方据此报「写入失败」，不要静默。
     */
    fun open(context: Context, token: String): Pair<Sink?, Sink?> =
        create(context, "dbg-$token-acc.wav", WAV_MIME) to
            create(context, "dbg-$token-voc.wav", WAV_MIME)

    /** 追一行对照表。**每次运行都写一行**（含被取消的半截文件），失败不抛。 */
    fun appendRun(
        context: Context,
        accName: String?,
        vocName: String?,
        model: String,
        backend: String,
        threads: Int,
        song: String,
        seconds: Float,
        status: String,
    ) {
        val name = accName ?: vocName ?: return
        val uri = find(context, MAP) ?: create(context, MAP)?.uri ?: return
        val row = listOf(
            name,
            model,
            backend,
            threads.toString(),
            cell(song),
            "%.1f".format(seconds),
            status,
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()),
        ).joinToString("\t")
        runCatching {
            val resolver = context.contentResolver
            val old = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            val body = when {
                old.isBlank() -> MAP_HEADER + "\n"
                old.endsWith("\n") -> old
                else -> "$old\n"
            } + row + "\n"
            resolver.openOutputStream(uri, "wt")?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
    }

    /**
     * 目录里的文件数（[delete] 为 false 只数）；[delete] 为 true 则逐个删掉。
     *
     * [delete] 走 `ContentResolver.delete(uri)` —— MediaProvider 会**连同磁盘文件一起删**，
     * 媒体库记录也随之消失。用户 2026-10-12 明确要求清空必须两者都删：只删文件不删记录的话，
     * 系统音乐 App 里仍会留着指向已删文件的条目。
     */
    fun clear(context: Context, delete: Boolean): Int {
        val resolver = context.contentResolver
        val collection = collection()
        val targets = ArrayList<Uri>()
        runCatching {
            resolver.query(collection, PROJECTION, null, null, null)?.use { cur ->
                val idIdx = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val pathIdx = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cur.moveToNext()) {
                    if (cur.getString(pathIdx)?.trimEnd('/') != DIR) continue
                    targets += ContentUris.withAppendedId(collection, cur.getLong(idIdx))
                }
            }
        }
        if (!delete) return targets.size
        var n = 0
        for (u in targets) runCatching { if (resolver.delete(u, null, null) > 0) n++ }
        return n
    }

    // ── 内部 ────────────────────────────────────────────────────────────────────

    private val PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
    )

    private fun collection(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 新建一个文件（[mime] 为 null 表示不是音频，纯文本/占位用）。 */
    private fun create(context: Context, name: String, mime: String? = null): Sink? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, REL)
            if (mime != null) put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }
        val uri = runCatching { context.contentResolver.insert(collection(), values) }
            .getOrNull() ?: return null
        val out = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
            ?: return null
        return Sink(name, uri, out)
    }

    /**
     * 找目录里已有的同名文件。
     *
     * 先查再建（不能直接 insert）：MediaProvider 遇到重名会把新文件改成 `.nomedia (1)`，
     * 那样 `.nomedia` 就不叫 `.nomedia` 了，等于没建。
     */
    private fun find(context: Context, name: String): Uri? = runCatching {
        val resolver = context.contentResolver
        val collection = collection()
        var hit: Uri? = null
        resolver.query(
            collection, PROJECTION,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null,
        )?.use { cur ->
            val idIdx = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val pathIdx = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (hit == null && cur.moveToNext()) {
                if (cur.getString(pathIdx)?.trimEnd('/') == DIR) {
                    hit = ContentUris.withAppendedId(collection, cur.getLong(idIdx))
                }
            }
        }
        hit
    }.getOrNull()

    /** 对照表是按 tab/换行分列的，字段里的分隔符要抹掉，否则一行会被读成多列。 */
    internal fun cell(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

package com.shiyin.music.data.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v1.3.x: 本机使用日志（设置 → 数据管理 → 日志）。
 *
 * **为什么要有它**：此前只有崩溃栈（`crash_logs/`）——"点了没反应""结果不对""备份失败"
 * 这类**没有崩溃**的问题，在真机上是一片空白，只能靠来回猜。这个文件让用户能把
 * "到底发生了什么"直接发回来。
 *
 * 三条硬规矩：
 *  1. **不记内容**：只记"做了什么事"（页面/技能/识别/备份）与结果、数量。
 *     歌词文本、搜索词、API Key、账号一律不进日志 —— 这个文件是会被用户分享出去的。
 *  2. **不阻塞主线程**：写盘走单线程 executor，从 UI 回调里直接调也不会掉帧。
 *  3. **有界**：按天一个文件，保留 [KEEP_DAYS] 天（启动时清理），单文件超 [MAX_FILE_BYTES]
 *     就停写当天的（宁可缺，不可把用户空间吃掉）。
 *
 * 崩溃日志（`crash_logs/`，由 `ShiyinApp` 的全局异常处理器写）在[prune]里按同样的
 * 保留期一起清理 —— 否则它会无限增长。
 */
object UsageLog {

    /**
     * 保留天数。改这个常量即可调整保留期（设置页文案读的就是它）。
     *
     * 用户定调：**3 天**（"3天都够久了，1天都足够了"）。取 3 不取 1 的理由：
     * 用户往往是**第二天**才发现问题、才去翻日志，只留 1 天会把出问题那天的记录
     * 正好删掉——保留期要比"用户反馈的延迟"长一点才有意义。
     */
    const val KEEP_DAYS = 3

    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val DIR_NAME = "usage_logs"
    private const val CRASH_DIR_NAME = "crash_logs"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usage-log").apply { isDaemon = true }
    }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    // ⚠️ 解析文件名里的日期必须和写文件用的 dayFmt 是**同一个格式**。
    // 曾写错成 "yyyyMMdd"（无分隔符），而文件名是 "yyyy-MM-dd"（带横杠）→
    // parse 必失败 → fileDay 返回 null → files() 把所有日志文件都过滤掉 →
    // 日志页永远"还没有记录"（其实文件已经写出来了）。
    private val nameFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile private var prunedThisProcess = false

    /** 记一条事件。[category] 是粗分类（启动/扫描/歌词/翻译/Agent/云备份），[detail] 只写结果与数量。 */
    fun event(context: Context, category: String, detail: String = "") {
        val app = context.applicationContext
        io.execute {
            runCatching {
                if (!prunedThisProcess) {
                    prunedThisProcess = true
                    prune(app)
                }
                val day = File(baseDir(app), "usage-${dayFmt.format(Date())}.log")
                if (day.exists() && day.length() > MAX_FILE_BYTES) return@runCatching
                baseDir(app).mkdirs()
                day.appendText("${timeFmt.format(Date())}  [$category] $detail\n")
            }
        }
    }

    private fun baseDir(context: Context) = File(context.filesDir, DIR_NAME)

    private fun crashDir(context: Context) =
        context.getExternalFilesDir(CRASH_DIR_NAME) ?: File(context.filesDir, CRASH_DIR_NAME)

    /** 保留期内的日志文件（旧的在前）。文件名里的日期是唯一判据——不用 mtime，改设备时间不会误删。 */
    fun files(context: Context): List<File> {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        return runCatching {
            (baseDir(context).listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.startsWith("usage-") && it.name.endsWith(".log") }
                .filter { fileDay(it)?.time?.let { t -> t >= cutoff } ?: false }
                .sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    /** 保留期内的崩溃报告文件（旧的在前）。 */
    fun crashFiles(context: Context): List<File> {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        return runCatching {
            (crashDir(context).listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.startsWith("crash_") }
                .filter { it.lastModified() >= cutoff }
                .sortedBy { it.lastModified() }
        }.getOrDefault(emptyList())
    }

    /** 全部保留日志拼成一段文本（供展示与分享）。[maxLines] 只保留末尾若干行。 */
    fun readAll(context: Context, maxLines: Int = 2000): String {
        val text = runCatching {
            files(context).joinToString("") { f ->
                runCatching { f.readText() }.getOrDefault("")
            }
        }.getOrDefault("")
        if (text.isBlank()) return ""
        val lines = text.trimEnd('\n').lines()
        return if (lines.size <= maxLines) lines.joinToString("\n") else
            lines.takeLast(maxLines).joinToString("\n")
    }

    /** 保留期内的日志总字节数（设置页展示占用）。 */
    fun sizeBytes(context: Context): Long =
        files(context).sumOf { it.length() } + crashFiles(context).sumOf { it.length() }

    /** 清空全部使用日志（**不碰崩溃报告**——那是排查崩溃的独立证据）。 */
    fun clear(context: Context) {
        runCatching { baseDir(context).listFiles()?.forEach { it.delete() } }
    }

    /**
     * 清理超出保留期的文件：使用日志 + 崩溃报告。
     * 进程内首次 [event] 会顺带跑一次，UI 打开日志页时也会显式调一次。
     */
    fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        runCatching {
            (baseDir(context).listFiles() ?: emptyArray()).forEach { f ->
                val t = fileDay(f)?.time ?: f.lastModified()
                if (t < cutoff) f.delete()
            }
        }
        runCatching {
            (crashDir(context).listFiles() ?: emptyArray()).forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        }
    }

    private fun fileDay(f: File): Date? = runCatching {
        val stamp = f.name.removePrefix("usage-").removeSuffix(".log")
        nameFmt.parse(stamp)
    }.getOrNull()
}

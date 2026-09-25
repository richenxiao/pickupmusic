package com.shiyin.music

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.shiyin.music.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The PlayerController lives at application scope so the sleep timer and the
 * gapless-off 1s gap keep working while the foreground service outlives the UI.
 */
class ShiyinApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val player: PlayerController by lazy { PlayerController(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        // v1.3.x: 使用日志（设置 → 数据管理 → 日志）。只记"发生了什么"，不记内容；
        // 写盘在后台单线程，顺带清理超过保留期的日志与崩溃报告。
        com.shiyin.music.data.log.UsageLog.event(this, "启动", "冷启动")
        // v4.3: ensure media playback notification channel exists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "shiyin_media_playback",
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "播放控制通知"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        // v2.0: global crash handler — writes stack trace to crash_logs/
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logDir = getExternalFilesDir("crash_logs") ?: File(filesDir, "crash_logs")
                logDir.mkdirs()
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(logDir, "crash_$ts.txt")
                FileWriter(file).use { w ->
                    w.write("=== PickUpMusic v1.0.0 Crash Report ===\n")
                    w.write("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
                    w.write("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})\n")
                    w.write("Thread: ${thread.name}\n\n")
                    w.write("${throwable.javaClass.name}: ${throwable.message}\n\n")
                    for (e in throwable.stackTrace) {
                        w.write("\tat $e\n")
                    }
                    var cause = throwable.cause
                    while (cause != null) {
                        w.write("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
                        for (e in cause.stackTrace) {
                            w.write("\tat $e\n")
                        }
                        cause = cause.cause
                    }
                }
            } catch (_: Exception) {
            } finally {
                Process.killProcess(Process.myPid())
            }
        }
    }
}

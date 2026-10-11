package com.shiyin.music.spike.karaoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 Spike A4（手机侧）：在**真机的 release(R8) 变体**上跑通伴唱探针。
 *
 * 为什么是 instrumented 测试而不是让用户手点：本工程 androidTest 走 release 变体
 * （app/build.gradle.kts `testBuildType = "release"`），正是 2026-10-11 真机崩溃
 * （ORT JNI 查不到被 R8 混淆的类 → SIGABRT）的那套配置。放在这里跑，等于把
 * 「R8 + ORT 绑定」这条最脆的链子钉住——以后换 ORT 版本/改混淆规则，CI 或本地
 * connected 测试会第一时间发现。
 */
@RunWith(AndroidJUnit4::class)
class OrtDeviceProbeTest {

    @Test
    fun probe_runsChunkOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = runBlocking { KaraokeP0Probe.runChunk(context) }
        println("=== 伴唱 P0 真机探针报告 ===\n$report\n===========================")
        assertTrue("探针报错:\n$report", !report.contains("❌"))
        assertTrue("输出量级异常:\n$report", report.contains("✅"))
    }
}

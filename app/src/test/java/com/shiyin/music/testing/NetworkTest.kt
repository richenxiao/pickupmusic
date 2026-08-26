package com.shiyin.music.testing

/**
 * v1.2.1: 标记"实时网络测试"——依赖外部 API(Discogs/AudioDB 等),按 IP 限流会偶发失败。
 *
 * 被 @Category(NetworkTest::class) 标注的测试类/方法在默认 `testDebugUnitTest` /
 * `testReleaseUnitTest` 里被排除(见 build.gradle.kts 的 excludeCategories),不作为
 * 本地/CI 的必过确定性测试。单独运行用 `gradle testNetwork` 任务。
 *
 * 默认跑的应是 mock/fixture 确定性测试;联网测试按需单独跑,失败重试即可。
 */
interface NetworkTest

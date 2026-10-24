pluginManagement {
    repositories {
        // ⚠️ 插件解析**官方源在前**（2026-10-24 CI 连挂两次的教训）：
        // 阿里云对 Gradle 插件的 marker POM（com.google.devtools.ksp.gradle.plugin 等）
        // 同步不稳定——本机 curl 是 200，CI（海外 runner）拿到的却是 404/"不存在"，
        // Gradle 记下"此仓库确认没有"后虽然会继续试后面的源，但每次都要先在阿里云上
        // 撞一遍墙，撞上瞬时故障就直接判死。插件只有几个、只在解析时拉一次，
        // 官方源放前面不影响国内日常速度；**依赖**仓库（下方）继续阿里云加速。
        // （不用腾讯镜像——用户明确不使用腾讯产品。）
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}
// 依赖仓库镜像：国内开发者保留阿里云镜像可加速依赖下载；
// 海外贡献者可注释掉 maven.aliyun.com 那一行，改用 google()/mavenCentral() 即可。
rootProject.name = "PickUpMusic"
include(":app")

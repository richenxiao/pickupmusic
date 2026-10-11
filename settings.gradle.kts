pluginManagement {
    repositories {
        // 国内加速源：阿里云公共仓库（不用腾讯镜像——用户明确不使用腾讯产品）。
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
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

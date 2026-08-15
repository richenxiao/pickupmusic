pluginManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        google()
        mavenCentral()
    }
}
// 依赖仓库镜像：国内开发者保留 Tencent 镜像可加速依赖下载；
// 海外贡献者可注释掉下面两行 mirrors.cloud.tencent.com，改用 google()/mavenCentral() 即可。
rootProject.name = "PickUpMusic"
include(":app")

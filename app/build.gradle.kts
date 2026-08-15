import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.shiyin.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shiyin.music"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
    }

    signingConfigs {
        // Release 签名配置从不入库：从 gitignored 的 keystore.properties 读取。
        // 仓库只提供 keystore.properties.example 占位模板。Debug 构建无需此文件；
        // Release 构建在缺少真实 keystore.properties / keystore 时会在此明确失败，
        // 避免误用错 keystore 或用 debug 签名冒充 release。
        create("release") {
            val ksPropsFile = rootProject.file("keystore.properties")
            if (ksPropsFile.exists()) {
                val props = Properties()
                props.load(ksPropsFile.inputStream())
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.media3:media3-common:1.7.1")

    // v5.2 Bug1: 系统级媒体输出切换器。SystemOutputSwitcherDialogController.showDialog
    // 弹出 Android 系统 Output Switcher（SystemUI/MediaRouter2），由系统执行真实音频路由，
    // 兼容 ColorOS 等 OEM（app 层 setPreferredDevice 在这些机器上不生效）。仅用于设备切换入口。
    implementation("androidx.mediarouter:mediarouter:1.8.1")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    implementation("androidx.datastore:datastore-preferences:1.1.6")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // v3.0: real album-cover color extraction (Spotify-style dynamic tints)
    implementation("androidx.palette:palette-ktx:1.0.0")

    // v5.2 #72: 拖拽排序改用 reorderable 库(sh.calvin.reorderable:3.1.0,KMP,Android
    // target 解析为 androidx.compose),替代手写状态机。先加依赖验证与 BOM 1.8.2 兼容。
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // v4.3: fuzzy-search unit tests
    testImplementation("junit:junit:4.13.2")
}

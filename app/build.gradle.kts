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
        versionCode = 10500
        versionName = "1.5.0"
        // v1.2.0 阶段一：instrumented test runner 显式用 androidx（不显式设时，
        // androidTest APK 经 R8 后 AGP 可能回退到系统框架 android.test.InstrumentationTestRunner
        // 致使 instrumentation 进程启动即崩）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // v1.2.0 #6: 歌手写真自动源的 API key（可选）。Discogs 无需 key 即可取人物照；
        // Last.fm / Fanart.tv 需有效 key 才返回图。缺省空串→对应源跳过，Discogs 仍可独立工作。
        // key 写在 gitignored 的 local.properties（LASTFM_API_KEY= / FANART_API_KEY=）。
        val lp = rootProject.file("local.properties")
        val props = Properties().apply { if (lp.exists()) load(lp.inputStream()) }
        buildConfigField("String", "LASTFM_API_KEY", "\"${props.getProperty("LASTFM_API_KEY", "")}\"")
        buildConfigField("String", "FANART_API_KEY", "\"${props.getProperty("FANART_API_KEY", "")}\"")

        // v1.5.0 伴唱 P0 Spike：onnxruntime-android 的 .so 体积大，spike 期间限定 arm64-v8a
        // （规格 §11 本就要求显式 abiFilters；正式取舍由 S4 体积实测后拍板）。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // v1.5.0 伴唱 P0 Spike：开发期把模型打进 APK（用户 2026-10-11 拍板，A57）。
    // 打进 APK 的只有 .karaoke/bundled/ 里的模型（当前仅 9482，29.7MB——另外两个 66.7MB
    // 的用户拍板线是 <40MB，先不内置）。.karaoke 在 .gitignore 里，不入库；其它机器构建时
    // 该目录不存在则 APK 不含模型，探针会提示「模型未打包」。
    sourceSets {
        getByName("main") {
            assets.srcDir("../.karaoke/bundled")
        }
    }
    androidResources {
        // ONNX 模型已是无压缩二进制，压缩只会拖慢读取（ORT 需要 mmap/文件路径）。
        noCompress += "onnx"
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
                // storeFile 在 keystore.properties 中通常是相对项目根的路径，
                //故用 rootProject.file 解析（相对 app 模块会错位到 app/keystore/）。
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // v1.2.0 阶段一：开启 R8 裁剪，剔除 material-icons-extended ~24MB 未引用图标与死代码。
            // 配合 app/proguard-rules.pro 保护 Kuromoji/Gson/Room/Media3 等反射依赖。
            // proguard-android.txt 自带 -dontoptimize（不开优化 pass，最稳）；混淆开，
            // mapping.txt 输出用于反混淆崩溃栈。Kuromoji 33MB 词表为 JAR 资源，不受影响。
            // (v1.3.0 调试期曾临时关闭过一次,排完 Agent 日志已恢复。)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // v1.2.0 阶段一：instrumented test 走 release 变体（经 R8）以验证 R8 未破坏 Kuromoji。
    // 仅影响 androidTest 的目标 buildType，不改变 release 产品 APK 本身。
    testBuildType = "release"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // v1.2.0 #6: 歌手写真 Last.fm / Fanart.tv 源需要 API key。key 从 gitignored 的
        // local.properties 读取（LASTFM_API_KEY= / FANART_API_KEY=），缺省空串→对应源跳过，
        // Discogs（无需 key）仍可独立工作。BuildConfig 字段被 R8 内联，无运行时 IO。
        buildConfig = true
    }
    // Kuromoji IPADIC 自带词表 jar 与其它依赖在 META-INF 下存在重复资源，
    // 打包阶段会报 DuplicateFileException。排除这些路径即可，不影响运行时词表加载。
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                // kuromoji-ipadic 与 kuromoji-core 两个 jar 在 META-INF 下重复携带
                // 这些 markdown / 文本元数据，排除其一即可（非运行时所需）。
                "META-INF/*.md",
                "META-INF/*.txt",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
            )
        }
    }

    // v1.2.1: 默认单元测试排除"实时网络测试"(标 @Category(NetworkTest::class) 的)，
    // 使本地/CI 跑 testDebugUnitTest 只跑确定性(mock/fixture)测试,不受 Discogs 按 IP
    // 限流偶发失败干扰。联网测试单独运行用 `gradle testNetwork` 任务(见文件末注册)。
    testOptions {
        unitTests {
            // v1.2.1: 单测 JVM 无 android.jar 实现,写真源里的 android.util.Log 诊断日志
            // 会让 NetworkTest 抛 "Method d not mocked"(源逻辑并未被测,日志只是副作用)。
            // 开 returnDefaultValues 让 Log 等框架桩默认返回零值,联网测试可真正跑通。
            isReturnDefaultValues = true
            all {
                it.useJUnit { excludeCategories("com.shiyin.music.testing.NetworkTest") }
            }
        }
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
    // Material Icons Extended（完整图标集，R8 裁剪只保留实际引用的）：
    // 用于 Eye/EyeOff/Speed 等 Lucide 子集缺失的语义图标。
    implementation("androidx.compose.material:material-icons-extended")

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

    // v1.5.0 伴唱 P0 Spike：STFT/iSTFT 需要支持非 2 幂长度（n_fft=6144=2^11·3、
    // 7680=2^9·3·5）的混合基 FFT。JTransforms 支持任意合数长度（2/3/5 混合基 + Bluestein），
    // 三重许可 MPL-1.1/LGPL-2.1/GPL-3.0（本项目按 MPL-1.1 以二进制依赖使用，与 MIT 兼容）。
    implementation("com.github.wendykierp:JTransforms:3.1")

    // v1.5.0 伴唱 P0 Spike A4（手机侧）：onnxruntime-android AAR。
    // 桌面 JDK 的 CRT 太旧导致 1.31.0 在 JVM 单测崩溃是桌面特有问题；Android 上 AAR 自带
    // libc++_shared，直接用最新版。16KB 页对齐在 S4 实测（探针会报页大小）。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")

    // v1.5.0 伴唱 P0 Spike A4：桌面 JVM 侧单 chunk 对拍（SNR 基准来自
    // tools/karaoke/make_chunk_fixture.py 的 onnxruntime-python 输出）。
    // 仅 test 作用域；Android 侧运行时选型（onnxruntime-android vs mobile）见阶段 A 报告。
    // 注：1.31.0 在本机 JDK17（自带 msvcp140 14.36）下原生初始化崩溃；
    // 1.20.0 为可工作的桌面对拍版本。Android 侧版本选型与此无关（手机上是 ART + AAR 内 CRT）。
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")

    // ── 需求6「歌词本翻译」离线路径：ML Kit 端侧翻译（**已实测、待拍板，暂未启用**）──
    // 拟加：implementation("com.google.mlkit:translate:17.0.3")
    //   bundled 变体（翻译代码含 TFLite 运行时打进 APK），不依赖 Google Play 服务，
    //   国内无 GMS 的 ROM 也能跑；另一变体 play-services-mlkit-translation 走 Play
    //   Services 分发，国内 ROM 不可用，已排除。
    // 语言包本身无法打进 APK（官方只提供动态下载，约 30MB/语言存用户手机）。
    //
    // ⚠️ 2026-09-16 实测：加这一个依赖使 release APK **27.2 MiB → 87.5 MiB（+60.3 MiB）**，
    //    远超方案预估的 +2~4MB。原因：R8 裁不了原生库，libtranslate_jni.so 四个 ABI
    //    全带（arm64 15.60 + armeabi-v7a 11.07 + x86_64 16.57 + x86 16.46 MiB）。
    //    16KB page size 对齐已实测通过（PT_LOAD p_align = 16384）。
    //    处置选项（三选一，尚未拍板）：①接受 87.5MB ②release 只留 arm 两 ABI（≈54.5 MiB，
    //    但模拟器 x86_64 的 instrumented test 会受影响）③仅 arm64（≈43.4 MiB）。
    //    拍板后再放开这一行并补回 MlKitLyricsTranslator.kt。

    // v1.1.0: 日文歌词振假名（furigana）分词引擎。Kuromoji IPADIC 为纯 Java，
    // 无 .so / JNI / 网络，词表内置（APK 增大约 14MB）。Token.getReading() 返回
    // 片假名读音，渲染层据此生成振假名注音。仅用于「歌词本」全屏页。
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    // v1.2.0 阶段一：instrumented test 在模拟器/真机上验证 release(R8) 变体下
    // Kuromoji 振假名未受 R8 裁剪影响（依赖反射/字符串加载词表，最高风险点）。
    // androidTest 不进产品 APK，不影响现有功能。
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// v1.2.1: 实时网络测试单独运行任务。只跑标了 @Category(NetworkTest::class) 的测试
// (ArtistImageSourcesTest 等,依赖外部 API,按 IP 限流会偶发失败)。
// 默认 testDebugUnitTest/testReleaseUnitTest 已 exclude 该类,故本地/CI 不受其干扰。
// 用法:gradle :app:testNetwork   (联网失败重试即可,不代表代码回归)
val networkTest = tasks.register<Test>("testNetwork") {
    group = "verification"
    description = "Realtime network tests (Discogs/AudioDB etc.). Excluded from default test runs; may transiently fail on rate-limit. Run explicitly."
    val base = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = base.get().testClassesDirs
    classpath = base.get().classpath
    useJUnit { includeCategories("com.shiyin.music.testing.NetworkTest") }
    // 联网测试偶发限流:显式运行时也尽量不让一次限流把整个任务标红——
    // 但仍打印每个失败,便于判断是真回归还是网络抖动。
    ignoreFailures = true
}

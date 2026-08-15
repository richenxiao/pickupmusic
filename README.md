# 拾音音乐 PickUpMusic

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1+-7F52FF.svg)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-2025.05-%234285F4.svg)](https://developer.android.com/jetpack/compose)
[![API](https://img.shields.io/badge/Min%20SDK-31-%2344CC11.svg)](https://developer.android.com/about/versions/12/features)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **拾音** — 一个极简、现代的本地音乐播放器，采用 Material 3 设计语言和 Jetpack Compose 构建。
> 专注于本地音乐播放体验，并在此基础上提供 AI 歌曲信息识别、歌手头像自动抓取、
> Spotify 风格动态取色、收听统计、最近播放、歌手页专辑分类浏览等进阶能力。

## ✨ 功能特性

### 播放与音频

- **🎵 本地音乐播放** — 扫描并播放设备上的本地音频文件，支持多种格式
- **▶️ 后台播放** — 前台服务 + Media3 实现后台持续播放，支持锁屏控制
- **🔔 通知栏控制** — 系统通知栏显示播放信息，支持播放/暂停/切歌
- **📋 播放队列** — 自定义播放列表，支持循环、随机播放模式
- **🔗 无缝播放** — 可切换的无间隙音轨过渡（gapless）
- **🎧 音频设备路由** — 在扬声器 / 耳机 / 蓝牙设备间切换输出，支持蓝牙连接感知

### 歌词

- **📃 歌词同步** — 支持 LRC 歌词解析与自动匹配，逐行滚动同步
- **📝 歌词导入** — 支持为本地歌曲手动导入歌词并持久化保存

### 音乐库与浏览

- **📚 音乐库管理** — 按歌曲、专辑、歌手分类浏览，支持搜索
- **🎤 歌手详情页** — 歌手头像、歌曲列表、专辑区块
- **💿 歌手专辑区块** — 歌手页默认横向展示前 4 张专辑，点击「显示全部」进入完整专辑列表；
  按 iTunes 发行日期排序（最新优先 / 最早优先），并自动分类为 **单曲（1-3 首）/ EP（4-6 首且 ≤30 分钟）/ 专辑（≥7 首或 >30 分钟）**
  > 注：发行日期来自 iTunes 商店日期，仅用于排序，并非歌曲原始发行日期。
- **🔀 歌手归一化** — 自动合并同一歌手被识别为多个名字的情况（如 `fujiikaze` / `藤井风`），支持事后撤销
- **🖼️ 歌手头像自动抓取** — 通过 MusicBrainz → Wikidata → iTunes 三级回退获取歌手头像

### 智能 & 个性化

- **🤖 AI 歌曲信息识别** — 集成 DeepSeek，辅助补全歌曲的专辑归属、艺人、发行信息等元数据（需在设置中填入自己的 API Key）
- **🎨 动态取色** — 借鉴 Spotify 加权评分算法从专辑封面提取主色，沉浸式播放界面与歌词页随封面色调联动（WCAG 4.5 对比度调整，深灰兜底）
- **🕘 最近播放** — 持久化的最近播放列表，最多保留 12 首，侧边栏直达
- **📊 收听统计** — 基于带时间戳的播放事件，按周聚合统计最常听的专辑 / 歌手 / 歌曲
- **🔔 你的更新** — 新扫描入库的专辑一次性提醒，避免错过新增内容

### 界面与主题

- **🎨 Material 3 设计** — 动态主题配色，沉浸式播放界面
- **🔄 专辑封面动画** — 播放界面旋转唱片动画
- **🌙 深色模式** — 跟随系统主题或手动切换，夜间使用更舒适
- **🏷️ 应用图标** — 自适应图标 + Android 13+ 单色主题图标支持

## 🛠️ 技术栈

| 技术 | 用途 |
|------|------|
| **Kotlin** | 开发语言 |
| **Jetpack Compose** | UI 框架，声明式界面构建 |
| **Material 3** | 设计系统，Material You 动态主题 |
| **AndroidX Media3 (ExoPlayer)** | 音频播放引擎，支持后台播放与 MediaSession |
| **Room** | 本地数据库，存储播放列表、缓存、播放事件、歌手/专辑元数据 |
| **DataStore** | 键值对存储，保存设置偏好与最近播放 |
| **KSP** | 编译时注解处理，Room 代码生成 |
| **Coroutines / Flow** | 异步与响应式编程 |
| **OkHttp** | 网络请求（歌手头像、AI 识别、在线元数据） |
| **Gson** | JSON 解析 |
| **AndroidX Palette** | 专辑封面主色提取，驱动动态取色 |

## 📱 界面一览

| 页面 | 功能 |
|------|------|
| **首页** | 推荐歌曲、最近播放、快捷操作 |
| **搜索** | 本地音乐搜索 |
| **音乐库** | 按歌曲 / 专辑 / 歌手分类浏览，含歌手详情与专辑分类列表 |
| **播放器** | 唱片动画、歌词同步、播放控制、进度条、设备路由 |
| **歌词** | 全屏逐行歌词显示，随封面取色联动 |
| **最近播放** | 持久化的最近播放历史 |
| **收听统计** | 按周聚合的收听榜单 |
| **你的更新** | 新扫描专辑的更新提醒 |
| **设置** | 主题、播放设置、DeepSeek API Key、关于 |

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog (2024.1+) 或更高版本
- JDK 17+
- Android SDK 36（compileSdk / targetSdk），最低支持 Android 12（minSdk 31）
- Gradle 8.13+

### 构建与运行

> Debug 构建无需任何签名 / 密码配置，克隆后即可直接构建。

```bash
# 克隆仓库
git clone https://github.com/richenxiao/PickUpMusic.git
cd PickUpMusic

# 使用 Gradle Wrapper 构建 Debug APK（Windows 下用 gradlew.bat）
./gradlew assembleDebug

# 或直接用 Android Studio 打开项目运行
# （首次打开时 Android Studio 会自动生成 local.properties 指向本机 Android SDK；
#   命令行构建可参考 local.properties.example 自行填写 sdk.dir）
```

Debug APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### 生成 Release APK（需自行配置签名）

仓库不包含任何真实签名密钥与密码。要构建 Release，请按 `keystore.properties.example` 自行准备你自己的签名：

1. 生成你自己的 release keystore：
   ```bash
   keytool -genkey -v -keystore my-release.keystore -alias my-key \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. 复制 `keystore.properties.example` 为 `keystore.properties`，填入你的 keystore 路径与密码：
   ```properties
   storeFile=/absolute/path/to/my-release.keystore
   storePassword=你的密码
   keyAlias=my-key
   keyPassword=你的密码
   ```
   （`keystore.properties` 与 `*.jks` 均在 `.gitignore`，不会被提交。）
3. 构建：
   ```bash
   ./gradlew assembleRelease
   ```

Release APK 输出路径：`app/build/outputs/apk/release/app-release.apk`

> 作者的正式发布签名身份（keystore / 密码）绝不公开；fork 后请使用你自己的签名构建你自己的 Release。

## 📁 项目结构

```
PickUpMusic/
├── app/
│   ├── src/main/
│   │   ├── java/com/shiyin/music/
│   │   │   ├── data/
│   │   │   │   ├── ai/              # AI 服务（DeepSeek 元数据识别）
│   │   │   │   ├── colors/          # 封面主色提取（PaletteExtractor）
│   │   │   │   ├── normalize/       # 歌手名归一化（ArtistNormalizer）
│   │   │   │   ├── recognition/     # 歌手头像抓取（ArtistAvatarFetcher）
│   │   │   │   ├── db/             # Room 数据库（AppDatabase, DAO, 实体, 迁移）
│   │   │   │   ├── lyrics/         # 歌词解析（LRC, USLT）
│   │   │   │   ├── MediaScanner.kt   # 媒体库扫描
│   │   │   │   ├── SettingsStore.kt  # DataStore 设置与最近播放
│   │   │   │   └── Track.kt         # 歌曲数据模型
│   │   │   ├── playback/
│   │   │   │   ├── PlaybackService.kt  # 后台播放服务
│   │   │   │   ├── PlayerController.kt # 播放器控制器
│   │   │   │   └── DeviceRouter.kt    # 音频输出设备路由
│   │   │   ├── ui/
│   │   │   │   ├── components/    # 通用 UI 组件（PillButton, CoverArt, OIcon…）
│   │   │   │   ├── icons/        # 图标库 (Lucide)
│   │   │   │   ├── screens/      # 页面（首页/搜索/音乐库/播放器/歌词/最近播放/收听统计/你的更新/设置；歌手详情与专辑列表内嵌于 LibraryScreen）
│   │   │   │   └── theme/        # 主题配置与 OrganicColors
│   │   │   ├── MainActivity.kt   # 主 Activity
│   │   │   ├── MainViewModel.kt  # 主 ViewModel
│   │   │   ├── ShiyinApp.kt      # Application 类
│   │   │   └── AppRoot.kt        # 导航根组件
│   │   ├── res/                  # 资源文件（含各档位应用图标）
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts               # 根构建脚本
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat          # Gradle Wrapper（仓库自带，无需全局安装 Gradle）
├── keystore.properties.example    # Release 签名占位模板（自行复制为 keystore.properties 填真实值，不入库）
├── local.properties.example       # 本机 Android SDK 路径占位模板（自行复制为 local.properties，不入库）
├── DEVELOPMENT.md                 # 开发规范（分支/命名/提交/版本/签名/PR 流程）
├── CHANGELOG.md                   # 版本变更记录
├── LICENSE                         # MIT 许可证
├── CLAUDE.md                       # AI 协作工作规范
└── README.md
```

> 真实签名文件（`*.jks` / `*.keystore`）、`keystore.properties`、`local.properties`、
> 以及作者私密签名管理备忘 `PRIVATE_SECURITY.md` 均在 `.gitignore` 中，绝不入库。

## 🔒 隐私说明

- 所有本地音乐扫描与播放均在设备本地完成。
- 网络访问仅用于：抓取歌手头像（MusicBrainz / Wikidata / iTunes）、调用用户自配的 DeepSeek API 进行元数据识别。
- 不会上传你的本地音乐文件或收听记录到任何服务器。

## 📄 许可证

本项目基于 MIT 许可证开源。详见 [LICENSE](LICENSE) 文件。

---

**拾音** — 用心聆听每一个音符 🎶

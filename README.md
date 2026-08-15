# PickUpMusic

> 本地音乐文件从不缺，缺的是完整的音乐体验。

你手机里的本地音乐往往没有封面、缺歌词、元数据残缺，多数播放器只负责把文件播出来，残缺原样留给你。PickUpMusic 反过来：通过站点 API 自动匹配歌词、封面与歌曲信息，匹配不准的再交给一套完整的人工修正工具，最后用以专辑为中心的方式把散落的文件重新拼回完整的音乐形态——让本地音乐库接近你熟悉的那种音乐软件的体验，而不是再多一个能播歌的 App。

<p align="center">
  <img src="docs/screenshots/now-playing.jpg" width="400" alt="PickUpMusic 播放页：专辑封面随播放旋转，整页配色取自封面，歌词由 LRCLIB 自动匹配" />
</p>

## 核心能力

### 1. 音乐资源匹配 —— 自动补全缺失的歌词、封面与信息

- **歌词自动匹配**：LRCLIB 与网易云两源级联，命中即填；文件内嵌的 USLT 歌词也会被直接读取。
- **封面自动匹配**：从 iTunes 拉取候选封面，自动挑最合适的一张。
- **歌曲信息**：基于文件名与已有元数据，向在线来源补全专辑归属、艺人、发行信息。

### 2. 人工修正 —— 自动匹配不准时，每一项都能手动修

- **歌词**：一键换源（切到另一个来源重新匹配）、删除不合适的、或手动导入一份 LRC。
- **封面**：重新搜索、在多个候选里挑一张、或从相册上传一张。
- **歌曲信息**：逐字段编辑，并基于编辑后的信息重新匹配封面与歌词——改一次信息，封面和歌词跟着重新对齐。

### 3. 本地音乐库管理 —— 不该当音乐的，一开始就别收进来

- **排除目录与文件**：录音、通话录音、通知音、其他 App 的音频，先挡在音乐库之外，而不是扫进来再一个个手动删。
- **歌手归一化**：同一歌手被识别成多个名字（如 `fujiikaze` / `藤井风`）时自动合并，支持事后撤销。

### 4. 完整的音乐组织 —— 以专辑为中心，而非以文件夹为中心

- **自动分类单曲 / EP / 专辑**：按曲目数与时长判定（单曲 1–3 首 / EP 4–6 首且 ≤30 分钟 / 专辑 ≥7 首或 >30 分钟）。
- **专辑与歌曲分开维护**：一张专辑的元数据单独编辑，不与单首歌耦合；歌曲信息也能单独改。
- **无缝播放（gapless）默认开启**：尊重专辑内音轨的连续性，不把曲目之间的衔接打断。

### 5. 更接近音乐软件的体验

- **歌手页**：从一首歌进到歌手，再看这位歌手的其他作品——而不是停留在文件夹浏览。
- **歌词书**：独立的全屏歌词阅读界面，配色随封面联动。
- **沉浸式播放页**：从专辑封面提取主色，整页配色随之变化（遵循 WCAG 4.5 对比度，深灰兜底）。
- **深色模式**、**Material 3** 设计语言。

### 其他

- **收听统计**（按周聚合）、**最近播放**（最多 12 首）、**"你的更新"**（新扫描专辑一次性提醒，不错过新增内容）。
- **AI 歌曲信息识别**（DeepSeek，自配 API Key，运行时作为参数传入，源码不存任何密钥）。
- **音频输出路由**：扬声器 / 耳机 / 蓝牙切换；部分 OEM 上交给系统级媒体输出切换器处理，保证真实路由生效。

## 下载与构建

首个正式版本 `v1.0.0` 的源码与 tag 已公开。官方 Release APK 将随 GitHub Release 提供；在此之前，可从源码自行构建。

### 从源码构建（Debug，无需任何签名 / 密码）

```bash
git clone https://github.com/richenxiao/pickupmusic.git
cd pickupmusic
./gradlew assembleDebug        # Windows 下用 gradlew.bat
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

环境要求：JDK 17+、Android SDK 36（compileSdk / targetSdk）、最低支持 Android 12（minSdk 31）。仓库自带 Gradle Wrapper，克隆后无需全局安装 Gradle。

### 构建 Release（需自行配置签名）

仓库不含任何真实签名密钥与密码。要构建 Release，按 `keystore.properties.example` 自行准备你自己的签名：

1. 生成你自己的 keystore：
   ```bash
   keytool -genkey -v -keystore my-release.keystore -alias my-key \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. 复制 `keystore.properties.example` 为 `keystore.properties`，填入你的 keystore 路径与密码。
   （`keystore.properties` 与 `*.jks` / `*.keystore` 均在 `.gitignore`，不会被提交。）
3. 构建：
   ```bash
   ./gradlew assembleRelease
   ```

产物路径：`app/build/outputs/apk/release/app-release.apk`

> 作者的正式发布签名身份（keystore / 密码）绝不公开；fork 后请使用你自己的签名构建你自己的 Release。

## 技术栈

| 技术 | 用途 |
|------|------|
| Kotlin | 开发语言 |
| Jetpack Compose | UI 框架 |
| Material 3 | 设计系统 |
| AndroidX Media3 (ExoPlayer) | 音频播放引擎，后台播放 + MediaSession |
| Room / DataStore | 本地数据库 / 键值偏好 |
| Coroutines / Flow | 异步与响应式 |
| OkHttp / Gson | 网络请求 / JSON 解析 |
| AndroidX Palette | 专辑封面主色提取，驱动动态取色 |

完整版本号、依赖与构建细节见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 项目结构

```
PickUpMusic/
├── app/
│   ├── src/main/
│   │   ├── java/com/shiyin/music/
│   │   │   ├── data/
│   │   │   │   ├── ai/              # AI 服务（DeepSeek 元数据识别）
│   │   │   │   ├── colors/          # 封面主色提取（PaletteExtractor）
│   │   │   │   ├── normalize/       # 歌手名归一化
│   │   │   │   ├── recognition/     # 歌手头像抓取
│   │   │   │   ├── db/             # Room 数据库（实体、DAO、迁移）
│   │   │   │   ├── lyrics/         # 歌词解析与匹配（LRC / USLT / LRCLIB / 网易云）
│   │   │   │   ├── MediaScanner.kt  # 媒体库扫描
│   │   │   │   └── SettingsStore.kt  # DataStore 设置与最近播放
│   │   │   ├── playback/            # 后台播放服务、播放控制器、音频设备路由
│   │   │   ├── ui/
│   │   │   │   ├── components/      # 通用 UI 组件
│   │   │   │   ├── screens/         # 页面（首页 / 搜索 / 音乐库 / 播放器 / 歌词 / 设置 等）
│   │   │   │   └── theme/           # 主题与配色
│   │   │   ├── MainActivity.kt
│   │   │   ├── MainViewModel.kt
│   │   │   └── AppRoot.kt           # 导航根组件
│   │   ├── res/                     # 资源文件（含各档位应用图标）
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── gradlew / gradlew.bat            # Gradle Wrapper（仓库自带）
├── keystore.properties.example      # Release 签名占位模板
├── local.properties.example         # 本机 Android SDK 路径占位模板
├── DEVELOPMENT.md                   # 开发规范
├── CHANGELOG.md                     # 版本变更记录
├── LICENSE                          # MIT 许可证
└── README.md
```

> 真实签名文件（`*.jks` / `*.keystore`）、`keystore.properties`、`local.properties` 均在 `.gitignore` 中，绝不入库。

## 隐私说明

- 本地音乐扫描与播放均在设备本地完成。
- 网络访问仅用于：抓取歌手头像（MusicBrainz / Wikidata / iTunes）、调用你自配的 DeepSeek API 做元数据识别。
- 不会上传你的本地音乐文件或收听记录到任何服务器。

## 许可证

本项目基于 MIT 许可证开源。详见 [LICENSE](LICENSE)。

# 开发规范 (DEVELOPMENT)

本文档是 PickUpMusic（拾音）的工程开发规范，面向所有贡献者。日常 AI 协作规则另见仓库根 `CLAUDE.md`。

## 1. 项目目录

- 正式开发目录：`PickUpMusic/`（即本仓库根）。
- 旧目录 `shiyin/` 为历史私有仓库，不再作为开发主线，不对外公开。
- 命名空间 / applicationId：`com.shiyin.music`（保留，不改）。

## 2. 技术栈

| 项 | 版本 / 说明 |
|----|------|
| Kotlin | 2.1.21 |
| AGP | 8.10.1 |
| Gradle | 8.13（仓库自带 Wrapper） |
| Jetpack Compose BOM | 2025.05.01 |
| Material3 | 随 Compose BOM |
| Media3 (ExoPlayer) | 1.7.1 |
| Room | 2.7.1（KSP 2.1.21-2.0.1） |
| OkHttp | 4.12.0 |
| Gson | 2.11.0 |
| palette-ktx | 1.0.0 |
| mediarouter | 1.8.1 |
| reorderable | 3.1.0 |
| compileSdk / targetSdk | 36 |
| minSdk | 31（Android 12） |
| Java | 17 |

依赖仓库：默认走 Google / MavenCentral；`settings.gradle.kts` 顶部额外配置了腾讯云镜像（国内加速）。海外贡献者可注释掉镜像两行改用默认源。

## 3. 代码约定

- 语言：Kotlin。UI 全 Jetpack Compose，不写 XML 布局（除 Manifest / 资源外）。
- 包结构：`com.shiyin.music.{data,playback,ui,...}`，按职责分层。
- 异步：Coroutines + Flow；数据持久化用 Room + DataStore。
- 命名：类 PascalCase，函数 / 变量 camelCase，常量 UPPER_SNAKE。
- 注释密度：跟随周边代码风格；公共 API 写 KDoc；不罗列调试过程。

## 4. 分支规范

- `main`：仅放已验收、可发布的稳定版本，每次合并对应一次 GitHub Release。
- `develop`：日常开发主线，允许待验收状态，但必须可编译。
- `feature/<简述>`：单个大功能分支，完成后 PR 回 develop。
- 小修补可直接在 develop 提交。分支名英文小写 + 连字符，不用中文 / 空格。

## 5. 提交信息规范（Conventional Commits）

前缀：`feat:` / `fix:` / `refactor:` / `perf:` / `docs:` / `build:` / `chore:` / `test:`。

- 示例：`feat: 新增日语歌词振假名注音`、`fix: 扫描 FLAC 卡死的并发问题`。
- 禁用模糊描述：`update` / `modify` / `test` / `改一下` / `最新版` / `临时提交`。
- 一次 commit 只做一件语义内聚的事；较大改动在 body 补背景结论，不写调试流水账。

## 6. 版本号规范（SemVer）

- 格式：`MAJOR.MINOR.PATCH`，如 `1.0.0`。
- MAJOR：不兼容破坏性变更；MINOR：向下兼容的新功能；PATCH：向下兼容的缺陷修复。
- Git tag 带 `v` 前缀：`v1.0.0`。
- 不机械沿用旧的 `0.0.x`；以当前完成度定合理基线（见 `CHANGELOG.md`）。

## 7. versionName / versionCode 对应

- `versionName`：`app/build.gradle.kts` 的 `versionName`，与 Git tag / GitHub Release 三者一致（如 `1.0.0`）。
- `versionCode`：整数，每个发布版本递增。公式 `MAJOR*10000 + MINOR*100 + PATCH`（如 `1.0.0` → 10000），保证单调递增即可。
- 当前基线：`versionName = "1.0.0"`，`versionCode = 10000`。

## 8. Release 发布规范

仅当维护者明确确认"这个版本可以发布"后执行：

1. develop 合并到 main。
2. 在 main 打 tag：`git tag v1.0.0`，推送 `git push origin v1.0.0`。
3. GitHub Release 关联该 tag，说明含：新功能 / 修复 / 破坏性变更 / 已知问题 / 升级注意。
4. Release 资产上传对应 Release APK（由维护者用官方签名构建）。

- 临时开发 commit 不打 tag、不建 Release。

## 9. Debug / Release 构建规范

- Debug：`./gradlew assembleDebug`。无需任何签名 / 密码。产物 `app/build/outputs/apk/debug/app-debug.apk`。
- Release：`./gradlew assembleRelease`。需先配置签名（见第 10 节）。产物 `app/build/outputs/apk/release/app-release.apk`。
- 合并前自检：Debug 构建通过 + 工作区干净（无非预期文件被暂存）。
- Windows 下用 `gradlew.bat`。

## 10. 配置文件规范

| 文件 | 入库? | 用途 |
|------|------|------|
| `local.properties` | 否（gitignored） | 本机 Android SDK 路径。参考 `local.properties.example`。 |
| `keystore.properties` | 否（gitignored） | Release 签名四要素。参考 `keystore.properties.example`。 |
| `*.jks` / `*.keystore` | 否（gitignored） | 真实签名密钥文件。 |
| `*.example` | 是 | 占位模板，仅含 `YOUR_*` 占位。 |
| `.gitignore` | 是 | 忽略规则。 |
| `PRIVATE_SECURITY.md` | 否（gitignored） | 作者私密签名 / 密钥管理备忘。 |

Android Studio 首次打开项目会自动生成 `local.properties`；命令行构建者请自行复制 example 填写。

## 11. 密钥 / Secret 管理规范

- 真实签名密钥（`*.jks`）、签名密码、`keystore.properties`、`local.properties` 绝不入库。
- 仓库只提供 `.example` 占位模板，仅含 `YOUR_*` 占位，不含任何真实值。
- DeepSeek API Key 不在源码硬编码；运行时由用户在 App 设置页填入（`DeepSeekService` 以参数传入）。
- 任何外部 API Key / Token / 服务端凭据 / SSH key / Cookie 一律不入库。
- 个人开发机绝对路径、Windows 用户名路径不入库（文档中只用相对路径或 `./gradlew`）。
- 作者私密签名管理备忘见本地 `PRIVATE_SECURITY.md`（不入库）。

## 12. Pull Request 规范

- 分支：`feature/* → develop`；`develop → main` 仅在发布时。
- PR 描述：解决了什么问题 / 实现了什么功能（最终结论），引用相关 Issue。不写调试流水账。
- 合并前：`./gradlew assembleDebug` 通过；工作区干净。
- 不强制 squash，保留语义 commit。
- 不 `--force` 推已分享分支，不 `--no-verify` 跳 hook，不 amend 已推送 commit。

## 13. Issue 规范

- Bug：复现步骤、预期 / 实际、设备 / Android 版本、日志。
- Feature：动机、期望行为、是否影响既有功能。
- 标签：`bug` / `feature` / `enhancement` / `question` 等。

## 14. 测试规范

- 单元测试：`app/src/test/`，JUnit 4。当前覆盖模糊搜索等纯逻辑模块。
- 仪器测试：`app/src/androidTest/`（按需补充）。
- 合并前至少保证 `./gradlew assembleDebug` 通过；涉及纯逻辑改动补单元测试。

# 拾音 PickUpMusic — 项目工作规范

> 本文件是本项目的长期生效规则。Claude Code 每次在本仓库开工都须自动遵守，
> 无需用户反复提醒。如与用户当次明确指令冲突，以用户当次指令为准并事后同步本文件。

仓库地址：https://github.com/richenxiao/PickUpMusic

---

## 一、分支结构

- **main**：只存放验收满意、正式发布的稳定版本。每次合并进 main 都对应一次 GitHub Release。
  - 不直接在 main 上做日常改动。
  - main 上的每一个 commit 都应是"可发布"状态。
- **develop**：日常开发主线。所有还在迭代验证中的改动先合并到这里。
  - develop 允许处于"待验收"状态，但不允许编译失败。
- **feature/功能简述**：单个大功能开发时使用。
  - 命名示例：`feature/lyrics-furigana`、`feature/artist-album-list`。
  - 完成后通过 Pull Request 合并回 develop，合并后可删除该分支。
  - 体量小的改动可直接在 develop 上提交，不必强行开 feature 分支。

### 当前状态（2026-08 公开迁移基线）
- PickUpMusic 采用全新干净的 Git 历史，首个公开基线 commit = "经过安全审计的当前项目基线"，不复制旧 shiyin 仓库的历史。
- 应用本身已历经 v2 / v3 / v4 / v5 多轮内部迭代（见 CHANGELOG.md），功能稳定，但尚未发布正式公开版本、尚无 tag 与 GitHub Release。
- 自基线之后的新改动严格按本规范走分支 + PR 流程，不再在工作区堆积大量未提交存量。

---

## 二、命名规范

- **分支名**：英文小写 + 连字符，不使用中文与空格。
  - 示例：`feature/lyrics-furigana`、`fix/scanner-stuck-on-flac`、`develop`、`main`。
- **版本号**：遵循语义化版本规范（Semantic Versioning），格式 `主版本号.次版本号.修订号`。
  - 示例：`v3.3.0`。
  - tag 前缀带 `v`：`v3.3.0`，不是 `3.3.0`。
  - 主版本号：不兼容的破坏性变更；次版本号：向下兼容的新功能；修订号：向下兼容的缺陷修复。
- **Commit 信息**：采用 Conventional Commits 规范前缀，中文描述即可。
  - 前缀：`feat:` / `fix:` / `refactor:` / `docs:` / `chore:` / `test:` / `perf:` 等。
  - 示例：`feat: 新增日语歌词振假名注音`、`fix: 扫描 FLAC 卡死的并发问题`、`refactor: 拆分 LibraryScreen 歌手页`。
  - 一次 commit 只做一件语义内聚的事；跨多功能的改动拆成多个 commit。
  - 较大改动在 commit body 内补充背景说明，但不要罗列调试过程细节，只写最终结论。

---

## 三、开发与 Review 流程

- 日常开发在 **develop** 或对应的 **feature 分支**进行，**不直接在 main 上改动**。
- 每次较大功能改动完成后，通过 **Pull Request** 的方式合并回 develop。
  - 即使目前只有一个人 review，也必须走 PR 流程留痕，方便后续追溯每次改动的讨论与验收记录。
  - 小修小补（typo、注释、单行修复）可直接在 develop 提交，不强求 PR。
- **PR 描述要求**：
  - 清晰说明这次改动**解决了什么问题 / 实现了什么功能**（最终结论）。
  - 不需要罗列调试过程细节。
  - 如有构建验证，注明已通过 `./gradlew assembleDebug`。
- **合并方向**：
  - `feature/* → develop`：日常合并。
  - `develop → main`：仅当确认达到满意的稳定状态时，由用户拍板后执行。
- **合并前自检**：
  - `./gradlew assembleDebug` 必须通过（命令见末尾"常用命令"）。
  - 工作区干净，无 `node_modules/` 等不该入库的内容被暂存（已被 .gitignore 忽略）。
- **不要**：不要用 `git push --force` 改写已分享的分支历史；不要 `--no-verify` 跳过 hook；不要 amend 已推送的 commit。

---

## 四、Release 发布流程

只有用户明确说"这个版本可以发布了"后，才执行下列流程。**绝不在未经用户确认时自行打 tag 或建 Release。**

1. 确认 develop 处于满意稳定状态，用户已拍板。
2. 将 develop 合并到 main（PR 或 fast-forward，视情况）。
3. 在 main 上创建对应的语义化版本 tag：`git tag v3.3.0`。
4. 推送 tag：`git push origin v3.3.0`。
5. 基于 tag 创建 GitHub Release。
6. 编译 Release APK：`./gradlew assembleRelease`（需先配置 keystore.properties，见 DEVELOPMENT.md；若暂无签名则用 `./gradlew assembleDebug`）。
7. 将编译好的 APK 作为 Release 资产上传。
8. **Release 说明**：
   - 清晰列出**相对上一个正式版本**的主要变更。
   - **功能新增**与**问题修复**分开列。
   - 引用相关 PR 编号。

### 当前发布状态
- 尚无任何正式版本 tag / Release。
- 第一个正式版本（预计为 `v2.0.0` 或由用户指定）发布时，Release 说明需包含 v2/v3/v4 三个大版本的累积变更。

---

## 五、构建与常用命令

构建工具：Gradle 8.13。仓库内置 Gradle Wrapper（`gradlew` / `gradlew.bat` + `gradle-wrapper.jar`），克隆后无需全局安装 Gradle，直接用 wrapper 即可。若本机已安装 Gradle 8.13+，也可直接用 `gradle` 调用。

- **Debug 构建（合并前自检）**：
  ```
  ./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
  ```
- **Release 构建**（需先配置签名，见 DEVELOPMENT.md「Release 签名」一节）：
  ```
  ./gradlew assembleRelease
  ```
- **APK 产物路径**：
  - Debug：`app/build/outputs/apk/debug/app-debug.apk`
  - Release：`app/build/outputs/apk/release/app-release.apk`

---

## 六、不可触碰区域（历史约定）

以下文件/模块在历次重构中约定不改动，开工前须确认当前任务不涉及：

- **PlayerScreen.kt 的封面渲染代码**：动态取色与封面展示逻辑已定型，除非任务明确要求否则不动。
- **PaletteExtractor.kt 的评分逻辑**：Spotify 加权评分取色算法已定型，除非任务明确要求否则不动。

如任务确实需要改动以上区域，须在改动前与用户确认范围。

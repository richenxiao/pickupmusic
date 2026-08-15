# 变更记录 (CHANGELOG)

本仓库（PickUpMusic）采用全新干净的 Git 历史，首个公开基线为 `v2.0.0`。
在此之前的 v2 / v3 / v4 / v5 为内部迭代，此处按大版本汇总其累积能力作为基线背景。
后续变更严格按 [Semantic Versioning](https://semver.org/) 与 Conventional Commits 记录。

## [v2.0.0] — 2026-08-15 · 公开迁移基线

首个公开基线版本，经安全审计后从内部仓库迁移而来，采用全新 Git 历史。

### 累积能力（来自 v2–v5 内部迭代）

#### 播放与音频
- 本地音乐扫描与播放（多格式），Media3 ExoPlayer 后台播放 + 前台服务 + MediaSession。
- 通知栏控制、自定义播放队列、循环 / 随机、无缝播放（gapless）。
- 音频设备路由：扬声器 / 耳机 / 蓝牙切换，蓝牙连接感知。
- v5.2：系统级媒体输出切换器（SystemOutputSwitcherDialogController），兼容 ColorOS 等 OEM（app 层 setPreferredDevice 不生效时由系统执行真实路由）。

#### 歌词
- LRC 歌词解析与同步滚动、USLT 歌词、手动导入并持久化。
- v3：歌词页随封面动态取色联动，白字 + WCAG 4.5 对比度。

#### 音乐库与浏览
- 按歌曲 / 专辑 / 歌手分类浏览，搜索。
- 歌手详情页、歌手专辑区块（横向前 4 张 + 全部列表）、按 iTunes 发行日期排序、单曲 / EP / 专辑自动分类。
- 歌手归一化（合并同人多名字），支持事后撤销。
- v2：歌手 schema 完整重构，歌手头像 URL 缓存到 Room，统一 OkHttp；MusicBrainz → Wikidata → iTunes 三级回退抓头像。

#### 智能 & 个性化
- AI 歌曲信息识别（DeepSeek，用户自配 API Key，运行时参数传入，源码无硬编码 key）。
- v3：Spotify 加权评分取色算法从专辑封面提取主色，沉浸式播放界面与歌词页联动（WCAG 4.5 HSV 调整，深灰兜底）。
- 最近播放（持久化，最多 12 首）、收听统计（按周聚合）、"你的更新"（新扫描专辑提醒）。

#### 界面与主题
- Material 3 + 动态主题、旋转唱片动画、深色模式、自适应图标 + Android 13 单色主题图标。
- v5.2：拖拽排序改用 reorderable 库（sh.calvin.reorderable:3.1.0），替代手写状态机。
- v4.3：模糊搜索单元测试。

### 工程基线变更
- 从私有 `shiyin` 仓库迁移至公开 `PickUpMusic` 仓库，采用全新 Git 历史，不复制旧历史。
- Release 签名配置改为 `keystore.properties`（gitignored）+ `keystore.properties.example` 占位模板，真实密钥与密码绝不入库。
- 仓库内置 Gradle Wrapper（`gradlew` / `gradlew.bat` + `gradle-wrapper.jar`），克隆即可 `./gradlew assembleDebug`。
- 补齐 `DEVELOPMENT.md` / `CHANGELOG.md` / `LICENSE`(MIT) / `README.md` 等公开文档。

### 已知问题
- 旧 `shiyin` 仓库历史中曾提交过签名密码（已在当前基线移除；历史轮换详见本地 `PRIVATE_SECURITY.md`）。
- 部分 OEM 上设备切换行为依赖系统 Output Switcher（已适配）。

### 升级注意
- 全新克隆即可 Debug 构建，无需任何密码。
- Release 构建者需自行生成 keystore 并配置 `keystore.properties`。

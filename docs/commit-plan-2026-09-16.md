# 提交计划（2026-09-16 累积改动）

> 原方案排的是「翻译功能 5 个 commit」。实际这轮累积的改动**超出了原计划**——
> 多了"迷你播放条横向分页"整条线，以及翻译落地的持久化与成功率修复。
> 本文按**"一个 commit 解决一个问题"**重排，供你逐条确认。
> **未经你同意不会提交/推送。**

---

## 0. ⚠️ 先决条件：两个坑，动手前必须处理

### 0.1 SPIKE 文件绝不能提交

| 文件 | 内容 | 处置 |
|---|---|---|
| `app/src/main/AndroidManifest.xml` | +5 行：注册 `SpikeScrubActivity`，注释写明「⚠️ SPIKE 临时(SharedTransitionLayout scrub 验证)——验证完删除」 | **排除** |
| `app/src/main/java/com/shiyin/music/SpikeScrubActivity.kt` | 未跟踪的新文件 | **排除** |

> 这是你定的约束："Spike 文件（SpikeScrubActivity.kt + AndroidManifest.xml）留在工作区不提交，
> **必须不能**被提交"。所以 `git add -A` 会误伤，必须逐文件 add。

### 0.2 `MainViewModel.kt` 混了两拨改动，必须**分块暂存**

```
@@ -364,0 +365,7 @@     ← ArtCache 160 桶预热（属于"迷你播放条分页"那条线）
@@ -2993,0 +3001,2 @@   ← 本次：deleteLyrics 清译文
@@ -2998,0 +3008,3 @@   ← 本次：deleteLyrics 清译文（注释）
@@ -3099,0 +3112,200 @@ ← 本次：翻译整块
```

用 `git add -p app/src/main/java/com/shiyin/music/MainViewModel.kt` 分两次加：
`+365,7` 进 Commit 1，其余进 Commit 6。**直接 `git add` 整个文件会把两件事混进一个 commit。**

### 0.3 未跟踪的文档/目录（自行决定）

- `docs/handoff-2026-09-16-*.md`（3 份）、`docs/pending/`、`docs/req*.md`、`docs/pending-requirements-2026-09-12.md`
  —— 建议随相关 commit 一起提交（它们是这批工作的说明），或统一一个 `docs:` commit。
- `.workbuddy-ai/` —— 建议**不要**提交（工具的工作目录）。

---

## 1. 建议的提交序列

### Commit 1 — 迷你播放条横向分页（含本轮闪退修复）
```
fix: 迷你播放条横向分页切歌——修重复 key 闪退、同专辑封面跟手、跟手不精确
```
**文件**：`ui/AppRoot.kt`、`playback/PlayerController.kt`（`wrappedNextTrackId`）、
`MainViewModel.kt` **仅 `+365,7` 那块**（160 桶封面预热）
**解决的问题**（按重要性）：
1. **闪退**：三个槽位的 `key` 只用 `track.id` → 上一首/下一首回绕到同一首时同层重复 key，
   Compose 抛 `IllegalArgumentException` 崩溃。改 `key(slotIndex, pageTrack.id)`。
2. 同专辑封面仍跟手移动（需求要求"必须完全稳定"）→ 拆出独立封面轨道，同专辑位移恒 0。
3. `offsetX.value` 在组合期读 → 拖动逐帧全量重组；每帧 `scope.launch{snapTo}` → 跟手不精确。
   改为 `graphicsLayer` 延迟读 + 直接写 float state。
4. 中断的编辑留下的**括号不平衡**（文件当时编译不过，33 条错误）。

### Commit 2 — 翻译：离线引擎
```
feat: 歌词翻译离线引擎——句组拼接/拆回对齐/错位兜底
```
**文件**：`data/translate/LyricsTranslationEngine.kt` + `LyricsTranslationEngineTest.kt`(17)
**核心纪律**：宁可缺行，不可错行。

### Commit 3 — 翻译：AI 线上协议
```
feat: 歌词翻译 AI 协议——行号协议 + 缺行补齐 + 宽容解析
```
**文件**：`data/translate/LyricsTranslationProtocol.kt` + 测试(23)
**要点**：行号协议让"模型合并行"表现为**可检测的缺行号**而非不可检测的整体错位；
解析宽容 markdown 装饰（模型爱给行号加粗，不剥会整篇解析失败）。

### Commit 4 — 翻译：AI 编排 + VM 接线
```
feat: 歌词翻译 AI 编排与 VM 接线——整歌调用/缺行补齐/取消/token 入账
```
**文件**：`data/translate/LyricsTranslationPipeline.kt` + 测试(17)、
`ui/components/LyricsTranslationState.kt` + 测试(12)、`MainViewModel.kt` **仅翻译块**、
`data/translate/LyricsTranslationPersistenceTest.kt`(5)
**要点**：LLM 以构造函数注入 → 整条链路可假实现单测；
**取消必须原样穿透 `CancellationException`**（被 `runCatching` 吞掉会继续烧 token）。

### Commit 5 — 翻译：界面
```
feat: 歌词本「譯」按钮与译文渲染
```
**文件**：`ui/screens/LyricsScreen.kt`
**要点**：与「あ」同 38dp 平行；进行中=实心圆+顺时针加载弧，完成=圆角方块+纯文字 AI；
译文缺行留白；`weight` 从 Row 移到外层 Column；
**译文出现会让每行变高 → 跟行 effect 的键加上"译文是否显示"以重新居中**；
旋转动画只在进行中驱动帧（不用 `rememberInfiniteTransition`）。

### Commit 6 — 翻译：持久化 + 清理入口
```
feat: 翻译结果持久化——新表 22/自动恢复/两个缓存清理入口
```
**文件**：`data/db/AppDatabase.kt`（实体 + DAO + `MIGRATION_21_22` + version 22）、
`data/db/BackupManager.kt`（译文随备份）、`MainViewModel.kt` 的 `deleteLyrics` 那两块、
`data/db/BackupDataRoundTripTest.kt`(+2)
**要点**：与振假名同键法 `(mediaId, lyricsHash)`；译完即存、进歌词本自动恢复；
按钮只管显示/隐藏（数据持久化）；编辑面板加「删除振假名缓存」「删除翻译缓存」。

### Commit 7 — 翻译：成功率
```
fix: 翻译成功率——解析宽容/网络重试/超时与 token 预算/可诊断失败原因
```
**说明**：如果你希望 Commit 3 就带上解析宽容，这条可以只留后三项，或整体并入 Commit 4。
**要点**：`retries` 0→1（息屏后死连接复用是项目实测最常见失败）、`callSec` 60→90、
每行 token 24→32（给思考型模型的 reasoning 留余量）、失败文案带上 `LlmClient.lastError`。

### Commit 8（可选）— 文档
```
docs: 迷你播放条分页与歌词翻译的落地交接
```
**文件**：`docs/handoff-2026-09-16-miniplayer-paging.md`、`docs/handoff-2026-09-16-lyrics-translation-frontend.md`

---

## 2. 建议合并/拆分的判断

- **想少几条**：Commit 2+3+4 合成一条 `feat: 歌词翻译数据层（离线引擎 + AI 协议与编排）`，
  7 条压到 4 条。
- **想更细**：Commit 5 可拆成"按钮"与"译文渲染"两条（各自可独立装机看效果）。
- **不建议合并**：Commit 1（分页）与 Commit 6（DB 版本 22）——
  前者是 UI 行为修复，后者动数据库版本，混在一起出问题时不好定位。

## 3. 提交前验证（已过）

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | **253 用例全过**（0 失败 / 0 错误 / 0 跳过） |
| `:app:assembleRelease` | BUILD SUCCESSFUL，APK 28,600,579 B |
| DB 迁移 21→22 | 迁移 SQL 经真实 SQLite 实跑，`PRAGMA table_info` 与 Room 生成语句**逐列一致** |
| APK 新鲜度 | 从 DEX 搜到本轮新增字符串（`行号不要加粗`/`歌词翻译`/`删除翻译缓存`） |

## 4. 仍未做 / 仍待拍板

- **真机验证**（闪退、按钮动画、持久化、两个删除按钮）——**这是提交前的最后一道门**。
- **离线翻译（ML Kit）**：未接线。等 APK 体积决策（①87.5MB ②只留 arm ≈54.5MB
  ③仅 arm64 ≈43.4MB ④放弃离线）。依赖已回退，封装暂存 `docs/pending/MlKitLyricsTranslator.kt.txt`。
- **设置页翻译项 / 语言包管理**：未做（依赖上面那条决策）。
- **"默认 AI 校验"**：仍未确认指什么。

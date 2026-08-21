# 日语歌词 Furigana 注音功能 · 技术设计评估报告

> 本报告为**设计评估**，不含任何代码改动。待用户确认设计方案后再进入开发。
> 评估基于对现有歌词系统真实代码结构的调研（见文末「附录：现有歌词系统结构要点」）。

---

## 〇、需求与交互总览（含用户补充）

1. **前提**：歌词源（LRCLIB / 网易云 / USLT 内嵌 / 手动导入）已提供**原版日文歌词**。非日语歌词一律不出现注音功能。
2. **触发方式**（二选一并存）：
   - **手动**：歌词本页面左下角放一个「あ」按钮，点击后对当前日文歌词执行注音。默认不标注。
   - **自动**：设置页提供「日语歌词默认开启振假名」开关，开启后日语歌词加载完成即自动注音，无需每次点按钮。
3. **适用范围**：仅日语歌。中文、英文、纯乐器等歌词**完全不显示「あ」按钮、不触发注音**。
4. **核心约束**：原始 LRC 文本一个字都不改；时间戳、切源、导入、高亮等既有逻辑零影响。

---

## 一、可行性结论

**可行。** Android + Compose 能承载一个纯 Java 的日语形态素解析器（推荐 Kuromoji），离线运行；Compose 可用自定义 `Layout` 渲染振假名（假名小字位于汉字上方）。主要代价是词典体积（约 20–50 MB 量级，见第四节）与歌词文学读法的识别准确率（见第六节，需人工修正兜底）。

不可行/不推荐的部分：
- 纯在线 API 注音——与 App「不上传歌词」的隐私立场冲突，且离线不可用，排除。
- MeCab 原生库方案——需交叉编译 native 库 + 大词典，集成与 APK 体积成本高，作为备选而非首选。

---

## 二、技术路线选型

| 方案 | 类型 | 体积(粗估) | 准确率 | Android 集成 | 离线 | 评估 |
|------|------|-----------|--------|-------------|------|------|
| **Kuromoji (Lucene)** | 纯 Java | ~23 MB(IPADIC) | 中上 | 直接引 jar，无 native | ✅ | **首选** |
| Sudachi | 纯 Java | ~50 MB(系统辞书) | 较高 | 可引 jar | ✅ | 备选（准确率更高但体积更大） |
| MeCab + IPA | C++ native | ~50 MB+ | 高 | 需交叉编译 + JNI | ✅ | 不推荐（集成重） |
| 在线 API(Goo/Yahoo等) | 网络 | 0 | 较高 | HTTP | ❌ | **排除**（隐私+离线） |
| 自定义词典 | 叠加层 | 视量级 | 提升专名/文学读法 | 配合上述引擎 | ✅ | **必配**（修正专名与读法） |

### 推荐：Kuromoji + 用户可编辑自定义词典

- Kuromoji（`org.apache.lucene:lucene-analyzers-*` 或独立 `kuromoji-ipadic` artifact）纯 Java，无需 native，能给出每个 token 的**读音（片假名）**与词性。
- 对**固有名词 / 歌曲专名 / 文学当て字**识别薄弱，叠加一个**用户可编辑的自定义词典**（Room 存，覆盖/补充引擎结果）做修正。这也是人工修正的落点（见第六节）。
- 词典体积较大，建议作为**可选模块**（开启 furigana 才下载/载入），或用 split APK / 按需下载，避免拖大默认 APK。
- **集成需先做一次 spike**：验证所选 Kuromoji artifact 在 Android（minSdk 31、Java 17、R8/AGP 8.10）下能正常 `Tokenizer` 并产出读音，再据此定稿依赖坐标。

> 之所以不选 Sudachi 作首选：其词典体积更大、artifact 复杂度更高，而歌词注音对准确率的要求可由"置信度门控 + 人工修正"弥补，Kuromoji 已够用。若后续实测 Kuromoji 误判率不可接受，再升级到 Sudachi。

---

## 三、与现有歌词系统的兼容性设计（最重要）

### 设计总原则

**furigana 是叠加层，不是修改层。** 原始 LRC 从获取到入库到渲染全程不动一个字符。

现有链路（调研结论）：
```
LyricsFetcher/UsltReader/import → raw String
  → LrcParser.parse(raw) → ParsedLyrics(List<LyricLine>)
  → LoadedLyrics(raw, parsed, source, kind, saved, offsetMs)
  → currentLyrics state → LyricsScreen 渲染 line.text
持久化：Room 表 saved_lyrics，一首歌存一整条 LRC 原文 String，PK=mediaId
```

关键事实与对应对策：

| 既有事实 | 对 furigana 的意义 | 对策 |
|---------|-------------------|------|
| `LyricLine(timeMs, text)` 只有纯 String（`LrcParser.kt:3-11`） | 渲染层拿到的是裸文本，无结构化分段 | 不改 `LyricLine`；在渲染层把 `Text(line.text)` 换成能查注音并渲染振假名的 `RubyText` |
| `LoadedLyrics.raw` 全程保留原始 LRC（`MainViewModel.kt:54`） | 原文从未丢弃 | 注音基于 `parsed.lines[i].text`（显示文本）计算，不碰 raw |
| `SavedLyricEntity.lyrics` 存整条 LRC（`AppDatabase.kt:22`） | "一首歌一条"现成实体 | **新增并行表** `furigana_overlays`（同样 mediaId PK），不动 `lyrics` 字段 |
| 内存缓存 `savedLyricsMap`（`MainViewModel.kt:97,264`） | 既有"Room→内存 Map→渲染查 Map"同构模式 | 仿照新增 `furiganaMap: Map<Long, FuriganaOverlay>`，命中即用、不重算 |
| 渲染 `Text(line.text)`（`LyricsScreen.kt:185-190`） | 唯一注入点 | 替换为 `RubyText(line.text, ruby = furiganaMap[mediaId]?.lines?.get(line.text))`；无注音时退化为普通文本，**非日语歌词渲染与改动前完全一致** |
| 高亮 `i <= activeI`（`LyricsScreen.kt:187`） | 只读 timeMs 与索引 | `RubyText` 仍按 `i <= activeI` 选色，高亮逻辑零改动 |
| 多时间标签同行会拆成多行（`LrcParser.kt:67-69`） | 同文本多行 | 同一 `line.text` 查到同一注音，天然适用 |
| `syncLyricLine` 会重排行序（`MainViewModel.kt:1617-1643`） | 行索引会变 | **以 `line.text` 作为注音匹配键，而非行索引**，重排不影响映射 |
| 切源/导入/删除会换 raw（`rematch*` / `importLrcContent` / `deleteLyrics`） | 旧注音 key 失配 | 这些操作触发**失效该 mediaId 的注音**（删 Room 行 + 清内存 Map），按需重算 |

### 数据结构设计

#### 1. Room 新增表（与 `saved_lyrics` 并列，独立）

```kotlin
@Entity(tableName = "furigana_overlays")
data class FuriganaOverlayEntity(
    @PrimaryKey val mediaId: Long,
    val payload: String,        // JSON，结构见下；与 saved_lyrics.lyrics 同构（一首歌一条 String）
    val engine: String,         // "kuromoji" / "sudachi" / "manual"
    val manuallyEdited: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
```

`payload` JSON 形态（以 `line.text` 为 key，存该行的振假名分段）：
```json
{
  "version": 1,
  "lines": {
    "明日に向かって歩く": [
      {"s": "明日", "r": "あした", "c": 2},
      {"s": "向", "r": "む", "c": 0},
      {"s": "かって", "r": null, "c": 0},
      {"s": "歩", "r": "ある", "c": 0},
      {"s": "く", "r": null, "c": 0}
    ]
  }
}
```
- `s` = surface（原文片段），`r` = reading（平假名/片假名，纯假名片段 `r=null` 表示无注音），`c` = 置信度（0 低 / 1 中 / 2 高，见第六节）。
- key 用 `line.text`（渲染显示文本），与渲染层取值同源，匹配稳定。
- 整条 JSON 存一行，镜像 `saved_lyrics` 的"一首歌一 String"设计，风格统一、迁移简单。

#### 2. 内存模型

```kotlin
data class FuriganaOverlay(
    val mediaId: Long,
    val lines: Map<String, List<RubySegment>>,   // key = line.text
    val manuallyEdited: Boolean,
)
data class RubySegment(val surface: String, val reading: String?, val confidence: Int)
```
内存 Map：`furiganaMap: Map<Long, FuriganaOverlay>`，由 DAO Flow 在 init 里 prime，仿 `savedLyricsMap`。

#### 3. 渲染层 Composable（新增，不改 LyricLine）

`RubyText(text, ruby: List<RubySegment>?, color, style, ...)`：
- `ruby == null` 或空 → 直接 `Text(text, ...)`，与现状逐字一致（非日语、未注音、注音未就绪都走这条）。
- `ruby` 非空 → 用自定义 `Layout`/`AnnotatedString` 渲染：汉字 surface 正常字号，reading 以小字置于其上方（振假名）。颜色仍由调用方按 `i <= activeI` 传入。

### 各既有流程的影响清单

| 流程 | 入口 | 对 furigana 的处理 | 是否破坏既有 |
|------|------|-------------------|-------------|
| 加载歌词 `loadLyricsFor`（`MainViewModel.kt:1427-1484`） | saved→USLT→sidecar→online | 若日语且开启自动注音 → 触发 `ensureFurigana`；手动模式 → 仅备好注音缓存供按钮调用 | 否 |
| 切换来源 `rematchNextSource`/`rematchSource`（`1493-1513`） | 换 raw | **失效旧注音**（删 Room+Map），新 raw 加载后按需重算 | 否 |
| AI 兜底 `rematchWithAI`（`1516-1537`） | 换 raw | 同上 | 否 |
| 手动导入 `importLrcContent`（`1599-1606`） | 仅内存 raw | 失效旧注音；导入若是日文，按需计算 | 否 |
| 保存 `saveLyrics`（`1645-1652`） | 写 saved_lyrics | 不写注音（注音独立表，独立时机保存） | 否 |
| 删除 `deleteLyrics`（`1654-1662`） | 删 saved_lyrics | **同步删 furigana_overlays 行**，避免孤儿 | 否 |
| 时间戳同步 `syncLyricLine`（`1617-1643`） | 改 timeMs + 重排 | 注音按 text 匹配，重排不影响 | 否 |
| 时间偏移微调 `bumpLyricsOffset`（`1608-1611`） | 改 offsetMs | 注音无关 | 否 |
| 时间高亮 `activeIndex`（`LrcParser.kt:26`） | 读 timeMs | 注音不动 timeMs | 否 |

> 结论：原始 LRC、时间戳、切源、导入、高亮、中英文歌词渲染**全部零影响**。furigana 是纯叠加层，且可随时整体关闭（删表/关开关即回到现状）。

---

## 四、识别准确率分析

### 难点

日语歌词里高频出现：
- **多音字**：明日（あした/あす/みょうにち）、生（せい/しょう/なま/い/う）、人（ひと/じん/にん）、一日（いちにち/ついたち）、彼（かれ/かの）。
- **文学读法 / 当て字**（歌词特多，分析器最容易错）：貴方→あなた、此処→ここ、幾度→いくたび、蒼→あお、曙→あけぼの、私→わたし/あたし。这些在普通 IPADIC 里常以"常规读音"存在，歌词语境的文学读法**不在词典主读法里**。
- **专名**：歌手名、曲名、地名、角色名，往往不在词典里，落到 unknown 处理，读音可能缺失或错。

### 预期误判率（定性）

- 常见词、常规读法：高准确。
- 多音字：中等，依赖词性/上下文，Kuromoji 基于词典与前后 token 轻量消歧，约能命中主读法。
- 文学读法 / 当て字：**误判率较高**，这是歌词注音的固有短板，必须靠人工修正兜底，不能承诺"全自动无误"。

### 置信度机制（降低自动误判的外显）

Kuromoji 不直接给 per-token 置信度，用**启发式分级**：
- `c=2 高`：词典内已知词且该词仅有单一读法 → 自动显示。
- `c=1 中`：词典内但有多读法（消歧后取一）→ 可显示但可标黄/可纠。
- `c=0 低`：OOV（unknown，如专名、当て字）→ **默认不自动显示注音**，仅在用户点「あ」展开"待确认"时呈现候选，引导人工选/输。

这样"自动模式"只外放高置信注音，低置信藏起来待人工，显著降低"读错的假名直接糊在用户脸上"的概率。

### 人工修正

- 入口：点「あ」后进入注音编辑态，逐行可点 segment，从候选读法里选或手输平假名。
- 存为 `manuallyEdited=true`，覆盖引擎结果；后续不再被自动重算覆盖。
- 用户修正可沉淀进自定义词典（可选），逐步提升后续歌曲的命中。

> 诚实的结论：歌词注音是"便利功能"而非"完美功能"。常规读法自动覆盖；文学读法与专名靠人工修正兜底。产品文案与预期管理要照此设定，不要承诺 100% 准确。

---

## 五、性能与缓存设计

### 不重算：一首歌算一次

仿 `savedLyricsMap` 的同构模式：
- 首次需要注音时（点「あ」或自动模式加载完成）→ IO 协程跑一次全曲 tokenize → upsert `furigana_overlays` → 更新内存 `furiganaMap` → 重组 UI。
- 之后每次打开这首歌：直接查内存 Map，**零计算、零网络**。

### 首次解析流程（非阻塞）

```
点「あ」/自动触发
  → 查 furiganaMap[mediaId]：命中即用，结束
  → 未命中：启动 Dispatchers.IO 协程
    → 日语门控校验（见下）
    → Kuromoji tokenize 所有 parsed.lines 的 text
    → 合并自定义词典覆盖
    → 生成 RubySegment[] + 置信度
    → upsert Room + 更新内存 Map
    → UI 重组，注音淡入
```
- 歌词**立即显示**（纯文本），注音算好后再叠加，不卡首屏。
- 全曲 tokenize（~30–80 行、每行 10–40 字）：毫秒到低两位数毫秒级，可接受。
- 首次 tokenization 要付一次词典加载（~100–300ms），放在后台线程，可在 App 空闲时预热。

### 日语门控（先挡掉非日语，省算力 + 不扰用户）

在触发注音前做**廉价启发式**判定当前歌词是否日文：
- 含平假名/片假名 → 判日文（中文歌词无假名）。
- 仅汉字无假名 → 判中文，**不显示「あ」按钮、不注音**。
- 纯英文/数字/符号 → 不注音。
此判定极快（字符范围扫描），在 `ensureFurigana` 入口与「あ」按钮显隐处都用它。

### 缓存失效时机（避免失配）

- 切源 / 导入 / AI 兜底换 raw → 失效该 mediaId 注音（删 Room + Map）。
- 删除歌词 → 同步删注音。
- 仅当 `raw`/`line.text` 变化时才失效；纯 offset / 时间戳调整不失效。

### 词典体积与离线

- Kuromoji 词典 ~20+ MB。建议：
  - 作为**可选功能模块**，用户首次开启 furigana 时提示下载/载入，或走 split APK（`abi`/`density` 之外加一个"dictionaries"或按需模块）。
  - 或压缩内置，仅对启用者计体积。
- 完全离线，无任何网络。

---

## 六、交互与 UI 设计（按用户补充）

### 「あ」按钮

- 位置：歌词本页（`LyricsScreen.kt`）左下角。
- 显隐条件：**仅当当前歌词通过日语门控**才显示；非日语歌该按钮不存在，功能不存在。
- 行为：
  - 默认（未点）：歌词正常显示，无注音。
  - 点击：触发 `ensureFurigana`，注音就绪后叠加显示；按钮态切换为"已开启"（如高亮/あ变为实心）。
  - 再点：关闭注音显示（注音数据仍保留在缓存/Room，下次秒开）。

### 设置开关：默认开启

- 设置页一项「日语歌词自动标注振假名」（存 DataStore，与现有 `SettingsStore` 同构）。
- 开 → 日语歌词加载完成自动 `ensureFurigana`，无需点按钮，「あ」按钮态为"已开启"，仍可点关。
- 关 → 维持手动点按钮触发。

### 两者关系

- 手动「あ」是**会话级开关**（当前这首歌注音开/关）。
- 设置「默认开启」是**全局偏好**（决定日语歌词加载后是否自动算+显示）。
- 两者都受日语门控约束：非日语歌无论如何都不出现注音。

---

## 七、涉及的模块清单

| 层 | 新增/改动 | 说明 |
|----|----------|------|
| `data/furigana/FuriganaAnalyzer.kt` | 新增 | 封装 Kuromoji，`text → List<RubySegment>`，含置信度分级 |
| `data/furigana/JapaneseDetector.kt` | 新增 | 假名比例门控，判日/中/英 |
| `data/furigana/FuriganaEngine.kt` | 新增 | 引擎接口（便于 Kuromoji↔Sudachi 切换、mock 测试） |
| `data/db/AppDatabase.kt` | 改动 | 加 `FuriganaOverlayEntity` + DAO（`upsert/get/delete`），version 10→11，写 migration（`CREATE TABLE furigana_overlays`） |
| `MainViewModel.kt` | 改动 | 加 `furiganaMap` 状态、`ensureFurigana(mediaId)`、`invalidateFurigana(mediaId)`；在 `rematch*`/`import`/`delete` 处接失效钩子；暴露注音状态给 UI |
| `data/SettingsStore.kt` | 改动 | 加「默认开启振假名」DataStore 项 |
| `ui/components/RubyText.kt` | 新增 | 振假名渲染 Composable（自定义 Layout 或 AnnotatedString） |
| `ui/screens/LyricsScreen.kt:185-190` | 改动 | `Text(line.text)` → `RubyText(line.text, ruby)`；加左下角「あ」按钮（受门控显隐） |
| `app/build.gradle.kts` | 改动 | 加 Kuromoji 依赖（坐标待 spike 定稿） |

### 不可触碰区域核查（CLAUDE.md 约定）

- `PlayerScreen.kt` 封面渲染 —— **不涉及**。
- `PaletteExtractor.kt` 评分逻辑 —— **不涉及**。
本方案仅动 `LyricsScreen.kt` 的文本渲染与新增左下角按钮，不在两处禁区内。

---

## 八、风险与后续开发步骤

### 风险

1. **Kuromoji 在 Android 的可用性**：artifact 坐标、与 R8/minify（当前 `isMinifyEnabled=false`，暂无混淆问题，但未来开启需验 keep 规则）、词典体积。→ **需先做集成 spike**。
2. **歌词文学读法误判率**：当て字/古语读法自动准确率有限。→ 置信度门控 + 人工修正兜底，文案不承诺完美。
3. **`line.text` 作 key 的边界**：若 LrcParser 对同一原文在不同时机产出空格/全半角差异，key 可能失配。→ 以渲染层实际 `line.text` 为 key 源（同源一致），并在 tokenize 前对 key 做统一 trim/规格化；调研显示 parse 行为确定性，风险低。
4. **Room 迁移**：version 10→11 加表，写好 `Migration` 避免清库。新建表无数据迁移负担。

### 后续开发步骤（待用户确认设计后）

1. **feature 分支**：`feature/lyrics-furigana`（大功能，走 PR 回 develop，见 CLAUDE.md）。
2. **Spike 阶段**：验证 Kuromoji artifact 在 `assembleDebug` 下可 `tokenize` 出读音，定稿依赖坐标与词典加载方式；出最小 PoC（一个文本框输入日文→显示振假名）。
3. **数据层**：`FuriganaOverlayEntity` + DAO + Migration(v11) + 内存 Map prime。
4. **分析层**：`FuriganaAnalyzer` + 置信度分级 + 自定义词典覆盖 + `JapaneseDetector` 门控。
5. **渲染层**：`RubyText` Composable + `LyricsScreen` 替换 + 「あ」按钮 + 设置开关。
6. **失效钩子**：在 `rematch*`/`importLrcContent`/`deleteLyrics` 接 `invalidateFurigana`。
7. **自检**：`./gradlew assembleDebug` 通过；日/中/英三类歌词回归（中文不出现按钮、英文不注音、日文手动/自动两路径正常）。

---

## 附录：现有歌词系统结构要点（调研依据）

- 数据模型：`LyricLine(timeMs, text)`、`ParsedLyrics(lines, synced, globalOffsetMs)`（`LrcParser.kt:3-11`）；`LoadedLyrics(mediaId, raw, parsed, source, kind, saved, offsetMs)`（`MainViewModel.kt:52-68`）。`raw` 全程保留原始 LRC。
- 解析：`LrcParser.parse(raw)`（`LrcParser.kt:50`），多时间标签同行拆多行（`67-69`），未同步行 `timeMs=null`（`86-90`）。
- 获取：`LyricsFetcher` LRCLIB+网易云级联返回原始 String（`LyricsFetcher.kt:16-117`）；USLT 内嵌 `UsltReader.read`（`UsltReader.kt:11-116`）；AI 兜底 `DeepSeekService` 返回 `MM:SS 歌词`。
- 切源/导入：`rematchNextSource`/`rematchSource`/`rematchWithAI`/`importLrcContent`/`deleteLyrics`/`saveLyrics`/`syncLyricLine`（行号见第七节流程表）。
- 持久化：`SavedLyricEntity(mediaId PK, lyrics:String整条, source, offsetMs, savedAt)`（`AppDatabase.kt:19-26`），version 10（`461`）；DAO `lyricsFlow/upsertLyric/deleteLyric`（`198-206`）。
- 缓存：内存 `savedLyricsMap`（`MainViewModel.kt:97,264`）+ 在线去重 `triedAuto`（`223`）。无 parsed 缓存，每次从 raw 重建。
- 渲染：`LyricsScreen` 全屏歌词本（`LyricsScreen.kt:61-304`），行渲染 `Text(line.text)`（`185-190`），高亮 `i <= activeI`（`187`），当前行 `activeIndex`（`LrcParser.kt:26`，`LyricsScreen.kt:84`），自动滚到中部（`147-152`）。
- 调整面板/导入/切源对话框：`LyricsAdjustSheet`（`LyricsScreen.kt:322-473`）、`LyricsImportDialog`（`AppRoot.kt:561-614`）、`LyricsSourcePickerDialog`（`AppRoot.kt:517-554`）。

---

*本报告未修改任何代码。待设计方案确认后，按第八节步骤进入开发。*

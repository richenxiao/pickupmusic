# 专辑虚拟拆分为单曲 · 数据模型与实现方案

> 状态：**待确认**（确认后再进入 UI / 交互实现）
> 目标：整轨文件（如 45 分钟一张专辑）在软件内虚拟拆成多首独立单曲，物理文件零切割。
> 拆分单曲在收藏 / 歌单 / 播放次数 / 最近播放 / 收听记录中的表现必须与真实独立歌曲**完全一致**；
> 同时保留「完整专辑」播放入口，两套统计口径在数据层清晰区分、不重复计数。

---

## 0. 结论速览

| 需求方关注点 | 勘查结论 |
|---|---|
| 拆分专辑是不是真正的专辑实体 | **是**。直接复用现有 `albumId` / `albumKey` 机制，不建平行体系。物理文件所属的专辑就是「拆分专辑」。 |
| 一个文件、多个逻辑曲目怎么落地 | **合成 mediaId + 方案表**。`split_track` 表存曲名/起点，对外暴露合成 id；下游所有表（收藏、歌单、播放事件、歌词…）**零 schema 改动**即可工作。 |
| 现有播放次数判定是否适用 | **适用**。≥30s 判定对 2–5 分钟单曲没问题；≤60s 的 intro/outro 加比例兜底让它**能计数**，但用 `heatCountFor()` 把它们挡在热度榜之外。 |
| 下游表是否按文件路径分组 | **没有**。全库以 `mediaId` 为键。风险不在这里，而在「id 被当作 MediaStore id 使用」的 10 处位置（见 §1.4）。 |
| 两套统计口径怎么隔离 | `play_event` 加 `kind` 列（`track`/`album`）+ 两条 id 值域不相交。**完整专辑播放只计入专辑播放次数，不产生任何单曲事件**；单曲播放不产生容器事件。 |
| 元数据优先级 | 专辑级读专辑/整轨文件，单曲级曲名读用户录入，`track_info_override` 仍然最高可覆盖。 |
| 无缝播放 | **队列里只放一个 MediaItem**（整轨文件），切歌只做一次元数据替换，引擎层零 seek / 零 reload。 |

---

## 1. 现状勘查（对应需求第一节的排查项）

### 1.1 曲目身份：全库以 `mediaId` 为唯一键

| 表 | 主键 / 分组键 | 拆分单曲上线后 |
|---|---|---|
| `playlist_track` | `(playlistId, mediaId)` | ✅ 天然独立。「我的喜欢」就是 `playlist.id = 'p3'`（`MainViewModel.kt:3487-3492`），收藏逻辑 `isFav/toggleFav` 直接按 id 工作。 |
| `play_event` | `mediaId` + `GROUP BY mediaId`（`AppDatabase.kt:474`） | ✅ 合成 id 唯一 → 无污染。 |
| `saved_lyrics` | `mediaId` | ✅ 副产品：**每首拆分单曲可以有自己的歌词**。 |
| `track_info_override` | `mediaId`（title/artist/note/hidden） | ✅ 拆分单曲的改名、隐藏**零成本复用**。 |
| `track_album_move` | `mediaId` | ✅ |
| `trashed_track` | `mediaId` | ⚠️ 见 §1.4 |
| `reading_override` / `external_reading_evidence` | `(mediaId, lyricsHash, …)` | ✅ |
| `album_order` | `albumKey`（`aid:<albumId>` 或 `album\|artist`） | ✅ 自定义排序里存的是 id 列表，`albumOrder()` 用 `mapNotNull { byId[it] }` 自动丢弃失效 id（`MainViewModel.kt:886-887`）→ 重新拆分不会残留脏数据。 |
| `album_art_cache` / `album_info_override` / `new_album` | `albumId` | ✅ 专辑级，不受影响。 |
| `TrackCache`（磁盘 JSON） | 以 `id` 反推 `uri`（`TrackCache.kt:55-63`） | ⚠️ 见 §1.4 |

**唯一按路径的地方**只有两处，都不是统计分组：
- `MediaScanner.diagnoseMissingFiles`（`MediaScanner.kt:73`）：文件系统 vs MediaStore 的目录诊断；
- `playUri()` 的曲目匹配（`MainViewModel.kt:1892`）：用 `uri` / `dataPath` 找回 track。

> **结论：不存在「按文件路径 GROUP BY 导致多首拆分单曲互相污染统计」的既有 SQL。** 这个风险点可以排除。
> 但 §1.4 的 id 语义风险必须修，否则会出现崩溃 / 删错文件 / 缓存脏数据。

### 1.2 播放次数判定逻辑

`PlaySessionTracker`（`playback/PlaySessionTracker.kt`）：

- **阈值**：`COUNT_THRESHOLD_MS = 30_000`，累计**真实播放**满 30 秒 → 该次 session 计一次有效播放（`play_event.completed = 1`）。
- **seek 剔除**：每拍取位置增量，`delta ∉ [1, 2000]ms` 视为大跳/倒退，不累加（`sample()` 第 96-114 行）。
- **暂停不计**：只在 `isPlaying && stateReady` 时累加。
- **口径来源**：`playCountFlow()` 从 `play_event WHERE completed=1 GROUP BY mediaId` 聚合，`play_count` 表已废弃不用。

对拆分单曲的适用性：

| 场景 | 结论 |
|---|---|
| 2–5 分钟拆分单曲 | ✅ **完全适用，不需要调整阈值**。30s ≈ 该曲的 10%–25%，与普通歌曲体验一致。 |
| ≤60 秒的短曲（intro / outro / interlude / skit） | ❌ 现状下**永远不可能被计数**（是既有缺口 —— `shortTracks()` 甚至把 1–30s 的曲列为"待清理"）。 |
| 结论（已定） | 加一条**比例兜底**：`threshold = min(30s, max(5s, durationMs / 2))`，**仅对 `durationMs ≤ 60s` 生效**。>60s 的曲行为逐字不变。详见 §3.3。 |

**已确认的产品规则（用户明确）**：intro / outro 这类 ~30 秒的曲目**算作专辑的过渡，某种程度上不算「歌」**。因此：

1. **要计数** —— 必须能被 `completed=1` 记到（→ §3.3 的兜底阈值）；
2. **不参与热度评判** —— 不能进"最常听单曲"榜，也不能影响任何按播放次数排序的列表（→ §3.6）；
3. **不当作"可清理的短音频"** —— 不得出现在 设置·清理 的"短音频"建议里（→ R3）。

**最关键的一条实现约束（本需求的核心难点）**：

> session 只在 `Player.Listener.onMediaItemTransition` 时结束并开新 session。
> 整轨文件在引擎层**永远不会 transition** → 如果不显式处理，整轨播 45 分钟只会产生**第 1 首的 1 条 play_event**，后面 11 首全部漏计。

解决方案见 §4.3：边界切换走一次 `replaceMediaItem`（换 mediaId），复用既有的 transition → `startTracking` 链路，自动完成「finalize 上一首 + 开新 session」。

### 1.3 元数据读取优先级（现状）

`lib()` 的应用顺序（`MainViewModel.kt:744-770`）：

```
tracksRaw(MediaStore 内嵌标签)
  → track_album_move（迁移专辑）
  → artist_alias（歌手归一）
  → album_override（单曲级重挂专辑）
  → album_info_override（专辑级名称/歌手/封面）
  → track_info_override（单曲级 title/artist/note）
```

拆分单曲的曲名需要作为**新的最内层来源**插在 `tracksRaw` 之后、`track_info_override` 之前（见 §5）。

### 1.4 ⚠️ 真正需要修的风险清单：`id` 被当作 MediaStore id 使用

| # | 位置 | 现状 | 拆分单曲下的后果 | 处理 |
|---|---|---|---|---|
| R1 | `uriOf(id)` + `requestTrash`（`MainViewModel.kt:2359-2385`） | `ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, id)` | 合成 id 构造出**不存在的 URI**，MediaStore 抛异常或误删 | 拆分单曲请求回收站前先解析回源文件 id；或禁用并在菜单里给「删除整轨文件」 |
| R2 | `TrackCache`（`TrackCache.kt:55-63`） | 落盘时只存 id，读回时用 id 重建 uri | 合成 id 落盘 → 冷启后 uri 非法 | 虚拟曲目**绝不写入** `tracks_cache.json` |
| R3 | `shortTracks()` / `cleanCount()` / `cleanSizeBytes()`（`MainViewModel.kt:1605-1620`） | 1–30s 的曲列入"可清理" | **拆分出来的 intro/outro 会被建议删除**（它们是专辑的有机组成，不是垃圾） | 排除合成 id（同时排除所有拆分曲目，见 §3.4） |
| R4 | `dupGroups()`（`MainViewModel.kt:1600-1601`） | 按 `title\|artist` 分组 | 同名拆分曲被判重复 | 排除合成 id |
| R5 | `foldersMap()`（`MainViewModel.kt:840`） | 按 `folder` 分组，数据源是 `lib()` | 文件夹里由 1 条变 N 条 → **看起来像文件真的被切割了**（用户明确不要这个） | **`foldersMap()` 改为基于未展开的物理曲目构建**（见 §2.8） |
| R6 | `playUri()`（`MainViewModel.kt:1892`） | 按 uri / dataPath 匹配 | 会命中整轨文件本体 | 命中整轨文件时按「完整专辑」处理 |
| R7 | `PlaybackService` 通知栏 next/prev | `seekToNextMediaItem()` | 队列只有 1 项 → 按钮无效 | 在 `MediaSession.Callback` 里接管（§4.5） |
| R8 | `BackupManager` | 导出白名单式覆盖表 | 新表不导出 → 换机丢失拆分 | 纳入导出（§2.5） |
| R9 | `playCountFor(containerId)` | 无专辑级计数 | 专辑播放数无处可查 | 新增专辑级 flow（§3.2） |
| R10 | `ListeningStatsScreen` / `RecentPlaysScreen` / `HomeScreen` 的 `lib[ev.mediaId]` | 直接查 `lib()` 映射 | 专辑事件 id 查不到 → 走进 `"未知"` 分支污染艺人榜 | 改用 `vm.trackById()` + kind 分流（§3.4） |

`ArtCache` / `loadThumbnail(track.uri)`（`Components.kt:221`）无影响 —— 拆分单曲的 `uri` 就是整轨文件的 content URI，封面照常。

### 1.5 可复用的既有先例

- **合成 id 冒充 mediaId**：`playUri()` 用 `-uri.hashCode()`（`MainViewModel.kt:1900`）、`batchAssignToNewAlbum` 用 `+0x100000000L`（`MainViewModel.kt:1009`）→ 说明这是本库既有范式，不是新发明。
- **边听边打点**：`syncLyricLine(lineIdx)`（`MainViewModel.kt:2886-2912`）+ `LyricsScreen` 每行的 Clock 按钮（`LyricsScreen.kt:445-456`，`clickable { vm.syncLyricLine(i) }`）→ 直接照搬交互。
- **再点即覆盖更新**：`syncLyricLine` 无条件覆盖该行时间戳 → 同一语义。
- **中途保存进度**：歌词打轴在"最后一行被点亮"时立刻落库（`MainViewModel.kt:2905-2911`）→ 拆分草稿保存的先例。
- **进度条 + 拖动试听**：`player.seekToFraction()`（`PlayerController.kt:530`）。

---

## 2. 数据模型方案

### 2.1 核心决策：合成 id + 方案表

```
┌─────────────────────────────────────────────────────────────┐
│ MediaStore: id=1234, uri=content://media/.../1234           │  ← 物理文件，1 条，不动
│   dataPath=/Music/专辑/整轨.flac  duration=45:12            │
└─────────────────────────────────────────────────────────────┘
        │ 被 album_split 标记为「已拆分」
        ▼
┌─────────────────────────────────────────────────────────────┐
│ split_track:                                                │
│  ordinal=1  title="intro"    startMs=0        → id 0x2000…01│
│  ordinal=2  title="黑"       startMs=185000   → id 0x2000…02│
│  ordinal=3  title="自由式"   startMs=402000   → id 0x2000…03│
│  …                                                          │
│  （endMs 不存，派生：= 下一首 startMs；最后一首 = 文件末尾） │
└─────────────────────────────────────────────────────────────┘
        │
        ▼ 对外表现为 N 个 Track(id = 合成 id, uri = 整轨文件 uri)
   收藏 / 歌单 / 播放事件 / 歌词 / 隐藏 / 改名 全部按 mediaId 工作，零改动
```

**不建平行体系**：拆分专辑的 `albumId` / `albumKey` 就是整轨文件所属专辑的 `albumId` / `albumKey`。`album_info_override`（专辑名/歌手/封面/类型）、`album_art_cache`（封面+取色+发行日期）、`new_album` 全部照常生效。

### 2.2 合成 id 命名空间

| 用途 | 规则 | 值域 |
|---|---|---|
| 拆分单曲 | `SPLIT_TRACK_BASE + split_track.id` | `0x2000_0000_0000L + n` |
| 整轨容器（「完整专辑」） | `SPLIT_CONTAINER_BASE + sourceMediaId` | `0x3000_0000_0000L + mediaId` |

选择依据：
- **正数**：区别于 `playUri` 的负数 id；
- **远离 MediaStore id**（实测 < 2³¹）与 `batchAssignToNewAlbum` 的 `0x1_0000_0000L` 段；
- 容器 id 由 `sourceMediaId` **确定性派生**（不占自增序列），重扫后仍稳定。

### 2.3 表结构（Room Entity / 建表 SQL）

```sql
-- 一条 = 一个整轨文件被拆分
CREATE TABLE album_split (
    sourceMediaId INTEGER NOT NULL PRIMARY KEY,  -- 整轨文件的 MediaStore _ID
    sourcePath    TEXT    NOT NULL,              -- 冗余存绝对路径：id 变化时兜底重解析
    status        INTEGER NOT NULL DEFAULT 0,    -- 0=草稿（有未定时间点）1=已完成
    createdAt     INTEGER NOT NULL,
    updatedAt     INTEGER NOT NULL
);

-- 一条 = 一首拆分单曲
CREATE TABLE split_track (
    id            INTEGER PRIMARY KEY AUTOINCREMENT, -- 对外 id = SPLIT_TRACK_BASE + id
    sourceMediaId INTEGER NOT NULL,
    ordinal       INTEGER NOT NULL,                  -- 1..N，展示序号 & 排序依据
    title         TEXT    NOT NULL,                  -- 用户录入的曲名（唯一曲名来源）
    startMs       INTEGER NOT NULL DEFAULT -1,       -- -1 = 未定（草稿态）
    updatedAt     INTEGER NOT NULL
);
CREATE INDEX idx_split_track_source ON split_track(sourceMediaId);
CREATE UNIQUE INDEX idx_split_track_src_ord ON split_track(sourceMediaId, ordinal);
```

**为什么 `endMs` 不落库**：它恒等于「下一首的 `startMs`」或「文件末尾」。两个字段分开存会在用户改中间某一首的起点时漂移（经典的双字段不同步 bug）。派生规则：

```
endMs(i) = startMs(i+1)                        (i < N)
endMs(N) = 文件时长（仅用于展示；运行时由流自然结束决定，见 §4.3）
```

**`startMs = -1` 表示未定**，而不是 0 —— 0 是合法起点（第一首固定 0），必须能区分"已定 0"和"未定"。

### 2.4 数据库迁移（v21 → v22）

一次迁移同时完成三件事（`AppDatabase.kt`）：

```kotlin
private val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS album_split (...)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS split_track (...)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_split_track_source ON split_track(sourceMediaId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_split_track_src_ord ON split_track(sourceMediaId, ordinal)")
        // 两套统计口径的数据层隔离位：默认 'track' → 全部存量行语义不变
        db.execSQL("ALTER TABLE play_event ADD COLUMN kind TEXT NOT NULL DEFAULT 'track'")
    }
}
```

`play_event.kind` 语义：

| 值 | 含义 | `mediaId` 是 |
|---|---|---|
| `'track'` | 单曲播放会话（含拆分单曲） | 真实 mediaId 或拆分单曲合成 id |
| `'album'` | 「完整专辑」整轨播放会话 | 容器 id（`SPLIT_CONTAINER_BASE + sourceMediaId`） |

存量行全部落 `'track'`，所有既有查询加 `AND kind = 'track'` 后语义**逐字不变**。

### 2.5 派生字段规则（虚拟 Track 构造）

| Track 字段 | 取值 | 理由 |
|---|---|---|
| `id` | `SPLIT_TRACK_BASE + split_track.id` | 合成 id |
| `uri` | 整轨文件的 content URI | 播放引擎读的就是它 |
| `title` | `split_track.title`（用户录入） | 唯一曲名来源，绝不被文件内嵌标签覆盖 |
| `artist` | 专辑级 artist（`album_info_override` > MediaStore） | 用户未逐曲录入歌手；可用 `track_info_override` 单独改 |
| `album` / `albumId` | 整轨文件的 album / albumId（含 override） | 保证归入同一张专辑 |
| `trackNo` | `split_track.ordinal` | 展示序号 |
| `durationMs` | `endMs - startMs` | ⚠️ **必须是分段时长**，不是文件时长。否则专辑总时长、`classifyAlbum`、行内时长、进度条全错 |
| `sizeBytes` | **0** | ⚠️ 不能给文件大小，否则 `cleanSizeBytes` / 占用统计重复计算 |
| `folder` / `dateAdded` / `dataPath` | 继承整轨文件 | 文件夹分组、排序 |

容器 Track（「完整专辑」）：

| 字段 | 取值 |
|---|---|
| `id` | `SPLIT_CONTAINER_BASE + sourceMediaId` |
| `title` | 专辑名 |
| `durationMs` | 整轨文件总时长 |
| `trackNo` | 0 |
| `sizeBytes` | 0 |

### 2.6 `lib()` 注入点与缓存失效

```
lib():
  1. splitBySource: Map<sourceMediaId, List<SplitTrack>>
       · 主键解析：sourceMediaId
       · 兜底解析：sourcePath == track.dataPath（MediaStore id 变化后仍能挂上）
  2. 遍历 tracksRaw：
       若 t.id ∈ splitBySource  → 丢弃 t，改为输出 N 个虚拟 Track
       否则                     → 走原有 override 链路
  3. 容器 Track 不进 lib() 返回值（否则会出现在 歌曲/文件夹/歌手 列表里）
     但必须进 libById —— 因为 onTrackStarted 有 `trackById(id) ?: return`（MainViewModel.kt:1795）
     容器 id 解析不到 → 整轨播放不计次
```

**缓存签名必须扩展**（`libSignature()`，`MainViewModel.kt:713-735`）：

```kotlin
val splitCount = splitTracks.size
val splitValues = splitTracks.sortedBy { it.id }.hashCode()   // 含 startMs/title
val splitSrcValues = splits.entries.sortedBy { it.key }.hashCode()
// → 拼进返回串
```

不加这一项，用户拆完专辑后 `lib()` 会命中旧缓存，界面"没反应"。

### 2.7 备份导出

`BackupManager` 的导出清单加入 `album_split` + `split_track`。跨设备用 `sourcePath` 重新解析 `sourceMediaId`（新设备的 MediaStore id 必然不同），解析不到则保留行但标记失效、不显示。

### 2.8 展开范围：哪些视图展开成 N 首，哪些保持 1 条

**已确认的产品定调**：拆分是**软件层面的划分**，不改变文件性质。因此：

| 视图 / 数据源 | 数据来源 | 结果 | 理由 |
|---|---|---|---|
| **文件夹**（`foldersMap()`，`MainViewModel.kt:840`） | **未展开的物理曲目** | **1 条**（整轨文件） | 文件夹 = 文件视图。出现 N 条会让人以为文件真的被切割了 —— 用户明确不要 |
| 歌曲列表（`sortedSongs()`） | `lib()` | **N 条** | 歌曲 = 歌曲视图。拆出来的单曲必须能被搜到、能播放、能收藏，否则"和独立歌曲完全一致"在最基本的入口就不成立 |
| 专辑页（`albumsMap()` / `albumOrder()`） | `lib()` | **N 条** + 顶部「完整专辑」行 | 需求三的最终呈现形态 |
| 歌手页（`artistsMap()`） | `lib()` | **N 条** | 它们确实是这位歌手的曲目 |
| 搜索 / Agent 搜歌（`searchTracks`） | `lib()` | **N 条** | "库里有没有这首歌"应该能查到 |
| 我的喜欢 / 歌单 | `playlist_track`（按 mediaId） | **按用户添加的实际条目** | 用户加了哪几首就是哪几首 |
| 随机播放全库（`playRandom`） | `lib()` | **N 条** | 见 §9 D8 |

**实现方式**（`foldersMap()` 的例外）：

```kotlin
fun foldersMap(): Map<String, List<Track>> {
    // 文件夹 = 文件视图：用未展开的物理曲目，整轨文件仍显示为 1 条。
    // 其余 override（改名/迁移/隐藏）照常应用，只是不做拆分展开。
    val m = LinkedHashMap<String, MutableList<Track>>()
    for (t in physicalTracksForFolders()) m.getOrPut(t.folder) { mutableListOf() }.add(t)
    return m
}
```

**文件夹里点整轨文件播放** = 播放**完整专辑**（容器模式，计专辑播放次数）—— 与 R6 的 `playUri()` 命中整轨文件时同一条路径。

> 副作用（可接受）：整轨文件不再参与 `dupGroups()` 重复检测（因为它不在 `lib()` 里了）。这是拆分后的合理代价。

---

## 3. 统计口径方案（两套口径的数据层隔离）

### 3.1 口径定义

| 用户动作 | 产生的 play_event | 计入 |
|---|---|---|
| 播放某首拆分单曲（含专辑页点第 N 行） | `kind='track'`, `mediaId=合成id` | 该单曲的播放次数、收听统计的单曲/专辑/艺人聚合 |
| 播放 ≤60s 的过渡曲（intro/outro） | `kind='track'`, `mediaId=合成id` | **播放次数照常计入**，但**不参与热度评判**（§3.6） |
| 点「播放完整专辑」/ 专辑页「播放」按钮 | `kind='album'`, `mediaId=容器id` | **只计入「专辑被播放次数」**，不计入任何单曲 |
| 播放普通歌曲 | `kind='track'`, `mediaId=真实id` | 同现状，逐字不变 |

> **已确认（用户明确）**：「完整专辑播放**计入专辑播放次数，但不是作为单曲**」。
> 数据层由 `kind` 列 + 不相交的 id 值域双重保证；消费层由 §3.2 的查询拆分保证。

「不重复计数」的硬约束：

- 整轨播放（容器模式）期间，边界切换**不触发**虚拟单曲切换 → 不写任何单曲事件；
- 单曲播放期间，**不写**容器事件；
- 两条口径的 `mediaId` 值域**不相交**（`0x2000…` vs `0x3000…`），且被 `kind` 二次加固。

### 3.2 查询层改造清单（`AppDatabase.kt`）

| 方法 | 现状 | 改为 |
|---|---|---|
| `playCountFlow()` | `WHERE completed=1 GROUP BY mediaId` | `WHERE completed=1 AND kind='track' GROUP BY mediaId` |
| `playCount(mediaId)` | 同上 | 加 `AND kind='track'` |
| **新增** `albumPlayCountFlow()` | — | `SELECT mediaId, COUNT(*) AS count FROM play_event WHERE completed=1 AND kind='album' GROUP BY mediaId` |
| **新增** `albumPlayCount(containerId)` | — | 同上单值查询 |
| `completedPlayEventsBetween(from,to)` | 全部 completed=1 | 保持返回全部（含 album），由消费方按 `kind` 分流 |
| `recentPlayEvents(limit)` | 全部事件 | 保持全部（最近播放要显示专辑播放） |

ViewModel 侧新增 `albumPlayCounts: Map<Long, Int>`（新 flow 收集）+ `fun albumPlayCountFor(containerId: Long): Int`。

### 3.3 阈值判定（已定：调整，仅 ≤60s 生效）

**30s 阈值保持不变**，只补一条短曲兜底 —— 目的是让 intro/outro 这类过渡曲**能被计数**：

```kotlin
// PlaySessionTracker
/** v?: ≤60s 的过渡曲（intro/outro）兜底阈值——按半程计，保证短曲也能被计数。 */
fun start(mediaId: Long, durationMs: Long = 0L) {
    val threshold = if (durationMs in 1..60_000L)
        minOf(COUNT_THRESHOLD_MS, maxOf(5_000L, durationMs / 2))
    else COUNT_THRESHOLD_MS
    // Session(threshold = threshold, ...)
}
```

| 时长 | 阈值 | 说明 |
|---|---|---|
| > 60s（全部正常歌曲 + 全部 2–5 分钟拆分单曲） | 30s | **行为逐字不变** |
| 30s 的 intro | 15s | 听一半即计数 |
| 15s 的 interlude | 7.5s | |
| 5s 的 skit | 5s | 下限 5s，避免"刚点开就算" |

需要 `PlayerController.startTracking()` 传入当前曲目时长（从 `buildItem` 时的 Track 拿到，容器/虚拟曲目都已有 `durationMs`）。

### 3.4 过渡曲（intro / outro）：计入但不参与热度

**定义**：`INTERLUDE_MAX_SEC = 60`，即 `durationSec in 1..60` 的曲目视为"专辑过渡"，不是"歌"。

> 判定不落库、不加字段 —— 纯派生（时长即可判定），避免用户改时间点后标记不同步。
> 需要时可在 `track_info_override.note` 之外再加一个手动开关（暂不做）。

**两个口径必须同时成立**：

| 维度 | 过渡曲的处理 |
|---|---|
| 播放次数 `playCountFor()` | ✅ **照常计入**（`play_event` 里就是一条普通 `kind='track'` 事件） |
| 热度排序 / 榜单 | ❌ **排除**。新增 `heatCountFor(id)` = 过渡曲恒返回 0，其余等于 `playCountFor` |
| 专辑循环数（收听统计） | ✅ 计入（它本来就是专辑的一次播放） |
| 收听总时长 | ✅ 计入（`playedSec` 照常累加） |
| 最近播放 feed | ✅ 显示 |
| 收藏 / 歌单 / 歌词 / 改名 / 隐藏 | ✅ 完全可用（它仍然是可被收藏的曲目） |
| 设置·清理的"短音频"建议（R3） | ❌ **排除**（它是专辑的有机组成，不是垃圾文件） |

**需要把 `playCountFor` 换成 `heatCountFor` 的 5 个热度点**（已逐一核对）：

| # | 位置 | 用途 | 改法 |
|---|---|---|---|
| H1 | `MainViewModel.kt:1262` `searchTracks()` | Agent 搜歌按播放次数排序 | `heatCountFor(it.id)` |
| H2 | `MainViewModel.kt:1273` `searchArtists()` | Agent 搜歌手按该歌手总播放次数排序 | `heatCountFor(t.id)` |
| H3 | `LibraryScreen.kt:1569` | 歌手页曲目列表按播放次数排序 | `heatCountFor(it.id)` |
| H4 | `SearchScreen.kt:188` | 搜索结果专辑的"总播放次数" | 拆成两个数：`totalPlays`（含过渡曲，用于展示）与 `heatPlays`（排除过渡曲，用于排序） |
| H5 | `ListeningStatsScreen.kt:97-113` `trackMap` → 最常听单曲 | 榜单 | `kind='track'` 且**排除过渡曲** |

**H5 的细节**：`trackMap` 排除过渡曲，但同一批事件仍然要喂给 `albumPlayMap`（专辑循环数）和 `artistMap`（艺人榜）—— 否则听完一张含 3 首 interlude 的专辑，专辑循环数会算成 9/12 而不是 1。

**为什么不在 SQL 层排除**：过渡曲的判定依赖 `Track.durationMs`（虚拟曲目是 `endMs - startMs`，纯计算值），SQL 层拿不到。所以在 ViewModel 层用 `heatCountFor()` 收口。

### 3.5 消费方改造

| 位置 | 现状 | 改法 |
|---|---|---|
| `ListeningStatsScreen.kt:98-114` | `lib[ev.mediaId]`，null → 走 `"未知"` 分支 | ① 用 `vm.trackById(ev.mediaId)`；② `kind='album'` 的事件不进 `trackMap`，只累加专辑循环数；③ `trackMap` 仅统计 `kind='track'` 且非过渡曲（H5） |
| 专辑循环数（`albumCycles`，第 115-117 行） | `单曲播放数 / 曲目数` | `= 容器播放数 + (单曲播放数 / 曲目数)`（**含过渡曲的播放数**，不排除）。整轨播一次 = 1 个循环，语义正好 |
| 艺人榜（`artistMap`，第 110-112 行） | 每事件 +1 | `kind='album'` 也算该专辑艺人的一次收听（整轨会话就是在听这位歌手）—— **决策点 D3** |
| `RecentPlaysScreen.kt:67,108-113` | `lib[ev.mediaId]`，null → `continue`（静默丢失） | 改用 `vm.trackById()`；`kind='album'` 渲染为专辑行（封面 + 专辑名 + 「完整专辑」标记） |
| `HomeScreen.kt:189` | `recentIds.mapNotNull { byId[it] }`，`byId` 来自 `lib()` | 改用 `vm.trackById(it)`，否则首页「最近播放」看不到专辑播放 |
| `MainViewModel.playCountFor()`（783） | `playCounts[id]` | 保持（含过渡曲的完整计数）。新增 `heatCountFor()` + `albumPlayCountFor()` |
| `LibraryQuerySkill`（Agent 问答"我最近在听什么"） | 读 `playCounts` | 走 `heatCountFor`，回答里不会出现 interlude 挤掉真歌 |

### 3.6 验证点（装机必测）

1. 完整播放一张 12 首的拆分专辑 → 12 首**各自** `completed=1` 恰好 1 次；容器 `kind='album'` 事件 0 次。
2. 点「播放完整专辑」听满 30s 后退出 → 容器事件 1 次；12 首单曲的 `completed=1` **全部为 0**。
3. 只播第 5 首 20 秒就切走 → 第 5 首 `completed=0`（不出现在热度榜），但出现在最近播放 feed。
4. 同一物理文件的两首拆分单曲分别收藏 → 「我的喜欢」出现 2 条，互不影响。
5. 拆分单曲加入自建歌单 → 歌单内显示 N 首、可单独播放、可单独移除。
6. **过渡曲双口径**：把 3 首 interlude 各播满 20 秒 → 它们的 `completed=1` 都是 1（计数成立）；但"最常听单曲"、Agent 搜歌排序、歌手页排序里都**看不到**它们；专辑循环数仍然算满 12 首。
7. 过渡曲不出现在 设置·清理 的"短音频"列表里。

---

## 4. 播放引擎方案（无缝播放）

### 4.1 两种播放模式

| 模式 | 队列内容 | 边界行为 | 统计 |
|---|---|---|---|
| **容器模式**（「播放完整专辑」） | 1 个 MediaItem = 整轨文件，`mediaId = 容器id`，无 plan | 无虚拟切换，UI 全程显示「专辑」 | 1 条 `kind='album'` |
| **单曲模式**（点某首拆分单曲） | 1 个 MediaItem = 整轨文件，`mediaId = 起始虚拟曲目的合成id`，携带 plan | 边界处无缝换 mediaId + 元数据 | 每首各 1 条 `kind='track'` |

两种模式的 MediaItem **URI 完全相同**，只有 `mediaId` / `MediaMetadata` / plan 不同。

### 4.2 队列构造：逻辑曲目 → 容器条目

新增单一收口函数（VM 层），所有播放入口都过它：

```kotlin
data class VirtualTrack(val id: Long, val startMs: Long, val endMs: Long)
data class SplitPlan(val containerId: Long, val tracks: List<VirtualTrack>)

/** 逻辑曲目列表 → 引擎队列条目。连续同源区间合并成一个容器条目。 */
private fun toQueueEntries(logical: List<Track>, startId: Long?): QueueEntries
```

合并规则：**相邻且属于同一整轨文件**的逻辑曲目 → 合并进同一个容器条目的 plan（这样专辑 = 1 个条目）。不相邻（如歌单里同时有第 1 首和第 5 首）→ 拆成 2 个条目。

plan 随 MediaItem 走（存在 `MediaMetadata.extras` 里），这样 shuffle / `moveMediaItem` / `addMediaItem` 之后 plan 自动跟随，不会错位：

```
extras["split_container_id"] = containerId
extras["split_plan"]         = "id1:s1:e1;id2:s2:e2;…"
extras["split_idx"]          = 当前下标 k
```

各播放入口的映射：

| 入口 | 逻辑队列 | 模式 |
|---|---|---|
| `playAlbum(key)` 的「播放」按钮 | `[容器(plan=全曲)]` | 容器 |
| `playAlbum(key, startId)` 点第 N 行 | `[容器(plan=全曲, idx=N)]` + 起始位置 `startMs(N)` | 单曲 |
| `play(id)` 从歌曲页点拆分单曲 | `[容器(plan=全曲, idx=N)]` | 单曲 |
| `playPlaylist(pid, startId)` | 按合并规则展开，每个整轨文件一个条目 | 单曲 |
| `playArtist` / `playRandom` | 同上 | 单曲 |
| `playUri()` 命中整轨文件 | `[容器]` | 容器 |
| `resyncQueue()` / `syncQueue()` | 走同一收口函数 | — |

### 4.3 边界切换：一次 `replaceMediaItem`，引擎零动作

在既有的 300ms ticker（`PlayerController.startTicker`，第 216-241 行）里加一段：

```kotlin
val plan = currentItemPlan() ?: continue          // 非拆分曲目直接跳过
val k = plan.idxOf(positionMs)                     // 纯由位置派生，任何来源的 seek 都自洽
if (k != plan.idx) {
    val next = plan.tracks[k]
    if (k == plan.idx + 1 && next.startMs == plan.tracks[plan.idx].endMs) {
        // ① 专辑内连续边界：只换 mediaId + 标题/歌手，URI 与 LocalConfiguration 不变
        controller.replaceMediaItem(controller.currentMediaItemIndex, buildItem(next, plan, k))
        //   ↑ 引擎不重新 prepare（Media3 对相同 LocalConfiguration 只做元数据更新）
    } else {
        // ② 跳段（用户 seek 跨越多个边界）→ 只更新状态，不做引擎动作
        controller.replaceMediaItem(...)
    }
}
```

为什么这一次调用就够：

```
replaceMediaItem(mediaId 变化)
  → Player.Listener.onMediaItemTransition(reason = PLAYLIST_CHANGED)
      → 既有守卫 isSelfReplace = (reason==PLAYLIST_CHANGED && id==currentId) 为 false（id 变了）
      → startTracking(newId)
          → PlaySessionTracker.start() 内部 endCurrent() → 回填上一首 playedSec（finalize）
          → onTrackStarted(newId, sessionId) → 插新的 play_event 行（completed=0）
      → currentId = newId → Compose 重组 → 播放页/迷你条/歌词本切到新歌
      → queueVersion++ → 队列面板刷新
  → onTrackStarted 里的封面推送 → updateArtworkData(forMediaId=newId)
      → 再 replaceMediaItem（同 id）→ 被 isSelfReplace 守卫吸收，不成环（该守卫正是 2e4ff85 修的）
```

**关键点**：不改 URI、不加 `ClippingConfiguration`、不调 `seekTo`、不动 `setMediaItems` → 音频流完全连续，无卡顿无爆音。`replaceMediaItem` 在 URI/LocalConfiguration 不变时 Media3 只更新 MediaItem 元数据，不重建 MediaSource —— 这一点本库已有生产验证（`updateArtworkData` 播放中推送封面走的同一条路）。

**位置连续性保证计数正确**：`positionMs` 跨边界单调递增（0 → 文件末尾），`PlaySessionTracker.sample()` 的 delta 检测不会把边界误判为 seek；新 session 的 `lastPosMs` 初始为 0，第一拍 delta 超上限被丢弃（只损失 ≤300ms），随后正常累加。

### 4.4 为什么不用其他方案（对比表）

| 方案 | 边界行为 | 是否满足「引擎零动作」 |
|---|---|---|
| **本方案**：1 个 MediaItem + 应用层派生下标 | 只换 mediaId | ✅ |
| N 个 MediaItem + `ClippingConfiguration(start,end)` | ExoPlayer 释放 MediaSource A、prepare MediaSource B → 音频 sink flush | ❌ 每个边界都有可闻间隙 |
| N 个 MediaItem，无 clipping | 每项从头播到**文件末尾**再进下一项 | ❌ 功能错误 |
| 自定义 MediaSource（ConcatenatingMediaSource2） | 需要重写 PlaybackService 的 MediaSource 工厂 | ⚠️ 改动面大，且跨 period 仍可能 flush |

### 4.5 next / prev 与通知栏

| 场景 | 行为 |
|---|---|
| 播放页 / 迷你条「下一首」 | 若当前在 plan 中且有下一首 → `replaceMediaItem(下一首)` + `seekTo(next.startMs)`（用户主动操作，seek 可接受） |
| 「上一首」 | 若 `positionMs - cur.startMs > 3s` → 回本曲开头；否则 → 上一首起点（与 `setMaxSeekToPreviousPositionMs(4000)` 的既有语义一致） |
| **通知栏 / 蓝牙耳机 / 车机 next/prev** | 单条目队列下 `seekToNextMediaItem()` 无效。需在 `PlaybackService` 的 `MediaSession.Callback`（已有匿名实现，第 70-119 行）里 override `onPlayerCommandRequest`，接管 `COMMAND_SEEK_TO_NEXT/PREVIOUS`，从 `player.currentMediaItem.mediaMetadata.extras` 读 plan 自行处理 |
| 睡眠定时「本曲结束后」 | 现在用 `c.duration`（文件时长），拆分模式下应改用当前虚拟曲目的 `endMs` |
| 单曲循环（REPEAT_MODE_ONE） | 会循环整个文件。建议：拆分模式下 REPEAT_MODE_ONE 语义改为"循环当前虚拟曲目"（seek 回 `cur.startMs`） |

### 4.6 进度条 / 时长显示

`player.positionMs` / `player.durationMs` 在单曲模式下是**整轨文件**的坐标。UI 需要显示"当前这首"的坐标：

```kotlin
// PlayerController 暴露
val virtualStartMs: Long   // 0 = 非拆分模式
val virtualEndMs: Long     // 0 = 用真实时长
fun displayPositionMs(): Long = (positionMs - virtualStartMs).coerceAtLeast(0)
fun displayDurationMs(): Long = if (virtualEndMs > 0) virtualEndMs - virtualStartMs else durationMs
fun seekToDisplayFraction(f: Float)  // 拖进度条 → 映射回文件坐标
```

需要改的消费点：播放页进度条、迷你播放条进度、歌词本进度环（歌词本本身不需要变 —— 歌词按整轨文件的绝对时间戳存，跨边界继续走，反而更自然）。

### 4.7 已知边界情况

| 情况 | 处理 |
|---|---|
| 用户在单曲模式拖进度条跨过多个边界 | ticker 按位置重新派生下标 → `replaceMediaItem` 一次 → 统计正确归位 |
| 歌单里同时有同一文件的第 1 首和第 5 首 | 展开为 2 个容器条目（URI 相同）→ 第 1 首 `endMs` 处不连续 → `seekToNextMediaItem()` + seek 到第 5 首起点 |
| 拆分方案被用户删除 / 重新拆分 | 合成 id 失效 → `trackById` 返回 null → 队列自动剔除（`syncQueue` 的 `tracks.none { it.id == cur }` 守卫）；歌单/收藏里的旧 id 变成"幽灵项"，需在拆分编辑保存时提供「清理失效引用」 |
| 整轨文件被删除 / 移走 | `sourceMediaId` 解析失败 → 按 `sourcePath` 兜底；两者都失败 → 该拆分整体隐藏，`album_split` 行保留（文件回来后自动恢复） |
| shuffle | plan 随 MediaItem 走，shuffle 只改条目顺序，不影响条目内 plan |

---

## 5. 元数据优先级（最终）

```
【专辑级】album_info_override（手动）
        > MediaStore / 整轨文件内嵌标签（ALBUM / ALBUMARTIST）
          → 决定：专辑名、专辑歌手、封面、类型、发行日期
        ⚠️ 整轨文件的内嵌标签代表「整张专辑」，绝不参与单曲级字段

【单曲级】track_info_override（手动改名 / 改歌手 / 备注 / 隐藏）
        > split_track.title（用户在录入界面粘的列表）   ← 曲名唯一来源
        > 专辑歌手（artist 默认值）
        ⚠️ 绝不用整轨文件内嵌的 TITLE 覆盖用户录入的曲名

【时长】split_track 派生（endMs - startMs），不读文件时长
【序号】split_track.ordinal
```

代码位置：在 `lib()`（`MainViewModel.kt:744-770`）中，虚拟曲目的构造在 `tracksRaw` 之后、`track_info_override` 之前完成，因此 `track_info_override` 仍然可以覆盖拆分曲名（用户在曲目菜单里改名照样生效）。

---

## 6. 录入交互设计

### 6.1 命名（已定）

**用户定名：专辑整轨拆分为单曲**

| 位置 | 文案 |
|---|---|
| 专辑 ⋮ 菜单项 | **「专辑整轨拆分为单曲」** |
| 弹层标题 | **「专辑整轨拆分为单曲」** |
| 副文案 | *「整轨文件不会被切割，按时间点虚拟拆成多首，播放时自动接续」* |
| 反向操作 | **「取消拆分（合并回整轨）」**（已拆分时才出现，紧跟在拆分项下方） |
| 已拆分状态 | 菜单项变为 **「编辑拆分」** + 「取消拆分（合并回整轨）」 |

> 备选（如觉得菜单项太长）：菜单项缩成「整轨拆分为单曲」（弹层里已有专辑上下文，"专辑"二字可省），弹层标题保持全称。

### 6.2 入口路径与显示条件（已定）

**位置**：`专辑页 → ⋮（专辑信息编辑弹层）→ 拆分为单曲`，菜单项插在 **「批量迁移歌曲」与「Agent」之间**（即 Agent 上面）。

```
专辑信息编辑
  ├ 调整歌曲顺序
  ├ 文本调整顺序
  ├ 修改专辑信息
  ├ 更换专辑封面
  ├ 批量迁移歌曲
  ├ 专辑整轨拆分为单曲   ← 新增（Agent 上面）
  └ Agent
```

**显示条件（已定：不再设时长门槛）**：

| 条件 | 说明 |
|---|---|
| 专辑类型为「单曲」（`classifyAlbum(...) == AlbumCategory.Single`） | 覆盖 1–3 首的专辑。**45 分钟的全长整轨专辑因为只有 1 个文件，本身就是 Single** —— 所以"全长专辑"和"8 分钟串烧"都能进 |
| **不设时长门槛** | 8 分钟的串烧、3 分钟的整轨单曲都可以用这个功能 |
| 专辑下只有 1 个物理文件 | 直接进入拆分流程 |
| 专辑下有 2–3 个物理文件 | 仍显示入口；进入后**第一步先让用户选要拆哪个文件**（多一个选择步骤） |
| 已拆分的专辑 | 菜单项变为 **「编辑拆分」** + **「取消拆分（合并回整轨）」** |

**实现要点**：条件判断放在 `AlbumMenuSheet`（`AppRoot.kt:2137-2185`）里，用 `classifyAlbum(vm.albumOrder(key), typeOverride)` 与 `albumOrder(key).size` 求值。注意 `albumTypeFor()` 返回的是**手动覆盖优先**的结果 —— 若用户手动把单文件专辑标成「专辑」，`classifyAlbum` 会返回 Album 而隐藏入口，因此这里用**曲目数**（`tracks.size == 1` 或类型为 Single）作为兜底判据，避免被手动类型覆盖误伤。

### 6.3 第一步：粘贴歌曲列表

```
┌──────────────────────────────────────────┐
│ 粘贴歌曲列表                              │
│ 一行一首，可带时间点。第一首固定从 0:00 开始│
│ ┌──────────────────────────────────────┐ │
│ │ 1. intro                             │ │
│ │ 2. 黑                                │ │
│ │ 03:05 自由式                          │ │
│ │ 4. 03:42 未命名                       │ │
│ │ ...                                  │ │
│ └──────────────────────────────────────┘ │
│              [取消]      [下一步]         │
└──────────────────────────────────────────┘
```

解析规则（`SplitListParser`，纯 JVM，可单测）：

```
逐行处理：
  1. 去掉行首序号：^\s*\d+\s*[.、)\]]?\s*
  2. 尝试匹配时间戳（行内任意位置）：
       ^\s*(?:\[|\()?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(?:\]|\))?\s*
       支持 m:ss / mm:ss / h:mm:ss / [mm:ss] / (mm:ss)
  3. 若匹配到 → startMs 取值；同时把时间戳从曲名里剔除
  4. 若没匹配到 → startMs = UNKNOWN(-1)
  5. 曲名 = 剩余文本 trim；空则跳过该行
  6. 第一首（ordinal==1）无论解析结果如何，startMs 强制 = 0
  7. 校验：已定的 startMs 必须严格递增，否则把冲突行标为"需修正"并在下一步高亮
```

### 6.4 第二步：编辑界面（时间轴校准）

```
┌──────────────────────────────────────────┐
│ ← 按时间点拆分为单曲                       │
├──────────────────────────────────────────┤
│  ┌────┐  专辑名                          │
│  │封面│  歌手名                          │
│  └────┘  45:12                          │
│  ▶ ──────▼─────────────────────────────  │   ← 整轨文件总时长
│         ▲      ▲   ▲  ▲    ▲   ▲          │   ← 已定起点插旗（旗标=序号）
│  02:14 / 45:12                           │
├──────────────────────────────────────────┤
│  1  intro                 0:00:00   ✎    │
│  2  黑                    0:03:05   ✎    │
│  3  自由式          ⚑未定  [设为当前] ✎   │
│  4  未命名          ⚑未定  [设为当前] ✎   │
├──────────────────────────────────────────┤
│ 还有 2 首未设置时间点                      │
│              [保存草稿]      [完成]        │
└──────────────────────────────────────────┘
```

| 元素 | 实现 |
|---|---|
| 迷你播放器 | 复用 `player.playQueue(listOf(容器Track), 容器id, null)`（容器模式），进度条覆盖整轨文件总时长 |
| 进度条拖动试听 | `player.seekToFraction(f)`（`PlayerController.kt:530`） |
| 插旗标记 | 遍历已定 `startMs` → 在进度条上按 `startMs / duration` 定位；未定的曲目不插旗，列表行显示「未定」徽标。**一眼看出哪些位置已标记、哪些还没标** |
| 拖到任意位置试听 | 拖动中暂停 ticker 的边界派生（编辑态禁用虚拟切换，容器模式天然没有切换） |
| 「设为当前时间」 | `vm.setSplitStart(index) { 取 player.positionMs }` —— **照搬 `syncLyricLine` 的形态**（`MainViewModel.kt:2886`），每行右侧一个 Clock 圆形按钮（`LyricsScreen.kt:445-456` 同款） |
| 手动输入时间 | 点时间栏进入编辑，接受 `mm:ss` / `h:mm:ss` / `ss`（纯秒数），失焦时归一化显示 |
| 覆盖更新 | 再次点击/编辑直接覆盖该行 `startMs`，无二次确认（与 `syncLyricLine` 一致） |
| 编辑后联动 | 改中间某一首的起点 → 只改它自己的 `startMs`；上一首的 `endMs` 自动跟随（因为 `endMs` 是派生的） |

### 6.5 完成时的未完成项处理

参考歌词打轴的既有先例（`syncLyricLine` 在"最后一行被点亮"时自动落库，`MainViewModel.kt:2905-2911`），**推荐两者都做**：

| 情况 | 行为 |
|---|---|
| 全部已定，点「完成」 | 落库 `status=1`，回到专辑页，立即生效 |
| 有未定项，点「完成」 | 弹确认框：**「还有 N 首未设置时间点，未设置的部分不会出现在专辑里。可以「保存草稿」稍后继续，或「继续编辑」。」** 三个动作：继续编辑 / 保存草稿 / 忽略并完成 |
| 「保存草稿」 | 落库 `status=0`，`startMs=-1` 的行保留但**不显示在专辑页**（只显示已定时间的曲目），专辑页顶部提示「拆分未完成 · 继续编辑」 |
| 中途退出（返回键） | 与「保存草稿」同 —— 已有输入不丢。这是歌词打轴已有的"允许中途保存进度"先例 |
| 草稿态下的统计 | 只对已定时间的曲目生效；草稿不影响完整专辑播放入口 |

---

## 7. 专辑页最终呈现形态

```
┌──────────────────────────────────────────┐
│ ←                                        │
│              ┌──────────┐                │
│              │  专辑封面 │                │
│              └──────────┘                │
│  专辑名                                   │
│  歌手名                                   │
│  专辑 · 2024-05-01                        │
│  [▶ 播放]  [⋮]                            │
├──────────────────────────────────────────┤
│  ┌────┐  完整专辑             45:12  ▶    │  ← 不带序号的整轨入口
│  └────┘  12 首 · 整轨未切割               │     点它 = 从头播到尾，计专辑播放
├──────────────────────────────────────────┤
│  1   intro                    3:05        │  ← 拆分单曲，与普通专辑曲目列表
│  2   黑                       3:37        │     完全一致的展示与交互
│  3   自由式                   4:11        │
│  …                                        │
│  12  尾声                     2:48        │
├──────────────────────────────────────────┤
│  12 首 · 45 分钟                          │
└──────────────────────────────────────────┘
```

- 顶部「完整专辑」行 = 容器 Track 的入口（封面缩略图 + 「完整专辑」标题 + 总时长 + 播放按钮），**不带序号**，满足"用封面/LOGO 位代表整张专辑，而不是某首拆分单曲的序号占位"。
- 专辑页现有的「播放」PillButton 语义改为「播放完整专辑」（容器模式），与顶部行同一个动作。
- 下方曲目列表复用现有的 `NumberedTrackRow`，**展示形式与普通专辑逐像素一致**（需求三）。
- 每行的 `⋮` 菜单（收藏 / 加歌单 / 改名 / 隐藏 / 歌词）全部照常工作 —— 因为它们是按 `mediaId` 工作的。

---

## 8. 实施拆分（commit 计划）与验证

按需求「分 commit、分别验证」，建议 6 个 commit：

| # | Commit | 内容 | 验证 |
|---|---|---|---|
| **C1** | `feat(db): 拆分单曲数据模型——album_split/split_track + play_event.kind` | 实体、DAO、`MIGRATION_21_22`、BackupManager 导出、`SplitListParser` + 单测 | 单元测试（迁移 / 解析器 / 备份往返）；**不改任何 UI，行为零变化** |
| **C2** | `feat(lib): 虚拟曲目注入 lib()——整轨替换为 N 首合成曲目` | `lib()` 注入、`libSignature()` 扩展、`libById` 挂容器、`foldersMap()` 走未展开物理曲目（§2.8）、R1–R6 风险修复 | 单测：给定 tracksRaw + split 行 → lib() 输出正确 id/duration/title/albumId；`trackById(containerId)` 命中；**foldersMap 仍返回 1 条** |
| **C3** | `feat(stats): 播放统计双口径——kind 分流 + 专辑级计数 + 过渡曲热度排除` | 查询层改造、VM 两个计数 map、`heatCountFor()` 收口 H1–H5、收听统计/最近播放/首页 rail 改造、阈值兜底 | 单测 `PlaySessionTracker`（短曲阈值）+ `heatCountFor`；装机验证 §3.6 的 7 条 |
| **C4** | `feat(playback): 无缝虚拟切歌——容器队列 + 边界元数据替换` | `VirtualTrack/SplitPlan`、`toQueueEntries` 收口、ticker 边界派生、`displayPositionMs`、next/prev、睡眠定时、`MediaSession.Callback` 接管通知栏 | **装机重点**：专辑连续播放无卡顿/爆音；播放页/通知栏标题随曲目更新；next/prev 正常 |
| **C5** | `feat(ui): 拆分录入——粘贴解析 + 时间轴校准（插旗 / 设为当前时间）` | 两步界面、插旗进度条、`setSplitStart`、手动输入、草稿/完成 | 装机：粘贴各种格式（带/不带时间戳/乱序号）；边听边点；改中间时间点；中途退出不丢 |
| **C6** | `feat(ui): 专辑页拆分呈现——完整专辑入口 + 编号单曲列表` | 「完整专辑」行、菜单项（Agent 上方）、已拆分状态、取消拆分 | 装机：专辑页布局；完整专辑播放统计；收藏/歌单/最近播放回归 |

**装机测试覆盖清单**（需求五）：

1. **拆分录入流程**：粘贴解析（带时间戳 / 不带 / 乱格式 / 中英文序号）、边听边点打轴、进度条插旗位置正确、手动输入、覆盖更新、中途退出保存草稿、未完成项提示
2. **统计口径**：完整专辑播放 → 只计专辑；拆分单曲播放 → 只计该单曲；各听满 30s 与听 20s 切走的区别；热度榜、收听统计周报、最近播放、首页 rail 四处一致
3. **过渡曲双口径**：interlude 计入播放次数但不进任何热度排序（H1–H5 五点逐一验证）
4. **下游功能**：收藏（我的喜欢）单首、加入自建歌单、歌单内播放、隐藏、改名、单首歌词（每首独立）、Agent 问答
5. **无缝播放**：专辑内连续播放 5 个边界以上，无卡顿/爆音；播放页标题与通知栏同步切换；进度条显示当前曲目坐标而非文件坐标；拖动进度条跨边界；上一首/下一首；睡眠定时
6. **入口条件**：45 分钟整轨专辑、8 分钟串烧都能在专辑 ⋮ 菜单看到入口（位置在 Agent 上方）
7. **视图展开范围**：歌曲列表 N 条、**文件夹列表仍 1 条**、专辑页 N 条 + 完整专辑行、歌手页 N 条、搜索能查到

---

## 9. 决策点

### 已定（用户已确认）

| # | 问题 | 结论 |
|---|---|---|
| ~~D1~~ | 功能命名 | **「专辑整轨拆分为单曲」**（菜单项 + 弹层标题同用此名） |
| ~~D2~~ | 短曲（≤60s）计数阈值 | **调整**，仅 `durationMs ≤ 60s` 时生效：`min(30s, max(5s, 时长/2))`。>60s 行为逐字不变 |
| ~~D3a~~ | 完整专辑播放的归属 | **只计入「专辑被播放次数」，不计入任何单曲**（`kind='album'` + 容器 id，数据层双重隔离） |
| ~~D4~~ | 拆分单曲的删除行为 | **不给单曲级删除**。曲目菜单不提供回收站，改为「删除整轨文件（会一并移除 N 首拆分单曲）」+ 强确认 |
| ~~D5~~ | 是否进歌曲 / 文件夹列表 | **分开处理**：歌曲列表**出现 N 条**；**文件夹列表保持 1 条**（拆分只是软件层面的划分，不改变文件性质）—— 见 §2.8 |
| ~~D6~~ | 入口显示条件 | **改为：专辑类型为「单曲」即可，不设时长门槛**。8 分钟串烧、45 分钟全长整轨都能用；入口放在专辑 ⋮ 菜单的 **Agent 上方** |
| ~~D7~~ | 草稿态（未定时间点）的曲目 | **不显示**（避免时长未知的幽灵行），专辑页顶部给「拆分未完成 · 继续编辑」提示条 |
| **新增** | 过渡曲（intro/outro，~30s） | **要计数，但不作为热度评判标准**（§3.4：`heatCountFor()` 在 5 个排序点排除；清理建议也排除） |

### 仍待确认（已给默认，不回答就按建议走）

| # | 问题 | 我的建议（默认） |
|---|---|---|
| **D3b** | 完整专辑播放是否计入"最常听**艺人**"榜 | **计入**。整轨会话就是在听这位歌手；不计入会让收听总时长里凭空少掉一块 |
| **D8** | 过渡曲是否参与「随机播放全库」 | **参与**（它本来就是专辑的一部分，跳掉反而破坏专辑听感）；若你希望随机时跳过，改 `playbackFiltered` 一行即可 |

---

*本方案确认后按 §8 的 C1→C6 顺序实施。*

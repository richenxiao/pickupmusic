# 备份与恢复 —— 现状盘点与方案研究

> 目标：让用户的配置与数据能**方便地**备份、并且**换机后真的能用**。
> 状态：**方案探讨阶段**（等你拍板后再实现）

---

## 0. 结论速览

| 问题 | 现状 | 严重度 |
|---|---|---|
| 备份覆盖范围 | 只备了 **10 张「元数据修正」表**；歌单、手动导入的歌词、手动排序、**全部设置与 API Key** 一个都没备 | 🔴 高 |
| 换机可用性 | 已备的 10 张表里有 **8 张按 `mediaId` 存**，而 mediaId 换机/重扫必变 → **恢复后全部对不上号** | 🔴 高（地基问题） |
| 自动化 | 完全手动，靠用户想起来点「备份」 | 🟡 中 |
| 云备份 | 现状的 SAF 导出**其实已经能存网盘**（用户选 Google Drive/OneDrive/坚果云等），但没有引导，用户不知道 | 🟡 中 |
| Android 系统备份 | `allowBackup="true"` 已开，但**国内无 GMS 用不了**；且 25MB 上限有静默失败风险 | 🟡 中 |

**一句话**：现在这个备份功能，最需要的不是"更方便地存"，而是**先把它备的东西补齐、并让它在换机后能认得出是哪首歌**。

---

## 1. 现状盘点：项目里到底有哪些"用户资产"

### 1.1 Room 表（25 张）

| 表 | 内容 | 现在备份 | 换机后能恢复吗 |
|---|---|---|---|
| `album_override` | 单曲级重挂专辑名 | ✅ | ❌ 按 mediaId |
| `album_info_override` | 专辑级 名称/歌手/封面/类型 | ✅ | ⚠️ 按 albumId（也会变） |
| `track_info_override` | 单曲 改名/歌手/备注/隐藏 | ✅ | ❌ 按 mediaId |
| `track_album_move` | 单曲迁移到正确专辑 | ✅ | ❌ 按 mediaId |
| `artist` | 歌手 + 合并别名 + 头像 | ✅ | ✅ 按 name |
| `song_artist` | 曲目-歌手关联 | ✅ | ❌ 按 mediaId（artistId 自增已做重映射） |
| `reading_override` | 振假名当て字（长按修正的读法） | ✅ | ❌ 按 mediaId + lyricsHash |
| `external_reading_evidence` | 外部假名 evidence 缓存 | ✅ | ❌ 同上 |
| `artist_image_cache` | 歌手写真自动源缓存 | ✅ | ✅ 按 name |
| `artist_image_override` | 歌手**手选**写真 | ✅ | ✅ 按 name |
| `album_split` / `split_track` | 整轨拆分方案（新） | ✅ | ✅ 有 `sourcePath` 兜底 |
| **`playlist`** | **歌单（含内置「我的喜欢」）** | ❌ | — |
| **`playlist_track`** | **歌单曲目** | ❌ | ❌ 按 mediaId |
| **`saved_lyrics`** | **歌词**（`autoSaved=0` 的**手动导入/确认的无法重新识别**） | ❌ | ❌ 按 mediaId |
| **`album_order`** | **专辑内手动拖拽的曲序** | ❌ | ❌ 存的是 mediaId 列表 |
| **`artist_album_order`** | **歌手页专辑手动排序** | ❌ | ⚠️ 存 albumKey |
| `album_art_cache` | 封面 URL + 主色 + 发行日期 | ❌ | ⚠️ 按 albumId（URL 可重新下载） |
| `ignored_folder` | 忽略的文件夹 | ❌ | ✅ 按路径 |
| `play_event` | 收听记录（最近播放 / 周报） | ❌ | ❌ 按 mediaId |
| `play_count` | 已废弃表 | ❌ | — |
| `token_usage_log` | Token 用量曲线 | ❌ | ✅ 无外键 |
| `new_album` | 「你的更新」未读 | ❌ | 运行态，不必备 |
| `trashed_track` | 回收站 | ❌ | 运行态，不必备 |

### 1.2 DataStore（21 个 key）—— **全部未备份**

| 分类 | key |
|---|---|
| 花时间配的**凭证/配置** | `deepseek_api_key`、`tavily_api_key`、`llm_providers`（各供应商的 key/model/baseUrl）、`llm_active_provider`、`fanart_api_key`、`lastfm_api_key`、`custom_image_sources`、`disabled_image_sources` |
| 使用偏好 | `dark_theme`、`gapless`、`auto_match`、`auto_save_recognition`、`playback_speed`、`retro_speed_mode`、`furigana_default_on`、`furigana_auto_verify`、`lyrics_color_mode` |
| 运行态 | `onboarded`、`first_scan_done`、`recent_ids`、`update_state` |

> Agent 的 LLM 配置与 API Key 是用户花最多时间配的东西，换机后要从零再填一遍 —— 这是最容易被忽略、但用户最痛的缺口。

### 1.3 文件系统

| 路径 | 内容 | 要不要备 |
|---|---|---|
| `filesDir/tracks_cache.json` + `_sig` | 扫描缓存 | ❌ 可重建 |
| `filesDir/cover_cache/` | 封面字节磁盘缓存 | ❌ 有 URL 可重下 |
| `filesDir/crash_logs/` | 崩溃日志 | ❌ |
| `filesDir/databases/shiyin.db` | 全部 Room 表 | 见上表 |
| `filesDir/datastore/settings.preferences_pb` | 全部设置 | 见上表 |

### 1.4 体积估算

歌词是唯一可能大的：平均 2–5 KB/首，1000 首 ≈ **3 MB**。加上其余表，整体备份 JSON 大概 **< 10 MB**。纯文本，可 gzip 压到 1–2 MB。

---

## 2. 三个核心问题

### 🔴 问题 1：备份内容有巨大缺口

当前 `BackupManager` 的定位是"**用户修正数据**"（注释里明确写"不导出 saved_lyrics / playlist* / play_count"）。这个定位在"防止重新扫描后丢修正"这个场景下是成立的，但对用户心里的"**备份**"来说差得远：

- **歌单（含「我的喜欢」）** —— 用户整理最久的资产，一个都没备
- **手动导入的歌词**（`autoSaved=0`）—— 这些**不可能靠"重新识别"找回来**（本地导入的 lrc、手改过的歌词）
- **专辑内手动曲序**（`album_order`）—— 明确的人工整理成果
- **全部设置与 API Key**

### 🔴 问题 2：`mediaId` 不稳定 —— 换机后备份等于废纸（地基问题）

这是最容易被忽略、也最致命的一点：

```
MediaStore 的 _ID 是设备本地自增主键，不是文件的稳定标识。
换机 / 恢复出厂 / 清空媒体库重扫 → 同一个文件会拿到完全不同的 _ID。
```

而备份里 8/12 张表是**按 mediaId 存**的（`track_info_override`、`playlist_track`、`saved_lyrics`、`reading_override`、`song_artist`、`album_order`…）。所以**现在的备份导入到新设备后，这些修正会全部挂到错误的歌上，或者直接变成孤儿**。

**解决方向**（`split_track` 已经打了样 —— 它同时存了 `sourceMediaId` 和 `sourcePath`）：

- 每条按 mediaId 存的备份记录，**额外带上文件路径 `dataPath`**（+ `sizeBytes` 作为校验）
- 导入时按 `dataPath` 在当前库里反查新的 mediaId；查不到就跳过并**在结果里如实报告**（"有 12 条修正找不到对应文件"）
- 专辑级（`albumId`）同样问题 → 用**专辑名 + 歌手**反查（`albumKeyOf` 已有 `"$album|$artist"` 这个兜底形态可以直接用）

> 顺带说：这个机制对"不换机、只是重新扫描"也很有价值 —— 用户改了文件夹结构/换了存储卡，mediaId 也会变。

### 🟡 问题 3：没有自动化，也没有恢复前预览

- 全靠用户想起来点「备份」；导出路径是 SAF 选择器，选过一次后**下次不会记住**
- 导入是**直接覆盖写库**，没有预览。误点导入一个旧备份就会把当前修正冲掉，无法撤销

---

## 3. 备份形式对比（本地 / 云盘 / 系统）

| 形式 | 优点 | 缺点 | 国内可用 | 适配度 |
|---|---|---|---|---|
| **A. SAF 手动导出/导入**（现状） | 零依赖；用户可选任意位置，**其实已经能存网盘**（Google Drive / OneDrive / 坚果云 / 群晖都注册了 SAF Provider） | 全手动、易忘；不记住上次路径 | ✅ | 保底，保留 |
| **B. 本地自动快照**（WorkManager 定时写私有目录，滚动保留最近 3 份） | 防手滑、防误操作；无网络依赖 | 不防丢手机/换机 | ✅ | ⭐ 建议加（成本低） |
| **C. WebDAV 自动上传**（坚果云 / Nextcloud / 群晖 / 阿里云盘 WebDAV） | **无需任何 SDK**，就是 HTTP PUT；用户自填地址+账号；**坚果云在国内可用**；项目已有"用户自配 URL"的先例（自定义写真源） | 需用户有 WebDAV 账号；要做冲突处理（保留远端多版本） | ✅ | ⭐⭐ **最契合本项目** |
| **D. Google Drive / OneDrive API** | 体验最好（OAuth 一次授权） | 需 OAuth 客户端、上架审核、**国内不可用** | ❌ | 不建议 |
| **E. 导出后走系统分享**（微信/QQ/邮件/隔空投送） | 零依赖，用户熟悉 | 手动 | ✅ | 建议作为导出的一个附加按钮 |
| **F. Android Auto Backup**（`allowBackup` + `fullBackupContent`） | 近乎零代码 | **依赖 GMS，国内基本用不了**；**25MB 上限**，超了会静默不备；不透明 | ❌（国内） | 仅作海外用户的额外保险 |

补充两点关于 F 的实测结论：

- 项目**已经开了** `android:allowBackup="true"`，且没有 `dataExtractionRules`。这在 Android 12+ 上意味着**换机时的 D2D 直接迁移也会带上 app 数据** —— 部分国产换机工具（小米/华为/OPPO 的搬家 App）会利用这条通道。**所以不要把它关掉**，这是一个零成本的额外保险。
- 但如果把歌词也算进"待备份数据"，25MB 上限就有静默失败风险 → 建议显式加 `dataExtractionRules` 把 `databases/` 与 `datastore/` 纳入、把大缓存排除。

---

## 4. 推荐方案（分层，按性价比排序）

### L1 — 地基：把备份补全 + 让它在换机后认得出歌（必做）

1. **扩展备份内容**（按"用户资产"分级）：

   | 级别 | 内容 | 默认 |
   |---|---|---|
   | 必选 | 现有 10 张修正表 + `album_split`/`split_track` + **设置与凭证**（DataStore 全部非运行态 key） | 勾选 |
   | 必选 | **歌单 + 歌单曲目**（含「我的喜欢」）+ **手动导入的歌词**（`autoSaved=0`）+ **专辑内手动曲序** + `ignored_folder` + `artist_album_order` | 勾选 |
   | 可选 | 收听记录 `play_event` + `token_usage_log`（纯统计） | 默认勾选，可取消 |
   | 不备 | `tracks_cache`、封面字节缓存、`new_album`、`trashed_track`、`play_count` | — |

2. **引入"文件重解析键"**（解决换机问题的核心）：
   - 导出：每条按 mediaId 的记录带上 `dataPath` + `sizeBytes`
   - 导入：`dataPath` → 当前库反查新 mediaId；查不到则跳过
   - 专辑级：`albumId` → 用「专辑名 + 歌手」反查
   - **导入结果如实报告**：「恢复歌单 3 个（12 首）、歌词 86 首、设置 21 项；**有 7 条修正找不到对应文件，已跳过**」

3. **备份文件格式**：`version` 升级到 2（带 schema 版本），保留向后兼容（能读 v1 老备份）。文件头加 `appVersion` / `exportedAt` / `trackCount`，便于用户辨认。

### L2 — 体验：防手滑 + 可撤销（建议做，成本低）

4. **恢复前预览**：先解析备份，弹出「本次将恢复：歌单 3 个、歌词 86 首、设置 21 项、修正 42 条」，确认后才写库
5. **恢复前自动快照**：写库前把当前数据存一份到私有目录（滚动保留最近 3 份），提供「撤销上次恢复」
6. **本地自动快照**：WorkManager 每天（或每次修改后 debounce）写一份到私有目录
7. **导出加「分享」入口**：导出后直接调系统分享面板

### L3 — 云：WebDAV 自动备份（推荐做，这是"方便"的关键）

8. 新增「备份到云盘」设置项：填 WebDAV 地址 / 账号 / 密码 → 测试连接 → 一键上传 / 下载
   - 兼容坚果云、Nextcloud、群晖、阿里云盘 WebDAV 等
   - 远端按 `pickupmusic-backup-YYYYMMDD-HHmm.json` 命名，保留最近 N 份，界面可列出并选择恢复
   - 凭证存 DataStore（**要不要纳入备份本身？** → 建议**不纳入**，否则备份文件泄露等于泄露云盘账号）

### L4 — 可选

9. `dataExtractionRules`：把 `databases/` + `datastore/` 纳入 D2D/Auto Backup，排除大缓存（让海外用户与国产换机工具也能沾光）

---

## 5. 待你拍板的决策点

| # | 问题 | 我的建议 |
|---|---|---|
| **B1** | 备份范围扩到哪一档 | 按 L1 的"必选"两档 + 可选统计；**歌单和手动歌词一定要进** |
| **B2** | 要不要做 WebDAV 云备份 | **建议做**。它是"更方便"的答案，且零 SDK 依赖、国内可用；先做"手动上传/下载"，自动上传放第二步 |
| **B3** | API Key 要不要进备份 | **进**（否则换机最痛）；但**备份文件要提示"含密钥，请妥善保管"**，且 WebDAV 的密码不写进备份文件 |
| **B4** | 收听记录 / Token 用量要不要备 | 备，但默认可选（它们只是统计，且 `play_event` 换机后按 mediaId 对不上，只能按"标题+歌手"尽力匹配） |
| **B5** | 恢复策略 | **合并**（同主键覆盖，其余保留）而不是"清空后写入"——避免误操作毁掉现有数据；并在恢复前自动快照 |
| **B6** | 要不要顺带修"手动导入歌词丢失" | 顺带修：`saved_lyrics` 只备 `autoSaved=0`（自动识别的可重新识别，没必要占体积） |

---

## 6. 需要你确认的一处事实

- **手选的本地歌手写真**：`artist_image_override` 里存的是 `url`。如果用户是从本地相册选的，这个 url 可能是 `content://` URI —— **换机后大概率失效**。需要确认选图时是否已把文件**复制进 app 私有目录**（若只是存了 URI，就得改成落盘 + 备份时带上图片文件）。
  这个我需要看一下 `ArtistImageOverride` 的选图实现才能给准话，确认后再补进方案。

---

## 7. 建议的实施顺序

```
C1  备份内容扩展 + 文件重解析键（mediaId → dataPath）+ 版本 2 格式      ← 地基，收益最大
C2  恢复前预览 + 恢复前自动快照 + 导入结果报告                          ← 防误操作
C3  本地自动快照（WorkManager 滚动 3 份）+ 导出后分享                   ← 防手滑
C4  WebDAV 云备份（手动上传/下载 + 远端版本列表）                        ← "方便"的关键
C5  dataExtractionRules（顺带让 D2D 迁移也能带上数据）                   ← 小改动
```

每个 commit 独立可验证；C1 完成后再谈云。

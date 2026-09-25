# 需求 2：多作者头像部分加载不出来（含收听记录页同类问题）

> 独立需求文档，可直接单独发给分析方。

## 现状

头像加载链路（两处同链路）：
- 多作者弹窗 `ArtistPickerDialog`（AppRoot.kt）：`fetchArtistAvatar(name)`（personOnly=**false**，允许专辑封面兜底）→ `vm.artistImage(name)` → `rememberCandidateArt(url, 40dp)`
- 收听统计页 `ListeningStatsScreen:246`：`fetchArtistAvatarPerson(name)`（personOnly=**true**，只找人物照）→ 同链路

**关键事实（用户 2026-09-12 确认）：歌手写真已经获取并保留在本地**——"网络源缺图"对已缓存歌手不成立。问题收窄为"图明明在本地，显示链路却断了"。

"本地"分三层：
1. URL 缓存（Room `artist_image_cache` 表）：存 URL+元数据（宽高、imageType）
2. 位图磁盘缓存（ArtCache diskCache）：下载过的图片字节
3. 手动 override（歌手页手选，最高优先级）

## 已排查事实（代码层）

1. **personOnly 过滤**（`ArtistImageResolver.kt:82`）：收听记录页 personOnly=true 时，缓存条目 imageType="album"（当时胜出的是专辑封面层）**视作未命中** → 重新走 photo 层 → 搜空 → null → 空框。明明本地有图（封面），头像却空。这最可能是收听记录页"有些能有些不能"的根因：photo 类型缓存命中正常，album 类型全空。该语义是 v1.3.5 有意设计（防封面横幅塞圆框截成半张脸）。
2. **名字断裂**：缓存按歌手页打开时的 canonical 名存的；多作者弹窗的名字来自 `splitArtists("歌手A & 歌手B")` 拆分，变体名/feat. 艺人名对不上 → miss → 现场联网解析 → 失败 → 空。
3. **旧缓存行 w/h=0**（`ArtistImageCache.kt:41`）：旧版本缓存的行没宽高数据，视作"未验证"不直接用 → 重新解析；解析失败还会 putFailure **覆盖掉原 URL 行**（把本来能用的 URL 也毁了）。
4. URL 尺寸替换（`100x100bb`→`${px}x${px}bb`，Apple CDN 语法）对 Discogs URL 不生效——次要。
5. 用户曾猜测"海报尺寸不一"——实际对应的是 imageType 分类（album/photo）与宽高探测值（w/h=0 旧行不被信任），不是尺寸本身。

## 方案分析要求

这条已经有相当扎实的代码排查基础，接下来要做的是给出实机验证方案区分根因，而不是继续静态猜测哪个是真正原因。

**请给出：**

1. **根因的实机区分方法**：设计一个带 logcat tag（如 AIM/PaletteTrace）的排查步骤，能让实际测试时明确看出某个加载失败的歌手具体卡在哪一步（比如日志打印"personOnly 过滤命中/网络源查无结果/归一化后名字/URL 替换后的最终请求地址"），使得每一次失败案例都能被归类，而不是留在"可能是这个也可能是那个"的状态。
2. **收听记录页和多作者弹窗的 personOnly 语义是否要统一**：给出推荐方案（是否统一、如果不统一各自的理由是否站得住）以及至少一个备选。
3. **空框兜底策略**：给出 2-3 个候选（专辑封面截中间方形、占位首字母头像、渐显等待动画等），说明各自的实现复杂度和适用场景，不要直接选一个替我拍板。
4. 风险点：这条改动涉及的是取图链路，注意评估会不会影响其他已经用这条链路的地方（比如播放页专辑封面、其他头像展示位置）。

先给出排查步骤和方案分析，不直接改代码。确认根因分布之后（可能需要先跑一轮实机排查收集几个具体失败案例），再决定具体修哪一层，分 commit 验证。

## 附：关键文件

- `app/src/main/java/com/shiyin/music/data/image/ArtistImageResolver.kt`
- `app/src/main/java/com/shiyin/music/data/image/ArtistImageCache.kt`
- `app/src/main/java/com/shiyin/music/MainViewModel.kt`（fetchArtistAvatar / fetchArtistAvatarPerson :518-548）
- `app/src/main/java/com/shiyin/music/ui/components/Components.kt`（rememberCandidateArt :707 / loadCandidateBitmap :257）
- `app/src/main/java/com/shiyin/music/ui/AppRoot.kt`（ArtistPickerDialog）
- `app/src/main/java/com/shiyin/music/ui/screens/ListeningStatsScreen.kt:246`

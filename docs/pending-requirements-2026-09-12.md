# PickUpMusic 待分析需求清单（2026-09-12）

> 用途：拿给另一个 Claude 做方案分析。每条含【现状】【需求】【已排查事实】三段，
> 分析产出请给出：推荐方案 + 备选 + 风险点，不要直接给"改好了"的结论。

---

## 需求 1：morph 歌词本动画——保持上一版运动样式，只优化帧率 + 调整内容缩小呈现

### 现状（上一版 v2 架构，已回滚到这个状态，commit 495b169）

动画结构（`LyricsMorphOverlay.kt`，progress p: 0=卡片, 1=全屏，全部视觉属性是 p 的纯函数）：

- **窗口层**：纯色圆角矩形（专辑取色背景），graphicsLayer 非等比缩放从全屏形变到歌词卡片矩形。零内容，只承载色块几何形变。
- **内容层**：真 LyricsScreen，全屏原生布局、**不缩放**，是窗口层的兄弟层（不在其变换子树内）。带三件东西：
  - 透明度交叉淡化（fracRange(p, 0.40, 0.65)）
  - 锚行位移跟踪 translationY（当前播放行随窗口从卡片中心升到屏幕中心）
  - drawWithContent clipRect 裁剪到窗口当前矩形（内容只在窗口内可见）

收起动画视觉：**窗口非等比形变（全屏矩形 → 卡片矩形）+ 内容 clipRect 渐进裁剪 + 内容淡出**。
展开动画是反函数。

已做帧率优化（commit a6b3fb0）：组合零订阅 progress（snapshotFlow 一次性门）、
artBg 改 ArtCache 直读、所有逐帧视觉走 graphicsLayer 延迟读。
实测（release 包 gfxinfo）：打开动画窗 50th=5ms/99th=22ms，收起窗 50th=7ms/99th=16ms。

### 需求（用户 2026-09-12 原话）

"在上一版基础上提高帧率，并且改一下动画运动时内容的呈现的缩小效果"——
即：**窗口的运动轨迹/形变方式保持上一版不动**，只做两件事：
1. 帧率进一步提高
2. 内容在动画中的"缩小呈现方式"改善——用户嫌之前的（clipRect 渐进遮挡+淡出）不好看，
   想要"内容跟着窗口一起缩小的感觉"（应用窗口拉大拉小的隐喻），但**不是**我们后来试的
   "方案A 整窗等比缩放"（那个被否决：整窗等比在末段需要从窄高窗摊开到扁卡片，
   观感很差，用户评价"很难看"）。

### 需要分析的核心问题

如何在**保持窗口非等比形变轨迹**的前提下，让内容层的呈现读作"跟着窗口缩小"而不是
"被裁剪遮挡"？候选方向（供分析参考，不限于此）：
- 内容层加等比 scale（只内容缩放、窗口形变不动）——缩放锚点/缩放比例曲线怎么定才
  不和窗口的非等比矩形错位（内容比窗口大/小露出底色）？
- 内容层 scale 与 clipRect 并用（裁剪边界=窗口矩形，内部内容再等比缩）
- 其他方案

### 约束（历史教训，务必遵守）

- 窗口层几何形变公式（transformOrigin 左上锚 + lerp 补偿）已真机验证精确落卡，不动。
- 三触发路径（返回键/系统手势/顶部下拉）、指针闸门、中断弹回逻辑不动。
- 歌词卡片 1096×359（0.33:1）vs 全屏 1264×2780（2.2:1），宽高比差 6.7 倍——
  任何"内容等比缩放"方案必须回答"内容与窗口形状不匹配时多出来的区域怎么处理"。
- 分 commit、装机验证、全过再推送。

---

## 需求 2：多作者选择弹窗 & 收听记录页的歌手头像——部分加载不出来

### 现状

头像加载链路（两处同链路）：
- 多作者弹窗 `ArtistPickerDialog`（AppRoot.kt）：`fetchArtistAvatar(name)`（personOnly=**false**，
  允许专辑封面兜底）→ `vm.artistImage(name)` → `rememberCandidateArt(url, 40dp)`
- 收听统计页 `ListeningStatsScreen:246`：`fetchArtistAvatarPerson(name)`（personOnly=**true**，
  只找人物照，不拿专辑封面/banner 兜底）→ 同链路

### 现象（用户报告）

"多作者头像有些可以加载出来有些不能，我在收听记录中也遇到这个问题过"
用户猜测原因："是不是海报尺寸不一导致的"

### 已排查事实（代码层）

1. `ArtistImageResolver.resolve(canonical, personOnly)`：override→cache→自动源(Discogs 等)。
   personOnly=true 时专辑封面类图被过滤——**该歌手只有专辑封面图、没有人物照时，
   personOnly 解析为空 → 头像框空**。收听记录页用的正是 personOnly=true。
   多作者弹窗用 personOnly=false（本该有专辑兜底），加载不出来的可能是：
   该歌手在 Discogs 没有主艺人页/图片（冷门歌手、feat. 艺人、拼写差异）。
2. `rememberCandidateArt`（Components.kt:707）：produceState 异步下载位图。
   **url 为空串时直接返回 null → 占位图标**；下载失败也 null。
   磁盘缓存按 px bucket 分辨率存储；下载走 OkHttp 单次请求 8s 超时。
3. `loadCandidateBitmap`（Components.kt:257）：URL 里 `100x100bb`→`${px}x${px}bb` 替换
   （Apple CDN 尺寸语法），Discogs URL 不匹配该模式则按原 URL 下载。
4. 名字归一：`resolveArtist(name)` 经别名表（aliases）归一后再解析——别名没录的歌手
   （"歌手A & 歌手B" 拆分后的名字 vs Discogs 艺人名不一致）会解析 miss。

### 需要分析的问题

- "有些能加载有些不能"的根因排序（personOnly 过滤 vs 网络源缺图 vs 名字不匹配 vs
  尺寸替换 URL 失效）——需要的话给一个带 logcat tag（AIM/PaletteTrace）的实机排查步骤。
- 收听记录页（personOnly=true 空则空框）和多作者弹窗（personOnly=false）语义要不要统一？
  圆框头像场景 personOnly 防止"封面横幅截成半张脸"，但空框也不好——
  兜底策略（专辑封面截中间方形? 占位首字母头像? 渐显等待?）怎么选。

---

## 需求 3：歌词跟行滚动帧率仍低——"一顿一顿的"，确认优化是否真的生效

### 现状

已做两个优化（commit eca0b54 + 25044d3）：
1. @Immutable 四连（LyricLine/ParsedLyrics/LoadedLyrics/RubySegment）——
   数据稳定性标注，item 跳过能力
2. `activeI` 改 `derivedStateOf`——读点订阅行推进结果（每 2-5s 一次）而非
   positionMs ticker 原始值（每 300ms 写一次），消除全页 300ms 重组风暴

但用户实测（release 包）："歌词滚动的帧率仍然很低，一顿一顿的"。

### 跟行滚动的动画链（需要重点分析）

```
activeI 变化(每 2-5s 一次)
  → LaunchedEffect(activeI) 触发
  → listState.animateScrollBy(d, tween(240, FastOutSlowInEasing))
  → LazyColumn 滚动 → 新行进入视口 → item 组合 + 26sp Text 测量布局
```

已知嫌疑（未验证）：
1. **每行 26sp Extrabold 大字 + 行高 36sp 的 Text 测量开销**——新行滑入时组合+测量，
   一帧内多个新 item 进视口可能超 16ms。
2. **行颜色动画 animateColorAsState(tween 220ms)**（每行一个状态机，行推进时
   旧/新行各起一个颜色动画）——组合级状态，每帧驱动重组这些行的 lambda。
3. **RubyText 分支**（日文行）：RubyText 自绘双动画状态机（hp/kp animateFloatAsState），
   26sp+假名排版比纯 Text 贵得多。
4. `activeI` derivedStateOf 修复是否真的跳过了中间值——**关键疑点**：derivedStateOf 的
   读点若在 item lambda 内（`i <= activeI`），行推进时仍会重组整个 items 块
   （LazyColumn 的 item 内容 lambda 读 activeI → 行推进 = items lambda 重组），每次
   行推进全列表可见行重组合。@Immutable 只帮"参数没变跳过 Composable 调用"，
   不帮"lambda 本身失效重跑"。
5. **预滚 LaunchedEffect(track.id) 用 scrollToItem**（无动画）——切歌瞬间一帧跳变。

### 已确认的无关项

- 不是 debug 构建（release 包，R8 minify=true）
- 不是 positionMs ticker 风暴（已修，且滚动期间 activeI 不变，derivedStateOf 不重算）

### 需要分析的问题

"一顿一顿"最可能的根因排序 + 验证手段（Compose Composition tracing 实机抓每帧
重组数/耗时，或 Layout Inspector 重组高亮）+ 修复方案。注意约束：不要一次改多处，
先定位（用户规矩：先出结论附依据，再动手，分 commit 分别验证）。

---

## 附：仓库关键文件

- `app/src/main/java/com/shiyin/music/ui/screens/LyricsMorphOverlay.kt` — morph overlay
- `app/src/main/java/com/shiyin/music/ui/screens/LyricsScreen.kt` — 歌词列表/跟行
- `app/src/main/java/com/shiyin/music/ui/screens/PlayerScreen.kt` — 播放页/歌词卡片
- `app/src/main/java/com/shiyin/music/ui/components/LyricsMorphState.kt` — 动画状态机
- `app/src/main/java/com/shiyin/music/ui/components/Components.kt` — ArtCache/取图链路
- `app/src/main/java/com/shiyin/music/MainViewModel.kt` — VM 状态
- 分支 `feature/furigana-llm-assist`，最新 commit 已回滚方案A

## 附：本轮已落地且用户未推翻的 commit

f80b630 崩溃修复 / a6b3fb0 帧率优化 / eca0b54+25044d3 @Immutable+derivedStateOf /
31be2ae 菜单瘦身 / c446d49 toast 修复 / e553fe2 多作者头像链路（样式保留，问题见需求2）

# 歌词本翻译 —— 前端（界面 + 动画）交接方案

> 交接日期：2026-09-16
> 上游：接手会话 `f5b325e5` 时落地的需求6后半段
>
> **状态：✅ 已落地（2026-09-16 13:08）。** 用户要求"前端交给更擅长前端的模型"后，
> 又决定由当前模型（Kimi K3）直接完成。本文档因此从"待做方案"转为
> **「已落地记录 + 设计语言参考 + 真机验证清单」**——§2 起的设计语言部分仍是
> 后续任何歌词本 UI 改动的权威参考。

---

## 0. 已落地内容（2026-09-16）

改动**只有一个文件**：`app/src/main/java/com/shiyin/music/ui/screens/LyricsScreen.kt`（**+132 / -21**）。

### 0.1 「譯」按钮（繁体，用户已定）

- 落点：底栏播放键右侧（原 38dp 空占位槽）。
- **左槽改为 `if (hasKana) あ else Spacer(38.dp)`**，右槽恒为「譯」→ 左右各 38dp，
  **播放键在三处（日语歌 / 非日语歌 / 无歌词）都保持居中**。
- 与「あ」**同 38dp、同一行、垂直居中平行**（用户明确要求）。
- 三态：
  | 态 | 形状 / 背景 | 内容 |
  |---|---|---|
  | 初始 | 无底色悬浮 | 「譯」`artFg` 18sp ExtraBold |
  | 进行中 | **实心圆**（同「あ」激活态）+ 圆周**顺时针加载弧** | 「譯」`safeBg`（弧：淡轨道 + 100° 圆头亮弧旋转） |
  | 已译 | **圆角方块** `artFg` 实心 | 「譯」`safeBg` + 右上角**纯文字 `AI`**（7sp，**无圆圈底**） |
- **注意 2026-09-16 的修正**：初版用确定性 `CircularProgressIndicator`，首轮 `done` 恒为 0
  → 只剩一圈淡轨道，用户看到的是"静止的空心圈【O】"。现改为连续顺时针旋转，且**只在
  进行中才驱动帧**（`LaunchedEffect(spinning){ withFrameNanos 循环 }`，不用
  `rememberInfiniteTransition`——它会常驻帧时钟、阻止 app idle，本页对帧率极敏感）。
- 点击语义（**与振假名一致：数据持久化，按钮只管显示/隐藏**）：
  `running→取消` / `hasContent→隐藏(数据留)` / `hasData→立刻显示(不调 API)` / `else→翻译`。
- 无涟漪（`indication = null`）、`if (interactive)` 门住点击——与「あ」/返回键同一写法。
- CJK 单字居中用 `LineHeightStyle.Trim.Both`（同「あ」，v1.2.0 #1b）。

### 0.1b 翻译结果持久化（2026-09-16 追加）

用户反馈"再点一下又没有了，怀疑没做持久化"——确实没做，现已补上：

- 新表 `lyrics_translation`，主键 `(mediaId, lyricsHash)`（与振假名 evidence 同键法），
  `payload` 存 `Map<Int,String>` 的 JSON。DB **21 → 22**（`MIGRATION_21_22`）。
- **译完即存**（`MainViewModel.translateLyricsWithAi` 收尾处），只在有成果时写。
- **进入/切歌时同步**：`syncLyricsTranslation(ly)` —— 同曲同版本直接返回（关掉歌词本再打开
  不会误杀进行中的翻译，也不重置用户隐藏的状态）；换曲/换源才清场 + 从 DB 恢复（有则直接显示）。
- 歌词本编辑面板「确认并保存到本地」**下一行**新增一行两个按钮：
  **删除振假名缓存**（只清外部 evidence，**不碰**用户手改的 Song Override）、
  **删除翻译缓存**。两者都按 `(mediaId + lyricsHash)` 精确定位，不牵连其它歌。

### 0.1c 「切歌」与「取消」是两件事（2026-09-16 追加）

之前两者共用一个 `clearLyricsTranslation()`（都 cancel 任务）。问题是：**中途切歌会把已经
烧掉 token 的翻译直接扔掉**——一首歌 15~40 秒，用户很可能等不及就切走了，那些钱就白花了。

现在拆成两个：

| 方法 | 语义 | 调用点 |
|---|---|---|
| `clearLyricsTranslation()` | 只复位**显示状态**，**不 cancel 任务** | 切歌 / 换歌词源（`syncLyricsTranslation`） |
| `cancelLyricsTranslation()` | **真取消**（中止 LLM 调用）+ 复位 | 进行中再点「譯」、删除歌词、删除翻译缓存 |

任务收尾时有一道守卫：`stillCurrent = lyricsTranslation.mediaId == ly.mediaId`。
切歌后 `reset()` 把 mediaId 清成 null → 守卫不成立 → **不回写显示状态**（新歌不被污染），
但**落库照旧**（token 已经花了，结果必须留下，下次回到这首歌直接恢复）。

> 为什么"删除"要真取消：只复位显示的话，任务跑完会把刚被删掉的译文**重新写回库里**，
> 变成永远读不到的孤儿行。

### 0.2 译文渲染

- 每行原文下方一行小字：`body(13.5f, Normal, transC)`、`lineHeight = 19.sp`、`padding(top = 2.dp)`。
- 新增 `transC = LyricText.copy(alpha = 0.72f)`（比 `dimC` 0.55 亮一档：译文是内容不是装饰），
  **不跟随行激活变色**（避免译文单独闪）。
- **`weight(1f)` 从原文两分支移到外层 `Column`**，两个原文分支改 `fillMaxWidth()`
  （RubyText / Text 二选一的结构与 `furiganaOn` 外的常驻挂载**原样保留**）。
- **缺行的行直接留白**，不渲染任何占位文案（数据层"宁可缺行不可错行"纪律的延伸）。

### 0.3 切歌清理

```kotlin
LaunchedEffect(ly.mediaId) { vm.clearLyricsTranslation() }
```
与 `clearFuriganaAssistStatus()` 并列（同一个 F7 防残留套路）。

### 0.4 我做的一个设计决定（已被用户纠正）

~~已译时再点 = 关闭（清掉译文）~~ → 用户反馈"再点一下又没有了"，
**已改为与振假名一致的"数据持久化 + 按钮只管显示/隐藏"**（见 §0.1 / §0.1b）。

### 0.5 验证结果

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | **246 用例全通过**（0 失败 / 0 错误 / 0 跳过） |
| `:app:assembleRelease` | BUILD SUCCESSFUL，APK **28,600,579 B（27.3 MiB）** |
| DB 迁移 21→22 | 迁移 SQL 经 Python `sqlite3` 实跑，`PRAGMA table_info` 与 Room KSP 生成的权威 CREATE TABLE **逐列一致** |

**尚未做**：真机验证（§7 清单）、离线档接线、设置页与语言包管理。

### 0.6 闪退根因（2026-09-16 用户报，已修）

`AppRoot.MiniPlayer` 里两处 `key(pageTrack.id)`：三个槽位的 key 只用 track.id，
**当"上一首/下一首回绕到同一首"时同层出现重复 key**，Compose 直接抛
`IllegalArgumentException: Key ... was already used` → 闪退。
触发场景：单曲队列 + REPEAT_ALL（`previousMediaItemIndex` 回绕到自身）、双曲队列
（prev 与 next 是同一首）；而项目 v4.3 会**播专辑自动开 REPEAT_ALL**，很容易撞上。
修法：`key(slotIndex, pageTrack.id)`。

---

## 1. 文件与落点（已实现，留作后续参考）

**`app/src/main/java/com/shiyin/music/ui/screens/LyricsScreen.kt`**

| 落点 | 锚点 | 状态 |
|---|---|---|
| 底栏右侧 38dp 槽 | `// 右侧平衡占位，与左侧「あ」等宽` → 已换成「譯」按钮 | ✅ |
| 每行歌词渲染 | `itemsIndexed(lines) { i, line ->` 里的 `Row(...)` → 已包 `Column` | ✅ |
| 切歌清理 | `LaunchedEffect(ly.mediaId) { vm.clearFuriganaAssistStatus() }` 旁 | ✅ |

**除了这个文件没有改任何东西。**

---

## 2. 设计语言（**后续任何歌词本 UI 改动的权威参考**）

### 2.1 配色：Organic 主题

定义在 `ui/theme/Theme.kt`，经 `LocalOrganic.current` 取用（歌词本里已取为 `val c = LocalOrganic.current`）。

| Token | Light（奶油纸） | Dark（深棕） | 用途 |
|---|---|---|---|
| `bg` | `#F5EAD8` | `#211D18` | 页面底 |
| `surface` | `#EBDDC5` | `#2B2620` | 卡片/面板 |
| `text` | `#201E1D` | `#F2E9DA` | 主文字 |
| `accent` | `#C67139` 陶土橘 | 同 | 主强调 |
| `accent2` | `#7A8A5E` 鼠尾草绿 | 同 | 次强调 |

整体气质：**暖调纸感 + 陶土橘 + 鼠尾草绿**，圆角偏大、阴影柔和、无 Material 冷灰。
`divider = text.copy(alpha=0.16f)`、`textMuted = text.copy(alpha=0.55f)` 是现成的弱化色。

### 2.2 字体：两套，别混用

```kotlin
fun body(size: Float, weight: FontWeight = Normal, color: Color)   // Figtree —— 正文/歌词
fun heading(size: Int, color: Color = text)                        // Caprasimo —— 标题/装饰字
```
（`ui/components/Components.kt`，已 import，直接用。）

**歌词本当前用的是 `body(...)`**，你的新元素也应留在 Figtree 家族内，除非刻意做装饰字（那才用 Caprasimo）。

### 2.3 按钮语言：「虚影终版」——**这是本 app 的招牌，必须沿用**

`「あ」` 振假名按钮（同文件底栏）就是范式，照抄它的手感即可：

- **无涟漪**：`clickable(interactionSource = remember { MutableInteractionSource() }, indication = null)`
  —— 全 app 统一，用户明确嫌涟漪不好看。
- **未激活态 = 无底色悬浮**：背景 `Color.Transparent`，字用 `artFg` 原色直接浮在背景上。
- **激活态 = 实心盘**：`Modifier.size(38.dp).clip(CircleShape).background(artFg)`，字用 `safeBg`。
- **尺寸 38dp**，与「あ」等宽（这是播放键居中的前提，见 §3.2）。
- **CJK 单字垂直居中**：必须用 `lineHeightStyle` 的 `Trim.Both`（`includeFontPadding` 在 Compose 1.6+ 已移除）。
  直接抄「あ」那段：

```kotlin
style = body(18f, FontWeight.ExtraBold, if (active) safeBg else artFg).copy(
    lineHeight = 18.sp,
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
    ),
),
```

### 2.4 歌词本自己的颜色变量（同文件顶部已算好，直接用）

```kotlin
val artBg   // 封面取色背景
val artFg   // 与 artBg 自适应的前景（白或深灰）
val safeBg  // = artBg
val dimC    // = LyricText.copy(alpha = 0.55f)  次级文字
val btnBg   // = LyricText.copy(alpha = 0.14f)  按钮底
val inactiveC // = LyricText.copy(alpha = 0.34f) 非活动歌词行
```

---

## 3. 需求 A：「譯」按钮（**按钮字用繁体「譯」，已定**）

> 用户明确：「要繁体的譯」。

### 3.1 三态视觉

沿用「あ」的虚影语言，但**用方形底块与「あ」的圆盘区分**（避免两个功能按钮看起来是一类）：

| 态 | 视觉 | 含义 |
|---|---|---|
| **初始（Off）** | 「譯」无底色悬浮，`artFg` 色 | 翻译关闭 |
| **AI 进行中** | 方形底块 + 小转圈（或进度环） | 正在翻译 |
| **AI 已译** | **方形** `artFg` 底块 + 角标（右上小圆内 `AI` 两字，约 7sp） | 已由 AI 翻译 |

- 方形圆角建议 `RoundedCornerShape(11.dp)`（38dp 见方）——与「あ」的 `CircleShape` 形成对照。
- 角标是**必要信息**，不是装饰：将来离线档也要用同一按钮（那时角标换成别的标识），用户需要一眼分辨译文来源。
- **进行中再点 = 取消**（回初始态）。调 `vm.clearLyricsTranslation()`。

### 3.2 落点与布局（不要破坏播放键居中）

底栏那行是 `Row(horizontalArrangement = Center)`，结构为：

```
[あ (仅日语歌, 38dp)]  [weight(1f)]  [播放键 58dp]  [weight(1f)]  [右侧占位 38dp (仅日语歌)]
```

现状：**只有日语歌才有右侧占位**，非日语歌右侧是空的——但播放键因为左右各有一个 `weight(1f)` 仍然居中。

你的改动：
- **把右侧占位换成「譯」按钮**（永远存在，所有语种都有翻译需求）。
- 非日语歌时左侧没有「あ」，需补一个 `Spacer(Modifier.size(38.dp))` 保持左右等宽 → 播放键仍居中。

**即：左右两侧各固定 38dp，无论有没有「あ」/「譯」。** 这是保持播放键居中的唯一条件。

### 3.3 交互与文案

- 点击（未翻译时）：`vm.translateLyricsWithAi()` 返回 `Boolean`。
  - `true` = 已发起。
  - `false` = **被前置条件挡下**，此时 `vm.lyricsTranslation.error` 已有原因文案（如"未配置大模型——请到 设置 → Agent 里填好 API Key 并选模型"），**请把它弹给用户**（`vm.showToast(msg, null)` 或你自己的轻提示），并停在初始态。
- 进行中显示 `vm.lyricsTranslation.progressLabel`（形如 `"翻译中… 23/58 行"`）。**建议**在按钮附近给一行小字，别只靠转圈——整歌翻译典型 15-40 秒，用户需要知道在动。
- 已有译文时再点：建议**先清掉再重译**（调 `clearLyricsTranslation()` 然后 `translateLyricsWithAi()`），或做成"再点 = 关闭"。**这条请你自己定并告知用户**。

### 3.4 切歌必须清状态（**容易漏，会出 bug**）

翻译状态是**按曲隔离**的（`mediaId`）。请加：

```kotlin
LaunchedEffect(ly.mediaId) { vm.clearLyricsTranslation() }
```

放在文件里已有的 `LaunchedEffect(ly.mediaId) { vm.clearFuriganaAssistStatus() }` 旁边。
不加的话，**上一首的译文会挂到新歌的行上**——振假名那边（code-review F7）已经踩过同一个坑。

---

## 4. 需求 B：译文渲染

### 4.1 位置与样式

**每行原文下方一行小字**，与振假名（行内上方）可叠加共存，互不干扰。

| 属性 | 建议值 | 理由 |
|---|---|---|
| 字号 | `13.5f` ~ `14f` | 原文 26sp；译文明显次级但**必须可读**（这是用户真正想看的内容，别做太淡） |
| 字重 | `FontWeight.Normal` | 与原文的 `ExtraBold` 拉开层次 |
| 颜色 | `artFg.copy(alpha = 0.75f)` 左右 | 比 `dimC`(0.55) 亮——译文是内容不是装饰 |
| 行高 | `19.sp` ~ `20.sp` | 与 26sp 原文（lineHeight 36sp）拉开，避免两行挤在一起 |
| 间距 | 原文与译文之间 `padding(top = 2.dp)` | 保持紧凑 |

### 4.2 布局改法（**注意 weight 的位置**）

现在每行是：

```kotlin
Row(...) {
    if (hasRubyLine) RubyText(..., modifier = Modifier.weight(1f), ...)
    else             Text(line.text, ..., modifier = Modifier.weight(1f))
    if (unsynced) { /* 同步按钮 */ }
}
```

改成：把 `weight(1f)` **移到外层 Column**，两个分支改用 `fillMaxWidth()`：

```kotlin
Column(Modifier.weight(1f)) {
    if (hasRubyLine) RubyText(..., modifier = Modifier.fillMaxWidth(), ...)
    else             Text(line.text, ..., modifier = Modifier.fillMaxWidth())
    val tr = if (vm.lyricsTranslation.hasContent) vm.lyricsTranslation.translationFor(i) else null
    if (tr != null) Text(tr, style = body(13.5f, FontWeight.Normal, transColor).copy(lineHeight = 19.sp), ...)
}
```

**`weight` 留在 Row 里、同时又塞进 Column 会编译不过或塌成 0 宽**——这是最容易踩的坑。

### 4.3 缺行 = 留白，**不要补任何占位**

`translationFor(i)` 返回 `null` 表示该行没有译文（模型漏译 / 对齐失败 / 该行是空行或纯符号行）。
**请直接不渲染那一行小字**，不要显示"（无译文）"之类的占位——留白是设计的一部分。

> 这是数据层刻意的纪律：**宁可缺行，不可错行**。
> 离线路径尤其如此（整段译文对不齐时，只会挂到组内第一行，其余留空）。

### 4.4 动画（这是你的主场，请发挥）

数据层给的是**离散的 `Map<Int,String>`**，一次到位（不是流式逐字）。可做动画的空间：

- 译文**逐行淡入**（按行号错开 20-40ms 的小延迟），比整页"啪"一下出现高级得多。
- 行高变化用 `animateContentSize()` 或行高动画，避免歌词列表整体跳一下。
- 列表在滚动中时**不要**对每行都跑动画（可见行才有必要）。
- **务必注意**：`byLine` 是 `mutableStateOf<Map<...>>`，整批替换会触发一次全页重组。
  如果做逐行错开动画，请用 `graphicsLayer`/`animateFloatAsState` 驱动 alpha，**不要在组合期读动画进度值**。

---

## 5. VM 契约（你只能调这些，不要绕过）

```kotlin
// 状态（ui/components/LyricsTranslationState.kt）
vm.lyricsTranslation.mode        // Off / Offline / Ai
vm.lyricsTranslation.running     // Boolean
vm.lyricsTranslation.done        // Int
vm.lyricsTranslation.total       // Int
vm.lyricsTranslation.byLine      // Map<Int, String>   行号(0-based) → 译文
vm.lyricsTranslation.error       // String?  非空 = 需提示用户
vm.lyricsTranslation.mediaId     // Long?    归属曲目
vm.lyricsTranslation.hasContent  // Boolean  有可渲染译文（档位非关 且 至少一行）
vm.lyricsTranslation.translationFor(lineIndex: Int): String?
vm.lyricsTranslation.progressLabel: String?

// 动作
vm.translateLyricsWithAi(): Boolean   // false = 被前置条件挡下，原因在 .error
vm.clearLyricsTranslation()           // 取消进行中的调用 + 整体复位（也用于"再点=取消"）
```

**`LyricsTranslationMode.Offline` 目前是空的**——离线（ML Kit 端侧）尚未接线，
因为加那个依赖会让 APK 从 27 MiB 涨到 87 MiB（四 ABI 原生库 +60 MiB），**用户还没拍板**。
**请把 UI 写成"档位可扩展"**：按钮的三态视觉里给离线留出位置（方形底块 + 不同角标），
这样将来接线时你只需补一个状态分支，不用重做视觉。

---

## 6. 硬性约束（**违反了会引入用户已经骂过一轮的性能问题**）

1. **不要在组合期读 `vm.player.positionMs`。**
   它是 300ms ticker。歌词本已经用 `derivedStateOf` 把它收敛成 `activeI`（每 2-5s 变一次）。
   你新加的任何东西若直接读 `positionMs`，会把这个重组风暴重新引回来——**这是用户实测过的"一顿一顿"根因**。
2. **逐帧视觉一律走 `graphicsLayer` 延迟读**（不要在组合体里读动画进度再传给 modifier）。
3. **`interactive` 门要尊重。** 文件顶部有 `interactive: Boolean = true` 参数，预热期（`false`）时
   不要挂 `clickable`、不要订阅实时进度。你的「譯」按钮同样要 `if (interactive)` 门住点击。
4. **不要把 `RubyText` 改成条件挂载。** 文件里有一大段注释解释为什么振假名分支必须在
   `furiganaOn` 判断**之外**始终挂载（否则开关时动画状态机被销毁 → 瞬间跳变）。
   你重构 `Row` 内容时**保持这个结构**。
5. **`unsynced` 时的每行同步按钮（`Lucide.Clock`）不要动。**

---

## 7. 验证清单（改完请逐条过）

### 7.0 症状 → 原因 → 请反馈什么（真机验收看这张表最快）

| 症状 | 最可能的原因 | 请反馈 |
|---|---|---|
| 点「譯」立刻弹"未配置大模型" | LLM 未配置（设置 → Agent） | — （按提示配好即可） |
| 点「譯」弹"AI 翻译调用失败（…）" | 括号里就是 `LlmClient.lastError` | **括号里的原文**（网络/401/429/超时一眼可辨） |
| 点「譯」弹"AI 未返回可解析的译文" | 模型没按格式回 / 拒答 | 跑 `adb logcat -s LyricsTranslate:W`，把 `raw head(400)` 那行发我 |
| 只译出一部分（"已译 51/58 行"） | 模型漏行且补齐轮也没拿到 | 是固定某几行还是随机？固定的话可能那几行有特殊字符 |
| 转圈一直不停、很久没结果 | 单次调用撞 90s 读超时（慢供应商/思考型模型） | 你的供应商与模型名，我好判断要不要再放宽或分片 |
| 译文出现时歌词行跳走 | 跟行居中没生效（已修，若仍出现说明还有别的路径） | 是"跳一次就稳"还是"持续抖" |
| 切歌后还显示上一首的译文 | 按曲隔离失效 | 切歌路径（点下一首 / 滑迷你播放条 / 自动播完） |
| 重开歌词本译文没了 | 持久化没落库 | 跑 `adb logcat -s LyricsTranslate:W` 看有没有 `persist failed` |
| 点「譯」闪退 | 见 §0.6（已修重复 key）；若仍闪退说明还有别的 | `adb logcat -d -b crash \| Select-Object -Last 60` |

> 两条 logcat 命令：
> ```powershell
> adb logcat -s LyricsTranslate:W          # 翻译链路（含解析失败的模型原始输出）
> adb logcat -d -b crash | Select-Object -Last 60   # 闪退堆栈
> ```

### 7.1 功能清单

**编译与回归**
- [ ] `./gradlew :app:compileDebugKotlin` 通过
- [ ] `./gradlew :app:testDebugUnitTest` 通过（**当前基线 253 用例，0 失败**）
- [ ] `./gradlew :app:assembleRelease` 通过

**视觉与交互（需真机，release 包）**
- [ ] 「譯」按钮在三处（日语歌 / 中文歌 / 无歌词）位置都正确，**播放键始终居中**
- [ ] 非日语歌：左侧补了等宽空位，播放键没偏
- [ ] 初始态无底色悬浮，风格与「あ」一致（**无涟漪**）
- [ ] 点「譯」→ 未配置 LLM 时有明确提示且停在初始态（不发请求）
- [ ] 配好 LLM 后点「譯」→ 进行中有转圈 + 进度文案；15-40s 内出译文
- [ ] 译文逐行出现，字号/颜色次级但可读；缺行的行**留白**（无占位文案）
- [ ] 进行中再点 = 取消，回初始态（**且真的停止烧 token**——上游已修好取消语义）
- [ ] **切歌后译文与按钮状态被清干净**，新歌不会显示上一首的译文
- [ ] 振假名开关照常工作，开/关动画无跳变
- [ ] 歌词跟行滚动、逐行同步按钮、长按注音选择器、来源菜单、校准钟**全部不受影响**
- [ ] 深色 / 浅色两种主题下都好看（Organic 双套配色）

---

## 8. 明确不在本次范围（别顺手做）

| 项 | 为什么 |
|---|---|
| 离线（ML Kit）接线 | 等用户拍板 APK 体积（①接受 87.5MB ②只留 arm 两 ABI ≈54.5MB ③仅 arm64 ≈43.4MB ④放弃离线） |
| 设置页「翻译设置」与「语言包管理」 | 依赖上面的决策；且是设置页的活，不是歌词本的活 |
| 「默认 AI 校验」 | 用户还没说清指什么（自动 LLM 润色 / 振假名自动校验的类似物） |
| 目标语言设置 | v1 固定中文 |
| 改动 `data/translate/` 下任何文件 | 已单测覆盖（62 用例），有改动请另开讨论 |

---

## 9. 上游已完成的部分（供你理解边界，不必重读）

| 文件 | 作用 | 单测 |
|---|---|---|
| `data/translate/LyricsTranslationEngine.kt` | 离线：行分组 → 拼接 → 译后拆回对齐（宁可缺行不可错行） | 17 |
| `data/translate/LyricsTranslationProtocol.kt` | AI 线上协议：提示词 + `行号\|译文` 解析 | 20 |
| `data/translate/LyricsTranslationPipeline.kt` | AI 编排：整歌一次调用 → 缺行带语境补齐 → 汇总 | 17 |
| `ui/components/LyricsTranslationState.kt` | UI 状态机（本文档 §5 的契约） | 8 |
| `MainViewModel.translateLyricsWithAi()` | VM 入口：前置校验 / 取消 / token 入账 | — |

**共 239 用例全通过**（177 原有 + 62 新增），`compileDebugKotlin` / `assembleRelease` 均通过。

---

## 10. 想更进一步的话（可选，非必需）

如果你有余力想让这块更"像这个 app"，可以考虑：

- **译文与原文的层次**：原文 26sp ExtraBold、译文 13.5sp Normal——差距很大。若觉得译文太弱，
  可试 `FontWeight.Medium` + 略高的 alpha，而不是加大字号（字号一大就抢原文的视觉主导）。
- **「譯」按钮的激活态**：现在设想是方形实心块。若你觉得和「あ」的圆盘放在同一行过于"两个疙瘩"，
  可以试**无底色 + 字变 `artFg` 实心 + 下方一条 2dp 短横线**——更轻，也更符合"虚影"的调子。
  这是设计判断，你比我更有发言权。
- **角标**：`AI` 两字 7sp 很小，注意在深色/浅色下的可读性，必要时给角标一个描边或底色。

# 需求2排查方案：多作者头像部分加载不出来

> 2026-09-26 实机排查步骤设计

## 一、根因排序与实机区分方法

### 1.1 已知根因候选（按概率排序）

根据代码审查，"有些能加载有些不能"的根因排序：

1. **personOnly 过滤（收听记录页专属，概率最高）**  
   - 现象：收听记录页头像空框，但歌手页大写真存在、多作者弹窗也能显示
   - 根因：缓存里是 `imageType="album"`（专辑封面兜底），personOnly=true 时视作未命中 → 重新走 photo 层 → 搜空 → null
   - 代码位置：`ArtistImageResolver.kt:90`
   ```kotlin
   val cacheHit = cached != null && cached.url.isNotBlank() &&
       !(personOnly && cached.imageType.equals("album", ignoreCase = true))
   ```

2. **名字断裂（多作者弹窗专属，概率中）**  
   - 现象：多作者弹窗部分头像空，但音乐库同名歌手有写真
   - 根因：多作者弹窗的名字来自 `splitArtists("歌手A & 歌手B")` 拆分，变体名/feat. 艺人名对不上缓存的 canonical 名 → miss → 现场联网解析 → 失败
   - 代码位置：`AppRoot.kt` ArtistPickerDialog 调用 `fetchArtistAvatar(name)`，name 未经 `resolveArtist()` 归一

3. **旧缓存行 w/h=0 覆盖（历史遗留，已修但可能残留旧行，概率低）**  
   - 现象：明明本地有图（URL 非空），头像却空框
   - 根因：v1.3.6 之前的缓存行没宽高数据（w=0, h=0），旧实现视作"未验证"不信任 → 重新解析 → 失败还会 `putFailure` 覆盖掉原 URL 行
   - 代码位置：`ArtistImageResolver.kt:82-95`，v1.3.6 已修改为"URL 非空即信任"，但数据库里可能残留旧失败行

4. **网络源缺图（用户已明确排除）**  
   - 用户 2026-09-12 确认："歌手写真已经获取并保留在本地"
   - 结论：对已缓存歌手不成立，不是根因

### 1.2 实机区分方法（logcat tag: AIM）

**步骤1：复现并定位失败歌手**

在收听记录页和多作者弹窗中找到"加载不出来"的具体歌手名，记录下来（如"张三"、"李四 & 王五"）。

**步骤2：打开 logcat 过滤 AIM tag**

```bash
adb logcat -s AIM:D PaletteTrace:D
```

**步骤3：触发加载并读取日志**

| 日志输出 | 对应根因 | 下一步 |
|---|---|---|
| `cache valid: ... album ...` 然后头像仍为空 | **根因1（personOnly 过滤）** | 缓存命中但 imageType=album 被绕过 |
| `override hit` 但头像空 | 用户手选图 URL 失效（极少见） | 检查 artist_image_override 表 |
| `cache failure-fresh` | 旧失败锁未过期，6h TTL 内跳过重试 | 根因3（旧缓存覆盖）或真网络失败 |
| `photo tier exhausted (personOnly)` | **根因1确认** | photo 层搜空 + personOnly 拒绝 album 兜底 |
| `all tiers exhausted → cache failure` | 全源搜空，写入失败锁 | 名字不匹配 or 真网络失败 |
| 无 `AIM` 日志 | **根因2（名字断裂）** | 名字未经归一化，直接 miss 缓存且未触发解析 |

**步骤4：数据库排查（针对根因1/3）**

```bash
# 连接设备后进入数据库
adb shell
run-as com.shiyin.music
sqlite3 databases/shiyin.db

# 查询失败歌手的缓存记录
.mode column
.headers on
SELECT name, imageType, width, height, substr(url, 1, 60) as url_prefix, failUntilTs 
FROM artist_image_cache 
WHERE name LIKE '%张三%';
```

关键观察点：
- `imageType="album"` 且 `url` 非空 → 根因1（personOnly 绕过 album 缓存）
- `width=0, height=0` 且 `url` 非空 → 根因3（旧缓存行，v1.3.6 已修但可能残留）
- `url=""` 且 `failUntilTs > 当前时间戳` → 失败锁生效中
- 找不到记录 → 根因2（名字不匹配，缓存 miss）

### 1.3 预期结果分布

根据代码逻辑推测，实测后可能发现：

- **收听记录页**：根因1（personOnly 过滤）占主导（~80%），部分根因3（旧缓存行残留）
- **多作者弹窗**：根因2（名字断裂）占主导（~60%），部分根因1（相同歌手在两处语义不一致）

## 二、personOnly 语义统一建议

### 2.1 现状

| 位置 | personOnly | 语义 | 理由 |
|---|---|---|---|
| 收听记录页 `ListeningStatsScreen:246` | **true** | 只找人物照，不拿专辑封面兜底 | 圆框头像，防封面横幅截成半张脸 |
| 多作者弹窗 `ArtistPickerDialog` | **false** | 允许专辑封面兜底 | 有图比空框好 |

### 2.2 语义冲突

**问题**：同一歌手在两处的 imageType 可能不同（收听记录缓存 photo、多作者缓存 album），导致：
- 收听记录页空框（album 被 personOnly 绕过）
- 多作者弹窗有图（album 正常命中）
- 用户感知："同一个歌手，为什么一个地方有头像、另一个地方没有？"

### 2.3 推荐方案：统一为 personOnly=false（允许兜底）

**理由**：
1. **一致性优先**：同一歌手在所有位置语义一致，避免"薛定谔的头像"
2. **"有图"比"纯圆"重要**：空框的体验劣于"封面裁剪不完美"——至少有视觉锚点，而非空洞
3. **圆框裁剪有保险**：`rememberCandidateArt` 的裁剪逻辑可以处理横幅（ContentScale.Crop + 圆形 clip），最差也是"封面色块"而非"半张脸"
4. **photo 层优先级已保证**：v1.2.1 分层竞速让 photo 层先跑，album 只是兜底，不会"顶替"人物照

**改动**：
```kotlin
// ListeningStatsScreen.kt:246
- fetchArtistAvatarPerson(artist)  // personOnly=true
+ fetchArtistAvatar(artist)        // personOnly=false，与多作者弹窗统一
```

**备选方案A：统一为 personOnly=true（纯人物照）**

不推荐，理由：
- 加剧空框问题（photo 层搜空时两处都空）
- 多作者弹窗现状有兜底（用户未抱怨），改 true 会让它也空，体验降级

**备选方案B：保持不统一，但改善空框兜底**

如果坚持 personOnly 语义分离，则需实现"空框兜底策略"（见下节§3）。

## 三、空框兜底策略候选

### 3.1 方案对比

| 方案 | 实现复杂度 | 适用场景 | 优点 | 缺点 |
|---|---|---|---|---|
| **A. 专辑封面截中间方形** | 低 | personOnly 搜空时 fallback | 必有图，视觉连续 | 封面横幅可能截得难看 |
| **B. 占位首字母头像** | 中 | personOnly 搜空时 fallback | Material Design 常见模式，辨识度高 | 需要处理中文/英文/emoji、颜色分配 |
| **C. 渐显等待动画** | 低 | 加载中 | 告知用户"在加载中" | 加载失败后还是空框 |
| **D. 纯色占位图标** | 极低 | personOnly 搜空时 fallback | 实现最简单 | 无辨识度，视觉弱 |

### 3.2 推荐：方案A（专辑封面截中间方形）

**实现思路**：
```kotlin
// MainViewModel.kt fetchArtistAvatarPerson 改造
suspend fun fetchArtistAvatarPerson(name: String): String {
    val canonical = resolveArtist(name)
    val img = artistImageResolver.resolve(canonical, personOnly = true)
    if (img != null) return img.url
    
    // personOnly 搜空 → fallback 到该歌手的任意专辑封面
    val track = tracksGroupedByArtist.value[canonical]?.firstOrNull()
    return track?.let { albumArtPath(it.albumId) } ?: ""
}
```

**优点**：
- 零 UI 改动（`rememberCandidateArt` 已支持任意 URL + 圆形裁剪）
- 必有图（歌手有歌 → 有专辑 → 有封面）
- 与多作者弹窗兜底路径对齐

**缺点**：
- 封面横幅裁成圆形可能难看（但比空框好）

### 3.3 备选：方案B（占位首字母头像）

仅在用户明确要求"空框兜底必须美观"时考虑，实现成本中等：

```kotlin
@Composable
fun InitialAvatar(name: String, size: Dp) {
    val initial = name.firstOrNull()?.toString()?.uppercase() ?: "?"
    val bgColor = remember(name) { Color.hsl(name.hashCode() % 360f, 0.5f, 0.6f) }
    Box(
        Modifier.size(size).clip(CircleShape).background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
```

## 四、风险点

### 4.1 改动影响范围

**personOnly 语义统一（推荐方案）**：
- 影响文件：`ListeningStatsScreen.kt` 一处调用
- 影响链路：收听记录页头像加载 → `fetchArtistAvatar` (personOnly=false) → album 兜底可用
- 回归点：歌手页大写真（不受影响，已用 personOnly=false）、多作者弹窗（不受影响，已是 false）

**空框兜底方案A（fallback 到专辑封面）**：
- 影响文件：`MainViewModel.kt` fetchArtistAvatarPerson 一个函数
- 影响链路：personOnly 搜空时多走一次专辑封面查询（IO 开销小，albumArtPath 已缓存）
- 回归点：收听记录页加载逻辑不变（仍是 personOnly=true），只是搜空时多了兜底路径

### 4.2 数据库残留旧行

**风险**：v1.3.6 之前缓存的 `width=0, height=0` 行，v1.3.6 改为"URL 非空即信任"后不再是问题，但若旧行 URL 已失效，会走现场解析 → 失败 → putFailure 覆盖 → 新失败锁阻塞 6h。

**缓解**：
- 根因3（旧缓存覆盖）的实际影响已由 v1.3.6 修复大幅降低（不再主动丢弃 w/h=0 的有效 URL）
- 残留旧失败行会在首次重新解析成功后被覆盖（正常 URL + 正确 w/h）
- 若实测发现大量旧失败行残留，可考虑启动时一次性清理 `width=0 AND height=0 AND url!=''` 的行（类似 init 里的 `clearArtistImageFailures`）

### 4.3 名字断裂（根因2）修复方案

**问题根源**：多作者弹窗调用 `fetchArtistAvatar(name)`，name 是 `splitArtists()` 拆分的原始名，未经 `resolveArtist()` 归一。

**修复**（如果实测确认根因2占比高）：
```kotlin
// AppRoot.kt ArtistPickerDialog
artists.forEach { artist ->
-   val avatarUrl by produceState("") { value = vm.fetchArtistAvatar(artist) }
+   val canonical = remember(artist) { vm.resolveArtist(artist) }
+   val avatarUrl by produceState("") { value = vm.fetchArtistAvatar(canonical) }
```

**风险**：
- `resolveArtist` 是 VM 方法，需确认在 Composable 内调用的线程安全性
- 如果 alias 表未覆盖该变体名，仍会 miss（但至少和音乐库主体保持一致了）

## 五、实施步骤（分阶段验证）

### 阶段1：实机排查（必做）

1. 按 §1.2 步骤复现 + logcat 收集日志
2. 确认根因分布（personOnly 过滤 vs 名字断裂 vs 其他）
3. 数据库排查（确认是否有旧 w/h=0 残留行）
4. **输出**：具体失败案例清单 + 根因归类（如"10 个失败案例，8 个根因1，2 个根因2"）

### 阶段2：修复主根因（基于阶段1结果选择）

**如果根因1（personOnly 过滤）占主导**：
- Commit 1：personOnly 语义统一（`ListeningStatsScreen` 改 fetchArtistAvatar）
- 装机验证：收听记录页原空框歌手是否显示（album 兜底）
- 验证通过 → 推送

**如果根因2（名字断裂）占主导**：
- Commit 1：多作者弹窗名字归一（`ArtistPickerDialog` 加 resolveArtist）
- 装机验证：多作者弹窗原空框歌手是否显示
- 验证通过 → 推送

### 阶段3：空框兜底（可选，基于阶段2效果决定）

如果阶段2修复后仍有少量空框（真网络失败 or alias 表未覆盖）：
- Commit 2：实现方案A（专辑封面 fallback）or 方案B（首字母头像）
- 装机验证：剩余空框是否消失
- 验证通过 → 推送

### 阶段4：数据库清理（如果阶段1发现大量旧行残留）

```kotlin
// MainViewModel.kt init 块或 ArtistImageResolver init 块
scope.launch(Dispatchers.IO) {
    // 清理旧缓存行：w/h=0 且 URL 非空的行（v1.3.6 之前遗留）
    runCatching { 
        dao.execSQL("DELETE FROM artist_image_cache WHERE width=0 AND height=0 AND url!=''")
    }
}
```

装机后观察是否解决"明明有图却空框"的情况。

## 六、总结

**推荐修复路径**（最小改动 + 最大收益）：
1. **实机排查**（1-2 小时）→ 确认根因分布
2. **personOnly 语义统一**（5 分钟改动）→ 解决收听记录页 80% 空框
3. **名字归一（如需要）**（10 分钟改动）→ 解决多作者弹窗 60% 空框
4. **空框兜底（可选）**（30 分钟）→ 解决残留 ~5% 真失败/未覆盖变体名

**关键决策点**：
- 实机排查结果决定先修哪个根因（personOnly vs 名字断裂）
- 阶段2效果决定是否需要阶段3（空框兜底）
- 不要一次性改多处 → 分 commit、逐项验证、全过再推送

**下一步**：先做阶段1实机排查，拿到具体失败案例和根因分布数据，再决定具体修复方案。

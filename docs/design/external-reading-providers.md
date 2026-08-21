# 外部歌词假名校验 — Provider 调研对比与 V1.1+ 设计

> 目标：把网上已存在的人工标注歌词读音正式纳入振假名系统，作为 occurrence-level evidence，
> 把准确率推到接近 100%。JMdict + ContextResolver 退为离线兜底，外部 evidence 成为主力。
> 严格 occurrence 粒度（绝不 surface=何 全局替换），与 position-keyed Song Override 兼容。
>
> 状态：Provider 调研完成 + UtaTen E2E PoC（解析+对齐+occurrence）已通过单测；
> Android HTTP 集成进行中。本文件先输出对比与推荐顺序（实现前的强制交付物）。

---

## 1. 候选 Provider 实测对比（不预设能爬，逐个验证）

| 来源 | 有ふりがな? | 假名 HTML 结构 | 需 JS? | robots.txt | 搜索入口 | 技术可抓取? | 适合正式发布? |
|---|---|---|---|---|---|---|---|
| **UtaTen** (utaten.com) | **是** | `<span class="ruby"><span class="rb">漢字</span><span class="rt">かな</span></span>`，在 `class="hiragana"` 块 | **否**（server-rendered） | 允许 /lyric（仅禁 /manage,/outsourcing） | `/search/=/title=<q>=/artist_name=<q>=/` | **是（PoC 已验证）** | ⚠️ 需评估（见下） |
| uta-net (歌ネット) | **否** | 纯文本歌词（无注音） | 否 | 禁 /user/index_search 等，余允许 | 有 | 抓得到但**无假名**，无用 | — |
| J-Lyric.net | 否（多为纯文本） | — | 否 | `Disallow:` 空=全允许 | 有 | 无假名，无用 | — |
| 歌詞ナビ | 未确认 | — | — | robots 空 | — | 未验证有假名 | — |
| petitlyrics | 404（站点异常） | — | — | robots 404 | — | 站点不稳定 | 否 |

### 1.1 UtaTen 深度验证（唯一可用源）

- **页面结构**：歌词在 `<div class="lyricBody"><div class="hiragana">…</div></div>`。每个汉字
  用 `<span class="ruby"><span class="rb">漢字</span><span class="rt">かな</span></span>` 标注。
  `<br />` 为行边界。同一页另有 romaji 块（`<rt>` 为罗马字），解析器只取 `class="hiragana"` 块。
- **无 JS**：curl 直接拿到完整 ruby HTML（server-rendered），Android 用 `HttpURLConnection` 即可，无需 headless 浏览器。
- **robots**：`User-agent: *` 仅 `Disallow: /manage`、`/outsourcing`、`/outsourcing`；`/lyric/*` 与
  `/search/*` 未禁。ProxyPyBot 全禁（与本项目无关）。
- **搜索**：`GET /search/=/title=<urlenc title>=/artist_name=<urlenc artist>=/sort=score+DESC/`
  返回结果列表，每条含 `/lyric/<id>` 链接 + 标题/艺术家。匹配：取 title/artist 文本最相近的 `/lyric/<id>`。
- **Cloudflare/验证码/登录**：curl 普通 UA 可访问，未见验证码/登录墙（PoC 实测）。
- **E2E PoC 结果**（藤井風「何なんw」，单测 `UtaTenAlignE2ETest` 全过）：
  - 第一个「何」（`何があっても…`）→ **なに** ✓
  - `それは何なん` 的「何」→ **なん** ✓
  - 同行 `何で何も聞いてくれんかったん` 两个「何」→ 各自 **なん**，charStart 不同（**不归一**）✓
  - 空格差异（注入多余空格）经归一化仍对齐 ✓
  - 文本不匹配（版本差异）→ 返回 null（拒绝，不错位）✓

### 1.2 合规性评价（与"技术可抓取"分开，按 directive 要求）

- UtaTen 的ふりがな是其**自有原创内容**（与厂牌合作的衍生作品），涉及翻案权/著作権；
  利用規约通常禁止自动抓取。
- JASRAC/NexTone 词曲授权：UtaTen 已获，但**第三方再分发其假名标注**不在授权内。
- **结论**：技术上 UtaTen 完全可抓取且稳定（PoC 已证）；但**不适合未经评估直接进正式发行版**。
  正式发布路径选项：① 用户主动开启的"实验性外部假名"开关 + 明确告知"歌词文本将发送至
  第三方歌词站"；② 走 server-side adapter + 取得授权；③ 仅 dev/实验，不进发行版。
  本文件**不**因合规风险而否定技术可行性——PoC 已证明可实现。

---

## 2. 推荐顺序与多 Provider fallback

1. **UtaTen**（primary，唯一已验证 furigana 源）——PoC 已过。
2. **其他 furigana 源**（待发现/验证）——Phase 1 暂无第二个稳定源；J-Lyric/uta-net/歌詞ナビ
   均无假名。后续若发现 `プチリリ`恢复 或其他 ruby 歌词站，作为 fallback 加入。
3. **离线兜底链**（任何外部失败时）：JMdict 单读法 → ContextResolver 高置信 → Kuromoji → No Reading。

多 Provider fallback：Provider 接口 `ExternalReadingProvider`，按列表尝试，首个成功对齐的
evidence 入库（带 source/confidence）。多源一致 = 高 confidence（Phase 1 单源 = 中 confidence）。

---

## 3. 核心流程（已实现 PoC 的部分）

```
本地歌曲 metadata（title/artist/lyrics lines）
   ↓ async（首次遇歧义歌曲，off-render-path）
Provider.resolve(title, artist, localLines)
   ├─ 搜索 UtaTen → 匹配 /lyric/<id>
   ├─ 抓取歌词页 HTML
   ├─ UtaTenParser.parsePage → ParsedExternalLyric(surface + ruby runs)
   ├─ LyricAligner.align(parsed, localLines) → List<OccurrenceReading(lineIndex,charStart,length,reading)>
   └─ 失败（搜索/抓取/解析/对齐任一）→ null → 安全 fallback 到本地 FuriganaPipeline
   ↓ 成功
缓存到 (mediaId + lyricsHash) 的 evidence 表（source/confidence/fetchedAt）
   ↓ 后续播放
pipeline 优先用缓存 evidence（完全离线），不再联网
```

**对齐（最关键）**：归一化（去空白、全角→半角 ASCII）后逐字符 flat-match；外部 surface ==
本地 surface 才对齐，否则**拒绝导入**（绝不错位）。已处理：空格、换行、全角/半角。
不处理（→拒绝）：标点种类差异、歌词版本内容差异。后续可加 LCS fuzzy，但 fuzzy 须额外
保证不错位——Phase 1 用精确归一匹配，宁拒不错。

---

## 4. reading 优先级（已按 directive 调整）

```
1. 用户 Occurrence Override（position: lineIndex+charStart）   ← 最高，最终人工层
2. 多个外部来源一致的 reading（高 confidence）                  ← 跨 Provider 交叉验证
3. 单一可信外部来源明确标注的 reading（中 confidence）          ← UtaTen 等
4. ContextResolver 高置信结果（何が→なに 等 provable 规则）
5. JMdict 单一 reading
6. No Reading
```

外部来源冲突：不覆盖用户手动；单一网页不视为绝对正确（存 confidence，允许后续交叉验证）。
Phase 1 单 Provider（UtaTen）= 优先级 3（中 confidence）。多源一致（优先级 2）待加第二个源。

---

## 5. occurrence 粒度保证（绝不 surface=何 全局替换）

外部 evidence 与 Song Override 同坐标系：key = (lineIndex, charStart, length)。
同一行 `何で何も聞いてくれんかったん` 两个「何」分别存各自 (charStart, reading)，互不污染。
PoC 已验证：同行多「何」charStart 各异、读法各自（不归一）。

---

## 6. 已实现 / 进行中

- ✅ `ExternalReadingProvider` 接口 + `OccurrenceReading`/`ParsedExternalLyric` 数据类型
- ✅ `UtaTenParser`（HTML ruby 解析，JVM-pure，单测覆盖）
- ✅ `LyricAligner`（归一化 flat-match + 位置映射，单测覆盖）
- ✅ E2E PoC 单测 `UtaTenAlignE2ETest`（何なんw，4 项全过）
- 🚧 Android `UtaTenProvider`（HttpURLConnection 搜索+抓取，接 Parser+Aligner）
- 🚧 evidence 缓存表（Room，mediaId+lyricsHash+lineIndex+charStart+reading+source+confidence+fetchedAt）
- 🚧 pipeline 集成（external evidence 层，介于 Occurrence Override 与 ContextResolver）
- 🚧 VM 异步触发（首次歧义歌曲 off-render-path fetch + 缓存 + furiganaRevision 重算）

## 7. 边界与不做

- 不把网页抓取写进 FuriganaEngine（Provider 独立）。
- 联网不进正常渲染路径（async off-render，缓存后离线）。
- 不无限扩充静态 reading 规则（ContextResolver 仍只 provable 规则）。
- 外部失败必须安全 fallback，不影响歌词正文显示。
- 正式发布前合规评估（UtaTen 假名授权）——未评估不进发行版。

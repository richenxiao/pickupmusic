# 振假名 V1.1 — 准确率层实现报告

> 范围：纯本地、高准确率、错误安全、允许一次修正永久复用。不引入 Yahoo/Sudachi。
> 依据：`docs/design/furigana-accuracy-eval.md` 的评估结论。
> 分支：`feature/lyrics-furigana`。状态：实现完成，等真机验证。

---

## 1. JMdict 派生数据构建方式

- **构建脚本**：`tools/jmdict-build/build_jmdict_derived.py`（Python，离线，可重跑）。
- **输入**：`jmdict-simplified` 的 `jmdict-eng-common-*.json`（common-only 英文版，GitHub Release 下载，
  原始 ~16MB JSON，已 `.gitignore` 不入库）。
- **抽取逻辑**：遍历每个 entry 的 `kanji[]`（surface）×`kana[]`（reading，按 `appliesToKanji` 归属），
  聚合为 `{surface → [reading...]}`，**仅保留含汉字的 surface**（纯假名词不需 furigana 映射）。
  同一 surface 跨 entry 同形异读（homography，如 二人→ふたり/ににん 分属不同 entry）也合并，
  使多读法被完整保留 → 下游据此判 CONFLICT。
- **打包**：派生 JSON → gzip → `app/src/main/assets/furigana/jmdict_furigana_derived.json.gz`（随 APK 分发）。
  运行时由 `JMdictReadingDictionary` 后台懒加载（`org.json` 解析 + 片假名→平假名归一去重），
  未完成时 pipeline 跳过该层（安全降级，Kuromoji 兜底）。

## 2. 数据许可证与分发方式

- 上游：JMdict © EDRDG / Jim Breen，**CC BY-SA 4.0**。
- EDRDG licence 明确：**允许随闭源/商业软件打包分发并出售，软件无须开源**
  （https://www.edrdg.org/edrdg/licence.html）。所谓「禁止非自由软件再分发」=误传，已证伪。
- 派生数据继承 CC BY-SA 4.0：须保留 EDRDG 归属 + 许可声明（见
  `tools/jmdict-build/LICENSE-derivative.md`，运行时归属在「关于」页一并展示的规划见 §8）。
  App 代码本身无须开源（CC BY-SA 4.0 §4(a) Collection 规则）。

## 3. 最终条目数量与实际体积

| 指标 | 值 |
|---|---|
| 源 common words（含汉字） | 18,167 |
| 派生 surface 数 | **25,984** |
| ├ deterministic（单一读法） | 22,636 |
| └ CONFLICT（多读法） | 3,348 |
| 派生 JSON 未压缩 | 766 KB |
| 派生 gzip（随 APK） | **193 KB** |
| 构建耗时 | 0.91 s |
| APK 净增（含 JMdict gz，对比 v1.0.0 基线 ~48.5MB） | 约 +190 KB（gz 资产） |

## 4. 歌词特殊读法纠错表来源与规模

- **文件**：`LyricsReadingOverrides.kt`（自撰内存小表，`surface → 歌词常用读法`）。
- **来源**：PickUpMusic 自撰——词读法是事实、不可版权；**不从 JMdict/Wiktionary 抄录**
  （否则继承 CC BY-SA），以 MIT 随闭源 App 分发。
- **规模**：当前 ~20 词，**硬封顶 ~30**（见文件头「职责边界」注释）。只收「绝大多数歌词都这么读、
  且属 JMdict CONFLICT 或 Kuromoji 默认读错」的高频词：二人→ふたり、一人→ひとり、今日→きょう、
  明日→あした、昨日→きのう、大人→おとな、貴方→あなた、彼方→かなた、此方→こちら、
  其方→そちら、何方→どちら、此処→ここ、其処→そこ、彼処→あそこ、二十歳→はたち、
  何時→いつ、何故→なぜ、本当→ほんとう。
- **职责约束（硬约束）**：只是「第三方词典在歌词语境下无法确定读法时的少量高置信度偏好层」，
  不参与分词/活用/复合词切分（全归 Kuromoji），**不得演变成主引擎**，新词须满足三条准入门槛
  才能加，禁止为覆盖更多歌词不断加词。
- **不收录**：何（なに/なん 因歌而异 → Song Override）、方言读法 → Song Override。

## 5. Confidence 新规则

废弃 `isKnown=true → HIGH`。新四级（在 `FuriganaTokenizer.toSegments` 四参重载里实现）：

| 级别 | 触发 | 处理 |
|---|---|---|
| HIGH | Song Override 命中 / 纠错表命中 / JMdict 单一读法 / Kuromoji 单 token 已知且合法平假名 | 显示该读法 |
| CONFLICT | JMdict 多读法且无 override/纠错解析 | **不显示**（宁可无假名，不显错假名） |
| UNKNOWN | JMdict 未收录、Kuromoji OOV | 不显示 |
| LOW | OOV/`*`/异常拆分 | 不显示 |

优先级：**Song Override > 纠错小表 > JMdict（单读法 HIGH / 多读法 CONFLICT→无）> Kuromoji > No Reading**。
另有 `isValidKanaReading` 兜底：任何含罗马字母/空格/数字/`*` 的非法 reading 一律降级为不注音。

## 6. 真实测试集结果

`FuriganaPipelineTest`（注入假 `ReadingDictionary`，覆盖优先级与词级整体性）全部通过：

| 输入 | 期望 | 结果 |
|---|---|---|
| 何があっても（无 override） | 何=无注音（CONFLICT，不显错なに） | ✅ |
| 何があっても（override 何→なん） | 何=なん | ✅ |
| 二人で歩く | 二人=ふたり（整体，不拆 二/に+人/にん） | ✅ |
| 此方へ | 此方=こちら（整体） | ✅ |
| 真夏の海 | 真夏=まなつ（整体，不拆 真/ま+夏/なつ） | ✅ |
| 大人になる | 大人=おとな | ✅ |
| 明日は晴れる | 明日=あした | ✅ |
| 魏赵に捧ぐ（override 魏→たか/赵→こ） | 魏=たか、赵=こ | ✅ |
| 纯假名行 | 全无注音 | ✅ |
| JMdict 未加载（jmdict=null） | 真夏 仍 まなつ（Kuromoji 兜底） | ✅ |
| 多行拼接完整性 | surface 拼接恒等于原文 | ✅ |

`FuriganaTokenizerTest`（原 Kuromoji 行为）仍全通过——V1 路径无回归。

## 7. V1.1 相比原 Kuromoji 版本的错误减少

原 Kuromoji-only 版的典型错误及 V1.1 修复：

| 词 | 原（Kuromoji-only） | V1.1 |
|---|---|---|
| 二人 | 拆 二+人 → ににん（错） | 纠错表 → ふたり（整体）✅ |
| 何 | なに（默认，歌词语境常需なん） | CONFLICT → 默认无注音；Song Override→なん ✅ |
| 此方 | 拆 此(无)+方(かた)（破裂） | JMdict/纠错 → こちら（整体）✅ |
| 真夏 | まなつ（单 token，本来就对） | 仍 まなつ（JMdict 单一覆盖）✅ |
| 明日 | あした（单 token，对） | あした（纠错钉）✅ |
| 大人 | おとな（单 token，对） | おとな（JMdict 覆盖）✅ |
| 魏 | ぎ（Kuromoji 词典读法，歌词语境错） | 默认仍 ぎ（已知词读法错，V1.1 不自动拦 B/C 边界）；Song Override→たか ✅ |

净效果：CONFLICT 词（二人/此方/何…）不再硬显错读法——要么纠错表给对、要么默认无注音等用户修正。
OOV/破裂（此方）由 JMdict 覆盖修复。**词级整体性**保证（二人/此方/真夏 不被逐字拆错）。

## 8. 仍然无法解决的问题

- **B/C 边界：已知词、Kuromoji 给的读法在该歌词语境错**（如 魏→ぎ，歌词语境要 たか）。
  V1.1 不自动拦（只拦 CONFLICT/OOV/破裂），需用户 Song Override 修正。这是「纯词典引擎无上下文」
  的根本限制，不引入上下文模型无法自动解。
- **方言读法**（藤井風《何なんw》何→なん 等）：词典基于标准日语训练，不覆盖方言。
  走 Song Override（用户对那首歌设 何→なん，绑 mediaId+lyricsHash，永久复用）。
- **「关于」页 EDRDG/JMdict 归属显示**：许可要求归属可见，**尚未在 UI 加**（CC BY-SA 合规需补），
  正式发布前必须在「关于」页加 JMdict/EDRDG credit + 许可链接。**这是发布前阻塞项。**
- **Sudachi benchmark**：deferred（不阻塞 V1.1）。评估已确认换 Sudachi 不解决多读法（也一词一读法）且 +40MB。
- **Yahoo external resolver**：deferred（V2）。商用条款待确认 + 隐私冲突，不入 V1.1。

## 9. 与现有歌词系统的兼容性（已确认零影响）

- 未改：`raw LRC` / `LyricLine` / `LrcParser` / `ParsedLyrics` / 时间戳 / `activeIndex` / 滚动 / 切源 / 导入。
- Furigana 永远是独立 overlay：`produceState` 后台跑流水线，结果只影响 `RubyText` 渲染，不碰歌词存储。
- Song Override 存独立表 `reading_override`（DB v12），不写回 raw LRC/SavedLyrics。
- `deleteLyrics()` 级联清理该曲 Song Override，无孤儿。

## 10. 文件清单

新增：
- `tools/jmdict-build/build_jmdict_derived.py` + `LICENSE-derivative.md`（构建脚本+许可，原始 JMdict gitignored）
- `app/src/main/assets/furigana/jmdict_furigana_derived.json.gz`（派生数据，193KB）
- `app/src/main/java/com/shiyin/music/data/furigana/JMdictReadingDictionary.kt`
- `app/src/main/java/com/shiyin/music/data/furigana/LyricsReadingOverrides.kt`
- `app/src/main/java/com/shiyin/music/data/furigana/LyricsHash.kt`
- `app/src/test/java/com/shiyin/music/data/lyrics/FuriganaPipelineTest.kt`
- `docs/design/furigana-v1.1-report.md`（本文件）

修改：
- `app/src/main/java/com/shiyin/music/data/lyrics/FuriganaTokenizer.kt`（加 `FuriganaEngine` 接口 + `KuromojiEngine` + V1.1 四参流水线 + `isValidKanaReading` 兜底）
- `app/src/main/java/com/shiyin/music/data/db/AppDatabase.kt`（`ReadingOverrideEntity` + DAO + 迁移 11→12）
- `app/src/main/java/com/shiyin/music/MainViewModel.kt`（JMdict 加载 + 全局 override + `furiganaSegmentsFor` + `save/deleteReadingOverride` + 删除歌词级联）
- `app/src/main/java/com/shiyin/music/ui/screens/LyricsScreen.kt`（produceState→VM 流水线 + 长按编辑入口+对话框）
- `app/src/main/java/com/shiyin/music/ui/components/RubyText.kt`（V1.1 渲染：去竖排改 shrink-to-fit + segment 原子化 + 单布局，见前轮提交）
- `.gitignore`（tools 下原始 JMdict 不入库）

## 11. 架构边界（用户确认）

```
Kuromoji（成熟形态素库：分词/活用/复合词/助词/长句）  ← 基础分析与读音候选
   +
JMdict 派生词典（词典数据补充：CONFLICT 检测/覆盖）     ← 不是解析器，最长匹配在 token 边界上
   +
LyricsReadingOverrides（~20 词自撰偏好，封顶 30）       ← 只补歌词语境歧义，不膨胀
   +
Song Override（mediaId+lyricsHash，当て字/方言）        ← 用户歌曲专属
   =
无法确定 → 仅该词无注音，不影响整句显示                  ← 宁可无假名，不显错假名
```

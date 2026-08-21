# 振假名准确率策略重新评估（Furigana Accuracy Re-eval）

> 触发：V1 真实测试发现 `二人→ににん`、`何ん` 错误，且这些错误**不是** Kuromoji 的
> OOV/`*`/破裂拆分能抓到的。原 V1 设计「只筛 Kuromoji 明显错误」对「已知词、合法多读法」
> 无能为力。本评估重设准确率策略。**状态：评估完成，等待用户确认，不写实现代码。**

---

## 0. 结论先行

- 新核心原则：**词典 reading 只是候选，不是最终**。`isKnown=true` ≠ 可信。
- B 类（已知词多读法）**Kuromoji 与 Sudachi 都解决不了**——两者都是形态素解析，一词一读法，
  无法告诉你「这个词有多个合法读法」。**唯一能给多读法的是 JMdict（辞书，非形态素解析）。**
- 正解是**本地多层**：Kuromoji（base）＋ **JMdict 派生歧义词表**（检测多读法→CONFLICT→No Reading）
  ＋ **自撰 MIT 歌词纠错小词表**（解析高频 CONFLICT 的 preferred reading）＋ **Song Override**（ateji）。
- **Sudachi 不入 V1**：不解决 B 类核心（无多读法 API），词典 39.8–121 MB（vs IPADIC ~14 MB）。
- **Yahoo 不入 V1**：V2 才考虑（商用条款待确认 + 隐私冲突）。
- 优化目标是 **precision**，不是 recall——宁可无假名，不显错假名。

---

## 1. 本地 Kuromoji 实测 ground truth

| 词 | Kuromoji 行为 | reading | isKnown | POS | 最终呈现 | 类型 |
|---|---|---|---|---|---|---|
| 二人 | **拆 二+人**（IPADIC 不收录为复合词） | ニ+ニン | 均 true | 数+接尾 | **ににん** ✗ | B（覆盖/拆解） |
| 一人 | 拆 一+人 | イチ+ニン | 均 true | | いちにん ✗ | B |
| 二十歳 | 拆 二+十+歳 | ニ+ジュウ+サイ | 均 true | 数+数+接尾 | にじゅうさい ✗ | B |
| 大人 | 单 token | オトナ | true | 名詞/一般 | おとな ✓ | 正确 |
| 今日 | 单 token | キョウ | true | 副詞可能 | きょう ✓ | 正确 |
| 明日 | 单 token | アシタ | true | 副詞可能 | あした ✓（但 あす/みょうにち 也合法） | B（单选对） |
| 貴方 | 单 token | アナタ | true | 代名詞 | あなた ✓ | 正确 |
| 此処 | 单 token | ココ | true | 一般 | ここ ✓ | 正确 |
| 真夏 | 单 token | マナツ | true | 一般 | まなつ ✓ | 正确 |
| 此方 | 拆 此(unknown)+方(ホウ) | 此=`*`/方=ホウ | 此✗方✓ | 固有名詞 | 破裂 ✗ | A（OOV+破裂） |
| 魏 | 单 token | ギ | true | 固有名詞 | ぎ（错，应 たか） | C（ateji，引擎认识但读法错） |
| 赵 | 单 token | `*` | false | 固有名詞 | 无注音（应 こ） | C（ateji，OOV） |
| 何ん | 归一为何 | ナニ | true | 代名詞 | なに（诡异，变体归一） | A/B 边界 |

**关键认知修正**：`二人→ににん` **不是** Kuromoji「在 ふたり/ににん 两个读法里选错了」——
而是 **IPADIC 根本不收录「二人」为复合词**，拆成 二+人 各取音读，`ふたり` 在 IPADIC 里压根不存在。
这把 B 类的根因从「多读法选错」改为「不收录＋拆解」，但修复手段不变：需要一个**覆盖/多读法数据源**。

---

## 2. 三类错误重新界定

### A. 覆盖 / OOV
例：`此方→こちら`（IPADIC 不收录）、`赵`（OOV）。
修复手段：JMdict 派生词表（覆盖读法）/ Global Override / Sudachi（可能覆盖，但代价大，见 §3.3）。

### B. 已知词合法多读法（**最关键、当前覆盖不足**）
例：`二人`(ふたり/ににん)、`明日`(あした/あす/みょうにち)、`生`(せい/しょう/なま/いき…)。
**Kuromoji 与 Sudachi 都只返一个读法，无法告诉你它有多个合法读法。** 单靠 `isKnown`/POS 抓不到
（二人拆出的二、人都 `isKnown=true`、有合法读法、无 OOV 无 `*`）。
修复手段：**外部多读法数据源（JMdict）检测歧义 → CONFLICT → 无语境则 No Reading**；
高频词由**自撰纠错小词表**解析 preferred reading。

### C. 当て字 / 作者自定义读法
例：`魏→たか`、`赵→こ`。
通用词典无解（魏=ギ 是词典读法，非引擎瞎猜）。
修复手段：**Song Override**（用户，mediaId+lyricsHash+surface）。

---

## 3. 外部数据源调研结论（license-verified）

### 3.1 JMdict（EDRDG）—— **推荐**
- **License**：CC BY-SA 4.0。EDRDG licence **明确允许**随闭源/商业软件分发并出售
  （原文：「there is NO restriction placed on commercial use... can be bundled with software and
  sold... does not have to be under any form of open-source licence」）。所谓「禁止非自由软件再分发」=误传，证伪。
- **多读法**：同一 entry 多 `r_ele`（明日→3 读）＋ **跨 entry 同形异读**（二人→ふたり 与 ににん
  分属不同 entry，homography）。**这正是歧义信号，也是唯一能给多读法的数据源。**
- **派生子集**：`{surface → [readings]}`，仅歧义 surface。规模低千条，歌词切片几百条，压缩 <1 MB。
- **约束**：衍生数据须保持 CC BY-SA 4.0 ＋ EDRDG/Jim Breen 归属（about 页/NOTICE）。app 代码可继续闭源
  （CC BY-SA 4.0 §4(a) Collection 规则）。**不可**从 JMdict 抽取纠错词表→该表继承 CC BY-SA。

### 3.2 KANJIDIC2 —— 补充（弱）
- CC BY-SA 4.0，但 **SKIP 码是 CC BY-NC-SA（非商用，别抽）**；`ja_on`/`ja_kun` 可用。
- 字级读法，只能做歧义 hint，不解决词级。非主源。

### 3.3 Sudachi（`com.worksap.nlp:sudachi:0.8.0`）—— **不解决 B，不入 V1**
- 纯 Java、Apache 2.0、无 `.so`/JNI（这点友好）。
- **词典体积**：small 39.8 MB / core 68.8 MB / full 121 MB（vs IPADIC ~14 MB）。词典不随 Maven，需单独下载。
- **不提供多读法**：一个 word-id 一个 `reading()`，无 N-best，SplitMode A/B/C 是**粒度**不是读法变体。
  → **无法做 CONFLICT 检测，不解决 B 类核心。**
- `二人→ふたり`：从 UniDic 词源**很可能**（未实跑验证，见 §11）。但即便给 ふたり（对），对
  `明日→あす` 的歌词仍会给 あした（错）且无法察觉歧义。
- **结论**：Sudachi ≠ B 类解。词典体积对 V1 不可接受。**deferred**（V1.1+ 决策点，需实跑 benchmark）。

### 3.4 IPADIC / naist-jdic / UniDic / NEologd
- IPADIC = NAIST licence（商用 OK），但单读法，不解决多读法。
- UniDic = GPL/LGPL/BSD 三选，但 500 MB–2 GB，且仍单读法。APK 不可行。
- NEologd = Apache 2.0，加新词，仍单读法。

### 3.5 自撰歌词纠错小词表（LyricsCommonReadingDictionary）—— **可行**
- 日语词读法是事实、不可版权。**自撰** ~50–300 对（surface→preferred reading）→ MIT/CC0，可随闭源 app 分发。
- **约束**：不可从 JMdict/Wiktionary 抽取（继承 CC BY-SA）。用 JMdict 仅**验证**哪些 surface 歧义，
  然后凭自己语言知识手写 preferred reading。
- 候选（待调研后定，不直接全加）：`二人→ふたり`、`一人→ひとり`、`今日→きょう`、`明日→あした`、
  `大人→おとな`、`二十歳→はたち`、`此方→こちら`、`貴方→あなた`…

---

## 4. 重新设计的 Confidence

```
HIGH      单一明确读法（JMdict 单读法 且 引擎无破裂）— 真夏→まなつ、貴方→あなた
CONFLICT  surface 有多个合法读法（JMdict 检测）— 二人、明日。无语境证据 → No Reading
          （可被 LyricsCommonReadingDict / Override 解析为 HIGH）
UNKNOWN   OOV / IPADIC 不收录 — 此(此方)、赵。→ No Reading
LOW       破裂拆分 / 单字固有名詞音读簇 — 此+方。→ No Reading（可被 Override 解析）
```

「如何判断多合法读法」：查 JMdict 派生歧义词表 `{surface → [readings]}`，`|readings|>1` 即 CONFLICT。
**不再把 `isKnown=true` 当 HIGH。**

---

## 5. 新优先级栈（按「每个 span 如何决定 reading」）

```
对每个 span（单 token 或破裂合并的连续汉字簇）：
  1. Song Override 命中？                              → 用（ateji，最高）
  2. LyricsCommonReadingDict 命中？                   → 用（解析 CONFLICT 为 preferred，HIGH）
  3. JMdict 派生词表查 span.surface：
       单一读法                                       → 用（HIGH，含 A 类覆盖如 此方→こちら）
       多读法（CONFLICT）                              → No Reading（无语境不猜）
       未收录                                          → 落到 4
  4. Kuromoji IPADIC：
       单 token、无歧义、非破裂、非单字固有名詞音读簇  → 用（HIGH）
       破裂 / unknown / 单字固有名詞音读簇             → No Reading（LOW/UNKNOWN）
  5. No Reading                                       → 宁可无，不显错
```

栈层关系：Song Override（C）＞ 纠错小表（B 解析）＞ JMdict 歧义层（B 检测+A 覆盖）＞ Kuromoji（base）＞ No Reading。
Sudachi **不入栈**（V1）；作为未来 base engine 候选 deferred。

---

## 6. 用户 10 问回答

1. **为什么 Kuromoji 对 二人 选 ににん？**
   不是「选」——IPADIC 不收录「二人」为复合词，拆成 二(数)+人(接尾) 各取音读 ニ+ニン。
2. **为何 POS/isKnown 判不了正确？**
   二人拆出的二、人都 `isKnown=true`、有合法读法、无 OOV 无 `*`；IPADIC 每词一读法，不暴露
   「二人有多读法」。靠 isKnown/POS 抓不到。
3. **Sudachi 能改善？**
   单词覆盖上可能（二人很可能→ふたり），但 Sudachi 同样一词一读法、无多读法 API，**无法检测
   歧义**，B 类核心不解决。且词典 39.8–121 MB。
4. **比 IPADIC 更适合 furigana 的公开词典？**
   无「多读法」形态素解析词典；Sudachi/UniDic/IPADIC 都是单读法。**唯一能给多读法的是 JMdict**
   （辞书，非形态素解析）。
5. **小型歌词纠错词表可行？** 是，自撰 MIT，~50–300 对。
6. **数据源？** 读法事实自撰（用 JMdict 仅验证歧义范围，不抽取）。不可从 JMdict/Wiktionary 抄。
7. **怎么判「词典合法但语境不确定」？** 查 JMdict 派生 `{surface→[readings]}`，`|readings|>1` 即
   歧义。无额外语境 → No Reading。
8. **何时不显示？** CONFLICT 未解析、UNKNOWN/OOV、破裂簇、单字固有名詞音读（LOW）。原则：宁可无，不显错。
9. **ConfidenceAnalyzer 重设？** 见 §4。增加 JMdict 歧义查询能力，输出含 CONFLICT。不再把
   `isKnown=true` 当 HIGH。
10. **各词路径：**
    - `魏→たか`、`赵→こ`：C，Song Override。
    - `此方→こちら`：A，JMdict 派生词表（若收录）/ Global / 纠错小表。
    - `二人→ふたり`：B，LyricsCommonReadingDict 解析 CONFLICT→ふたり；无纠错则 No Reading。
    - `何ん`：变体归一为何(ナニ)，A/B 边界；按实际期望读法走纠错小表或 Song Override。

---

## 7. 推荐架构图

```
原始歌词
   │
   ▼
[JapaneseDetector] ── 非日语 ──▶ 普通 Text（零变化）
   │ 日语
   ▼
[Kuromoji tokenize] ──▶ List<Token>
   │
   ▼
[FuriganaConfidenceAnalyzer]
   ├─ 破裂合并 / OOV / 单字固有名詞音读簇 → LOW/UNKNOWN span
   ├─ JMdict 歧义词表查 span.surface → 多读法标记 CONFLICT
   └─ 单一读法 → HIGH
   │
   ▼ span 流
[解析（按优先级）]
   1. Song Override (mediaId+lyricsHash+surface)   ← 长按编辑写入
   2. LyricsCommonReadingDict (自撰 MIT)
   3. JMdict 派生词表（CONFLICT→No Reading；单读法→覆盖）
   4. Kuromoji（HIGH）
   5. No Reading
   │
   ▼
[List<RubySegment>] ──▶ RubyText 渲染
```

---

## 8. V 边界

| 版本 | 范围 | 网络 | 解决 |
|---|---|---|---|
| **V1.1（本地多层）** | Kuromoji + JMdict 派生歧义词表（CC BY-SA+归属）＋ 自撰 MIT 纠错小表 ＋ Song Override（长按编辑）＋ Confidence 重设（CONFLICT→No Reading） | 无（完全离线） | A 大部分、B 检测＋高频解析、C（用户） |
| V2（可选联网） | ExternalReadingResolver + Yahoo（opt-in，商用确认后） | opt-in | 边际（A 覆盖补缺；不解决 B/C） |
| V3（权威 furigana 源） | 用户导入带读法歌词；Lyric+Furigana 双来源核验 | 视来源 | ateji 尾巴自动化（C） |
| Sudachi benchmark | deferred | — | 决策点：是否换 base engine（不改策略） |

---

## 9. 风险

- **JMdict 衍生数据须 CC BY-SA 4.0 ＋ EDRDG 归属**（about 页/NOTICE）。app 代码仍闭源。
- **自撰纠错表勿从 JMdict/Wiktionary 抄**（继承 CC BY-SA）。用 JMdict 仅验证歧义范围，手写 preferred。
- **CONFLICT→No Reading 降低 recall**：部分本可读对的歧义词也不显示。这是用户原则（precision 优先）的代价。
- **JMdict 派生词表构建/体积需实测**：从 `jmdict-simplified` common-only 抽 `{surface→[readings]}`，过滤到歌词相关，测最终体积与命中率。
- **佽ん 等变体归一行为需个案处理**（Kuromoji 把 佈 归一为何）。
- **Song Override 缓存失效**：绑 `mediaId+lyricsHash`，换歌词源/改文本自动失效（hash 变）。

---

## 10. Sudachi / Yahoo 为何暂不实现

- **Sudachi**：source-verified 不提供多读法（一词一 `reading()`、无 N-best、SplitMode 是粒度非读法）→
  无法检测 B 类歧义，换了也不解决核心；词典 39.8–121 MB 对 V1 不可接受。留 `FuriganaEngine` 接口，
  作为 V1.1+ 决策点，需实跑 benchmark（二人单点等）再定，但**策略不变**。
- **Yahoo**：商用条款页面 JS 渲染未取到正文（须向 Yahoo 书面确认）；强制 credit 显示；上传歌词违背
  本地原则。V2 opt-in，确认后才能进发行版。

---

## 11. 未验证项与诚实声明

- Sudachi `二人→ふたり`：从 UniDic 词源**很可能**，但**未实跑验证**（agent 未下载 68.8 MB 词典运行）。
  本评估不依赖此单点——Sudachi 不提供多读法是 source-verified 的决定性事实，无论二人单点结果如何，
  策略不变。若用户要此单点，可作 V1.1+ benchmark 第一项实跑。
- JMdict 派生词表的实际命中率/体积：需构建脚本实测，本评估为可行性结论（license＋数据结构可行）。

---

## 12. 是否值得 / 下一步

- **值得**：V1.1 本地多层（JMdict 歧义 ＋ 自撰纠错小表 ＋ Song Override ＋ Confidence 重设）是正解——
  纯离线、license 可行、解决 A/B/C 大部。
- **不值得**：换 Sudachi（不解决 B、体积大）；Yahoo 入 V1（商用/隐私）。
- **下一步（待用户确认）**：
  1. 写 JMdict 派生歧义词表构建脚本（从 `jmdict-simplified` common-only 抽 `{surface→[readings]}`）。
  2. 自撰 MIT 歌词纠错小表（先调研哪些词最高价值、最高频）。
  3. Confidence 重设 + 优先级栈实现。
  4. Song Override + 长按编辑（沿用原 V1 设计的这部分，仍有效）。
  5. 回归测试覆盖 `二人/一人/明日/此方/魏/赵/真夏/生きる/佽ん`。

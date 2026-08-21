# JMdict 派生词表 — 数据来源与许可声明

本目录下的 `jmdict_furigana_derived.json`（及其打包进 APK 的形式）是 PickUpMusic
为「歌词振假名」功能从 JMdict 派生的最小数据集，仅包含 furigana 渲染所需信息：

```
{ "surface": ["reading1", "reading2", ...] }
```

不包含任何英文 gloss、词性、例句等 JMdict 完整元数据。

## 上游来源

- **源数据**：jmdict-simplified（https://github.com/scriptin/jmdict-simplified）
  的 `jmdict-eng-common-*.json`（common-only 英文版子集）
- **原始词典**：JMdict / JMnedict / KANJIDIC2，© Electronic Dictionary Research
  and Development Group (EDRDG) / Jim Breen
- **上游主页**：https://www.edrdg.org/jmdict/jmdict.html

## 许可证（CC BY-SA 4.0）

本派生数据继承上游 JMdict 的 **Creative Commons Attribution-ShareAlike 4.0
International (CC BY-SA 4.0)** 许可。

EDRDG 通用词典许可声明（https://www.edrdg.org/edrdg/licence.html ）明确：

> "there is NO restriction placed on commercial use of the files. The files can
> be bundled with software and sold for whatever the developer wants to charge.
> Software using these files does not have to be under any form of open-source
> licence."

因此本派生数据**可随 PickUpMusic（闭源应用）打包分发**。依据 CC BY-SA 4.0：

1. **归属 (Attribution)**：须保留对 EDRDG / Jim Breen 的归属声明（本文件即履行此义务；
   应用内「关于」页亦须显示 JMdict/EDRDG 归属与许可链接）。
2. **相同方式共享 (ShareAlike)**：本派生**数据本身**须以 CC BY-SA 4.0 分发，接收方有权
   再使用该数据。此约束**仅及于该派生数据**，不波及 PickUpMusic 的应用代码
   （CC BY-SA 4.0 §4(a)「集合作品」规则：集合中的派生作品须保持原许可，但集合整体无须）。
3. **不得主张版权**：不得对源自 JMdict 的材料主张版权。

完整法律文本：https://creativecommons.org/licenses/by-sa/4.0/legalcode

## 构建方式

派生词表由 `build_jmdict_derived.py` 离线构建，可随时从上游重新生成（见脚本头部文档），
不依赖任何在线服务。构建过程不修改上游数据语义，仅做抽取与聚合（surface → readings 去重）。

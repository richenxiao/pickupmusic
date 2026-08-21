#!/usr/bin/env python3
"""
PickUpMusic Furigana — JMdict 派生词表构建脚本（V1.1 Phase 1）

从 jmdict-simplified 的 common-only 英文版抽取 Furigana 所需的最小信息：
    { surface: [reading1, reading2, ...] }

仅保留「含汉字的 kanji surface」及其读法（kana，平假名/片假名原样）。
同一 surface 若对应多个不同 reading → 该 surface 为 CONFLICT（多读法），下游
Confidence 据此判 CONFLICT。单一 reading → deterministic mapping。

数据来源与许可证：
  - 源数据：jmdict-simplified（https://github.com/scriptin/jmdict-simplified）
    jmdict-eng-common-*.json（common-only 子集，英文 gloss 已剔除不用）
  - 上游：JMdict © EDRDG / Jim Breen，CC BY-SA 4.0
  - 派生数据继承 CC BY-SA 4.0：可在闭源/商业 App 中随包分发，须保留 EDRDG
    归属与许可声明（见产物旁的 LICENSE/NOTICE）。App 代码本身无须开源。
    https://www.edrdg.org/edrdg/licence.html

用法：
    python build_jmdict_derived.py <jmdict-eng-common-*.json> <out.json>

产物体积/条目数见 stdout 末尾统计。
"""
import json
import sys
import os
import time


def is_kanji(ch: str) -> bool:
    c = ord(ch)
    return (0x4E00 <= c <= 0x9FFF) or (0x3400 <= c <= 0x4DBF) or (0xF900 <= c <= 0xFAFF)


def surface_has_kanji(s: str) -> bool:
    return any(is_kanji(c) for c in s)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    src, out = sys.argv[1], sys.argv[2]
    t0 = time.time()

    with open(src, encoding="utf-8") as f:
        data = json.load(f)
    words = data["words"]

    # surface -> set of readings（保持插入顺序去重）
    mapping: dict[str, dict[str, None]] = {}
    n_entries = 0
    for w in words:
        kanji_list = w.get("kanji") or []
        kana_list = w.get("kana") or []
        if not kanji_list or not kana_list:
            continue  # 纯假名词汇（无汉字 surface）不需要 furigana 映射
        n_entries += 1
        # 读法按 appliesToKanji 归到具体 surface；appliesToKanji=["*"] 表示适用于全部
        for k_ele in kanji_list:
            surface = k_ele.get("text", "")
            if not surface or not surface_has_kanji(surface):
                continue
            applicable = []
            for r_ele in kana_list:
                applies = r_ele.get("appliesToKanji") or ["*"]
                if "*" in applies or surface in applies:
                    reading = r_ele.get("text", "")
                    if reading:
                        applicable.append(reading)
            if not applicable:
                continue
            bucket = mapping.setdefault(surface, {})
            for r in applicable:
                bucket[r] = None  # 去重保序

    # 序列化：surface -> [readings...]（仅当 |readings|>=1）
    result = {s: list(rs) for s, rs in mapping.items() if rs}

    # 统计
    total = len(result)
    conflict = sum(1 for rs in result.values() if len(rs) > 1)
    deterministic = total - conflict

    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))

    raw_size = os.path.getsize(out)
    # gzip 模拟 APK 内压缩（Android 资源 aapt 对未压缩资产会原样存，但若放 assets 可被
    # 运行时 GZIP 解压；这里给出 gzip 后体积作为「打包进 APK 的真实占用」参考）
    import gzip
    gz_path = out + ".gz"
    with gzip.open(gz_path, "wb") as gf:
        gf.write(open(out, "rb").read())
    gz_size = os.path.getsize(gz_path)

    dt = time.time() - t0
    print(f"源条目数(common words with kanji): {n_entries}")
    print(f"派生 surface 数: {total}")
    print(f"  deterministic(单一 reading): {deterministic}")
    print(f"  CONFLICT(多 reading): {conflict}")
    print(f"产物未压缩: {raw_size:,} bytes ({raw_size/1024:.1f} KB)")
    print(f"产物 gzip:   {gz_size:,} bytes ({gz_size/1024:.1f} KB)")
    print(f"构建耗时: {dt:.2f}s")
    print(f"产物: {out}")

    # 抽样验证关键 case
    print("--- 抽样验证 ---")
    for probe in ["二人", "一人", "明日", "今日", "大人", "此方", "此処", "貴方", "真夏", "生"]:
        if probe in result:
            print(f"  {probe} -> {result[probe]}")
        else:
            print(f"  {probe} -> (未收录)")


if __name__ == "__main__":
    main()

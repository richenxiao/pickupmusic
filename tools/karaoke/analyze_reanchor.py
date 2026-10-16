#!/usr/bin/env python3
"""闸门 1 补证：**整条抽样**汇总。

口径（用户 2026-10-16 定的）：按探针日志里的 `say ===X===` 分段，
每段统计 —— 每个条目**至少 20 个块**、帧号误差 **≤1 采样**；并汇报
重锚 / 锚缺 / 条目切换 / 交叉校验 / 不一致 五个计数。

探针行只解析"块级"的（带 `计数器=` 的），sink 诊断行单独统计。

用法:
    py -3.11 tools/karaoke/analyze_reanchor.py .karaoke/probe_scen.log
"""
import re
import sys

RE_CNT = re.compile(r"计数器=(\d+)")
RE_ANCH = re.compile(r"锚点pts帧=(-?\d+|无)")
RE_DIFF = re.compile(r"戳差\(P−T\)=(-?\d+)")
RE_TRUTH = re.compile(r"真值首帧=(-?\d+)")
RE_REALIGN = re.compile(r"重锚=(\d+)")
RE_MISS = re.compile(r"锚缺=(\d+)")
RE_ITEMCH = re.compile(r"条目切换=(\d+)")
RE_XCHK = re.compile(r"交叉校验=(\d+)")
RE_XERR = re.compile(r"不一致=(\d+)")
RE_SINK_CFG = re.compile(r"sink#\d+ configure (\d+)Hz")
RE_SINK_OFS = re.compile(r"sink#\d+ setOutputStreamOffsetUs=(\d+)µs")
RE_SINK_BUF = re.compile(r"sink#\d+ handleBuffer pts=(-?\d+)µs")


def num(rx, s, default=None):
    m = rx.search(s)
    return int(m.group(1)) if m else default


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else ".karaoke/probe_scen.log"
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()

    section = "(前导)"
    stats = {}
    fmt_seen = {}
    sink_conf = []
    sink_ofs = []
    blocks = 0

    for ln in lines:
        m = re.search(r"\[标记\] ===(\w+)===", ln)
        if m:
            section = m.group(1)
            stats.setdefault(section, dict(blocks=0, diffs=[], realign=0, miss=0,
                                           itemch=0, xchk=0, xerr=0, anchor_none=0,
                                           rates=set(), maxabs=0))
            continue
        st = stats.setdefault(section, dict(blocks=0, diffs=[], realign=0, miss=0,
                                            itemch=0, xchk=0, xerr=0, anchor_none=0,
                                            rates=set(), maxabs=0))
        mc = RE_SINK_CFG.search(ln)
        if mc:
            sink_conf.append((section, int(mc.group(1))))
        mo = RE_SINK_OFS.search(ln)
        if mo:
            sink_ofs.append((section, int(mo.group(1))))
        if "计数器=" not in ln:
            continue
        st["blocks"] += 1
        blocks += 1
        d = num(RE_DIFF, ln)
        if d is not None:
            st["diffs"].append(d)
        if RE_ANCH.search(ln) and RE_ANCH.search(ln).group(1) == "无":
            st["anchor_none"] += 1
        for key, rx in (("realign", RE_REALIGN), ("miss", RE_MISS), ("itemch", RE_ITEMCH),
                        ("xchk", RE_XCHK), ("xerr", RE_XERR)):
            v = num(rx, ln)
            if v is not None:
                st[key] = max(st[key], v)

    print("日志：%s  总块级探针行=%d\n" % (path, blocks))
    hdr = ("%-18s %6s %8s %8s %7s %6s %6s %6s %6s %6s" %
           ("段", "块数", "戳差非0", "|戳差|max", "重锚", "锚缺", "无锚点", "条目切换", "交叉校验", "不一致"))
    print(hdr)
    print("-" * len(hdr))
    total_err = 0
    for sec, st in stats.items():
        if st["blocks"] == 0:
            continue
        nz = [d for d in st["diffs"] if d != 0]
        mx = max((abs(d) for d in st["diffs"]), default=0)
        print("%-18s %6d %8d %8d %7d %6d %6d %6d %6d %6d" % (
            sec, st["blocks"], len(nz), mx, st["realign"], st["miss"],
            st["anchor_none"], st["itemch"], st["xchk"], st["xerr"]))
        total_err += st["xerr"]
    print("\n交叉校验不一致合计 = %d" % total_err)
    print("sink 层 configure 次数 = %d，setOutputStreamOffsetUs 次数 = %d" % (len(sink_conf), len(sink_ofs)))
    print("configure 的采样率序列 = %s" % [r for _, r in sink_conf])
    print("偏移序列(µs) = %s" % [o for _, o in sink_ofs])
    print("\n判据：每段每条目 ≥20 块、|戳差| ≤1（WAV/FLAC）；MP3 受编码器前置延迟影响，单列。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

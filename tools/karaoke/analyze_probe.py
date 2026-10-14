#!/usr/bin/env python3
"""步骤 2b/2c 结果分析：解析 `karaoke_probe.log`，按「扫描计划」分组给统计。

两个口径（用户点名要分开）：
  ① **戳差(P−T)** = 「时间戳换算的帧号 − 真值帧号」
     → **步骤 2c 的验收指标**：绝对值必须 ≤ 1 个采样。
  ② **差(真值首帧−请求帧)** = 「flush 后第一个采样的真实帧号 − app 请求的目标帧号」
     → 步骤 2b 的指标：反映"请求位置与真实落点差多少"（会被对齐到解码缓冲边界）。

顺带汇报：重锚次数、锚缺次数、队列丢弃数、flush 次数。

用法: py -3.11 tools/karaoke/analyze_probe.py .karaoke/probe.log
"""
import re
import statistics
import sys

RE_PLAN = re.compile(r"扫描计划 file=(?P<file>\S+) count=(?P<n>\d+) fileDur=(?P<dur>\d+)ms 位置ms=(?P<pos>[\d,]*)")
RE_FLUSH = re.compile(r"flush #(?P<no>\d+) 计数器=(?P<ctr>\d+) 重锚=(?P<re>\d+) 锚缺=(?P<miss>\d+)")
RE_BLOCK = re.compile(r"\[探针\] (?P<where>.+?)：(?P<body>.*)$")

RE_CTR = re.compile(r"计数器=(-?\d+)")
RE_PTS = re.compile(r"锚点pts帧=(无|-?\d+)")
RE_PRED = re.compile(r"自数预测=(-?\d+)")
RE_BIAS = re.compile(r"锚偏差=(-?\d+)")
RE_REALIGN = re.compile(r"重锚=(\d+)")
RE_MISS = re.compile(r"锚缺=(\d+)")
RE_TRUTH = re.compile(r"真值首帧=(-?\d+)")
RE_PTSDIFF = re.compile(r"戳差\(P.T\)=(-?\d+)")
RE_CLICK = re.compile(r"首咔哒偏移=(无|-?\d+)")
RE_PHASE = re.compile(r"相位=(\d+)")
RE_REQ = re.compile(r"请求帧=(-?\d+)")
RE_REQDIFF = re.compile(r"差\(真值首帧.请求帧\)=(-?\d+)")


def num(rx, s, default=None, group=1):
    m = rx.search(s)
    if not m:
        return default
    try:
        return int(m.group(group))
    except ValueError:
        return default


def stat(vals):
    if not vals:
        return "（无）"
    a = [abs(v) for v in vals]
    return "n=%d min=%d max=%d 均值=%.2f |绝对值| 最大=%d 达标=%s" % (
        len(vals), min(vals), max(vals), statistics.fmean(vals), max(a),
        "✅" if max(a) <= 1 else "❌",
    )


def plain(vals):
    if not vals:
        return "（无）"
    return "n=%d min=%d max=%d 均值=%.2f 取值=%s" % (
        len(vals), min(vals), max(vals), statistics.fmean(vals), sorted(set(vals))[:10],
    )


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else ".karaoke/probe.log"
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()

    plans = []            # [(file, count, dur, [positions])]
    cur = -1
    n_flush = 0
    flush_by_plan = {}
    # 每个 plan 下的「flush 后第 1 块」记录
    rec = {}              # (plan, 请求#) -> dict
    steady = []
    max_realign = 0
    max_miss = 0
    max_drop = 0

    for ln in lines:
        m = RE_PLAN.search(ln)
        if m:
            plans.append((m.group("file"), int(m.group("n")), int(m.group("dur")),
                          [int(x) for x in m.group("pos").split(",") if x]))
            cur = len(plans) - 1
            flush_by_plan.setdefault(cur, 0)
            continue
        m = RE_FLUSH.search(ln)
        if m:
            n_flush += 1
            if cur >= 0:
                flush_by_plan[cur] = flush_by_plan.get(cur, 0) + 1
            max_realign = max(max_realign, int(m.group("re")))
            max_miss = max(max_miss, int(m.group("miss")))
            continue
        m = RE_BLOCK.search(ln)
        if not m:
            continue
        body = m.group("body")
        where = m.group("where")
        r = num(RE_REALIGN, body)
        if r is not None:
            max_realign = max(max_realign, r)
        mi = num(RE_MISS, body)
        if mi is not None:
            max_miss = max(max_miss, mi)
        dd = num(re.compile(r"队列丢弃=(\d+)"), body)
        if dd is not None:
            max_drop = max(max_drop, dd)
        row = {
            "where": where,
            "ctr": num(RE_CTR, body),
            "pts": None if (m2 := RE_PTS.search(body)) and m2.group(1) == "无" else num(RE_PTS, body),
            "pred": num(RE_PRED, body),
            "bias": num(RE_BIAS, body),
            "truth": num(RE_TRUTH, body),
            "ptsdiff": num(RE_PTSDIFF, body),
            "click": None if (m3 := RE_CLICK.search(body)) and m3.group(1) == "无" else num(RE_CLICK, body),
            "phase": num(RE_PHASE, body),
            "req": num(RE_REQ, body),
            "reqdiff": num(RE_REQDIFF, body),
        }
        if where.startswith("flush 后第 1 块") and cur >= 0:
            key = (cur, row["req"] if row["req"] is not None else -1)
            rec.setdefault(key, row)
        if where == "稳态":
            steady.append(row)

    print("文件: %s" % path)
    print("扫描计划 %d 次；flush 行 %d 条；配对到的「flush 后第 1 块」 %d 条；稳态行 %d 条"
          % (len(plans), n_flush, len(rec), len(steady)))
    print("全日志最大：重锚=%d 锚缺=%d 队列丢弃=%d" % (max_realign, max_miss, max_drop))

    for si, (name, count, dur, positions) in enumerate(plans):
        rows = [v for (k, v) in rec.items() if k[0] == si]
        rows.sort(key=lambda r: (r["req"] if r["req"] is not None else 0))
        print("\n=== 计划 #%d  file=%s  fileDur=%dms  请求 %d 次  配对到 %d 次  flush %d 条 ==="
              % (si, name, dur, len(positions), len(rows), flush_by_plan.get(si, 0)))
        if not rows:
            print("  （没有配对的探测行）")
            continue
        print("  ① 戳差(P−T) 验收        : %s" % stat([r["ptsdiff"] for r in rows if r["ptsdiff"] is not None]))
        print("  ② 真值首帧−请求帧        : %s" % plain([r["reqdiff"] for r in rows if r["reqdiff"] is not None]))
        print("     锚偏差(pts−自数预测)   : %s" % plain([r["bias"] for r in rows if r["bias"] is not None]))
        clicks = [r for r in rows if r["click"] is not None]
        if clicks:
            print("     咔哒：命中 %d/%d 次；相位取值 %s"
                  % (len(clicks), len(rows), sorted({r["phase"] for r in clicks})[:10]))
        print("  明细（请求帧 → pts帧 / 真值 / 戳差）:")
        for r in rows[:10]:
            print("     req=%-9s pts=%-9s truth=%-9s 戳差=%-6s 锚偏差=%-6s"
                  % (r["req"], r["pts"], r["truth"], r["ptsdiff"], r["bias"]))

    if steady:
        print("\n=== 稳态（非 flush 后）===")
        print("  ① 戳差(P−T) : %s" % stat([r["ptsdiff"] for r in steady if r["ptsdiff"] is not None]))
        print("     锚偏差     : %s" % plain([r["bias"] for r in steady if r["bias"] is not None]))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""步骤 2c 验收分析：解析全自动扫描日志，按文件给统计。

⚠️ 关键：**跳转后的第 1 块不能用来判真值** —— 实测那一块里是**残留字节**
（原始采样既不是文件开头、解出的"真值帧号"也是乱的），锚点也往往还没建立。
所以按「请求#」把每次 seek 之后的若干块分组，**取其中第一块『有锚点』的**来判：
那才是"跳转落稳之后"的第一个可信采样。

口径
----
① **戳差(P−T)** = 「时间戳换算的帧号 − 真值帧号」（用户验收指标，|·| ≤ 1 采样）。
   实测时间戳带一个**恒定纪元**（`presentationTimeUs` 固定多 10^12 微秒，与内容无关、不漂移），
   帧域 = 1e6 × 采样率。故同时给**扣掉纪元后的残差** = 真正的对齐精度。
② **差(真值首帧−请求帧)** = 落点相对请求位置偏了多少（与时间戳无关）。
③ 重锚 / 锚缺：每个 buffer 都用时间戳校验自数帧，偏差 > 1 采样才记一次重锚。

用法: py -3.11 tools/karaoke/analyze_sweep.py .karaoke/sweep.log
"""
import re
import statistics
import sys

RE_LOAD = re.compile(r"#### 载入 (\S+) 探针=(.+?) ####")
RE_PLAN = re.compile(r"扫描计划 file=(\S+) count=(\d+) fileDur=(\d+)ms")
RE_CFG = re.compile(r"输入格式 (\d+)Hz / (\d+)ch")
RE_BLOCK = re.compile(r"\[探针\] (?P<where>flush 后第 (?P<idx>\d+) 块)：(?P<body>.*)$")
RE_FLUSH = re.compile(r"flush #(\d+) 计数器=(\d+) 重锚=(\d+) 锚缺=(\d+)")

RE_PTS = re.compile(r"锚点ptsμs=(-?\d+)")
RE_HAS_PTS = re.compile(r"锚点pts帧=(?!无)")
RE_CTR = re.compile(r"计数器=(-?\d+)")
RE_TRUTH = re.compile(r"真值首帧=(-?\d+)")
RE_PTSDIFF = re.compile(r"戳差\(P.T\)=(-?\d+)")
RE_REQ = re.compile(r"请求#(-?\d+)")
RE_REQFRAME = re.compile(r"请求帧=(-?\d+)")
RE_REQDIFF = re.compile(r"差\(真值首帧.请求帧\)=(-?\d+)")
RE_PHASE = re.compile(r"相位=(\d+)")
RE_CLICKOFF = re.compile(r"首咔哒偏移=(无|-?\d+)")
RE_REALIGN = re.compile(r"重锚=(\d+)")
RE_MISS = re.compile(r"锚缺=(\d+)")


def num(rx, s, g=1):
    m = rx.search(s)
    if not m:
        return None
    try:
        return int(m.group(g))
    except ValueError:
        return None


def stat(vals, label):
    if not vals:
        return "  %-28s （无）" % label
    a = [abs(v) for v in vals]
    return "  %-28s n=%-4d min=%-8d max=%-8d 均值=%-10.1f |绝对|最大=%-8d %s" % (
        label, len(vals), min(vals), max(vals), statistics.fmean(vals), max(a),
        "✅达标" if max(a) <= 1 else "❌超标")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else ".karaoke/sweep.log"
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()

    cur, files = None, []
    for ln in lines:
        m = RE_LOAD.search(ln)
        if m:
            cur = {"name": m.group(1), "probe": m.group(2), "rate": 0, "plan": None,
                   "groups": {}, "flushes": 0, "realign": 0, "miss": 0}
            files.append(cur); continue
        if cur is None:
            continue
        m = RE_PLAN.search(ln)
        if m:
            cur["plan"] = (int(m.group(2)), int(m.group(3))); continue
        m = RE_CFG.search(ln)
        if m and not cur["rate"]:
            cur["rate"] = int(m.group(1)); continue
        m = RE_FLUSH.search(ln)
        if m:
            cur["flushes"] += 1
            cur["realign"] = max(cur["realign"], int(m.group(3)))
            cur["miss"] = max(cur["miss"], int(m.group(4)))
            continue
        m = RE_BLOCK.search(ln)
        if not m:
            continue
        body = m.group("body")
        cur["realign"] = max(cur["realign"], num(RE_REALIGN, body) or 0)
        cur["miss"] = max(cur["miss"], num(RE_MISS, body) or 0)
        req = num(RE_REQ, body)
        if req is None or req < 0:
            continue
        cur["groups"].setdefault(req, []).append((int(m.group("idx")), body))

    print("文件: %s（共 %d 个）\n" % (path, len(files)))
    tot = []

    for d in files:
        rate = d["rate"]
        epoch = 1_000_000 * rate if rate else None
        picked, noanchor = [], 0
        for req, blocks in d["groups"].items():
            blocks.sort()
            hit = None
            for idx, body in blocks:
                if RE_HAS_PTS.search(body):
                    hit = (idx, body); break
            if hit is None:
                noanchor += 1
            else:
                picked.append((req, hit[0], hit[1]))

        print("=" * 104)
        print("%s  探针=%s  %dHz  计划=%s" % (
            d["name"], d["probe"], rate,
            ("%d 次/%dms" % d["plan"]) if d["plan"] else "（无）"))
        print("  seek 次数(有请求#) = %d；其中**始终没拿到锚点**的 = %d；用到锚点的 = %d；flush 行 = %d；重锚 = %d；锚缺 = %d"
              % (len(d["groups"]), noanchor, len(picked), d["flushes"], d["realign"], d["miss"]))
        if not picked:
            continue

        ptsdiff = [num(RE_PTSDIFF, b) for _, _, b in picked]
        ptsdiff = [v for v in ptsdiff if v is not None]
        reqdiff = [num(RE_REQDIFF, b) for _, _, b in picked]
        reqdiff = [v for v in reqdiff if v is not None]

        if ptsdiff:
            resid = [p - epoch for p in ptsdiff]
            tot += [(rate, r) for r in resid]
            print(stat(ptsdiff, "① 戳差(P−T) 原始"))
            print(stat(resid, "   扣掉纪元(%d帧)后" % epoch))
        print(stat(reqdiff, "② 真值首帧−请求帧"))

        if d["probe"].startswith("咔哒"):
            ph = [num(RE_PHASE, b) for _, _, b in picked]
            ph = [v for v in ph if v is not None]
            ok = sum(1 for _, _, b in picked
                     if (mm := RE_CLICKOFF.search(b)) and mm.group(1) != "无")
            print("   咔哒：命中 %d/%d；相位取值 %s" % (ok, len(picked), sorted(set(ph))[:8]))

    if tot:
        print("=" * 104)
        print("【全部无损文件合计】扣掉纪元后的对齐精度（按各自采样率换算座位）:")
        for rate in sorted({r for r, _ in tot}):
            print(stat([v for r, v in tot if r == rate], "%dHz" % rate))
    return 0


if __name__ == "__main__":
    sys.exit(main())

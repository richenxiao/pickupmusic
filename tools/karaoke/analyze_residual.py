#!/usr/bin/env python3
"""闸门 2（0.4s 残差）取证：**残差的逐窗剖面**。

背景
----
"独立缓存路径零拍"测试用 `seed` 把**原曲本身**当伴奏写进缓存，于是
    人声档输出 = 原曲 − 缓存(原曲)
**必须恒为 0**。真机结果是：稳态**逐位为 0**，但开头约 0.4 秒有残差（量级 ≤ 数百）。
本脚本把那段残差的**时间剖面**打出来，用来判定它到底是哪一件事：

  · 与 300ms 淡化窗**完全重合** ⇒ 残差就是**系数插值本身**（300ms 交叉淡化的定义，不是 bug）；
  · 只在**第 1 个块**上有 ⇒ 是「首块拿不到锚点 ⇒ 欠载退回原曲」（已在闸门 1 修掉）；
  · 更宽/更窄、或与两者都不重合 ⇒ 另有原因，继续查。

配套实验（同一台机器、同一首歌，只改一个变量）：
    ramp 300   → 采一次
    ramp 0     → 采一次        ← **瞬时淡化开关**
两次剖面对比，就能把"淡化"这一项单独摘出来。

用法
----
    py -3.11 tools/karaoke/analyze_residual.py CAP.pcm [CAP_B.pcm]
侧车 `.txt`（由处理器写出）提供 sampleRate。
"""
import os
import struct
import sys

WIN_MS = 25.0          # 剖面窗口；300ms 淡化 = 12 个窗，够看出形状
SHOW_SECONDS = 1.5     # 只打开头这一段（稳态恒 0，打了没信息）


def read_sidecar(pcm):
    txt = os.path.splitext(pcm)[0] + ".txt"
    meta = {}
    if os.path.exists(txt):
        for ln in open(txt, encoding="utf-8", errors="replace"):
            if "=" in ln:
                k, v = ln.strip().split("=", 1)
                meta[k.strip()] = v.strip()
    return meta


def read_frames(path, limit_frames=None):
    """→ [(l, r), ...]，16bit 交错立体声（小端）。"""
    with open(path, "rb") as f:
        raw = f.read()
    n = len(raw) // 4
    if limit_frames:
        n = min(n, limit_frames)
    out = [struct.unpack_from("<hh", raw, i * 4) for i in range(n)]
    return out


def profile(frames, rate):
    """→ [(起始秒, max|L|, max|R|, RMS)]，按 WIN_MS 切窗。"""
    win = max(1, int(rate * WIN_MS / 1000.0))
    rows = []
    for s in range(0, len(frames), win):
        chunk = frames[s:s + win]
        if not chunk:
            break
        ml = max(abs(l) for l, _ in chunk)
        mr = max(abs(r) for _, r in chunk)
        rms = (sum(l * l + r * r for l, r in chunk) / (2.0 * len(chunk))) ** 0.5
        rows.append((s / rate, ml, mr, rms))
    return rows


def print_profile(name, rows, seconds):
    keep = [r for r in rows if r[0] < seconds]
    print("\n%s —— 前 %.1fs 逐 %.0fms 窗" % (name, seconds, WIN_MS))
    print("  窗口起点s   max|L|  max|R|    RMS")
    for t, ml, mr, rms in keep:
        bar = "#" * min(40, int(ml / max(1, (max(r[1] for r in rows) or 1)) * 40))
        print("  %8.3f  %7d %7d  %6.1f  %s" % (t, ml, mr, rms, bar))
    whole = max((r[1] for r in rows), default=0)
    first_block = max((r[1] for r in keep), default=0)
    print("  全片 max|L| = %d ；前 %.1fs 内 max|L| = %d" % (whole, seconds, first_block))
    # 残差何时回到"恒为 0"
    last_nonzero = -1.0
    for t, ml, mr, _ in rows:
        if ml or mr:
            last_nonzero = t
    print("  最后一次非零窗起点 = %.3fs ⇒ 残差总跨度 ≈ %.3fs" % (last_nonzero, last_nonzero + WIN_MS / 1000.0))


def analyze_ratio(frames, ref, rate):
    """逐采样比 `out / 原曲` —— 这是用户点名要的口径。

    seed 零拍用的是"把原曲本身当伴奏"，于是
        · 伴奏档：`out = 0·原曲 + 1·伴奏 = 原曲`      ⇒ 比值 = 1
        · 人声档：`out = 1·原曲 + (−1)·伴奏 = 0`      ⇒ 比值 = 0
        · **切档的 300ms 内**：`out = (go+ga)·原曲`，`go+ga` 线性 1→0 ⇒ 比值是**一条直线**。
    所以：比值曲线是不是**直的**、跨度是不是**恰好 rampMs**，一眼就能判。
    """
    n = min(len(frames), len(ref))
    ratio = [None] * n
    for i in range(n):
        d = ref[i][0] + ref[i][1]
        if abs(d) > 1000:                       # 太小就没有分辨力，跳过
            ratio[i] = (frames[i][0] + frames[i][1]) / d
    start = 0
    for i, v in enumerate(ratio):
        if v is not None and abs(v) > 0.99:
            start = i
    win = max(1, int(rate * 0.010))             # 10ms 汇总
    print("\n逐采样比 out/原曲（从「最后一次≈1」起，逐 10ms）")
    print("   起点s   比值均值   最小   最大   跨度s")
    f0 = max(0, start - win)
    t_last = None
    for s in range(f0, min(n, start + int(rate * 0.45)), win):
        vals = [v for v in ratio[s:s + win] if v is not None]
        if not vals:
            continue
        t = (s - start) / rate
        print("  %7.3f  %8.4f %6.3f %6.3f" % (t, sum(vals) / len(vals), min(vals), max(vals)))
        if abs(sum(vals) / len(vals)) > 0.02:
            t_last = t
    if t_last is not None:
        print("  比值落到 ~0 的跨度 ≈ %.3fs（rampMs/1000 应与此一致）" % (t_last + 0.010))
    return ratio


def main():
    argv = [a for a in sys.argv[1:] if not a.startswith("--")]
    ref_path = None
    for i, a in enumerate(sys.argv[1:]):
        if a == "--ref":
            ref_path = sys.argv[i + 2]
    if not argv:
        print(__doc__)
        return 2
    caps = argv
    profiles = []
    for c in caps:
        meta = read_sidecar(c)
        rate = int(meta.get("sampleRate", 0) or 44100)
        limit = int(rate * (SHOW_SECONDS + 0.5))
        frames = read_frames(c, limit_frames=limit)
        print("%s：sidecar=%s 读到 %d 帧（限 %d）" % (os.path.basename(c), meta, len(frames), limit))
        rows = profile(frames, rate)
        print_profile(os.path.basename(c), rows, SHOW_SECONDS)
        profiles.append(rows)
        if ref_path:
            ref = read_frames(ref_path, limit_frames=len(frames))
            if len(ref) < len(frames):
                print("  ⚠️ 参考只有 %d 帧，短于采集 %d 帧 —— 只比前 %d 帧" % (len(ref), len(frames), len(ref)))
            analyze_ratio(frames, ref, rate)

    if len(profiles) == 2:
        spans = []
        for rows in profiles:
            last = -1.0
            for t, ml, mr, _ in rows:
                if ml or mr:
                    last = t
            spans.append(last + WIN_MS / 1000.0)
        print("\n── 两次对比（残差 = 非零窗跨度）──")
        print("  第 1 次（ramp=300）残差跨度 ≈ %.3fs" % spans[0])
        print("  第 2 次（ramp=0）  残差跨度 ≈ %.3fs" % spans[1])
        print("  差 = %.3fs" % (spans[0] - spans[1]))
        if spans[1] < 0.05 and (spans[0] - spans[1]) > 0.15:
            print("  ⇒ **残差就是 300ms 系数插值本身**：关掉淡化后残差立刻消失（两次的稳态都逐位为 0）。")
        elif abs(spans[0] - spans[1]) < 0.05:
            print("  ⇒ 两次一样 ⇒ **与淡化无关**，残差另有来源，继续查。")
        else:
            print("  ⇒ 有差异但不是 0.3s 量级 —— 需人工看剖面。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

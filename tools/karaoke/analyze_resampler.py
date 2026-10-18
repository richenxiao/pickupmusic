#!/usr/bin/env python3
"""追加 A（用户 2026-10-18）：**重采样器质量验收** —— 频响，并与参考实现对比。

输入是 Kotlin 侧 `ResamplerImpulseDumpTest` 导出的**冲激响应**：
  · `.karaoke/ir-<in>_<out>.txt`    = 当前实现
  · `.karaoke/ir16-<in>_<out>.txt`  = 旧的 16 抽头版本（留着做"改前/改后"对照）
线性时不变系统完全由冲激响应决定 ⇒ `|H(f)| = |FFT(h)|`。

⚠️ **两个口径都报**（用户明确要求"不得只报好看的"）：
  · 18kHz —— 用户定的判据口径（18kHz 以内偏差 ≤0.1dB）
  · 20kHz —— 更严的口径（本变换的 −3dB 点就在 20.6~21.2kHz，20kHz 已踩在过渡带上）

用法:
    py -3.11 tools/karaoke/analyze_resampler.py
"""
import os
import sys

import numpy as np
from scipy import signal

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
K = os.path.join(ROOT, ".karaoke")

PB_LO = 20.0            # 通带下限
PB_18 = 18_000.0        # 判据口径上限
PB_20 = 20_000.0        # 更严口径上限
CRITERION_DB = 0.1


def pb_hi(out_rate):
    """降采样时输出带宽更窄（Nyquist 的 95%），上采样用 20kHz 上限。"""
    return min(PB_20, out_rate / 2.0 * 0.95)


def load_ir(path):
    vals, meta = [], {}
    for ln in open(path, encoding="utf-8"):
        if ln.startswith("#"):
            for kv in ln[1:].split():
                if "=" in kv:
                    k, v = kv.split("=", 1)
                    meta[k] = v
            continue
        s = ln.strip()
        if s:
            vals.append(float(s))
    return np.array(vals), meta


def response_db(h, fs):
    n = 1 << int(np.ceil(np.log2(max(len(h) * 64, 65536))))
    H = np.fft.rfft(h, n)
    f = np.fft.rfftfreq(n, 1.0 / fs)
    mag = np.abs(H)
    # 参考取共享通带(20Hz–18kHz)里的最大幅度
    f18 = min(PB_18, pb_hi(fs))
    ref = mag[(f >= PB_LO) & (f <= f18)].max()
    db = 20 * np.log10(np.maximum(mag / max(ref, 1e-30), 1e-12))
    return f, db


def band_stats(f, db, in_rate, out_rate):
    top20 = pb_hi(out_rate)
    top18 = min(PB_18, top20)

    def ripple(top):
        m = (f >= PB_LO) & (f <= top)
        return float(np.abs(db[m]).max()) if m.any() else float("nan")

    below = np.where((f > top18) & (db <= -3.0))[0]
    f3 = float(f[below[0] - 1]) if len(below) else float("nan")
    stop_from = min(in_rate, out_rate) / 2.0
    st = f >= stop_from
    img = (f >= in_rate - top18) & (f <= in_rate)
    return dict(
        r18=ripple(top18), r20=ripple(top20), f3=f3,
        stop=float(db[st].max()) if st.any() else float("nan"),
        image=float(db[img].max()) if img.any() else float("nan"),
    )


def reference_ir(in_rate, out_rate):
    g = np.gcd(int(in_rate), int(out_rate))
    up, down = int(out_rate) // g, int(in_rate) // g
    n = 4096
    x = np.zeros(n)
    x[n // 2] = 1.0
    return signal.resample_poly(x, up, down), up, down


def main():
    pairs = [(44100, 48000), (44100, 96000), (44100, 32000)]
    print("=" * 104)
    print("追加A · 重采样器频响验收   （通带 20Hz 起；判据：1**8kHz 以内**偏差 ≤ %.1f dB）" % CRITERION_DB)
    print("=" * 104)
    hdr = "%-20s %-16s %9s %9s %9s %10s %10s" % (
        "变换", "实现", "偏差@18k", "偏差@20k", "-3dB点", "阻带max", "镜像max")
    print(hdr)
    print("-" * len(hdr))

    worst18_cur = 0.0
    for in_rate, out_rate in pairs:
        rows = []
        cur = os.path.join(K, f"ir-{in_rate}_{out_rate}.txt")
        old = os.path.join(K, f"ir16-{in_rate}_{out_rate}.txt")
        if os.path.exists(cur):
            h, meta = load_ir(cur)
            f, db = response_db(h, out_rate)
            rows.append((f"当前({meta.get('taps')}抽头)", band_stats(f, db, in_rate, out_rate)))
            worst18_cur = max(worst18_cur, rows[-1][1]["r18"])
        if os.path.exists(old):
            h, meta = load_ir(old)
            f, db = response_db(h, out_rate)
            rows.append((f"旧({meta.get('taps')}抽头)", band_stats(f, db, in_rate, out_rate)))
        hr, up, down = reference_ir(in_rate, out_rate)
        fr, dbr = response_db(hr, out_rate)
        rows.append((f"scipy({up}/{down})", band_stats(fr, dbr, in_rate, out_rate)))

        for i, (name, s) in enumerate(rows):
            label = f"{in_rate}→{out_rate}" if i == 0 else ""
            print("%-20s %-16s %9.3f %9.3f %9.0f %10.1f %10.1f" % (
                label, name, s["r18"], s["r20"], s["f3"], s["stop"], s["image"]))
        print()

    print("判据（用户 2026-10-18）：18kHz 以内通带偏差 ≤ %.1f dB" % CRITERION_DB)
    print("当前实现最差 = %.3f dB ⇒ %s" % (
        worst18_cur, "**达标**" if worst18_cur <= CRITERION_DB else "**仍不达标**"))
    print()
    print("说明：「偏差@20k」明显大于「@18k」是**正常**的 —— 本变换的 −3dB 点就在 20.6~21.2kHz，")
    print("      20kHz 已经踩在过渡带上；用户判据取的是 18kHz，两个数都列出来供你判断。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""步骤 2c 端到端对齐：**互相关测零点偏差**。

背景（用户点名的大坑）：分离流水线用 `AudioDecoder`（`MediaExtractor` + `MediaCodec`，**完全不处理
encoder delay / padding**），而播放链路是 ExoPlayer 的 `DefaultAudioSink` —— 后者的管线里**有**
`TrimmingAudioProcessor`，会按 `Format.encoderDelay/encoderPadding` 裁掉起始填充。
两条路径解出来的 PCM 若差一个固定偏移，**按帧号混合就会整体错位**。

做法：同一首歌，两条路径各出一份 16bit 交错立体声 PCM（都从头开始），互相关求最佳对齐滞后。
**要求 ≤ ±1 个采样**才算通过；不通过就先解决，不进混音。

输入（都在 app 私有外部目录）：
  karaoke_capture.pcm / karaoke_capture.txt   ← 播放链路：处理器收到的 PCM（探针=采 PCM）
  e2e_decoder.pcm     / e2e_decoder.txt       ← 分离流水线：AudioDecoder 解码的 PCM

用法:
  py -3.11 tools/karaoke/align_zero.py .karaoke/e2e/karaoke_capture.pcm .karaoke/e2e/e2e_decoder.pcm
"""
import os
import struct
import sys

try:
    import numpy as _np
except Exception:                      # 没有 numpy 也能跑（慢一些）
    _np = None


def read_sidecar(pcm_path):
    txt = os.path.splitext(pcm_path)[0] + ".txt"
    meta = {}
    if os.path.exists(txt):
        for ln in open(txt, encoding="utf-8", errors="replace"):
            if "=" in ln:
                k, v = ln.strip().split("=", 1)
                meta[k.strip()] = v.strip()
    return meta


def read_mono(path, limit_frames=None):
    """读 16bit 交错立体声 → 单声道（L、R 平均）。"""
    with open(path, "rb") as f:
        raw = f.read()
    n = len(raw) // 2
    if _np is not None:
        a = _np.frombuffer(raw[: n * 2], dtype="<i2").astype(_np.float64)
        frames = a.size // 2
        if limit_frames:
            frames = min(frames, limit_frames)
        a = a[: frames * 2].reshape(-1, 2)
        return (a[:, 0] + a[:, 1]) * 0.5
    vals = struct.unpack("<%dh" % n, raw[: n * 2])
    frames = n // 2
    if limit_frames:
        frames = min(frames, limit_frames)
    return [(vals[2 * i] + vals[2 * i + 1]) * 0.5 for i in range(frames)]


def _energy(sig, i, win, stride=32):
    if _np is not None:
        return float(_np.abs(sig[i:i + win:stride]).sum())
    s = 0.0
    for j in range(i, i + win, stride):
        s += abs(sig[j])
    return s


def best_window(sig, win, rate):
    """在开头 60% 里找能量最大的起点（避开静音前奏）。"""
    hi = max(1, int(len(sig) * 0.6) - win)
    step = max(1, rate // 4)
    bi, bv = 0, -1.0
    for i in range(0, hi, step):
        e = _energy(sig, i, win)
        if e > bv:
            bv, bi = e, i
    return bi


def ncc(a, b, off, win, stride=4):
    """a[0:win] 与 b[off:off+win] 的归一化互相关（抽样加速）。"""
    if _np is not None:
        x = a[:win:stride]
        y = b[off:off + win:stride]
        sx = float(_np.sqrt((x * x).sum()))
        sy = float(_np.sqrt((y * y).sum()))
        if sx <= 0 or sy <= 0:
            return -1.0
        return float((x * y).sum()) / (sx * sy)
    s = sa = sb = 0.0
    for i in range(0, win, stride):
        x = a[i]
        y = b[off + i]
        s += x * y
        sa += x * x
        sb += y * y
    if sa <= 0 or sb <= 0:
        return -1.0
    return s / ((sa ** 0.5) * (sb ** 0.5))


def main():
    cap_path = sys.argv[1] if len(sys.argv) > 1 else ".karaoke/e2e/karaoke_capture.pcm"
    dec_path = sys.argv[2] if len(sys.argv) > 2 else ".karaoke/e2e/e2e_decoder.pcm"

    cm = read_sidecar(cap_path)
    dm = read_sidecar(dec_path)
    print("采集(播放链路) 侧车      : %s" % cm)
    print("解码(分离流水线) 侧车    : %s" % dm)
    print("numpy: %s" % ("有" if _np is not None else "无（纯 Python，慢一些）"))

    rate_c = int(cm.get("sampleRate", 0) or 0)
    rate_d = int(dm.get("sampleRate", 0) or 0)
    if rate_c and rate_d and rate_c != rate_d:
        print("❗ 两条路径采样率不同（%d vs %d）—— 必须先统一才能比，本次作废" % (rate_c, rate_d))
        return 2

    cap = read_mono(cap_path)
    dec = read_mono(dec_path)
    print("采集 %d 帧；解码 %d 帧" % (len(cap), len(dec)))

    rate = rate_c or rate_d or 44100
    win = min(len(cap), rate * 2)                 # 2 秒相关窗
    if win < rate // 2:
        print("❗ 采集太短（%d 帧）" % win)
        return 2

    start = best_window(cap, win, rate)
    probe = cap[start:start + win]
    print("相关窗：从采集第 %d 帧（%.3fs）起，长 %d 帧" % (start, start / rate, win))

    span = rate                                   # 搜索 ±1 秒
    lo = max(0, start - span)
    hi = min(len(dec) - win, start + span)
    if hi <= lo:
        print("❗ 解码文件太短，覆盖不到搜索范围")
        return 2

    best, best_v = lo, -2.0
    for off in range(lo, hi + 1, 16):
        v = ncc(probe, dec, off, win, stride=32)
        if v > best_v:
            best_v, best = v, off
    for off in range(max(lo, best - 16), min(hi, best + 16) + 1):
        v = ncc(probe, dec, off, win, stride=4)
        if v > best_v:
            best_v, best = v, off

    delta = best - start                       # 数组空间：cap[i] ⇄ dec[i+delta]
    first_pts = int(cm.get("firstPtsFrame", 0) or 0)
    # 采集起点没拿到锚点时侧车写 -1（跳转后第一块本来就没有锚点）——那是"未知"，不是"−1 帧"。
    # 按 0 处理后 deviation 就等于数组空间滞后 Δ，这才是能报的数。
    if first_pts < 0:
        first_pts = 0
        print("  （侧车 firstPtsFrame=-1 = 采集起点未取到锚点 → 按 0 处理）")
    deviation = delta - first_pts              # 扣掉"采集起点不在文件 0"的影响
    print("\n最佳对齐：采集第 %d 帧 ⇄ 解码第 %d 帧   归一化相关=%.4f" % (start, best, best_v))
    print("  数组空间滞后 Δ = %d 个采样" % delta)
    print("  采集首块 pts 帧 = %d（侧车）；扣掉它 ⇒ **零点偏差 = %d 个采样**" % (first_pts, deviation))
    print("  判据：|零点偏差| ≤ 1 %s" % ("✅ 通过" if abs(deviation) <= 1 else "❌ 未通过"))
    if abs(best_v) < 0.5:
        print("  ⚠️ 相关偏低（%.3f）—— 两条路径可能不是同一段音频，结果不可信" % best_v)
    if abs(delta) > rate // 4:
        print("  ⚠️ Δ 接近搜索边界，可能是错配（试过更宽的搜索范围再看）")
    return 0 if abs(deviation) <= 1 else 1


if __name__ == "__main__":
    sys.exit(main())

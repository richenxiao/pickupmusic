#!/usr/bin/env python3
"""P2 spike 步骤 2 的**真值测试文件**生成器（只用标准库，无 numpy/soundfile 依赖）。

用途：给「按源帧序号混合」这条路线提供**可自证的真值**——处理器从解码后的 PCM 里
直接读出"我是源文件的第几帧"，而不是拿 `Player.currentPosition` 当参考
（那是播放器的估计值，用它去验对齐等于用被测对象验自己）。

两种模式
--------
**`--mode framenum`（无损：WAV / FLAC）**
   每帧把**该帧的绝对帧号**编码进两个声道：
       L = ( idx        & 0xFFF) - 2048
       R = ((idx >> 12) & 0xFFF) - 2048
   解码侧（Android/Kotlin 与 Python 同一套规则）：
       idx = (L + 2048) | ((R + 2048) << 12)
   → 24 位帧号，可无歧义表示 16,777,216 帧（44.1kHz 下 ≈ 6 分 20 秒），够任何一首歌。
   代价：振幅只有 ±2048（≈ -24 dBFS）的"数字噪声"——**别开大音量听**，它就是个测量信号。
   用 `--bits 8` 可把振幅降到 ±128（≈ -48 dBFS，几乎听不见），但无歧义窗口缩到
   65,536 帧（44.1kHz 下 ≈ 1.49 秒）——只要对照时知道期望值，测"seek 后重锚误差"够用。

**`--mode click`（有损：MP3 / M4A）**
   每秒一个 5ms / 1kHz 短促 click，其余静音。有损编码器会引入 priming/padding 与
   时间量化，**拿不到精确帧号**，只能靠咔哒位置看"有没有错位、错多少"。容差要单独说明。

用法
----
    python make_framenum_fixture.py --out fixture_framenum_44100.wav --rate 44100 --seconds 60
    python make_framenum_fixture.py --out fixture_click_44100.wav --rate 44100 --seconds 60 --mode click
    python make_framenum_fixture.py --selftest      # 编码往返自检，不写业务文件

输出一律 **PCM 16bit 立体声 WAV**（与处理器步骤 1 验过的 `enc=2` 一致）。
需要 FLAC 就用 ffmpeg 转：`ffmpeg -i x.wav -c:a flac x.flac`（FLAC 无损，帧号仍然精确）。
"""

import argparse
import os
import struct
import sys
import wave

BITS_DEFAULT = 12
MASK = (1 << BITS_DEFAULT) - 1


def encode_frame(idx: int, bits: int = BITS_DEFAULT) -> tuple[int, int]:
    """帧号 → (L, R) 两个 int16 采样值。bits 是每声道承载的位数（默认 12+12=24 位）。"""
    mask = (1 << bits) - 1
    bias = 1 << (bits - 1)
    lo = (idx & mask) - bias
    hi = ((idx >> bits) & mask) - bias
    return lo, hi


def decode_frame(left: int, right: int, bits: int = BITS_DEFAULT) -> int:
    """(L, R) → 帧号。**Kotlin 侧照抄这一段即可**（注意 int16 → 无符号要先 & 0xFFFF）。"""
    mask = (1 << bits) - 1
    bias = 1 << (bits - 1)
    lo = (left + bias) & mask
    hi = (right + bias) & mask
    return (hi << bits) | lo


def write_framenum(path: str, rate: int, seconds: float, bits: int = BITS_DEFAULT) -> int:
    frames = int(rate * seconds)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(rate)
        buf = bytearray(frames * 4)
        off = 0
        for i in range(frames):
            l, r = encode_frame(i, bits)
            struct.pack_into("<hh", buf, off, l, r)
            off += 4
        w.writeframes(bytes(buf))
    return frames


def write_click(path: str, rate: int, seconds: float) -> int:
    """每秒一个 5ms / 1kHz click（幅度 0.5），其余静音。"""
    import math

    frames = int(rate * seconds)
    click_len = int(rate * 0.005)
    buf = bytearray(frames * 4)
    for s in range(int(seconds)):
        start = s * rate
        for n in range(click_len):
            v = int(0.5 * 32767 * math.sin(2 * math.pi * 1000 * n / rate))
            off = (start + n) * 4
            if off + 4 <= len(buf):
                struct.pack_into("<hh", buf, off, v, v)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(buf))
    return frames


def selftest() -> int:
    """编码 → 落盘 → 读回 → 解码，确认往返无损、且能覆盖到 24 位上限。"""
    import tempfile

    rate = 44100
    probe = [0, 1, 2, 4095, 4096, 100_000, 1_000_000, (1 << 24) - 1]
    for bits in (12, 8):
        path = os.path.join(tempfile.gettempdir(), f"selftest_{bits}.wav")
        with wave.open(path, "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(rate)
            b = bytearray(len(probe) * 4)
            for i, idx in enumerate(probe):
                l, r = encode_frame(idx % (1 << (2 * bits)), bits)
                struct.pack_into("<hh", b, i * 4, l, r)
            w.writeframes(bytes(b))
        with wave.open(path, "rb") as w:
            data = w.readframes(len(probe))
        bad = []
        for i, idx in enumerate(probe):
            l, r = struct.unpack_from("<hh", data, i * 4)
            got = decode_frame(l, r, bits)
            want = idx % (1 << (2 * bits))
            if got != want:
                bad.append((idx, want, got))
        os.remove(path)
        print(f"bits={bits}: 往返 {'OK' if not bad else 'FAIL ' + str(bad)}")
        if bad:
            return 1
    print("振幅上限：bits=12 → ±2048（≈ -24 dBFS）；bits=8 → ±128（≈ -48 dBFS）")
    print("无歧义窗口：bits=12 → 16,777,216 帧（44.1k 下 380.4s）；bits=8 → 65,536 帧（1.49s）")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="P2 spike 真值测试文件生成器")
    ap.add_argument("--out", help="输出 WAV 路径")
    ap.add_argument("--rate", type=int, default=44100, help="采样率（默认 44100）")
    ap.add_argument("--seconds", type=float, default=60.0, help="时长秒（默认 60）")
    ap.add_argument("--mode", choices=["framenum", "click"], default="framenum")
    ap.add_argument("--bits", type=int, default=BITS_DEFAULT, choices=[8, 10, 12],
                    help="framenum 模式每声道承载的位数，默认 12")
    ap.add_argument("--selftest", action="store_true", help="只跑编码往返自检")
    a = ap.parse_args()

    if a.selftest:
        return selftest()
    if not a.out:
        ap.error("需要 --out（或用 --selftest）")

    if a.mode == "framenum":
        n = write_framenum(a.out, a.rate, a.seconds, a.bits)
        window = (1 << (2 * a.bits)) / a.rate
        print(f"已写 {a.out}：{n} 帧 @ {a.rate}Hz 立体声 16bit（framenum, bits={a.bits}）")
        print(f"无歧义窗口 {1 << (2 * a.bits)} 帧 ≈ {window:.1f}s")
    else:
        n = write_click(a.out, a.rate, a.seconds)
        print(f"已写 {a.out}：{n} 帧 @ {a.rate}Hz 立体声 16bit（click，每秒一个 5ms/1kHz）")
        print("[!] 有损格式用这个；精确帧号拿不到，容差需单独说明。")
    print(f"大小 {os.path.getsize(a.out) / 1024 / 1024:.2f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())

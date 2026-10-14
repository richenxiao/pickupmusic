#!/usr/bin/env python3
"""步骤 2c 素材：帧号真值文件 **44.1k / 48k / 96k 各一份（WAV + FLAC）**。

用户 2026-10-13 的验收标准点名要这三个采样率、两种无损容器各一份。
WAV 由 `make_framenum_fixture.py` 生成；FLAC 用 ffmpeg 转（无损 ⇒ 帧号仍精确），并**回读校验**。

校验一律**先转成裸 s16le** 再读 —— 96k 的 WAV 头是 WAVE_FORMAT_EXTENSIBLE，Python 标准库
`wave` 读不了（`unknown format: 65534`），绕开容器最省事。

用法（要带 imageio-ffmpeg 的那个 Python）：
    py -3.11 tools/karaoke/make_2c_fixtures.py
产物在 `.karaoke/fixtures/`，推送命令在最后打印。
"""
import os
import struct
import subprocess
import sys
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FIX = os.path.join(ROOT, ".karaoke", "fixtures")
TOOLS = os.path.join(ROOT, "tools", "karaoke")

# 采样率。秒数沿用已有的 60s（2b 阶段已按 60s 生成过 48k/96k），不再重生成。
RATES = [44100, 48000, 96000]
SECONDS = 60.0


def ffmpeg_exe():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def run(args):
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit("失败: %s\n%s" % (" ".join(args), r.stderr[-800:]))


def decode_frame(l, r, bits=12):
    mask = (1 << bits) - 1
    bias = 1 << (bits - 1)
    return (((r + bias) & mask) << bits) | ((l + bias) & mask)


def verify(ff, path, rate):
    """把任意音频转成裸 s16le 读回，抽查若干点的帧号。返回 (ok, 说明)。"""
    raw = os.path.join(FIX, "_verify.raw")
    run([ff, "-y", "-loglevel", "error", "-i", path, "-f", "s16le", "-acodec", "pcm_s16le", raw])
    data = open(raw, "rb").read()
    os.remove(raw)
    n = len(data) // 4                      # 帧数（立体声 2×2 字节）
    probes = [0, 1, 4095, 4096, 100_000, n // 2, n - 1, n - 2]
    probes = [p for p in probes if 0 <= p < n]
    bad = []
    for idx in probes:
        l, r = struct.unpack_from("<hh", data, idx * 4)
        got = decode_frame(l, r)
        if got != idx:
            bad.append((idx, got))
    return (not bad), "n=%d 帧（%.1fs）%s" % (n, n / rate, "" if not bad else "BAD=%s" % bad)


def main():
    os.makedirs(FIX, exist_ok=True)
    ff = ffmpeg_exe()
    print("ffmpeg:", ff)
    print()

    made = []
    for rate in RATES:
        wav = os.path.join(FIX, "fixture_fn_%d.wav" % rate)
        flac = os.path.join(FIX, "fixture_fn_%d.flac" % rate)
        if not os.path.exists(wav) or os.path.getsize(wav) < 1000:
            print("生成 WAV %s …" % os.path.basename(wav), flush=True)
            run([sys.executable, os.path.join(TOOLS, "make_framenum_fixture.py"),
                 "--out", wav, "--rate", str(rate), "--seconds", str(SECONDS)])
        with wave.open(wav, "rb") as w:
            rw = w.getframerate()
        ok_w, why_w = verify(ff, wav, rw)
        print("  WAV  %-26s %-4s @%dHz %5.1fMB  %s" % (
            os.path.basename(wav), "OK" if ok_w else "FAIL", rw,
            os.path.getsize(wav) / 1024 / 1024, why_w))

        run([ff, "-y", "-loglevel", "error", "-i", wav, "-c:a", "flac",
             "-compression_level", "8", flac])
        ok_f, why_f = verify(ff, flac, rw)
        print("  FLAC %-26s %-4s          %5.1fMB  %s" % (
            os.path.basename(flac), "OK" if ok_f else "FAIL",
            os.path.getsize(flac) / 1024 / 1024, why_f))
        made += [wav, flac]

    mp3 = os.path.join(FIX, "fixture_click_44100_320.mp3")
    print("\n咔哒 MP3: %s %s" % (
        os.path.basename(mp3),
        "在（%.1fMB）" % (os.path.getsize(mp3) / 1024 / 1024) if os.path.exists(mp3)
        else "**缺，先跑 make_2b_fixtures.py**"))

    print("\n── 推到手机（app 私有外部目录下 fixtures/）──")
    print("adb shell mkdir -p /sdcard/Android/data/com.shiyin.music/files/fixtures")
    for f in made + ([mp3] if os.path.exists(mp3) else []):
        print('adb push "%s" /sdcard/Android/data/com.shiyin.music/files/fixtures/' % f)


if __name__ == "__main__":
    main()

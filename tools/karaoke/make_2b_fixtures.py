#!/usr/bin/env python3
"""步骤 2b 素材：帧号真值文件 → FLAC（无损，**转完回读逐帧校验**）；
咔哒标记文件 → 高码率 MP3（有损，**顺手把咔哒位置漂移量测出来当容差**，用户点名要"容差单独说明"）。

用法（要带 imageio-ffmpeg 的那个 Python）：
    py -3.11 tools/karaoke/make_2b_fixtures.py
"""
import os
import struct
import subprocess
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FIX = os.path.join(ROOT, ".karaoke", "fixtures")
RATE = 44100


def ffmpeg_exe():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def run(args):
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit("ffmpeg 失败: %s\n%s" % (" ".join(args), r.stderr[-800:]))
    return r


def decode_frame(l, r, bits=12):
    """与 Kotlin `KaraokeFrameCodec.decodeFrame` / `make_framenum_fixture.py` 同一套规则。"""
    mask = (1 << bits) - 1
    bias = 1 << (bits - 1)
    return (((r + bias) & mask) << bits) | ((l + bias) & mask)


def read_s16(path):
    with wave.open(path, "rb") as w:
        nch, sw, n = w.getnchannels(), w.getsampwidth(), w.getnframes()
        data = w.readframes(n)
    return nch, sw, n, data


def main():
    ff = ffmpeg_exe()
    print("ffmpeg:", ff)

    src = os.path.join(FIX, "fixture_fn_44100.wav")
    flac = os.path.join(FIX, "fixture_fn_44100.flac")
    back = os.path.join(FIX, "_verify_flac.wav")

    # ── ① WAV → FLAC ─────────────────────────────────────────────
    run([ff, "-y", "-loglevel", "error", "-i", src, "-c:a", "flac", "-compression_level", "8", flac])
    print("FLAC: %s  %d bytes" % (flac, os.path.getsize(flac)))

    # ── ② 回读 FLAC → WAV，逐点核对帧号 ────────────────────────────
    run([ff, "-y", "-loglevel", "error", "-i", flac, "-c:a", "pcm_s16le", back])
    nch, sw, n, data = read_s16(back)
    print("回读: channels=%d sampwidth=%d frames=%d" % (nch, sw, n))
    probes = (0, 1, 4095, 4096, 100_000, n // 2, n - 1)
    bad = []
    for idx in probes:
        l, r = struct.unpack_from("<hh", data, idx * 4)
        got = decode_frame(l, r)
        if got != idx:
            bad.append((idx, got))
    shape_ok = (nch == 2 and sw == 2 and n == 2_646_000)
    print("FLAC 无损校验: %s" % ("通过（抽查 %d 点帧号全部还原；帧数/声道/位深一致）" % len(probes)
                                 if shape_ok and not bad else "失败 shape_ok=%s bad=%s" % (shape_ok, bad)))

    # ── ③ 咔哒 WAV → MP3 320k ────────────────────────────────────
    click = os.path.join(FIX, "fixture_click_44100.wav")
    mp3 = os.path.join(FIX, "fixture_click_44100_320.mp3")
    run([ff, "-y", "-loglevel", "error", "-i", click, "-c:a", "libmp3lame", "-b:a", "320k", mp3])
    print("MP3: %s  %d bytes" % (mp3, os.path.getsize(mp3)))

    # ── ④ 回读 MP3 → WAV，量咔哒位置漂移（= 容差）──────────────────
    back2 = os.path.join(FIX, "_verify_mp3.wav")
    run([ff, "-y", "-loglevel", "error", "-i", mp3, "-c:a", "pcm_s16le", back2])

    def onsets(path, thr=8000):
        _, _, nn, dd = read_s16(path)
        out, prev = [], False
        for i in range(nn):
            on = abs(struct.unpack_from("<h", dd, i * 4)[0]) > thr
            if on and not prev:
                out.append(i)
            prev = on
        return out

    a, b = onsets(click), onsets(back2)
    print("咔哒数: 原始=%d 回读=%d" % (len(a), len(b)))
    if a and b and len(a) == len(b):
        shifts = [y - x for x, y in zip(a, b)]
        print("咔哒位置漂移: 最小 %d / 最大 %d 采样（%.2f ms）← MP3 的容差（编码器前置延迟+量化扩散）"
              % (min(shifts), max(shifts), max(abs(s) for s in shifts) / (RATE / 1000.0)))
    for f in (back, back2):
        os.remove(f)


if __name__ == "__main__":
    main()

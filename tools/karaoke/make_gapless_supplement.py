#!/usr/bin/env python3
"""闸门 1 **补证**素材：把"换条目重锚"扩到 MP3 / FLAC，并覆盖损失格式的判据。

两种"味道"，因为判据不同
----------------------
· **帧号真值（framenum，无损）**：把绝对帧号编码进 PCM —— WAV/FLAC 逐采样可用，
  `戳差(P−T)` 必须 ≤1 采样。**MP3 用不了**（有损会把 ±2048 的编码层洗掉）。
· **咔哒（click，每秒一个 5ms/1kHz）**：有损格式唯一能用的 —— 探针按"每秒一个整数秒"
  的网格反推真值。这是 2b 阶段就给 MP3 定的口径。

两条播放列表（一次实验一条，因为探针模式是全局的）
--------------------------------------------------
    FN    : [WAV 44.1k 帧号] [FLAC 48k 帧号] [FLAC 44.1k 帧号]        → probe=fn
    CLICK : [WAV 44.1k 咔哒] [MP3 VBR **无 Xing** 44.1k] [MP3 LAME 48k] → probe=click

第 2 条刻意放"**无 Xing 头**的 VBR MP3"（解码器拿不到 encoder delay ⇒ 无法裁剪起始填充），
第 3 条放"**带 LAME 标签**的 MP3"（有 encoder delay 信息）。两者在"换条目后的时间戳"
上表现是否不同，正是要看的。

用法
----
    py -3.11 tools/karaoke/make_gapless_supplement.py
产物在 `.karaoke/fixtures/`，末尾打印推送命令。
"""
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOLS = os.path.join(ROOT, "tools", "karaoke")
FIX = os.path.join(ROOT, ".karaoke", "fixtures")
EPOCH_US = 1_000_000_000_000

sys.path.insert(0, TOOLS)
from make_framenum_fixture import write_framenum, write_click  # noqa: E402


def find_ffmpeg():
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        pass
    from shutil import which
    return which("ffmpeg")


def run(args):
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit("失败: %s\n%s" % (" ".join(args), r.stderr[-800:]))


def has_xing(path):
    """MP3 里有没有 Xing/Info 头（LAME 用它带 encoder delay/padding）。"""
    with open(path, "rb") as f:
        head = f.read(8192)
        f.seek(0, os.SEEK_END)
        tail = f.read(4096)
    return (b"Xing" in head or b"Info" in head or b"Xing" in tail or b"Info" in tail)


def main():
    os.makedirs(FIX, exist_ok=True)
    ff = find_ffmpeg()
    if not ff:
        raise SystemExit("需要 ffmpeg（imageio_ffmpeg 或 PATH）")
    print("ffmpeg:", ff)

    specs = [
        # (文件名, 采样率, 秒, 味道, 容器)
        ("sup1_wav44100_fn.wav", 44100, 12.0, "fn", "wav"),
        ("sup2_flac48000_fn.flac", 48000, 10.0, "fn", "flac"),
        ("sup3_flac44100_fn.flac", 44100, 12.0, "fn", "flac"),
        ("sup1_wav44100_click.wav", 44100, 12.0, "click", "wav"),
        ("sup2_mp3vbr_noxing_44100_click.mp3", 44100, 10.0, "click", "mp3_noxing"),
        ("sup3_mp3lame_48000_click.mp3", 48000, 12.0, "click", "mp3_lame"),
    ]

    made = []
    for name, rate, secs, flavor, cont in specs:
        out = os.path.join(FIX, name)
        src = os.path.join(FIX, "_tmp_supp.wav")
        if flavor == "fn":
            frames = write_framenum(src, rate, secs)
        else:
            frames = write_click(src, rate, secs)

        if cont == "wav":
            os.replace(src, out)
        elif cont == "flac":
            run([ff, "-y", "-loglevel", "error", "-i", src, "-c:a", "flac",
                 "-compression_level", "8", out])
            os.remove(src)
        elif cont == "mp3_noxing":
            # **FRAME_SIZE 必须显式给**：libmp3lame 只在这个帧大小下才写 Xing/LAME 标签。
            run([ff, "-y", "-loglevel", "error", "-i", src, "-c:a", "libmp3lame",
                 "-q:a", "4", "-write_xing", "0", out])
            os.remove(src)
        else:
            run([ff, "-y", "-loglevel", "error", "-i", src, "-c:a", "libmp3lame",
                 "-b:a", "192k", out])
            os.remove(src)

        xing = has_xing(out) if out.endswith(".mp3") else None
        made.append(out)
        print("  %-38s %6.2fs %8d 帧 %5.2fMB%s" % (
            name, frames / rate, frames, os.path.getsize(out) / 1024 / 1024,
            "" if xing is None else ("  Xing/LAME=%s" % xing)))

    print("\n── FN 列表（probe=fn）──")
    fn = [m for m in made if m.endswith("_fn.wav") or m.endswith("_fn.flac")]
    print("printf 'playlist %s' > .../karaoke-cmd" % " ".join(os.path.basename(m) for m in fn))
    print("\n── CLICK 列表（probe=click）──")
    ck = [m for m in made if "_click." in m]
    print("printf 'playlist %s' > .../karaoke-cmd" % " ".join(os.path.basename(m) for m in ck))

    man = {"fn": [os.path.basename(m) for m in fn], "click": [os.path.basename(m) for m in ck],
           "epochUs": EPOCH_US,
           "note": "判据：每条第一个带 pts 的 buffer 源帧号应为 0；fn 味道要求 戳差(P−T) ≤1 采样"}
    mp = os.path.join(FIX, "gapless_supplement.json")
    with open(mp, "w", encoding="utf-8") as f:
        json.dump(man, f, ensure_ascii=False, indent=2)
    print("\n清单：%s" % mp)
    print("\n── 推送 ──")
    for m in made:
        print('adb push "%s" /sdcard/Android/data/com.shiyin.music/files/fixtures/' % m)
    return 0


if __name__ == "__main__":
    sys.exit(main())

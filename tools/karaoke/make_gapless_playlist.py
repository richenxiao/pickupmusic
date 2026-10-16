#!/usr/bin/env python3
"""闸门 1（gapless 重锚）素材：**三条目真值播放列表**。

为什么需要
----------
闸门 1 要验的是"换条目之后帧号还能对上"。为此必须让播放器**真的自动连播多条**，
而且每条都要能**自证帧号** —— 也就是 `make_framenum_fixture.py` 那种把绝对帧号
编码进 PCM 的无损文件。

默认规格（**故意把两个维度都混起来**，一次覆盖到位）：
    第 1 条  WAV  44100Hz  30s
    第 2 条  FLAC 48000Hz  20s      ← 既换容器、又换采样率
    第 3 条  WAV  44100Hz  25s      ← 时长各不相同

判定依据（供真机日志对拍）
--------------------------
Media3 给每条算的渲染器偏移是**逐条累加**的：
    条目 i 的 startOffset = 1e12µs + Σ(前 i 条时长)
所以第 2/3 条的"第一个 buffer"换算出来的源帧号必须回到 **0**；
若沿用第一条的偏移，会算成一个 ≈ 前一条的时长（远超该文件长度）⇒ 欠载 ⇒ 退回原曲。
本脚本把期望偏移写进 `gapless_playlist.json`，真机日志直接对这份清单即可。

用法
----
    py -3.11 tools/karaoke/make_gapless_playlist.py
    py -3.11 tools/karaoke/make_gapless_playlist.py --seconds 8,6,7   # 快速自测用短文件

产物在 `.karaoke/fixtures/`，末尾打印 adb 推送命令。
FLAC 需要 ffmpeg（优先用 imageio_ffmpeg 自带的那份）；找不到就退回 WAV 并**明确打印**。
"""
import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOLS = os.path.join(ROOT, "tools", "karaoke")
FIX = os.path.join(ROOT, ".karaoke", "fixtures")

# 渲染器偏移的起点（MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US）。
EPOCH_US = 1_000_000_000_000

# (序号, 采样率, 时长秒, 容器)
DEFAULT_SPEC = [
    (1, 44100, 30.0, "wav"),
    (2, 48000, 20.0, "flac"),
    (3, 44100, 25.0, "wav"),
]

sys.path.insert(0, TOOLS)
from make_framenum_fixture import write_framenum  # noqa: E402


def find_ffmpeg():
    """优先 imageio_ffmpeg（本仓库既有脚本就用它），其次 PATH。找不到返回 None。"""
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        pass
    from shutil import which
    return which("ffmpeg")


def to_flac(ff, wav, flac):
    r = subprocess.run(
        [ff, "-y", "-loglevel", "error", "-i", wav, "-c:a", "flac",
         "-compression_level", "8", flac],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        raise SystemExit("FLAC 转换失败：%s\n%s" % (r.args, r.stderr[-800:]))


def main():
    ap = argparse.ArgumentParser(description="闸门 1 三条目真值播放列表生成器")
    ap.add_argument("--prefix", default="gapless", help="文件名前缀（默认 gapless）")
    ap.add_argument("--seconds", default=None,
                    help="逗号分隔的三条时长（秒）；默认 30,20,25")
    a = ap.parse_args()

    seconds = [float(x) for x in a.seconds.split(",")] if a.seconds else None
    spec = DEFAULT_SPEC
    if seconds:
        spec = [(i + 1, rate, seconds[i], cont) for i, (_, rate, _, cont) in enumerate(DEFAULT_SPEC)]

    os.makedirs(FIX, exist_ok=True)
    ff = find_ffmpeg()
    if any(c == "flac" for _, _, _, c in spec) and not ff:
        print("[!] 找不到 ffmpeg ⇒ 第 2 条改用 **WAV**（容器维度这次就覆盖不到了）")
    print("ffmpeg:", ff or "（无）")

    items = []
    start_us = EPOCH_US
    made = []

    for idx, rate, secs, cont in spec:
        base = "%s_%d_%d" % (a.prefix, idx, rate)
        wav = os.path.join(FIX, base + ".wav")
        frames = write_framenum(wav, rate, secs)

        if cont == "flac" and ff:
            path = os.path.join(FIX, base + ".flac")
            to_flac(ff, wav, path)
            os.remove(wav)              # 只留 FLAC，免得推送时推两份
        else:
            path = wav
        made.append(path)

        dur_us = round(frames * 1_000_000.0 / rate)
        items.append({
            "index": idx - 1,
            "file": os.path.basename(path),
            "rate": rate,
            "seconds": round(frames / rate, 6),
            "frames": frames,
            "startOffsetUs": start_us,
            "endOffsetUs": start_us + dur_us,
            # 该条目第一个 buffer 的**期望源帧号**永远是 0 —— 这就是判据
            "expectedFirstSrcFrame": 0,
        })
        start_us += dur_us

        print("  %-28s %6.2fs  %8d 帧  startOffsetUs=%d  %5.2fMB" % (
            os.path.basename(path), frames / rate, frames, items[-1]["startOffsetUs"],
            os.path.getsize(path) / 1024 / 1024))

    manifest = {
        "epochUs": EPOCH_US,
        "note": "startOffsetUs 是 Media3 给该条目的**渲染器偏移**（逐条累加）；"
                "该条目第一个 buffer 的源帧号应为 0。",
        "items": items,
    }
    mp = os.path.join(FIX, a.prefix + "_playlist.json")
    with open(mp, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print("\n清单：%s" % mp)

    print("\n── 推到手机（app 私有外部目录下 fixtures/）──")
    print("adb shell mkdir -p /sdcard/Android/data/com.shiyin.music/files/fixtures")
    for f in made:
        print('adb push "%s" /sdcard/Android/data/com.shiyin.music/files/fixtures/' % f)

    print("\n── 连播指令（写进同名目录的 karaoke-cmd，App 轮询到就执行）──")
    print('adb shell "echo playlist %s > /sdcard/Android/data/com.shiyin.music/files/karaoke-cmd"'
          % " ".join(i["file"] for i in items))
    return 0


if __name__ == "__main__":
    sys.exit(main())

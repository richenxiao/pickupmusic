# P0 Spike A3：Kotlin STFT 与 torch 语义对拍。
# 流程：DUMP_FIXTURES=1 跑 MdxStftTest.fixtureDump_whenEnvSet 导出夹具 → 本脚本比对。
# Kotlin 侧输入文件即真值来源（避免跨语言 PRNG 对齐）；参考值由 stft_mirror 的
# numpy 镜像给出（该镜像已与 torch.stft 对拍一致，见 stft_mirror.py 输出）。
# 注意：夹具是 Java DataOutputStream 写的**大端** float32。
# 用法：py -3 tools/karaoke/compare_stft_fixture.py
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stft_mirror import stft_np

FIX = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".karaoke", "fixtures")


def main():
    ok = True
    for n_fft in (6144, 7680):
        hop = 1024
        x = np.fromfile(os.path.join(FIX, f"kotlin_in_{n_fft}.f32"), dtype=">f4")
        got = np.fromfile(os.path.join(FIX, f"kotlin_spec_{n_fft}.f32"), dtype=">f4")
        n_frames = 1 + x.size // hop
        n_bins = n_fft // 2 + 1
        got = got.reshape(n_frames, n_bins, 2)
        got_c = got[..., 0] + 1j * got[..., 1]

        ref = stft_np(x.astype(np.float64), n_fft, hop)  # (n_frames, n_bins)
        err = np.abs(got_c - ref)
        rel = err.max() / np.abs(ref).max()
        print(f"n_fft={n_fft}: frames={n_frames} max|err|={err.max():.3e} "
              f"rel={rel:.3e} (ref peak={np.abs(ref).max():.1f})")
        if rel > 1e-3:  # float32 FFT 累积误差的宽容线；量级应远小于此
            ok = False
    print("PASS" if ok else "FAIL")


if __name__ == "__main__":
    main()

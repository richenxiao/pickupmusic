# P0 Spike 阶段 B：整条分离流水线（分块/拼接/残差）的 python 参考输出。
# 与 Kotlin 的 MdxSeparator 逐块同构（UVR 经典分块：trim=n_fft/2、genSize=chunk-2·trim、
# 两端补零、首尾相接），用于 SNR 对拍。
# 产物（.karaoke/fixtures/，大端 float32）：pipe_in_<tag>.f32（2×N 输入）、
#   pipe_acc_<tag>.f32、pipe_voc_<tag>.f32（参考输出）
# 用法：py -3 tools/karaoke/make_pipeline_fixture.py [tag ...]
import os
import sys

import numpy as np
import onnxruntime as ort

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stft_mirror import stft_np, istft_np

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.normpath(os.path.join(HERE, "..", "..", ".karaoke", "fixtures"))
MODELS = os.path.normpath(os.path.join(HERE, "..", "..", ".karaoke", "models"))

# tag -> (文件名, n_fft, dim_f, compensate, 预测的是伴奏吗)
PARAMS = {
    "9482": ("UVR_MDXNET_9482.onnx", 6144, 2048, 1.035, False),
    "inst_hq_3": ("UVR-MDX-NET-Inst_HQ_3.onnx", 6144, 3072, 1.022, True),
    "voc_ft": ("UVR-MDX-NET-Voc_FT.onnx", 7680, 3072, 1.021, False),
}
HOP = 1024
DIM_T = 256


def make_mix(seconds=20, sr=44100, seed=2026):
    """合成一段「像歌」的立体声：低音贝斯 + 和弦 + 带颤音的旋律 + 轻噪声。"""
    rng = np.random.RandomState(seed)
    n = sr * seconds
    t = np.arange(n) / sr
    bass = 0.25 * np.sin(2 * np.pi * 55 * t)
    chord = 0.2 * (np.sin(2 * np.pi * 220 * t) + np.sin(2 * np.pi * 277 * t) + np.sin(2 * np.pi * 330 * t))
    vib = 1 + 0.02 * np.sin(2 * np.pi * 5 * t)
    melody = 0.3 * np.sin(2 * np.pi * 440 * t * vib)
    noise = 0.02 * rng.randn(n)
    left = (bass + chord + melody + noise).astype(np.float32)
    right = (bass + chord * 0.9 + melody * 0.95 + 0.02 * rng.randn(n)).astype(np.float32)
    return np.stack([left, right])


def run_pipeline(sess, mix, n_fft, dim_f, compensate, predicts_inst):
    trim = n_fft // 2
    chunk = HOP * (DIM_T - 1)
    gen = chunk - 2 * trim
    n = mix.shape[1]
    pad = gen if n % gen == 0 else gen - (n % gen)
    total = (n + pad) // gen
    n_bins = n_fft // 2 + 1

    out = np.zeros((2, n), dtype=np.float32)
    for idx in range(total):
        start = idx * gen
        seg = np.zeros((2, chunk), dtype=np.float32)
        # 对应 Kotlin：越界即零（不整首复制带补丁的 mix）
        for j in range(chunk):
            k = start + j - trim
            if 0 <= k < n:
                seg[:, j] = mix[:, k]

        x = np.zeros((1, 4, dim_f, DIM_T), dtype=np.float32)
        for ch in range(2):
            s = stft_np(seg[ch].astype(np.float64), n_fft, HOP)[:, :dim_f].T
            x[0, ch * 2] = s.real
            x[0, ch * 2 + 1] = s.imag
        y = sess.run(None, {"input": x})[0]

        for ch in range(2):
            re = y[0, ch * 2].T
            im = y[0, ch * 2 + 1].T
            full = np.zeros((re.shape[0], n_bins), dtype=np.complex128)
            full[:, :dim_f] = re + 1j * im
            wave = istft_np(full, n_fft, HOP)
            dst = start
            end = min(start + gen, n)
            if end > dst:
                out[ch, dst:end] = wave[trim: trim + (end - dst)].astype(np.float32)

    if predicts_inst:
        acc, voc = out, mix - out * compensate
    else:
        voc, acc = out, mix - out * compensate
    return acc.astype(np.float32), voc.astype(np.float32)


def main():
    os.makedirs(FIX, exist_ok=True)
    mix = make_mix()
    tags = sys.argv[1:] or list(PARAMS)
    np.asarray(mix, dtype=">f4").tofile(os.path.join(FIX, "pipe_in.f32"))
    print(f"输入 {mix.shape} peak={np.abs(mix).max():.3f}")
    for tag in tags:
        fname, n_fft, dim_f, comp, inst = PARAMS[tag]
        sess = ort.InferenceSession(os.path.join(MODELS, fname), providers=["CPUExecutionProvider"])
        acc, voc = run_pipeline(sess, mix, n_fft, dim_f, comp, inst)
        np.asarray(acc, dtype=">f4").tofile(os.path.join(FIX, f"pipe_acc_{tag}.f32"))
        np.asarray(voc, dtype=">f4").tofile(os.path.join(FIX, f"pipe_voc_{tag}.f32"))
        res = np.abs(mix - (acc + voc))
        print(f"{tag}: acc peak={np.abs(acc).max():.3f} voc peak={np.abs(voc).max():.3f} "
              f"(acc+voc 与 mix 的最大偏差={res.max():.4f}，即 compensate 造成)")


if __name__ == "__main__":
    main()

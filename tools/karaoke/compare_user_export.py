# 用户实测对拍：把手机上导出的「伴奏」与桌面参考实现（同模型）的输出比对，
# 并量化"鼓是否被抬高"（分频段能量占比）。
# 用法：py -3 tools/karaoke/compare_user_export.py <原曲> <手机导出的伴奏.wav> [模型tag]
import os
import sys
import wave

import numpy as np
import onnxruntime as ort
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stft_mirror import stft_np, istft_np
from make_pipeline_fixture import PARAMS, HOP, DIM_T, MODELS


def read_wav(path):
    with wave.open(path, "rb") as w:
        ch, sr, n = w.getnchannels(), w.getframerate(), w.getnframes()
        data = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float32) / 32768.0
    return data.reshape(-1, ch).T, sr


def band_shares(x, sr):
    """粗略分频段能量占比：低(<250Hz) / 中(250-2k) / 高(2k-8k) / 更高(>8k)。"""
    n = min(x.shape[1], sr * 30)  # 取前 30 秒够代表
    seg = x[:, :n]
    spec = np.abs(np.fft.rfft(seg, axis=1)) ** 2
    freqs = np.fft.rfftfreq(n, 1 / sr)
    total = spec.sum()
    out = {}
    for name, (lo, hi) in {
        "低<250Hz": (0, 250), "中250-2k": (250, 2000),
        "高2k-8k": (2000, 8000), "超高>8k": (8000, sr / 2),
    }.items():
        m = (freqs >= lo) & (freqs < hi)
        out[name] = spec[:, m].sum() / total
    return out


def run_ref(model_file, mix, n_fft, dim_f, compensate, predicts_inst, chunk_sec=None):
    """桌面参考：与 Kotlin 同构的 UVR 经典分块（可用 chunk_sec 只跑前 N 秒加速）。"""
    if chunk_sec:
        mix = mix[:, : int(chunk_sec * 44100)]
    sess = ort.InferenceSession(os.path.join(MODELS, model_file), providers=["CPUExecutionProvider"])
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
            full = np.zeros((DIM_T, n_bins), dtype=np.complex128)
            full[:, :dim_f] = y[0, ch * 2].T + 1j * y[0, ch * 2 + 1].T
            wave = istft_np(full, n_fft, HOP)
            end = min(start + gen, n)
            if end > start:
                out[ch, start:end] = wave[trim: trim + (end - start)].astype(np.float32)
        if (idx + 1) % 10 == 0:
            print(f"  参考跑批 {idx+1}/{total}")
    if predicts_inst:
        acc, voc = out, mix - out * compensate
    else:
        voc, acc = out, mix - out * compensate
    return acc, voc


def snr(ref, got):
    n = min(ref.shape[1], got.shape[1])
    e = got[:, :n] - ref[:, :n]
    return 10 * np.log10((ref[:, :n] ** 2).sum() / max((e ** 2).sum(), 1e-20))


def main():
    src, exported = sys.argv[1], sys.argv[2]
    tag = sys.argv[3] if len(sys.argv) > 3 else "inst_hq_3"
    model_file, n_fft, dim_f, comp, inst = PARAMS[tag]

    mix, sr = sf.read(src, always_2d=True)
    mix = mix.T.astype(np.float32)
    if mix.shape[0] == 1:
        mix = np.vstack([mix, mix])
    print(f"原曲 {mix.shape} sr={sr} 峰值={np.abs(mix).max():.3f}")

    phone_acc, phone_sr = read_wav(exported)
    print(f"手机导出伴奏 {phone_acc.shape} sr={phone_sr} 峰值={np.abs(phone_acc).max():.3f}")

    # 只比前 60 秒（够判断特性，且参考跑得快）
    secs = 60
    ref_acc, ref_voc = run_ref(model_file, mix, n_fft, dim_f, comp, inst, chunk_sec=secs)

    a = phone_acc[:, : secs * 44100]
    b = ref_acc[:, : secs * 44100]
    print(f"\n【对拍】手机导出 vs 桌面参考（同模型）前 {secs}s：SNR = {snr(b, a):.1f} dB")

    mix60 = mix[:, : secs * 44100]
    print("\n【分频段能量占比】原曲 → 手机导出伴奏 → 桌面参考伴奏")
    for name, v in band_shares(mix60, sr).items():
        v2 = band_shares(a, sr)[name]
        v3 = band_shares(b, sr)[name]
        print(f"  {name:>10}: {v*100:5.1f}%  →  {v2*100:5.1f}%  →  {v3*100:5.1f}%")


if __name__ == "__main__":
    main()

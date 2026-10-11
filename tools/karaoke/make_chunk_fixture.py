# P0 Spike A4：为「一个 chunk 的 MDX-Net 推理」生成对拍夹具。
# 产物（.karaoke/fixtures/，大端 float32）：
#   chunk_mix_<model>.f32       输入音频块（2 × chunk_samples，声道优先）
#   chunk_in_<model>.f32        模型输入张量 [1,4,dim_f,dim_t]（ch,ri,f,t，与参考实现一致）
#   chunk_out_<model>.f32       onnxruntime 输出张量 [1,4,dim_f,dim_t]
#   chunk_wave_<model>.f32      iSTFT 还原的波形（2 × chunk_samples）
# 用法：py -3 tools/karaoke/make_chunk_fixture.py [model.onnx ...]
import os
import sys

import numpy as np
import onnxruntime as ort

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stft_mirror import hann_periodic, stft_np, istft_np

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.normpath(os.path.join(HERE, "..", "..", ".karaoke", "fixtures"))
MODELS = os.path.normpath(os.path.join(HERE, "..", "..", ".karaoke", "models"))

# 与 UVR model_data.json 核实值一致（见 A1 报告）
PARAMS = {
    "UVR_MDXNET_9482.onnx": dict(tag="9482", n_fft=6144, hop=1024, dim_f=2048),
    "UVR-MDX-NET-Inst_HQ_3.onnx": dict(tag="inst_hq_3", n_fft=6144, hop=1024, dim_f=3072),
    "UVR-MDX-NET-Voc_FT.onnx": dict(tag="voc_ft", n_fft=7680, hop=1024, dim_f=3072),
}
DIM_T = 256  # 三个模型的输入张量第 4 维实测均为 256（inspect_onnx.py）


def write_f32(path, arr):
    np.asarray(arr, dtype=">f4").tofile(path)


def main():
    os.makedirs(FIX, exist_ok=True)
    rng = np.random.RandomState(2026)
    targets = sys.argv[1:] or list(PARAMS)
    for fname in targets:
        p = PARAMS[fname]
        n_fft, hop, dim_f = p["n_fft"], p["hop"], p["dim_f"]
        n_bins = n_fft // 2 + 1
        chunk_samples = hop * (DIM_T - 1)
        tag = p["tag"]

        mix = rng.randn(2, chunk_samples).astype(np.float32) * 0.3

        # STFT（numpy 镜像，已与 torch 对拍一致）→ 参考实现的打包布局
        specs = [stft_np(mix[ch].astype(np.float64), n_fft, hop) for ch in range(2)]
        # specs[ch]: (n_frames, n_bins) complex → 截到 dim_f，转 (f, t)
        x = np.zeros((1, 4, dim_f, DIM_T), dtype=np.float32)
        for ch in range(2):
            s = specs[ch][:, :dim_f].T  # (dim_f, n_frames)
            x[0, ch * 2 + 0] = s.real
            x[0, ch * 2 + 1] = s.imag

        sess = ort.InferenceSession(os.path.join(MODELS, fname),
                                    providers=["CPUExecutionProvider"])
        y = sess.run(None, {"input": x})[0]

        # iSTFT：频域补零回 n_bins → 每声道逆变换
        wave = np.zeros((2, chunk_samples), dtype=np.float32)
        for ch in range(2):
            re = y[0, ch * 2 + 0].T  # (n_frames, dim_f)
            im = y[0, ch * 2 + 1].T
            full = np.zeros((re.shape[0], n_bins), dtype=np.complex128)
            full[:, :dim_f] = re + 1j * im
            wave[ch] = istft_np(full, n_fft, hop).astype(np.float32)

        write_f32(os.path.join(FIX, f"chunk_mix_{tag}.f32"), mix)
        write_f32(os.path.join(FIX, f"chunk_in_{tag}.f32"), x)
        write_f32(os.path.join(FIX, f"chunk_out_{tag}.f32"), y)
        write_f32(os.path.join(FIX, f"chunk_wave_{tag}.f32"), wave)
        resid = float(np.abs(mix - wave).max())
        print(f"{tag}: chunk={chunk_samples} in/out {x.shape} "
              f"mix peak={np.abs(mix).max():.3f} wave peak={np.abs(wave).max():.3f} "
              f"(参考残差 max|mix-wave|={resid:.3f})")


if __name__ == "__main__":
    main()

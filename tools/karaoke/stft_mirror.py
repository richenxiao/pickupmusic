# P0 Spike A3：torch.stft / torch.istft 语义的纯 numpy 镜像。
# 目的：Kotlin 实现前先在这里把约定钉死——reflect 补边、周期 Hann、居中裁剪、
# 包络(w² 叠加)除法。镜像与 torch 对上（误差 ~1e-6 量级）后，Kotlin 照此移植。
# 用法：py -3 tools/karaoke/stft_mirror.py
import numpy as np
import torch


def hann_periodic(n_fft):
    # torch.hann_window(window_length=N, periodic=True) = 0.5 - 0.5*cos(2πn/N)
    n = np.arange(n_fft, dtype=np.float64)
    return 0.5 - 0.5 * np.cos(2.0 * np.pi * n / n_fft)


def reflect_pad(x, pad):
    # torch center=True 的默认 pad_mode='reflect'：边缘不重复。
    # [a b c d] pad=2 -> [c b | a b c d | c b]（左右各 pad）
    left = x[1:pad + 1][::-1]
    right = x[-pad - 1:-1][::-1]
    return np.concatenate([left, x, right])


def stft_np(x, n_fft, hop):
    win = hann_periodic(n_fft)
    pad = n_fft // 2
    xp = reflect_pad(x, pad)
    n_frames = 1 + (xp.size - n_fft) // hop
    frames = np.stack([xp[t * hop: t * hop + n_fft] * win for t in range(n_frames)])
    return np.fft.rfft(frames, axis=1)  # (n_frames, n_fft//2+1) complex


def istft_np(spec, n_fft, hop):
    # spec: (n_frames, n_bins) complex
    win = hann_periodic(n_fft)
    n_frames = spec.shape[0]
    frames = np.fft.irfft(spec, n=n_fft, axis=1) * win  # (n_frames, n_fft)
    out_len = (n_frames - 1) * hop + n_fft
    out = np.zeros(out_len)
    env = np.zeros(out_len)
    wsq = win * win
    for t in range(n_frames):
        s = t * hop
        out[s:s + n_fft] += frames[t]
        env[s:s + n_fft] += wsq
    # torch 的包络保护：包络过小处不除（clamp 1e-11）
    ok = env > 1e-11
    out[ok] /= env[ok]
    # center=True：裁掉两端各 n_fft//2
    pad = n_fft // 2
    return out[pad: out_len - pad]


def main():
    rng = np.random.RandomState(42)
    for n_fft, hop in [(6144, 1024), (7680, 1024)]:
        sig = rng.randn(261120).astype(np.float32)  # 一个 chunk 的长度

        # torch 参考
        xt = torch.from_numpy(sig)
        win = torch.hann_window(n_fft, periodic=True)
        spec_t = torch.stft(xt, n_fft=n_fft, hop_length=hop, window=win,
                            center=True, return_complex=True)
        rec_t = torch.istft(spec_t, n_fft=n_fft, hop_length=hop, window=win,
                            center=True)

        # numpy 镜像
        spec_n = stft_np(sig, n_fft, hop)
        rec_n = istft_np(spec_n, n_fft, hop)

        err_spec = np.abs(spec_n - spec_t.numpy().T).max()
        err_rec = np.abs(rec_n[:rec_t.shape[0]] - rec_t.numpy()).max()
        print(f"n_fft={n_fft}: frames={spec_t.shape[1]} "
              f"stft max|err|={err_spec:.3e}  istft max|err|={err_rec:.3e}")

        # 往返误差（相对满幅）
        rt = istft_np(spec_n, n_fft, hop)
        n = min(rt.size, sig.size)
        err_rt = np.abs(rt[:n] - sig[:n]).max()
        print(f"  roundtrip max|err|={err_rt:.3e} (sig peak={np.abs(sig).max():.2f})")


if __name__ == "__main__":
    main()

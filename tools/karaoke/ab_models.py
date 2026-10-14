# 客观 A/B：同一首歌，9482 与 Inst_HQ_3 的伴奏对比。
# 指标：
#  1) 两个伴奏的相似度（SNR / 相关系数）——差多少
#  2) **人声残留代理**：把 A 的伴奏与 B 估出的人声做相关——若明显相关，说明 A 的伴奏里还漏着人声
#  3) 分频段能量占比
# 用法：py -3 tools/karaoke/ab_models.py <原曲> [秒数]
import os, sys
import numpy as np, soundfile as sf
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from compare_user_export import run_ref, band_shares

def snr(ref, got):
    n = min(ref.shape[1], got.shape[1])
    e = got[:, :n] - ref[:, :n]
    return 10*np.log10((ref[:,:n]**2).sum()/max((e**2).sum(),1e-20))

def corr(a, b):
    n = min(a.shape[1], b.shape[1])
    x = a[:, :n].ravel(); y = b[:, :n].ravel()
    x = x - x.mean(); y = y - y.mean()
    d = np.sqrt((x**2).sum()*(y**2).sum())
    return float((x*y).sum()/d) if d > 0 else 0.0

src = sys.argv[1]
secs = int(sys.argv[2]) if len(sys.argv) > 2 else 60
mix, sr = sf.read(src, always_2d=True)
mix = mix.T.astype(np.float32)
if mix.shape[0] == 1: mix = np.vstack([mix, mix])

out = {}
for tag in ("inst_hq_3", "9482"):
    from make_pipeline_fixture import PARAMS
    mf, n_fft, dim_f, comp, inst = PARAMS[tag]
    acc, voc = run_ref(mf, mix, n_fft, dim_f, comp, inst, chunk_sec=secs)
    out[tag] = (acc, voc)
    print(f"  跑完 {tag}")

a_hq, v_hq = out["inst_hq_3"]
a_94, v_94 = out["9482"]

print(f"\n【1】两个伴奏的差异（前 {secs}s）")
print(f"  9482 vs HQ3 伴奏：SNR={snr(a_hq, a_94):.1f} dB · 相关系数={corr(a_hq, a_94):.3f}")

print("\n【2】人声残留代理（数值越大＝该伴奏里人声漏得越多）")
print(f"  HQ3 伴奏  与 9482 估的人声：相关={corr(a_hq, v_94):+.3f}")
print(f"  9482 伴奏 与 HQ3  估的人声：相关={corr(a_94, v_hq):+.3f}")
print("  （参照：两套人声之间的相关 = {:.3f}）".format(corr(v_hq, v_94)))

print("\n【3】分频段能量占比")
mixc = mix[:, :secs*44100]
bh = band_shares(a_hq, sr); b9 = band_shares(a_94, sr); bm = band_shares(mixc, sr)
for k in bm:
    print(f"  {k:>10}: 原曲 {bm[k]*100:5.1f}%  HQ3 {bh[k]*100:5.1f}%  9482 {b9[k]*100:5.1f}%")

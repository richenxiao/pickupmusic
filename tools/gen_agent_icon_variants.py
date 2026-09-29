"""迭代调参:生成 ic_agent.xml 候选并渲染,供肉眼核对。"""
import math, os, sys
sys.path.insert(0, os.path.dirname(__file__))

CX, CY = 11.9, 12.0

def pt(r, deg):
    a = math.radians(deg)
    return (round(CX + r * math.cos(a), 2), round(CY + r * math.sin(a), 2))

def arc(r, a0, a1):
    x0, y0 = pt(r, a0); x1, y1 = pt(r, a1)
    large = 1 if abs(a1 - a0) > 180 else 0
    sweep = 1 if a1 > a0 else 0
    return f"M {x0} {y0} A {r} {r} 0 {large} {sweep} {x1} {y1}"

def build(cfg):
    """cfg: dict with parameters; returns xml string"""
    ring_r = cfg['ring_r']; ring_w = cfg['ring_w']
    hub_r = cfg['hub_r']; hole_r = cfg['hole_r']
    orb_rx = cfg['orb_rx']; orb_ry = cfg['orb_ry']; orb_rot = cfg['orb_rot']; orb_w = cfg['orb_w']
    groove_w = cfg['groove_w']
    grooves = cfg['grooves']            # [(radius, a0, a1)]

    # orbit 旋转椭圆:参数方程 t=0 → P0, t=180 → P1
    t = math.radians(orb_rot)
    x0 = CX + orb_rx * math.cos(t); y0 = CY + orb_rx * math.sin(t)
    x1 = CX - orb_rx * math.cos(t); y1 = CY - orb_rx * math.sin(t)
    orbit_d = (f"M {round(x0,2)} {round(y0,2)} A {orb_rx} {orb_ry} {orb_rot} 0 1 {round(x1,2)} {round(y1,2)} "
               f"A {orb_rx} {orb_ry} {orb_rot} 0 1 {round(x0,2)} {round(y0,2)} Z")

    parts = []
    parts.append(f'''    <path
        android:fillColor="#00000000"
        android:strokeColor="#000000"
        android:strokeWidth="{orb_w}"
        android:strokeLineCap="round"
        android:pathData="{orbit_d}" />''')
    parts.append(f'''    <path
        android:fillColor="#00000000"
        android:strokeColor="#000000"
        android:strokeWidth="{ring_w}"
        android:strokeLineCap="round"
        android:pathData="M {CX} {CY} m -{ring_r} 0 a {ring_r} {ring_r} 0 1 0 {ring_r*2} 0 a {ring_r} {ring_r} 0 1 0 -{ring_r*2} 0" />''')
    for (gr, a0, a1) in grooves:
        parts.append(f'''    <path
        android:fillColor="#00000000"
        android:strokeColor="#000000"
        android:strokeWidth="{groove_w}"
        android:strokeLineCap="round"
        android:pathData="{arc(gr, a0, a1)}" />''')
    parts.append(f'''    <path
        android:fillColor="#000000"
        android:fillType="evenOdd"
        android:pathData="M {CX} {CY} m -{hub_r} 0 a {hub_r} {hub_r} 0 1 0 {hub_r*2} 0 a {hub_r} {hub_r} 0 1 0 -{hub_r*2} 0 Z M {CX} {CY} m -{hole_r} 0 a {hole_r} {hole_r} 0 1 0 {hole_r*2} 0 a {hole_r} {hole_r} 0 1 0 -{hole_r*2} 0 Z" />''')
    if cfg.get('star', True):
        parts.append(STAR)
    if cfg.get('note', False):
        parts.append(NOTE)

    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n    android:height="24dp"\n'
            '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
            + "\n".join(parts) + "\n</vector>\n")


STAR = '''    <path
        android:fillColor="#000000"
        android:pathData="M 21.22 4.13 C 21.27 4.23 21.31 4.33 21.34 4.44 C 21.45 4.85 21.64 5.28 22.01 5.50 C 22.03 5.51 22.06 5.52 22.08 5.54 C 22.10 5.55 22.12 5.56 22.14 5.57 C 22.39 5.70 22.64 5.77 22.91 5.83 C 22.82 5.92 22.72 5.93 22.60 5.96 C 22.19 6.08 21.81 6.26 21.59 6.64 C 21.42 6.94 21.33 7.26 21.25 7.59 C 21.24 7.59 21.24 7.59 21.18 7.56 C 21.16 7.48 21.14 7.40 21.12 7.31 C 21.02 6.90 20.85 6.48 20.49 6.24 C 20.46 6.23 20.43 6.21 20.40 6.19 C 20.38 6.18 20.36 6.17 20.34 6.15 C 20.05 5.99 19.69 5.92 19.37 5.86 C 19.38 5.84 19.40 5.81 19.41 5.79 C 19.48 5.77 19.55 5.76 19.63 5.76 C 20.06 5.70 20.47 5.54 20.76 5.20 C 20.99 4.89 21.10 4.49 21.22 4.13 Z" />'''

NOTE = '''    <path
        android:fillColor="#000000"
        android:pathData="M 22.32 14.75 C 22.33 15.01 22.33 15.27 22.33 15.54 C 22.33 15.66 22.33 15.78 22.34 15.91 C 22.34 16.03 22.34 16.14 22.34 16.26 C 22.34 16.31 22.34 16.35 22.34 16.40 C 22.35 16.67 22.33 16.89 22.14 17.11 C 21.99 17.23 21.81 17.26 21.62 17.25 C 21.50 17.22 21.42 17.16 21.33 17.08 C 21.25 16.92 21.23 16.76 21.28 16.58 C 21.35 16.41 21.45 16.29 21.62 16.22 C 21.81 16.16 21.90 16.19 22.10 16.26 C 22.10 15.95 22.10 15.65 22.10 15.34 C 21.96 15.40 21.81 15.47 21.67 15.54 C 21.62 15.56 21.57 15.58 21.52 15.60 C 21.45 15.64 21.38 15.67 21.31 15.70 C 21.30 15.71 21.30 15.71 21.24 15.73 C 21.22 15.74 21.20 15.75 21.18 15.76 C 21.16 15.77 21.14 15.78 21.13 15.79 C 21.06 15.82 20.99 15.85 20.92 15.89 C 20.92 15.91 20.92 15.93 20.92 15.95 C 20.92 16.14 20.92 16.33 20.91 16.52 C 20.91 16.62 20.91 16.72 20.91 16.82 C 20.91 16.93 20.90 17.04 20.90 17.16 C 20.90 17.19 20.90 17.23 20.90 17.26 C 20.90 17.50 20.87 17.73 20.71 17.92 C 20.69 17.94 20.67 17.95 20.64 17.97 C 20.62 17.99 20.60 18.00 20.58 18.02 C 20.41 18.13 20.23 18.12 20.04 18.10 C 19.90 18.05 19.82 17.97 19.75 17.85 C 19.68 17.69 19.67 17.53 19.73 17.37 C 19.83 17.18 19.95 17.06 20.14 16.98 C 20.30 16.94 20.48 16.96 20.63 17.03 C 20.63 16.55 20.63 16.06 20.63 15.56 C 21.80 14.97 21.80 14.97 21.88 14.93 C 21.93 14.91 21.98 14.88 22.04 14.85 C 22.05 14.84 22.05 14.84 22.12 14.81 C 22.14 14.80 22.16 14.79 22.19 14.77 C 22.25 14.75 22.25 14.75 22.32 14.75 Z" />'''

VARIANTS = {
    # v7: 减法——外环+两组各一条纹路+轴盘+轨道+星,线宽全部压低
    'v7': dict(ring_r=7.0, ring_w=2.0, hub_r=2.3, hole_r=0.7,
               orb_rx=9.6, orb_ry=6.3, orb_rot=-18, orb_w=1.8, groove_w=1.3,
               grooves=[(5.2, -160, -60), (5.2, 30, 120)], star=True, note=False),
    # v8: 加回第二条纹路(更窄角度)与乐符,看是否还糊
    'v8': dict(ring_r=7.0, ring_w=2.0, hub_r=2.3, hole_r=0.7,
               orb_rx=9.6, orb_ry=6.3, orb_rot=-18, orb_w=1.8, groove_w=1.2,
               grooves=[(5.3, -155, -65), (3.9, -145, -75), (5.3, 35, 115), (3.9, 45, 105)],
               star=True, note=False),
    # v9: 只保留上左两条纹路 + 星(最克制)
    'v9': dict(ring_r=7.1, ring_w=2.0, hub_r=2.2, hole_r=0.7,
               orb_rx=9.7, orb_ry=6.3, orb_rot=-18, orb_w=1.8, groove_w=1.3,
               grooves=[(5.3, -155, -65), (3.9, -145, -75)], star=True, note=False),
}

if __name__ == '__main__':
    out_dir = r'E:\tmp'
    xml_dir = os.path.join(out_dir, 'cand')
    os.makedirs(xml_dir, exist_ok=True)
    for name, cfg in VARIANTS.items():
        xml = build(cfg)
        p = os.path.join(xml_dir, f'{name}.xml')
        open(p, 'w', encoding='utf-8').write(xml)
        print('wrote', p)

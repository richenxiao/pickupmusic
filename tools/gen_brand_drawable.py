#!/usr/bin/env python3
"""从 docs/images/agent-logo 的权威 SVG 源文件精确生成 Android drawable。
不画像、不简化——原 path data 一字不动，只调 viewBox / 包壳 / 分层。"""
import re, os, sys

ROOT = r"E:\Software\AndroidPhone\PickUpMusic"
SRC = ROOT + r"\docs\images\agent-logo"
DST = ROOT + r"\app\src\main\res\drawable"

def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

def parse_paths(svg):
    """提取 path -> [(d, fill)]"""
    out = []
    for m in re.finditer(r'<path\s+([^>]*?)/?>', svg, re.DOTALL):
        attrs = m.group(1)
        d = re.search(r'd="([^"]+)"', attrs)
        if not d:
            continue
        fill = re.search(r'fill="([^"]+)"', attrs)
        out.append((d.group(1), fill.group(1) if fill else "#FAA35F"))
    return out

def path_bbox(d):
    """单个 path 的数值包围盒（SVG 全是绝对 M/C/L，无弧线）"""
    nums = [float(n) for n in re.findall(r'-?\d+\.?\d*', d)]
    xs = nums[0::2]; ys = nums[1::2]
    if not xs: return None
    return min(xs), min(ys), max(xs), max(ys)

def fmt(v):
    s = f"{v:.2f}"
    return s.rstrip('0').rstrip('.')

# ── 读源 ──────────────────────────────────────────────────────────
brand_src = read(os.path.join(SRC, "agent_brand.svg"))
icon_src = read(os.path.join(SRC, "agent_icon.svg"))

paths_color = parse_paths(brand_src)
paths_icon = parse_paths(icon_src)

print(f"agent_brand.svg: {len(paths_color)} path")
for i, (d, fill) in enumerate(paths_color):
    bb = path_bbox(d)
    if bb:
        x0, y0, x1, y1 = bb
        print(f"  [{i}] fill={fill}  bbox x[{fmt(x0)},{fmt(x1)}] y[{fmt(y0)},{fmt(y1)}]  W={fmt(x1-x0)} H={fmt(y1-y0)}")

print(f"\nagent_icon.svg: {len(paths_icon)} path")
for i, (d, fill) in enumerate(paths_icon):
    bb = path_bbox(d)
    if bb:
        x0, y0, x1, y1 = bb
        print(f"  [{i}] fill={fill}  bbox x[{fmt(x0)},{fmt(x1)}] y[{fmt(y0)},{fmt(y1)}]  W={fmt(x1-x0)} H={fmt(y1-y0)}")

# ── 1) 彩色标：agent_brand.xml 双变体 ─────────────────────────────
# 内容边界
all_x = []; all_y = []
for d, _ in paths_color:
    bb = path_bbox(d)
    if bb:
        all_x += [bb[0], bb[2]]; all_y += [bb[1], bb[3]]
bx0, by0 = min(all_x), min(all_y)
bx1, by1 = max(all_x), max(all_y)
bw, bh = bx1 - bx0, by1 - by0

# 等比裁剪：方形 viewBox
side = max(bw, bh) * 1.07
cx, cy = (bx0 + bx1) / 2, (by0 + by1) / 2
vx0, vy0 = cx - side/2, cy - side/2
print(f"\n裁剪 viewBox: {fmt(vx0)} {fmt(vy0)} {fmt(side)} {fmt(side)}")

# 盘面圆形 path（代替原稿"镂空背景"）
DISC = (258.0, 243.0, 83.0)
def circle_path(cx, cy, r):
    return (f"M {fmt(cx)} {fmt(cy)} m -{fmt(r)} 0 "
            f"a {fmt(r)} {fmt(r)} 0 1 1 {fmt(2*r)} 0 "
            f"a {fmt(r)} {fmt(r)} 0 1 1 -{fmt(2*r)} 0")

def brand_xml(light):
    disc_color = "#E6D9BE" if light else "#1A1A1E"
    title = "浅色米白盘" if light else "深色黑胶盘"
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f'<!-- 拾音音乐 · Agent Brand Logo · {title}',
        f'   源:docs/images/agent-logo/agent_brand.svg(等比裁剪 viewBox 0 0 512 512 -> {fmt(vx0)} {fmt(vy0)} {fmt(side)} {fmt(side)})',
        f'   几何:所有 path 原样保留;底层增一枚 {disc_color} 实心圆盘面(代替镂空背景) -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="88dp"',
        '    android:height="88dp"',
        f'    android:viewportWidth="{fmt(side)}"',
        f'    android:viewportHeight="{fmt(side)}">',
        f'  <path android:fillColor="{disc_color}" android:pathData="{circle_path(*DISC)}"/>',
    ]
    for d, fill in paths_color:
        lines.append(f'  <path android:fillColor="{fill}" android:pathData="{d}"/>')
    lines.append('</vector>')
    return "\n".join(lines)

with open(os.path.join(DST, "agent_brand.xml"), "w", encoding="utf-8") as f:
    f.write(brand_xml(light=True))
with open(os.path.join(DST, "agent_brand_dark.xml"), "w", encoding="utf-8") as f:
    f.write(brand_xml(light=False))
print("-> agent_brand.xml(浅色米白)/agent_brand_dark.xml(深色黑胶)")

# ── 2) 边栏单色：ic_agent.xml ──────────────────────────────────────
# 源文件已是 24×24、fill=#000、stroke=none(纯填充)
# 原样移植，一个像素都不改
lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!-- 拾音音乐 · 拾光Agent 单色图标(侧边栏用,接受 tint)',
    '   源:docs/images/agent-logo/agent_icon.svg(作者手画;path 数据原样保留,不改一点) -->',
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
    '    android:width="24dp" android:height="24dp"',
    '    android:viewportWidth="24" android:viewportHeight="24">',
]
for d, _ in paths_icon:
    lines.append(f'  <path android:fillColor="#000000" android:pathData="{d}"/>')
lines.append('</vector>')
with open(os.path.join(DST, "ic_agent.xml"), "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("-> ic_agent.xml")

# ── 3) 动画 AgentLogoThinking：分层复刻 ───────────────────────────
# HTML 原始四层:Record = 底层 disc + 轨道环状路径 + 3条内线 + 中心圆
# 从 7 个 path 里区分:
#   Record 那堆 = 唱片纹路 + 中心 + 外围轨道(都随唱片浮动)
#   Star       = path index 0(x max ~379, y min ~156)——右上星
#   Note       = path index 5(y min ~274,y 最大 ~312)——右下音符
# 轨道 = path index 1(有 ~380 右延,横跨唱片外围,是外环)
# 3 条纹路 = path 2/3/4(都收在唱片内部)
# 中心圆 = path 4 里嵌的 M 250.44...(复用 path 4 内含两子例)
# 直观可见:paths[1] = 轨道(大,横跨),paths[2-4] = 唱片内纹/中心,paths[0]/[5] = 星/音符

def find_bbox(paths, idx):
    return path_bbox(paths[idx][0])

print("\n按 HTML 分层:")
for name, idx in [("轨道",1),("纹路1",2),("纹路2",3),("中心圆+纹路3",4),("星",0),("音符",5)]:
    bb = find_bbox(paths_color, idx)
    if bb:
        print(f"  {name}[path {idx}]: bbox x[{fmt(bb[0])},{fmt(bb[2])}] y[{fmt(bb[1])},{fmt(bb[3])}]")

print("\n生成完毕。")

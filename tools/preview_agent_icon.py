"""渲染 ic_agent.xml 成多尺寸预览图,供视觉自查。"""
import re, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from render_vector_preview import parse_path, draw_icon
from PIL import Image

XML = r'E:\Software\AndroidPhone\PickUpMusic\app\src\main\res\drawable\ic_agent.xml'
OUT_DIR = r'E:\tmp'
BG = (246, 240, 228)
FG = (90, 90, 95)

src = open(XML, encoding='utf-8').read()
paths = re.findall(r'<path\b(.*?)/>', src, re.S)

elements = []   # (subpaths, kind, width)
for body in paths:
    d = re.search(r'android:pathData="([^"]+)"', body).group(1)
    stroke = re.search(r'android:strokeColor="([^"]+)"', body)
    fill = re.search(r'android:fillColor="([^"]+)"', body)
    sw = re.search(r'android:strokeWidth="([\d.]+)"', body)
    fill_type = re.search(r'android:fillType="(\w+)"', body)
    subs = parse_path(d)
    if stroke and stroke.group(1) != '#00000000':
        elements.append((subs, 'stroke', float(sw.group(1)) if sw else 1.0))
    elif fill and fill.group(1) != '#00000000':
        if fill_type and fill_type.group(1) == 'evenOdd' and len(subs) > 1:
            # 外圈填充 + 内圈用背景色"打孔"
            elements.append(([subs[0]], 'fill', 0))
            elements.append(([subs[1]], 'hole', 0))
        else:
            elements.append((subs, 'fill', 0))

palette = []
for name, size in [('zoom', 240), ('x3', 72), ('x2', 48), ('x1', 24)]:
    path = os.path.join(OUT_DIR, f'icon_{name}.png')
    draw_icon(elements, path, size, BG, FG)
    palette.append((name, path))
    print('wrote', path, size)

# 打孔元素改用背景色重画
def draw_with_holes(out_path, size_px):
    from PIL import ImageDraw
    SS = 16
    W = size_px * SS
    scale = W / 24.0
    img = Image.new('RGB', (W, W), BG)
    dr = ImageDraw.Draw(img)
    for subs, kind, width in elements:
        if kind == 'stroke':
            w = max(1, round(width * scale))
            for pts, closed in subs:
                p = [(x * scale, y * scale) for x, y in pts]
                if closed:
                    p = p + [p[0]]
                if len(p) >= 2:
                    dr.line(p, fill=FG, width=w, joint='curve')
        elif kind == 'fill':
            for pts, closed in subs:
                if len(pts) < 3:
                    continue
                dr.polygon([(x * scale, y * scale) for x, y in pts], fill=FG)
        elif kind == 'hole':
            for pts, closed in subs:
                if len(pts) < 3:
                    continue
                dr.polygon([(x * scale, y * scale) for x, y in pts], fill=BG)
    img = img.resize((size_px, size_px), Image.LANCZOS)
    img.save(out_path)
    return out_path

for name, size in [('zoom', 240), ('x3', 72), ('x2', 48), ('x1', 24)]:
    draw_with_holes(os.path.join(OUT_DIR, f'icon_{name}.png'), size)

# 拼一张对照图:左=大图,右=真机尺寸(3x)
big = Image.open(os.path.join(OUT_DIR, 'icon_zoom.png'))
small = Image.open(os.path.join(OUT_DIR, 'icon_x3.png'))
strip = Image.new('RGB', (big.width + small.width + 30, max(big.height, small.height)), BG)
strip.paste(big, (0, 0))
strip.paste(small, (big.width + 30, 0))
strip.save(os.path.join(OUT_DIR, 'icon_compare.png'))
print('wrote', os.path.join(OUT_DIR, 'icon_compare.png'))

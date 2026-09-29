"""把候选 XML 渲染成对比图(左:放大;右:真机 3x 尺寸),供肉眼挑。"""
import os, sys, glob, re
sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image, ImageDraw
from preview_agent_icon import draw_with_holes  # reuse? 不同模块,重写一个轻量版

# 复用 render_vector_preview 的解析
from render_vector_preview import parse_path

BG = (246, 240, 228)
FG = (90, 90, 95)
SS = 16


def load_elements(xml_path):
    src = open(xml_path, encoding='utf-8').read()
    els = []
    for body in re.findall(r'<path\b(.*?)/>', src, re.S):
        d = re.search(r'android:pathData="([^"]+)"', body).group(1)
        stroke = re.search(r'android:strokeColor="([^"]+)"', body)
        fill = re.search(r'android:fillColor="([^"]+)"', body)
        sw = re.search(r'android:strokeWidth="([\d.]+)"', body)
        ft = re.search(r'android:fillType="(\w+)"', body)
        subs = parse_path(d)
        if stroke and stroke.group(1) != '#00000000':
            els.append((subs, 'stroke', float(sw.group(1)) if sw else 1.0))
        elif fill and fill.group(1) != '#00000000':
            if ft and ft.group(1) == 'evenOdd' and len(subs) > 1:
                els.append(([subs[0]], 'fill', 0))
                els.append(([subs[1]], 'hole', 0))
            else:
                els.append((subs, 'fill', 0))
    return els


def render(els, size_px):
    W = size_px * SS
    s = W / 24.0
    img = Image.new('RGB', (W, W), BG)
    dr = ImageDraw.Draw(img)
    for subs, kind, width in els:
        if kind == 'stroke':
            w = max(1, round(width * s))
            for pts, closed in subs:
                if len(pts) < 2:
                    continue
                p = [(x * s, y * s) for x, y in pts]
                if closed:
                    p = p + [p[0]]
                dr.line(p, fill=FG, width=w, joint='curve')
        elif kind == 'fill':
            for pts, closed in subs:
                if len(pts) >= 3:
                    dr.polygon([(x * s, y * s) for x, y in pts], fill=FG)
        elif kind == 'hole':
            for pts, closed in subs:
                if len(pts) >= 3:
                    dr.polygon([(x * s, y * s) for x, y in pts], fill=BG)
    return img.resize((size_px, size_px), Image.LANCZOS)


if __name__ == '__main__':
    cand_dir = r'E:\tmp\cand'
    names = [os.path.splitext(os.path.basename(p))[0] for p in sorted(glob.glob(os.path.join(cand_dir, '*.xml')))]
    ZOOM, REAL = 200, 72
    cols = len(names)
    strip = Image.new('RGB', (ZOOM * cols + 20 * (cols - 1), ZOOM + REAL + 30), BG)
    dd = ImageDraw.Draw(strip)
    x = 0
    for n in names:
        els = load_elements(os.path.join(cand_dir, f'{n}.xml'))
        strip.paste(render(els, ZOOM), (x, 20))
        strip.paste(render(els, REAL), (x + (ZOOM - REAL) // 2, ZOOM + 25))
        dd.text((x + 4, 4), n, fill=(30, 30, 30))
        x += ZOOM + 20
    out = r'E:\tmp\icon_variants.png'
    strip.save(out)
    print('wrote', out)

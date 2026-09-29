"""渲染作者定稿 agent_icon.svg(填充式)与候选对比,判断哪种在 20-24dp 可读。"""
import os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
from PIL import Image, ImageDraw
from render_vector_preview import parse_path

BG = (246, 240, 228)

def load_svg_fills(path, dilate=0.0):
    """作者定稿:纯填充 path。dilate>0 = 同时描同色边(等效加粗)。"""
    src = open(path, encoding='utf-8').read()
    ds = re.findall(r'<path\s+d="([^"]+)"', src)
    els = []
    for d in ds:
        els.append((parse_path(d), 'fill', 0))
        if dilate > 0:
            els.append((parse_path(d), 'stroke', dilate))
    return els

def load_cand(xml_path, fg):
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
                els.append(([subs[0]], 'fill', 0)); els.append(([subs[1]], 'hole', 0))
            else:
                els.append((subs, 'fill', 0))
    return els

SS = 16
def render(els, size_px, fg):
    W = size_px * SS; s = W / 24.0
    img = Image.new('RGB', (W, W), BG); dr = ImageDraw.Draw(img)
    for subs, kind, width in els:
        if kind == 'stroke':
            w = max(1, round(width * s))
            for pts, closed in subs:
                if len(pts) < 2: continue
                p = [(x*s, y*s) for x, y in pts]
                if closed: p = p + [p[0]]
                dr.line(p, fill=fg, width=w, joint='curve')
        elif kind == 'fill':
            for pts, closed in subs:
                if len(pts) >= 3: dr.polygon([(x*s, y*s) for x, y in pts], fill=fg)
        elif kind == 'hole':
            for pts, closed in subs:
                if len(pts) >= 3: dr.polygon([(x*s, y*s) for x, y in pts], fill=BG)
    return img.resize((size_px, size_px), Image.LANCZOS)

if __name__ == '__main__':
    SRC = r'E:\Software\AndroidPhone\PickUpMusic\docs\images\agent-logo\agent_icon.svg'
    rows = [
        ('author 原稿', load_svg_fills(SRC, 0.0)),
        ('author + 0.5 加粗', load_svg_fills(SRC, 0.5)),
        ('author + 0.9 加粗', load_svg_fills(SRC, 0.9)),
    ]
    ZOOM, MID, REAL = 170, 96, 60    # 60px ≈ 20dp@3x,侧边栏实际大小
    W = ZOOM + MID + REAL + 40
    H = (ZOOM + 22) * len(rows)
    strip = Image.new('RGB', (W, H), BG); dd = ImageDraw.Draw(strip)
    y = 0
    for name, els in rows:
        dd.text((4, y + 4), name, fill=(20,20,20))
        strip.paste(render(els, ZOOM, (90,90,95)), (0, y + 20))
        strip.paste(render(els, MID, (90,90,95)), (ZOOM + 20, y + 20))
        strip.paste(render(els, REAL, (90,90,95)), (ZOOM + MID + 40, y + 20))
        y += ZOOM + 22
    out = r'E:\tmp\icon_author_vs_v7.png'
    strip.save(out)
    print('wrote', out)

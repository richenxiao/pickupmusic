"""把 Android vector drawable 的 pathData 光栅化成 PNG —— 供侧边栏图标视觉自查。
支持 M/m L/l C/c A/a Z 与 stroke(圆头)/fill(fillType=evenOdd)。
"""
import math, re, sys
from PIL import Image, ImageDraw

SS = 16  # 超采样倍率


def tokenize(d):
    tok = re.findall(r'[MmLlCcAaZzHhVv]|-?\d*\.?\d+(?:e-?\d+)?', d)
    return tok


def sample_cubic(p0, p1, p2, p3, n=24):
    out = []
    for i in range(1, n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
        y = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
        out.append((x, y))
    return out


def arc_to_points(p0, rx, ry, rot_deg, large, sweep, p1, n=48):
    """SVG 端点式圆弧 → 采样点(SVG 规范 F.6.5)。"""
    if p0 == p1:
        return []
    phi = math.radians(rot_deg)
    dx2, dy2 = (p0[0] - p1[0]) / 2.0, (p0[1] - p1[1]) / 2.0
    x1p = math.cos(phi) * dx2 + math.sin(phi) * dy2
    y1p = -math.sin(phi) * dx2 + math.cos(phi) * dy2
    rx, ry = abs(rx), abs(ry)
    lam = x1p**2 / rx**2 + y1p**2 / ry**2
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx**2 * ry**2 - rx**2 * y1p**2 - ry**2 * x1p**2
    den = rx**2 * y1p**2 + ry**2 * x1p**2
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        coef = -coef
    cxp = coef * rx * y1p / ry
    cyp = -coef * ry * x1p / rx
    cx = math.cos(phi) * cxp - math.sin(phi) * cyp + (p0[0] + p1[0]) / 2.0
    cy = math.sin(phi) * cxp + math.cos(phi) * cyp + (p0[1] + p1[1]) / 2.0

    def ang(ux, uy, vx, vy):
        d = (ux * vx + uy * vy) / (math.hypot(ux, uy) * math.hypot(vx, vy))
        d = max(-1.0, min(1.0, d))
        a = math.acos(d)
        return -a if (ux * vy - uy * vx) < 0 else a

    th1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dth > 0:
        dth -= 2 * math.pi
    elif sweep and dth < 0:
        dth += 2 * math.pi
    pts = []
    for i in range(1, n + 1):
        t = th1 + dth * i / n
        x = cx + rx * math.cos(t) * math.cos(phi) - ry * math.sin(t) * math.sin(phi)
        y = cy + rx * math.cos(t) * math.sin(phi) + ry * math.sin(t) * math.cos(phi)
        pts.append((x, y))
    return pts


def parse_path(d):
    """→ [(subpath_points, closed), ...]"""
    tok = tokenize(d)
    i = 0
    cur = (0.0, 0.0)
    start = (0.0, 0.0)
    subs = []
    pts = []
    closed = False
    cmd = None

    def num():
        nonlocal i
        v = float(tok[i]); i += 1
        return v

    while i < len(tok):
        t = tok[i]
        if re.match(r'[A-Za-z]', t):
            cmd = t; i += 1
            if cmd in 'Zz':
                if pts:
                    subs.append((pts, True))
                pts = []
                cur = start
                continue
        if cmd in 'Mm':
            x, y = num(), num()
            if cmd == 'm':
                x, y = cur[0] + x, cur[1] + y
            if pts:
                subs.append((pts, False))
            pts = [(x, y)]
            cur = start = (x, y)
            cmd = 'L' if cmd == 'M' else 'l'
        elif cmd in 'Ll':
            x, y = num(), num()
            if cmd == 'l':
                x, y = cur[0] + x, cur[1] + y
            pts.append((x, y)); cur = (x, y)
        elif cmd in 'Cc':
            x1, y1, x2, y2, x, y = (num() for _ in range(6))
            if cmd == 'c':
                x1 += cur[0]; x2 += cur[0]; x += cur[0]
                y1 += cur[1]; y2 += cur[1]; y += cur[1]
            pts += sample_cubic(cur, (x1, y1), (x2, y2), (x, y))
            cur = (x, y)
        elif cmd in 'Aa':
            rx, ry, rot, large, sweep, x, y = (num() for _ in range(7))
            if cmd == 'a':
                x, y = cur[0] + x, cur[1] + y
            pts += arc_to_points(cur, rx, ry, rot, int(large), int(sweep), (x, y))
            cur = (x, y)
        else:
            i += 1
    if pts:
        subs.append((pts, closed))
    return subs


def draw_icon(elements, out_path, size_px, bg=(246, 240, 228), fg=(90, 90, 95)):
    """elements: [(subpaths, kind, width)] kind in {'stroke','fill'}"""
    W = size_px * SS
    scale = W / 24.0
    img = Image.new('RGB', (W, W), bg)
    dr = ImageDraw.Draw(img)

    def sc(pts):
        return [(x * scale, y * scale) for (x, y) in pts]

    # 先画描边,再画填充(与 drawable 顺序无关,同色)
    for subpaths, kind, width in elements:
        if kind != 'stroke':
            continue
        w = max(1, round(width * scale))
        for pts, closed in subpaths:
            p = sc(pts)
            if closed:
                p = p + [p[0]]
            if len(p) >= 2:
                dr.line(p, fill=fg, width=w, joint='curve')
            r = w / 2.0
            for (x, y) in (p if len(p) < 400 else p[::2]):
                dr.ellipse([x - r, y - r, x + r, y + r], fill=fg)
    for subpaths, kind, width in elements:
        if kind != 'fill':
            continue
        for pts, closed in subpaths:
            if len(pts) < 3:
                continue
            dr.polygon(sc(pts), fill=fg)

    img = img.resize((size_px, size_px), Image.LANCZOS)
    img.save(out_path)
    return out_path


if __name__ == '__main__':
    print('module ok')

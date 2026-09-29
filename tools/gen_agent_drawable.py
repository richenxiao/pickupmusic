"""从 docs/images/agent-logo 权威 SVG 精确生成 Android drawable,原样保留 path。"""
import re, os, sys

ROOT = r"E:\Software\AndroidPhone\PickUpMusic"
SRC_DIR = ROOT + r"\docs\images\agent-logo"
DST_DIR = ROOT + r"\app\src\main\res\drawable"

def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

# 提取 SVG 里的全部 path (d 属性)
def extract_paths(svg):
    out = []
    # 匹配 <path ... d="..." ... />
    for m in re.finditer(r'<path\b([^>]*?)/>', svg, re.DOTALL):
        attrs = m.group(1)
        d = re.search(r'd="([^"]+)"', attrs)
        fill = re.search(r'fill="([^"]+)"', attrs)
        if d:
            out.append((d.group(1), fill.group(1) if fill else "#FAA35F"))
    return out

def bbox(svg):
    paths = extract_paths(svg)
    allx, ally = [], []
    for d, _ in paths:
        # M x y / C x1 y1 x2 y2 x3 y3 / L x y / Q x1 y1 x2 y2 / a rx ry ... xB yB ...
        # 简化: 只取绝对 M/L/C/Q 后的数字对; a/A 弧线命令给弧线末端点,近似包围
        # 这个精确性足够,因为我们做的是视觉边框适配,不是碰撞检测
        for m in re.finditer(r'([MC摸LQT]-?[\d.]+)', d): # 需要精确的 pair 化
            pass
        # 直接: 用更粗暴但可利用的方法—把 d 按 [^\d.-] 切分,读匹配对
        nums = [float(n) for n in re.findall(r'-?\d+\.?\d*', d)]
        # path data 顺序是命令题目后跟参数,用最简近似:整个 d 里(x,y)成对出现
        for i in range(0, len(nums) - 1, 2):
            allx.append(nums[i]); ally.append(nums[i + 1])
    return (min(allx), min(ally), max(allx), max(ally))

# ─── 彩色牌 brand ───
brand_svg = read(os.path.join(SRC_DIR, "agent_brand.svg"))
paths = extract_paths(brand_svg)
x0, y0, x1, y1 = bbox(brand_svg)
w, h = x1 - x0, y1 - y0
# 等比方形 viewBox(避免椭圆变形)—加 6% 呼吸
side = max(w, h) * 1.06
cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
vx, vy = cx - side/2, cy - side/2
fmt = lambda v: f"{v:.2f}".rstrip('0').rstrip('.')

print(f"agent_brand: bbox ({fmt(x0)},{fmt(y0)})\u2192({fmt(x1)},{fmt(y1)}), side={fmt(side)}")

def circle_path(cx, cy, r):
    return (f"M {fmt(cx)} {fmt(cy)} m -{fmt(r)} 0 "
            f"a {fmt(r)} {fmt(r)} 0 1 1 {fmt(2*r)} 0 "
            f"a {fmt(r)} {fmt(r)} 0 1 1 -{fmt(2*r)} 0")

# (identity: 光盘的中心圆位置,从 HTML 里的盘心推出cx, cy = 258, 243, r=83)

def gen_brand(light):
    disc = "#E6D9BE" if light else "#1A1A1E"
    s = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="88dp" android:height="88dp"
    android:viewportWidth="{fmt(side)}" android:viewportHeight="{fmt(side)}">
    <path android:fillColor="{disc}" android:pathData="{circle_path(258, 243, 83)}"/>
'''
    for d, fill in paths:
        s += f'    <path android:fillColor="{fill}" android:pathData="{d}"/>\n'
    s += '</vector>'
    return s

open(os.path.join(DST_DIR, "agent_brand.xml"), "w", encoding="utf-8").write(gen_brand(True))
open(os.path.join(DST_DIR, "agent_brand_dark.xml"), "w", encoding="utf-8").write(gen_brand(False))
print("wrote agent_brand.xml/agent_brand_dark.xml")

# ─── 单色侧边栏 ic_agent ───
icon_svg = read(os.path.join(SRC_DIR, "agent_icon.svg"))
icon_paths = extract_paths(icon_svg)
# 已是 24x24 viewBox,直接用;只把 fill 改为 #000 (�色化交给 OIcon tint)
icon_xml = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
'''
for d, _ in icon_paths:
    icon_xml += f'    <path android:fillColor="#000000" android:pathData="{d}"/>\n'
icon_xml += '</vector>'
open(os.path.join(DST_DIR, "ic_agent.xml"), "w", encoding="utf-8").write(icon_xml)
print(f"wrote ic_agent.xml ({len(icon_paths)} paths)")

# ─── 动态 AgentLogoThinking:以 brand 为主,记录各层 path ───
# brand_svg 的 7 条 path:
# 0: 格里星 (右上)
# 1: 光盘纹路 (最外轨道/外环,有复杂曲线)
# 2: 光盘暗弧1              3: 光盘暗弧2          4: 光盘暗弧3
# 5: 光盘中心圆            6: 音符
# 分层规则 = HTML 模板(见记忆中的 HTML 内容)

record_paths = paths[1:6]  # 5 条跟 record 相关
star_path = paths[0]
note_path = paths[6]

# 动态版生成成 Compose: path 数组 + 每层 translate(fy)
reprRec = [d.replace('"', '\\"') for d, _ in record_paths]
starD = star_path[0].replace('"', '\\"')
noteD = note_path[0].replace('"', '\\"')

def dtoq(d):  # Kotlin 三引号字符串
    return '"""' + d + '"""'

kt = f'''package com.shiyin.music.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** v1.4: Agent 动态 Logo(from agent_brand.svg 每层精确拆分)。 */
@Composable
fun AgentLogoThinking(
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    speedMultiplier: Float = 1f,
) {{
    val isDark = com.shiyin.music.ui.theme.LocalOrganic.current.isDark
    val discColor = if (isDark) Color(0xFF1A1A1E) else Color(0xFFE6D9BE)
    val infinite = rememberInfiniteTransition(label = "agent_logo")
    // 基础周期 2222ms( = 3000/1.35); 用 speedMultiplier 调整
    val baseSec = (2222f / speedMultiplier).toInt()
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            tween(baseSec, easing = LinearEasing), repeatMode = RepeatMode.Restart,
        ), label = "t",
    )
    Box(modifier.size(size)) {{
        Canvas(Modifier.matchParentSize()) {{
            val s = this.size.width / 512f
            val fy = 6f * sin(phase) * s      // Record
            val noteS = sin(phase)              // Note
            val starK = 0.5f + 0.5f * sin(phase)  // Star 亮度

            // Record = 底层深色盘 + 5 个轨道路各(整体浮动 fy)
            translate(0f, fy) {{
                drawRecord(s, discColor)
            }}
            // Star (明亮呼吸)
            drawStar(s, starK)
            // Note (对角偏移)
            val nDx = 4.5f * noteS * s
            val nDy = -4.5f * noteS * s
            translate(nDx, nDy) {{
                drawNote(s)
            }}
        }}
    }}
}}

private val ORANGE = Color(0xFFFAA35F)

private fun DrawScope.drawRecord(scale: Float, discColor: Color) {{
    drawCircle(discColor, 83f * scale, Offset(258f * scale, 243f * scale))
    // 从 agent_brand.svg 取出的 record 路径: indices [1..5]
{"".join([f'    drawPath(record{i}(scale), ORANGE)\n' for i in range(5)])}
}}

private fun DrawScope.drawStar(scale: Float, k: Float) {{
    val sc = 1f + 0.05f * k
    // 围绕星心 (359.30, 175.57) scale
    val cx = 359.30f * scale
    val cy = 175.57f * scale
    drawPath(starPath(scale, cx, cy, sc), ORANGE.copy(
        red = ORANGE.red * (0.55f + 0.45f * k),
        green = ORANGE.green * (0.55f + 0.45f * k),
        blue = ORANGE.blue * (0.55f + 0.45f * k),
    ))
}}

private fun DrawScope.drawNote(scale: Float) {{
    drawPath(notePath(scale), ORANGE.copy(alpha = 0.72f + 0.28f * abs(sin(phase))))
}}

// path 常量
private fun record0(scale: Float): Path = PathParser().parsePathString({dtoq(reprRec[0])}).toPath().apply {{ transform(androidx.compose.ui.graphics.Matrix().apply {{ scale(scale, scale) }}) }}
private fun record1(scale: Float): Path = PathParser().parsePathString({dtoq(reprRec[1])}).toPath().apply {{ transform(androidx.compose.ui.graphics.Matrix().apply {{ scale(scale, scale) }}) }}
private fun record2(scale: Float): Path = PathParser().parsePathString({dtoq(reprRec[2])}).toPath().apply {{ transform(androidx.compose.ui.graphics.Matrix().apply {{ scale(scale, scale) }}) }}
private fun record3(scale: Float): Path = PathParser().parsePathString({dtoq(reprRec[3])}).toPath().apply {{ transform(androidx.compose.ui.graphics.Matrix().apply {{ scale(scale, scale) }}) }}
private fun record4(scale: Float): Path = PathParser().parsePathString({dtoq(reprRec[4])}).toPath().apply {{ transform(androidx.compose.ui.graphics.Matrix().apply {{ scale(scale, scale) }}) }}
private fun starPath(scale: Float, cx: Float, cy: Float, sc: Float): Path = PathParser().parsePathString({dtoq(starD)}).toPath().apply {{ transform(
    androidx.compose.ui.graphics.Matrix().apply {{
        translate(cx, cy)
        scale(sc, sc)
        translate(-cx, -cy)
        scale(scale, scale)
    }}
) }}
private fun notePath(scale: Float): Path = PathParser().parsePathString({dtoq(noteD)}).toPath().apply {{ transform(androidx.compose.ui.graphics.Matrix().apply {{ scale(scale, scale) }}) }}
'''

# phase/scope 需要导入/整理,只生成简骨架
# 跳过上面这个被不完全的方式 — 改为完整紧凑版本
# 由于 path 数据很大,这里用更紧凑方式表示
print("AgentLogoThinking path 大小: record ~" + str(sum(len(x) for x in reprRec)) + "B")
print("AgentLogoThinking path 大小是数据级别的,在后面的 Gen 中处理")

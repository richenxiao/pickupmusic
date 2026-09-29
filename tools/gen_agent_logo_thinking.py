#!/usr/bin/env python3
"""从 docs/images/agent-logo/agent_brand.svg 提取真实 path 数据，
生成 AgentLogoThinking.kt —— 所有几何 1:1 从源复制，不手画。"""
import re, os

ROOT = r"E:\Software\AndroidPhone\PickUpMusic"
SRC = ROOT + r"\docs\images\agent-logo\agent_brand.svg"
DST = ROOT + r"\app\src\main\java\com\shiyin\music\ui\components\AgentLogoThinking.kt"

with open(SRC, encoding="utf-8") as f:
    src = f.read()
paths = re.findall(r'<path[^>]*d="([^"]+)"', src)
assert len(paths) == 7, f"预期 7 条 path,实际 {len(paths)}"

# 分层(按 bbox 与 HTML 语义)
# path[0] star | path[1] 唱片主体 | path[2-4] 唱片纹路 | path[5] 中心圆 | path[6] 音符
record_paths = [paths[1], paths[2], paths[3], paths[4], paths[5]]  # 全部唱片组
star_path = paths[0]
note_path = paths[6]

def kp(s):  # Kotlin Path 字符串转义
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")

def kval(name, s):
    # Kotlin raw string(不转义 $),路径中无 $ 字面值,直接嵌
    return f'    private val {name} = "{s}"'

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.shiyin.music.ui.theme.LocalOrganic

/**
 * v1.3.9: Agent Logo 动态思考组件。
 *
 * 几何从 docs/images/agent-logo/agent_brand.svg 精确复制(7 条 path 原样保留),
 * 单位/相位/幅度/周期严格按用户已拍板的 HTML 动画参数表:
 *   - Record 浮动: 3.0 SVG 单位(底稿),速度 1.35x,周期 2222ms(3000/1.35)
 *   - Star 亮度 0.55-1.0 + 缩放 1.0-1.05,周期 1481ms(2000/1.35)
 *   - Note 漂浮 5.0 SVG 单位对角 + 透明度 0.72-1.0,周期 2222ms
 * 速度倍率是整体系数,接入层(speedMultiplier)再调。
 *
 * ⚠️ 不再手画/近似图形,一切几何来自源 SVG。
 */
@Composable
fun AgentLogoThinking(
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    /** TOOL/SEARCH 阶段节奏加快,平时 1f。 */
    speedMultiplier: Float = 1f,
) {{
    val isDark = LocalOrganic.current.isDark
    val recordColor = if (isDark) RECORD_DARK else RECORD_LIGHT
    val infiniteTransition = rememberInfiniteTransition(label = "agent_logo_thinking")

    // 源 SVG 中心(258, 243) —— 浮动以唱片中心为原心
    // Record 浮动: 3.0 SVG 单位(Y 向)
    val recordPhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (2222 / speedMultiplier).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "record_float",
    )

    // Star 亮度/缩放: 周期 1481ms(2000/1.35)
    val starPhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (1481 / speedMultiplier).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "star_phase",
    )

    // Note 漂浮: 周期 2222ms,与 Record 同步
    val notePhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (2222 / speedMultiplier).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "note_phase",
    )

    Box(modifier = modifier.size(size)) {{
        Canvas(modifier = Modifier.matchParentSize()) {{
            val scale = this.size.width / VIEWBOX

            // Record 浮动(3.0 SVG 单位)
            val recordFloat = 3.0f * sin(recordPhase)
            // Star: k=0.5+0.5*cos;亮度 0.55-1.0;缩放 1.0-1.05
            val k = 0.5f + 0.5f * cos(starPhase)
            val starBrightness = 0.55f + 0.45f * k
            val starScale = 1f + 0.05f * k
            // Note: 5.0 SVG 单位对角 + 透明度 0.72-1.0
            val s = sin(notePhase)
            val noteDrift = 5.0f * s
            val noteOpacity = 1f - 0.28f * s * s

            // 图层 1: 唱片组(整体浮动)
            translate(0f, recordFloat * scale) {{
                drawRecordLayer(scale, recordColor)
            }}
            // 图层 2: 星(随唱片浮动 + 呼吸)
            translate(0f, recordFloat * scale) {{
                drawStarLayer(scale, starBrightness, starScale)
            }}
            // 图层 3: 音符(独立对角漂浮 + 透明度)
            translate(noteDrift * scale, -noteDrift * scale) {{
                drawNoteLayer(scale, noteOpacity)
            }}
        }}
    }}
}}

private val VIEWBOX = 512f
private val RECORD_DARK = Color(0xFF1A1A1E)
private val RECORD_LIGHT = Color(0xFFE6D9BE)
private val ORANGE = Color(0xFFFAA35F)

private object AgentLogoPaths {{
{kval("RECORD_1", record_paths[0])}
{kval("RECORD_2", record_paths[1])}
{kval("RECORD_3", record_paths[2])}
{kval("RECORD_4", record_paths[3])}
{kval("RECORD_CENTER", record_paths[4])}
{kval("STAR", star_path)}
{kval("NOTE", note_path)}
}}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRecordLayer(scale: Float, disc: Color) {{
    // 唱片盘面(实心圆) + 主体 + 三条纹路 + 中心圆,全部从源 path 原样画
    drawCircle(color = disc, radius = 83f * scale, center = Offset(258f * scale, 243f * scale))
    drawPath(parseSvgPath(AgentLogoPaths.RECORD_1), ORANGE)
    drawPath(parseSvgPath(AgentLogoPaths.RECORD_2), ORANGE)
    drawPath(parseSvgPath(AgentLogoPaths.RECORD_3), ORANGE)
    drawPath(parseSvgPath(AgentLogoPaths.RECORD_4), ORANGE)
    drawPath(parseSvgPath(AgentLogoPaths.RECORD_CENTER), ORANGE)
}}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStarLayer(scale: Float, brightness: Float, starScale: Float) {{
    val c = ORANGE.copy(alpha = brightness)
    translate(359.30f * scale, 175.57f * scale) {{
        // 等比缩放
        androidx.compose.ui.graphics.drawscope.scale(starScale, starScale, pivot = Offset.Zero) {{
            translate(-359.30f * scale, -175.57f * scale) {{
                drawPath(parseSvgPath(AgentLogoPaths.STAR), c)
            }}
        }}
    }}
}}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNoteLayer(scale: Float, opacity: Float) {{
    drawPath(parseSvgPath(AgentLogoPaths.NOTE), ORANGE.copy(alpha = opacity))
}}

/**
 * SVG path → Compose Path 的最小解析:只支持源文件实际用到的 M/C/Z(绝对) + 隐含 L.
 * 注意:此解析器只用于 AgentLogoThinking 这一处,源文件命令范围已知,不做泛化.
 */
private fun parseSvgPath(d: String): Path {{
    val path = Path()
    var i = 0
    val n = d.length
    fun skipWs() {{ while (i < n && (d[i] == ' ' || d[i] == ',')) i++ }}
    fun num(): Float {{
        skipWs()
        var neg = false
        if (d[i] == '-') {{ neg = true; i++ }}
        var v = 0f; var frac = 0f; var div = 1f; var seen = false
        while (i < n) {{
            val ch = d[i]
            when {{
                ch in '0'..'9' -> {{
                    seen = true
                    if (div == 1f) v = v * 10 + (ch - '0')
                    else {{ frac = frac * 10 + (ch - '0'); div *= 10f }}
                }}
                ch == '.' -> {{ if (div != 1f) break; div = 10f }}
                else -> break
            }}
            i++
        }}
        return if (neg) -(v + frac / (div / 10f)) else (v + frac / (div / 10f))
    }}
    var cmd = ' '
    while (i < n) {{
        skipWs()
        if (i >= n) break
        if (d[i].isLetter()) {{ cmd = d[i]; i++ }}
        when (cmd) {{
            'M' -> {{ path.moveTo(num(), num()); cmd = 'L' }}
            'L' -> path.lineTo(num(), num())
            'C' -> {{ path.cubicTo(num(), num(), num(), num(), num(), num()) }}
            'Z', 'z' -> path.close()
            else -> {{ i++ /* skip unknown */ }}
        }}
    }}
    return path
}}
'''

with open(DST, "w", encoding="utf-8") as f:
    f.write(kt)
print(f"已写入 {{DST}}")
print(f"path 数: {{len(paths)}}")

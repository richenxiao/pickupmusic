package com.shiyin.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一段振假名文本：surface 为显示原文，reading 为该 surface 对应的假名注音。
 * reading 为 null 表示该段无需注音（纯假名 / 标点 / 送假名）。该模型由分词器
 * （M2: Kuromoji）以"汉字段 + 送假名段"预切分形式直接产出——即每段 surface
 * 的 reading 与整段对齐，渲染时标注在 surface 上方，无需再做送假名剥除。
 *
 * startOffset：该段 surface 在原文中的起始字符偏移（V1.1 Song Override 按"出现位置"
 * 存取用，使同一 surface 在歌里多次出现、读法不同时可分别设——如「何」有的なに
 * 有的なん）。RubyText 渲染不读此字段。
 */
data class RubySegment(val surface: String, val reading: String?, val startOffset: Int = 0)

/**
 * 自绘振假名（ruby / furigana）文本组件，用于「歌词本」全屏页的日文歌词。
 *
 * 渲染策略：用 Compose TextMeasurer 对整行做正常段落排版（免费得到正确的日文
 * shaping / 自动换行 / 居中），再用 Canvas 把假名逐段画在对应汉字上方。
 *
 * 布局正确性（v1.1 修复"换行后振假名脱离正文";v1.3.x 修"开注音后断行异常"）：
 *   - 断行保护（零宽不换行符 U+2060 WORD JOINER）只插在「多字共享一个完整读音」
 *     的段内部——surface 拆两半会把整段读音撕离一半字符（「二人/ふたり」拆开 =
 *     「二=ふたり」的错误信息，违反"宁可不显示、不可显示错"）。段边界全部放开
 *     可断行：读音属于各自段内，边界断行不会拆散任何读音↔surface 对。
 *     v1.1–v1.2 曾把全部段边界也粘死，断行机会只剩无读音段内部零星几处，贪婪
 *     断行取行宽内最靠后的机会——常落在行首四五字处，"开注音后只有四五个字就
 *     换行"即由此而来（三层增强把注音覆盖率拉满后集中暴露）。
 *     原文偏移↔测量文本偏移用 offMap 互转。
 *   - 全部坐标（getBoundingBox / getLineForOffset）取自单一 baseLayout，杜绝
 *     "探测布局"与"绘制布局"因行高不同在边界字符上换行漂移。
 *
 * 宽注音处理（v1.1 废弃竖排堆叠改 shrink-to-fit；v1.3.x 两轮重做）：
 *   单字汉字对应多假名时，水平假名可能比汉字宽、居中后侵入相邻假名范围。始终
 *   水平排列在汉字正上方，绝不竖排；宽于约束则按比例缩字号（下限 [rubyShrinkMin]）。
 *   v1.3.x 第一轮改"连读组统一缩放"修同词字号参差；第二轮发现旧"逐段可用宽"
 *   模型两处盲区（单个空格分隔的相邻读音全部命中）：① 只有右向约束，后一个
 *   相邻读音可用宽直达行尾几乎不缩 → 相同读音相邻"前缩后不缩"；② 假名居中于
 *   surface，宽出部分左右各伸一半，相邻两段假名可同时伸进共享间隙互挤——
 *   surface 间距够 ≠ 假名不碰。现改为「成对假名边距约束」：同行任两段相邻注音
 *   约束 kana_i.right + margin ≤ kana_j.left（同组 margin 小=读法连续，跨组
 *   margin 大=词间透气），两侧假名宽度同乘比例 s 线性一次可解；组比例取触及
 *   该组全部约束的最小值——组内字号统一保持，相邻同读音约束对称 → 两侧同缩
 *   同放。行首/行尾越界仍由绘制时 x 轴 clamp 兜底（与旧行为一致：宁可轻微
 *   越过相邻字符上方，不为贴边强缩字号）。
 *
 * 行高：带振假名的行 lineHeight = 调用方 lineHeight(未指定时 baseVal×1.3 兜底)
 * + 单层 ruby 槽(rubySize + gap)，Bottom 对齐把 ruby 槽压在文字上方。单一 ruby
 * 槽（无堆叠），所有带振假名行行高一致、稳定；槽收尽后(p=0)与无注音行完全等高，
 * 开关切换行节奏不跳。不带振假名的行沿用调用方 lineHeight，行高紧凑。
 *
 * v1.3.x 行高过渡动画(用户设计方案)：[heightProgress] 驱动行高增量与文字下沉
 * (同一进度,腾空与下沉一个连贯动作);[kanaProgress] 驱动假名淡入/淡出与轻微
 * 下滑归位。开启方向调用方给两者相同 spec(数值恒同步=单驱动效果);关闭方向
 * kanaProgress 更快(110ms 先消失,行高 240ms 后合拢,"字先消失、空间再合拢")。
 * 两进度均不进测量 key——测量结果与进度无关,Canvas 高度/平移/透明度按帧绘制。
 */
@Composable
fun RubyText(
    text: String,
    segments: List<RubySegment>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    rubySizeRatio: Float = 0.46f,
    rubyGap: Dp = 2.dp,
    rubyShrinkMin: Float = 0.55f,
    color: Color = style.color,
    textAlign: TextAlign = TextAlign.Start,
    // v1.3.x 过渡动画双驱动值(0f..1f,默认 1f=完整态)——见类注释
    heightProgress: Float = 1f,
    kanaProgress: Float = 1f,
) {
    // 安全检查：segments 拼接必须等于 text。若不一致（produceState 竞态、
    // splitOkurigana 边界问题等），安全降级为整行无注音——绝不崩溃。
    if (segments.joinToString("") { it.surface } != text) {
        val safeSegments = listOf(RubySegment(text, null, 0))
        // 用 safeSegments 继续渲染（无注音，但显示正文）
        // 直接 fallback 到普通 Text 渲染
        androidx.compose.material3.Text(text, style = style, modifier = modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val baseVal = if (style.fontSize.isSpecified) style.fontSize.value else 16f
    val rubyVal = baseVal * rubySizeRatio
    val rubySize = rubyVal.sp
    val gapPx = with(density) { rubyGap.toPx() }
    // v1.3.x code-review: rubyStyle 的 remember 键不含 color——颜色不影响布局,
    // 假名文本的绘制色由 drawText(color=) 按帧提供(同 baseLayout 的做法);
    // style.copy 里也不带 color,避免闭包捕获调用方每帧变化的 color 造成键失效。
    val rubyStyle = remember(style, rubyVal) {
        // Trim.Both 让假名布局高度=字号本身（剥掉字体 ascent/descent 额外量），
        // 否则 kanaH≈字号×1.2 会溢出 ruby 槽顶顶到上一行。
        style.copy(
            fontSize = rubySize,
            color = androidx.compose.ui.graphics.Color.Unspecified,
            lineHeight = rubySize,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        )
    }

    BoxWithConstraints(modifier) {
        val maxW = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val hasRuby = segments.any { it.reading != null }
        // 重新测量键:输入文本 + 各段假名 + 字号 + 行高 + 槽位 + 可用宽度 + 是否带
        // 振假名。v1.3.x code-review 两项修正:
        //  ① color 不进 key——颜色不影响布局测量,而 LyricsScreen 常驻挂载后行色
        //    220ms 渐变每帧都换色,旧 key 含 color.value 会逐帧全量重测量(整段
        //    排版+逐段假名+收缩求解),低端机肉眼可见卡顿;颜色由 Canvas 绘制层
        //    drawText(color=) 按帧取,不进 remember 缓存。rubyStyle 里的 color 同
        //    步移除。
        //  ② lineHeight 进 key——baseStyle.lineHeight 从 style.lineHeight 派生后,
        //    若未来调用方同文本同字号换 lineHeight,旧 key 不变会拿回旧布局
        //    (画布高度/条带几何全错位)。
        val lineHKey = if (style.lineHeight.isSpecified) style.lineHeight.value else -1f
        val key = buildString {
            append(text); append('|')
            segments.forEach { append(it.reading ?: ""); append(',') }
            append('|'); append(baseVal); append(':'); append(rubyVal); append(':')
            append(lineHKey); append(':')
            append(gapPx); append(':')
            append(textAlign.toString()); append(':'); append(maxW); append(':'); append(hasRuby)
        }
        val measured = remember(key) {
            // 1. 各段在原文 text 中的 [start, end) 偏移 + 默认字号假名水平布局
            var off = 0
            val ranges = ArrayList<IntArray>(segments.size)
            val defaultKanaLayouts = ArrayList<TextLayoutResult?>(segments.size)
            for (seg in segments) {
                val start = off
                val end = off + seg.surface.length
                ranges.add(intArrayOf(start, end))
                defaultKanaLayouts.add(
                    if (seg.reading != null) {
                        measurer.measure(
                            seg.reading, rubyStyle,
                            constraints = Constraints(maxWidth = Int.MAX_VALUE),
                        )
                    } else null,
                )
                off = end
            }

            // 1b. 构造测量文本。断行保护（零宽不换行 U+2060 WORD JOINER）只保留在
            //     「多字共享一个完整读音」的段内部：surface 拆两半会把整段读音从
            //     一半字符上撕下来（「二人/ふたり」拆开就成了「二=ふたり」的错误
            //     信息，违反"宁可不显示、不可显示错"——用户定调：宁可断行不完全
            //     一致，不可读音显示错误）。其余位置全部放开：
            //     v1.1 起每个段前后都插 WJ 把全部段边界焊死，整行断行机会只剩
            //     "无读音段内部"零星几处，贪婪断行取行宽内最靠后的机会——常落在
            //     行首四五字处，即"开注音后只有四五个字就换行"的主根因。段边界
            //     断行不拆散任何读音↔surface 对（读音属于各自段内），v1.1"注音
            //     脱离"只由 surface 自身被拆引起，由段内 WJ 继续保证。
            //     offMap[原文offset]=测量offset。
            val WJ = '⁠'
            val measureText = StringBuilder(text.length + segments.size)
            val offMap = IntArray(text.length + 1)
            var oOrig = 0
            for (idx in segments.indices) {
                val segStart = ranges[idx][0]
                while (oOrig < segStart) {
                    offMap[oOrig] = measureText.length
                    measureText.append(text[oOrig])
                    oOrig++
                }
                val segLen = segments[idx].surface.length
                // 只有"带读音且 ≥2 字"的段才防拆；单字读音段无处可拆、无读音段
                // 保留内部断行能力（长送假名/标点串免得整段溢出）。
                val atomic = segments[idx].reading != null && segLen >= 2
                for (c in 0 until segLen) {
                    offMap[oOrig] = measureText.length
                    measureText.append(text[oOrig])
                    oOrig++
                    if (atomic && c < segLen - 1) measureText.append(WJ)
                }
            }
            while (oOrig <= text.length) {
                offMap[oOrig] = measureText.length
                if (oOrig < text.length) measureText.append(text[oOrig])
                oOrig++
            }
            val mText = measureText.toString()
            val mRanges = Array(segments.size) { i ->
                intArrayOf(offMap[ranges[i][0]], offMap[ranges[i][1]])
            }

            // 2. 单一 baseLayout（带单层 ruby 槽）。换行骨架与绘制坐标全部从此取。
            //    v1.3.x 动画重构:无槽基础行高改用调用方 lineHeight(未指定时
            //    baseVal×1.3 兜底)——p=0 收拢后的行与 Text 分支(同 36sp)
            //    逐字节一致,开关切换行节奏不跳;此前用 baseVal*1.3=33.8sp,
            //    关闭结束瞬间会 33.8→36 跳一下。
            val rubySizePx = with(density) { rubySize.toPx() }
            val singleRubySp = with(density) { (rubySizePx / density.density).sp }
            val lineGapSp = with(density) { gapPx / density.density }
            val baseLineH = if (style.lineHeight.isSpecified) style.lineHeight.value else baseVal * 1.3f
            val baseStyle = if (hasRuby) {
                style.copy(
                    color = color,
                    textAlign = textAlign,
                    lineHeight = (baseLineH + singleRubySp.value + lineGapSp).sp,
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Bottom,
                        trim = LineHeightStyle.Trim.None,
                    ),
                )
            } else {
                style.copy(color = color, textAlign = textAlign)
            }
            val baseLayout = measurer.measure(
                mText, baseStyle,
                softWrap = true,
                constraints = Constraints(maxWidth = maxW),
            )

            // 3. 成对假名边距约束的 shrink-to-fit（v1.3.x 重做：单位"段→连读组",
            //    模型"逐段可用宽→成对边距"）。两轮迭代的结论:
            //    a) 连读组:直接相邻的带读音段(分词把一个词拆成多段,心=こころ +
            //       臓=ぞう)划为一组,组内字号必须统一——词里任何一段需要收缩,
            //       词的全部注音一起缩,杜绝"一个字偏小一个正常"的参差。
            //    b) 成对约束:旧"逐段可用宽"只有右向约束,相邻读音"宴 宴"的前者
            //       可用宽=到后者 surface 左缘(单字+空格,3 假名放不下→缩),后者
            //       直达行尾(不缩)——相同读音一个缩一个不缩;且假名居中于 surface,
            //       宽出部分左右各伸一半,相邻两段假名可同时伸进共享间隙互挤
            //       (surface 间距够 ≠ 假名不碰;3 假名 ≈36sp 才伸得进 ≈13sp 的
            //       半角空格间隙,≤2 假名 ≈24sp 比单字还窄不伸——即"三个以上
            //       假名才挤"的观察)。故改为:同一行上任两段相邻注音(无论是否
            //       同组)约束 前假名右缘 + margin ≤ 后假名左缘;假名居中,两端各
            //       伸 kanaW/2·s,约束两侧宽度同乘 s,线性一次可解(见下)。跨行
            //       段不参与(两行布局独立);margin 同组取 rubyGapPx(读法连续),
            //       跨组取 2×(词间透气)。组比例=触及该组全部约束的最小值;
            //       相邻同读音约束对称 → 两侧同缩同放。行首/行尾越界由绘制时
            //       x-clamp 兜底,不参与缩放(宁可轻微越过相邻字符上方,不为贴
            //       边强缩字号)。
            //    c) 不需要死板全缩(用户补充):三假名标注的汉字,若旁边没有拥挤
            //       的可能,就完全不缩——只有约束真的被触及时才产生收缩需求,
            //       本模型天然如此(无约束触发的组保持默认字号)。
            val rubyGap2Px = gapPx * 2f
            // 收缩约束建模:组 g 的假名按 s_g ≤ 1 缩。假名 i 居中于其 surface,
            // 半宽 h_i·s,面心 c_i(surface 中心)。约束:相邻注音对 (i,j)
            //   c_i + h_i·s(g_i) + margin ≤ c_j - h_j·s(g_j)
            // 变量是每组的 s(g),左侧系数 h_i(h_i≥0),右侧 -h_j,均为正系数的
            // 线性不等式。逐段可用宽(右缘到下一注音左缘)的旧约束废弃——它假设
            // "假名从 surface 左缘起排",与居中绘制不符(正是盲区②)。
            // 解法(v1.3.x 第二轮:对称求解):从左向右扫描,每个相邻注音对
            //   s_pair = (c_j - c_i - margin) / (h_i + h_j)
            // 作为上界**同时**套给两侧组(各自取 min)。同组对左右端同组,天然
            // 对称;跨组对也统一比例——相邻注音字号一致符合日语排版观感,杜绝
            // 第一轮"先假定右侧不缩、负担全给左侧"求出的 0.95/1.00 不对称解
            // (几何上塞得下但视觉"一大一小",实机验证打回)。clamp 到
            // [rubyShrinkMin, 1]。从左向右一次扫描 O(n)。
            val groupIds = IntArray(segments.size) { -1 }
            var gid = 0
            for (i in segments.indices) {
                if (segments[i].reading == null) continue
                val sameGroupAsPrev = i > 0 && segments[i - 1].reading != null &&
                    baseLayout.getLineForOffset(mRanges[i - 1][0]) == baseLayout.getLineForOffset(mRanges[i][0])
                groupIds[i] = if (sameGroupAsPrev) groupIds[i - 1] else gid++
            }
            // 各段假名默认半宽、surface 中心、所在行(px,居中绘制假设)
            val halfW = FloatArray(segments.size)
            val center = FloatArray(segments.size)
            val segLine = IntArray(segments.size)
            for (i in segments.indices) {
                if (segments[i].reading == null) continue
                val mStart = mRanges[i][0]
                val mEnd = mRanges[i][1]
                val lineStart = baseLayout.getLineForOffset(mStart)
                val lineEnd = baseLayout.getLineForOffset((mEnd - 1).coerceAtLeast(mStart))
                val left: Float; val right: Float
                if (lineStart == lineEnd) {
                    left = baseLayout.getBoundingBox(mStart).left
                    right = baseLayout.getBoundingBox((mEnd - 1).coerceAtLeast(mStart)).right
                } else {
                    // 防御：带读音 ≥2 字段已原子化不应跨行；若仍跨行（极长单段
                    // 超行宽），注音标首行那截——与绘制分支同一口径。
                    val firstLineLast = (baseLayout.getLineEnd(lineStart) - 1).coerceAtLeast(mStart)
                    left = baseLayout.getBoundingBox(mStart).left
                    right = baseLayout.getBoundingBox(firstLineLast).right
                }
                halfW[i] = defaultKanaLayouts[i]!!.size.width / 2f
                center[i] = (left + right) / 2f
                segLine[i] = lineStart
            }
            // 从左向右逐对解对称比例(初值 1,被触及的约束收紧):
            val scaleByGroup = FloatArray(gid) { 1f }
            var prevKanaIdx = -1
            for (i in segments.indices) {
                if (segments[i].reading == null) continue
                if (prevKanaIdx >= 0 && segLine[prevKanaIdx] == segLine[i]) {
                    val hSum = halfW[prevKanaIdx] + halfW[i]
                    if (hSum > 0f) {
                        val margin = if (groupIds[prevKanaIdx] == groupIds[i]) gapPx else rubyGap2Px
                        val bound = ((center[i] - center[prevKanaIdx] - margin) / hSum)
                            .coerceIn(rubyShrinkMin, 1f)
                        val gi = groupIds[prevKanaIdx]
                        val gj = groupIds[i]
                        if (bound < scaleByGroup[gi]) scaleByGroup[gi] = bound
                        if (bound < scaleByGroup[gj]) scaleByGroup[gj] = bound
                    }
                }
                prevKanaIdx = i
            }
            // 组比例应用:缩过的组重测假名,未触及约束的组保持默认字号(不缩)
            val kanaLayouts = ArrayList<TextLayoutResult?>(segments.size)
            repeat(segments.size) { kanaLayouts.add(null) }
            for (i in segments.indices) {
                val reading = segments[i].reading ?: continue
                val hLayout = defaultKanaLayouts[i] ?: continue
                val scale = scaleByGroup[groupIds[i]]
                kanaLayouts[i] = if (scale >= 0.999f) hLayout
                else {
                    val shrunkStyle = rubyStyle.copy(fontSize = (rubyVal * scale).sp)
                    measurer.measure(
                        reading, shrunkStyle,
                        constraints = Constraints(maxWidth = Int.MAX_VALUE),
                    )
                }
            }
            Measured(baseLayout, mRanges, kanaLayouts)
        }
        val baseLayout = measured.baseLayout
        val mRanges = measured.mRanges
        val kanaLayouts = measured.kanaLayouts

        // v1.3.x 双驱动值:heightProgress 驱动画布高度(行高增量)+文字下沉,
        // kanaProgress 驱动假名透明度+下滑归位。p=0 时高度=无槽布局、与 Text
        // 分支逐字节一致——开关切换行节奏不跳。动画期间偏移/透明度不进
        // 测量 key(测量与进度无关),按帧绘制。
        // 逐行几何:完整布局第 i 行行框 [i·L, (i+1)·L],L=基础行高+槽,Bottom
        // 对齐文字在行框底部(槽在文字上方)。进度 hp 时目标行框高=基础+槽·hp,
        // 第 i 行目标框顶 = i·(基础+槽·hp),文字应在 [框顶+槽·hp, +基础]。
        // 由完整布局换算:第 i 行文字整体上移 dy_i = -(i+1)·槽·(1-hp)。
        // 一个全局平移表达不了逐行递增间距 → 逐行 clip 到目标条带内平移绘制。
        val hp = heightProgress.coerceIn(0f, 1f)
        val kp = kanaProgress.coerceIn(0f, 1f)
        val fullH = baseLayout.size.height
        val slotPerLinePx = with(density) { rubySize.toPx() } + gapPx
        val lineCount = baseLayout.lineCount
        val slotTotalPx = slotPerLinePx * lineCount
        // 槽完全展开的行高(px)与无槽基础行高——从布局反推:L = fullH/lineCount
        val slotLineH = fullH / lineCount.coerceAtLeast(1)
        val baseLineHPx = slotLineH - slotPerLinePx
        // v1.3.x 回归修复(实机:未开振假名时英文行/作词作曲行被压裁成半高):
        // hasRuby=false 的行 baseStyle 本就不含槽(测量高度=无槽高度),上面的
        // 槽减项对它们是错减——无条件减去 slotTotal×(1-hp) 把画布裁得比布局矮
        // 一截。槽减项只对 hasRuby=true(布局内真嵌着槽)的行有意义;无注音行
        // 画布高度=布局原高,p=0/p=1 都不受 hp 影响。这也解释"开振假名后恢复
        // 正常":hp→1 减项归零。
        val hPx = if (hasRuby) (fullH - slotTotalPx * (1f - hp)).coerceAtLeast(1f) else fullH.toFloat()
        val h = with(density) { hPx.toDp() }
        val rubySlotPx = slotPerLinePx
        Canvas(Modifier.fillMaxWidth().height(h)) {
            // 逐行:clip 到该行目标条带,整布局平移 dy_i 绘制(条带只露出第 i 行)
            if (!hasRuby || hp >= 0.999f) {
                drawText(baseLayout, color = color, topLeft = Offset.Zero)
            } else {
                for (line in 0 until lineCount) {
                    val bandTop = line * (baseLineHPx + slotPerLinePx * hp)
                    val bandBottom = bandTop + baseLineHPx + slotPerLinePx * hp
                    val dy = -(line + 1) * slotPerLinePx * (1f - hp)
                    clipRect(0f, bandTop, size.width, bandBottom) {
                        drawText(baseLayout, color = color, topLeft = Offset(0f, dy))
                    }
                }
            }
            if (!hasRuby || kp <= 0f) return@Canvas
            for (i in segments.indices) {
                val kana = kanaLayouts[i] ?: continue
                val mStart = mRanges[i][0]
                val mEnd = mRanges[i][1]
                val lineStart = baseLayout.getLineForOffset(mStart)
                val lineEnd = baseLayout.getLineForOffset((mEnd - 1).coerceAtLeast(mStart))
                val runLeft: Float
                val runRight: Float
                if (lineStart == lineEnd) {
                    runLeft = baseLayout.getBoundingBox(mStart).left
                    runRight = baseLayout.getBoundingBox((mEnd - 1).coerceAtLeast(mStart)).right
                } else {
                    // 防御：segment 原子化后不应跨行；若仍跨行（极长单段超行宽），注音标首行
                    val firstLineLast = (baseLayout.getLineEnd(lineStart) - 1).coerceAtLeast(mStart)
                    runLeft = baseLayout.getBoundingBox(mStart).left
                    runRight = baseLayout.getBoundingBox(firstLineLast).right
                }
                val lineTop = baseLayout.getLineTop(lineStart)
                val cx = (runLeft + runRight) / 2f
                val kanaW = kana.size.width.toFloat()
                val kanaH = kana.size.height.toFloat()
                // 水平居中于 surface，clamp 到画布内保证完整可见（防最左边假名被裁半边）
                val x = (cx - kanaW / 2f).coerceIn(0f, (maxW.toFloat() - kanaW).coerceAtLeast(0f))
                // 假名垂直位置 = 槽内居中 + 该行下沉量 dy(lineStart) + 未归位量
                // ((1-kp)·槽/2,从稍高处下滑)。clamp 到该行目标条带顶,防半开槽
                // 时假名越进上一行文字区。
                val bandTopLine = lineStart * (baseLineHPx + slotPerLinePx * hp)
                val y = (lineTop + ((rubySlotPx - kanaH) / 2f).coerceAtLeast(0f)
                    - (lineStart + 1) * slotPerLinePx * (1f - hp)
                    - (1f - kp) * (rubySlotPx / 2f))
                    .coerceAtLeast(bandTopLine)
                drawText(
                    kana, color = color.copy(alpha = color.alpha * kp),
                    topLeft = Offset(x, y),
                )
            }
        }
    }
}

private class Measured(
    val baseLayout: TextLayoutResult,
    val mRanges: Array<IntArray>,
    val kanaLayouts: List<TextLayoutResult?>,
)

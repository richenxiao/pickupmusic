package com.shiyin.music.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Lucide icons used by the design, embedded as stroke path data
 * (stroke-width 2.75, round cap/join, 24x24 viewport, per handoff).
 */
private fun stroke(name: String, vararg paths: String, width: Float = 2.75f): ImageVector {
    val b = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    )
    for (d in paths) {
        b.addPath(
            pathData = addPathNodes(d),
            stroke = SolidColor(Color.White),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

private fun filled(name: String, vararg paths: String): ImageVector {
    val b = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    )
    for (d in paths) {
        b.addPath(pathData = addPathNodes(d), fill = SolidColor(Color.White))
    }
    return b.build()
}

object Lucide {
    val Music by lazy {
        stroke("music", "M9 18V5l12-2v13", "M6 18m-3 0a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M18 16m-3 0a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
    }
    val Folder by lazy {
        stroke("folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    }
    val Trash by lazy {
        stroke("trash", "M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    }
    val Check by lazy { stroke("check", "M20 6 9 17l-5-5") }
    val CheckBold by lazy { stroke("checkBold", "M20 6 9 17l-5-5", width = 3.5f) }
    // v1.3.x 反馈:振假名"自动校验"图标——Check(单勾)语义太弱,用户仍觉得不符内容
    // (它是"逐行校对跑没跑"而非"确认成功")。BadgeCheck(徽章+勾=已验证)贴合。
    val BadgeCheck by lazy {
        stroke(
            "badgeCheck",
            "M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 4 4 0 0 1-6.74 0 4 4 0 0 1-4.78-4.78 4 4 0 0 1 0-6.74Z",
            "m9 12 2 2 4-4",
        )
    }
    // v1.3.x 反馈:歌词本翻译设置图标——Languages(双语对照:文A/字A)。
    val Languages by lazy {
        stroke("languages", "m5 8 6 6", "m4 14 6-6 2-3", "M2 5h12", "M7 2h1", "m22 22-5-10-5 10", "M14 18h6")
    }
    val Info by lazy {
        stroke("info", "M12 12m-10 0a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 8v4", "M12 16h.01")
    }
    val Settings by lazy {
        stroke(
            "settings",
            "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
            "M12 12m-3 0a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        )
    }
    val Search by lazy { stroke("search", "M11 11m-8 0a8 8 0 1 0 16 0a8 8 0 1 0 -16 0", "m21 21-4.3-4.3") }
    // AI/Agent 入口与对话发送用。Sparkles 四角星(6,8 主星 + 三颗小星),Send 纸飞机。
    val Sparkles by lazy { stroke("sparkles", "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z", "M20 3v4", "M22 5h-4", "M4 17v2", "M3 18h2") }
    // v1.3.10 A19/P3:「拾光记忆」入口。记忆=认识你的事实,用 brain 比 sparkles 更贴切
    // (sparkles 已被 Agent 品牌入口占用)。
    val Brain by lazy {
        stroke(
            "brain",
            "M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z",
            "M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z",
            "M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4",
            "M17.599 6.5a3 3 0 0 0 .399-1.375",
            "M6.003 5.125A3 3 0 0 0 6.401 6.5",
            "M3.477 10.896a4 4 0 0 1 .585-.396",
            "M19.938 10.5a4 4 0 0 1 .585.396",
            "M6 18a4 4 0 0 1-1.967-.516",
            "M19.967 17.484A4 4 0 0 1 18 18",
        )
    }
    val Send by lazy { stroke("send", "M14.536 21.686a.5.5 0 0 0 .908-.317l.586-7.426.585 7.426a.5.5 0 0 0 .908.317l5.878-5.878a.5.5 0 0 0 .146-.448l-1.085-6.477a.5.5 0 0 0-.746-.375l-13.5 6.75a.5.5 0 0 0 0 .866l3.59 2.157") }
    // Agent 设置里"自动获取模型"的刷新按钮。
    val RefreshCw by lazy {
        stroke(
            "refreshCw",
            "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5",
        )
    }
    val ChevronRight by lazy { stroke("chevR", "m9 18 6-6-6-6") }
    val ChevronLeft by lazy { stroke("chevL", "m15 18-6-6 6-6") }
    val ChevronDown by lazy { stroke("chevD", "m6 9 6 6 6-6") }
    val ChevronUp by lazy { stroke("chevU", "m18 15-6-6-6 6") }
    // v1.3.x 歌词本四项体验:歌词本返回/设置键去底色后视觉存在感偏弱,补加粗变体
    // (stroke 2.75→3.6 + 略放大)——只用于歌词本页,别处维持标准 2.75。
    val ChevronDownBold by lazy { stroke("chevD-b", "m6 9 6 6 6-6", width = 3.6f) }
    /** v1.3.2: 向上箭头(Agent 发送键,图1风格)。Lucide "arrow-up"。 */
    val ArrowUp by lazy { stroke("arrowUp", "M12 19V5", "m5 12 7-7 7 7") }
    val ArrowUpDown by lazy {
        stroke("arrowUpDown", "m21 16-4 4-4-4", "M17 20V4", "m3 8 4-4 4 4", "M7 4v16")
    }
    val LayoutGrid by lazy {
        stroke(
            "layoutGrid",
            "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M15 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
            "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1z",
        )
    }
    val ListRows by lazy {
        stroke("listRows", "M3 12h.01", "M3 18h.01", "M3 6h.01", "M8 12h13", "M8 18h13", "M8 6h13")
    }
    val ListMusic by lazy {
        stroke("listMusic", "M21 15V6", "M18.5 18a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z", "M12 12H3", "M16 6H3", "M12 18H3")
    }
    /** 字幕/歌词框：圆角框内两行文字，用于「打开歌词」。 */
    val Captions by lazy {
        stroke("captions", "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z", "M7 11h5", "M7 14h8")
    }
    /** 眼睛(明文),与 [EyeOff] 成对,用于密码框明/密文切换。 */
    val Eye by lazy {
        stroke(
            "eye",
            "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0",
            "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z",
        )
    }
    val EyeOff by lazy {
        stroke(
            "eyeOff",
            "M10.7 5C11.6 4.4 12.7 4 14 4c7 0 8 8 8 8a13 13 0 0 1-2.6 3.8",
            "M6.6 6.6A13 13 0 0 0 2 12s1 8 10 8c1.9 0 3.5-.5 4.9-1.3",
            "m2 2 20 20",
            "M9.9 9.9a3 3 0 0 0 4.2 4.2",
        )
    }
    val RotateCcw by lazy {
        stroke("rotateCcw", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    }
    val Brush by lazy {
        stroke(
            "brush",
            "m13 11 9-9",
            "M14.6 12.6c.8.8.9 2.1.2 3L10 22l-8-8 6.4-4.8c.9-.7 2.2-.6 3 .2Z",
            "m6.8 10.4 6.8 6.8",
        )
    }
    val Moon by lazy { stroke("moon", "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z") }

    /**
     * v1.4.0: 「主题/外观」（Lucide 官方 `palette` 路径，含四个色点）。
     *
     * 为什么不用已有的 [Brush]：本仓的 Brush **不是官方 `brush` 路径**（是手搓的斜扫形状），
     * 20dp 下看起来像扫把，不能当主题图标。这里按官方定义补一个语义正确的。
     * 四个 `h.01` 是官方那几个 r=.5 的小圆点——靠 round cap 渲染成点。
     */
    val Palette by lazy {
        stroke(
            "palette",
            // 官方 icons/palette.svg 的当前路径（2026-10 核对自 lucide-icons/lucide 主仓；
            // 旧版是 "M12 2C6.5 2 2 6.5 2 12s…"，已废弃，别再用）。四个点是官方那四个 r=.5 圆。
            "M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z",
            "M13.5 6.5h.01",
            "M17.5 10.5h.01",
            "M6.5 12.5h.01",
            "M8.5 7.5h.01",
        )
    }

    /** v1.4.0: 主题三态里的「浅色」段（Lucide sun：圆 + 8 条光线）。 */
    val Sun by lazy {
        stroke(
            "sun",
            "M12 12m-4 0a4 4 0 1 0 8 0a4 4 0 1 0 -8 0",
            "M12 2v2", "M12 20v2",
            "M4.93 4.93l1.41 1.41", "M17.66 17.66l1.41 1.41",
            "M2 12h2", "M20 12h2",
            "M6.34 17.66l-1.41 1.41", "M19.07 4.93l-1.41 1.41",
        )
    }
    val Infinity by lazy {
        stroke("infinity", "M12 12c-2-2.67-4-4-6-4a4 4 0 1 0 0 8c2 0 4-1.33 6-4Zm0 0c2 2.67 4 4 6 4a4 4 0 0 0 0-8c-2 0-4 1.33-6 4Z")
    }
    val Mic by lazy {
        stroke("mic", "M12 8a3 3 0 0 0 3-3V3a3 3 0 0 0-6 0v2a3 3 0 0 0 3 3Z", "M19 5a7 7 0 0 1-14 0", "M12 12v9")
    }
    val Clock by lazy { stroke("clock", "M12 12m-10 0a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 6v6l4 2") }
    val Shuffle by lazy {
        stroke(
            "shuffle",
            "m18 14 4 4-4 4",
            "m18 2 4 4-4 4",
            "M2 18h1.973a4 4 0 0 0 3.3-1.7l5.454-8.6a4 4 0 0 1 3.3-1.7H22",
            "M2 6h1.972a4 4 0 0 1 3.6 2.2",
            "M22 18h-6.041a4 4 0 0 1-3.3-1.8l-.359-.45",
        )
    }
    val Repeat by lazy {
        stroke("repeat", "m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3")
    }

    /**
     * v1.3.10 需求 A25:单曲循环图标(Lucide `repeat-1`)。
     * 旧实现是"Repeat 图标上叠一个 9sp 的 '1' Text"——居中叠字既糊又土(用户实测嫌丑)。
     * 官方 repeat-1 = repeat 的四条路径 + 中间的「1」(`M11 10h1v4`)。
     */
    val Repeat1 by lazy {
        stroke(
            "repeat-1",
            "m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3", "M11 10h1v4",
        )
    }
    val Sliders by lazy {
        stroke(
            "sliders",
            "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3",
            "M14 2v4", "M8 10v4", "M16 18v4",
        )
    }
    // v1.3.x 歌词本四项体验:同 ChevronDownBold——设置键加粗变体,只用于歌词本页。
    val SlidersBold by lazy {
        stroke(
            "sliders-b",
            "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3",
            "M14 2v4", "M8 10v4", "M16 18v4",
            width = 3.6f,
        )
    }
    // v1.3.x 虚影三稿曾加过 ChevronDownFilled/SlidersFilled 实心图标(方案 A),
    // 终版定调"去掉圆形底色、图标原样悬浮"后删除——描边图标直接用回
    // ChevronDown/Sliders。
    val Close by lazy { stroke("close", "M18 6 6 18", "m6 6 12 12") }
    val Home by lazy {
        stroke(
            "home",
            "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
            "M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
        )
    }
    val Library by lazy { stroke("library", "m16 6 4 14", "M12 6v14", "M8 8v12", "M4 4v16") }
    val Heart by lazy {
        stroke("heart", "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z")
    }
    val HeartFilled by lazy {
        filled("heartF", "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z")
    }
    val Plus by lazy { stroke("plus", "M5 12h14", "M12 5v14") }
    // v1.3.9: Agent 回复操作栏(复制/重试) + 对话收藏。
    val Copy by lazy {
        stroke("copy",
            "M10 8m0 0a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2z",
            "M6 18H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v2")
    }
    val Pin by lazy {
        stroke("pin", "M12 17v5", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1z")
    }
    val PinFilled by lazy {
        filled("pinF", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1z", "M12 17v5")
    }
    // v1.3.9: Agent 中间过程记录的「动作类型」图标——不同类型不同图标,
    // 图标切换本身就是"这是一条新的独立记录"的信号(参照 DeepSeek 的结构)。
    /** 思考:方盒(立方体)。 */
    val Box by lazy {
        stroke("box",
            "M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z",
            "m3.3 7 8.7 5 8.7-5", "M12 22V12")
    }
    /** 浏览页面:文档。 */
    val FileText by lazy {
        stroke("fileText",
            "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
            "M14 2v4a2 2 0 0 0 2 2h4", "M16 13H8", "M16 17H8", "M10 9H8")
    }

    // v1.5–v1.8 additions
    val MoreVertical by lazy {
        filled(
            "moreV",
            "M12 5m-1.7 0a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0",
            "M12 12m-1.7 0a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0",
            "M12 19m-1.7 0a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0",
        )
    }
    val CirclePlus by lazy {
        stroke("circlePlus", "M12 12m-10 0a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M8 12h8", "M12 8v8", width = 2.5f)
    }
    val CircleFilled by lazy { filled("circleF", "M12 12m-10 0a10 10 0 1 0 20 0a10 10 0 1 0 -20 0") }
    val CheckSmall by lazy { stroke("checkSmall", "M16.5 8.5 10.5 15 7.5 12") }
    val ListPlus by lazy {
        stroke("listPlus", "M11 12H3", "M16 6H3", "M16 18H3", "M18 9v6", "M21 12h-6")
    }
    val ListQueue by lazy {
        stroke("listQueue", "M12 12H3", "M16 6H3", "M12 18H3", "m16 12 5 3-5 3v-6")
    }
    val Disc by lazy {
        stroke("disc", "M12 12m-10 0a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 12m-3 0a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
    }
    val Gauge by lazy {
        stroke("gauge", "M12 14a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM9.14 9.14 4.5 4.5M12 14l4-4")
    }
    val User by lazy { stroke("user", "M12 8m-5 0a5 5 0 1 0 10 0a5 5 0 1 0 -10 0", "M20 21a8 8 0 0 0-16 0") }
    val Users by lazy {
        stroke("users", "M18 21a8 8 0 0 0-16 0", "M10 8m-5 0a5 5 0 1 0 10 0a5 5 0 1 0 -10 0", "M22 20c0-3.37-2-6.5-4-8a5 5 0 0 0-.45-8.3")
    }
    val Smartphone by lazy {
        stroke("smartphone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M12 18h.01")
    }
    val Headphones by lazy {
        stroke("headphones", "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3")
    }
    val Speaker by lazy {
        stroke("speaker", "M6 2h12a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M12 6h.01", "M12 14m-4 0a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M12 14h.01")
    }
    // v1.3.x 播放设置专用(用户明确要求不复用图标):Volume2/AudioLines/SlidersVertical/Layers,
    // 与 Lucide 官方 24x24 路径一致,描边风格同本项目 stroke()。
    val Volume2 by lazy {
        stroke(
            "volume2",
            "M11 5 6 9H2v6h4l5 4V5z",
            "M15.54 8.46a5 5 0 0 1 0 7.07",
            "M19.07 4.93a10 10 0 0 1 0 14.14",
        )
    }
    val AudioLines by lazy {
        stroke(
            "audioLines",
            "M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3",
        )
    }
    val SlidersVertical by lazy {
        stroke(
            "slidersVertical",
            "M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3",
            "M2 14h4", "M10 8h4", "M18 16h4",
        )
    }
    val Layers by lazy {
        stroke(
            "layers",
            "m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z",
            "m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65",
            "m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65",
        )
    }
    val Tv by lazy {
        stroke("tv", "M4 7h16a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M17 2 12 7 7 2")
    }
    val GripLines by lazy { stroke("gripLines", "M4 7h16", "M4 12h16", "M4 17h16", width = 2.5f) }
    val Minus by lazy { stroke("minus", "M5 12h14", width = 3f) }
    val Shield by lazy {
        stroke("shield", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z")
    }
    val CircleInfo by lazy {
        stroke("circleInfo", "M12 12m-10 0a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 16v-4", "M12 8h.01")
    }

    /**
     * v1.8 brand mark: a clay bowl catching a falling sound seed with two
     * concentric sound arcs above. 48 viewBox, mono, tinted at use site.
     */
    val Logo by lazy {
        val b = ImageVector.Builder(
            name = "shiyinLogo", defaultWidth = 48.dp, defaultHeight = 48.dp,
            viewportWidth = 48f, viewportHeight = 48f,
        )
        b.addPath(
            pathData = addPathNodes("M10 27 C10 35.5 16 41 24 41 C32 41 38 35.5 38 27 C38 25.9 37.1 25 36 25 L12 25 C10.9 25 10 25.9 10 27 Z"),
            fill = SolidColor(Color.White),
        )
        b.addPath(
            pathData = addPathNodes("M24 19m-4.6 0a4.6 4.6 0 1 0 9.2 0a4.6 4.6 0 1 0 -9.2 0"),
            fill = SolidColor(Color.White),
        )
        for (d in listOf("M18 13 A8.5 8.5 0 0 1 30 13", "M14.8 9.8 A13 13 0 0 1 33.2 9.8")) {
            b.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.6f,
                strokeLineCap = StrokeCap.Round,
            )
        }
        b.build()
    }

    // Filled transport glyphs
    val Play by lazy { filled("play", "M6 3 L20 12 L6 21 Z") }

    /** v1.3.x: 描边版 play——设置列表等"图标全是描边"的场景用,与 Lucide 官方
     *  play 路径一致(填充版 Play 只用于彩色圆钮上的白色三角)。 */
    val PlayOutline by lazy { stroke("playOutline", "M6 3 20 12 6 21Z") }

    /** v1.3.x: 打轴模式入口(锚定/对准语义,与每行的锚定 Clock 按钮区分)。 */
    val Crosshair by lazy {
        stroke(
            "crosshair",
            "M22 12h-4", "M6 12H2", "M12 6V2", "M12 22v-4",
            "M12 12m-10 0a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
        )
    }
    val Pause by lazy {
        filled("pause", "M7 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M15 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    }
    val SkipBack by lazy {
        filled("skipBack", "M19 20 L9 12 L19 4 Z", "M5.5 4h.5a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-.5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    }
    val SkipForward by lazy {
        filled("skipFwd", "M5 4 L15 12 L5 20 Z", "M18 4h.5a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H18a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    }
    val Menu by lazy { stroke("menu", "M4 6h16", "M4 12h16", "M4 18h16", width = 2.5f) }
    /** v1.3.5: 首页专用汉堡——三条线比标准 Menu 更长(20)、间距更大(y=4/12/20,跨 16
     *  而非 12)、线更粗(3),在小圆按钮里视觉量级与"下午好"大标题相称(用户定调:
     *  线要拉开,视觉不协调)。 */
    val MenuWide by lazy { stroke("menuWide", "M3 4h18", "M3 12h18", "M3 20h18", width = 3f) }
    val Download by lazy { stroke("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3") }
    // v4: sidebar icons
    val History by lazy { stroke("history", "M12 8v4l3 3", "M12 22a10 10 0 1 0 -10 -10", "M3 12H2") }
    val BarChart by lazy { stroke("barChart", "M4 20h16", "M9 20V8", "M15 20V4", "M4 20h16", "M9 20V8", "M15 20V4") }
    val Bell by lazy { stroke("bell", "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9", "M13.73 21a2 2 0 0 1-3.46 0") }

    // v4.3: bluetooth icon for BT-audio device indication
    val Bluetooth by lazy { stroke("bluetooth", "m7 7 10 10-5 5V2l5 5L7 17") }

    /** v1.3.x 写真源"修改键"按钮(统一图标设计语言,替代纯文字按钮)。Lucide "pencil"。 */
    val Pencil by lazy { stroke("pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.642 16.374a3 3 0 0 0-.748 1.5l-.403 2.124a1 1 0 0 0 1.165 1.165l2.124-.403a3 3 0 0 0 1.5-.748Z", "m15 5 4 4") }

    /** v1.3.x 识别缓存专用(存储/数据库语义),与文件夹管理(Folder)去重。Lucide "database"。 */
    val Database by lazy { stroke("database", "M12 5c-5 0-9 1.2-9 2.7v10.6c0 1.5 4 2.7 9 2.7s9-1.2 9-2.7V7.7c0-1.5-4-2.7-9-2.7z", "M3 8c0 1.5 4 2.7 9 2.7s9-1.2 9-2.7", "M3 12.7c0 1.5 4 2.7 9 2.7s9-1.2 9-2.7") }
}

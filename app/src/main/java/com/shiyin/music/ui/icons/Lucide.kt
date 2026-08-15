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
    val ChevronRight by lazy { stroke("chevR", "m9 18 6-6-6-6") }
    val ChevronLeft by lazy { stroke("chevL", "m15 18-6-6 6-6") }
    val ChevronDown by lazy { stroke("chevD", "m6 9 6 6 6-6") }
    val ChevronUp by lazy { stroke("chevU", "m18 15-6-6-6 6") }
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
    val Sliders by lazy {
        stroke(
            "sliders",
            "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3",
            "M14 2v4", "M8 10v4", "M16 18v4",
        )
    }
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
    val Download by lazy { stroke("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3") }
    // v4: sidebar icons
    val History by lazy { stroke("history", "M12 8v4l3 3", "M12 22a10 10 0 1 0 -10 -10", "M3 12H2") }
    val BarChart by lazy { stroke("barChart", "M4 20h16", "M9 20V8", "M15 20V4", "M4 20h16", "M9 20V8", "M15 20V4") }
    val Bell by lazy { stroke("bell", "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9", "M13.73 21a2 2 0 0 1-3.46 0") }

    // v4.3: bluetooth icon for BT-audio device indication
    val Bluetooth by lazy { stroke("bluetooth", "m7 7 10 10-5 5V2l5 5L7 17") }
}

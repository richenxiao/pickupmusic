package com.shiyin.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.data.Track
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic

/**
 * Spotify-style bottom sheet used by the v1.5+ menus: dim scrim + a floating
 * rounded panel inset 10dp from the screen edges.
 */
@Composable
fun BoxScope.SheetOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    maxHeightFraction: Float = 1f,
    content: @Composable () -> Unit,
) {
    val c = LocalOrganic.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(150)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss)
        )
    }
    val maxH = if (maxHeightFraction < 1f) {
        (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * maxHeightFraction).dp
    } else null
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(250)) { it / 3 } + fadeIn(tween(250)),
        exit = slideOutVertically(tween(200)) { it / 3 } + fadeOut(tween(150)),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Box(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 10.dp)
                .padding(bottom = 10.dp)
                .fillMaxWidth()
                .then(if (maxH != null) Modifier.heightIn(max = maxH) else Modifier)
                .shadowLg(RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(c.bg),
        ) { content() }
    }
}

/**
 * v22: 收藏按钮 —— **加号连续变形为勾号**（不再是两个图标交叉淡化）。
 *
 * 现状原来是 `CirclePlus` ↔ 实心圈+`CheckSmall` 两个图标硬切；现在用 Canvas 画
 * 一个圆 + 两条线，几何完全取自项目自己的图标定义（见 [FavMorph]），由**同一个
 * 动画进度**驱动端点插值，所以加号是"自己弯成勾"的：
 *   · 横线收缩并旋转 → 勾号左边短臂
 *   · 竖线缩短并抬起 → 勾号右边长臂
 *   · 两条线的内侧端点一起汇到折点，加号的交叉点自然张开成勾号的折点
 *
 * 同一进度还驱动配色：未收藏 = 描边圈 + [idleTint] 线；已收藏 = accent 实心圈 + 背景色线。
 * 再次点击 = 进度反向播放 —— `animateFloatAsState` 支持中途改目标值，所以**快速连点
 * 不会错乱**，而是平滑地从当前位置反向，不需要"动画中忽略点击"。
 *
 * @param interactionSource 传进来即可拿到"按下"态做缩放反馈（按下轻微缩小、松手弹回）；
 *        不传就没有按下反馈（调用方若自己在外面处理点击，把同一个 source 交给它即可）。
 */
@Composable
fun FavIcon(
    fav: Boolean,
    size: Dp,
    idleTint: Color,
    interactionSource: MutableInteractionSource? = null,
) {
    val c = LocalOrganic.current
    val p by animateFloatAsState(
        targetValue = if (fav) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "favMorph",
    )
    // 按下缩放。条件式调用 @Composable 在这里是安全的：该条件对同一调用点是稳定的。
    val pressed = if (interactionSource != null) interactionSource.collectIsPressedAsState().value else false
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "favPress",
    )

    Canvas(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        val u = this.size.minDimension / 24f          // 24 = 图标 viewBox
        val sw = FavMorph.STROKE_W * u
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f

        // 圆：未收藏描边（随进度淡出），已收藏实心（随进度淡入）
        if (p < 1f) {
            drawCircle(
                color = idleTint.copy(alpha = idleTint.alpha * (1f - p)),
                radius = FavMorph.CIRCLE_R * u - sw / 2f,
                center = Offset(cx, cy),
                style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        if (p > 0f) {
            drawCircle(color = c.accent.copy(alpha = p), radius = FavMorph.CIRCLE_R * u, center = Offset(cx, cy))
        }

        // 线色：未收藏跟随 idleTint，已收藏在实心圈上要反色
        val lineColor = androidx.compose.ui.graphics.lerp(idleTint, c.bg, p)
        for (s in FavMorph.segments(p)) {
            drawLine(
                color = lineColor,
                start = Offset(cx + (s.x1 - 12f) * u, cy + (s.y1 - 12f) * u),
                end = Offset(cx + (s.x2 - 12f) * u, cy + (s.y2 - 12f) * u),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Header row shared by the track menu and player menu sheets. */
@Composable
fun SheetSongHeader(track: Track) {
    val c = LocalOrganic.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(track, 42.dp, RoundedCornerShape(8.dp), fontSize = 17)
            Column(Modifier.weight(1f)) {
                Text(track.title, style = body(14.5f, FontWeight.Bold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = body(12f, FontWeight.Normal, c.n600), modifier = Modifier.padding(top = 1.dp), maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Spacer(Modifier.height(6.dp))
    }
}

/** One action row inside a menu sheet. */
@Composable
fun SheetActionRow(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) = SheetActionRowImpl(
    icon = { OIcon(icon, 19.dp, tint ?: LocalOrganic.current.n700) },
    label = label,
    labelColor = tint ?: LocalOrganic.current.text,
    trailing = trailing,
    badge = badge,
    onClick = onClick,
)

/**
 * 重载：矢量 drawable（`painterResource`）——拾光Agent 的品牌图标走这条。
 * 见 `OIcon(Painter…)` 注释：本 Compose 版本没有可用的 `vectorResource`。
 *
 * 槽位仍 19dp（与 Lucide 行一致）；图标画 27.4dp 并 `wrapContentSize(unbounded)`
 * 允许溢出，理由同 `AppRoot.kt` 的 `SidebarItem(Painter)`。
 */
@Composable
fun SheetActionRow(
    icon: androidx.compose.ui.graphics.painter.Painter,
    label: String,
    tint: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) = SheetActionRowImpl(
    icon = {
        Box(Modifier.size(19.dp), contentAlignment = Alignment.Center) {
            OIcon(
                icon, 27.4.dp, tint ?: LocalOrganic.current.n700,
                modifier = Modifier.wrapContentSize(unbounded = true),
            )
        }
    },
    label = label,
    labelColor = tint ?: LocalOrganic.current.text,
    trailing = trailing,
    badge = badge,
    onClick = onClick,
)

@Composable
private fun SheetActionRowImpl(
    icon: @Composable () -> Unit,
    label: String,
    labelColor: Color,
    trailing: (@Composable () -> Unit)?,
    badge: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        if (badge == null) {
            Text(label, style = body(14.5f, FontWeight.SemiBold, labelColor), modifier = Modifier.weight(1f))
        } else {
            // 带铭牌：标签 + BETA 徽标同行左对齐（徽标紧贴名字，不随 trailing 右飘）
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = body(14.5f, FontWeight.SemiBold, labelColor))
                badge()
            }
        }
        trailing?.invoke()
    }
}

/**
 * v1.5.0: 「BETA」铭牌 —— 标在伴唱/升降 Key 这类**尚不成熟**的功能名旁。
 *
 * 用途是管理预期：功能可用但仍在打磨（伴唱卡死问题在查、升降 Key 音质受
 * Sonic 变调器限制），先标出来比事后解释更诚实。样式跟随主题的柔和强调色，
 * 不做高饱和大色块（铭牌要能被看见，但不该抢功能名的注意力）。
 */
@Composable
fun BetaTag() {
    val c = LocalOrganic.current
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(c.a200)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            "BETA",
            style = body(8.5f, FontWeight.ExtraBold, c.a700).copy(letterSpacing = 0.6.sp),
        )
    }
}

@Composable
fun SheetDivider() {
    val c = LocalOrganic.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .height(1.dp)
            .background(c.divider)
    )
}

/** Circular multi-select check used by the save-to-playlist sheet. */
@Composable
fun RoundCheck(checked: Boolean) {
    val c = LocalOrganic.current
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) c.accent else Color.Transparent)
            .border(2.dp, if (checked) c.accent else c.n400, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) OIcon(Lucide.CheckBold, 12.dp, Color.White)
    }
}

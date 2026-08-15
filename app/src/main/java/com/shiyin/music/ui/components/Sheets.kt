package com.shiyin.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** ⊕ outline (not favorited) / accent filled circle with a check (favorited). */
@Composable
fun FavIcon(fav: Boolean, size: Dp, idleTint: Color) {
    val c = LocalOrganic.current
    if (fav) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(c.accent),
            contentAlignment = Alignment.Center,
        ) { OIcon(Lucide.CheckSmall, size * 0.8f, c.bg) }
    } else {
        OIcon(Lucide.CirclePlus, size, idleTint)
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
    onClick: () -> Unit,
) {
    val c = LocalOrganic.current
    val color = tint ?: c.text
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OIcon(icon, 19.dp, if (tint != null) tint else c.n700)
        Text(label, style = body(14.5f, FontWeight.SemiBold, color), modifier = Modifier.weight(1f))
        trailing?.invoke()
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

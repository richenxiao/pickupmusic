package com.shiyin.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.shiyin.music.R

/**
 * Organic design tokens. Dark theme flips each tonal ramp end-for-end
 * (100<->900, 200<->800, 300<->700, 400<->600, 500 stays), per handoff spec.
 */
data class OrganicColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val accent2: Color,
    val n100: Color, val n200: Color, val n300: Color, val n400: Color,
    val n500: Color, val n600: Color, val n700: Color, val n800: Color, val n900: Color,
    val a100: Color, val a200: Color, val a300: Color, val a400: Color,
    val a500: Color, val a600: Color, val a700: Color, val a800: Color, val a900: Color,
    val s100: Color, val s200: Color, val s300: Color, val s400: Color,
    val s500: Color, val s600: Color, val s700: Color, val s800: Color, val s900: Color,
    val isDark: Boolean,
) {
    val divider: Color get() = text.copy(alpha = 0.16f)
    val textMuted: Color get() = text.copy(alpha = 0.55f)
    val shadowAmbient: Color get() = if (isDark) Color.Black else Color(0xFF2E2B25)
}

val LightOrganic = OrganicColors(
    bg = Color(0xFFF5EAD8), surface = Color(0xFFEBDDC5), text = Color(0xFF201E1D),
    accent = Color(0xFFC67139), accent2 = Color(0xFF7A8A5E),
    n100 = Color(0xFFF9F4ED), n200 = Color(0xFFEEE7DB), n300 = Color(0xFFDCD3C4),
    n400 = Color(0xFFC0B6A5), n500 = Color(0xFFA19786), n600 = Color(0xFF82796A),
    n700 = Color(0xFF645C50), n800 = Color(0xFF474238), n900 = Color(0xFF2E2B25),
    a100 = Color(0xFFFFF2EB), a200 = Color(0xFFFFE1D0), a300 = Color(0xFFFFC6A5),
    a400 = Color(0xFFF6A06B), a500 = Color(0xFFD67F48), a600 = Color(0xFFB2622D),
    a700 = Color(0xFF8C491A), a800 = Color(0xFF643312), a900 = Color(0xFF402310),
    s100 = Color(0xFFF0FAE1), s200 = Color(0xFFE1EECC), s300 = Color(0xFFCCDBB2),
    s400 = Color(0xFFAEBF92), s500 = Color(0xFF8FA073), s600 = Color(0xFF728157),
    s700 = Color(0xFF56633F), s800 = Color(0xFF3D472B), s900 = Color(0xFF272E1B),
    isDark = false,
)

val DarkOrganic = OrganicColors(
    bg = Color(0xFF211D18), surface = Color(0xFF2B2620), text = Color(0xFFF2E9DA),
    accent = Color(0xFFC67139), accent2 = Color(0xFF7A8A5E),
    n100 = Color(0xFF2E2B25), n200 = Color(0xFF474238), n300 = Color(0xFF645C50),
    n400 = Color(0xFF82796A), n500 = Color(0xFFA19786), n600 = Color(0xFFC0B6A5),
    n700 = Color(0xFFDCD3C4), n800 = Color(0xFFEEE7DB), n900 = Color(0xFFF9F4ED),
    a100 = Color(0xFF402310), a200 = Color(0xFF643312), a300 = Color(0xFF8C491A),
    a400 = Color(0xFFB2622D), a500 = Color(0xFFD67F48), a600 = Color(0xFFF6A06B),
    a700 = Color(0xFFFFC6A5), a800 = Color(0xFFFFE1D0), a900 = Color(0xFFFFF2EB),
    s100 = Color(0xFF272E1B), s200 = Color(0xFF3D472B), s300 = Color(0xFF56633F),
    s400 = Color(0xFF728157), s500 = Color(0xFF8FA073), s600 = Color(0xFFAEBF92),
    s700 = Color(0xFFCCDBB2), s800 = Color(0xFFE1EECC), s900 = Color(0xFFF0FAE1),
    isDark = true,
)

val LocalOrganic = staticCompositionLocalOf { LightOrganic }

val Caprasimo = FontFamily(Font(R.font.caprasimo_regular, FontWeight.Normal))
val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
    Font(R.font.figtree_bold, FontWeight.ExtraBold),
)

/** 200ms full-token cross-fade when toggling dark mode, per handoff. */
@Composable
private fun animatedColors(target: OrganicColors): OrganicColors {
    val spec = tween<Color>(200)
    val bg by animateColorAsState(target.bg, spec, label = "bg")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val text by animateColorAsState(target.text, spec, label = "text")
    return target.copy(bg = bg, surface = surface, text = text)
}

@Composable
fun ShiyinTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = animatedColors(if (dark) DarkOrganic else LightOrganic)
    val selection = TextSelectionColors(
        handleColor = colors.accent,
        backgroundColor = colors.accent.copy(alpha = 0.3f),
    )
    CompositionLocalProvider(
        LocalOrganic provides colors,
        LocalTextSelectionColors provides selection,
    ) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = colors.accent,
                background = colors.bg,
                surface = colors.surface,
                onBackground = colors.text,
                onSurface = colors.text,
            ),
            content = content,
        )
    }
}

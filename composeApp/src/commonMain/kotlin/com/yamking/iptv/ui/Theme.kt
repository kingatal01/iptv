package com.yamking.iptv.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Palette {
    val Background = Color(0xFF0B0D14)
    val Surface = Color(0xFF141824)
    val SurfaceHigh = Color(0xFF1C2232)
    val Outline = Color(0xFF2A3145)
    /** Contour des champs de saisie et texte de repli : 3,7:1 sur la surface (un contour utile doit se voir). */
    val OutlineStrong = Color(0xFF667293)
    val Accent = Color(0xFF8B7BFF)
    val AccentSoft = Color(0xFF2A2550)
    val Cyan = Color(0xFF4FD8E8)
    val Text = Color(0xFFF1F3FA)
    val TextMuted = Color(0xFF98A1B8)
    val Heart = Color(0xFFFF6B8A)
    val Danger = Color(0xFFFF8A7A)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF14102E),
    primaryContainer = Palette.AccentSoft,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.Cyan,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextMuted,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.OutlineStrong,
    outlineVariant = Palette.Outline,
    error = Palette.Danger,
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

/**
 * Contour visible quand l'élément a le focus du clavier ou de la télécommande (Android TV).
 * À placer avant `clickable` dans la chaîne de modificateurs.
 */
fun Modifier.focusHighlight(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .border(2.dp, if (focused) Palette.Cyan else Color.Transparent, shape)
}

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}

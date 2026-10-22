package com.agmada.tide.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agmada.tide.R

/** Ocean's palette (design/themes.json in the Ocean repo, "ocean" theme). */
data class Palette(
    val bg: Color,
    val fg: Color,
    val soft: Color,
    val muted: Color,
    val faint: Color,
    val surface: Color,
    val accent: Color,
    val dark: Boolean,
)

val DarkPalette = Palette(
    bg = Color(0xFF0D0D0E), fg = Color(0xFFF2F2F0), soft = Color(0xFFB4B4B0), muted = Color(0xFF8A8A86),
    faint = Color(0xFF2A2A2B), surface = Color(0xFF18181A), accent = Color(0xFF4DA8FF), dark = true,
)

val LightPalette = Palette(
    bg = Color(0xFFFAFAF9), fg = Color(0xFF111111), soft = Color(0xFF4A4A48), muted = Color(0xFF6E6E6B),
    faint = Color(0xFFE4E4E1), surface = Color(0xFFFFFFFF), accent = Color(0xFF0070D6), dark = false,
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

val p: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

@OptIn(ExperimentalTextApi::class)
private fun instrument(weight: Int) = Font(
    R.font.instrument_sans_variable,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Instrument = FontFamily(instrument(400), instrument(500), instrument(600))

/** Ocean sets everything in Instrument Sans at 400; size does the work, not weight. */
object Type {
    val display = TextStyle(fontFamily = Instrument, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp)
    val title = TextStyle(fontFamily = Instrument, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.3).sp)
    val heading = TextStyle(fontFamily = Instrument, fontSize = 20.sp, lineHeight = 25.sp)
    val body = TextStyle(fontFamily = Instrument, fontSize = 15.5.sp, lineHeight = 21.sp)
    val small = TextStyle(fontFamily = Instrument, fontSize = 13.sp, lineHeight = 17.sp)
    val tiny = TextStyle(fontFamily = Instrument, fontSize = 12.sp, lineHeight = 15.sp)
}

/** Ocean's shape sizes. */
object Shape {
    val radius = 26.dp
    val art = 12.dp
    val tile = 20.dp
}

@Composable
fun TideTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = (if (palette.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = palette.fg, onPrimary = palette.bg, background = palette.bg, onBackground = palette.fg,
        surface = palette.surface, onSurface = palette.fg, surfaceContainer = palette.surface,
        onSurfaceVariant = palette.muted, outline = palette.faint,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalContentColor provides palette.fg,
            LocalTextStyle provides Type.body,
            LocalTextSelectionColors provides TextSelectionColors(palette.accent, palette.accent.copy(alpha = 0.3f)),
            content = content,
        )
    }
}

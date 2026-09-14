package com.tianmaoyu.speakmate.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Teal = Color(0xFF0E9F9A)
    val TealDeep = Color(0xFF0A7A76)
    val Mint = Color(0xFF63E6BE)
    val Indigo = Color(0xFF4C6EF5)
    val Amber = Color(0xFFE8A33D)
    val Rose = Color(0xFFE5484D)
    val Ink = Color(0xFF0B1220)
    val Cloud = Color(0xFFF4F7FB)
}

private val LightColors = lightColorScheme(
    primary = Palette.Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F4F1),
    onPrimaryContainer = Color(0xFF053B39),
    secondary = Palette.Indigo,
    onSecondary = Color.White,
    background = Palette.Cloud,
    onBackground = Color(0xFF101828),
    surface = Color.White,
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFE8EEF6),
    onSurfaceVariant = Color(0xFF42546B),
    outline = Color(0xFFC3CEDD),
    error = Palette.Rose,
)

private val DarkColors = darkColorScheme(
    primary = Palette.Mint,
    onPrimary = Color(0xFF04302E),
    primaryContainer = Color(0xFF0E4A47),
    onPrimaryContainer = Color(0xFFB8F2EE),
    secondary = Color(0xFF9BAEF7),
    onSecondary = Color(0xFF14203F),
    background = Palette.Ink,
    onBackground = Color(0xFFE7EDF6),
    surface = Color(0xFF131C2E),
    onSurface = Color(0xFFE7EDF6),
    surfaceVariant = Color(0xFF1D293F),
    onSurfaceVariant = Color(0xFFAFBDD1),
    outline = Color(0xFF3A4A63),
    error = Color(0xFFFF7B7E),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun SpeakMateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

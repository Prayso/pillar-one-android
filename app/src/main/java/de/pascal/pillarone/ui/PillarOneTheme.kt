package de.pascal.pillarone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF17685C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EDE4),
    onPrimaryContainer = Color(0xFF123D34),
    background = Color(0xFFF5F7F4),
    surface = Color(0xFFF5F7F4),
    onBackground = Color(0xFF1B2924),
    onSurface = Color(0xFF1B2924),
    onSurfaceVariant = Color(0xFF53635B),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE2EAE3),
    outline = Color(0xFF718078),
    outlineVariant = Color(0xFFCED9D1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93D5BF),
    onPrimary = Color(0xFF07392E),
    primaryContainer = Color(0xFF234C40),
    onPrimaryContainer = Color(0xFFD2F0E3),
    background = Color(0xFF111916),
    surface = Color(0xFF111916),
    onBackground = Color(0xFFE2EBE4),
    onSurface = Color(0xFFE2EBE4),
    onSurfaceVariant = Color(0xFFB3C4B9),
    surfaceContainerLow = Color(0xFF1B2620),
    surfaceContainerHighest = Color(0xFF2D3A32),
    outline = Color(0xFF869A8D),
    outlineVariant = Color(0xFF405249),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp,
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun PillarOneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

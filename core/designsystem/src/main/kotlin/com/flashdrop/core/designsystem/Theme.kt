package com.flashdrop.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens from the blueprint: charcoal surfaces with a restrained violet accent. */
object FdColors {
    val Background = Color(0xFF101216)
    val Surface = Color(0xFF191D24)
    val Elevated = Color(0xFF232936)
    val Border = Color(0xFF2B3240)
    val Accent = Color(0xFFA78BFA)
    val OnAccent = Color(0xFF17112B)
    val AccentSoft = Color(0x29A78BFA)
    val TextPrimary = Color(0xFFF1F3F8)
    val TextSecondary = Color(0xFF969EAD)
    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFF87171)
}

private val scheme = darkColorScheme(
    primary = FdColors.Accent,
    onPrimary = FdColors.OnAccent,
    primaryContainer = FdColors.AccentSoft,
    onPrimaryContainer = FdColors.Accent,
    secondary = FdColors.Accent,
    onSecondary = FdColors.OnAccent,
    background = FdColors.Background,
    onBackground = FdColors.TextPrimary,
    surface = FdColors.Surface,
    onSurface = FdColors.TextPrimary,
    surfaceVariant = FdColors.Elevated,
    onSurfaceVariant = FdColors.TextSecondary,
    surfaceContainer = FdColors.Surface,
    surfaceContainerHigh = FdColors.Elevated,
    surfaceContainerHighest = FdColors.Elevated,
    surfaceContainerLow = FdColors.Surface,
    outline = FdColors.Border,
    outlineVariant = FdColors.Border,
    error = FdColors.Error,
    onError = Color(0xFF2B0B0B),
)

private val typography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    )
}

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** FlashDrop is dark-first: the dark scheme is used regardless of the system setting. */
@Composable
fun FlashDropTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}

package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val ExpressiveLightColorScheme = lightColorScheme(
    primary = CobaltPrimaryLight,
    onPrimary = OnCobaltLight,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = OnCobaltContainerLight,
    secondary = CyanSecondaryLight,
    onSecondary = OnCyanLight,
    secondaryContainer = CyanContainerLight,
    onSecondaryContainer = OnCyanContainerLight,
    tertiary = MagentaTertiaryLight,
    onTertiary = OnMagentaLight,
    tertiaryContainer = MagentaContainerLight,
    onTertiaryContainer = OnMagentaContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = PaperBackgroundLight,
    onBackground = InkOnPaperLight,
    surface = PaperSurfaceLight,
    onSurface = InkOnPaperLight,
    surfaceVariant = PaperSurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    surfaceContainerLowest = PaperSurfaceContainerLowestLight,
    surfaceContainerLow = PaperSurfaceContainerLowLight,
    surfaceContainer = PaperSurfaceContainerLight,
    surfaceContainerHigh = PaperSurfaceContainerHighLight,
    surfaceContainerHighest = PaperSurfaceContainerHighestLight
)

private val ExpressiveDarkColorScheme = darkColorScheme(
    primary = CobaltPrimaryDark,
    onPrimary = OnCobaltDark,
    primaryContainer = CobaltContainerDark,
    onPrimaryContainer = OnCobaltContainerDark,
    secondary = CyanSecondaryDark,
    onSecondary = OnCyanDark,
    secondaryContainer = CyanContainerDark,
    onSecondaryContainer = OnCyanContainerDark,
    tertiary = MagentaTertiaryDark,
    onTertiary = OnMagentaDark,
    tertiaryContainer = MagentaContainerDark,
    onTertiaryContainer = OnMagentaContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = SlateBackgroundDark,
    onBackground = SlateOnBackgroundDark,
    surface = SlateSurfaceDark,
    onSurface = SlateOnBackgroundDark,
    surfaceVariant = SlateSurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    surfaceContainerLowest = SlateSurfaceContainerLowestDark,
    surfaceContainerLow = SlateSurfaceContainerLowDark,
    surfaceContainer = SlateSurfaceContainerDark,
    surfaceContainerHigh = SlateSurfaceContainerHighDark,
    surfaceContainerHighest = SlateSurfaceContainerHighestDark
)

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp)
)

@Immutable
data class InkSemanticColors(
    val cyan: Color = InkCyan,
    val magenta: Color = InkMagenta,
    val yellow: Color = InkYellow,
    val black: Color = InkBlack,
    val online: Color = StatusOnlineGreen,
    val paused: Color = StatusPausedAmber,
    val blocked: Color = StatusBlockedRed,
    val sending: Color = StatusSendingCobalt
)

val LocalInkColors = staticCompositionLocalOf { InkSemanticColors() }

@Composable
fun InkFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ExpressiveDarkColorScheme
        else -> ExpressiveLightColorScheme
    }

    val inkColors = InkSemanticColors(
        black = if (darkTheme) Color(0xFF475569) else InkBlack
    )

    CompositionLocalProvider(LocalInkColors provides inkColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = ExpressiveShapes,
            content = content
        )
    }
}

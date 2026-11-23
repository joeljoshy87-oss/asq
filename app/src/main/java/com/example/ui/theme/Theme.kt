package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class SubPulseSignalColors(
    val kick: Color = KickCoral,
    val kickBright: Color = KickCoralBright,
    val kickContainer: Color = KickCoralContainer,
    val kickOnContainer: Color = KickCoralOnContainer,
    val bass: Color = BassCyan,
    val bassDeep: Color = BassCyanDeep,
    val bassContainer: Color = BassCyanContainer,
    val bassOnContainer: Color = BassCyanOnContainer,
    val actuator: Color = ActuatorAmber,
    val actuatorContainer: Color = ActuatorAmberContainer,
    val actuatorOnContainer: Color = ActuatorAmberOnContainer,
    val liveGreen: Color = SignalEmerald,
    val liveGreenContainer: Color = SignalEmeraldContainer,
    val surfaceGlass: Color = ObsidianSurfaceElevated,
    val hairline: Color = ObsidianOutlineSubtle
)

val LocalSubPulseColors = staticCompositionLocalOf { SubPulseSignalColors() }

private val SubPulseDarkColorScheme = darkColorScheme(
    primary = BassCyan,
    onPrimary = ObsidianVoid,
    primaryContainer = BassCyanContainer,
    onPrimaryContainer = BassCyanOnContainer,
    secondary = KickCoral,
    onSecondary = ObsidianVoid,
    secondaryContainer = KickCoralContainer,
    onSecondaryContainer = KickCoralOnContainer,
    tertiary = ActuatorAmber,
    onTertiary = ObsidianVoid,
    tertiaryContainer = ActuatorAmberContainer,
    onTertiaryContainer = ActuatorAmberOnContainer,
    background = ObsidianVoid,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianVoid,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceElevated,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    outline = ObsidianOutline,
    outlineVariant = ObsidianOutlineSubtle,
    error = ErrorCrimson,
    errorContainer = ErrorContainerDark,
    onError = ObsidianVoid,
    onErrorContainer = Color(0xFFFFDAD9)
)

@Composable
fun SubPulseTheme(
    content: @Composable () -> Unit
) {
    val signalColors = SubPulseSignalColors()
    CompositionLocalProvider(LocalSubPulseColors provides signalColors) {
        MaterialTheme(
            colorScheme = SubPulseDarkColorScheme,
            typography = SubPulseTypography,
            content = content
        )
    }
}

package com.lybvinci.videoutils.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.lybvinci.videoutils.settings.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = Neutral100,
    onPrimary = Neutral10,
    primaryContainer = Neutral20,
    onPrimaryContainer = Neutral100,
    secondary = Neutral90,
    onSecondary = Neutral10,
    secondaryContainer = Neutral30,
    onSecondaryContainer = Neutral100,
    background = Neutral0,
    onBackground = Neutral100,
    surface = Neutral10,
    onSurface = Neutral100,
    surfaceVariant = Neutral20,
    onSurfaceVariant = Neutral80,
    outline = Neutral60,
)

private val LightColorScheme = lightColorScheme(
    primary = Neutral10,
    onPrimary = Neutral100,
    primaryContainer = Neutral95,
    onPrimaryContainer = Neutral10,
    secondary = Neutral30,
    onSecondary = Neutral100,
    secondaryContainer = Neutral90,
    onSecondaryContainer = Neutral20,
    background = Neutral100,
    onBackground = Neutral10,
    surface = Neutral100,
    onSurface = Neutral10,
    surfaceVariant = Neutral95,
    onSurfaceVariant = Neutral40,
    outline = Neutral80,
)

@Composable
fun VideoUtilsTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themeMode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

package com.laksh.forge.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkScheme = darkColorScheme(
    primary = Ember,
    onPrimary = Color.White,
    primaryContainer = EmberDim,
    onPrimaryContainer = Color(0xFFFFE2DA),
    secondary = Protein,
    onSecondary = Color(0xFF00201C),
    tertiary = Fiber,
    onTertiary = Color.White,
    background = Ink1,
    onBackground = TextHi,
    surface = Ink2,
    onSurface = TextHi,
    surfaceVariant = Ink3,
    onSurfaceVariant = TextMid,
    surfaceContainer = Ink2,
    surfaceContainerHigh = Ink3,
    surfaceContainerHighest = Ink4,
    surfaceContainerLow = Ink1,
    surfaceContainerLowest = Ink0,
    outline = Color(0xFF3A424E),
    outlineVariant = Color(0xFF262D37),
    error = Negative,
    onError = Color.White
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFCF4A2C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD1),
    onPrimaryContainer = Color(0xFF3F0A00),
    secondary = Color(0xFF0F8577),
    onSecondary = Color.White,
    tertiary = Color(0xFF5A48D6),
    onTertiary = Color.White,
    background = Paper0,
    onBackground = TextHiLight,
    surface = Paper1,
    onSurface = TextHiLight,
    surfaceVariant = Paper2,
    onSurfaceVariant = TextMidLight,
    surfaceContainer = Paper2,
    surfaceContainerHigh = Paper3,
    surfaceContainerHighest = Color(0xFFD6D6D2),
    surfaceContainerLow = Paper1,
    surfaceContainerLowest = Color.White,
    outline = Color(0xFFB4B8BF),
    outlineVariant = Color(0xFFD9DCE1),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

/** Macro colors are kept outside the M3 scheme so they stay stable across themes. */
object Macro {
    val protein = Protein
    val carbs = Carbs
    val fat = Fat
    val fiber = Fiber
}

@Composable
fun ForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = scheme, typography = ForgeType, content = content)
}

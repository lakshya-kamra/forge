package com.laksh.forge.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf

// ------------------------------------------------------------------ brand schemes

private val LightScheme = lightColorScheme(
    primary = EmberDeep,
    onPrimary = Color.White,
    primaryContainer = EmberContainerLight,
    onPrimaryContainer = OnEmberContainerLight,
    inversePrimary = Color(0xFFFFB4A1),
    secondary = TealDeep,
    onSecondary = Color.White,
    secondaryContainer = TealContainerLight,
    onSecondaryContainer = OnTealContainerLight,
    tertiary = VioletDeep,
    onTertiary = Color.White,
    tertiaryContainer = VioletContainerLight,
    onTertiaryContainer = OnVioletContainerLight,
    background = Mist0,
    onBackground = TextHiLight,
    surface = Mist0,
    onSurface = TextHiLight,
    surfaceVariant = Color(0xFFE1E5EA),
    onSurfaceVariant = TextMidLight,
    surfaceTint = EmberDeep,
    inverseSurface = Color(0xFF2B3036),
    inverseOnSurface = Mist0,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7A8391),
    outlineVariant = Color(0xFFD5D9DF),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFAFBFC),
    surfaceDim = Color(0xFFD8DCE1),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Mist1,
    surfaceContainer = Mist2,
    surfaceContainerHigh = Mist3,
    surfaceContainerHighest = Mist4
)

private val DarkScheme = darkColorScheme(
    primary = Ember,
    onPrimary = OnEmberDark,
    primaryContainer = EmberContainerDark,
    onPrimaryContainer = OnEmberContainerDark,
    inversePrimary = EmberDeep,
    secondary = Protein,
    onSecondary = Color(0xFF00201C),
    secondaryContainer = TealContainerDark,
    onSecondaryContainer = OnTealContainerDark,
    tertiary = Fiber,
    onTertiary = Color(0xFF1F0F5C),
    tertiaryContainer = VioletContainerDark,
    onTertiaryContainer = OnVioletContainerDark,
    background = Ink1,
    onBackground = TextHi,
    surface = Ink1,
    onSurface = TextHi,
    surfaceVariant = Ink3,
    onSurfaceVariant = TextMid,
    surfaceTint = Ember,
    inverseSurface = Color(0xFFE2E6EC),
    inverseOnSurface = Ink3,
    error = Negative,
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF586170),
    outlineVariant = Color(0xFF2A313B),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2E3540),
    surfaceDim = Ink1,
    surfaceContainerLowest = Ink0,
    surfaceContainerLow = Color(0xFF101318),
    surfaceContainer = Ink2,
    surfaceContainerHigh = Ink3,
    surfaceContainerHighest = Ink4
)

/**
 * AMOLED: the page and every "lowest" surface go to true black so the panel switches those
 * pixels off; containers keep a faint lift so cards still read as cards.
 */
private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Amoled0,
    surface = Amoled0,
    surfaceDim = Amoled0,
    surfaceContainerLowest = Amoled0,
    surfaceContainerLow = Amoled1,
    surfaceContainer = Amoled2,
    surfaceContainerHigh = Amoled3,
    surfaceContainerHighest = Amoled4,
    inverseOnSurface = Amoled3
)

/** Rounder than Material's defaults: Expressive leans on generous corners. */
val ForgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/** Macro colors are kept outside the M3 scheme so they stay stable across themes. */
object Macro {
    val protein = Protein
    val carbs = Carbs
    val fat = Fat
    val fiber = Fiber
}

// ------------------------------------------------------------------ appearance

/** Everything that changes how the app looks, resolved to plain values. */
@Immutable
data class ForgeAppearance(
    val dark: Boolean,
    val dynamicColor: Boolean = false,
    val amoledBlack: Boolean = false,
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true
)

/**
 * The live settings snapshot, provided by [ForgeTheme] so the shell (and anything else that
 * needs a cheap synchronous read) does not open a second DataStore collection. Null until
 * the first read completes.
 */
val LocalForgeSettings = compositionLocalOf<ForgeSettings?> { null }

/** True when the user asked for less motion: decorative animation should be skipped. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Whether haptics are on. [LocalHapticFeedback] is already gated by [ForgeTheme]; this exists
 * for the rare call that has to go through `View.performHapticFeedback` for a constant Compose
 * does not expose.
 */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * App theme. Reads the appearance settings itself, so callers only wrap content.
 *
 * [darkTheme] forces light or dark (screenshots, previews); null follows Settings ->
 * Appearance, which in turn may follow the system.
 */
@Composable
fun ForgeTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val settingsFlow = remember { settingsFlowOrDefault() }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val s = settings
    val systemDark = isSystemInDarkTheme()
    val dark = darkTheme ?: when (s?.themeMode ?: ThemeMode.SYSTEM) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val appearance = ForgeAppearance(
        dark = dark,
        dynamicColor = s?.dynamicColor ?: false,
        amoledBlack = s?.amoledBlack ?: false,
        reduceMotion = s?.reduceMotion ?: false,
        haptics = s?.haptics ?: true
    )

    SystemBarAppearance(dark)

    CompositionLocalProvider(LocalForgeSettings provides s) {
        ForgeAppearanceTheme(appearance = appearance, content = content)
    }
}

/**
 * Stateless theme for an explicit [appearance]. Used by [ForgeTheme], the appearance preview
 * thumbnails and screenshot tests (which must not touch DataStore).
 */
@Composable
fun ForgeAppearanceTheme(
    appearance: ForgeAppearance,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = remember(appearance.dark, appearance.dynamicColor, appearance.amoledBlack, context) {
        resolveScheme(context, appearance)
    }
    val motion = remember(appearance.reduceMotion) {
        if (appearance.reduceMotion) MotionScheme.standard() else MotionScheme.expressive()
    }
    val platformHaptics = LocalHapticFeedback.current
    val haptics = remember(platformHaptics, appearance.haptics) {
        GatedHapticFeedback(platformHaptics, appearance.haptics)
    }

    CompositionLocalProvider(
        LocalHapticFeedback provides haptics,
        LocalHapticsEnabled provides appearance.haptics,
        LocalReduceMotion provides appearance.reduceMotion
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = motion,
            shapes = ForgeShapes,
            typography = ForgeType,
            content = content
        )
    }
}

private fun resolveScheme(context: Context, a: ForgeAppearance): ColorScheme {
    // Some OEM ROMs throw from the Monet APIs; fall back to the brand scheme instead of
    // crashing the whole app over a cosmetic preference.
    val dynamic = if (a.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            if (a.dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }.getOrNull()
    } else null
    val base = dynamic ?: if (a.dark) DarkScheme else LightScheme
    return if (a.dark && a.amoledBlack) base.amoled() else base
}

private fun settingsFlowOrDefault(): Flow<ForgeSettings> =
    runCatching { ForgeApp.instance.repository.settingsStore.settings }
        .getOrNull()
        // A corrupt preferences file must cost the user their theme, not the app.
        ?.catch { emit(ForgeSettings()) }
        ?: flowOf(ForgeSettings())

/** Status/navigation bar icons follow the in-app theme, not only the system one. */
@Composable
private fun SystemBarAppearance(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Swallows haptics when the user turned them off, so every caller respects the setting. */
private class GatedHapticFeedback(
    private val delegate: HapticFeedback,
    private val enabled: Boolean
) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled) delegate.performHapticFeedback(hapticFeedbackType)
    }
}

// ------------------------------------------------------------------ scheme helpers

/** True for dark schemes, including dynamic ones (decided by the surface, not a flag). */
val ColorScheme.isDark: Boolean get() = surface.luminance() < 0.5f

/** Page background behind cards. */
val ColorScheme.appBackground: Color get() = background

/** Card fill: white on the grey light page, a faint lift on the dark page. */
val ColorScheme.cardContainer: Color
    get() = if (isDark) surfaceContainer else surfaceContainerLowest

/** Brand and macro colours are tuned for dark; pull them toward ink on light pages. */
fun ColorScheme.accentFor(source: Color): Color =
    if (isDark) source else lerp(source, Color.Black, 0.18f)

/** A soft container tinted with [source], for chips and icon badges. */
fun ColorScheme.tintedContainer(source: Color, amount: Float = 0.16f): Color =
    lerp(surfaceContainerHigh, source, if (isDark) amount + 0.06f else amount)

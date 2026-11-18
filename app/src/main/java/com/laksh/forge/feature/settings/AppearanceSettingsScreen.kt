package com.laksh.forge.feature.settings

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.ThemeMode
import com.laksh.forge.ui.components.ErrorNotice
import com.laksh.forge.ui.components.ForgeSubScreen
import com.laksh.forge.ui.components.LocalForgeSnackbar
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.components.SegmentedSwitchRow
import com.laksh.forge.ui.components.ShimmerCard
import com.laksh.forge.ui.components.bounceClick
import com.laksh.forge.ui.theme.ForgeAppearance
import com.laksh.forge.ui.theme.ForgeAppearanceTheme
import com.laksh.forge.ui.theme.Macro
import com.laksh.forge.ui.theme.appBackground
import com.laksh.forge.ui.theme.cardContainer
import com.laksh.forge.ui.theme.isDark

/**
 * Appearance. Every switch applies instantly to the whole app (ForgeTheme reads these
 * settings live), and the three thumbnails render a miniature Forge in each theme with the
 * current colour options, so the choice is made by looking rather than by reading.
 */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val editError by vm.editError.collectAsStateWithLifecycle()
    val snackbar = LocalForgeSnackbar.current

    LaunchedEffect(editError) {
        val msg = editError ?: return@LaunchedEffect
        snackbar?.showSnackbar(msg)
        vm.clearEditError()
    }

    ForgeSubScreen(title = "Appearance", onBack = onBack) { padding ->
        AppearanceContent(
            state = state,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
            onThemeMode = vm::setThemeMode,
            onDynamicColor = vm::setDynamicColor,
            onAmoled = vm::setAmoledBlack,
            onHaptics = vm::setHaptics,
            onReduceMotion = vm::setReduceMotion
        )
    }
}

@Composable
private fun AppearanceContent(
    state: SettingsUiState,
    contentPadding: PaddingValues,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAmoled: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onReduceMotion: (Boolean) -> Unit
) {
    val s = state.settings
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        if (state.error != null) {
            item(key = "error", contentType = "error") { ErrorNotice(state.error) }
            return@LazyColumn
        }
        if (state.loading) {
            item(key = "loading", contentType = "loading") { ShimmerCard(lines = 4) }
            return@LazyColumn
        }

        item(key = "theme_header", contentType = "section") { SectionHeader("Theme") }
        item(key = "theme_previews", contentType = "previews") {
            ThemePreviews(settings = s, onSelect = onThemeMode)
        }

        item(key = "colour_header", contentType = "section") { SectionHeader("Colour") }
        item(key = "dynamic", contentType = "switch_row") {
            SegmentedSwitchRow(
                index = 0, count = 2,
                title = "Dynamic colour",
                supporting = if (dynamicAvailable) "Take the accent colours from your wallpaper instead of Forge's ember"
                else "Needs Android 12 or newer",
                checked = s.dynamicColor && dynamicAvailable,
                onCheckedChange = onDynamicColor,
                enabled = dynamicAvailable,
                icon = Icons.Outlined.Palette
            )
        }
        item(key = "amoled", contentType = "switch_row") {
            SegmentedSwitchRow(
                index = 1, count = 2,
                title = "AMOLED black",
                supporting = "Pure black backgrounds in dark mode; the panel switches those pixels off",
                checked = s.amoledBlack,
                onCheckedChange = onAmoled,
                icon = Icons.Outlined.Contrast
            )
        }

        item(key = "feel_header", contentType = "section") { SectionHeader("Feel") }
        item(key = "haptics", contentType = "switch_row") {
            SegmentedSwitchRow(
                index = 0, count = 2,
                title = "Haptics",
                supporting = "Subtle vibrations when you log, switch tabs or cross a swipe threshold",
                checked = s.haptics,
                onCheckedChange = onHaptics,
                icon = Icons.Outlined.Vibration
            )
        }
        item(key = "motion", contentType = "switch_row") {
            SegmentedSwitchRow(
                index = 1, count = 2,
                title = "Reduce motion",
                supporting = "Calmer springs, cross-fades between screens, no decorative animation",
                checked = s.reduceMotion,
                onCheckedChange = onReduceMotion,
                icon = Icons.Outlined.Animation
            )
        }
    }
}

// ------------------------------------------------------------------ live previews

@Composable
private fun ThemePreviews(settings: ForgeSettings, onSelect: (ThemeMode) -> Unit) {
    val systemDark = isSystemInDarkTheme()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ThemeMode.entries.forEach { mode ->
            val dark = when (mode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            ThemeThumb(
                label = when (mode) {
                    ThemeMode.SYSTEM -> "System"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                },
                appearance = ForgeAppearance(
                    dark = dark,
                    dynamicColor = settings.dynamicColor,
                    amoledBlack = settings.amoledBlack,
                    reduceMotion = settings.reduceMotion,
                    haptics = settings.haptics
                ),
                selected = settings.themeMode == mode,
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private val ThumbShape = RoundedCornerShape(22.dp)

@Composable
private fun ThemeThumb(
    label: String,
    appearance: ForgeAppearance,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outlineSelected = MaterialTheme.colorScheme.primary
    val outlineIdle = MaterialTheme.colorScheme.outlineVariant
    val scale = animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
        label = "thumbScale"
    )

    Column(
        modifier.semantics {
            this.selected = selected
            contentDescription = "$label theme"
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .clip(ThumbShape)
                .bounceClick(onClick = onClick, haptic = HapticFeedbackType.SegmentTick, role = Role.RadioButton)
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) outlineSelected else outlineIdle,
                    shape = ThumbShape
                )
        ) {
            ForgeAppearanceTheme(appearance) { MiniForge() }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(outlineSelected),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A miniature Today: header, ring card, a meal card and the floating bar. Static, no data. */
@Composable
private fun MiniForge() {
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onSurface
    val mutedInk = scheme.onSurfaceVariant.copy(alpha = 0.5f)
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.56f)
            .background(scheme.appBackground)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .padding(top = 6.dp, start = 2.dp)
                .width(34.dp)
                .height(7.dp)
                .clip(CircleShape)
                .background(ink)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.cardContainer)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val track = scheme.surfaceContainerHighest
            val primary = scheme.primary
            Canvas(Modifier.size(44.dp)) {
                val stroke = 5.dp.toPx()
                val arc = Size(size.width - stroke, size.height - stroke)
                val tl = Offset(stroke / 2, stroke / 2)
                drawArc(track, 135f, 270f, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(primary, 135f, 170f, false, tl, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Spacer(Modifier.height(6.dp))
            MiniBar(Macro.protein, 0.7f)
            Spacer(Modifier.height(3.dp))
            MiniBar(Macro.carbs, 0.45f)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.cardContainer)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(scheme.secondaryContainer)
            )
            Spacer(Modifier.width(5.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(mutedInk)
            )
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(16.dp)
                    .clip(CircleShape)
                    .background(if (scheme.isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(width = 12.dp, height = 7.dp)
                        .clip(CircleShape)
                        .background(scheme.primaryContainer)
                )
                repeat(3) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(mutedInk)
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(16.dp)
                    .clip(MaterialShapes.Cookie4Sided.toShape())
                    .background(scheme.primary)
            )
        }
    }
}

@Composable
private fun MiniBar(color: androidx.compose.ui.graphics.Color, fraction: Float) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

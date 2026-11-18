package com.laksh.forge.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.BuildConfig
import com.laksh.forge.Routes
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.ThemeMode
import com.laksh.forge.ui.components.ErrorNotice
import com.laksh.forge.ui.components.ExpressiveLoading
import com.laksh.forge.ui.components.IconBadge
import com.laksh.forge.ui.components.LocalFloatingBarClearance
import com.laksh.forge.ui.components.LocalForgeSnackbar
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.components.SegmentedContentRow
import com.laksh.forge.ui.components.SegmentedNavRow
import com.laksh.forge.ui.components.SegmentedSwitchRow
import com.laksh.forge.ui.components.ShimmerCard
import com.laksh.forge.ui.components.segmentGap
import com.laksh.forge.ui.theme.Macro
import com.laksh.forge.ui.theme.accentFor
import com.laksh.forge.ui.theme.cardContainer
import kotlin.math.roundToInt

/**
 * Settings hub. Each feature owns its own settings screen; this is the map to them, plus the
 * two food-logging options that are too small to deserve a screen, and About.
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
    onNavigate: (String) -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editError by vm.editError.collectAsStateWithLifecycle()
    val snackbar = LocalForgeSnackbar.current
    val context = LocalContext.current

    LaunchedEffect(editError) {
        val msg = editError ?: return@LaunchedEffect
        snackbar?.showSnackbar(msg)
        vm.clearEditError()
    }

    SettingsContent(
        state = state,
        onNavigate = onNavigate,
        onSafetyMargin = vm::setSafetyMargin,
        onStorePhotos = vm::setStorePhotos,
        onOpenLicence = vm::openLicence
    )

    state.licence?.let { lic ->
        LicenceDialog(
            view = lic,
            onDismiss = vm::closeLicence,
            onOpenSource = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lic.notice.url)))
                } catch (e: ActivityNotFoundException) {
                    // No browser installed; the URL is shown in the dialog text anyway.
                }
            }
        )
    }
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    onNavigate: (String) -> Unit,
    onSafetyMargin: (Int) -> Unit,
    onStorePhotos: (Boolean) -> Unit,
    onOpenLicence: (OpenSourceNotice) -> Unit,
    modifier: Modifier = Modifier
) {
    val clearance = LocalFloatingBarClearance.current
    val s = state.settings

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = clearance + 24.dp)
    ) {
        item(key = "title", contentType = "title") {
            Text(
                "Settings",
                style = MaterialTheme.typography.displaySmallEmphasized,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
        }

        if (state.error != null) {
            item(key = "error", contentType = "error") { ErrorNotice(state.error) }
            return@LazyColumn
        }
        if (state.loading) {
            item(key = "loading", contentType = "loading") { ShimmerCard(lines = 5) }
            return@LazyColumn
        }

        group("you", "You") {
            listOf<@Composable (Int, Int) -> Unit>(
                { i, n ->
                    SegmentedNavRow(
                        index = i, count = n,
                        title = "Profile & goals",
                        supporting = "${s.goal.label} · ${s.age} years · ${s.weightUnit.label}",
                        icon = Icons.Outlined.Person,
                        onClick = { onNavigate(Routes.SETTINGS_PROFILE) }
                    )
                },
                { i, n ->
                    SegmentedNavRow(
                        index = i, count = n,
                        title = "Progress",
                        supporting = "Measurements, photos and check-ins",
                        icon = Icons.Outlined.Timeline,
                        iconTint = MaterialTheme.colorScheme.accentFor(Macro.protein),
                        onClick = { onNavigate(Routes.PROGRESS) }
                    )
                }
            )
        }

        group("training", "Training") {
            listOf<@Composable (Int, Int) -> Unit>(
                { i, n ->
                    SegmentedNavRow(
                        index = i, count = n,
                        title = "Exercise library",
                        supporting = "Browse exercises, cues and the muscles they train",
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        iconTint = MaterialTheme.colorScheme.accentFor(Macro.fiber),
                        onClick = { onNavigate(Routes.EXERCISES) }
                    )
                },
                { i, n ->
                    SegmentedNavRow(
                        index = i, count = n,
                        title = "Tools",
                        supporting = "Plate math, one-rep max and warm-up sets",
                        icon = Icons.Outlined.Build,
                        iconTint = MaterialTheme.colorScheme.accentFor(Macro.carbs),
                        onClick = { onNavigate(Routes.TOOLS) }
                    )
                }
            )
        }

        group("connections", "Connections") {
            listOf<@Composable (Int, Int) -> Unit>(
                { i, n ->
                    SegmentedNavRow(
                        index = i, count = n,
                        title = "AI provider",
                        supporting = if (s.hasApiKey) "Connected - photo estimates, labels and the coach are on"
                        else "Add a key to turn on photo estimates and the coach",
                        icon = Icons.Outlined.AutoAwesome,
                        onClick = { onNavigate(Routes.SETTINGS_AI) }
                    )
                },
                { i, n ->
                    SegmentedNavRow(
                        index = i, count = n,
                        title = "Wearables",
                        supporting = wearableSummary(s),
                        icon = Icons.Outlined.Watch,
                        iconTint = MaterialTheme.colorScheme.accentFor(Macro.protein),
                        onClick = { onNavigate(Routes.WEARABLES) }
                    )
                }
            )
        }

        item(key = "food_header", contentType = "section") { SectionHeader("Food logging") }
        item(key = "food_margin", contentType = "slider_row") {
            SafetyMarginRow(s.safetyMarginPct, onSafetyMargin, index = 0, count = 3)
        }
        item(key = "food_photos", contentType = "switch_row") {
            SegmentedSwitchRow(
                index = 1, count = 3,
                title = "Keep meal photos",
                supporting = "Stored in this app's private storage on the phone only",
                checked = s.storePhotos,
                onCheckedChange = onStorePhotos,
                icon = Icons.Outlined.PhotoLibrary
            )
        }
        item(key = "food_privacy", contentType = "info_row") {
            SegmentedContentRow(
                index = 2, count = 3,
                title = "What happens to your photos",
                supporting = "A meal or label photo is sent to the AI provider you set up, only to produce the estimate; " +
                    "how long the provider keeps API traffic depends on its own data policy. " +
                    "With \"Keep meal photos\" off, Forge analyses the image and discards it instead of writing it to disk. " +
                    "Barcode scanning is fully on-device - only the digits are looked up.",
                icon = Icons.Outlined.Shield
            )
        }

        group("app", "App") {
            listOf<@Composable (Int, Int) -> Unit>(
                { i, n ->
                    SegmentedNavRow(
                        index = i, count = n,
                        title = "Appearance",
                        supporting = appearanceSummary(s),
                        icon = Icons.Outlined.Palette,
                        onClick = { onNavigate(Routes.SETTINGS_APPEARANCE) }
                    )
                },
                { i, n ->
                    SegmentedNavRow(
                        index = i, count = n,
                        title = "Data & backup",
                        supporting = "Back up, restore and export your data",
                        icon = Icons.Outlined.Backup,
                        iconTint = MaterialTheme.colorScheme.accentFor(Macro.fiber),
                        onClick = { onNavigate(Routes.SETTINGS_DATA) }
                    )
                }
            )
        }

        item(key = "about_header", contentType = "section") { SectionHeader("About") }
        item(key = "about_version", contentType = "info_row") {
            SegmentedContentRow(
                index = 0, count = 3,
                title = "Forge ${BuildConfig.VERSION_NAME}",
                supporting = "General fitness guidance, not medical advice.",
                icon = Icons.Outlined.Info
            )
        }
        OpenSourceNotice.entries.forEachIndexed { i, notice ->
            item(key = "licence_${notice.name}", contentType = "nav_row") {
                SegmentedNavRow(
                    index = i + 1, count = OpenSourceNotice.entries.size + 1,
                    title = notice.title,
                    supporting = notice.licence,
                    icon = if (notice == OpenSourceNotice.GOOGLE_SANS_FLEX) Icons.Outlined.TextFields
                    else Icons.AutoMirrored.Outlined.MenuBook,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { onOpenLicence(notice) }
                )
            }
        }
    }
}

/** Adds a titled group of segmented rows; each row lambda receives (index, count). */
private fun LazyListScope.group(
    key: String,
    title: String,
    rows: () -> List<@Composable (Int, Int) -> Unit>
) {
    val list = rows()
    item(key = "${key}_header", contentType = "section") { SectionHeader(title) }
    list.forEachIndexed { i, row ->
        item(key = "${key}_$i", contentType = "nav_row") { row(i, list.size) }
    }
}

private fun wearableSummary(s: ForgeSettings): String {
    val sources = buildList {
        if (s.healthConnectEnabled) add("Health Connect")
        if (s.whoopConnected) add("WHOOP")
    }
    return if (sources.isEmpty()) "Not connected" else "Connected: ${sources.joinToString(" · ")}"
}

private fun appearanceSummary(s: ForgeSettings): String = buildList {
    add(
        when (s.themeMode) {
            ThemeMode.SYSTEM -> "System theme"
            ThemeMode.LIGHT -> "Light"
            ThemeMode.DARK -> "Dark"
        }
    )
    if (s.dynamicColor) add("Dynamic colour")
    if (s.amoledBlack) add("AMOLED black")
    if (s.reduceMotion) add("Reduced motion")
}.joinToString(" · ")

/**
 * The slider moves a local draft and writes once on release: writing DataStore on every
 * drag frame would queue dozens of disk writes for one gesture.
 */
@Composable
private fun SafetyMarginRow(value: Int, onCommit: (Int) -> Unit, index: Int, count: Int) {
    val haptics = LocalHapticFeedback.current
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        modifier = Modifier.segmentGap(index, count),
        leadingContent = { IconBadge(Icons.Outlined.PhotoCamera) },
        supportingContent = {
            Column {
                Slider(
                    value = draft,
                    onValueChange = { v ->
                        if (v.roundToInt() != draft.roundToInt()) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        }
                        draft = v
                    },
                    valueRange = 0f..40f,
                    steps = 39,
                    onValueChangeFinished = { onCommit(draft.roundToInt()) }
                )
                Text(
                    "Added on top of photo estimates, which already lean high by design. Protein is never padded - only calories, carbs and fat.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.cardContainer)
    ) {
        Text(
            "Safety margin: ${draft.roundToInt()}%",
            style = MaterialTheme.typography.bodyLargeEmphasized
        )
    }
}

@Composable
private fun LicenceDialog(
    view: LicenceView,
    onDismiss: () -> Unit,
    onOpenSource: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(view.notice.title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(view.notice.summary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Licence: ${view.notice.licence}",
                    style = MaterialTheme.typography.labelLargeEmphasized
                )
                Text(
                    view.notice.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                when {
                    view.loading -> ExpressiveLoading(Modifier.fillMaxWidth(), size = 40.dp)
                    view.error != null -> Text(
                        view.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    view.text != null -> {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            view.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onOpenSource, shapes = ButtonDefaults.shapes()) { Text("Open source page") }
        }
    )
}

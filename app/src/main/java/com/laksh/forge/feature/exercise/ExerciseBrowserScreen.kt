package com.laksh.forge.feature.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.exercise.ExerciseInfo
import com.laksh.forge.domain.exercise.ExerciseLabels
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ErrorNotice
import com.laksh.forge.ui.components.ExpressiveLoading
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.ForgeSubScreen
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.skeleton.SkeletonThumbnail
import com.laksh.forge.ui.theme.cardContainer

/** The exercise library: search, muscle and equipment filters, and a still of each lift. */
@Composable
fun ExerciseBrowserScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val vm: ExerciseBrowserViewModel = viewModel(factory = ExerciseBrowserViewModel.factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The field owns its text so typing never round-trips through the view model's flow,
    // which would move the cursor; the view model just mirrors it.
    var text by rememberSaveable { mutableStateOf(state.query) }

    ForgeSubScreen(
        title = "Exercises",
        subtitle = if (state.total > 0) "${state.total} lifts with demos and tutorials" else null,
        onBack = onBack
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { ExpressiveLoading(message = "Opening the exercise library") }

            state.total == 0 && state.error != null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Library unavailable",
                    body = state.error ?: "",
                    action = { Pill("Try again", filled = true, onClick = vm::load) }
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "search", contentType = "search") {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            vm.setQuery(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search: \"RDL\", \"lat pulldown\", \"incline DB\"") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (text.isNotEmpty()) {
                                IconButton(onClick = {
                                    text = ""
                                    vm.setQuery("")
                                }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.cardContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.cardContainer
                        )
                    )
                }
                state.error?.let { err ->
                    item(key = "error", contentType = "error") { ErrorNotice(err, onRetry = vm::load) }
                }
                item(key = "muscles", contentType = "chips") {
                    ChipRow(
                        options = state.muscles,
                        selected = state.muscle,
                        allLabel = "All muscles",
                        label = ExerciseLabels::muscle,
                        onSelect = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            vm.setMuscle(it)
                        }
                    )
                }
                item(key = "equipment", contentType = "chips") {
                    ChipRow(
                        options = state.equipmentOptions,
                        selected = state.equipment,
                        allLabel = "Any equipment",
                        label = ExerciseLabels::equipment,
                        onSelect = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            vm.setEquipment(it)
                        }
                    )
                }

                if (state.results.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.SearchOff,
                            title = "No matching lifts",
                            body = "Try another name or loosen the filters.",
                            modifier = Modifier.animateItem(),
                            action = {
                                Pill("Clear filters", filled = true, onClick = {
                                    text = ""
                                    vm.clearFilters()
                                })
                            }
                        )
                    }
                } else {
                    items(state.results, key = { it.id }, contentType = { "exercise" }) { ex ->
                        ExerciseRow(ex, onClick = { onOpen(ex.id) }, modifier = Modifier.animateItem())
                    }
                }

                if (state.unmatchedLogged.isNotEmpty() && state.muscle == null && state.equipment == null) {
                    item(key = "logged-h", contentType = "header") { SectionHeader("From your logs") }
                    items(state.unmatchedLogged, key = { "logged-$it" }, contentType = { "logged" }) { name ->
                        LoggedRow(name, onClick = { onOpen(name) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(
    options: List<String>,
    selected: String?,
    allLabel: String,
    label: (String) -> String,
    onSelect: (String?) -> Unit
) {
    if (options.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(allLabel) })
        }
        items(options, key = { it }) { o ->
            FilterChip(
                selected = selected == o,
                onClick = { onSelect(if (selected == o) null else o) },
                label = { Text(label(o)) }
            )
        }
    }
}

@Composable
private fun ExerciseRow(ex: ExerciseInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ForgeCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ex.hasMotion) {
                SkeletonThumbnail(
                    exerciseId = ex.id,
                    primaryMuscles = ex.primaryMuscles,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
            } else {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ex.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    ex.primaryMuscles.joinToString(" · ") { ExerciseLabels.muscle(it) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ex.equipment.joinToString(" · ") { ExerciseLabels.equipment(it) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (ex.videos.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.PlayCircle,
                            contentDescription = "${ex.videos.size} tutorials",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            " ${ex.videos.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoggedRow(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ForgeCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Your history and tutorial search",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

package com.laksh.forge.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader

@Composable
fun LibraryScreen(onBack: () -> Unit, onOpenProgram: (String) -> Unit) {
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Free programs", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = {
                    Text(
                        if (state.all.isEmpty()) "Published free by their authors"
                        else "${state.all.size} programs, each checked against its sources",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                scrollBehavior = scroll
            )
        }
    ) { padding ->
        when {
            state.loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { LoadingIndicator(Modifier.size(72.dp)) }

            state.error != null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = "Library unavailable",
                    body = state.error.orEmpty(),
                    action = { Pill("Try again", filled = true, onClick = { vm.load() }) }
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 48.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.recommended.isNotEmpty() && !state.filters.isActive) {
                    item(key = "rec_header", contentType = "header") {
                        Column(Modifier.animateItem()) {
                            SectionHeader("RECOMMENDED FOR YOU")
                            state.profile?.let { p ->
                                Text(
                                    buildString {
                                        append("Based on: ${levelLabel(p.level).lowercase()}")
                                        if (!p.levelKnown) append(" (set your experience in Profile for better picks)")
                                        p.days?.let { append(", about $it training days a week") }
                                        append(", ${goalLabel(p.goal)} goal")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                    item(key = "rec_row", contentType = "rec_row") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.animateItem()
                        ) {
                            items(state.recommended, key = { "rec_${it.id}" }, contentType = { "rec_card" }) { p ->
                                ProgramCard(
                                    program = p,
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        onOpenProgram(p.id)
                                    },
                                    highlight = true,
                                    compact = true,
                                    modifier = Modifier.width(290.dp)
                                )
                            }
                        }
                    }
                }

                item(key = "filters", contentType = "filters") {
                    Filters(
                        state = state,
                        onCategory = vm::setCategory,
                        onLevel = vm::setLevel,
                        onDays = vm::setDays,
                        onEquipment = vm::toggleEquipment,
                        onClear = vm::clearFilters,
                        modifier = Modifier.animateItem()
                    )
                }

                item(key = "all_header", contentType = "header") {
                    SectionHeader(
                        if (state.filters.isActive) "${state.visible.size} MATCHING" else "ALL PROGRAMS",
                        modifier = Modifier.animateItem()
                    )
                }

                if (state.visible.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.FilterAltOff,
                            title = "No programs match",
                            body = "Every program here needs something you filtered out. Loosen a filter to see more.",
                            action = { Pill("Clear filters", filled = true, onClick = vm::clearFilters) },
                            modifier = Modifier.animateItem()
                        )
                    }
                } else {
                    items(state.visible, key = { it.id }, contentType = { "program" }) { p ->
                        ProgramCard(
                            program = p,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                onOpenProgram(p.id)
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

private fun goalLabel(goal: String) = when (goal.uppercase()) {
    "CUT" -> "fat-loss"
    "LEAN_GAIN" -> "lean-gain"
    "MAINGAIN" -> "maingain"
    "MAINTAIN" -> "maintenance"
    "RECOMP" -> "recomp"
    else -> goal.lowercase().replace('_', ' ')
}

@Composable
private fun Filters(
    state: LibraryState,
    onCategory: (String?) -> Unit,
    onLevel: (String?) -> Unit,
    onDays: (Int?) -> Unit,
    onEquipment: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipRow("Goal") {
            items(state.categories, key = { "c_$it" }) { c ->
                Chip(categoryLabel(c), state.filters.category == c) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onCategory(c)
                }
            }
        }
        ChipRow("Level") {
            items(state.levels, key = { "l_$it" }) { l ->
                Chip(levelLabel(l), state.filters.level == l) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onLevel(l)
                }
            }
        }
        ChipRow("Days a week") {
            items(state.dayOptions, key = { "d_$it" }) { d ->
                Chip("$d days", state.filters.days == d) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onDays(d)
                }
            }
        }
        ChipRow("Equipment I have") {
            items(state.equipment, key = { "e_$it" }) { e ->
                val have = e !in state.filters.missing
                Chip(equipmentLabel(e), have) {
                    haptics.performHapticFeedback(if (have) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onEquipment(e)
                }
            }
        }
        if (state.filters.isActive) {
            Spacer(Modifier.height(2.dp))
            Pill("Clear filters", icon = Icons.Outlined.FilterAltOff, onClick = onClear)
        }
    }
}

@Composable
private fun ChipRow(label: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Outlined.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
        shape = MaterialTheme.shapes.extraLarge
    )
}

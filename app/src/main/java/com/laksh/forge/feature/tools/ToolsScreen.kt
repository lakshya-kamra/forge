package com.laksh.forge.feature.tools

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.theme.cardContainer
import kotlin.math.roundToInt

@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    vm: ToolsViewModel = viewModel(factory = ToolsViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptics = LocalHapticFeedback.current
    val buzz: (HapticFeedbackType) -> Unit = { type ->
        if (state.haptics) haptics.performHapticFeedback(type)
    }
    val motion = MaterialTheme.motionScheme
    val background = MaterialTheme.colorScheme.background

    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
    ) {
        LargeFlexibleTopAppBar(
            title = { Text("Gym tools") },
            subtitle = { Text(subtitleFor(state.inputs.tab)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
            // The app shell's Scaffold already applied the status-bar inset.
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = background,
                scrolledContainerColor = background
            ),
            scrollBehavior = scrollBehavior
        )

        ConnectedChoice(
            options = ToolTab.entries,
            selected = state.inputs.tab,
            label = { it.label },
            onSelect = {
                buzz(HapticFeedbackType.SegmentTick)
                vm.selectTab(it)
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.size(64.dp))
            }
        } else AnimatedContent(
            targetState = state.inputs.tab,
            transitionSpec = {
                fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec())
            },
            label = "toolTab",
            modifier = Modifier.fillMaxSize()
        ) { tab ->
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                state.error?.let { msg ->
                    item(key = "error", contentType = "error") {
                        ErrorCard(msg, onDismiss = vm::dismissError, modifier = Modifier.animateItem())
                    }
                }
                when (tab) {
                    ToolTab.PLATES -> platesTab(state, vm, buzz)
                    ToolTab.WARMUP -> warmupTab(state, vm, buzz)
                    ToolTab.ONE_RM -> oneRmTab(state, vm, buzz)
                    ToolTab.CONVERT -> convertTab(state, vm, buzz)
                }
            }
        }
    }
}

private fun subtitleFor(tab: ToolTab): String = when (tab) {
    ToolTab.PLATES -> "Plates per side from your gym's inventory"
    ToolTab.WARMUP -> "A ramp to your first working set"
    ToolTab.ONE_RM -> "Estimated 1RM, percentages and RPE"
    ToolTab.CONVERT -> "kg, lb, cm, in, kcal, kJ"
}

// =================================================================== plates

private fun LazyListScope.platesTab(
    state: ToolsState,
    vm: ToolsViewModel,
    buzz: (HapticFeedbackType) -> Unit
) {
    val profile = state.profile
    val step = PlateMath.smallestStepKg(profile) ?: 2.5

    item(key = "plate_input", contentType = "input") {
        ForgeCard(Modifier.animateItem()) {
            NumberField(
                value = state.inputs.plateTargetKg,
                onValue = vm::setPlateTarget,
                label = "Target weight",
                suffix = "kg"
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val current = state.inputs.plateTargetKg ?: profile.emptyKg
                FilledTonalIconButton(onClick = {
                    buzz(HapticFeedbackType.SegmentTick)
                    vm.setPlateTarget((current - step).coerceAtLeast(profile.emptyKg))
                }) { Icon(Icons.Outlined.Remove, contentDescription = "Minus ${Units.fmt(step)} kg") }
                Text(
                    "${Units.fmt(step)} kg steps",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalIconButton(onClick = {
                    buzz(HapticFeedbackType.SegmentTick)
                    vm.setPlateTarget(current + step)
                }) { Icon(Icons.Outlined.Add, contentDescription = "Plus ${Units.fmt(step)} kg") }
                if (state.unit == WeightUnit.LB) {
                    Spacer(Modifier.weight(1f))
                    state.inputs.plateTargetKg?.let {
                        Text(
                            "= ${Units.fmt(Units.kgToLb(it), 1)} lb",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    item(key = "plate_result", contentType = "result") {
        PlateResultCard(
            load = state.plate,
            profile = profile,
            maxLoadKg = state.maxLoadKg,
            unit = state.unit,
            onPick = {
                buzz(HapticFeedbackType.SegmentTick)
                vm.setPlateTarget(it)
            },
            modifier = Modifier.animateItem()
        )
    }

    item(key = "profile_header", contentType = "header") {
        SectionHeader(
            "GYM PROFILE",
            action = "Reset",
            onAction = { vm.resetProfile() },
            modifier = Modifier.animateItem()
        )
    }

    item(key = "profile_bar", contentType = "profile") {
        ForgeCard(Modifier.animateItem()) {
            Text("Bar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            ConnectedChoice(
                options = GymProfile.BAR_OPTIONS,
                selected = GymProfile.BAR_OPTIONS.firstOrNull { PlateMath.roundKg(it) == PlateMath.roundKg(profile.barKg) }
                    ?: profile.barKg,
                label = { "${Units.fmt(it)} kg" },
                onSelect = {
                    buzz(HapticFeedbackType.SegmentTick)
                    vm.setBar(it)
                }
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Collars", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (profile.useCollars) "Counted in every load: ${Units.fmt(profile.collarKg)} kg each"
                        else "Spring clips or none - not counted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = profile.useCollars,
                    onCheckedChange = {
                        buzz(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        vm.setUseCollars(it)
                    }
                )
            }
            if (profile.useCollars) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Each collar",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Stepper(
                        valueLabel = "${Units.fmt(profile.collarKg)} kg",
                        onMinus = {
                            buzz(HapticFeedbackType.SegmentTick)
                            vm.setCollarKg(profile.collarKg - 0.25)
                        },
                        onPlus = {
                            buzz(HapticFeedbackType.SegmentTick)
                            vm.setCollarKg(profile.collarKg + 0.25)
                        },
                        minusEnabled = profile.collarKg > 0.0,
                        plusEnabled = profile.collarKg < 5.0
                    )
                }
            }
        }
    }

    item(key = "profile_plates", contentType = "profile") {
        ForgeCard(Modifier.animateItem()) {
            Text("Plates", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "How many of each you can put on ONE side.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            val scheme = MaterialTheme.colorScheme
            state.editablePlates.forEach { plate ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(width = 6.dp, height = 22.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(plateColor(plate.kg, scheme))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${Units.fmt(plate.kg)} kg",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Stepper(
                        valueLabel = "${plate.perSide}",
                        onMinus = {
                            buzz(HapticFeedbackType.SegmentTick)
                            vm.setPlateCount(plate.kg, plate.perSide - 1)
                        },
                        onPlus = {
                            buzz(HapticFeedbackType.SegmentTick)
                            vm.setPlateCount(plate.kg, plate.perSide + 1)
                        },
                        minusEnabled = plate.perSide > 0,
                        plusEnabled = plate.perSide < 20
                    )
                }
            }
        }
    }
}

@Composable
private fun PlateResultCard(
    load: PlateLoad?,
    profile: GymProfile,
    maxLoadKg: Double,
    unit: WeightUnit,
    onPick: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    ForgeCard(modifier) {
        when {
            load == null -> EmptyHint("Enter a target weight to see the plates.")
            load.belowBar -> {
                EmptyHint(
                    "${Units.fmt(load.targetKg)} kg is lighter than the empty bar" +
                        (if (profile.useCollars) " with collars" else "") +
                        " (${Units.fmt(profile.emptyKg)} kg)."
                )
                Spacer(Modifier.height(8.dp))
                Pill("Use ${Units.fmt(profile.emptyKg)} kg", onClick = { onPick(profile.emptyKg) })
            }
            else -> PlateResultBody(load, maxLoadKg, unit, onPick)
        }
    }
}

@Composable
private fun PlateResultBody(
    load: PlateLoad,
    maxLoadKg: Double,
    unit: WeightUnit,
    onPick: (Double) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Units.fmt(load.totalKg),
                style = MaterialTheme.typography.displaySmallEmphasized,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                " kg",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Spacer(Modifier.weight(1f))
            Pill(
                if (load.exact) "Exact" else "Nearest lower",
                color = if (load.exact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                filled = true
            )
        }
        if (unit == WeightUnit.LB) {
            Text(
                "${Units.fmt(Units.kgToLb(load.totalKg), 1)} lb",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!load.exact) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (load.targetKg > maxLoadKg + 1e-6) {
                    "Your plates top out at ${Units.fmt(maxLoadKg)} kg."
                } else {
                    "${Units.fmt(load.targetKg)} kg can't be built from your plates."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))
        PlateStack(perSide = load.perSide)
        Spacer(Modifier.height(6.dp))

        if (load.perSide.isEmpty()) {
            Text(
                "Empty bar - no plates.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "Per side",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            val scheme = MaterialTheme.colorScheme
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                load.grouped.forEach { (kg, n) ->
                    Pill("${Units.fmt(kg)} kg × $n", color = plateColor(kg, scheme).copy(alpha = 1f), filled = true)
                }
            }
        }

        val lower = load.nextLowerKg?.takeIf { load.exact }
        val higher = load.nextHigherKg
        if (lower != null || higher != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                lower?.let { Pill("Down: ${Units.fmt(it)} kg", onClick = { onPick(it) }) }
                higher?.let { Pill("Up: ${Units.fmt(it)} kg", onClick = { onPick(it) }) }
            }
        }
    }
}

// =================================================================== warm-up

private fun LazyListScope.warmupTab(
    state: ToolsState,
    vm: ToolsViewModel,
    buzz: (HapticFeedbackType) -> Unit
) {
    item(key = "warm_input", contentType = "input") {
        ForgeCard(Modifier.animateItem()) {
            NumberField(
                value = state.inputs.warmupWorkKg,
                onValue = vm::setWarmupWork,
                label = "First working set",
                suffix = "kg"
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Work reps", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Stepper(
                    valueLabel = "${state.inputs.warmupWorkReps}",
                    onMinus = {
                        buzz(HapticFeedbackType.SegmentTick)
                        vm.setWarmupWorkReps(state.inputs.warmupWorkReps - 1)
                    },
                    onPlus = {
                        buzz(HapticFeedbackType.SegmentTick)
                        vm.setWarmupWorkReps(state.inputs.warmupWorkReps + 1)
                    },
                    minusEnabled = state.inputs.warmupWorkReps > 1,
                    plusEnabled = state.inputs.warmupWorkReps < 20
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Jumps from the empty bar",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            ConnectedChoice(
                options = listOf(3, 4, 5),
                selected = state.inputs.warmupJumps,
                label = { "$it" },
                onSelect = {
                    buzz(HapticFeedbackType.SegmentTick)
                    vm.setWarmupJumps(it)
                }
            )
        }
    }

    val plan = state.warmup
    if (plan == null) {
        item(key = "warm_empty", contentType = "hint") {
            ForgeCard(Modifier.animateItem()) { EmptyHint("Enter your first working weight to build a ramp.") }
        }
        return
    }
    if (plan.steps.isEmpty()) {
        item(key = "warm_none", contentType = "hint") {
            ForgeCard(Modifier.animateItem()) { EmptyHint(plan.note ?: "No warm-up needed.") }
        }
        return
    }

    item(key = "warm_header", contentType = "header") {
        SectionHeader("RAMP", modifier = Modifier.animateItem())
    }
    plan.steps.forEachIndexed { i, step ->
        item(key = "warm_${i}_${step.kg}", contentType = "step") {
            WarmupRow(step, unit = state.unit, modifier = Modifier.animateItem())
        }
    }
    item(key = "warm_note", contentType = "note") {
        Column(Modifier.animateItem().padding(horizontal = 4.dp)) {
            plan.note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "Shape: empty bar 2×5, then even jumps up to the work weight (the Starting Strength warm-up). " +
                    "Reps taper as the load climbs, and every load is rounded down to what your plates can make.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WarmupRow(step: WarmupStep, unit: WeightUnit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    ForgeCard(
        modifier = modifier,
        color = if (step.isWorkSet) scheme.primaryContainer else scheme.cardContainer,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
    ) {
        val onColor = if (step.isWorkSet) scheme.onPrimaryContainer else scheme.onSurface
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    step.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (step.isWorkSet) onColor else scheme.onSurfaceVariant
                )
                Text(
                    "${Units.fmt(step.kg)} kg",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = onColor
                )
                if (unit == WeightUnit.LB) {
                    Text(
                        "${Units.fmt(Units.kgToLb(step.kg), 1)} lb",
                        style = MaterialTheme.typography.bodySmall,
                        color = onColor.copy(alpha = 0.75f)
                    )
                }
            }
            Text(
                if (step.sets > 1) "${step.sets} × ${step.reps}" else "× ${step.reps}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onColor
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (step.load.perSide.isEmpty()) "Empty bar"
            else "Per side: " + step.load.grouped.joinToString("  ") { (kg, n) ->
                if (n == 1) Units.fmt(kg) else "${Units.fmt(kg)}×$n"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (step.isWorkSet) onColor.copy(alpha = 0.8f) else scheme.onSurfaceVariant
        )
    }
}

// =================================================================== 1RM

private fun LazyListScope.oneRmTab(
    state: ToolsState,
    vm: ToolsViewModel,
    buzz: (HapticFeedbackType) -> Unit
) {
    val i = state.inputs
    item(key = "orm_input", contentType = "input") {
        ForgeCard(Modifier.animateItem()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    value = i.ormWeightKg,
                    onValue = vm::setOrmWeight,
                    label = "Weight",
                    suffix = "kg",
                    modifier = Modifier.weight(1.4f)
                )
                IntField(
                    value = i.ormReps,
                    onValue = vm::setOrmReps,
                    label = "Reps",
                    modifier = Modifier.weight(1f)
                )
            }
            if (i.ormReps != null && i.ormReps !in 1..20) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Use 1 to 20 reps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "RPE (10 = nothing left in the tank)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OneRepMax.RPE_CHOICES.forEach { rpe ->
                    FilterChip(
                        selected = rpe == i.ormRpe,
                        onClick = {
                            buzz(HapticFeedbackType.SegmentTick)
                            vm.setOrmRpe(rpe)
                        },
                        label = { Text(Units.fmt(rpe, 1)) },
                        shape = RoundedCornerShape(50)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ConnectedChoice(
                options = E1rmFormula.entries,
                selected = i.formula,
                label = { it.label },
                onSelect = {
                    buzz(HapticFeedbackType.SegmentTick)
                    vm.setFormula(it)
                }
            )
        }
    }

    val orm = state.orm
    item(key = "orm_result", contentType = "result") {
        ForgeCard(Modifier.animateItem()) {
            if (orm == null) {
                EmptyHint("Enter a weight and 1-20 reps to estimate a 1RM.")
            } else {
                Text(
                    "Estimated 1RM · ${i.formula.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Units.fmt(orm.of(i.formula), 1),
                        style = MaterialTheme.typography.displaySmallEmphasized,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        " kg",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    if (state.unit == WeightUnit.LB) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${Units.fmt(Units.kgToLb(orm.of(i.formula)), 1)} lb",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                val other = if (i.formula == E1rmFormula.EPLEY) E1rmFormula.BRZYCKI else E1rmFormula.EPLEY
                Text(
                    "${other.label}: ${Units.fmt(orm.of(other), 1)} kg",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (orm.rpe < 10.0) {
                    Text(
                        "${orm.reps} reps at RPE ${Units.fmt(orm.rpe, 1)} counts as ${Units.fmt(orm.repsToFailure, 1)} reps to failure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (orm != null && state.percentRows.isNotEmpty()) {
        item(key = "pct_header", contentType = "header") {
            SectionHeader("PERCENTAGES · TAP TO LOAD", modifier = Modifier.animateItem())
        }
        item(key = "pct_table", contentType = "table") {
            ForgeCard(Modifier.animateItem(), contentPadding = PaddingValues(vertical = 8.dp)) {
                state.percentRows.forEach { row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                buzz(HapticFeedbackType.SegmentTick)
                                vm.loadOnBar(row.loadKg)
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${row.percent}%",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 56.dp)
                        )
                        Text(
                            "${Units.fmt(row.rawKg, 1)} kg",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${Units.fmt(row.loadKg)} kg",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    "Right column: nearest load your plates can make.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                )
            }
        }
    }

    state.rpeTable?.let { table ->
        item(key = "rpe_header", contentType = "header") {
            SectionHeader("RPE TABLE · % OF 1RM", modifier = Modifier.animateItem())
        }
        item(key = "rpe_table", contentType = "table") {
            ForgeCard(Modifier.animateItem(), contentPadding = PaddingValues(12.dp)) {
                RpeGrid(table, highlightReps = orm?.reps, highlightRpe = orm?.rpe)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Worked out from ${table.formula.label} using the RIR-based RPE scale: " +
                        "each RPE point under 10 is one rep left in reserve.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RpeGrid(table: RpeTable, highlightReps: Int?, highlightRpe: Double?) {
    val scheme = MaterialTheme.colorScheme
    val cellW = 46.dp
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column {
            GridCell("RPE", cellW, header = true)
            table.rpes.forEach { GridCell(Units.fmt(it, 1), cellW, header = true) }
        }
        table.reps.forEachIndexed { ci, reps ->
            Column {
                GridCell("$reps", cellW, header = true)
                table.rpes.forEachIndexed { ri, rpe ->
                    val hit = reps == highlightReps && rpe == highlightRpe
                    GridCell(
                        text = "${(table.cells[ri][ci] * 100).roundToInt()}",
                        width = cellW,
                        highlight = hit,
                        highlightColor = scheme.primaryContainer,
                        highlightText = scheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun GridCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    header: Boolean = false,
    highlight: Boolean = false,
    highlightColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    highlightText: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Box(
        Modifier
            .width(width)
            .height(32.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (highlight) Modifier.background(highlightColor) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (header || highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                highlight -> highlightText
                header -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center
        )
    }
}

// =================================================================== converter

private fun LazyListScope.convertTab(
    state: ToolsState,
    vm: ToolsViewModel,
    buzz: (HapticFeedbackType) -> Unit
) {
    val i = state.inputs
    item(key = "conv_kind", contentType = "input") {
        ForgeCard(Modifier.animateItem()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Conversion.entries.forEach { c ->
                    FilterChip(
                        selected = c == i.conversion,
                        onClick = {
                            buzz(HapticFeedbackType.SegmentTick)
                            vm.setConversion(c)
                        },
                        label = { Text("${c.fromLabel} → ${c.toLabel}") },
                        shape = RoundedCornerShape(50)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            NumberField(
                value = i.convertValue,
                onValue = vm::setConvertValue,
                label = "Value",
                suffix = i.conversion.fromLabel
            )
        }
    }
    item(key = "conv_result", contentType = "result") {
        ForgeCard(Modifier.animateItem()) {
            val result = state.converted
            if (result == null || i.convertValue == null) {
                EmptyHint("Type a value to convert.")
            } else {
                Text(
                    "${Units.fmt(i.convertValue, 3)} ${i.conversion.fromLabel} =",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Units.fmt(result, i.conversion.decimals),
                        style = MaterialTheme.typography.displaySmallEmphasized,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        " ${i.conversion.toLabel}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }
    }
}

// =================================================================== shared bits

/**
 * A single-choice row of connected toggle buttons - the Expressive replacement for a
 * segmented button (same construction as OmniCal's ConnectedToggleGroup).
 */
@Composable
private fun <T> ConnectedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { if (option != selected) onSelect(option) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(label(option), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Stepper(
    valueLabel: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    minusEnabled: Boolean,
    plusEnabled: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onMinus, enabled = minusEnabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Remove, contentDescription = "Decrease", modifier = Modifier.size(18.dp))
        }
        Text(
            valueLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 52.dp)
        )
        FilledTonalIconButton(onClick = onPlus, enabled = plusEnabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Add, contentDescription = "Increase", modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Decimal input whose text lives in the composable (so fast typing never races a StateFlow
 * round trip) while the parsed number lives in the view model. External changes - a tapped
 * percentage, a stepper - overwrite the text only when they differ from what's typed.
 */
@Composable
private fun NumberField(
    value: Double?,
    onValue: (Double?) -> Unit,
    label: String,
    suffix: String,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf(value?.let { Units.fmt(it, 3) } ?: "") }
    LaunchedEffect(value) {
        if (Units.parse(text) != value) text = value?.let { Units.fmt(it, 3) } ?: ""
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() || it == '.' || it == ',' }.take(8)
            text = cleaned
            onValue(Units.parse(cleaned))
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun IntField(
    value: Int?,
    onValue: (Int?) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf(value?.toString() ?: "") }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value?.toString() ?: ""
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filter { it.isDigit() }.take(3)
            text = cleaned
            onValue(cleaned.toIntOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun EmptyHint(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.FitnessCenter,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    ForgeCard(modifier, color = MaterialTheme.colorScheme.errorContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

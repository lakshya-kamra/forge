package com.laksh.forge.feature.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.DinnerDining
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FreeBreakfast
import androidx.compose.material.icons.outlined.LunchDining
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.NoMeals
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.laksh.forge.data.db.FoodEntry
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.feature.plan.PlanSummaryCard
import com.laksh.forge.feature.wearables.ReadinessCard
import com.laksh.forge.ui.components.AnimatedCounter
import com.laksh.forge.ui.components.CalorieRing
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ErrorNotice
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.IconBadge
import com.laksh.forge.ui.components.LocalFloatingBarClearance
import com.laksh.forge.ui.components.LocalForgeSnackbar
import com.laksh.forge.ui.components.MacroBar
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.components.ShimmerBlock
import com.laksh.forge.ui.components.ShimmerCard
import com.laksh.forge.ui.components.SwipeToDeleteRow
import com.laksh.forge.ui.components.enterStagger
import com.laksh.forge.ui.components.showUndo
import com.laksh.forge.ui.theme.Macro
import com.laksh.forge.ui.theme.accentFor
import com.laksh.forge.ui.theme.cardContainer
import com.laksh.forge.util.Dates
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** The intro stagger plays once per process, never again on tab switches or day changes. */
private object TodayIntro {
    var played = false
}

@Composable
fun TodayScreen(
    onAddFood: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    vm: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
    onOpenPlan: () -> Unit = {},
    onOpenProgress: () -> Unit = {},
    onOpenWearables: () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalForgeSnackbar.current
    val scope = rememberCoroutineScope()
    var showWeightDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm, snackbar) {
        vm.messages.collect { msg -> snackbar?.showSnackbar(msg) }
    }

    TodayContent(
        state = state,
        onShiftDay = vm::shiftDay,
        onRefresh = vm::refresh,
        onRetry = vm::retry,
        onAddFood = onAddFood,
        onOpenEntry = onOpenEntry,
        onOpenProgress = onOpenProgress,
        onOpenWearables = onOpenWearables,
        onLogWeight = { showWeightDialog = true },
        onDelete = { entry ->
            vm.delete(entry)
            scope.launch {
                if (snackbar == null) vm.finalizeDelete(entry)
                else snackbar.showUndo(
                    message = "Removed ${entry.name.take(32)}",
                    onUndo = { vm.restore(entry) },
                    onExpired = { vm.finalizeDelete(entry) }
                )
            }
        },
        onQuickAdd = { entry ->
            vm.repeat(entry) { added ->
                scope.launch {
                    snackbar?.showUndo(
                        message = "Added ${added.name.take(32)}",
                        onUndo = { vm.removeRepeated(added) }
                    )
                }
            }
        },
        planSlot = { PlanSummaryCard(onOpen = onOpenPlan) },
        readinessSlot = { ReadinessCard(onOpen = onOpenWearables) }
    )

    if (showWeightDialog) {
        WeightDialog(
            unit = state.settings.weightUnit,
            current = state.latestWeight?.let {
                NutritionMath.kgToDisplay(it.kg, state.settings.weightUnit)
            },
            onDismiss = { showWeightDialog = false },
            onSave = { w, bf ->
                vm.logWeight(w, bf)
                showWeightDialog = false
            }
        )
    }
}

/**
 * Stateless Today. Everything it shows comes from [state]; the plan and readiness cards are
 * slots so screenshot tests can render the screen without other features' view models.
 */
@Composable
fun TodayContent(
    state: TodayState,
    onShiftDay: (Int) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onAddFood: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    onOpenProgress: () -> Unit,
    onOpenWearables: () -> Unit,
    onLogWeight: () -> Unit,
    onDelete: (FoodEntry) -> Unit,
    onQuickAdd: (FoodEntry) -> Unit,
    modifier: Modifier = Modifier,
    planSlot: @Composable () -> Unit = {},
    readinessSlot: @Composable () -> Unit = {}
) {
    val clearance = LocalFloatingBarClearance.current
    val haptics = LocalHapticFeedback.current
    val pullState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val playIntro = remember { !TodayIntro.played }
    SideEffect { TodayIntro.played = true }

    // A tick when the pull passes the point where letting go will refresh.
    LaunchedEffect(pullState) {
        snapshotFlow { pullState.distanceFraction >= 1f }
            .distinctUntilChanged()
            .collect { armed -> if (armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        state = pullState,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = pullState,
                isRefreshing = state.refreshing,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = clearance + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header", contentType = "header") {
                DayHeader(
                    date = state.date,
                    dateLong = state.dateLong,
                    isToday = state.isToday,
                    onShift = onShiftDay,
                    modifier = Modifier.enterStagger(0, playIntro)
                )
            }

            if (state.error != null) {
                item(key = "error", contentType = "error") {
                    ErrorNotice(message = state.error, onRetry = onRetry)
                }
                return@LazyColumn
            }

            if (state.loading) {
                item(key = "loading", contentType = "loading") { LoadingSkeleton() }
                return@LazyColumn
            }

            item(key = "hero", contentType = "hero") {
                HeroCard(state, Modifier.enterStagger(1, playIntro))
            }

            if (state.isToday) {
                item(key = "plan", contentType = "slot") {
                    Box(Modifier.enterStagger(2, playIntro)) { planSlot() }
                }
                item(key = "readiness", contentType = "slot") {
                    Box(Modifier.enterStagger(3, playIntro)) { readinessSlot() }
                }
            }

            item(key = "tiles", contentType = "tiles") {
                Row(
                    Modifier.enterStagger(4, playIntro),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WeightTile(
                        weightKg = state.latestWeight?.kg,
                        unit = state.settings.weightUnit,
                        onClick = onLogWeight,
                        onOpenProgress = onOpenProgress,
                        modifier = Modifier.weight(1f)
                    )
                    StepsTile(
                        steps = state.health?.steps,
                        target = state.settings.stepsTarget,
                        onClick = onOpenWearables,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (state.isToday && state.recent.isNotEmpty()) {
                item(key = "quick_header", contentType = "section") { SectionHeader("Quick add") }
                item(key = "quick", contentType = "quick") {
                    QuickAddRow(state.recent, state.hideNumbers, onQuickAdd)
                }
            }

            item(key = "food_header", contentType = "section") {
                SectionHeader(
                    title = if (state.isToday) "Today's food" else "${Dates.dayLabel(state.date)}'s food",
                    action = if (state.isToday) "Add" else null,
                    onAction = if (state.isToday) onAddFood else null
                )
            }

            if (state.meals.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    ForgeCard {
                        EmptyState(
                            icon = Icons.Outlined.NoMeals,
                            title = "Nothing logged",
                            body = if (state.isToday) "Snap a photo of your meal, scan a barcode, or describe it."
                            else "No food was logged on this day.",
                            action = if (state.isToday) {
                                {
                                    Button(
                                        onClick = onAddFood,
                                        shapes = ButtonDefaults.shapes()
                                    ) {
                                        Icon(Icons.Rounded.Add, null, Modifier.size(ButtonDefaults.IconSize))
                                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                        Text("Log food")
                                    }
                                }
                            } else null
                        )
                    }
                }
            } else {
                state.meals.forEach { meal ->
                    item(key = "meal_${meal.slot}", contentType = "meal_header") {
                        MealHeader(meal, state.hideNumbers, Modifier.animateItem())
                    }
                    itemsIndexed(
                        items = meal.entries,
                        key = { _, entry -> entry.id },
                        contentType = { _, _ -> "entry" }
                    ) { index, entry ->
                        val shape = segmentShape(index, meal.entries.size)
                        SwipeToDeleteRow(
                            onDelete = { onDelete(entry) },
                            shape = shape,
                            deleteLabel = "Remove ${entry.name}",
                            modifier = Modifier.animateItem()
                        ) {
                            FoodRow(
                                entry = entry,
                                shape = shape,
                                hideNumbers = state.hideNumbers,
                                onClick = { onOpenEntry(entry.id) },
                                onDelete = { onDelete(entry) }
                            )
                        }
                    }
                }
                item(key = "swipe_hint", contentType = "hint") {
                    Text(
                        "Swipe a meal left to remove it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .animateItem(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun DayHeader(
    date: String,
    dateLong: String,
    isToday: Boolean,
    onShift: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = date,
                transitionSpec = {
                    // ISO dates compare lexically: later days slide in from the right.
                    val forward = targetState > initialState
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(spatial) { it / 3 * dir } + fadeIn(effects))
                        .togetherWith(slideOutHorizontally(spatial) { -it / 3 * dir } + fadeOut(effects))
                },
                label = "dayTitle"
            ) { d ->
                Text(
                    Dates.dayLabel(d),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                    maxLines = 1
                )
            }
            Text(
                dateLong,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onShift(-1)
            },
            shapes = IconButtonDefaults.shapes()
        ) { Icon(Icons.Rounded.ChevronLeft, "Previous day") }
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onShift(1)
            },
            enabled = !isToday,
            shapes = IconButtonDefaults.shapes()
        ) { Icon(Icons.Rounded.ChevronRight, "Next day") }
    }
}

// ------------------------------------------------------------------ hero

@Composable
private fun HeroCard(state: TodayState, modifier: Modifier = Modifier) {
    val targets = state.targets
    val target = targets?.kcal ?: 0
    val over = state.remaining < 0

    ForgeCard(modifier = modifier, contentPadding = PaddingValues(20.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CalorieRing(
                consumed = state.kcal,
                target = target,
                centerTop = "",
                centerMain = "",
                centerBottom = "",
                centerContent = {
                    if (state.hideNumbers) {
                        Icon(
                            Icons.Outlined.VisibilityOff, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${state.entries.size} logged",
                            style = MaterialTheme.typography.titleMediumEmphasized
                        )
                    } else {
                        Text(
                            if (over) "OVER BY" else "REMAINING",
                            style = MaterialTheme.typography.labelMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AnimatedCounter(
                            value = abs(state.remaining),
                            style = MaterialTheme.typography.displayMediumEmphasized,
                            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "of $target kcal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }

        if (!state.hideNumbers) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                HeroStat("Eaten", state.kcal.roundToInt(), "kcal")
                HeroStat("Target", target, "kcal")
                HeroStat("Protein", state.totals?.proteinG?.roundToInt() ?: 0, "g")
            }
        }

        targets?.let {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.targetsFromPlan) {
                    Pill("Plan", filled = true, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    it.basis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        if (targets != null && !state.hideNumbers) {
            Spacer(Modifier.height(18.dp))
            val t = state.totals
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MacroBar("Protein", t?.proteinG ?: 0.0, targets.proteinG, Macro.protein, Modifier.weight(1f))
                MacroBar("Carbs", t?.carbsG ?: 0.0, targets.carbsG, Macro.carbs, Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MacroBar("Fat", t?.fatG ?: 0.0, targets.fatG, Macro.fat, Modifier.weight(1f))
                MacroBar("Fibre", t?.fiberG ?: 0.0, targets.fiberG, Macro.fiber, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: Int, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            AnimatedCounter(value = value, style = MaterialTheme.typography.titleMediumEmphasized)
            Text(
                " $unit",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------------ tiles

@Composable
private fun WeightTile(
    weightKg: Double?,
    unit: WeightUnit,
    onClick: () -> Unit,
    onOpenProgress: () -> Unit,
    modifier: Modifier = Modifier
) {
    ForgeCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.MonitorWeight, size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Weight",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenProgress, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ShowChart,
                    contentDescription = "Open progress",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            weightKg?.let { "%.1f %s".format(NutritionMath.kgToDisplay(it, unit), unit.label) } ?: "Log",
            style = MaterialTheme.typography.headlineSmallEmphasized
        )
        Text(
            if (weightKg != null) "Tap to update" else "Tap to add",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepsTile(
    steps: Long?,
    target: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.accentFor(Macro.protein)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fraction = if (steps == null || target <= 0) 0f else (steps.toFloat() / target).coerceIn(0f, 1f)

    ForgeCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.AutoMirrored.Rounded.DirectionsWalk, tint = accent, size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Steps",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        if (steps == null) {
            Text("No data", style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(
                "Connect a wearable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AnimatedCounter(value = steps.toInt(), style = MaterialTheme.typography.headlineSmallEmphasized)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .drawBehind {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(track, cornerRadius = r)
                        if (fraction > 0f) {
                            drawRoundRect(
                                accent,
                                size = Size((size.width * fraction).coerceAtLeast(size.height), size.height),
                                cornerRadius = r
                            )
                        }
                    }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "of $target",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------ quick add

@Composable
private fun QuickAddRow(
    recent: List<FoodEntry>,
    hideNumbers: Boolean,
    onQuickAdd: (FoodEntry) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(recent, key = { it.id }, contentType = { "quick" }) { entry ->
            Pill(
                text = if (hideNumbers) entry.name.take(24)
                else "${entry.name.take(22)} · ${entry.kcal.roundToInt()}",
                filled = true,
                icon = Icons.Rounded.Add,
                color = MaterialTheme.colorScheme.primary,
                onClick = { onQuickAdd(entry) },
                modifier = Modifier.animateItem()
            )
        }
    }
}

// ------------------------------------------------------------------ meals

private fun slotIcon(slot: String): ImageVector = when (slot) {
    "BREAKFAST" -> Icons.Outlined.FreeBreakfast
    "LUNCH" -> Icons.Outlined.LunchDining
    "DINNER" -> Icons.Outlined.DinnerDining
    else -> Icons.Outlined.Cookie
}

@Composable
private fun MealHeader(meal: MealGroup, hideNumbers: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, start = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(slotIcon(meal.slot), size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(meal.label, style = MaterialTheme.typography.titleMediumEmphasized)
            Text(
                Dates.timeLabel(meal.firstTimestamp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!hideNumbers) {
            AnimatedCounter(
                value = meal.kcal.roundToInt(),
                style = MaterialTheme.typography.titleSmallEmphasized,
                suffix = " kcal"
            )
        }
    }
}

// Segment shapes are allocated once; one of four is picked per row.
private val SegmentSingle = RoundedCornerShape(20.dp)
private val SegmentTop = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
private val SegmentMiddle = RoundedCornerShape(6.dp)
private val SegmentBottom = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp)

private fun segmentShape(index: Int, count: Int): Shape = when {
    count <= 1 -> SegmentSingle
    index == 0 -> SegmentTop
    index == count - 1 -> SegmentBottom
    else -> SegmentMiddle
}

private fun sourceIcon(source: String): ImageVector = when (source) {
    "PHOTO" -> Icons.Outlined.PhotoCamera
    "BARCODE" -> Icons.Outlined.QrCodeScanner
    "LABEL" -> Icons.Outlined.Receipt
    "FAVOURITE" -> Icons.Outlined.Replay
    else -> Icons.Outlined.EditNote
}

@Composable
private fun FoodRow(
    entry: FoodEntry,
    shape: Shape,
    hideNumbers: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.cardContainer,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            // The swipe is invisible to TalkBack; the clickable row is what gets focus, so the
            // remove action has to live on it.
            .semantics {
                customActions = listOf(CustomAccessibilityAction("Remove") { onDelete(); true })
            }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val photo = entry.photoPath
            if (photo != null) {
                AsyncImage(
                    model = File(photo),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )
            } else {
                IconBadge(sourceIcon(entry.source), tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 44.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(entry.servingDesc)
                        entry.brand?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!hideNumbers) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MacroChip("P", entry.proteinG, Macro.protein)
                        MacroChip("C", entry.carbsG, Macro.carbs)
                        MacroChip("F", entry.fatG, Macro.fat)
                    }
                }
            }
            if (!hideNumbers) {
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${entry.kcal.roundToInt()}", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        "kcal",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroChip(letter: String, grams: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "$letter ${grams.roundToInt()}g",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------------------------------------------------------ loading

@Composable
private fun LoadingSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.cardContainer)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ShimmerBlock(Modifier.size(210.dp), CircleShape)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShimmerBlock(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                )
                ShimmerBlock(
                    Modifier
                        .weight(1f)
                        .height(28.dp)
                )
            }
        }
        ShimmerCard(lines = 2)
        ShimmerCard(lines = 3)
    }
}

// ------------------------------------------------------------------ weight dialog

@Composable
fun WeightDialog(
    unit: WeightUnit,
    current: Double?,
    onDismiss: () -> Unit,
    onSave: (Double, Double?) -> Unit
) {
    var weight by rememberSaveable { mutableStateOf(current?.let { "%.1f".format(it) } ?: "") }
    var bodyFat by rememberSaveable { mutableStateOf("") }
    val parsed = weight.replace(',', '.').toDoubleOrNull()
    val haptics = LocalHapticFeedback.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log weight") },
        text = {
            Column {
                Text(
                    "Weigh in first thing, after the toilet, before eating. Day-to-day noise is normal - the trend line is what matters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("Weight (${unit.label})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = bodyFat,
                    onValueChange = { bodyFat = it },
                    label = { Text("Body fat % (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    parsed?.let {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onSave(it, bodyFat.replace(',', '.').toDoubleOrNull())
                    }
                },
                enabled = parsed != null && parsed > 0,
                shapes = ButtonDefaults.shapes()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
        }
    )
}

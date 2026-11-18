package com.laksh.forge.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.NoMeals
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.db.FoodEntry
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.ui.components.CalorieRing
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.MacroBar
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.ui.theme.Macro
import com.laksh.forge.util.Dates
import kotlin.math.abs
import kotlin.math.roundToInt

private val MEAL_ORDER = listOf("BREAKFAST", "LUNCH", "DINNER", "SNACK")

private fun mealLabel(slot: String) = when (slot) {
    "BREAKFAST" -> "Breakfast"
    "LUNCH" -> "Lunch"
    "DINNER" -> "Dinner"
    else -> "Snacks"
}

@Composable
fun TodayScreen(
    onAddFood: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    vm: TodayViewModel = viewModel(factory = TodayViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showWeightDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { DateBar(state.date, onShift = vm::shiftDay) }

        item {
            ForgeCard(contentPadding = PaddingValues(20.dp)) {
                val targets = state.targets
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CalorieRing(
                        consumed = state.kcal,
                        target = targets?.kcal ?: 0,
                        centerTop = if (state.remaining >= 0) "REMAINING" else "OVER BY",
                        centerMain = abs(state.remaining).toString(),
                        centerBottom = "of ${targets?.kcal ?: 0} kcal"
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${state.kcal.roundToInt()} eaten",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                targets?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it.basis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                Spacer(Modifier.height(20.dp))
                val t = state.totals
                targets?.let { tg ->
                    MacroBar("Protein", t?.proteinG ?: 0.0, tg.proteinG, Macro.protein)
                    Spacer(Modifier.height(12.dp))
                    MacroBar("Carbs", t?.carbsG ?: 0.0, tg.carbsG, Macro.carbs)
                    Spacer(Modifier.height(12.dp))
                    MacroBar("Fat", t?.fatG ?: 0.0, tg.fatG, Macro.fat)
                    Spacer(Modifier.height(12.dp))
                    MacroBar("Fibre", t?.fiberG ?: 0.0, tg.fiberG, Macro.fiber)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WeightTile(
                    modifier = Modifier.weight(1f),
                    weightKg = state.latestWeight?.kg,
                    unit = state.settings.weightUnit,
                    onClick = { showWeightDialog = true }
                )
                HealthTile(modifier = Modifier.weight(1f), state = state)
            }
        }

        if (state.recent.isNotEmpty() && state.isToday) {
            item { SectionHeader("Quick add") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.recent, key = { it.id }) { entry ->
                        Pill(
                            text = "${entry.name.take(22)} · ${entry.kcal.roundToInt()}",
                            filled = true,
                            color = MaterialTheme.colorScheme.primary,
                            onClick = { vm.repeat(entry) }
                        )
                    }
                }
            }
        }

        item { SectionHeader("${Dates.dayLabel(state.date)}'s food") }

        if (state.entries.isEmpty()) {
            item {
                ForgeCard {
                    EmptyState(
                        icon = Icons.Outlined.NoMeals,
                        title = "Nothing logged",
                        body = "Snap a photo of your meal, scan a barcode, or type it in.",
                        action = {
                            Pill(
                                "Add food",
                                icon = Icons.Outlined.Add,
                                filled = true,
                                onClick = onAddFood
                            )
                        }
                    )
                }
            }
        } else {
            MEAL_ORDER.forEach { slot ->
                val items = state.entries.filter { it.mealSlot == slot }
                if (items.isNotEmpty()) {
                    item(key = "hdr_$slot") {
                        MealHeader(mealLabel(slot), items.sumOf { it.kcal })
                    }
                    items(items, key = { it.id }) { entry ->
                        FoodRow(
                            entry = entry,
                            onClick = { onOpenEntry(entry.id) },
                            onDelete = { vm.deleteEntry(entry) }
                        )
                    }
                }
            }
        }
    }

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

@Composable
private fun DateBar(date: String, onShift: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onShift(-1) }) {
            Icon(Icons.Filled.ChevronLeft, "Previous day")
        }
        Text(
            Dates.dayLabel(date),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(
            onClick = { onShift(1) },
            enabled = date != Dates.today()
        ) {
            Icon(
                Icons.Filled.ChevronRight,
                "Next day",
                tint = if (date == Dates.today()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun MealHeader(label: String, kcal: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "${kcal.roundToInt()} kcal",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FoodRow(entry: FoodEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }

    ForgeCard(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(entry.servingDesc)
                        entry.brand?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MacroChip("P", entry.proteinG, Macro.protein)
                    MacroChip("C", entry.carbsG, Macro.carbs)
                    MacroChip("F", entry.fatG, Macro.fat)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${entry.kcal.roundToInt()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "kcal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Remove",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { confirmDelete = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove entry?") },
            text = { Text("${entry.name} will be deleted from this day.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
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

@Composable
private fun WeightTile(
    modifier: Modifier,
    weightKg: Double?,
    unit: com.laksh.forge.data.prefs.WeightUnit,
    onClick: () -> Unit
) {
    ForgeCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.MonitorWeight, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Weight",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            weightKg?.let {
                "%.1f %s".format(NutritionMath.kgToDisplay(it, unit), unit.label)
            } ?: "Log",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            if (weightKg != null) "Tap to update" else "Tap to add",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HealthTile(modifier: Modifier, state: TodayState) {
    ForgeCard(modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Outlined.DirectionsWalk, null,
                tint = Macro.protein,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Wearable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        val h = state.health
        if (h == null) {
            Text(
                "No data",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Connect in Settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                h.steps?.toString() ?: "-",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "steps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                h.activeKcal?.let {
                    MiniStat(Icons.Outlined.LocalFireDepartment, "${it.roundToInt()}")
                }
                h.sleepMinutes?.let {
                    MiniStat(Icons.Outlined.Bedtime, "${it / 60}h${it % 60}m")
                }
            }
        }
    }
}

@Composable
private fun MiniStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun WeightDialog(
    unit: com.laksh.forge.data.prefs.WeightUnit,
    current: Double?,
    onDismiss: () -> Unit,
    onSave: (Double, Double?) -> Unit
) {
    var weight by remember { mutableStateOf(current?.let { "%.1f".format(it) } ?: "") }
    var bodyFat by remember { mutableStateOf("") }
    val parsed = weight.replace(',', '.').toDoubleOrNull()

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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = bodyFat,
                    onValueChange = { bodyFat = it },
                    label = { Text("Body fat % (optional)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let { onSave(it, bodyFat.replace(',', '.').toDoubleOrNull()) } },
                enabled = parsed != null && parsed > 0
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

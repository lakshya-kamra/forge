package com.laksh.forge.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.health.HealthAvailability
import com.laksh.forge.data.prefs.GoalMode
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.data.remote.CostTier
import com.laksh.forge.data.remote.Task
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    val context = LocalContext.current

    var keyDraft by remember(s.apiKey) { mutableStateOf(s.apiKey) }
    var showKey by remember { mutableStateOf(false) }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = vm.healthContract()
    ) { vm.onHealthPermissionResult() }

    LaunchedEffect(Unit) { vm.refreshHealthStatus() }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        // ------------------------------------------------ API key
        item { SectionHeader("Claude connection") }
        item {
            ForgeCard {
                Text(
                    "Photo estimates, label reading, PDF import and the coach all run through your own Anthropic API key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    label = { Text("API key") },
                    placeholder = { Text("sk-ant-...") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(if (showKey) "Hide" else "Show", onClick = { showKey = !showKey })
                    Pill(
                        "Save",
                        icon = Icons.Outlined.Key,
                        filled = true,
                        onClick = { vm.setApiKey(keyDraft) }
                    )
                    if (s.hasApiKey) {
                        Pill(
                            if (state.testingKey) "Testing..." else "Test",
                            onClick = vm::testKey
                        )
                    }
                }
                state.keyTestResult?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Cost tier",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Each job gets the smallest model that does it well, rather than one model for everything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(10.dp))
                CostTier.entries.forEach { tier ->
                    ChoiceRow(
                        title = "${tier.label} - ${tier.roughMonthly}",
                        subtitle = tier.blurb,
                        selected = s.tier == tier,
                        onClick = { vm.setTier(tier) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                TierBreakdown(s.tier)
            }
        }

        // ------------------------------------------------ goal
        item { SectionHeader("Goal") }
        item {
            ForgeCard {
                GoalMode.entries.forEach { g ->
                    ChoiceRow(
                        title = g.label,
                        subtitle = g.blurb,
                        selected = s.goal == g,
                        onClick = { vm.setGoal(g) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                val rateDisplay = NutritionMath.kgToDisplay(s.rateKgPerWeek, s.weightUnit)
                Text(
                    "Target rate: %+.2f %s/week".format(rateDisplay, s.weightUnit.label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = s.rateKgPerWeek.toFloat(),
                    onValueChange = { vm.setRate(it.toDouble()) },
                    valueRange = -1.2f..0.7f,
                    steps = 37
                )
                Text(
                    when {
                        s.rateKgPerWeek < -0.9 -> "Aggressive. Expect to lose some muscle and feel it in training."
                        s.rateKgPerWeek < -0.2 -> "Sustainable rate of loss."
                        s.rateKgPerWeek < 0.1 -> "Roughly maintenance."
                        s.rateKgPerWeek < 0.35 -> "Lean gaining pace."
                        else -> "Fast gain - more of this will be fat than muscle."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ------------------------------------------------ targets
        item { SectionHeader("Daily targets") }
        item {
            ForgeCard {
                ToggleRow(
                    title = "Calculate targets for me",
                    subtitle = "Uses your weight trend and actual intake to learn maintenance",
                    checked = s.useAutoTargets,
                    onCheckedChange = vm::setUseAuto
                )
                Spacer(Modifier.height(12.dp))
                val t = state.previewTargets
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TargetChip("kcal", t.kcal.toString())
                    TargetChip("protein", "${t.proteinG}g")
                    TargetChip("carbs", "${t.carbsG}g")
                    TargetChip("fat", "${t.fatG}g")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    t.basis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!s.useAutoTargets) {
                    Spacer(Modifier.height(14.dp))
                    NumberSetting(
                        label = "Daily calories",
                        value = s.manualKcal.toString(),
                        onValue = { it.toIntOrNull()?.let(vm::setManualKcal) }
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "Protein: %.1f g per kg".format(s.proteinPerKg),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = s.proteinPerKg.toFloat(),
                    onValueChange = { vm.setProteinPerKg((it * 10).roundToInt() / 10.0) },
                    valueRange = 1.2f..3.0f,
                    steps = 17
                )
                Text(
                    "1.6-2.2 g/kg covers most of the benefit; the higher end helps in a deficit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    "Fat: %.1f g per kg".format(s.fatPerKg),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = s.fatPerKg.toFloat(),
                    onValueChange = { vm.setFatPerKg((it * 10).roundToInt() / 10.0) },
                    valueRange = 0.4f..1.5f,
                    steps = 10
                )
            }
        }

        // ------------------------------------------------ estimation
        item { SectionHeader("Photo estimates") }
        item {
            ForgeCard {
                Text(
                    "Safety margin: ${s.safetyMarginPct}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = s.safetyMarginPct.toFloat(),
                    onValueChange = { vm.setSafetyMargin(it.roundToInt()) },
                    valueRange = 0f..40f,
                    steps = 39
                )
                Text(
                    "Added on top of the estimate, which already leans high by design. Protein is never padded - only calories, carbs and fat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ------------------------------------------------ privacy
        item { SectionHeader("Photos and privacy") }
        item {
            ForgeCard {
                ToggleRow(
                    title = "Keep meal photos",
                    subtitle = "Stored in this app's private storage on the phone only",
                    checked = s.storePhotos,
                    onCheckedChange = vm::setStorePhotos
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "What happens to your photos",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "A meal photo is sent to Anthropic's API only to produce the estimate, and the reply is the estimate itself. " +
                        "Anthropic does not train models on API inputs or outputs. They may hold API traffic briefly for abuse monitoring, " +
                        "so the honest position is \"not stored long-term and never trained on\" rather than \"never leaves your phone\".\n\n" +
                        "Turning off \"Keep meal photos\" means Forge analyses the image and discards it instead of writing it to disk. " +
                        "Barcode scanning is fully on-device - only the digits are looked up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ------------------------------------------------ health connect
        item { SectionHeader("Amazfit / Zepp") }
        item {
            ForgeCard {
                when (state.healthAvailability) {
                    HealthAvailability.NOT_SUPPORTED -> {
                        Text(
                            "Health Connect isn't available on this device.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    HealthAvailability.NOT_INSTALLED -> {
                        Text(
                            "Health Connect needs installing or updating before wearable data can sync.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Pill(
                            "Get Health Connect",
                            filled = true,
                            onClick = { runCatching { context.startActivity(vm.healthInstallIntent()) } }
                        )
                    }
                    HealthAvailability.AVAILABLE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.healthGranted) {
                                Icon(
                                    Icons.Outlined.CheckCircle, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (state.healthGranted) "Connected to Health Connect"
                                else "Not connected yet",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The Helio strap has no direct API. The route is Zepp → Health Connect → Forge. " +
                                "In the Zepp app, open Profile → Settings → Health Connect and turn on sharing for steps, sleep, heart rate and activity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!state.healthGranted) {
                                Pill(
                                    "Connect",
                                    filled = true,
                                    onClick = { healthPermissionLauncher.launch(vm.healthPermissions()) }
                                )
                            } else {
                                Pill(
                                    if (state.syncing) "Syncing..." else "Sync now",
                                    icon = Icons.Outlined.Sync,
                                    filled = true,
                                    onClick = vm::syncHealth
                                )
                            }
                            Pill(
                                "Health Connect settings",
                                onClick = { runCatching { context.startActivity(vm.healthSettingsIntent()) } }
                            )
                        }
                        state.syncMessage?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { vm.clearSyncMessage() }
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------ profile
        item { SectionHeader("About you") }
        item {
            ForgeCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sex.entries.forEach { sex ->
                        Pill(
                            sex.name.lowercase().replaceFirstChar { it.uppercase() },
                            filled = s.sex == sex,
                            color = if (s.sex == sex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { vm.setSex(sex) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    WeightUnit.entries.forEach { u ->
                        Pill(
                            u.label,
                            filled = s.weightUnit == u,
                            color = if (s.weightUnit == u) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { vm.setUnit(u) }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberSetting(
                        label = "Age",
                        value = s.age.toString(),
                        onValue = { it.toIntOrNull()?.let(vm::setAge) },
                        modifier = Modifier.weight(1f)
                    )
                    NumberSetting(
                        label = "Height (cm)",
                        value = s.heightCm.roundToInt().toString(),
                        onValue = { it.toDoubleOrNull()?.let(vm::setHeight) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Activity level: %.2f".format(s.activityFactor),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = s.activityFactor.toFloat(),
                    onValueChange = { vm.setActivity((it * 100).roundToInt() / 100.0) },
                    valueRange = 1.2f..1.9f,
                    steps = 13
                )
                Text(
                    when {
                        s.activityFactor < 1.35 -> "Desk job, little movement outside training"
                        s.activityFactor < 1.55 -> "Moderately active - some walking, regular training"
                        s.activityFactor < 1.75 -> "Active - high step count or physical job"
                        else -> "Very active"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Only used until Forge has enough data to learn your real maintenance from intake and weight change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }

        item {
            Text(
                "Forge · general fitness guidance, not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

/** Shows exactly which model each job runs on, so the tier isn't a black box. */
@Composable
private fun TierBreakdown(tier: CostTier) {
    val rows = listOf(
        "Meal photos" to Task.FOOD_PHOTO,
        "Nutrition labels" to Task.LABEL,
        "Program PDFs" to Task.PROGRAM,
        "Coach" to Task.COACH
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp)
    ) {
        rows.forEachIndexed { i, (label, task) ->
            if (i > 0) Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    prettyModel(tier.modelFor(task)),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun prettyModel(id: String): String = when {
    id.contains("opus") -> "Opus 5"
    id.contains("sonnet") -> "Sonnet 5"
    id.contains("haiku") -> "Haiku 4.5"
    else -> id
}

@Composable
private fun TargetChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.onPrimary)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NumberSetting(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it.filter { c -> c.isDigit() || c == '.' }
            onValue(draft)
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number
        ),
        modifier = modifier
    )
}

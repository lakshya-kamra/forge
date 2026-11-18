package com.laksh.forge.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.prefs.GoalMode
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.plan.Equipment
import com.laksh.forge.domain.plan.MeasurementGuides
import com.laksh.forge.domain.plan.PhaseCopy
import com.laksh.forge.domain.plan.PlanBuilder
import com.laksh.forge.feature.plan.BulletList
import com.laksh.forge.feature.plan.ChoiceCard
import com.laksh.forge.feature.plan.ConnectedChoice
import com.laksh.forge.feature.plan.MeasurementInput
import com.laksh.forge.feature.plan.NumberField
import com.laksh.forge.feature.plan.PlanScaffold
import com.laksh.forge.feature.plan.SupportResources
import com.laksh.forge.feature.plan.SwitchRow
import com.laksh.forge.feature.plan.TargetsRow
import com.laksh.forge.feature.plan.plusContent
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.util.Dates
import kotlin.math.roundToInt

@Composable
fun ProfileSettingsScreen(onBack: () -> Unit, onRedoOnboarding: () -> Unit) {
    val vm: ProfileSettingsViewModel = viewModel(factory = ProfileSettingsViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var scoffOpen by rememberSaveable { mutableStateOf(false) }
    var confirmRedo by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    PlanScaffold(title = "Profile", subtitle = "You, your training and your targets", onBack = onBack, snackbarHostState = snackbar) { inner ->
        when {
            !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
            state.error != null -> EmptyState(
                icon = Icons.Outlined.Person,
                title = "Couldn't load your profile",
                body = state.error ?: "",
                modifier = Modifier.padding(inner)
            )
            else -> LazyColumn(
                contentPadding = inner.plusContent(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "aboutHeader", contentType = "header") { SectionHeader("About you", Modifier.animateItem()) }
                item(key = "about", contentType = "card") { AboutCard(state, vm, Modifier.animateItem()) }
                item(key = "trainingHeader", contentType = "header") { SectionHeader("Training", Modifier.animateItem()) }
                item(key = "training", contentType = "card") { TrainingCard(state, vm, Modifier.animateItem()) }
                item(key = "safetyHeader", contentType = "header") { SectionHeader("Health & safety", Modifier.animateItem()) }
                item(key = "safety", contentType = "card") { SafetyCard(state, vm, onRetakeScoff = { scoffOpen = true }, Modifier.animateItem()) }
                if (state.settings.edScreenPositive || state.profile.crisisLockActive()) {
                    item(key = "support", contentType = "note") { SupportResources(Modifier.animateItem()) }
                }
                item(key = "goalHeader", contentType = "header") { SectionHeader("Goal & targets", Modifier.animateItem()) }
                item(key = "goal", contentType = "card") { GoalCard(state, vm, Modifier.animateItem()) }
                item(key = "redo", contentType = "card") {
                    ForgeCard(Modifier.animateItem()) {
                        Text("Redo onboarding", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Go through the questions, measurements and optional photos again for a fresh recommendation. Your history is kept.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(onClick = { confirmRedo = true }, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Redo onboarding")
                        }
                    }
                }
            }
        }
    }

    if (scoffOpen) {
        ScoffDialog(onDismiss = { scoffOpen = false }, onSubmit = { answers ->
            scoffOpen = false
            vm.submitScoff(answers)
        })
    }
    if (confirmRedo) {
        AlertDialog(
            onDismissRequest = { confirmRedo = false },
            title = { Text("Redo onboarding?") },
            text = { Text("Your current plan stays active until you accept a new one.") },
            confirmButton = { TextButton(onClick = { confirmRedo = false; onRedoOnboarding() }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { confirmRedo = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AboutCard(state: ProfileUiState, vm: ProfileSettingsViewModel, modifier: Modifier) {
    val s = state.settings
    var age by remember(s.age) { mutableStateOf(s.age.toString()) }
    var height by remember(s.heightCm) { mutableStateOf(s.heightCm.roundToInt().toString()) }
    ForgeCard(modifier) {
        ConnectedChoice(Sex.entries, s.sex, { if (it == Sex.MALE) "Male" else "Female" }, vm::setSex)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                label = "Age",
                value = age,
                onValue = { v -> age = v; v.toIntOrNull()?.let(vm::setAge) },
                suffix = "years",
                decimal = false,
                modifier = Modifier.weight(1f)
            )
            Column(Modifier.weight(1.3f)) {
                MeasurementInput(MeasurementGuides.height, height, { v -> height = v; v.toDoubleOrNull()?.let(vm::setHeight) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Weight unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        ConnectedChoice(WeightUnit.entries, s.weightUnit, { it.label }, vm::setUnit)
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "South Asian background",
            subtitle = "Used only for neutral health context, such as the waist cut-off",
            checked = state.profile.southAsian,
            onCheckedChange = vm::setSouthAsian
        )
    }
}

@Composable
private fun TrainingCard(state: ProfileUiState, vm: ProfileSettingsViewModel, modifier: Modifier) {
    val s = state.settings
    val p = state.profile
    ForgeCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(TrainingAge.NOVICE, TrainingAge.INTERMEDIATE, TrainingAge.ADVANCED).forEach { t ->
                ChoiceCard(t.label, t.blurb, s.trainingAge == t, onClick = { vm.setTrainingAge(t) })
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow("Returning after a long break", null, p.detrained, vm::setDetrained)
        Spacer(Modifier.height(8.dp))
        Text("Days a week", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        ConnectedChoice((2..6).toList(), p.daysPerWeek.takeIf { it in 2..6 }, { it.toString() }, vm::setDays)
        Spacer(Modifier.height(12.dp))
        Text("Equipment", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Equipment.entries.forEach { e -> ChoiceCard(e.label, null, p.equipment == e, onClick = { vm.setEquipment(e) }) }
        }
    }
}

@Composable
private fun SafetyCard(state: ProfileUiState, vm: ProfileSettingsViewModel, onRetakeScoff: () -> Unit, modifier: Modifier) {
    val s = state.settings
    val p = state.profile
    ForgeCard(modifier) {
        if (s.sex == Sex.FEMALE) {
            Text("Pregnancy", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            ConnectedChoice(
                PregnancyStatus.entries, s.pregnancyStatus,
                {
                    when (it) {
                        PregnancyStatus.NONE -> "Neither"
                        PregnancyStatus.PREGNANT -> "Pregnant"
                        PregnancyStatus.BREASTFEEDING -> "Breastfeeding"
                    }
                },
                vm::setPregnancy
            )
            if (s.pregnancyStatus != PregnancyStatus.NONE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Forge won't set a deficit. Please check training and nutrition changes with your doctor, midwife or dietitian.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        SwitchRow("Kidney disease", "Protein is held at 0.8 g/kg; targets come from your nephrologist or renal dietitian", p.kidneyDisease, vm::setKidney)
        SwitchRow("Diabetes treated with medication", "Forge keeps you at maintenance", p.diabetesMedication, vm::setDiabetes)
        SwitchRow("Another condition affecting weight or appetite", "Forge keeps you at maintenance", p.otherWeightCondition, vm::setOtherCondition)
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            "Hide calorie numbers",
            "Show plain-language guidance instead of calorie and weight figures",
            s.hideNumbers,
            vm::setHideNumbers
        )
        Spacer(Modifier.height(10.dp))
        Text("Eating check", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (s.edScreenAt > 0) "Last answered ${Dates.dayLabel(Dates.dayOf(s.edScreenAt))}." else "Not answered yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetakeScoff) { Text("Answer the five questions") }
    }
}

@Composable
private fun GoalCard(state: ProfileUiState, vm: ProfileSettingsViewModel, modifier: Modifier) {
    val s = state.settings
    val plan = state.activePlan
    val hide = s.hideNumbers
    val profile = state.profile

    ForgeCard(modifier) {
        if (plan != null) {
            Text("Set by your ${PhaseCopy.name(plan.phase).lowercase()} plan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Your plan's targets change only at weekly check-ins or when you re-plan. Protein and fat preferences below re-split the same calories.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            TargetsRow(plan.kcal, plan.proteinG, plan.carbsG, plan.fatG, hideCalories = hide)
        } else {
            Text("Goal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalMode.entries.forEach { g ->
                    val blocked = g == GoalMode.CUT && !state.deficitAllowed
                    ChoiceCard(
                        title = g.label,
                        subtitle = if (blocked) "Not available with your current health details" else g.blurb,
                        selected = s.goal == g,
                        enabled = !blocked,
                        onClick = { vm.setGoal(g) }
                    )
                }
            }
            if (!hide) {
                Spacer(Modifier.height(12.dp))
                RateSlider(state, vm)
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                "Calculate targets for me",
                "Uses your weight trend and actual intake to learn maintenance",
                s.useAutoTargets,
                vm::setUseAuto
            )
            if (!s.useAutoTargets && !hide) {
                var kcal by remember(s.manualKcal) { mutableStateOf(s.manualKcal.toString()) }
                NumberField(
                    label = "Daily calories",
                    value = kcal,
                    onValue = { kcal = it },
                    suffix = "kcal",
                    decimal = false,
                    supporting = "Saved when you tap Save; never below ${PlanBuilder.calorieFloor(s.sex)} kcal"
                )
                TextButton(onClick = { kcal.toIntOrNull()?.let(vm::setManualKcal) }) { Text("Save") }
            }
            state.preview?.let { t ->
                Spacer(Modifier.height(10.dp))
                TargetsRow(t.kcal, t.proteinG, t.carbsG, t.fatG, hideCalories = hide)
                if (!hide) {
                    Text(t.basis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.previewNotes.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    BulletList(state.previewNotes)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        ActivitySlider(s.activityFactor, vm::setActivity)

        Spacer(Modifier.height(14.dp))
        val proteinMax = if (profile.kidneyDisease) PlanBuilder.KIDNEY_PROTEIN_MAX_G_PER_KG else 3.0
        val proteinMin = if (profile.kidneyDisease) 0.6 else 1.2
        PrefSlider(
            title = "Protein",
            value = s.proteinPerKg.coerceIn(proteinMin, proteinMax),
            range = proteinMin..proteinMax,
            steps = ((proteinMax - proteinMin) * 10).roundToInt() - 1,
            unit = "g per kg",
            hint = if (profile.kidneyDisease) "With kidney disease, any target above 0.8 g/kg should come from your nephrologist or renal dietitian."
            else "1.6-2.2 g/kg covers most of the benefit; the higher end helps in a deficit.",
            onDone = vm::setProteinPerKg
        )
        Spacer(Modifier.height(12.dp))
        PrefSlider(
            title = "Fat",
            value = s.fatPerKg.coerceIn(0.5, 1.5),
            range = 0.5..1.5,
            steps = 9,
            unit = "g per kg",
            hint = "Forge keeps fat at 0.5 g/kg or more - some fat is needed for hormones and absorbing vitamins.",
            onDone = vm::setFatPerKg
        )

        Spacer(Modifier.height(12.dp))
        var steps by remember(s.stepsTarget) { mutableStateOf(s.stepsTarget.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberField(
                label = "Daily steps target",
                value = steps,
                onValue = { steps = it },
                decimal = false,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { steps.toIntOrNull()?.let(vm::setSteps) }) { Text("Save") }
        }
    }
}

@Composable
private fun RateSlider(state: ProfileUiState, vm: ProfileSettingsViewModel) {
    val s = state.settings
    val w = state.weightKg
    val min = if (state.deficitAllowed) (-0.01 * w).toFloat() else 0f
    val max = (0.005 * w).toFloat()
    var draft by remember(s.rateKgPerWeek) { mutableFloatStateOf(s.rateKgPerWeek.toFloat().coerceIn(min, max)) }
    Text(
        "Target rate: %+.2f %s/week".format(NutritionMath.kgToDisplay(draft.toDouble(), s.weightUnit), s.weightUnit.label),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium
    )
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { vm.setRate(draft.toDouble()) },
        valueRange = min..max
    )
    val pct = draft / w * 100
    Text(
        when {
            pct < -0.7 -> "Faster than ~0.7% a week costs more muscle and is harder to sustain."
            pct < -0.1 -> "A sustainable rate of loss."
            pct < 0.05 -> "Roughly maintenance."
            pct <= 0.25 -> "A slow, lean gaining pace."
            else -> "Faster gain - more of it tends to be fat than muscle."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ActivitySlider(value: Double, onDone: (Double) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat().coerceIn(1.2f, 1.9f)) }
    Text("Activity level: %.2f".format(draft), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onDone(draft.toDouble()) },
        valueRange = 1.2f..1.9f,
        steps = 13
    )
    Text(
        when {
            draft < 1.35 -> "Desk job, little movement outside training"
            draft < 1.55 -> "Moderately active - some walking, regular training"
            draft < 1.75 -> "Active - high step count or physical job"
            else -> "Very active"
        } + ". Only used until Forge learns your real maintenance from intake and weight change.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PrefSlider(
    title: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    steps: Int,
    unit: String,
    hint: String,
    onDone: (Double) -> Unit
) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text("$title: %.1f $unit".format(draft), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onDone(draft.toDouble()) },
        valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        steps = steps.coerceAtLeast(0)
    )
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ScoffDialog(onDismiss: () -> Unit, onSubmit: (List<Boolean>) -> Unit) {
    var answers by remember { mutableStateOf(List<Boolean?>(Guardrails.SCOFF_QUESTIONS.size) { null }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eating check") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "A few quick questions about eating. There are no right or wrong answers, and your answers stay on this phone.",
                    style = MaterialTheme.typography.bodySmall
                )
                Guardrails.SCOFF_QUESTIONS.forEachIndexed { i, q ->
                    Spacer(Modifier.height(12.dp))
                    Text(q, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    ConnectedChoice(listOf(true, false), answers[i], { if (it) "Yes" else "No" }, { yes ->
                        answers = answers.toMutableList().also { it[i] = yes }
                    })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(answers.map { it == true }) },
                enabled = answers.all { it != null },
                shapes = ButtonDefaults.shapes()
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

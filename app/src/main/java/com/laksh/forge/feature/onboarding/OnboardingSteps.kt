package com.laksh.forge.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import com.laksh.forge.ui.components.SecureWindow
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.plan.BodyFatRange
import com.laksh.forge.domain.plan.Equipment
import com.laksh.forge.domain.plan.MeasurementGuides
import com.laksh.forge.domain.plan.PhaseCopy
import com.laksh.forge.domain.plan.PhasePreference
import com.laksh.forge.domain.plan.PhotoProtocol
import com.laksh.forge.domain.plan.Pose
import com.laksh.forge.domain.plan.TrainingGoal
import com.laksh.forge.domain.plan.planSourceLabel
import com.laksh.forge.feature.plan.Badge
import com.laksh.forge.feature.plan.BulletList
import com.laksh.forge.feature.plan.ChoiceCard
import com.laksh.forge.feature.plan.ConnectedChoice
import com.laksh.forge.feature.plan.Expandable
import com.laksh.forge.feature.plan.MeasurementInput
import com.laksh.forge.feature.plan.NoteCard
import com.laksh.forge.feature.plan.NoteTone
import com.laksh.forge.feature.plan.NumberField
import com.laksh.forge.feature.plan.RangeBar
import com.laksh.forge.feature.plan.SupportResources
import com.laksh.forge.feature.plan.SwitchRow
import com.laksh.forge.feature.plan.TargetsRow
import com.laksh.forge.feature.progress.GalleryPhotos
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.util.Dates
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun StepContent(
    step: OnboardingStep,
    state: OnboardingUiState,
    vm: OnboardingViewModel,
    onCapture: (List<Pose>) -> Unit,
    onOpenProgram: ((String) -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when (step) {
            OnboardingStep.WELCOME -> WelcomeStep(state)
            OnboardingStep.BASICS -> BasicsStep(state, vm)
            OnboardingStep.TRAINING -> TrainingStep(state, vm)
            OnboardingStep.HEALTH -> HealthStep(state, vm)
            OnboardingStep.MEASUREMENTS -> MeasurementsStep(state, vm)
            OnboardingStep.PHOTOS -> PhotosStep(state, vm, onCapture)
            OnboardingStep.ANALYSIS -> AnalysisStep(state, vm)
            OnboardingStep.RECOMMENDATION -> RecommendationStep(state, vm)
            OnboardingStep.REVIEW -> ReviewStep(state, onOpenProgram)
            OnboardingStep.ACCEPT -> AcceptStep(state)
        }
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun ErrorText(text: String?) {
    if (text != null) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

// ---------------------------------------------------------------------------- 1 welcome

@Composable
private fun WelcomeStep(state: OnboardingUiState) {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        MorphHero(animate = !state.baseSettings.reduceMotion)
    }
    Text(
        if (state.hasPlan) "Let's rebuild your plan" else "Let's build your plan",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "A few questions about you, your training and your health, a tape measure and optional photos. Forge then " +
            "recommends a starting phase with daily targets and explains why.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    ForgeCard {
        WelcomeRow(Icons.Outlined.FitnessCenter, "Evidence first", "Every recommendation comes from published coaching and research rules, with safety limits on top.")
        Spacer(Modifier.height(12.dp))
        WelcomeRow(Icons.Outlined.Straighten, "Ranges, not false precision", "Body-fat estimates are shown as likely ranges because every method has real error.")
        Spacer(Modifier.height(12.dp))
        WelcomeRow(Icons.Outlined.Lock, "Private by default", "Your answers and measurements stay on this phone. Forge never keeps photos, and sends them for analysis only if you say so.")
    }
}

@Composable
private fun WelcomeRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shape-morphing hero; animated values are read only in draw/layer lambdas, never in composition. */
@Composable
private fun MorphHero(animate: Boolean) {
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf) }
    val transition = rememberInfiniteTransition(label = "hero")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animate) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "heroMorph"
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (animate) 360f else 0f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing)),
        label = "heroSpin"
    )
    val fill = MaterialTheme.colorScheme.primaryContainer
    val accent = MaterialTheme.colorScheme.primary
    val path = remember { Path() }
    val matrix = remember { Matrix() }
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(150.dp)
                .graphicsLayer { rotationZ = rotation }
        ) {
            path.rewind()
            morph.toPath(progress, path)
            matrix.reset()
            matrix.scale(size.width, size.height)
            path.transform(matrix)
            drawPath(path, fill)
        }
        Icon(Icons.Outlined.AutoAwesome, null, tint = accent, modifier = Modifier.size(48.dp))
    }
}

// ---------------------------------------------------------------------------- 2 basics

@Composable
private fun BasicsStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    val d = state.draft
    val errors = if (state.showErrors) OnboardingValidation.basics(d) else emptyMap()
    StepHeader("About you", "Used for your maintenance estimate and safety checks. It stays on this phone.")

    Label("Sex")
    ConnectedChoice(
        options = Sex.entries,
        selected = d.sex,
        label = { if (it == Sex.MALE) "Male" else "Female" },
        onSelect = { s -> vm.edit { it.copy(sex = s) } }
    )
    Text(
        "The maintenance and tape-measure formulas use different equations for men and women.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    NumberField(
        label = "Age",
        value = d.age,
        onValue = { v -> vm.edit { it.copy(age = v) } },
        suffix = "years",
        decimal = false,
        isError = errors.containsKey("age"),
        supporting = errors["age"]
    )
    MeasurementInput(
        guide = MeasurementGuides.height,
        value = d.heightCm,
        onValue = { v -> vm.edit { it.copy(heightCm = v) } },
        isError = errors.containsKey("height")
    )
    ErrorText(errors["height"])

    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberField(
            label = "Current weight",
            value = d.weight,
            onValue = { v -> vm.edit { it.copy(weight = v) } },
            suffix = d.unit.label,
            isError = errors.containsKey("weight"),
            supporting = errors["weight"],
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        ConnectedChoice(
            options = WeightUnit.entries,
            selected = d.unit,
            label = { it.label },
            onSelect = { u ->
                vm.edit { old ->
                    // Convert what was typed so switching units never changes the weight.
                    val kg = old.weightKg
                    old.copy(
                        unit = u,
                        weight = kg?.let { "%.1f".format(NutritionMath.kgToDisplay(it, u)) } ?: old.weight
                    )
                }
            },
            modifier = Modifier.width(132.dp)
        )
    }
    Text(
        "Ideally your morning weight - after the toilet, before food or drink.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SwitchRow(
        title = "South Asian background",
        subtitle = "Health risks can start at a lower BMI and waist size than the usual charts suggest. Forge uses this only for neutral health context.",
        checked = d.southAsian,
        onCheckedChange = { v -> vm.edit { it.copy(southAsian = v) } }
    )

    if (d.ageInt != null && d.isMinor) {
        NoteCard(
            title = "Built around growing well",
            body = "Because you're under 18, Forge focuses on training, strength and healthy eating rather than weight-loss " +
                "or body-fat goals. Your body is still growing, and fuelling it well matters most right now.",
            tone = NoteTone.SUPPORT
        )
    }
}

// ---------------------------------------------------------------------------- 3 training

@Composable
private fun TrainingStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    val d = state.draft
    val errors = if (state.showErrors) OnboardingValidation.training(d) else emptyMap()
    StepHeader("Your training", "So the plan and program suggestions fit your experience and week.")

    Label("How long have you trained consistently?")
    listOf(TrainingAge.NOVICE, TrainingAge.INTERMEDIATE, TrainingAge.ADVANCED).forEach { t ->
        ChoiceCard(t.label, t.blurb, d.trainingAge == t, onClick = { vm.edit { it.copy(trainingAge = t) } })
    }
    ErrorText(errors["trainingAge"])
    SwitchRow(
        title = "Returning after a long break",
        subtitle = "Coming back to lifting after months off",
        checked = d.detrained,
        onCheckedChange = { v -> vm.edit { it.copy(detrained = v) } }
    )

    Label("Days a week you can train")
    ConnectedChoice(
        options = (2..6).toList(),
        selected = d.daysPerWeek,
        label = { it.toString() },
        onSelect = { n -> vm.edit { it.copy(daysPerWeek = n) } }
    )

    Label("Equipment")
    Equipment.entries.forEach { e ->
        ChoiceCard(e.label, null, d.equipment == e, onClick = { vm.edit { it.copy(equipment = e) } })
    }

    Label("What do you want most right now?")
    TrainingGoal.entries.forEach { g ->
        ChoiceCard(g.label, g.blurb, d.goal == g, onClick = { vm.edit { it.copy(goal = g) } })
    }
    ErrorText(errors["goal"])

    Label("If losing fat and building muscle are both reasonable for you, which would you rather start with?")
    PhasePreference.entries.forEach { p ->
        ChoiceCard(p.label, p.blurb, d.preference == p, onClick = { vm.edit { it.copy(preference = p) } })
    }

    SwitchRow(
        title = "I've just come off a long diet",
        subtitle = "A deficit of 8+ weeks that ended in the last month, or dieting has been wearing you down",
        checked = d.recentDiet,
        onCheckedChange = { v -> vm.edit { it.copy(recentDiet = v) } }
    )
}

// ---------------------------------------------------------------------------- 4 health & safety

@Composable
private fun HealthStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    val d = state.draft
    val errors = if (state.showErrors) OnboardingValidation.health(d) else emptyMap()
    StepHeader("Health & safety", "A few questions that change what advice is safe for you. Nothing here is shared.")

    if (d.sex == Sex.FEMALE) {
        Label("Are you currently...")
        ConnectedChoice(
            options = PregnancyStatus.entries,
            selected = d.pregnancy,
            label = {
                when (it) {
                    PregnancyStatus.NONE -> "Neither"
                    PregnancyStatus.PREGNANT -> "Pregnant"
                    PregnancyStatus.BREASTFEEDING -> "Breastfeeding"
                }
            },
            onSelect = { p -> vm.edit { it.copy(pregnancy = p) } }
        )
        when (d.pregnancy) {
            PregnancyStatus.PREGNANT -> NoteCard(
                title = "Plan with your doctor or midwife",
                body = "During pregnancy Forge won't set a calorie deficit or weight-loss goal. Please check your training " +
                    "plan with your doctor or midwife, and stop and call them if you have bleeding, dizziness or faintness, " +
                    "chest pain, headache, muscle weakness, calf pain or swelling, painful contractions, leaking fluid, or " +
                    "breathlessness before exercise.",
                tone = NoteTone.CAUTION,
                icon = Icons.Outlined.HealthAndSafety
            )
            PregnancyStatus.BREASTFEEDING -> NoteCard(
                title = "No deficit while breastfeeding",
                body = "While you're breastfeeding, Forge adds to your maintenance target and won't set a deficit. If you'd " +
                    "like to lose weight during this time, your doctor or a dietitian can plan it safely with you.",
                tone = NoteTone.CAUTION,
                icon = Icons.Outlined.HealthAndSafety
            )
            PregnancyStatus.NONE -> Unit
        }
    }

    Label("Medical conditions")
    SwitchRow(
        "Kidney disease", "Protein targets then come from your nephrologist or renal dietitian",
        d.kidneyDisease, { v -> vm.edit { it.copy(kidneyDisease = v) } }
    )
    SwitchRow(
        "Diabetes treated with medication", null,
        d.diabetesMedication, { v -> vm.edit { it.copy(diabetesMedication = v) } }
    )
    SwitchRow(
        "Another condition that affects your weight or appetite", "Anything a doctor is managing",
        d.otherCondition, { v -> vm.edit { it.copy(otherCondition = v) } }
    )
    SwitchRow(
        "Recent chest pain, fainting, palpitations or unusual breathlessness", null,
        d.redFlagSymptoms, { v -> vm.edit { it.copy(redFlagSymptoms = v) } }
    )
    if (d.redFlagSymptoms) {
        NoteCard(
            title = "Please see a doctor before training",
            body = "These symptoms need a doctor's check before you exercise again. If you have chest pain that isn't " +
                "easing, or you've fainted, call 112 now.",
            tone = NoteTone.CAUTION,
            icon = Icons.Outlined.HealthAndSafety
        )
    }
    if (d.kidneyDisease || d.diabetesMedication || d.otherCondition) {
        NoteCard(
            title = "Forge will keep things at maintenance",
            body = "With a condition that affects weight or nutrition, Forge won't set a deficit or an aggressive surplus. " +
                "Please agree any change in weight with your doctor."
        )
    }

    SectionHeader("Eating")
    Text(
        "A few quick questions about eating. There are no right or wrong answers, and your answers stay on this phone.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Guardrails.SCOFF_QUESTIONS.forEachIndexed { i, q ->
        ForgeCard {
            Text(q, style = MaterialTheme.typography.bodyLarge)
            if (i == 2 && !q.contains("kg")) {
                Text("(one stone = about 6.35 kg)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            ConnectedChoice(
                options = listOf(true, false),
                selected = d.scoff.getOrNull(i),
                label = { if (it) "Yes" else "No" },
                onSelect = { yes -> vm.setScoff(i, yes) }
            )
        }
    }
    ErrorText(errors["scoff"])

    if (state.eatingConcern) {
        NoteCard(
            title = "Thank you for answering honestly",
            body = "Some of your answers suggest food may be causing you stress. That's common and nothing to be ashamed " +
                "of, and talking to someone helps. Forge will keep you at maintenance and pause weight-loss features. You " +
                "can talk to your doctor and ask about an eating-disorder specialist (ideally one who works with athletes " +
                "or lifters).",
            tone = NoteTone.SUPPORT
        ) {
            SwitchRow(
                title = "Hide calorie numbers",
                subtitle = "Show plain-language guidance instead of calorie and weight figures",
                checked = d.hideNumbers,
                onCheckedChange = { v -> vm.edit { it.copy(hideNumbers = v) } }
            )
        }
        SupportResources(
            intro = if (state.crisisDisclosed)
                "It sounds like things are hard right now, and you don't have to handle this alone. If you're in immediate danger, call 112."
            else null
        )
    }

    Label("Anything else about eating or your health? (optional)")
    OutlinedTextField(
        value = d.healthNote,
        onValueChange = { v -> vm.edit { it.copy(healthNote = v) } },
        placeholder = { Text("Only read on this phone, never saved") },
        minLines = 2,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

// ---------------------------------------------------------------------------- 5 measurements

@Composable
private fun MeasurementsStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    val d = state.draft
    val errors = if (state.showErrors) OnboardingValidation.measurements(d) else emptyMap()
    val motion = MaterialTheme.motionScheme
    StepHeader(
        "Measurements",
        if (d.isMinor) "Optional - only for tracking your own progress."
        else "A tape measure gives Forge a body-fat range (US Navy method) and a trend line that beats the scale."
    )
    Expandable("How to measure accurately") {
        Text(MeasurementGuides.protocol, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!d.isMinor) {
        SwitchRow(
            title = "I don't have a tape measure right now",
            subtitle = "Forge will start you at maintenance and you can add measurements later",
            checked = d.noTape,
            onCheckedChange = { v -> vm.edit { it.copy(noTape = v) } }
        )
    }
    AnimatedVisibility(
        visible = !d.noTape || d.isMinor,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec())
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MeasurementInput(MeasurementGuides.waistFor(d.sex), d.waist, { v -> vm.edit { it.copy(waist = v) } }, isError = errors.containsKey("waist"))
            ErrorText(errors["waist"])
            MeasurementInput(MeasurementGuides.neck, d.neck, { v -> vm.edit { it.copy(neck = v) } }, isError = errors.containsKey("neck"))
            ErrorText(errors["neck"])
            val hipsGuide = if (d.sex == Sex.FEMALE) MeasurementGuides.hips
            else MeasurementGuides.hips.copy(title = "Hips (optional)")
            MeasurementInput(hipsGuide, d.hips, { v -> vm.edit { it.copy(hips = v) } }, isError = errors.containsKey("hips"))
            ErrorText(errors["hips"])
            if (d.southAsian) {
                MeasurementInput(MeasurementGuides.waistHealth, d.healthWaist, { v -> vm.edit { it.copy(healthWaist = v) } }, isError = errors.containsKey("healthWaist"))
            }
            Expandable("More sites to track (optional)") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MeasurementInput(MeasurementGuides.chest, d.chest, { v -> vm.edit { it.copy(chest = v) } }, isError = errors.containsKey("chest"))
                    MeasurementInput(MeasurementGuides.arm, d.arm, { v -> vm.edit { it.copy(arm = v) } }, isError = errors.containsKey("arm"))
                    MeasurementInput(MeasurementGuides.thigh, d.thigh, { v -> vm.edit { it.copy(thigh = v) } }, isError = errors.containsKey("thigh"))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- 6 photos

@Composable
private fun PhotosStep(state: OnboardingUiState, vm: OnboardingViewModel, onCapture: (List<Pose>) -> Unit) {
    SecureWindow()
    val d = state.draft
    var pickingFor by rememberSaveable { mutableStateOf<Pose?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val pose = pickingFor
        if (uri != null && pose != null) vm.addPhoto(pose, uri)
        pickingFor = null
    }

    StepHeader(
        "Progress photos",
        "Optional. Front, side and back photos show change far better than the scale. Forge doesn't keep them: " +
            "take them with your phone's camera and keep them in your gallery - an album called \"Progress\" works " +
            "well - with the same pose, light and time each month."
    )
    Expandable("How to take comparable photos") { BulletList(PhotoProtocol.steps) }

    // Photos here exist only to be analysed once. Where no analysis can run, picking them
    // would achieve nothing, so the step explains instead of offering slots.
    when {
        d.effectivePregnancy == PregnancyStatus.PREGNANT -> {
            NoteCard(
                title = "Photo analysis is off during pregnancy",
                body = "Keep taking photos for your own record in your gallery if you like; Forge won't estimate body fat from them."
            )
            return
        }
        !state.photoAnalysisAllowed -> {
            NoteCard(
                title = "Photo analysis isn't available right now",
                body = "Your tape measurements still give a body-fat range. Any photos you take are yours to keep in your gallery.",
                tone = NoteTone.SUPPORT
            )
            return
        }
        !state.aiConfigured -> {
            ConnectAiCard(
                body = "Photos can give a one-time body-fat estimate with an AI key from any supported provider. Without one, " +
                    "your tape measurements still give a body-fat range. Forge only analyses photos - it never stores them.",
                onConnect = vm::openAiSetup
            )
            return
        }
        else -> Unit
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pose.entries.forEach { pose ->
            PoseSlot(
                pose = pose,
                photo = d.photos[pose],
                onCamera = { onCapture(listOf(pose)) },
                onGallery = {
                    pickingFor = pose
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRemove = { vm.removePhoto(pose) },
                modifier = Modifier.weight(1f)
            )
        }
    }
    FilledTonalButton(
        onClick = { onCapture(Pose.entries.filter { it !in d.photos.keys }.ifEmpty { Pose.entries }) },
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Outlined.PhotoCamera, null)
        Spacer(Modifier.width(8.dp))
        Text(if (d.photos.isEmpty()) "Take all three with the pose guide" else "Take the rest with the pose guide")
    }
    if (GalleryPhotos.canSaveDirectly) {
        SwitchRow(
            title = "Also save pose-guide shots to my gallery",
            subtitle = "Into ${GalleryPhotos.albumLabel}, so you have this month's set to compare later.",
            checked = d.saveCapturesToGallery,
            onCheckedChange = { v -> vm.edit { it.copy(saveCapturesToGallery = v) } }
        )
    }
    if (state.photoBusy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("Preparing photo...", style = MaterialTheme.typography.bodySmall)
        }
    }
    state.photoError?.let { NoteCard(title = "That photo didn't work", body = it, tone = NoteTone.CAUTION) }

    if (d.photos.isNotEmpty()) {
        ForgeCard {
            SwitchRow(
                title = "Send these photos for one body-fat estimate",
                subtitle = null,
                checked = d.photoConsent,
                onCheckedChange = { v -> vm.edit { it.copy(photoConsent = v) } }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "If you turn this on, the photos are sent once to " +
                    (state.bodyModel.ifBlank { "your AI provider" }) +
                    " and then discarded - Forge never saves them, on this phone or anywhere else. The answer is a " +
                    "body-fat range and nothing else. Estimates from photos are rough and not scientifically validated, " +
                    "and the AI is instructed never to comment on appearance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PoseSlot(
    pose: Pose,
    photo: HeldPhoto?,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (photo != null) {
                Image(
                    bitmap = photo.image,
                    contentDescription = "${pose.label} photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                FilledTonalIconButton(
                    onClick = onRemove,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp)
                ) { Icon(Icons.Outlined.Close, "Remove ${pose.label} photo", Modifier.size(16.dp)) }
            } else {
                Icon(Icons.Outlined.AddAPhoto, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(pose.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row {
            FilledTonalIconButton(onClick = onCamera, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.PhotoCamera, "Take ${pose.label} photo", Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalIconButton(onClick = onGallery, shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.PhotoLibrary, "Choose ${pose.label} photo", Modifier.size(18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------- 7 analysis

@Composable
private fun AnalysisStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    StepHeader("Your starting point", "Body fat as a likely range, with where each number came from.")
    val a = state.analysis
    if (state.analysing || a == null) {
        CenteredLoading("Working out your likely range...")
        return
    }
    a.skippedReason?.let {
        NoteCard(title = "No body-fat estimate", body = it, tone = NoteTone.SUPPORT)
        return
    }
    if (state.draft.hideNumbers) {
        NoteCard(
            title = "Numbers hidden",
            body = "You chose to hide numbers, so Forge isn't showing body-fat figures. They're only used to pick a safe starting point.",
            tone = NoteTone.SUPPORT
        )
    } else {
        val bf = a.bodyFat
        if (bf == null) {
            NoteCard(
                title = "No body-fat estimate - that's fine",
                body = "Forge will start from your weight, goals and preferences, and your waist and strength trends will guide the next step."
            )
        } else {
            bf.navy?.let { RangeCard("Tape measure", it, "US Navy formula, shown as a likely range. Same tape, same protocol over time matters more than the exact figure.") }
            bf.photo?.let {
                RangeCard(
                    "Photos",
                    it,
                    "AI estimate - unvalidated" + (a.photoConfidence?.let { c -> ", model confidence ${c.lowercase()}" } ?: "") +
                        (a.photoIssues?.let { i -> ". Photo notes: $i" } ?: "")
                )
            }
            if (bf.disagreement) {
                NoteCard(
                    title = "Your two estimates don't overlap",
                    body = "Rather than averaging them, Forge shows both and leans on your waist and your preference when choosing a phase.",
                    tone = NoteTone.CAUTION
                )
            } else if (bf.navy != null && bf.photo != null) {
                bf.combined?.let { RangeCard("Combined", it, "Both methods weighted by how uncertain each one is.") }
            }
            Text(
                "No tape or photo method has been validated specifically for South Asian adults, and every method has errors of several points. " +
                    "Your waist and strength trends are the main progress signals from here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    a.photoNote?.let { note ->
        NoteCard(title = "About your photos", body = note) {
            if (state.draft.photos.isNotEmpty() && state.photoAnalysisAllowed && state.draft.photoConsent &&
                state.aiConfigured && !a.photoRateLimited
            ) {
                OutlinedButton(onClick = vm::retryAnalysis, shapes = ButtonDefaults.shapes()) { Text("Try the photo estimate again") }
            }
        }
    }
}

/** Optional: opens Settings > AI on top of onboarding; nothing is required to continue. */
@Composable
private fun ConnectAiCard(body: String, onConnect: () -> Unit) {
    NoteCard(title = "Connect an AI provider (optional)", body = body) {
        OutlinedButton(onClick = onConnect, shapes = ButtonDefaults.shapes()) { Text("Add an AI key") }
    }
}

@Composable
private fun RangeCard(title: String, range: BodyFatRange, caption: String) {
    ForgeCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("about ${range.label}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(10.dp))
        RangeBar(range)
        Spacer(Modifier.height(8.dp))
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CenteredLoading(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LoadingIndicator(Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------- 8 recommendation

@Composable
private fun RecommendationStep(state: OnboardingUiState, vm: OnboardingViewModel) {
    StepHeader("Your recommended phase", null)
    val p = state.proposal
    if (state.planning) {
        CenteredLoading(if (state.aiConfigured) "Weighing your options inside the safety rules..." else "Applying the evidence-based rules...")
        return
    }
    if (p == null) {
        NoteCard(title = "Couldn't build a plan", body = state.planError ?: "Something went wrong.", tone = NoteTone.CAUTION) {
            OutlinedButton(onClick = vm::retryPlan, shapes = ButtonDefaults.shapes()) { Text("Try again") }
        }
        return
    }
    if (!state.aiConfigured) {
        ConnectAiCard(
            body = "This plan comes from Forge's built-in rules. With an AI key, the AI weighs your answers and " +
                "measurements to pick the phase, always inside the same safety rules.",
            onConnect = vm::openAiSetup
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(22.dp)
    ) {
        Badge(planSourceLabel(p.source), color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(12.dp))
        Text(
            PhaseCopy.name(p.phase),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.height(6.dp))
        Text(PhaseCopy.definition(p.phase), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    p.sourceNote?.let { NoteCard(title = "How this plan was made", body = it) }
    ForgeCard {
        Text("Why", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(p.rationale, style = MaterialTheme.typography.bodyMedium)
        if (p.decision.straddles && p.source != "AI") {
            Spacer(Modifier.height(8.dp))
            Text(
                "Your range crosses one of the decision thresholds, so it's worth revisiting at your first review.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    p.safetyNotes.forEach { NoteCard(title = "Safety", body = it, tone = NoteTone.CAUTION, icon = Icons.Outlined.HealthAndSafety) }
    if (p.decision.candidates.size > 1) {
        SectionHeader("Other safe options")
        p.decision.candidates.forEach { c ->
            ChoiceCard(
                title = PhaseCopy.name(c) + if (c == p.decision.recommended) " · recommended by the rules" else "",
                subtitle = PhaseCopy.definition(c),
                selected = c == p.phase,
                onClick = { vm.choosePhase(c) }
            )
        }
    }
}

// ---------------------------------------------------------------------------- 9 review

@Composable
private fun ReviewStep(state: OnboardingUiState, onOpenProgram: ((String) -> Unit)?) {
    val p = state.proposal ?: return
    val d = state.draft
    val hide = d.hideNumbers
    StepHeader("Your plan", "${PhaseCopy.name(p.phase)} · ${planSourceLabel(p.source)}")

    ForgeCard {
        Text("Daily targets", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        TargetsRow(p.checked.kcal, p.checked.proteinG, p.checked.carbsG, p.checked.fatG, hideCalories = hide)
        Spacer(Modifier.height(10.dp))
        if (!hide) {
            Text(
                "Maintenance estimate ${p.tdee.roundToInt()} kcal - ${p.details.tdeeBasis}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
        }
        Text("Fibre: at least ${p.checked.fiberG} g a day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    ForgeCard {
        Text("Pace and movement", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val rateKg = p.checked.rateKgPerWeek
        val rateText = when {
            abs(rateKg) < 0.005 -> "Hold your weekly-average weight steady."
            hide -> if (rateKg > 0) "A slow, steady gain." else "A slow, steady loss."
            else -> "%+.2f %s a week (%+.2f%% of bodyweight).".format(
                NutritionMath.kgToDisplay(rateKg, d.unit), d.unit.label, p.built.ratePctPerWeek
            )
        }
        Text(rateText, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text("Steps: about ${"%,d".format(p.built.stepsTarget)} a day, kept steady through the phase.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Review: ${Dates.shortLabel(Dates.plusDays(Dates.today(), minOf(p.built.plannedWeeks, 12) * 7))} " +
                "(${p.built.plannedWeeks} weeks planned).",
            style = MaterialTheme.typography.bodyMedium
        )
    }

    ForgeCard {
        Text("Check-ins", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        BulletList(p.details.checkIns)
    }

    ForgeCard {
        Text("Training", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(p.details.training, style = MaterialTheme.typography.bodyMedium)
        if (p.programs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Programs that fit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.programs.forEach { prog ->
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .then(if (onOpenProgram != null) Modifier.clickable { onOpenProgram(prog.id) } else Modifier)
                        .padding(12.dp)
                ) {
                    Text(prog.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${prog.author} · ${prog.daysPerWeek} days/week", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (prog.summary.isNotBlank()) Text(prog.summary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (onOpenProgram != null) "Tap one to see it, or find them later in Train → Library." else "Find them in Train → Library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    ForgeCard {
        Text("When to change course", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        BulletList(p.details.stopConditions)
        if (p.details.nextPhase.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Likely next: ${p.details.nextPhase}", style = MaterialTheme.typography.bodyMedium)
        }
    }

    val notes = (p.checked.notes + p.built.notes + p.details.notes).distinct()
    if (notes.isNotEmpty()) {
        NoteCard(title = "Notes and safety adjustments", body = "") { BulletList(notes) }
    }
    p.details.aiModel?.let {
        Text("Plan written with $it inside Forge's rules and safety limits.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------- 10 accept

@Composable
private fun AcceptStep(state: OnboardingUiState) {
    val d = state.draft
    if (state.done) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.TaskAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(16.dp))
            Text("You're all set", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Your first check-in is in a week.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    StepHeader("Ready to start", "Here's what Forge will save on this phone.")
    val measurements = listOf(d.waist, d.neck, d.hips, d.chest, d.arm, d.thigh).count { d.cm(it) != null }
    ForgeCard {
        BulletList(
            buildList {
                add("Your ${PhaseCopy.name(state.proposal?.phase ?: "").lowercase()} plan and daily targets")
                add("Today's weight")
                if (measurements > 0) add("$measurements tape measurement${if (measurements == 1) "" else "s"}")
                // Photos are deliberately not in this list: Forge used them for the estimate only.
                add("Your answers, so the plan can be re-checked safely later")
            }
        )
    }
    state.saveError?.let { NoteCard(title = "Saving didn't work", body = it, tone = NoteTone.CAUTION) }
    Text(
        "You can change anything later in Profile, and re-plan whenever your situation changes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

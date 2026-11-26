package com.laksh.forge.feature.onboarding

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import androidx.room.withTransaction
import com.laksh.forge.data.db.BodyMeasurement
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.data.remote.Task
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.guardrails.GuardrailProfile
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.plan.AiPlanChoice
import com.laksh.forge.domain.plan.BodyFatCombiner
import com.laksh.forge.domain.plan.BodyFatRange
import com.laksh.forge.domain.plan.BodyFatSummary
import com.laksh.forge.domain.plan.BuildInput
import com.laksh.forge.domain.plan.BuiltTargets
import com.laksh.forge.domain.plan.CheckedTargets
import com.laksh.forge.domain.plan.CombinedBodyFat
import com.laksh.forge.domain.plan.Equipment
import com.laksh.forge.domain.plan.NavyBodyFat
import com.laksh.forge.domain.plan.PhaseCopy
import com.laksh.forge.domain.plan.PhaseDecision
import com.laksh.forge.domain.plan.PhaseInputs
import com.laksh.forge.domain.plan.PhasePreference
import com.laksh.forge.domain.plan.PhaseRules
import com.laksh.forge.domain.plan.PlanAi
import com.laksh.forge.domain.plan.PlanBuilder
import com.laksh.forge.domain.plan.PlanDetails
import com.laksh.forge.domain.plan.PlanProfile
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.plan.PlanStore
import com.laksh.forge.domain.plan.Pose
import com.laksh.forge.domain.plan.TrainingGoal
import com.laksh.forge.domain.plan.edScreenLockActive
import com.laksh.forge.domain.plan.libraryEquipment
import com.laksh.forge.domain.program.LibraryProgramSummary
import com.laksh.forge.domain.program.ProgramLibrary
import com.laksh.forge.feature.progress.GalleryPhotos
import com.laksh.forge.feature.progress.ProgressPhotoFiles
import com.laksh.forge.util.ImageUtils
import kotlinx.coroutines.NonCancellable
import java.io.File
import java.time.LocalDate
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class OnboardingStep(val label: String) {
    WELCOME("Welcome"),
    BASICS("About you"),
    TRAINING("Training"),
    HEALTH("Health & safety"),
    MEASUREMENTS("Measurements"),
    PHOTOS("Photos"),
    ANALYSIS("Analysis"),
    RECOMMENDATION("Recommendation"),
    REVIEW("Your plan"),
    ACCEPT("Start")
}

/**
 * A photo held in memory for the one onboarding estimate - never written to disk. A plain
 * class on purpose: identity equality makes a retake a different photo set, so a cached
 * estimate is never reused for new photos, without comparing pixels.
 */
class HeldPhoto(val bitmap: Bitmap) {
    val image: ImageBitmap = bitmap.asImageBitmap()
}

/** Everything typed so far. Numbers stay as text so half-typed input is never reformatted. */
data class OnboardingDraft(
    val sex: Sex = Sex.MALE,
    val age: String = "",
    val heightCm: String = "",
    val weight: String = "",
    val unit: WeightUnit = WeightUnit.KG,
    val southAsian: Boolean = false,

    val trainingAge: TrainingAge = TrainingAge.UNKNOWN,
    val detrained: Boolean = false,
    val daysPerWeek: Int = 3,
    val equipment: Equipment = Equipment.FULL_GYM,
    val goal: TrainingGoal? = null,
    val preference: PhasePreference = PhasePreference.NO_PREFERENCE,
    val recentDiet: Boolean = false,

    val pregnancy: PregnancyStatus = PregnancyStatus.NONE,
    val kidneyDisease: Boolean = false,
    val diabetesMedication: Boolean = false,
    val otherCondition: Boolean = false,
    val redFlagSymptoms: Boolean = false,
    val scoff: List<Boolean?> = List(5) { null },
    /** Free text screened for crisis language, then discarded - never persisted. */
    val healthNote: String = "",
    val hideNumbers: Boolean = false,

    val noTape: Boolean = false,
    val waist: String = "",
    val neck: String = "",
    val hips: String = "",
    val healthWaist: String = "",
    val chest: String = "",
    val arm: String = "",
    val thigh: String = "",

    /** In-memory photos per pose, used only for the estimate; Forge never saves them. */
    val photos: Map<Pose, HeldPhoto> = emptyMap(),
    val photoConsent: Boolean = false,
    /** Pose-guide shots are also copied to the person's own gallery (Pictures/Forge Progress). */
    val saveCapturesToGallery: Boolean = false
) {
    val ageInt: Int? get() = age.toIntOrNull()
    val heightD: Double? get() = heightCm.toDoubleOrNull()
    val weightKg: Double? get() = weight.toDoubleOrNull()?.let { NutritionMath.displayToKg(it, unit) }
    val isMinor: Boolean get() = (ageInt ?: 99) < 18
    val effectivePregnancy: PregnancyStatus get() = if (sex == Sex.FEMALE) pregnancy else PregnancyStatus.NONE
    val scoffAnswered: Boolean get() = scoff.all { it != null }
    val scoffPositive: Boolean get() = scoffAnswered && Guardrails.scoffPositive(scoff.map { it == true })

    fun cm(s: String): Double? = s.toDoubleOrNull()?.takeIf { it > 0 }
}

data class AnalysisResult(
    val navyRaw: Double? = null,
    val bodyFat: CombinedBodyFat? = null,
    val photoConfidence: String? = null,
    val photoIssues: String? = null,
    /** Why the photo estimate did not run or failed; shown calmly, never blocks progress. */
    val photoNote: String? = null,
    /** Set when body fat is deliberately not estimated (under 18). */
    val skippedReason: String? = null,
    /** The 14-day rule stopped the estimate; retrying can't help, so no retry is offered. */
    val photoRateLimited: Boolean = false,
    /** A fresh AI photo estimate is part of this result (persisted as the last estimate on save). */
    val photoEstimated: Boolean = false
)

data class PlanProposal(
    val decision: PhaseDecision,
    val phase: String,
    val built: BuiltTargets,
    val checked: CheckedTargets,
    val details: PlanDetails,
    val rationale: String,
    /** AI | RULES | MANUAL */
    val source: String,
    /** Why the plan is rules-based (no key, AI failure) - shown as a label. */
    val sourceNote: String?,
    val programs: List<LibraryProgramSummary>,
    val tdee: Double,
    val safetyNotes: List<String>
)

data class OnboardingUiState(
    val loaded: Boolean = false,
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val draft: OnboardingDraft = OnboardingDraft(),
    val isRedo: Boolean = false,
    /**
     * A phase plan (active or past) already exists. Distinct from [isRedo]: a v1 user is
     * onboarded but has never had a plan, so the copy says "build", not "rebuild".
     */
    val hasPlan: Boolean = false,
    val aiConfigured: Boolean = false,
    val bodyModel: String = "",
    val crisisDisclosed: Boolean = false,
    /** A crisis flag from an earlier session that is still inside its 30-day lock. */
    val priorCrisisLock: Boolean = false,
    /** A positive SCOFF from an earlier session still inside its 30-day lock; a redo can't lift it. */
    val priorEdLock: Boolean = false,
    /** An open low-intake / rapid-loss safety event from the weekly check-in. */
    val priorSafetyEvent: Boolean = false,
    val showErrors: Boolean = false,
    val photoBusy: Boolean = false,
    val photoError: String? = null,
    val analysing: Boolean = false,
    val analysis: AnalysisResult? = null,
    val planning: Boolean = false,
    val planError: String? = null,
    val proposal: PlanProposal? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val done: Boolean = false,
    /** Settings > AI is open on top of onboarding so a first-run user can add a key. */
    val connectingAi: Boolean = false,
    val baseSettings: ForgeSettings = ForgeSettings(),
    val lastPhotoEstimateAt: Long = 0L
) {
    val eatingConcern: Boolean get() = draft.scoffPositive || crisisDisclosed || priorCrisisLock || priorEdLock

    /** Only a concern raised in this run starts a new 30-day lock; an inherited one keeps its date. */
    val newEatingConcern: Boolean get() = draft.scoffPositive || crisisDisclosed

    /** guardrails photo_feature_blocked_groups: minors, pregnancy, a positive screen or open crisis. */
    val photoAnalysisAllowed: Boolean
        get() = !draft.isMinor && draft.effectivePregnancy != PregnancyStatus.PREGNANT && !eatingConcern && !priorSafetyEvent

    /** Photos are skipped entirely for minors and after an eating concern, to avoid body-checking. */
    val showPhotoStep: Boolean get() = !draft.isMinor && !eatingConcern

    val steps: List<OnboardingStep>
        get() = OnboardingStep.entries.filter { it != OnboardingStep.PHOTOS || showPhotoStep }

    val stepIndex: Int get() = steps.indexOf(step).coerceAtLeast(0)
}

/** Validation messages per field, computed from the draft so the UI never stores them. */
object OnboardingValidation {
    fun basics(d: OnboardingDraft): Map<String, String> = buildMap {
        val age = d.ageInt
        if (age == null || age !in 10..100) put("age", "Enter your age in years")
        val h = d.heightD
        if (h == null || h !in 120.0..230.0) put("height", "Enter your height in cm (120-230)")
        val w = d.weightKg
        if (w == null || w !in 30.0..300.0) put("weight", "Enter your current weight")
    }

    fun training(d: OnboardingDraft): Map<String, String> = buildMap {
        if (d.trainingAge == TrainingAge.UNKNOWN) put("trainingAge", "Pick the closest description")
        if (d.goal == null) put("goal", "Pick what matters most right now")
    }

    fun health(d: OnboardingDraft): Map<String, String> = buildMap {
        if (!d.scoffAnswered) put("scoff", "Please answer all five questions - yes or no is fine")
    }

    private fun site(v: String) = v.toDoubleOrNull()?.takeIf { it in 15.0..250.0 }

    fun measurements(d: OnboardingDraft): Map<String, String> = buildMap {
        fun optional(key: String, v: String) {
            if (v.isNotBlank() && site(v) == null) put(key, "Check this value (cm)")
        }
        // Adults need waist and neck (and hips for women) for the tape estimate unless they
        // say they have no tape; under-18s never get a body-fat estimate, so all are optional.
        val required = !d.noTape && !d.isMinor
        if (required) {
            if (site(d.waist) == null) put("waist", "Needed for the tape estimate")
            if (site(d.neck) == null) put("neck", "Needed for the tape estimate")
            if (d.sex == Sex.FEMALE && site(d.hips) == null) put("hips", "Needed for the tape estimate")
            val w = site(d.waist)
            val n = site(d.neck)
            if (w != null && n != null && w <= n) put("waist", "Waist should be larger than neck - please re-measure")
        } else {
            optional("waist", d.waist); optional("neck", d.neck); optional("hips", d.hips)
        }
        optional("healthWaist", d.healthWaist)
        optional("chest", d.chest); optional("arm", d.arm); optional("thigh", d.thigh)
    }

    fun forStep(step: OnboardingStep, d: OnboardingDraft): Map<String, String> = when (step) {
        OnboardingStep.BASICS -> basics(d)
        OnboardingStep.TRAINING -> training(d)
        OnboardingStep.HEALTH -> health(d)
        OnboardingStep.MEASUREMENTS -> measurements(d)
        else -> emptyMap()
    }
}

class OnboardingViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var analysisJob: Job? = null
    private var planJob: Job? = null
    private var analysisKey: Any? = null
    private var planKey: Any? = null
    /** The AI's answer is kept so switching phases away and back does not lose its words. */
    private var aiChoice: AiPlanChoice? = null

    /**
     * The photo estimate for one photo set: reused when other answers change, so going back to
     * fix a typo neither spends a second estimate nor trips the 14-day rule on the first.
     */
    private data class PhotoEstimate(val key: Any, val range: BodyFatRange, val confidence: String?, val issues: String?)
    private var photoEstimate: PhotoEstimate? = null

    private val app get() = ForgeApp.instance

    init {
        // Warm the program library off the main thread; recommend() reads it later.
        viewModelScope.launch(Dispatchers.IO) { runCatching { ProgramLibrary.all() } }
        viewModelScope.launch {
            val s = repo.settings()
            val profile = PlanProfileStore.current()
            val latest = PlanStore.latestWeightKg(repo)
            val redo = s.onboarded
            val hasPlan = runCatching { PlanStore.planDao.allPlans().isNotEmpty() }.getOrDefault(false)
            _state.update {
                it.copy(
                    loaded = true,
                    isRedo = redo,
                    hasPlan = hasPlan,
                    aiConfigured = repo.ai.isConfigured,
                    bodyModel = runCatching { repo.ai.describe(Task.BODY) }.getOrDefault(""),
                    baseSettings = s,
                    priorCrisisLock = profile.crisisLockActive(),
                    priorEdLock = edScreenLockActive(s),
                    priorSafetyEvent = profile.safetyEventOpen(),
                    lastPhotoEstimateAt = profile.lastPhotoEstimateAt,
                    draft = if (!redo) OnboardingDraft(unit = s.weightUnit) else OnboardingDraft(
                        sex = s.sex,
                        age = s.age.toString(),
                        heightCm = s.heightCm.roundToInt().toString(),
                        weight = latest?.let { kg -> "%.1f".format(NutritionMath.kgToDisplay(kg, s.weightUnit)) }.orEmpty(),
                        unit = s.weightUnit,
                        southAsian = profile.southAsian,
                        trainingAge = s.trainingAge,
                        detrained = profile.detrained,
                        daysPerWeek = profile.daysPerWeek.takeIf { d -> d in 2..6 } ?: 3,
                        equipment = profile.equipment,
                        goal = profile.trainingGoal,
                        preference = profile.preference,
                        pregnancy = s.pregnancyStatus,
                        kidneyDisease = profile.kidneyDisease,
                        diabetesMedication = profile.diabetesMedication,
                        otherCondition = profile.otherWeightCondition,
                        hideNumbers = s.hideNumbers
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------ editing

    fun edit(transform: (OnboardingDraft) -> OnboardingDraft) {
        _state.update { it.copy(draft = transform(it.draft)) }
    }

    fun setScoff(index: Int, yes: Boolean) = edit { d ->
        d.copy(scoff = d.scoff.toMutableList().also { it[index] = yes })
    }

    // ------------------------------------------------------------------ navigation

    /** Validates the current step; returns true when it moved on. */
    fun next(): Boolean {
        val s = _state.value
        val errors = OnboardingValidation.forStep(s.step, s.draft)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(showErrors = true) }
            return false
        }
        if (s.step == OnboardingStep.HEALTH) {
            // The note is screened once on leaving the step; a match keeps the person here so the
            // support card is seen before anything else. The words themselves are never stored.
            val crisis = s.draft.healthNote.isNotBlank() && Guardrails.detectCrisis(s.draft.healthNote)
            if (crisis && !s.crisisDisclosed) {
                _state.update { it.copy(crisisDisclosed = true, draft = it.draft.copy(healthNote = "")) }
                return false
            }
            _state.update { it.copy(draft = it.draft.copy(healthNote = "")) }
        }
        val steps = _state.value.steps
        val idx = steps.indexOf(s.step)
        val target = steps.getOrNull(idx + 1) ?: return false
        goTo(target)
        return true
    }

    fun back(): Boolean {
        val s = _state.value
        val idx = s.steps.indexOf(s.step)
        if (idx <= 0 || s.saving) return false
        goTo(s.steps[idx - 1])
        return true
    }

    private fun goTo(step: OnboardingStep) {
        // A key can be added from the AI card on these steps (or Settings) after init ran.
        if (step == OnboardingStep.ANALYSIS || step == OnboardingStep.RECOMMENDATION) refreshAiConfigured()
        _state.update { it.copy(step = step, showErrors = false) }
        when (step) {
            OnboardingStep.ANALYSIS -> runAnalysis()
            OnboardingStep.RECOMMENDATION -> runRecommendation()
            else -> Unit
        }
    }

    private fun refreshAiConfigured() {
        val configured = repo.ai.isConfigured
        if (configured == _state.value.aiConfigured) return
        _state.update { it.copy(aiConfigured = configured) }
        viewModelScope.launch {
            val model = runCatching { repo.ai.describe(Task.BODY) }.getOrDefault("")
            _state.update { it.copy(bodyModel = model) }
        }
    }

    fun openAiSetup() = _state.update { it.copy(connectingAi = true) }

    fun closeAiSetup() {
        _state.update { it.copy(connectingAi = false) }
        onAiConnected()
    }

    /** Called after a key is saved from onboarding: re-run whatever the missing key held back. */
    fun onAiConnected() {
        val was = _state.value.aiConfigured
        refreshAiConfigured()
        if (was || !repo.ai.isConfigured) return
        when (_state.value.step) {
            OnboardingStep.ANALYSIS -> retryAnalysis()
            OnboardingStep.RECOMMENDATION -> retryPlan()
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ photos

    /**
     * Decode a picked or captured photo into memory at analysis size. A pose-guide capture is a
     * temporary cache file: it is optionally copied to the person's gallery, then deleted
     * whatever happens, so onboarding leaves no photo in Forge's storage.
     */
    fun addPhoto(pose: Pose, uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(photoBusy = true, photoError = null) }
            val isCapture = uri.scheme == "file"
            try {
                attempt { GalleryPhotos.load(app, uri, ANALYSIS_EDGE) }
                    .onSuccess { loaded ->
                        _state.update {
                            it.copy(photoBusy = false, draft = it.draft.copy(photos = it.draft.photos + (pose to HeldPhoto(loaded.bitmap))))
                        }
                    }
                    .onFailure { e -> _state.update { it.copy(photoBusy = false, photoError = e.message ?: "That photo couldn't be used.") } }
                if (isCapture && _state.value.draft.saveCapturesToGallery && GalleryPhotos.canSaveDirectly) {
                    val path = uri.path
                    if (path != null) {
                        attempt {
                            GalleryPhotos.saveToGallery(app, File(path), GalleryPhotos.displayName(LocalDate.now(), pose.label), System.currentTimeMillis())
                        }.onFailure { e ->
                            _state.update { it.copy(photoError = "The photo is ready for the estimate, but saving it to your gallery didn't work: ${e.message ?: "unknown error"}") }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { ImageUtils.discardCapture(app, uri) }
                _state.update { it.copy(photoBusy = false) }
            }
        }
    }

    fun removePhoto(pose: Pose) = edit { it.copy(photos = it.photos - pose) }

    fun clearPhotoError() = _state.update { it.copy(photoError = null) }

    // ------------------------------------------------------------------ analysis

    private fun draftSettings(s: OnboardingUiState): ForgeSettings {
        val d = s.draft
        return s.baseSettings.copy(
            sex = d.sex,
            age = d.ageInt ?: s.baseSettings.age,
            heightCm = d.heightD ?: s.baseSettings.heightCm,
            weightUnit = d.unit,
            trainingAge = d.trainingAge,
            pregnancyStatus = d.effectivePregnancy,
            // A crisis disclosure applies the same locks as a positive screen.
            edScreenPositive = s.eatingConcern,
            hideNumbers = d.hideNumbers
        )
    }

    private fun draftProfile(s: OnboardingUiState, base: PlanProfile = PlanProfile()): PlanProfile {
        val d = s.draft
        return base.copy(
            kidneyDisease = d.kidneyDisease,
            diabetesMedication = d.diabetesMedication,
            otherWeightCondition = d.otherCondition,
            southAsian = d.southAsian,
            detrained = d.detrained,
            trainingGoal = d.goal ?: TrainingGoal.HEALTH,
            preference = d.preference,
            daysPerWeek = d.daysPerWeek,
            equipment = d.equipment,
            crisisFlaggedAt = if (s.crisisDisclosed) System.currentTimeMillis() else base.crisisFlaggedAt
        )
    }

    fun retryAnalysis() {
        analysisKey = null
        runAnalysis()
    }

    private fun runAnalysis() {
        val s = _state.value
        val d = s.draft
        val key = listOf(d.sex, d.age, d.heightCm, d.weight, d.unit, d.noTape, d.waist, d.neck, d.hips, d.photos, d.photoConsent, s.photoAnalysisAllowed)
        if (key == analysisKey && s.analysis != null) return
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _state.update { it.copy(analysing = true, analysis = null, proposal = null) }
            planKey = null
            val result = analyse(_state.value)
            analysisKey = key
            _state.update { it.copy(analysing = false, analysis = result) }
        }
    }

    private suspend fun analyse(s: OnboardingUiState): AnalysisResult {
        val d = s.draft
        if (d.isMinor) {
            return AnalysisResult(
                skippedReason = "Because you're under 18, Forge doesn't estimate body fat. Your body is still " +
                    "growing, and fuelling it well matters most right now."
            )
        }
        val height = d.heightD ?: return AnalysisResult()
        val navyRaw = if (d.noTape) null else NavyBodyFat.estimate(d.sex, d.cm(d.waist), d.cm(d.neck), d.cm(d.hips), height)
        val navy = navyRaw?.let { NavyBodyFat.range(d.sex, it) }

        var photo: BodyFatRange? = null
        var confidence: String? = null
        var issues: String? = null
        var photoNote: String? = null
        var rateLimited = false
        var estimated = false
        val minGapMs = 14L * 86_400_000L
        val photoKey = listOf(d.photos, d.photoConsent)
        val cached = photoEstimate?.takeIf { it.key == photoKey }
        when {
            d.photos.isEmpty() -> Unit
            !s.photoAnalysisAllowed -> photoNote = "Photo analysis is off for you right now, so the photos weren't used. Forge didn't keep them."
            !d.photoConsent -> photoNote = "You didn't allow photo analysis, so the photos weren't used. Forge didn't keep them."
            cached != null -> {
                photo = cached.range
                confidence = cached.confidence
                issues = cached.issues
                estimated = true
            }
            !repo.ai.isConfigured -> photoNote = "No AI provider is set up, so the photos weren't used. Forge didn't keep them."
            System.currentTimeMillis() - s.lastPhotoEstimateAt < minGapMs -> {
                photoNote = "Forge allows one photo estimate every 14 days, so this time the photos weren't used."
                rateLimited = true
            }
            else -> {
                val settings = draftSettings(s)
                val weightKg = d.weightKg ?: 70.0
                attempt {
                    // The base64 copies live only for this call; the in-memory bitmaps go when
                    // onboarding ends.
                    val images = withContext(Dispatchers.Default) {
                        d.photos.entries.sortedBy { it.key.ordinal }
                            .map { (pose, held) -> pose to ImageUtils.toBase64Jpeg(held.bitmap, 85) }
                    }
                    PlanAi.estimateBodyFat(
                        ai = repo.ai,
                        profile = PlanStore.guardrailProfile(settings, weightKg, null),
                        sex = d.sex,
                        age = d.ageInt ?: settings.age,
                        heightCm = height,
                        weightKg = weightKg,
                        navy = navy,
                        photos = images
                    )
                }.onSuccess { est ->
                    val range = BodyFatCombiner.photoRange(est.estimatePct, est.lowPct, est.highPct)
                    photo = range
                    confidence = est.confidence
                    issues = est.photoIssues.ifBlank { null }
                    estimated = true
                    photoEstimate = PhotoEstimate(photoKey, range, confidence, issues)
                    // Held in memory only: a different photo set in this run counts against the
                    // 14 days, but the stored date is written on save so abandoning onboarding
                    // doesn't lock the estimate for two weeks.
                    _state.update { it.copy(lastPhotoEstimateAt = System.currentTimeMillis()) }
                }.onFailure { e ->
                    photoNote = (e.message ?: "The photo estimate didn't work.") + " Your tape estimate is used on its own."
                }
            }
        }
        val combined = BodyFatCombiner.combine(navy, photo)
        return AnalysisResult(
            navyRaw = navyRaw,
            bodyFat = combined.takeIf { navy != null || photo != null },
            photoConfidence = confidence,
            photoIssues = issues,
            photoNote = photoNote,
            photoRateLimited = rateLimited,
            photoEstimated = estimated
        )
    }

    // ------------------------------------------------------------------ recommendation

    fun retryPlan() {
        planKey = null
        runRecommendation()
    }

    private fun runRecommendation() {
        val s = _state.value
        val key = listOf(s.draft, s.analysis, s.crisisDisclosed)
        if (key == planKey && s.proposal != null) return
        planJob?.cancel()
        planJob = viewModelScope.launch {
            _state.update { it.copy(planning = true, planError = null, proposal = null) }
            aiChoice = null
            attempt { recommend(_state.value) }
                .onSuccess { p ->
                    planKey = key
                    _state.update { it.copy(planning = false, proposal = p) }
                }
                .onFailure { e -> _state.update { it.copy(planning = false, planError = e.message ?: "Couldn't build a plan.") } }
        }
    }

    private suspend fun recommend(s: OnboardingUiState): PlanProposal {
        val d = s.draft
        val settings = draftSettings(s)
        val weightKg = d.weightKg ?: throw IllegalStateException("Your weight is missing - go back to About you.")
        val heightCm = d.heightD ?: throw IllegalStateException("Your height is missing - go back to About you.")
        val profile = draftProfile(s, PlanProfileStore.current())
        val bf = s.analysis?.bodyFat
        // Safety inputs never use a photo-raised lower bound (photo_bf_accuracy_limits).
        val bfDecision = bf?.forSafety
        val gp = PlanStore.guardrailProfile(settings, weightKg, bfDecision?.low)
        val allowed = PlanStore.allowedPhases(gp, profile, settings, PlanStore.newCutBlock())
        val holds = PlanStore.safetyHolds(settings, profile)

        val decision = PhaseRules.decide(
            PhaseInputs(
                sex = d.sex,
                age = settings.age,
                heightCm = heightCm,
                weightKg = weightKg,
                trainingAge = d.trainingAge,
                detrained = d.detrained,
                goal = d.goal ?: TrainingGoal.HEALTH,
                preference = d.preference,
                bodyFat = if (d.isMinor) null else bfDecision,
                healthWaistCm = d.cm(d.healthWaist),
                southAsian = d.southAsian,
                recentlyFinishedDiet = d.recentDiet,
                safetyHolds = holds
            ),
            allowed
        )
        val (tdee, basis) = PlanStore.maintenance(settings, weightKg)
        val baseline = PlanStore.baselineSteps(repo)
        val bands = decision.candidates.associateWith { PlanBuilder.band(it, d.sex, d.trainingAge, bfDecision) }

        var phase = decision.recommended
        var source = "RULES"
        var sourceNote: String? = null
        if (repo.ai.isConfigured) {
            attempt {
                PlanAi.choosePlan(
                    repo.ai, gp,
                    PlanAi.PlanContext(
                        sex = d.sex, age = settings.age, heightCm = heightCm, weightKg = weightKg,
                        trainingAgeLabel = d.trainingAge.label, detrained = d.detrained,
                        goal = d.goal ?: TrainingGoal.HEALTH, preference = d.preference,
                        daysPerWeek = d.daysPerWeek, equipment = d.equipment,
                        bodyFat = if (d.isMinor) null else bf,
                        decision = decision, bands = bands, tdee = tdee, tdeeBasis = basis,
                        guardrailNotes = safetyNotes(s)
                    )
                )
            }.onSuccess { choice ->
                aiChoice = choice
                phase = choice.phase
                source = "AI"
            }.onFailure { e ->
                sourceNote = "Rules-based plan: the AI step didn't work this time (${e.message ?: "unknown error"})."
            }
        } else {
            sourceNote = "Rules-based plan: no AI provider is set up, so Forge used its built-in evidence-based rules."
        }
        return assemble(s, settings, profile, decision, phase, source, sourceNote, tdee, basis, baseline, gp, bf)
    }

    /** Switch to another safe candidate. The AI's words are reused only for the phase it chose. */
    fun choosePhase(phase: String) {
        val s = _state.value
        val p = s.proposal ?: return
        if (phase == p.phase || phase !in p.decision.candidates) return
        viewModelScope.launch {
            attempt {
                val settings = draftSettings(s)
                val weightKg = s.draft.weightKg ?: return@attempt null
                val profile = draftProfile(s, PlanProfileStore.current())
                val bf = s.analysis?.bodyFat
                val gp = PlanStore.guardrailProfile(settings, weightKg, bf?.forSafety?.low)
                val (tdee, basis) = PlanStore.maintenance(settings, weightKg)
                val ai = aiChoice
                val source = when {
                    ai != null && ai.phase == phase -> "AI"
                    ai == null && phase == p.decision.recommended -> "RULES"
                    else -> "MANUAL"
                }
                assemble(s, settings, profile, p.decision, phase, source, p.sourceNote, tdee, basis, PlanStore.baselineSteps(repo), gp, bf)
            }.onSuccess { np -> if (np != null) _state.update { it.copy(proposal = np) } }
                .onFailure { e -> _state.update { it.copy(planError = e.message) } }
        }
    }

    private fun safetyNotes(s: OnboardingUiState): List<String> {
        val d = s.draft
        return buildList {
            if (d.isMinor) add("Because you're under 18, Forge focuses on training, strength and healthy eating rather than weight-loss or body-fat goals.")
            when (d.effectivePregnancy) {
                PregnancyStatus.PREGNANT -> add(
                    "During pregnancy Forge won't set a calorie deficit or weight-loss goal. Please check your training plan " +
                        "with your doctor or midwife, and stop and call them if you have bleeding, dizziness or faintness, " +
                        "chest pain, headache, muscle weakness, calf pain or swelling, painful contractions, leaking fluid, " +
                        "or breathlessness before exercise."
                )
                PregnancyStatus.BREASTFEEDING -> add(
                    "While you're breastfeeding, Forge adds to your maintenance target and won't set a deficit. If you'd " +
                        "like to lose weight during this time, your doctor or a dietitian can plan it safely with you."
                )
                PregnancyStatus.NONE -> Unit
            }
            if (s.eatingConcern) add(
                "Forge has switched to maintenance and paused weight-loss features. You can talk to your doctor and ask " +
                    "about an eating-disorder specialist, or call Tele MANAS free at 14416 any time."
            )
            if (d.kidneyDisease) add("With kidney disease, any protein target should come from your nephrologist or renal dietitian.")
            if (d.diabetesMedication || d.otherCondition) add(
                "Because a medical condition affects your weight or nutrition, Forge keeps you at maintenance - please " +
                    "agree any weight change with your doctor."
            )
            if (d.redFlagSymptoms) add(
                "Please stop training until a doctor has checked the symptoms you mentioned. If you have chest pain that " +
                    "isn't easing, or you've fainted, call 112 now."
            )
        }
    }

    private suspend fun assemble(
        s: OnboardingUiState,
        settings: ForgeSettings,
        profile: PlanProfile,
        decision: PhaseDecision,
        phase: String,
        source: String,
        sourceNote: String?,
        tdee: Double,
        tdeeBasis: String,
        baselineSteps: Double?,
        gp: GuardrailProfile,
        bf: CombinedBodyFat?
    ): PlanProposal {
        val d = s.draft
        val weightKg = d.weightKg ?: throw IllegalStateException("Your weight is missing - go back to About you.")
        val ai = aiChoice?.takeIf { it.phase == phase && source == "AI" }
        val bfDecision = if (d.isMinor) null else bf?.forSafety
        val built = PlanBuilder.build(
            BuildInput(
                phase = phase,
                sex = d.sex,
                age = settings.age,
                weightKg = weightKg,
                trainingAge = d.trainingAge,
                tdee = tdee,
                bodyFat = bfDecision,
                kidneyDisease = d.kidneyDisease,
                breastfeeding = d.effectivePregnancy == PregnancyStatus.BREASTFEEDING,
                baselineSteps = baselineSteps,
                fatPerKg = settings.fatPerKg,
                ratePctOverride = ai?.ratePctPerWeek,
                postDiet = d.recentDiet,
                heightCm = d.heightD
            )
        )
        val checked = PlanStore.check(
            gp, built.kcal, built.proteinG, built.carbsG, built.fatG, built.rateKgPerWeek, tdee, weightKg, d.kidneyDisease
        )
        val (ruleStops, ruleNext) = PlanBuilder.stopConditions(phase, d.sex)
        // The library is ~1 MB of JSON parsed on first use; never on the main thread.
        val programs = withContext(Dispatchers.IO) {
            runCatching {
                ProgramLibrary.recommend(PhaseCopy.level(d.trainingAge), phase, d.daysPerWeek, d.equipment.libraryEquipment)
            }.getOrDefault(emptyList())
        }
        val rationale = when {
            ai != null -> ai.rationale
            phase == decision.recommended -> decision.explanation
            else -> "You chose a ${PhaseCopy.name(phase).lowercase()} from the safe options: " +
                PhaseCopy.definition(phase).replaceFirstChar { it.lowercase() } +
                " Forge's own suggestion was ${PhaseCopy.name(decision.recommended).lowercase()}, and either is reasonable for you."
        }
        val safety = safetyNotes(s)
        val details = PlanDetails(
            plannedWeeks = built.plannedWeeks,
            ratePctPerWeek = built.ratePctPerWeek,
            bandMinPct = built.band.minPct,
            bandMaxPct = built.band.maxPct,
            tdee = tdee.roundToInt(),
            tdeeBasis = tdeeBasis,
            bodyFat = if (d.isMinor) null else bf?.let { b ->
                val shown = b.combined ?: b.forDecision
                shown?.let {
                    BodyFatSummary(
                        low = it.low, high = it.high, method = it.method.label,
                        navyLow = b.navy?.low, navyHigh = b.navy?.high,
                        photoLow = b.photo?.low, photoHigh = b.photo?.high,
                        photoConfidence = s.analysis?.photoConfidence,
                        disagreement = b.disagreement
                    )
                }
            },
            rule = decision.rule.name,
            ruleExplanation = decision.explanation,
            candidates = decision.candidates,
            stopConditions = ai?.stopConditions?.takeIf { it.isNotEmpty() } ?: ruleStops,
            nextPhase = ai?.nextPhase?.takeIf { it.isNotBlank() } ?: ruleNext,
            training = ai?.training?.takeIf { it.isNotBlank() } ?: PlanBuilder.trainingText(d.trainingAge, d.detrained, phase),
            trainingDaysPerWeek = d.daysPerWeek,
            equipment = d.equipment.label,
            checkIns = PlanBuilder.checkInCadence,
            notes = (ai?.notes.orEmpty() + built.notes).distinct(),
            programIds = programs.map { it.id },
            programNames = programs.map { it.name },
            aiModel = if (ai != null) runCatching { repo.ai.describe(Task.PLAN) }.getOrNull() else null,
            citations = decision.rule.citations,
            weightKgAtStart = weightKg,
            postDiet = d.recentDiet
        )
        return PlanProposal(
            decision = decision,
            phase = phase,
            built = built,
            checked = checked,
            details = details,
            rationale = rationale,
            source = source,
            sourceNote = if (source == "AI") null else sourceNote,
            programs = programs,
            tdee = tdee,
            safetyNotes = safety
        )
    }

    // ------------------------------------------------------------------ accept

    fun accept() {
        val s = _state.value
        val p = s.proposal ?: return
        if (s.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, saveError = null) }
            attempt { save(s, p) }
                // The estimate is done with; drop the in-memory photos now rather than with the screen.
                .onSuccess { _state.update { it.copy(saving = false, done = true, draft = it.draft.copy(photos = emptyMap())) } }
                .onFailure { e -> _state.update { it.copy(saving = false, saveError = e.message ?: "Saving failed - please try again.") } }
        }
    }

    private suspend fun save(s: OnboardingUiState, p: PlanProposal) {
        val d = s.draft
        val now = System.currentTimeMillis()
        val today = Dates.today()
        val weightKg = d.weightKg ?: throw IllegalStateException("Your weight is missing.")
        val store = repo.settingsStore

        // Photos are deliberately absent: they were only ever used for the estimate, and
        // Forge doesn't keep progress photos.

        // Every row in one transaction: a failure leaves nothing behind, so a retry can't
        // duplicate the start weight (which would skew adaptive TDEE) or the measurement.
        ForgeDatabase.get(app).withTransaction {
            repo.weightDao.insert(
                WeightEntry(
                    timestamp = now,
                    localDate = Dates.dayOf(now),
                    kg = NutritionMath.displayToKg(d.weight.toDouble(), d.unit),
                    bodyFatPct = null,
                    note = "Start of ${PhaseCopy.name(p.phase).lowercase()} plan"
                )
            )

            val anyMeasurement = listOf(d.waist, d.neck, d.hips, d.chest, d.arm, d.thigh).any { d.cm(it) != null }
            if (anyMeasurement) {
                val navy = if (d.isMinor) null else s.analysis?.navyRaw
                PlanStore.bodyDao.insertMeasurement(
                    BodyMeasurement(
                        timestamp = now,
                        localDate = today,
                        waistCm = d.cm(d.waist),
                        neckCm = d.cm(d.neck),
                        hipsCm = d.cm(d.hips),
                        chestCm = d.cm(d.chest),
                        armCm = d.cm(d.arm),
                        thighCm = d.cm(d.thigh),
                        bodyFatPct = navy?.let { (it * 10).roundToInt() / 10.0 },
                        bodyFatMethod = if (navy != null) "NAVY" else null,
                        note = d.cm(d.healthWaist)?.let { "Health-site waist ${it} cm" }
                    )
                )
            }

            val plan = PlanStore.entity(
                phase = p.phase,
                checked = p.checked,
                built = p.built,
                details = p.details,
                rationale = p.rationale,
                source = p.source,
                bodyFatEstimate = p.details.bodyFat?.let { (it.low + it.high) / 2.0 }
            )
            PlanStore.planDao.activate(plan)
        }
        attempt { repo.recomputeAdaptiveTdee() }

        store.setSex(d.sex)
        d.ageInt?.let { store.setAge(it) }
        d.heightD?.let { store.setHeight(it) }
        store.setWeightUnit(d.unit)
        store.setTrainingAge(d.trainingAge)
        store.setPregnancyStatus(d.effectivePregnancy)
        // safety_scoff_positive_response: a lock lasts at least 30 days and lifts only after a
        // negative re-screen once they have passed. An inherited lock keeps its start date.
        when {
            s.newEatingConcern -> store.setEdScreen(true, now)
            s.eatingConcern -> Unit
            else -> store.setEdScreen(false, now)
        }
        store.setHideNumbers(d.hideNumbers)
        store.setGoal(PhaseCopy.goalFor(p.phase))
        store.setRate(p.checked.rateKgPerWeek)
        store.setProteinPerKg(((p.checked.proteinG / weightKg) * 10).roundToInt() / 10.0)
        store.setFatPerKg(((p.checked.fatG / weightKg) * 10).roundToInt() / 10.0)
        store.setStepsTarget(p.built.stepsTarget)
        store.setUseAutoTargets(true)

        PlanProfileStore.update { old ->
            draftProfile(s, old).copy(
                photoConsentAt = if (d.photoConsent && d.photos.isNotEmpty()) now else old.photoConsentAt,
                lastPhotoEstimateAt = if (s.analysis?.photoEstimated == true) now else old.lastPhotoEstimateAt,
                // safety_medical_red_flags_stop: training stays locked until clearance is confirmed.
                trainingHoldAt = if (d.redFlagSymptoms) now else old.trainingHoldAt
            )
        }

        // Last, because the app shell swaps from onboarding to the main UI on this flag.
        store.setOnboardingVersion(2)
        store.setOnboarded(true)
    }

    override fun onCleared() {
        // Held photos go with this view model. v2.0 could leave staged photos in the cache;
        // clear any leftovers so nothing lingers.
        val context = ForgeApp.instance
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ProgressPhotoFiles.clearStaging(context) }
        }
        super.onCleared()
    }

    companion object {
        /** Photo long edge for the estimate: full body reads fine and keeps image tokens modest. */
        const val ANALYSIS_EDGE = 1024

        val Factory = forgeFactory { OnboardingViewModel(it) }
    }
}

/** runCatching that lets coroutine cancellation propagate instead of swallowing it. */
internal suspend inline fun <T> attempt(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

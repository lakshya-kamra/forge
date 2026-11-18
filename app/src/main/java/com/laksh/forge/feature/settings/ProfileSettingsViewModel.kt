package com.laksh.forge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.PhasePlan
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.GoalMode
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.domain.MacroTargets
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.guardrails.Phases
import com.laksh.forge.domain.plan.Equipment
import com.laksh.forge.domain.plan.PlanBuilder
import com.laksh.forge.domain.plan.PlanProfile
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.plan.PlanStore
import com.laksh.forge.domain.plan.details
import com.laksh.forge.domain.plan.CutHistory
import com.laksh.forge.util.Dates
import java.time.LocalDate
import com.laksh.forge.domain.plan.edScreenLockActive
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class ProfileUiState(
    val loaded: Boolean = false,
    val settings: ForgeSettings = ForgeSettings(),
    val profile: PlanProfile = PlanProfile(),
    val latestWeightKg: Double? = null,
    val activePlan: PhasePlan? = null,
    /** Phases the guardrails allow right now; CUT missing means no deficit may be set. */
    val allowed: Set<String> = Phases.ALL.toSet(),
    /** Targets the non-plan screens would use, already through the guardrails. */
    val preview: MacroTargets? = null,
    val previewNotes: List<String> = emptyList(),
    val error: String? = null
) {
    val deficitAllowed: Boolean get() = Phases.CUT in allowed
    val weightKg: Double get() = latestWeightKg ?: activePlan?.details()?.weightKgAtStart ?: 70.0
}

class ProfileSettingsViewModel(private val repo: ForgeRepository) : ViewModel() {

    /** One-shot messages (guardrail refusals, lock explanations) for the snackbar. */
    val message = MutableStateFlow<String?>(null)

    val state: StateFlow<ProfileUiState> = combine(
        repo.settingsStore.settings,
        PlanProfileStore.profile,
        repo.weightDao.observeLatest(),
        PlanStore.planDao.observeActivePlan(),
        PlanStore.planDao.observePlans()
    ) { s, profile, latest, plan, plans ->
        val weightKg = latest?.kg ?: plan?.details()?.weightKgAtStart ?: 70.0
        val gp = PlanStore.guardrailProfile(s, weightKg, plan?.details()?.bodyFat?.low)
        val newCutBlock = CutHistory.of(plans, LocalDate.parse(Dates.today())).newCutFlag
        val allowed = PlanStore.allowedPhases(gp, profile, s, newCutBlock)
        val targets = NutritionMath.targets(s, weightKg, s.learnedTdee.takeIf { it > 0 }, profile.kidneyDisease)
        val (tdee, _) = PlanStore.maintenance(s, weightKg)
        val checked = PlanStore.check(
            gp, targets.kcal, targets.proteinG, targets.carbsG, targets.fatG,
            if (s.rateKgPerWeek != 0.0) s.rateKgPerWeek else NutritionMath.defaultRate(s.goal, weightKg),
            tdee, weightKg, profile.kidneyDisease
        )
        ProfileUiState(
            loaded = true,
            settings = s,
            profile = profile,
            latestWeightKg = latest?.kg,
            activePlan = plan,
            allowed = allowed,
            preview = targets.copy(kcal = checked.kcal, proteinG = checked.proteinG, carbsG = checked.carbsG, fatG = checked.fatG),
            previewNotes = checked.notes
        )
    }
        .catch { e -> emit(ProfileUiState(loaded = true, error = e.message ?: "Couldn't load your profile.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    private val store get() = repo.settingsStore

    /**
     * Safety-relevant edits end a now-disallowed deficit straight away - the active plan if
     * there is one, otherwise the legacy goal that Today falls back to without a plan.
     */
    private suspend fun enforce() {
        if (PlanStore.enforceSafety(repo)) {
            message.value = "Your plan is now maintenance, because Forge won't plan a deficit with these details."
            return
        }
        if (PlanStore.planDao.activePlan() != null) return
        val s = repo.settings()
        if (s.goal != GoalMode.CUT && s.rateKgPerWeek >= 0.0) return
        val profile = PlanProfileStore.current()
        val weightKg = PlanStore.latestWeightKg(repo) ?: 70.0
        val gp = PlanStore.guardrailProfile(s, weightKg, null)
        if (Phases.CUT !in PlanStore.allowedPhases(gp, profile, s)) {
            store.setGoal(GoalMode.MAINTAIN)
            store.setRate(0.0)
            message.value = "Your goal is now maintenance, because Forge won't set a deficit with these details."
        }
    }

    // ------------------------------------------------------------------ about you

    fun setSex(v: Sex) = viewModelScope.launch {
        store.setSex(v)
        if (v == Sex.MALE) store.setPregnancyStatus(PregnancyStatus.NONE)
        enforce()
    }

    fun setAge(v: Int) = viewModelScope.launch {
        if (v !in 10..100) return@launch
        store.setAge(v)
        enforce()
    }

    fun setHeight(v: Double) = viewModelScope.launch {
        if (v !in 120.0..230.0) return@launch
        store.setHeight(v)
        enforce()
    }

    fun setUnit(v: WeightUnit) = viewModelScope.launch { store.setWeightUnit(v) }
    fun setSouthAsian(v: Boolean) = viewModelScope.launch { PlanProfileStore.update { it.copy(southAsian = v) } }

    // ------------------------------------------------------------------ training

    fun setTrainingAge(v: TrainingAge) = viewModelScope.launch { store.setTrainingAge(v) }
    fun setDetrained(v: Boolean) = viewModelScope.launch { PlanProfileStore.update { it.copy(detrained = v) } }
    fun setDays(v: Int) = viewModelScope.launch { PlanProfileStore.update { it.copy(daysPerWeek = v) } }
    fun setEquipment(v: Equipment) = viewModelScope.launch { PlanProfileStore.update { it.copy(equipment = v) } }

    // ------------------------------------------------------------------ health & safety

    fun setPregnancy(v: PregnancyStatus) = viewModelScope.launch {
        store.setPregnancyStatus(v)
        enforce()
    }

    fun setKidney(v: Boolean) = viewModelScope.launch {
        PlanProfileStore.update { it.copy(kidneyDisease = v) }
        if (v) {
            // The kidney override supersedes every other protein rule.
            store.setProteinPerKg(PlanBuilder.KIDNEY_PROTEIN_G_PER_KG)
            PlanStore.rebalanceActivePlan(repo, PlanBuilder.KIDNEY_PROTEIN_G_PER_KG, repo.settings().fatPerKg)
            message.value = "Protein is now 0.8 g per kg. Please confirm any target with your nephrologist or renal dietitian."
        }
        enforce()
    }

    fun setDiabetes(v: Boolean) = viewModelScope.launch {
        PlanProfileStore.update { it.copy(diabetesMedication = v) }
        enforce()
    }

    fun setOtherCondition(v: Boolean) = viewModelScope.launch {
        PlanProfileStore.update { it.copy(otherWeightCondition = v) }
        enforce()
    }

    fun setHideNumbers(v: Boolean) = viewModelScope.launch { store.setHideNumbers(v) }

    /**
     * A re-taken SCOFF. Positive applies the locks; negative never counts as clearance and
     * cannot lift a lock that is under 30 days old or tied to an open crisis flag
     * (guardrails.final.json safety_scoff_screen and safety_scoff_positive_response).
     */
    fun submitScoff(answers: List<Boolean>) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val s = repo.settings()
        val profile = PlanProfileStore.current()
        val positive = Guardrails.scoffPositive(answers)
        if (positive) {
            store.setEdScreen(true, now)
            enforce()
            message.value = "Thanks for answering honestly. Forge has switched to maintenance and paused weight-loss features. Support lines are just below."
            return@launch
        }
        val lockActive = edScreenLockActive(s, now)
        if (lockActive || profile.crisisLockActive(now)) {
            message.value = "Thanks. The pause on weight-loss features stays for at least 30 days from when it started."
        } else {
            store.setEdScreen(false, now)
            message.value = "Thanks - your answers are saved on this phone."
        }
    }

    // ------------------------------------------------------------------ goal & targets

    fun setGoal(v: GoalMode) = viewModelScope.launch {
        val st = state.value
        if (v == GoalMode.CUT && !st.deficitAllowed) {
            message.value = PlanStore.newCutBlock()?.message ?: "Forge won't set a deficit with your current details."
            return@launch
        }
        store.setGoal(v)
        store.setRate(NutritionMath.defaultRate(v, st.weightKg))
    }

    fun setRate(kgPerWeek: Double) = viewModelScope.launch {
        val st = state.value
        val w = st.weightKg
        // Never faster than 1% of bodyweight a week down or 0.5% up (planContents adjustmentRules).
        val min = if (st.deficitAllowed) -0.01 * w else 0.0
        val clamped = kgPerWeek.coerceIn(min, 0.005 * w)
        store.setRate((clamped * 100).roundToInt() / 100.0)
    }

    fun setActivity(v: Double) = viewModelScope.launch { store.setActivityFactor((v * 100).roundToInt() / 100.0) }
    fun setUseAuto(v: Boolean) = viewModelScope.launch { store.setUseAutoTargets(v) }

    /** Manual calories go through the floor; a lower figure is refused and explained. */
    fun setManualKcal(v: Int) = viewModelScope.launch {
        val s = repo.settings()
        val floor = PlanBuilder.calorieFloor(s.sex)
        if (v < floor) {
            store.setManualKcal(floor)
            message.value = "Forge keeps your daily target at or above $floor kcal. If a doctor or dietitian has prescribed a lower intake, follow their plan directly."
        } else {
            store.setManualKcal(v)
        }
    }

    fun setProteinPerKg(v: Double) = viewModelScope.launch {
        val profile = PlanProfileStore.current()
        val max = if (profile.kidneyDisease) PlanBuilder.KIDNEY_PROTEIN_MAX_G_PER_KG else PlanBuilder.PROTEIN_MAX_G_PER_KG
        val value = ((v.coerceAtMost(max)) * 10).roundToInt() / 10.0
        store.setProteinPerKg(value)
        PlanStore.rebalanceActivePlan(repo, value, repo.settings().fatPerKg)
    }

    fun setFatPerKg(v: Double) = viewModelScope.launch {
        val value = (v.coerceIn(0.5, 1.5) * 10).roundToInt() / 10.0
        store.setFatPerKg(value)
        PlanStore.rebalanceActivePlan(repo, repo.settings().proteinPerKg, value)
    }

    fun setSteps(v: Int) = viewModelScope.launch {
        if (v !in 1000..40000) return@launch
        store.setStepsTarget(v)
        PlanStore.planDao.activePlan()?.let { PlanStore.planDao.updatePlan(it.copy(stepsTarget = v)) }
    }

    fun consumeMessage() { message.value = null }

    companion object {
        val Factory = forgeFactory { ProfileSettingsViewModel(it) }
    }
}

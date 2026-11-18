package com.laksh.forge.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.PhasePlan
import com.laksh.forge.data.db.WeeklyCheckin
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.guardrails.Phases
import com.laksh.forge.domain.plan.AdjustKind
import com.laksh.forge.domain.plan.CheckedTargets
import com.laksh.forge.domain.plan.CheckinData
import com.laksh.forge.domain.plan.CheckinEngine
import com.laksh.forge.domain.plan.CheckinProposal
import com.laksh.forge.domain.plan.PhaseCopy
import com.laksh.forge.domain.plan.PlanBuilder
import com.laksh.forge.domain.plan.PlanDetails
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.plan.PlanStore
import com.laksh.forge.domain.plan.details
import com.laksh.forge.domain.plan.edScreenLockActive
import com.laksh.forge.feature.onboarding.attempt
import com.laksh.forge.ui.forgeFactory
import com.laksh.forge.util.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class CheckinUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val plan: PhasePlan? = null,
    val details: PlanDetails? = null,
    val settings: ForgeSettings = ForgeSettings(),
    val data: CheckinData? = null,
    val proposal: CheckinProposal? = null,
    /** Targets after the proposed change, already through the guardrails. */
    val preview: CheckedTargets? = null,
    /** This week's check-in when it is already done. */
    val alreadyDone: WeeklyCheckin? = null,
    val energy: Int? = null,
    val hunger: Int? = null,
    val performance: Int? = null,
    val notes: String = "",
    /** guardrails safety_scoff_screen: re-offer every 12 weeks during a cut. */
    val scoffNeeded: Boolean = false,
    val scoff: List<Boolean?> = List(5) { null },
    val saving: Boolean = false,
    val saved: WeeklyCheckin? = null,
    /** True after a crisis disclosure or positive screen: show support, no numbers. */
    val support: Boolean = false,
    val outcome: String? = null,
    /** safety_low_intake_rapid_loss_checkin fired: the supportive check-in card is shown. */
    val safetyCheck: String? = null,
    val safetyFired: Boolean = false,
    /** Pregnancy, diet-break and similar prompts from the guardrails, shown with the proposal. */
    val safetyNotes: List<String> = emptyList()
) {
    val scoffComplete: Boolean get() = !scoffNeeded || scoff.all { it != null }
}

class CheckinViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val _state = MutableStateFlow(CheckinUiState())
    val state: StateFlow<CheckinUiState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        attempt { compute() }
            .onSuccess { s -> _state.update { s } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Couldn't load your week.") } }
    }

    private suspend fun compute(): CheckinUiState {
        val settings = repo.settings()
        val plan = PlanStore.planDao.activePlan() ?: return CheckinUiState(loading = false, settings = settings)
        val details = plan.details()
        val today = LocalDate.now()
        val todayS = today.toString()

        val weights = repo.weightDao.since(System.currentTimeMillis() - 21L * 86_400_000L)
        val totals = repo.foodDao.observeTotalsRange(Dates.daysAgo(13), todayS).first()
        val health = repo.healthDao.range(Dates.daysAgo(6), todayS)
        val data = CheckinEngine.summarize(today, weights, totals, health)

        val checkins = checkinsFor(plan, PlanStore.planDao.observeCheckins().first())
        val alreadyDone = if (settings.lastCheckinWeek == data.weekStart) {
            checkins.firstOrNull { it.weekStart == data.weekStart }
        } else null

        val lastChangeAt = checkins.firstOrNull { it.adjustmentKcal != 0 }?.createdAt ?: plan.createdAt
        val daysSinceChange = (System.currentTimeMillis() - lastChangeAt) / 86_400_000L
        val weeksIn = (daysBetween(plan.startDate, todayS) / 7).toInt()
        val weightKg = data.avgWeightKg ?: PlanStore.latestWeightKg(repo) ?: details?.weightKgAtStart ?: 70.0
        val band = details?.band ?: PlanBuilder.band(plan.phase, settings.sex, settings.trainingAge, null)

        val proposal = CheckinEngine.propose(
            phase = plan.phase,
            currentKcal = plan.kcal,
            intendedRateKgPerWeek = plan.rateKgPerWeek,
            band = band,
            weightKg = weightKg,
            learnedTdee = settings.learnedTdee.takeIf { it > 0 },
            daysSinceLastChange = daysSinceChange,
            weeksInPhase = weeksIn,
            stepsTarget = plan.stepsTarget ?: settings.stepsTarget,
            age = settings.age,
            data = data
        )

        val preview = if (proposal.kcalDelta != 0) {
            val profile = PlanProfileStore.current()
            val m = PlanBuilder.macros(
                (plan.kcal + proposal.kcalDelta).toDouble(),
                weightKg,
                plan.proteinG / weightKg,
                plan.fatG / weightKg,
                PhaseCopy.isDeficit(plan.phase)
            )
            val (tdee, _) = PlanStore.maintenance(settings, weightKg)
            val gp = PlanStore.guardrailProfile(settings, weightKg, details?.bodyFat?.low)
            // Protein is carried over from the plan rather than re-derived, so a calorie change
            // never quietly cuts protein.
            PlanStore.check(gp, m.kcal, plan.proteinG, m.carbsG, m.fatG, plan.rateKgPerWeek, tdee, weightKg, profile.kidneyDisease)
        } else null

        val twelveWeeksMs = 12L * 7 * 86_400_000L
        val (check, safetyNotes) = CheckinEngine.safetyPrompts(
            profile = PlanStore.guardrailProfile(settings, weightKg, details?.bodyFat?.low),
            phase = plan.phase,
            today = today,
            weights = weights,
            totals = totals,
            weightKgAtStart = details?.weightKgAtStart,
            avgWeightKg = data.avgWeightKg,
            weeksInPhase = weeksIn,
            pregnant = settings.pregnancyStatus == PregnancyStatus.PREGNANT,
            weightChangeKg = data.weightChangeKg
        )
        return CheckinUiState(
            safetyCheck = check?.message,
            safetyFired = check != null,
            safetyNotes = safetyNotes,
            loading = false,
            plan = plan,
            details = details,
            settings = settings,
            data = data,
            proposal = proposal,
            preview = preview,
            alreadyDone = alreadyDone,
            scoffNeeded = plan.phase == Phases.CUT && System.currentTimeMillis() - settings.edScreenAt > twelveWeeksMs
        )
    }

    fun setEnergy(v: Int) = _state.update { it.copy(energy = v) }
    fun setHunger(v: Int) = _state.update { it.copy(hunger = v) }
    fun setPerformance(v: Int) = _state.update { it.copy(performance = v) }
    fun setNotes(v: String) = _state.update { it.copy(notes = v) }

    /** "I'm fine, or I've been ill or travelling": the card goes; the event is still recorded. */
    fun dismissSafetyCheck() = _state.update { it.copy(safetyCheck = null) }

    /** "Take the eating check": the SCOFF questions join this check-in. */
    fun startScoff() = _state.update { it.copy(scoffNeeded = true) }
    fun setScoff(i: Int, yes: Boolean) = _state.update { s ->
        s.copy(scoff = s.scoff.toMutableList().also { it[i] = yes })
    }

    fun save() {
        val s = _state.value
        val plan = s.plan ?: return
        val data = s.data ?: return
        val proposal = s.proposal ?: return
        if (s.saving || !s.scoffComplete) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            attempt { persist(s, plan, data, proposal) }
                .onSuccess { (checkin, support, outcome) ->
                    _state.update { it.copy(saving = false, saved = checkin, support = support, outcome = outcome) }
                }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.message ?: "Saving the check-in failed.") } }
        }
    }

    private suspend fun persist(
        s: CheckinUiState,
        plan: PhasePlan,
        data: CheckinData,
        proposal: CheckinProposal
    ): Triple<WeeklyCheckin, Boolean, String> {
        val now = System.currentTimeMillis()
        val store = repo.settingsStore
        val crisis = s.notes.isNotBlank() && Guardrails.detectCrisis(s.notes)
        val scoffPositive = s.scoffNeeded && Guardrails.scoffPositive(s.scoff.map { it == true })

        var applied = 0
        val outcome: String
        val support = crisis || scoffPositive
        // The supportive check-in opens a safety event (read by photo analysis); a negative
        // screen closes it.
        when {
            s.safetyFired -> PlanProfileStore.update { it.copy(safetyEventAt = now) }
            s.scoffNeeded && !scoffPositive && !crisis -> PlanProfileStore.update { it.copy(safetyEventAt = 0L) }
        }
        if (support) {
            // safety_crisis_disclosure / safety_scoff_positive_response: no coaching numbers this
            // turn, stop any deficit and pause weight-loss features for at least 30 days.
            if (crisis) PlanProfileStore.update { it.copy(crisisFlaggedAt = now) }
            store.setEdScreen(true, now)
            if (PhaseCopy.isDeficit(plan.phase)) {
                PlanStore.switchToMaintenance(
                    repo,
                    "Forge has switched to maintenance and paused weight-loss features for now. Training stays the focus."
                )
            }
            outcome = "Your check-in is saved. Forge has paused weight-loss features for now."
        } else {
            // A negative screen never lifts a lock inside its first 30 days.
            if (s.scoffNeeded && !edScreenLockActive(s.settings, now)) store.setEdScreen(false, now)
            when {
                proposal.kind == AdjustKind.END_CUT -> {
                    PlanStore.switchToMaintenance(repo, proposal.message)
                    outcome = "Your cut is complete and your plan is now maintenance."
                }
                proposal.kcalDelta != 0 && s.preview != null -> {
                    val p = requireNotNull(s.preview)
                    applied = p.kcal - plan.kcal
                    PlanStore.planDao.updatePlan(
                        plan.copy(
                            kcal = p.kcal,
                            proteinG = p.proteinG,
                            carbsG = p.carbsG,
                            fatG = p.fatG,
                            fiberG = p.fiberG,
                            guardrailNotes = mergeNotes(plan.guardrailNotes, p.notes)
                        )
                    )
                    outcome = if (applied > 0) "Daily calories go up by $applied." else "Daily calories go down by ${-applied}."
                }
                proposal.stepsDelta != 0 -> {
                    val newSteps = (plan.stepsTarget ?: s.settings.stepsTarget) + proposal.stepsDelta
                    PlanStore.planDao.updatePlan(plan.copy(stepsTarget = newSteps))
                    store.setStepsTarget(newSteps)
                    outcome = "Your step target is now ${"%,d".format(newSteps)} a day."
                }
                else -> outcome = "No changes this week."
            }
        }

        val checkin = WeeklyCheckin(
            weekStart = data.weekStart,
            createdAt = now,
            avgWeightKg = data.avgWeightKg,
            weightChangeKg = data.weightChangeKg,
            avgKcal = data.avgKcal7,
            adherence = data.adherence7,
            avgSleepMinutes = data.avgSleepMinutes,
            avgSteps = data.avgSteps,
            energy = s.energy,
            hunger = s.hunger,
            performance = s.performance,
            // Words that matched the crisis screen are never stored or shown back.
            notes = if (crisis) null else s.notes.trim().ifBlank { null },
            adjustmentKcal = applied,
            summary = if (support) "Check-in saved; targets paused at maintenance." else proposal.message
        )
        val id = PlanStore.planDao.insertCheckin(checkin)
        store.setLastCheckinWeek(data.weekStart)
        return Triple(checkin.copy(id = id), support, outcome)
    }

    private fun mergeNotes(existing: String?, added: List<String>): String? =
        (existing.orEmpty().lines() + added).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString("\n").ifBlank { null }

    companion object {
        val Factory = forgeFactory { CheckinViewModel(it) }
    }
}

package com.laksh.forge.domain.plan

import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.BodyDao
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.db.PhasePlan
import com.laksh.forge.data.db.PlanDao
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.guardrails.GuardrailFlag
import com.laksh.forge.domain.guardrails.GuardrailProfile
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.guardrails.Phases
import com.laksh.forge.util.Dates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Final, guardrail-checked numbers ready to store on a PhasePlan. */
data class CheckedTargets(
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int,
    val rateKgPerWeek: Double,
    /** Every adjustment the guardrails (or the kidney override) made, in plain language. */
    val notes: List<String>
)

/**
 * The plan feature's data plumbing: DAO access, maintenance estimates, guardrail checks and
 * the few writes several screens share (activating a plan, falling back to maintenance).
 */
object PlanStore {

    private val db: ForgeDatabase get() = ForgeDatabase.get(ForgeApp.instance)
    val planDao: PlanDao get() = db.planDao()
    val bodyDao: BodyDao get() = db.bodyDao()

    /** Maintenance estimate: learned TDEE once it exists, otherwise Mifflin-St Jeor x activity. */
    fun maintenance(s: ForgeSettings, weightKg: Double): Pair<Double, String> {
        val learned = s.learnedTdee.takeIf { it > 0 }
        return if (learned != null) {
            learned to "Learned from your logged intake and weight trend"
        } else {
            NutritionMath.bmr(s.sex, weightKg, s.heightCm, s.age) * s.activityFactor to
                "Estimated with Mifflin-St Jeor x activity %.2f - replaced by your real data after 2-3 weeks of logging".format(s.activityFactor)
        }
    }

    /**
     * Guardrail profile for [s]. When a body-fat range exists its LOWER bound is passed, so the
     * low-body-fat blocks and the rate tiers can only ever err on the cautious side.
     */
    fun guardrailProfile(s: ForgeSettings, weightKg: Double, bodyFatLow: Double?): GuardrailProfile =
        Guardrails.profileFrom(s, weightKg, if (s.isMinor) null else bodyFatLow)

    /**
     * Guardrails first, then the extra holds this feature knows about (medical, crisis, and the
     * maintenance weeks owed after a 16-week cut, passed as [newCutBlock]).
     */
    fun allowedPhases(
        profile: GuardrailProfile,
        plan: PlanProfile,
        s: ForgeSettings,
        newCutBlock: GuardrailFlag? = null
    ): Set<String> {
        var allowed = Guardrails.allowedPhases(profile)
        val noDeficit = plan.medicalHold || plan.crisisLockActive() || s.isMinor ||
            s.pregnancyStatus != PregnancyStatus.NONE || s.edScreenPositive || newCutBlock != null
        if (noDeficit) allowed = allowed - Phases.CUT
        return allowed
    }

    /** Today, Trends, the widget and the coach can start observing at once; switch only once. */
    private val cutLimitLock = Mutex()

    suspend fun cutHistory(): CutHistory = CutHistory.of(planDao.allPlans(), LocalDate.parse(Dates.today()))

    /** Guardrails.newCutFlag for the plan history: non-null (BLOCK) while a new cut must wait. */
    suspend fun newCutBlock(): GuardrailFlag? = cutHistory().newCutFlag

    /**
     * nutrition_max_cut_length: end the deficit automatically at 16 weeks, whether or not a
     * check-in was saved. Runs whenever the active plan is observed.
     */
    suspend fun enforceCutLimit(repo: ForgeRepository): Boolean = cutLimitLock.withLock {
        val plan = planDao.activePlan() ?: return@withLock false
        if (plan.phase != Phases.CUT || !cutHistory().cutLimitReached) return@withLock false
        switchToMaintenance(
            repo,
            "Your cut has reached 16 weeks, so Forge has moved you to maintenance. Spend at least 4 weeks here " +
                "before another deficit; it lets hormones, training and hunger settle."
        )
        true
    }

    fun safetyHolds(s: ForgeSettings, plan: PlanProfile): Set<SafetyHold> = buildSet {
        if (s.isMinor) add(SafetyHold.MINOR)
        when (s.pregnancyStatus) {
            PregnancyStatus.PREGNANT -> add(SafetyHold.PREGNANT)
            PregnancyStatus.BREASTFEEDING -> add(SafetyHold.BREASTFEEDING)
            PregnancyStatus.NONE -> Unit
        }
        if (s.edScreenPositive || plan.crisisLockActive()) add(SafetyHold.EATING_CONCERN)
        if (plan.medicalHold) add(SafetyHold.MEDICAL)
    }

    /**
     * Run a prescription through Guardrails.checkNutrition, then re-apply the kidney override,
     * which supersedes every other protein rule (guardrails.final.json nutrition_protein_upper_soft_cap).
     */
    fun check(
        profile: GuardrailProfile,
        kcal: Int,
        proteinG: Int,
        carbsG: Int,
        fatG: Int,
        rateKgPerWeek: Double,
        tdee: Double,
        weightKg: Double,
        kidneyDisease: Boolean
    ): CheckedTargets {
        // profileFrom cannot know about kidney disease (it lives in this feature's store), so the
        // flag is added here and checkNutrition applies the KDIGO protein limit itself.
        val gp = if (kidneyDisease) profile.copy(kidneyDisease = true) else profile
        val c = Guardrails.checkNutrition(gp, kcal, proteinG, carbsG, fatG, rateKgPerWeek, tdee)
        val notes = c.flags.map { it.message }.filter { it.isNotBlank() }.toMutableList()
        var protein = c.proteinG
        var carbs = c.carbsG
        val proteinCap = ((if (kidneyDisease) PlanBuilder.KIDNEY_PROTEIN_G_PER_KG else PlanBuilder.PROTEIN_MAX_G_PER_KG) * weightKg).roundToInt()
        if (protein > proteinCap) {
            protein = proteinCap
            carbs = ((c.kcal - protein * 4 - c.fatG * 9) / 4.0).coerceAtLeast(0.0).roundToInt()
            if (kidneyDisease) notes += "Protein is kept at 0.8 g per kg because you reported kidney disease."
        }
        val fiber = max(25, (c.kcal / 1000.0 * 14).roundToInt())
        return CheckedTargets(c.kcal, protein, carbs, c.fatG, fiber, c.rateKgPerWeek, notes.distinct())
    }

    /** Average steps over the last two weeks of wearable data, or null without any. */
    suspend fun baselineSteps(repo: ForgeRepository): Double? =
        repo.healthDao.range(Dates.daysAgo(14), Dates.today())
            .mapNotNull { it.steps?.toDouble() }
            .takeIf { it.isNotEmpty() }
            ?.average()

    suspend fun latestWeightKg(repo: ForgeRepository): Double? = repo.weightDao.observeLatest().first()?.kg

    /** Assemble the stored entity. */
    fun entity(
        phase: String,
        checked: CheckedTargets,
        built: BuiltTargets,
        details: PlanDetails,
        rationale: String,
        source: String,
        bodyFatEstimate: Double?
    ): PhasePlan {
        val today = Dates.today()
        val reviewWeeks = min(built.plannedWeeks, 12)
        return PhasePlan(
            createdAt = System.currentTimeMillis(),
            phase = phase,
            startDate = today,
            reviewDate = Dates.plusDays(today, reviewWeeks * 7),
            endDate = Dates.plusDays(today, built.plannedWeeks * 7),
            kcal = checked.kcal,
            proteinG = checked.proteinG,
            carbsG = checked.carbsG,
            fatG = checked.fatG,
            fiberG = checked.fiberG,
            rateKgPerWeek = checked.rateKgPerWeek,
            stepsTarget = built.stepsTarget,
            rationale = rationale,
            planJson = PlanJson.encode(details),
            bodyFatEstimate = bodyFatEstimate,
            source = source,
            isActive = true,
            guardrailNotes = (built.notes + checked.notes).distinct().joinToString("\n").ifBlank { null }
        )
    }

    /**
     * Replace the active plan with a rules-based maintenance plan. Used when a cut reaches its
     * 16-week limit, after a crisis disclosure or positive screen, and when a safety change
     * (pregnancy, age, a medical condition) makes the current phase disallowed.
     */
    suspend fun switchToMaintenance(repo: ForgeRepository, reason: String): PhasePlan {
        val s = repo.settings()
        val profile = PlanProfileStore.current()
        val previous = planDao.activePlan()
        val prevDetails = previous?.details()
        val weightKg = latestWeightKg(repo) ?: prevDetails?.weightKgAtStart ?: 70.0
        val (tdee, basis) = maintenance(s, weightKg)
        val gp = guardrailProfile(s, weightKg, prevDetails?.bodyFat?.low)
        val built = PlanBuilder.build(
            BuildInput(
                phase = Phases.MAINTAIN,
                sex = s.sex,
                age = s.age,
                weightKg = weightKg,
                trainingAge = s.trainingAge,
                tdee = tdee,
                bodyFat = null,
                kidneyDisease = profile.kidneyDisease,
                breastfeeding = s.pregnancyStatus == PregnancyStatus.BREASTFEEDING,
                baselineSteps = baselineSteps(repo),
                fatPerKg = s.fatPerKg,
                postDiet = previous?.phase == Phases.CUT
            )
        )
        val checked = check(gp, built.kcal, built.proteinG, built.carbsG, built.fatG, built.rateKgPerWeek, tdee, weightKg, profile.kidneyDisease)
        val (stops, next) = PlanBuilder.stopConditions(Phases.MAINTAIN, s.sex)
        val details = PlanDetails(
            plannedWeeks = built.plannedWeeks,
            ratePctPerWeek = built.ratePctPerWeek,
            bandMinPct = built.band.minPct,
            bandMaxPct = built.band.maxPct,
            tdee = tdee.roundToInt(),
            tdeeBasis = basis,
            bodyFat = prevDetails?.bodyFat,
            rule = DecisionRule.SAFETY_HOLD.name,
            ruleExplanation = reason,
            candidates = listOf(Phases.MAINTAIN),
            stopConditions = stops,
            nextPhase = next,
            training = PlanBuilder.trainingText(s.trainingAge, profile.detrained, Phases.MAINTAIN),
            trainingDaysPerWeek = prevDetails?.trainingDaysPerWeek,
            equipment = prevDetails?.equipment,
            checkIns = PlanBuilder.checkInCadence,
            notes = built.notes,
            programIds = prevDetails?.programIds.orEmpty(),
            programNames = prevDetails?.programNames.orEmpty(),
            weightKgAtStart = weightKg,
            postDiet = previous?.phase == Phases.CUT
        )
        val plan = entity(Phases.MAINTAIN, checked, built, details, reason, "RULES", previous?.bodyFatEstimate)
        val id = planDao.activate(plan)
        repo.settingsStore.setGoal(PhaseCopy.goalFor(Phases.MAINTAIN))
        repo.settingsStore.setRate(0.0)
        return plan.copy(id = id)
    }

    /**
     * After any safety-relevant change, make sure the active plan is still allowed; if it is a
     * deficit that is no longer permitted, end it and move to maintenance.
     */
    suspend fun enforceSafety(repo: ForgeRepository): Boolean {
        val plan = planDao.activePlan() ?: return false
        val s = repo.settings()
        val profile = PlanProfileStore.current()
        val weightKg = latestWeightKg(repo) ?: plan.details()?.weightKgAtStart ?: return false
        val gp = guardrailProfile(s, weightKg, plan.details()?.bodyFat?.low)
        if (plan.phase in allowedPhases(gp, profile, s, if (plan.phase == Phases.CUT) newCutBlock() else null)) return false
        switchToMaintenance(
            repo,
            "Your details changed in a way that means Forge won't plan a ${PhaseCopy.name(plan.phase).lowercase()} " +
                "right now, so your plan is set to maintenance. Training stays the focus."
        )
        return true
    }

    /**
     * Re-split the active plan's calories after the person changes their protein or fat
     * preference, keeping calories and running the result through the guardrails again.
     */
    suspend fun rebalanceActivePlan(repo: ForgeRepository, proteinPerKg: Double, fatPerKg: Double) {
        val plan = planDao.activePlan() ?: return
        val s = repo.settings()
        val profile = PlanProfileStore.current()
        val weightKg = latestWeightKg(repo) ?: plan.details()?.weightKgAtStart ?: return
        val m = PlanBuilder.macros(plan.kcal.toDouble(), weightKg, proteinPerKg, fatPerKg, PhaseCopy.isDeficit(plan.phase))
        val (tdee, _) = maintenance(s, weightKg)
        val gp = guardrailProfile(s, weightKg, plan.details()?.bodyFat?.low)
        val checked = check(gp, m.kcal, m.proteinG, m.carbsG, m.fatG, plan.rateKgPerWeek, tdee, weightKg, profile.kidneyDisease)
        planDao.updatePlan(
            plan.copy(
                kcal = checked.kcal,
                proteinG = checked.proteinG,
                carbsG = checked.carbsG,
                fatG = checked.fatG,
                fiberG = checked.fiberG,
                guardrailNotes = (listOfNotNull(plan.guardrailNotes) + checked.notes).joinToString("\n")
                    .lines().filter { it.isNotBlank() }.distinct().joinToString("\n").ifBlank { null }
            )
        )
    }
}

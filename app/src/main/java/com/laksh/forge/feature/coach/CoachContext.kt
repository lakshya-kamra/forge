package com.laksh.forge.feature.coach

import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.DatedSet
import com.laksh.forge.data.db.DayTotals
import com.laksh.forge.data.db.ForgeDatabase
import com.laksh.forge.data.db.HealthDaily
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.PregnancyStatus
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.MacroTargets
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.domain.guardrails.GuardrailProfile
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.plan.PlanProfile
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.plan.PlanTargets
import com.laksh.forge.domain.training.VolumeAnalyzer
import com.laksh.forge.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/** The coach's view of the person: the guardrail profile plus the rendered data block. */
data class CoachSnapshot(
    val profile: GuardrailProfile,
    val context: String
)

/**
 * Builds the <user_data> block the coach answers from. Dense numbers over prose: it is sent
 * with every turn, and the model reads figures better than paragraphs. Every section fails
 * soft - a missing table or an unfinished feature leaves a "not available" line rather than
 * breaking the coach.
 */
object CoachContext {

    suspend fun build(repo: ForgeRepository): CoachSnapshot = withContext(Dispatchers.IO) {
        val db = ForgeDatabase.get(ForgeApp.instance)
        val s = repo.settings()
        val today = LocalDate.now()
        val todayStr = today.toString()

        val weights = runCatching {
            repo.weightDao.since(System.currentTimeMillis() - 120L * 86_400_000)
        }.getOrDefault(emptyList())
        val latestWeight = weights.maxByOrNull { it.timestamp }
            ?: runCatching { repo.weightDao.observeLatest().first() }.getOrNull()
        val weightKg = latestWeight?.kg

        val measurement = runCatching { db.bodyDao().latestMeasurement() }.getOrNull()
        val bodyFat = if (s.isMinor) null else measurement?.bodyFatPct
        // Health answers from onboarding live in the plan feature's own store; a crisis
        // disclosure inside its 30-day window locks the same way a positive screen does.
        val planProfile = runCatching { PlanProfileStore.current() }.getOrNull()
        val profile = Guardrails.profileFrom(
            s, weightKg ?: 0.0, bodyFat, measurement?.bodyFatMethod
        ).let { base ->
            if (planProfile == null) base
            else base.copy(
                kidneyDisease = planProfile.kidneyDisease,
                edScreenPositive = base.edScreenPositive || planProfile.crisisLockActive()
            )
        }

        val text = buildString {
            appendLine("<user_data>")
            appendLine("Today: $todayStr, local time about ${LocalTime.now().hour}:00. Weight unit preference: ${s.weightUnit.label}.")
            appendLine()
            profileSection(s, profile, planProfile)
            planSection(repo, db, s, weightKg, todayStr)
            weightSection(s, weights, latestWeight, today, measurement?.let {
                MeasurementLine(it.localDate, it.waistCm, if (s.isMinor) null else it.bodyFatPct, it.bodyFatMethod)
            })
            intakeSection(repo, s, profile, todayStr)
            trainingSection(repo, db, s, today)
            recoverySection(repo, today)
            safetySection(profile)
            append("</user_data>")
        }
        CoachSnapshot(profile, text)
    }

    // ------------------------------------------------------------------ sections

    private fun StringBuilder.profileSection(s: ForgeSettings, p: GuardrailProfile, pp: PlanProfile?) {
        appendLine("PROFILE")
        appendLine("- ${s.sex.name.lowercase()}, age ${s.age}, height ${s.heightCm.roundToInt()} cm")
        val stage = if (s.trainingAge == TrainingAge.UNKNOWN) "not set"
        else "${s.trainingAge.label} (${s.trainingAge.blurb})"
        appendLine("- Training age: $stage")
        appendLine("- Goal setting: ${s.goal.label} (${s.goal.blurb})")
        when (s.pregnancyStatus) {
            PregnancyStatus.PREGNANT -> appendLine("- Currently pregnant")
            PregnancyStatus.BREASTFEEDING -> appendLine("- Currently breastfeeding")
            PregnancyStatus.NONE -> Unit
        }
        if (s.edScreenPositive) {
            appendLine("- Eating-disorder screen: positive on ${Dates.dayOf(s.edScreenAt).takeIf { s.edScreenAt > 0 } ?: "an earlier date"}. Forge is locked to maintenance; keep the focus off weight loss.")
        }
        if (pp != null) {
            if (pp.crisisLockActive()) appendLine("- Recently shared something difficult (within the last 30 days): Forge is held at maintenance; be gentle, keep numbers in the background.")
            if (pp.kidneyDisease) appendLine("- Reports kidney disease: protein at most 0.8 g/kg; their nephrologist sets the target.")
            if (pp.diabetesMedication || pp.otherWeightCondition) appendLine("- Reports a medical condition or medication that affects weight: plan held at maintenance; defer weight-change questions to their doctor.")
            if (pp.southAsian) appendLine("- South Asian background: health risk can start at a lower BMI; waist, fitness and blood tests give a fuller picture (neutral context only).")
            if (pp.detrained) appendLine("- Returning after a layoff (detrained).")
            val prefs = listOfNotNull(
                pp.daysPerWeek.takeIf { it > 0 }?.let { "$it training days/week" },
                "equipment: ${pp.equipment.name.lowercase().replace('_', ' ')}",
                "training goal: ${pp.trainingGoal.name.lowercase().replace('_', ' ')}"
            )
            appendLine("- ${prefs.joinToString(", ")}")
        }
        if (s.hideNumbers) appendLine("- Numbers are hidden for this person: talk about behaviours and food choices, not calorie figures, unless they ask for a number.")
        if (p.isMinor) appendLine("- Under 18: no weight-loss, body-fat or BMI talk.")
        appendLine()
    }

    private suspend fun StringBuilder.planSection(
        repo: ForgeRepository,
        db: ForgeDatabase,
        s: ForgeSettings,
        weightKg: Double?,
        todayStr: String
    ) {
        appendLine("PLAN AND TARGETS")
        val plan = runCatching { db.planDao().activePlan() }.getOrNull()
        if (plan != null) {
            val weeksIn = runCatching {
                ChronoUnit.WEEKS.between(LocalDate.parse(plan.startDate), LocalDate.parse(todayStr)) + 1
            }.getOrNull()
            appendLine(
                "- Active plan: ${plan.phase} since ${plan.startDate}" +
                    (weeksIn?.let { " (week $it)" } ?: "") +
                    (plan.reviewDate?.let { ", review $it" } ?: "") +
                    (plan.endDate?.let { ", ends $it" } ?: "") +
                    ", planned rate ${fmtSigned(plan.rateKgPerWeek)} kg/week" +
                    (plan.stepsTarget?.let { ", steps target $it" } ?: "")
            )
            appendLine("- Plan numbers: ${plan.kcal} kcal, ${plan.proteinG} g protein, ${plan.carbsG} g carbs, ${plan.fatG} g fat, ${plan.fiberG} g fibre (source ${plan.source})")
            plan.rationale.takeIf { it.isNotBlank() }?.let { appendLine("- Plan rationale: ${it.take(400)}") }
            plan.guardrailNotes?.takeIf { it.isNotBlank() }?.let { appendLine("- Guardrail adjustments on the plan: ${it.take(300)}") }
        } else {
            appendLine("- Active plan: none")
        }

        // Prefer the active plan's adjusted targets; fall back to the rule-based ones.
        // Bounded wait: a flow that has not emitted yet must never stall the coach.
        val planTargets: MacroTargets? = runCatching {
            withTimeoutOrNull(1_500) { PlanTargets.observe(repo).first() }
        }.getOrNull()
        val targets = planTargets ?: weightKg?.let {
            NutritionMath.targets(s, it, s.learnedTdee.takeIf { t -> t > 0 })
        }
        if (targets != null) {
            appendLine(
                "- Daily targets: ${targets.kcal} kcal (shown as +/-${Guardrails.TARGET_DISPLAY_RANGE_KCAL}), " +
                    "${targets.proteinG} g protein, ${targets.carbsG} g carbs, ${targets.fatG} g fat, ${targets.fiberG} g fibre"
            )
            appendLine("- Target source: ${if (planTargets != null) "active plan (after check-ins)" else targets.basis}")
        } else {
            appendLine("- Daily targets: unavailable until a bodyweight is logged")
        }
        if (s.learnedTdee > 0) {
            appendLine("- Learned maintenance (intake vs weight trend): ${s.learnedTdee.roundToInt()} kcal")
        } else {
            appendLine("- Learned maintenance: not enough data yet (needs about 2 weeks of consistent logging plus weigh-ins)")
        }
        val checkin = runCatching { db.planDao().latestCheckin() }.getOrNull()
        if (checkin != null) {
            val ratings = listOfNotNull(
                checkin.energy?.let { "energy $it/5" },
                checkin.hunger?.let { "hunger $it/5" },
                checkin.performance?.let { "performance $it/5" }
            ).joinToString(", ")
            appendLine(
                "- Last weekly check-in (week of ${checkin.weekStart}): " +
                    listOfNotNull(
                        ratings.ifBlank { null },
                        checkin.adherence?.let { "logged ${(it * 100).roundToInt()}% of days" },
                        checkin.adjustmentKcal.takeIf { it != 0 }?.let { "calories changed ${fmtSignedInt(it)} kcal" },
                        checkin.summary.takeIf { it.isNotBlank() }?.take(200)
                    ).joinToString("; ")
            )
        }
        appendLine()
    }

    private data class MeasurementLine(
        val date: String,
        val waistCm: Double?,
        val bodyFatPct: Double?,
        val method: String?
    )

    private fun StringBuilder.weightSection(
        s: ForgeSettings,
        weights: List<WeightEntry>,
        latest: WeightEntry?,
        today: LocalDate,
        measurement: MeasurementLine?
    ) {
        appendLine("BODYWEIGHT")
        if (latest == null) {
            appendLine("- No weigh-ins logged")
        } else {
            appendLine("- Latest weigh-in: ${fmtKg(latest.kg)} kg on ${latest.localDate}")
            val sevenFrom = today.minusDays(6).toString()
            val last7 = weights.filter { it.localDate >= sevenFrom }
            if (last7.isNotEmpty()) {
                appendLine("- 7-day average: ${fmtKg(last7.map { it.kg }.average())} kg (${last7.size} weigh-ins)")
            }
            val trend = NutritionMath.trend(weights)
            trend.latestSmoothed?.let { appendLine("- Smoothed trend weight: ${fmtKg(it)} kg") }
            if (trend.points.size >= 4) {
                val pct = trend.kgPerWeek / (trend.latestSmoothed ?: latest.kg) * 100.0
                appendLine("- Trend: ${fmtSigned(trend.kgPerWeek)} kg/week (${fmtSigned(pct)}% of bodyweight/week) over ${trend.points.size} weigh-ins in the last 120 days")
            }
            // Weekly averages make "why is my weight up?" answerable without raw-day noise.
            val weekly = (3 downTo 0).mapNotNull { i ->
                val from = today.minusDays(7L * i + 6).toString()
                val to = today.minusDays(7L * i).toString()
                val wk = weights.filter { it.localDate in from..to }
                if (wk.isEmpty()) null else "$from..$to ${fmtKg(wk.map { it.kg }.average())}"
            }
            if (weekly.isNotEmpty()) appendLine("- Weekly averages (kg): ${weekly.joinToString("; ")}")
            val recent = weights.filter { it.localDate >= today.minusDays(13).toString() }
            if (recent.isNotEmpty()) {
                appendLine("- Raw weigh-ins, last 14 days: " + recent.joinToString(", ") { "${it.localDate.substring(5)} ${fmtKg(it.kg)}" })
            }
        }
        if (measurement != null) {
            val parts = listOfNotNull(
                measurement.waistCm?.let { "waist ${fmtKg(it)} cm" },
                measurement.bodyFatPct?.let {
                    val method = measurement.method ?: "unknown method"
                    if (method.equals("VISUAL_AI", ignoreCase = true)) {
                        val r = Guardrails.photoBodyFatRange(it)
                        "body fat about ${r.first}-${r.last}% (photo estimate, rough)"
                    } else "body fat about ${it.roundToInt()}% ($method)"
                }
            )
            if (parts.isNotEmpty()) appendLine("- Latest measurements (${measurement.date}): ${parts.joinToString(", ")}")
        }
        appendLine()
    }

    private suspend fun StringBuilder.intakeSection(
        repo: ForgeRepository,
        s: ForgeSettings,
        p: GuardrailProfile,
        todayStr: String
    ) {
        appendLine("INTAKE")
        val totals: List<DayTotals> = runCatching {
            repo.foodDao.observeTotalsRange(Dates.daysAgo(13), todayStr).first()
        }.getOrDefault(emptyList())
        val todayTotals = totals.firstOrNull { it.localDate == todayStr }
        if (todayTotals != null && todayTotals.items > 0) {
            appendLine("- Today so far: ${todayTotals.kcal.roundToInt()} kcal, ${todayTotals.proteinG.roundToInt()} g protein, ${todayTotals.carbsG.roundToInt()} g carbs, ${todayTotals.fatG.roundToInt()} g fat, ${todayTotals.fiberG.roundToInt()} g fibre (${todayTotals.items} items)")
        } else {
            appendLine("- Today so far: nothing logged")
        }
        val past = totals.filter { it.localDate != todayStr && it.items > 0 }
        if (past.isEmpty()) {
            appendLine("- Previous 13 days: no food logged")
        } else {
            val n = past.size
            appendLine(
                "- Previous 13 days: logged on $n days; averages ${(past.sumOf { it.kcal } / n).roundToInt()} kcal, " +
                    "${(past.sumOf { it.proteinG } / n).roundToInt()} g protein, ${(past.sumOf { it.carbsG } / n).roundToInt()} g carbs, " +
                    "${(past.sumOf { it.fatG } / n).roundToInt()} g fat, ${(past.sumOf { it.fiberG } / n).roundToInt()} g fibre"
            )
            appendLine("- Per day (kcal/protein g): " + past.joinToString(", ") {
                "${it.localDate.substring(5)} ${it.kcal.roundToInt()}/${it.proteinG.roundToInt()}"
            })
            val last7 = past.takeLast(7)
            Guardrails.fibreFlag(last7.map { it.fiberG }.average())?.let { appendLine("- Note: ${it.message}") }
            val below = last7.count { it.kcal < Guardrails.calorieFloor(p) }
            if (below > 0) appendLine("- Logged days below this person's calorie floor (${Guardrails.calorieFloor(p)} kcal) in the last 7: $below (partial logging also causes this)")
        }
        if (s.safetyMarginPct > 0) appendLine("- Photo-estimated foods already include the user's chosen safety margin.")
        appendLine()
    }

    private suspend fun StringBuilder.trainingSection(
        repo: ForgeRepository,
        db: ForgeDatabase,
        s: ForgeSettings,
        today: LocalDate
    ) {
        appendLine("TRAINING")
        val program = runCatching { repo.programDao.observeActive().first() }.getOrNull()
        if (program != null) {
            appendLine(
                "- Active program: ${program.name}" +
                    (program.author?.let { " by $it" } ?: "") +
                    (program.daysPerWeek?.let { ", $it days/week" } ?: "") +
                    (program.level?.let { ", ${it.lowercase()}" } ?: "")
            )
            val days = runCatching { repo.programDao.days(program.id) }.getOrDefault(emptyList())
            days.take(7).forEach { day ->
                val exercises = runCatching { repo.programDao.exercises(day.id) }.getOrDefault(emptyList())
                if (exercises.isNotEmpty()) {
                    appendLine("  ${day.name}: " + exercises.take(10).joinToString("; ") { e ->
                        "${e.name} ${e.sets}x${e.repLow}${if (e.repHigh != e.repLow) "-${e.repHigh}" else ""}" +
                            (e.rirTarget?.let { r -> " @${fmtNum(r)} RIR" } ?: "")
                    })
                }
            }
        } else {
            appendLine("- Active program: none")
        }

        val from14 = today.minusDays(13).toString()
        val sessions = runCatching { repo.workoutDao.observeSessions(30).first() }.getOrDefault(emptyList())
            .filter { it.localDate >= from14 }
        if (sessions.isEmpty()) {
            appendLine("- Sessions in the last 14 days: none logged")
        } else {
            appendLine("- Sessions in the last 14 days (${sessions.size}): " + sessions.sortedBy { it.startedAt }.joinToString("; ") {
                "${it.localDate.substring(5)} ${it.title}${if (it.endedAt == null) " (in progress)" else ""}"
            })
        }

        val sets = runCatching { db.insightsDao().workingSets(from14, today.toString()) }.getOrDefault(emptyList())
        if (sets.isNotEmpty()) {
            val best = sets.groupBy { it.exerciseName.trim() }
                .mapNotNull { (name, list) ->
                    val top = list.maxWithOrNull(compareBy<DatedSet>({ it.weightKg }, { it.reps })) ?: return@mapNotNull null
                    val last = list.maxByOrNull { it.localDate }?.localDate ?: top.localDate
                    Triple(name, top, last)
                }
                .sortedByDescending { it.third }
                .take(14)
            appendLine("- Best working set per lift, last 14 days:")
            best.forEach { (name, top, last) ->
                val effort = top.rir?.let { " @${fmtNum(it)} RIR" } ?: top.rpe?.let { " @RPE ${fmtNum(it)}" } ?: ""
                appendLine("  $name: ${fmtKg(top.weightKg)} kg x ${top.reps}$effort (last trained ${last.substring(5)})")
            }

            val report = runCatching {
                VolumeAnalyzer.analyzeRollingWeek(sets, today, s.trainingAge)
            }.getOrNull()
            if (report != null && report.hasData) {
                val trained = report.muscles.filter { it.sets > 0 || it.previousSets > 0 }
                if (trained.isNotEmpty()) {
                    appendLine("- Weekly hard sets per muscle (last 7 days, fractional; previous 7 days in brackets): " +
                        trained.joinToString(", ") { "${it.label.lowercase()} ${fmtNum(it.sets)} (${fmtNum(it.previousSets)})" })
                }
                if (report.untrained.isNotEmpty()) appendLine("- No hard sets in the last 7 days: ${report.untrained.joinToString(", ") { it.lowercase() }}")
                if (report.easySetsExcluded > 0) appendLine("- ${report.easySetsExcluded} logged sets were 5+ reps from failure and not counted")
                if (report.unresolved.isNotEmpty()) appendLine("- Exercises not in the catalog (not counted): ${report.unresolved.take(8).joinToString(", ")}")
                report.flags.take(6).forEach { appendLine("- Volume note: ${it.message}") }
            }
        } else {
            appendLine("- Working sets in the last 14 days: none logged")
        }
        appendLine()
    }

    private suspend fun StringBuilder.recoverySection(
        repo: ForgeRepository,
        today: LocalDate
    ) {
        appendLine("RECOVERY AND WEARABLES")
        // health_daily is merged across every wearable source by the wearables feature.
        val rows: List<HealthDaily> = runCatching {
            repo.healthDao.range(today.minusDays(27).toString(), today.toString())
        }.getOrDefault(emptyList())
        if (rows.isEmpty()) {
            appendLine("- No wearable data synced")
            appendLine()
            return
        }
        val sevenFrom = today.minusDays(6).toString()
        val recent = rows.filter { it.localDate >= sevenFrom }
        val baseline = rows.filter { it.localDate < sevenFrom }
        recent.forEach { h ->
            val parts = buildList {
                h.sleepMinutes?.let { add("sleep ${it / 60}h${(it % 60).toString().padStart(2, '0')}") }
                h.restingHr?.let { add("RHR $it") }
                h.hrvMs?.let { add("HRV ${it.roundToInt()} ms") }
                h.recoveryScore?.let { add("recovery ${it.roundToInt()}") }
                h.strain?.let { add("strain ${fmtNum(it)}") }
                h.steps?.let { add("$it steps") }
                h.activeKcal?.let { add("${it.roundToInt()} active kcal") }
                h.skinTempDeltaC?.let { add("skin temp ${fmtSigned(it)} C") }
            }
            if (parts.isNotEmpty()) appendLine("- ${h.localDate.substring(5)}: ${parts.joinToString(", ")}")
        }
        // Personal baseline from the 3 weeks before, so readiness is judged against this
        // person rather than population norms.
        val base = buildList {
            baseline.mapNotNull { it.sleepMinutes }.takeIf { it.isNotEmpty() }?.average()?.let {
                add("sleep ${(it / 60).toInt()}h${(it % 60).roundToInt().toString().padStart(2, '0')}")
            }
            baseline.mapNotNull { it.restingHr }.takeIf { it.isNotEmpty() }?.average()?.let { add("RHR ${it.roundToInt()}") }
            baseline.mapNotNull { it.hrvMs }.takeIf { it.isNotEmpty() }?.average()?.let { add("HRV ${it.roundToInt()} ms") }
            baseline.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()?.let { add("${it.roundToInt()} steps") }
        }
        if (base.isNotEmpty()) appendLine("- Baseline (previous 3 weeks, averages): ${base.joinToString(", ")}")
        val sources = rows.mapNotNull { it.sources }.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (sources.isNotEmpty()) appendLine("- Data sources: ${sources.take(5).joinToString(", ")} (HRV from Health Connect/WHOOP is RMSSD)")
        appendLine()
    }

    private fun StringBuilder.safetySection(p: GuardrailProfile) {
        appendLine("SAFETY STATE")
        appendLine("- Phases Forge allows for this person: ${Guardrails.allowedPhases(p).sorted().joinToString(", ")}")
        val blockers = Guardrails.deficitBlockers(p)
        if (blockers.isNotEmpty()) blockers.forEach { appendLine("- No deficit: ${it.message}") }
        if (p.weightKg > 0) {
            appendLine("- Calorie floor: ${Guardrails.calorieFloor(p)} kcal; max planned loss ${fmtNum(Guardrails.maxLossPctPerWeek(p))}% of bodyweight/week")
        }
    }

    // ------------------------------------------------------------------ formatting

    private fun fmtKg(v: Double): String = String.format(Locale.US, "%.1f", v)
    private fun fmtSigned(v: Double): String = String.format(Locale.US, "%+.2f", v)
    private fun fmtSignedInt(v: Int): String = if (v > 0) "+$v" else "$v"
    private fun fmtNum(v: Double): String =
        if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)
}

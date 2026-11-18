package com.laksh.forge.domain.training

import com.laksh.forge.data.db.DatedSet
import com.laksh.forge.data.prefs.TrainingAge
import com.laksh.forge.domain.exercise.ExerciseCatalog
import com.laksh.forge.domain.exercise.ExerciseInfo
import com.laksh.forge.domain.guardrails.GuardrailFlag
import com.laksh.forge.domain.guardrails.Guardrails
import java.time.LocalDate

/** Maps a logged exercise (free-text name plus optional catalog id) to its catalog entry. */
fun interface ExerciseResolver {
    fun resolve(name: String, exerciseId: String?): ExerciseInfo?
}

/** One muscle's weekly hard sets, counted fractionally. */
data class MuscleVolume(
    val muscle: String,
    val label: String,
    /** Fractional total: direct sets x 1.0 + indirect sets x 0.5. */
    val sets: Double,
    val directSets: Double,
    val indirectSets: Double,
    /** Distinct training days that gave this muscle at least one direct set. */
    val sessions: Int,
    /** The same fractional total for the 7 days before the window, for a week-on-week delta. */
    val previousSets: Double
)

data class VolumeReport(
    val from: String,
    val to: String,
    /** Every muscle with any counted volume in the window or the week before, most first. */
    val muscles: List<MuscleVolume>,
    /** Labels of catalog muscles with no counted volume this window. */
    val untrained: List<String>,
    /** Working sets that counted as hard sets (resolved exercise, within the RIR rule). */
    val hardSets: Int,
    /** Working sets left out because their logged RIR/RPE was too far from failure. */
    val easySetsExcluded: Int,
    /** Working sets whose exercise could not be matched to the catalog. */
    val unresolvedSets: Int,
    /** Distinct exercise names that could not be matched, so the user can fix them. */
    val unresolved: List<String>,
    val flags: List<GuardrailFlag>
) {
    val hasData: Boolean get() = hardSets > 0 || easySetsExcluded > 0 || unresolvedSets > 0
    val setsByMuscle: Map<String, Double> get() = muscles.associate { it.muscle to it.sets }
}

/**
 * Weekly hard sets per muscle, following training_set_counting_fractional
 * (Pelland et al. 2025: the fractional count best predicted hypertrophy and strength).
 *
 * Pure Kotlin so it runs off the main thread and in JVM tests; the catalog lookup is injected
 * through [ExerciseResolver] because the real catalog loads from Android assets.
 */
object VolumeAnalyzer {

    const val DIRECT_SET_WEIGHT = 1.0
    const val INDIRECT_SET_WEIGHT = 0.5

    /** The catalog-backed resolver used in the app. Prefers the stored id, then the name. */
    val CatalogResolver = ExerciseResolver { name, id ->
        runCatching {
            id?.takeIf { it.isNotBlank() }?.let { ExerciseCatalog.get(it) } ?: ExerciseCatalog.resolve(name)
        }.getOrNull()
    }

    /** Whether a logged set is close enough to failure to count (delegates to the guardrail). */
    fun countsAsHardSet(rir: Double?, rpe: Double?): Boolean = Guardrails.countsAsHardSet(rir, rpe)

    /**
     * Rolling 7-day window ending [today], compared against the 7 days before it. A rolling
     * window rather than a calendar week, so the numbers mean the same thing on a Monday as
     * on a Sunday.
     */
    fun analyzeRollingWeek(
        sets: List<DatedSet>,
        today: LocalDate,
        trainingAge: TrainingAge,
        resolver: ExerciseResolver = CatalogResolver
    ): VolumeReport = analyze(
        sets = sets,
        from = today.minusDays(6),
        to = today,
        trainingAge = trainingAge,
        resolver = resolver,
        previousFrom = today.minusDays(13),
        previousTo = today.minusDays(7)
    )

    /**
     * Count [sets] dated within [from]..[to] (inclusive). When a previous window is given,
     * its totals ride along as [MuscleVolume.previousSets]. Warm-ups never reach here: the
     * DAO only returns working sets.
     */
    fun analyze(
        sets: List<DatedSet>,
        from: LocalDate,
        to: LocalDate,
        trainingAge: TrainingAge,
        resolver: ExerciseResolver = CatalogResolver,
        previousFrom: LocalDate? = null,
        previousTo: LocalDate? = null
    ): VolumeReport {
        val cache = HashMap<Pair<String?, String>, ExerciseInfo?>()
        fun lookup(s: DatedSet): ExerciseInfo? =
            cache.getOrPut(s.exerciseId to s.exerciseName.trim().lowercase()) {
                resolver.resolve(s.exerciseName, s.exerciseId)
            }

        val fromStr = from.toString()
        val toStr = to.toString()
        val current = tally(sets.filter { it.localDate in fromStr..toStr }, ::lookup)
        val previous = if (previousFrom != null && previousTo != null) {
            val pf = previousFrom.toString()
            val pt = previousTo.toString()
            tally(sets.filter { it.localDate in pf..pt }, ::lookup)
        } else null

        val muscleIds = (current.total.keys + (previous?.total?.keys ?: emptySet()))
            .filter { (current.total[it] ?: 0.0) > 0.0 || (previous?.total?.get(it) ?: 0.0) > 0.0 }

        val muscles = muscleIds.map { id ->
            MuscleVolume(
                muscle = id,
                label = Muscles.label(id),
                sets = current.total[id] ?: 0.0,
                directSets = current.direct[id] ?: 0.0,
                indirectSets = current.indirect[id] ?: 0.0,
                sessions = current.directDays[id]?.size ?: 0,
                previousSets = previous?.total?.get(id) ?: 0.0
            )
        }.sortedWith(compareByDescending<MuscleVolume> { it.sets }.thenBy { Muscles.order(it.muscle) })

        val trainedNow = muscles.filter { it.sets > 0.0 }
        val untrained = Muscles.ALL.filter { (current.total[it] ?: 0.0) <= 0.0 }.map { Muscles.label(it) }

        val flags = buildList {
            addAll(
                Guardrails.trainingVolumeFlags(
                    trainedNow.associate { it.muscle to it.sets },
                    trainingAge
                )
            )
            trainedNow.forEach { m ->
                Guardrails.frequencyFlag(m.muscle, m.sets, m.sessions)?.let { add(it) }
            }
        }

        return VolumeReport(
            from = fromStr,
            to = toStr,
            muscles = muscles,
            untrained = untrained,
            hardSets = current.hardSets,
            easySetsExcluded = current.easySets,
            unresolvedSets = current.unresolvedSets,
            unresolved = current.unresolvedNames,
            flags = flags
        )
    }

    private class Tally {
        val total = HashMap<String, Double>()
        val direct = HashMap<String, Double>()
        val indirect = HashMap<String, Double>()
        val directDays = HashMap<String, MutableSet<String>>()
        var hardSets = 0
        var easySets = 0
        var unresolvedSets = 0
        val unresolvedNames = LinkedHashSet<String>()
    }

    private class TallyResult(
        val total: Map<String, Double>,
        val direct: Map<String, Double>,
        val indirect: Map<String, Double>,
        val directDays: Map<String, Set<String>>,
        val hardSets: Int,
        val easySets: Int,
        val unresolvedSets: Int,
        val unresolvedNames: List<String>
    )

    private fun tally(sets: List<DatedSet>, lookup: (DatedSet) -> ExerciseInfo?): TallyResult {
        val t = Tally()
        for (s in sets) {
            val info = lookup(s)
            if (info == null || (info.primaryMuscles.isEmpty() && info.secondaryMuscles.isEmpty())) {
                // Guessing muscles for an unknown name would put made-up numbers next to
                // evidence bands, so the set is skipped and the name surfaced instead.
                t.unresolvedSets++
                t.unresolvedNames += s.exerciseName.trim()
                continue
            }
            if (!countsAsHardSet(s.rir, s.rpe)) {
                t.easySets++
                continue
            }
            t.hardSets++
            val primary = info.primaryMuscles.distinct()
            // A muscle listed as both primary and secondary is a direct target; counting it
            // twice would inflate it to 1.5 sets.
            val secondary = info.secondaryMuscles.distinct().filter { it !in primary }
            for (m in primary) {
                t.total.merge(m, DIRECT_SET_WEIGHT, Double::plus)
                t.direct.merge(m, DIRECT_SET_WEIGHT, Double::plus)
                t.directDays.getOrPut(m) { HashSet() } += s.localDate
            }
            for (m in secondary) {
                t.total.merge(m, INDIRECT_SET_WEIGHT, Double::plus)
                t.indirect.merge(m, INDIRECT_SET_WEIGHT, Double::plus)
            }
        }
        return TallyResult(
            total = t.total,
            direct = t.direct,
            indirect = t.indirect,
            directDays = t.directDays,
            hardSets = t.hardSets,
            easySets = t.easySets,
            unresolvedSets = t.unresolvedSets,
            unresolvedNames = t.unresolvedNames.toList()
        )
    }
}

package com.laksh.forge.domain.program

import com.laksh.forge.ForgeApp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// CONTRACT - LibraryProgramSummary, summaries() and recommend() are used by other features
// (onboarding recommends a program). Everything else here is this feature's own model of the
// verified templates bundled in assets/programs/.

data class LibraryProgramSummary(
    val id: String,
    val name: String,
    val author: String,
    val category: String,       // STRENGTH | POWERBUILDING | HYPERTROPHY | GENERAL
    val level: String,          // BEGINNER | INTERMEDIATE | ADVANCED
    val daysPerWeek: Int,
    val summary: String
)

// ------------------------------------------------------------------ template model

@Serializable
data class ProgramSource(val title: String, val url: String, val kind: String? = null)

@Serializable
data class IncrementKg(val upper: Double? = null, val lower: Double? = null)

/** The program's own progression text, shown verbatim as "in the author's words". */
@Serializable
data class ProgressionText(
    val type: String = "CUSTOM",
    val description: String = "",
    val incrementKg: IncrementKg? = null,
    val deload: String? = null
)

@Serializable
data class Intensity(val type: String = "NONE", val value: Double? = null)

/**
 * One prescribed set. [of] is the basis of [percent]: TM, 1RM, 5RM, TOP_SET (the heaviest set
 * logged today for the slot's ref lift) or RECENT_RM (the heaviest recent 3-5 rep set).
 */
@Serializable
data class SetSpec(
    val reps: Int? = null,
    val repsLow: Int? = null,
    val repsHigh: Int? = null,
    val percent: Double? = null,
    val of: String? = null,
    val amrap: Boolean = false,
    val addKg: Double? = null
)

/**
 * A slot in a training day. The verified fields come straight from the research file; [rule]
 * and the fields after it were added by the converter to say which of the program's stated rules
 * drives this slot (see docs in [ProgressionEngine]).
 */
@Serializable
data class LibraryExercise(
    val exerciseId: String? = null,
    val name: String,
    val sets: Int? = null,
    val repsLow: Int? = null,
    val repsHigh: Int? = null,
    val setScheme: List<SetSpec>? = null,
    val intensity: Intensity? = null,
    val lastSetAmrap: Boolean = false,
    val restSeconds: Int? = null,
    val notes: String? = null,
    val optional: Boolean = false,
    val rule: String = Rules.NONE,
    val incrementKg: Double? = null,
    val maxKey: String? = null,
    val ref: String? = null,
    val tier: String? = null,
    val role: String? = null,
    val perSet: Boolean = false,
    val dropBelowLow: Boolean = false,
    val everyWeeks: Int? = null,
    val cutBelowReps: Int? = null,
    val note1rm: String? = null
) {
    val setCount: Int get() = setScheme?.size?.takeIf { it > 0 } ?: (sets ?: 1).coerceAtLeast(1)

    /** Target RIR, converting RPE with the standard RIR-based scale (RPE 10 = 0 in reserve). */
    val rirTarget: Double?
        get() = when (intensity?.type) {
            "RIR" -> intensity.value
            "RPE" -> intensity.value?.let { (10.0 - it).coerceAtLeast(0.0) }
            else -> null
        }
}

@Serializable
data class LibraryDay(val name: String, val focus: String? = null, val exercises: List<LibraryExercise> = emptyList())

@Serializable
data class LibraryWeek(val week: Int, val label: String? = null, val days: List<LibraryDay> = emptyList())

@Serializable
data class AmrapRule(val minReps: Int, val maxReps: Int? = null, val addKg: Double)

/** A number the adopt sheet asks for. [basis]: TM | 1RM | 5RM | START. */
@Serializable
data class SetupInput(
    val key: String,
    val basis: String,
    val label: String,
    val exerciseId: String? = null,
    val reps: Int = 5
)

/** How the engine runs this program, derived by the converter from the program's own rules. */
@Serializable
data class EngineConfig(
    /** LINEAR | DOUBLE | GZCLP | WENDLER | NSUNS | CANDITO | TEXAS | MADCOW | WAVE | JT2 | STANDARD */
    val model: String = "STANDARD",
    /** SESSION or WEEK: how often LINEAR slots may add weight. */
    val cadence: String = "SESSION",
    val failLimit: Int? = null,
    val deloadPct: Double? = null,
    /** After a deload session, go straight back to the weight that was failed (Helms novice). */
    val bounceBack: Boolean = false,
    /** SLOT: history by exercise name; DAY_SLOT: also by training day. */
    val scope: String = "SLOT",
    /** Training max as a share of a 1RM, when the program states it (5/3/1 85%, nSuns 90%). */
    val tmOfMax: Double? = null,
    val amrapRules: List<AmrapRule> = emptyList(),
    val allRepsKeys: List<String> = emptyList(),
    val cycleWeeks: Int? = null,
    val optionalWeeks: List<Int> = emptyList(),
    val deloadWeeks: List<Int> = emptyList(),
    val deloadSets: Int? = null,
    val deloadChecklist: List<String> = emptyList(),
    val missedRepCutPct: Double? = null,
    val projectionWeek: Int? = null,
    val projectionFactors: Map<String, Double> = emptyMap(),
    /** Day-order cycles for phased programs (Starting Strength). Null = days in order. */
    val rotation: List<List<Int>>? = null,
    /** True when the source gives no load step and the app's default is used instead. */
    val incrementIsAppDefault: Boolean = false,
    val setup: List<SetupInput> = emptyList()
)

@Serializable
data class LibraryProgram(
    val id: String,
    val name: String,
    val author: String,
    val isFree: Boolean = true,
    val category: String,
    val level: String,
    val daysPerWeek: Int,
    val sessionMinutes: Int? = null,
    val durationWeeks: Int? = null,
    val equipment: List<String> = emptyList(),
    val summary: String = "",
    val evidenceNote: String = "",
    val sources: List<ProgramSource> = emptyList(),
    val schedule: String = "",
    val progression: ProgressionText = ProgressionText(),
    val days: List<LibraryDay> = emptyList(),
    val weeks: List<LibraryWeek>? = null,
    val notes: String? = null,
    val verifiedAgainst: List<String> = emptyList(),
    val engine: EngineConfig = EngineConfig()
) {
    val isWave: Boolean get() = !weeks.isNullOrEmpty()

    fun toSummary() = LibraryProgramSummary(id, name, author, category, level, daysPerWeek, summary)

    /** Every distinct training day across the base days and all weeks, in first-seen order. */
    fun allDayNames(): List<String> {
        val names = LinkedHashSet<String>()
        days.forEach { names += it.name }
        weeks.orEmpty().forEach { w -> w.days.forEach { names += it.name } }
        return names.toList()
    }

    /** The template for [dayName], from the base days or the first week that has it. */
    fun dayTemplate(dayName: String): LibraryDay? =
        days.firstOrNull { it.name == dayName }
            ?: weeks.orEmpty().firstNotNullOfOrNull { w -> w.days.firstOrNull { it.name == dayName } }
}

/** Rule ids written by the converter. */
object Rules {
    const val NONE = "NONE"
    const val LINEAR = "LINEAR"
    const val DOUBLE = "DOUBLE"
    const val RIR = "RIR"
    const val PERCENT = "PERCENT"
    const val TOP_SET = "TOP_SET"
    const val RECENT_RM = "RECENT_RM"
    const val WORK_UP = "WORK_UP"
    const val STAGES = "STAGES"
    const val RAMP = "RAMP"
    const val PR_ATTEMPT = "PR_ATTEMPT"
    const val WAVE = "WAVE"
}

// ------------------------------------------------------------------ loader

object ProgramLibrary {

    /** Lenient on purpose: assets gain fields over time and older app code must still read them. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    /**
     * Loaded once, on first use, off whatever thread asks first. The asset set is ~1 MB of JSON,
     * so callers should hit this from a background dispatcher the first time.
     */
    private val programs: List<LibraryProgram> by lazy { loadFromAssets() }

    private fun loadFromAssets(): List<LibraryProgram> {
        val assets = ForgeApp.instance.assets
        val files = assets.list("programs").orEmpty()
            .filter { it.endsWith(".json") && it != "index.json" }
            .sorted()
        return files.mapNotNull { file ->
            // One malformed file must not take the whole library down.
            runCatching {
                assets.open("programs/$file").bufferedReader().use { parse(it.readText()) }
            }.getOrNull()
        }
    }

    fun parse(text: String): LibraryProgram = json.decodeFromString(LibraryProgram.serializer(), text)

    /** Every bundled program, full detail. */
    fun all(): List<LibraryProgram> = programs

    fun template(id: String): LibraryProgram? = programs.firstOrNull { it.id == id }

    fun summaries(): List<LibraryProgramSummary> = programs.map { it.toSummary() }

    /**
     * Best-fit programs for a person, best first. [level] is BEGINNER/INTERMEDIATE/ADVANCED,
     * [goal] a phase id from domain/guardrails/Phases (or a category such as STRENGTH),
     * [daysPerWeek] null when unknown.
     */
    fun recommend(level: String, goal: String, daysPerWeek: Int?): List<LibraryProgramSummary> =
        rank(programs, level, goal, daysPerWeek).take(3).map { it.toSummary() }

    /** [recommend] limited to programs whose equipment is all in [available] (null = anything). */
    fun recommend(level: String, goal: String, daysPerWeek: Int?, available: Set<String>?): List<LibraryProgramSummary> =
        rank(programs.filter { fitsEquipment(it, available) }, level, goal, daysPerWeek).take(3).map { it.toSummary() }

    fun fitsEquipment(p: LibraryProgram, available: Set<String>?): Boolean =
        available == null || p.equipment.all { it in available }

    /** Full ranking, exposed for the library screen and tests. */
    fun rank(
        candidates: List<LibraryProgram>,
        level: String,
        goal: String,
        daysPerWeek: Int?
    ): List<LibraryProgram> =
        candidates
            .map { it to Recommender.score(it, level, goal, daysPerWeek) }
            .filter { it.second > Float.NEGATIVE_INFINITY }
            .sortedWith(compareByDescending<Pair<LibraryProgram, Float>> { it.second }.thenBy { it.first.name })
            .map { it.first }
}

/**
 * Scoring for "recommended for you". These weights are product heuristics, not research claims:
 * matching the lifter's level matters most (a beginner on an advanced template stalls or gets
 * hurt), then fitting the days they actually have, then leaning toward the training style that
 * suits their current goal.
 */
internal object Recommender {

    private val levels = listOf("BEGINNER", "INTERMEDIATE", "ADVANCED")

    fun score(p: LibraryProgram, level: String, goal: String, days: Int?): Float {
        val user = levels.indexOf(level.uppercase()).let { if (it < 0) 0 else it }
        val prog = levels.indexOf(p.level.uppercase()).let { if (it < 0) 1 else it }
        val levelScore = when {
            user == prog -> 3f
            // Experienced lifters can run simpler programs; the reverse is the risky direction.
            prog < user -> if (user - prog == 1) 1.5f else 0.25f
            prog - user == 1 -> 0.25f
            else -> return Float.NEGATIVE_INFINITY
        }
        val dayScore = when {
            days == null -> 0f
            p.daysPerWeek == days -> 2.5f
            kotlin.math.abs(p.daysPerWeek - days) == 1 -> 0.75f
            else -> -1f * (kotlin.math.abs(p.daysPerWeek - days) - 1)
        }
        val goalScore = goalAffinity(goal.uppercase(), p.category.uppercase())
        return levelScore + dayScore + goalScore
    }

    private fun goalAffinity(goal: String, category: String): Float {
        // A category id passed directly ("I want strength") is the strongest signal.
        if (goal in setOf("STRENGTH", "HYPERTROPHY", "POWERBUILDING", "GENERAL")) {
            return when {
                goal == category -> 2f
                category == "POWERBUILDING" || goal == "POWERBUILDING" -> 1f
                goal == "GENERAL" -> 0.5f
                else -> 0f
            }
        }
        // Diet phases: building phases lean toward muscle-gain templates; a cut or maintenance
        // phase leans toward keeping strength with less total volume; recomp sits in between.
        return when (goal) {
            "LEAN_GAIN", "MAINGAIN" -> when (category) {
                "HYPERTROPHY" -> 1.5f; "POWERBUILDING" -> 1.25f; else -> 0.5f
            }
            "CUT", "DIET_BREAK", "MAINTAIN", "REVERSE_DIET" -> when (category) {
                "STRENGTH" -> 1.25f; "POWERBUILDING" -> 1f; else -> 0.75f
            }
            "RECOMP" -> when (category) {
                "POWERBUILDING" -> 1.5f; "HYPERTROPHY" -> 1.25f; else -> 0.75f
            }
            else -> 0.5f
        }
    }
}

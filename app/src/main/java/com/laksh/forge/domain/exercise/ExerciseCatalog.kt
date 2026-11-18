package com.laksh.forge.domain.exercise

import com.laksh.forge.ForgeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TutorialVideo(
    val videoId: String,
    val title: String,
    val author: String,
    val why: String
) {
    val url: String get() = "https://www.youtube.com/watch?v=$videoId"
    val thumbnailUrl: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}

data class ExerciseInfo(
    /** Canonical snake_case id, e.g. "back_squat". */
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    /** Muscle ids: chest, front_delts, side_delts, rear_delts, triceps, biceps, forearms,
     *  lats, upper_back, traps, lower_back, abs, obliques, glutes, quads, hamstrings,
     *  adductors, calves. */
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: List<String> = emptyList(),
    val cues: List<String> = emptyList(),
    val mistakes: List<String> = emptyList(),
    val videos: List<TutorialVideo> = emptyList(),
    /** Still images (free-exercise-db; the photos' copyright owner is unknown, see [CatalogLicence]). */
    val images: List<String> = emptyList(),
    /** Whether a 3D skeletal animation exists for this exercise. */
    val hasMotion: Boolean = false
)

/**
 * The bundled exercise catalog (assets/exercises/catalog.json): 62 canonical lifts with
 * verified cues, tutorial videos, photos and motion keyframes.
 *
 * Loading is lazy and synchronous on first use because the contract API is not suspending;
 * screens call [preload] from a background dispatcher first so the ~250 KB parse never lands
 * on the main thread.
 */
object ExerciseCatalog {

    private const val ASSET = "exercises/catalog.json"

    private class Loaded(
        val parsed: ParsedCatalog,
        val byId: Map<String, ExerciseInfo>,
        val matcher: ExerciseNameMatcher,
        val searchIndex: List<SearchEntry>,
        val error: String?
    )

    private class SearchEntry(val info: ExerciseInfo, val nameWords: List<String>, val otherWords: List<String>)

    @Volatile
    private var loaded: Loaded? = null
    private val lock = Any()

    /** Every catalogued exercise. Loaded lazily from assets on first use. */
    fun all(): List<ExerciseInfo> = data().parsed.exercises

    fun get(id: String): ExerciseInfo? = data().byId[id]

    /**
     * Resolve a free-text name ("Barbell Back Squat", "BB Bench", "lat pull-down") to a
     * catalog entry, or null. Used for PDF-imported programs and the session screen.
     */
    fun resolve(name: String): ExerciseInfo? {
        val d = data()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        d.byId[trimmed]?.let { return it }
        return d.matcher.match(trimmed)?.let { d.byId[it] }
    }

    /** Route key for an exercise: its id when known, otherwise the raw name. */
    fun keyFor(name: String): String = resolve(name)?.id ?: name

    // ------------------------------------------------------------------ beyond the contract

    /** Parse the catalog off the main thread. Safe to call repeatedly. */
    suspend fun preload() {
        if (loaded != null) return
        withContext(Dispatchers.IO) { data() }
    }

    /** Why the catalog is empty, when loading failed. */
    fun loadError(): String? = data().error

    fun motion(id: String): ExerciseMotion? = data().parsed.motions[id]

    fun extras(id: String): ExerciseExtras? = data().parsed.extras[id]

    /** GVS tier for a video author, when GVS ranked them. */
    fun tierOf(author: String): String? = data().parsed.authorTiers[author]

    /** Technique channels from the trusted-creator list, for searching lifts not in the catalog. */
    fun techniqueCreators(): List<Creator> = data().parsed.creators

    fun licence(): CatalogLicence = data().parsed.licence

    /**
     * Ranked search for the browser. Every query word must prefix-match a word of the name,
     * an alias, a muscle or a piece of equipment; name hits rank above alias hits.
     */
    fun search(query: String): List<ExerciseInfo> {
        val d = data()
        val q = ExerciseNameMatcher.tokens(query)
        if (q.isEmpty()) return d.parsed.exercises
        val resolved = d.matcher.match(query)
        val scored = ArrayList<Pair<ExerciseInfo, Int>>()
        for (entry in d.searchIndex) {
            val info = entry.info
            val nameWords = entry.nameWords
            val words = entry.otherWords
            var score = 0
            var all = true
            for (t in q) {
                when {
                    nameWords.any { it.startsWith(t) } -> score += 3
                    words.any { it.startsWith(t) } -> score += 1
                    else -> { all = false; break }
                }
            }
            if (info.id == resolved) score += 10
            if (all || info.id == resolved) scored += info to score
        }
        return scored.sortedWith(compareByDescending<Pair<ExerciseInfo, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
    }

    /** For tests: install a catalog from JSON text instead of reading the asset. */
    internal fun install(json: String) {
        synchronized(lock) { loaded = build(CatalogParser.parse(json), null) }
    }

    private fun data(): Loaded {
        loaded?.let { return it }
        synchronized(lock) {
            loaded?.let { return it }
            val result = runCatching {
                val text = ForgeApp.instance.assets.open(ASSET).bufferedReader().use { it.readText() }
                build(CatalogParser.parse(text), null)
            }.getOrElse { e ->
                build(EMPTY, "Could not load the exercise library: ${e.message ?: e.javaClass.simpleName}")
            }
            loaded = result
            return result
        }
    }

    private fun build(p: ParsedCatalog, error: String?): Loaded {
        val byId = p.exercises.associateBy { it.id }
        val matcher = ExerciseNameMatcher(p.exercises.map { e -> e.id to (listOf(e.name, e.id.replace('_', ' ')) + e.aliases) })
        val index = p.exercises.map { e ->
            val words = ArrayList<String>()
            (e.aliases + e.primaryMuscles.map(ExerciseLabels::muscle) + e.secondaryMuscles.map(ExerciseLabels::muscle) +
                e.equipment.map(ExerciseLabels::equipment)).forEach { words += ExerciseNameMatcher.tokens(it) }
            SearchEntry(e, ExerciseNameMatcher.tokens(e.name), words.distinct())
        }
        return Loaded(p, byId, matcher, index, error)
    }

    private val EMPTY = ParsedCatalog(
        exercises = emptyList(),
        motions = emptyMap(),
        extras = emptyMap(),
        authorTiers = emptyMap(),
        creators = emptyList(),
        licence = CatalogLicence("", "", "", "", "")
    )
}

/** Display labels for the ids used in the catalog. */
object ExerciseLabels {
    val MUSCLES = listOf(
        "chest", "front_delts", "side_delts", "rear_delts", "triceps", "biceps", "forearms",
        "lats", "upper_back", "traps", "lower_back", "abs", "obliques", "glutes", "quads",
        "hamstrings", "adductors", "calves"
    )

    fun muscle(id: String): String = when (id) {
        "chest" -> "Chest"
        "front_delts" -> "Front delts"
        "side_delts" -> "Side delts"
        "rear_delts" -> "Rear delts"
        "triceps" -> "Triceps"
        "biceps" -> "Biceps"
        "forearms" -> "Forearms"
        "lats" -> "Lats"
        "upper_back" -> "Upper back"
        "traps" -> "Traps"
        "lower_back" -> "Lower back"
        "abs" -> "Abs"
        "obliques" -> "Obliques"
        "glutes" -> "Glutes"
        "quads" -> "Quads"
        "hamstrings" -> "Hamstrings"
        "adductors" -> "Adductors"
        "calves" -> "Calves"
        else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun equipment(id: String): String = when (id) {
        "barbell" -> "Barbell"
        "ez_bar" -> "EZ bar"
        "dumbbell" -> "Dumbbell"
        "cable" -> "Cable"
        "machine" -> "Machine"
        "bodyweight" -> "Bodyweight"
        "bench" -> "Bench"
        "incline_bench" -> "Incline bench"
        "squat_rack" -> "Squat rack"
        "box" -> "Box"
        "pullup_bar" -> "Pull-up bar"
        "dip_bars" -> "Dip bars"
        "ab_wheel" -> "Ab wheel"
        "preacher_bench" -> "Preacher bench"
        "hyperextension_bench" -> "Hyperextension bench"
        else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

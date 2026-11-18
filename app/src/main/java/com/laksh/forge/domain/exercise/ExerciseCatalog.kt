package com.laksh.forge.domain.exercise

// CONTRACT - owned by the exercise feature, which replaces the loader with the bundled
// catalog in assets/exercises/. Keep these types and the ExerciseCatalog functions.

data class TutorialVideo(
    val videoId: String,
    val title: String,
    val author: String,
    val why: String
) {
    val url: String get() = "https://www.youtube.com/watch?v=$videoId"
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
    /** Still images (free-exercise-db, public domain). */
    val images: List<String> = emptyList(),
    /** Whether a 3D skeletal animation exists for this exercise. */
    val hasMotion: Boolean = false
)

object ExerciseCatalog {
    /** Every catalogued exercise. Loaded lazily from assets on first use. */
    fun all(): List<ExerciseInfo> = emptyList()

    fun get(id: String): ExerciseInfo? = all().firstOrNull { it.id == id }

    /**
     * Resolve a free-text name ("Barbell Back Squat", "BB Bench", "lat pull-down") to a
     * catalog entry, or null. Used for PDF-imported programs and the session screen.
     */
    fun resolve(name: String): ExerciseInfo? {
        val n = name.trim().lowercase()
        return all().firstOrNull { e ->
            e.id == n || e.name.lowercase() == n || e.aliases.any { it.lowercase() == n }
        }
    }

    /** Route key for an exercise: its id when known, otherwise the raw name. */
    fun keyFor(name: String): String = resolve(name)?.id ?: name
}

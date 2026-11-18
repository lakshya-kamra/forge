package com.laksh.forge.domain.training

/**
 * Muscle ids used by the exercise catalog (see ExerciseInfo.primaryMuscles), with the labels
 * shown to the user. Ordered roughly head to toe so lists read naturally when volumes tie.
 */
object Muscles {

    val ALL: List<String> = listOf(
        "chest", "front_delts", "side_delts", "rear_delts", "triceps", "biceps", "forearms",
        "lats", "upper_back", "traps", "lower_back", "abs", "obliques",
        "glutes", "quads", "hamstrings", "adductors", "calves"
    )

    private val LABELS: Map<String, String> = mapOf(
        "chest" to "Chest",
        "front_delts" to "Front delts",
        "side_delts" to "Side delts",
        "rear_delts" to "Rear delts",
        "triceps" to "Triceps",
        "biceps" to "Biceps",
        "forearms" to "Forearms",
        "lats" to "Lats",
        "upper_back" to "Upper back",
        "traps" to "Traps",
        "lower_back" to "Lower back",
        "abs" to "Abs",
        "obliques" to "Obliques",
        "glutes" to "Glutes",
        "quads" to "Quads",
        "hamstrings" to "Hamstrings",
        "adductors" to "Adductors",
        "calves" to "Calves"
    )

    /**
     * Display label for a muscle id. The catalog is maintained by another feature, so an id
     * this list does not know still gets a readable label instead of a raw snake_case key.
     */
    fun label(id: String): String = LABELS[id]
        ?: id.replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }

    /** Sort key: known muscles in [ALL] order, unknown ones after them. */
    fun order(id: String): Int = ALL.indexOf(id).let { if (it < 0) ALL.size else it }
}

package com.laksh.forge.domain.program

// CONTRACT - owned by the program library feature, which replaces the loader with the
// verified templates in assets/programs/. Keep LibraryProgramSummary and these functions.

data class LibraryProgramSummary(
    val id: String,
    val name: String,
    val author: String,
    val category: String,       // STRENGTH | POWERBUILDING | HYPERTROPHY | GENERAL
    val level: String,          // BEGINNER | INTERMEDIATE | ADVANCED
    val daysPerWeek: Int,
    val summary: String
)

object ProgramLibrary {
    fun summaries(): List<LibraryProgramSummary> = emptyList()

    /**
     * Best-fit programs for a person, best first. [level] is BEGINNER/INTERMEDIATE/ADVANCED,
     * [goal] a phase id from domain/guardrails/Phases, [daysPerWeek] null when unknown.
     */
    fun recommend(level: String, goal: String, daysPerWeek: Int?): List<LibraryProgramSummary> =
        summaries().filter { it.level == level }.take(3)
}

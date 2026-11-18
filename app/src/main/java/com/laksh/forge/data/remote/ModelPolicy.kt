package com.laksh.forge.data.remote

/** The distinct jobs Forge asks Claude to do. Each has its own difficulty and frequency. */
enum class Task { FOOD_PHOTO, LABEL, PROGRAM, COACH, PING }

/**
 * Cost tiers.
 *
 * The tasks differ enormously in how much model they actually need, so sending everything to
 * one model wastes money. Reading a printed nutrition panel is transcription and Haiku does it
 * as well as anything; judging portion size from a photo genuinely benefits from a stronger
 * model; parsing a training PDF is rare but expensive to get wrong.
 */
enum class CostTier(val label: String, val blurb: String) {
    ECONOMY(
        "Economy",
        "Haiku wherever it holds up. Cheapest by a wide margin; portion estimates get a little rougher."
    ),
    BALANCED(
        "Balanced",
        "Sonnet for judgement, Haiku for label reading. Roughly a fifth of Maximum's cost."
    ),
    MAXIMUM(
        "Maximum",
        "Opus for photo estimates and coaching. Best quality, and the most expensive by far."
    );

    fun modelFor(task: Task): String = when (this) {
        ECONOMY -> when (task) {
            Task.FOOD_PHOTO -> HAIKU
            Task.LABEL -> HAIKU
            // Still Sonnet: a program is imported once and a misread set/rep scheme is a
            // problem you live with for a whole block. The one-off cost is a few cents.
            Task.PROGRAM -> SONNET
            Task.COACH -> HAIKU
            Task.PING -> HAIKU
        }
        BALANCED -> when (task) {
            Task.FOOD_PHOTO -> SONNET
            Task.LABEL -> HAIKU
            Task.PROGRAM -> SONNET
            Task.COACH -> SONNET
            Task.PING -> HAIKU
        }
        MAXIMUM -> when (task) {
            Task.FOOD_PHOTO -> OPUS
            Task.LABEL -> HAIKU        // transcription; a bigger model buys nothing here
            Task.PROGRAM -> OPUS
            Task.COACH -> OPUS
            Task.PING -> HAIKU
        }
    }

    /** Rough monthly estimate at ~4 photo logs and ~3 coach questions a day. */
    val roughMonthly: String
        get() = when (this) {
            ECONOMY -> "about $2-3 / month"
            BALANCED -> "about $4-6 / month"
            MAXIMUM -> "about $15-25 / month"
        }

    companion object {
        const val OPUS = "claude-opus-5"
        const val SONNET = "claude-sonnet-5"
        const val HAIKU = "claude-haiku-4-5"
    }
}

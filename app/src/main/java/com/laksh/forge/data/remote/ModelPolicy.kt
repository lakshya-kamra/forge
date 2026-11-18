package com.laksh.forge.data.remote

/** The distinct jobs Forge asks a model to do. Each has its own difficulty and frequency. */
enum class Task {
    FOOD_PHOTO,
    LABEL,
    PROGRAM,
    COACH,
    /** Onboarding: estimate body composition from optional photos plus measurements. */
    BODY,
    /** Onboarding / re-plan: choose a phase and build a plan inside the guardrails. */
    PLAN,
    PING
}

/**
 * Cost tiers for Anthropic keys. Other providers map tiers to their own models in the
 * provider registry (data/ai).
 *
 * The tasks differ enormously in how much model they need: reading a printed nutrition panel
 * is transcription, judging portion size from a photo is judgement, and the onboarding plan
 * is rare but consequential.
 */
enum class CostTier(val label: String, val blurb: String) {
    ECONOMY(
        "Economy",
        "Haiku 5.5 for everything. Pennies a month; estimates get slightly rougher."
    ),
    BALANCED(
        "Balanced",
        "Sonnet 5.5 for judgement, Haiku 5.5 for transcription. The recommended default."
    ),
    MAXIMUM(
        "Maximum",
        "Opus 5.5 for photos, coaching and planning. Best quality, highest cost."
    );

    fun modelFor(task: Task): String = when (this) {
        ECONOMY -> HAIKU
        BALANCED -> when (task) {
            Task.LABEL, Task.PING -> HAIKU
            else -> SONNET
        }
        MAXIMUM -> when (task) {
            Task.LABEL, Task.PING -> HAIKU   // transcription; a bigger model buys nothing here
            else -> OPUS
        }
    }

    /** Rough monthly estimate at ~4 photo logs and ~3 coach questions a day. */
    val roughMonthly: String
        get() = when (this) {
            ECONOMY -> "under $0.50 / month"
            BALANCED -> "about $4-6 / month"
            MAXIMUM -> "about $12-20 / month"
        }

    companion object {
        const val OPUS = "claude-opus-5-5"
        const val SONNET = "claude-sonnet-5-5"
        const val HAIKU = "claude-haiku-5-5"
    }
}

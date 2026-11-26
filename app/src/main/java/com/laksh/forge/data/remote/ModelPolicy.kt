package com.laksh.forge.data.remote

import com.laksh.forge.data.ai.CostEstimator
import com.laksh.forge.data.ai.ModelRouter
import com.laksh.forge.data.ai.ProviderRegistry
import java.time.LocalDate

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
 * Cost tiers. Every provider maps them to its own models through the registry in data/ai
 * (economy / balanced / max picks per job from docs/research/providers.json).
 *
 * The tasks differ enormously in how much model they need: reading a printed nutrition panel
 * is transcription, judging portion size from a photo is judgement, and the onboarding plan
 * is rare but consequential. So labels always use the economy pick, whatever the tier.
 */
enum class CostTier(val label: String, val blurb: String) {
    ULTRA_CHEAP(
        "Ultra-cheap",
        "Open models for photos and labels, a small Claude for the coach and plans, with zero data " +
            "retention enforced. Only offered where Forge has a researched preset (OpenRouter)."
    ),
    ECONOMY(
        "Economy",
        "The cheapest capable model for every job. Pennies a month; estimates get slightly rougher."
    ),
    BALANCED(
        "Balanced",
        "A stronger model for judgement (photos, coach, plans), the cheap one for transcription. Recommended."
    ),
    MAXIMUM(
        "Maximum",
        "The provider's top models for photos, coaching and planning. Best quality, highest cost."
    );

    /**
     * The Anthropic model for [task] at this tier. Kept for v1 call sites; other providers
     * route through [ModelRouter] with their own registry entry.
     */
    fun modelFor(task: Task): String =
        runCatching {
            ModelRouter.pick(ProviderRegistry.anthropic, this, task, emptyMap(), needsVision = false).model
        }.getOrDefault(HAIKU)

    /**
     * Rough monthly figure at Anthropic prices (the usage in [CostEstimator.ASSUMPTIONS]), computed
     * from the registry rather than hand-typed so it can't drift from the prices.
     */
    val roughMonthly: String
        get() {
            val total = CostEstimator.monthly(ProviderRegistry.anthropic, this, emptyMap(), LocalDate.now()).totalUsd
            return if (total == null) "price unknown" else "about ${CostEstimator.formatUsd(total)} / month"
        }

    companion object {
        const val OPUS = "claude-opus-5-5"
        const val SONNET = "claude-sonnet-5-5"
        const val HAIKU = "claude-haiku-5-5"
    }
}

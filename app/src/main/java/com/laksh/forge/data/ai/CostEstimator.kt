package com.laksh.forge.data.ai

import com.laksh.forge.data.remote.CostTier
import com.laksh.forge.data.remote.Task
import java.time.LocalDate
import java.util.Locale

/**
 * Monthly cost from the registry prices at a stated usage pattern. The token counts are
 * Forge's own assumptions (written out below and shown in Settings), not provider figures,
 * except where a provider documents a per-image cost (Together, Groq's vision model).
 *
 * Deliberately conservative: no prompt-cache discount, and reasoning models are given some
 * room for thinking tokens in the output figure.
 */
object CostEstimator {

    const val PHOTOS_PER_DAY = 4
    const val COACH_PER_DAY = 3
    const val LABELS_PER_DAY = 1
    private const val DAYS_PER_MONTH = 30.4

    /** One kind of call: how often it happens and roughly what it sends and receives. */
    private data class Usage(
        val task: Task,
        val perDay: Int,
        val textInTokens: Int,
        /** Image tokens at Forge's downscale size when the provider states no figure. */
        val defaultImageTokens: Int,
        val outTokens: Int
    )

    // Meal photo: ~650-token system prompt + body context; the 1000px image is ~1000 tokens on
    // area-based tokenisers (ImageUtils.EDGE_FOOD); JSON answer plus some reasoning.
    // Label: shorter prompt, larger 1400px image (~2000 tokens), short JSON answer.
    // Coach: ~2.5K-token user_data context plus ~1.5K of history, ~500-token reply.
    private val usage = listOf(
        Usage(Task.FOOD_PHOTO, PHOTOS_PER_DAY, textInTokens = 900, defaultImageTokens = 1000, outTokens = 700),
        Usage(Task.COACH, COACH_PER_DAY, textInTokens = 4000, defaultImageTokens = 0, outTokens = 500),
        Usage(Task.LABEL, LABELS_PER_DAY, textInTokens = 500, defaultImageTokens = 2000, outTokens = 300)
    )

    /** The assumptions in one sentence, for the UI. */
    val ASSUMPTIONS =
        "Assumes $PHOTOS_PER_DAY meal photos, $COACH_PER_DAY coach messages and $LABELS_PER_DAY label a day " +
            "(~1.9K in / 0.7K out tokens per photo, ~4K / 0.5K per coach message, ~2.5K / 0.3K per label), " +
            "no caching discount. Program import, body check-ins and planning are occasional and not included."

    data class Line(
        val task: Task,
        val model: String,
        val spec: ModelSpec?,
        /** Null when the model's price is unknown (a custom or overridden model). */
        val monthlyUsd: Double?
    )

    data class Estimate(
        val lines: List<Line>,
        /** Null when any line's price is unknown: an honest total needs every price. */
        val totalUsd: Double?,
        val unpricedModels: List<String>
    )

    fun monthly(
        provider: ProviderSpec,
        tier: CostTier,
        overrides: Map<String, String>,
        date: LocalDate
    ): Estimate {
        val lines = usage.map { u ->
            val pick = runCatching {
                ModelRouter.pick(provider, tier, u.task, overrides, needsVision = u.defaultImageTokens > 0)
            }.getOrNull()
            val spec = pick?.spec
            val cost = if (spec == null) null else {
                val (inPerM, outPerM) = spec.priceOn(date)
                val imageTokens = if (u.defaultImageTokens == 0) 0
                else spec.imageTokens ?: provider.imageTokensPerPhoto ?: u.defaultImageTokens
                val perCall = (u.textInTokens + imageTokens) / 1_000_000.0 * inPerM +
                    u.outTokens / 1_000_000.0 * outPerM
                perCall * u.perDay * DAYS_PER_MONTH
            }
            Line(u.task, pick?.model ?: "-", spec, cost)
        }
        val unpriced = lines.filter { it.monthlyUsd == null }.map { it.model }.distinct()
        val total = if (unpriced.isEmpty()) lines.sumOf { it.monthlyUsd ?: 0.0 } else null
        return Estimate(lines, total, unpriced)
    }

    fun formatUsd(usd: Double): String = when {
        usd < 0.01 -> "< \$0.01"
        else -> "\$" + String.format(Locale.US, "%.2f", usd)
    }

    /** "$0.10 / $0.50 per M tokens" for a model on [date]. */
    fun priceLabel(spec: ModelSpec, date: LocalDate): String {
        val (i, o) = spec.priceOn(date)
        return "\$${trim(i)} in · \$${trim(o)} out per 1M tokens"
    }

    private fun trim(v: Double): String {
        val s = String.format(Locale.US, "%.3f", v).trimEnd('0')
        return if (s.endsWith('.')) s + "00" else if (s.substringAfter('.').length == 1) s + "0" else s
    }
}

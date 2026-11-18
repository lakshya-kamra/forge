package com.laksh.forge.data.ai

import com.laksh.forge.data.remote.CostTier
import com.laksh.forge.data.remote.Task
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The model chosen for one call. [spec] is null for a model Forge has no registry entry for. */
data class ModelPick(
    val model: String,
    val spec: ModelSpec?,
    val overridden: Boolean,
    /** Set when the router had to deviate from what the tier or override asked for. */
    val note: String? = null
)

/**
 * Per-task model overrides, stored in settings.modelOverridesJson as a flat object:
 * `{"provider": "openai", "FOOD_PHOTO": "gpt-6.1-terra", "DEFAULT": "..."}`.
 *
 * The "provider" marker scopes the overrides to the provider they were picked for, so
 * switching keys from OpenAI to Groq can never send an OpenAI model id to Groq.
 */
object ModelOverrides {
    const val PROVIDER_KEY = "provider"
    /** Applies to every task without its own override (the only way to pick a Custom model for all jobs). */
    const val DEFAULT_KEY = "DEFAULT"

    fun parse(raw: String, providerId: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val obj = runCatching { AiJson.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        val owner = (obj[PROVIDER_KEY] as? JsonPrimitive)?.contentOrNull
        if (owner != null && owner != providerId) return emptyMap()
        return obj.entries
            .filter { it.key != PROVIDER_KEY }
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it } }
            .toMap()
    }

    fun serialize(providerId: String, overrides: Map<String, String>): String {
        val clean = overrides.filterValues { it.isNotBlank() }
        if (clean.isEmpty()) return ""
        val obj: JsonObject = buildJsonObject {
            put(PROVIDER_KEY, providerId)
            clean.toSortedMap().forEach { (k, v) -> put(k, v.trim()) }
        }
        return obj.toString()
    }
}

/**
 * Chooses a model per task for a provider: user override first, then the tier map from
 * providers.json, respecting vision. Pure, so the whole routing table is unit-testable.
 */
object ModelRouter {

    /** Tier order to search. Each tier falls back toward cheaper models before dearer ones. */
    private fun searchOrder(tier: ModelTier): List<ModelTier> = when (tier) {
        ModelTier.ECONOMY -> listOf(ModelTier.ECONOMY, ModelTier.BALANCED, ModelTier.MAX)
        ModelTier.BALANCED -> listOf(ModelTier.BALANCED, ModelTier.ECONOMY, ModelTier.MAX)
        ModelTier.MAX -> listOf(ModelTier.MAX, ModelTier.BALANCED, ModelTier.ECONOMY)
    }

    fun tierOf(cost: CostTier): ModelTier = when (cost) {
        CostTier.ECONOMY -> ModelTier.ECONOMY
        CostTier.BALANCED -> ModelTier.BALANCED
        CostTier.MAXIMUM -> ModelTier.MAX
    }

    /**
     * Transcription (labels) and the key test gain nothing from a bigger model, so they always
     * use the economy pick, matching Forge's original Anthropic policy (Haiku at every tier).
     */
    fun effectiveTier(task: Task, tier: CostTier): ModelTier =
        if (task == Task.LABEL || task == Task.PING) ModelTier.ECONOMY else tierOf(tier)

    /**
     * @param needsVision true when this call carries an image.
     * @throws AiException when the provider has nothing that can do the job.
     */
    fun pick(
        provider: ProviderSpec,
        tier: CostTier,
        task: Task,
        overrides: Map<String, String>,
        needsVision: Boolean
    ): ModelPick {
        val overrideId = overrides[task.name] ?: overrides[ModelOverrides.DEFAULT_KEY]
        var note: String? = null
        if (overrideId != null) {
            val spec = provider.model(overrideId)
            if (!needsVision || spec == null || spec.vision) {
                // Unknown ids (from the live model list or typed by hand) are trusted: the
                // provider will say clearly if one cannot take images.
                return ModelPick(overrideId, spec, overridden = true)
            }
            note = "$overrideId can't read images, so ${task.label.lowercase()} with a photo use the tier's vision model."
        }

        if (provider.models.isEmpty()) {
            throw AiException(
                "Choose a model for ${provider.shortName} in Settings → AI → Advanced " +
                    "(the default for every job is enough)."
            )
        }

        val job = task.job
        val order = searchOrder(effectiveTier(task, tier))
        fun eligible(m: ModelSpec) = !needsVision || m.vision

        // Prefer a model the research assigns to this job, in tier order...
        for (t in order) {
            provider.models.firstOrNull { it.tier == t && job in it.jobs && eligible(it) }
                ?.let { return ModelPick(it.id, it, overridden = false, note = note) }
        }
        // ...then any capable model, e.g. a scanned PDF on a provider whose PDF models are text-only.
        for (t in order) {
            provider.models.firstOrNull { it.tier == t && eligible(it) }
                ?.let { return ModelPick(it.id, it, overridden = false, note = note) }
        }

        throw AiException(
            if (needsVision) "${provider.shortName} has no model in Forge's list that can read images, " +
                "so ${task.label.lowercase()} can't run on it. Use another provider for photo features."
            else "${provider.shortName} has no model in Forge's list for ${task.label.lowercase()}."
        )
    }
}

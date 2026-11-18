package com.laksh.forge.data.ai

import com.laksh.forge.data.remote.Task
import java.time.LocalDate

/** Wire protocol a provider speaks. Everything except Anthropic goes through the OpenAI shape. */
enum class ProviderKind { ANTHROPIC, OPENAI_COMPATIBLE }

/** How a provider is asked for JSON output. */
enum class JsonMode {
    /** Anthropic `output_config.format` json_schema; the answer arrives as a JSON text block. */
    ANTHROPIC_SCHEMA,
    /** OpenAI-style `response_format` json_schema (strictness decided per provider/model). */
    JSON_SCHEMA,
    /** Only `response_format` json_object exists, so the schema travels in the prompt. */
    JSON_OBJECT
}

/** How an image part is written into an OpenAI-compatible user message. */
enum class ImageStyle {
    /** `{"type":"image","source":{"type":"base64",...}}` (Anthropic Messages API). */
    ANTHROPIC_BASE64,
    /** `{"type":"image_url","image_url":{"url":"data:..."}}` (OpenAI and most compatibles). */
    OPENAI_OBJECT,
    /** `{"type":"image_url","image_url":"data:..."}` (the plain-string form Mistral documents). */
    PLAIN_STRING
}

/** The price/quality tiers in providers.json ("economy", "balanced", "max"). */
enum class ModelTier { ECONOMY, BALANCED, MAX }

/** The job names providers.json assigns models to. */
enum class Job { PHOTO, LABEL, COACH, PDF }

/** How much a provider's terms let it use what Forge sends. Drives the warning colour in Settings. */
enum class TrainingRisk {
    /** Documented as not used for training unless the user opts in. */
    NOT_BY_DEFAULT,
    /** Paid use is not trained on, but a free tier is. */
    FREE_TIER_TRAINS,
    /** Terms list training as a purpose or say nothing clear; assume it may happen. */
    MAY_TRAIN,
    /** A router: the upstream provider's policy applies. */
    DEPENDS_ON_ROUTE,
    /** Forge's verified research does not cover it. */
    UNKNOWN
}

data class DataPolicy(
    val summary: String,
    val risk: TrainingRisk,
    /** Shown prominently when set: the thing the user would most want to know before sending photos. */
    val warning: String? = null
)

/** A dated price change announced by the provider (e.g. gemini-4-carbon on 2026-12-01). */
data class PriceChange(val effective: LocalDate, val inPerM: Double, val outPerM: Double)

data class ModelSpec(
    val id: String,
    val jobs: Set<Job>,
    val tier: ModelTier,
    val vision: Boolean,
    /** Whether the provider documents JSON-schema structured output for this model. */
    val jsonSchema: Boolean,
    /**
     * True only when the schema is documented as enforced (constrained decoding). When false
     * Forge also puts the schema and an example in the prompt and parses tolerantly.
     */
    val schemaEnforced: Boolean,
    /** USD per 1M input / output tokens (standard tier, short context, from providers.json). */
    val inPerM: Double,
    val outPerM: Double,
    /** Friendly name where one is established; otherwise the UI shows [id]. */
    val label: String? = null,
    /** Limit on images per request where the provider states one. */
    val maxImages: Int? = null,
    /** Input tokens one photo costs on this model, where the provider states it. */
    val imageTokens: Int? = null,
    /** True when the provider offers a free tier for this model (relevant to data use). */
    val freeTier: Boolean = false,
    /** Preview models can be withdrawn at short notice. */
    val preview: Boolean = false,
    /** One line worth showing next to the model (price caveats, preview status). */
    val note: String? = null,
    val priceChanges: List<PriceChange> = emptyList()
) {
    val displayName: String get() = label ?: id

    /** Price in force on [date]: the latest announced change on or before it, else the base price. */
    fun priceOn(date: LocalDate): Pair<Double, Double> {
        val change = priceChanges.filter { !it.effective.isAfter(date) }.maxByOrNull { it.effective }
        return if (change != null) change.inPerM to change.outPerM else inPerM to outPerM
    }
}

/**
 * One key format. [confident] is false when the format is only "commonly observed" (per the
 * research notes) or collides with another provider's format, in which case Forge confirms
 * the provider with a no-cost GET /models probe before saving.
 */
data class KeyPattern(val regex: Regex, val confident: Boolean)

data class ProviderSpec(
    val id: String,
    /** Full name for pickers and headings. */
    val name: String,
    /** Short name for chips and "Provider · model" descriptions. */
    val shortName: String,
    val kind: ProviderKind,
    val keyPatterns: List<KeyPattern>,
    /** Short, honest hint about key formats, shown under the key field. */
    val keyHint: String,
    val baseUrl: String,
    val chatPath: String,
    val modelsPath: String,
    val jsonMode: JsonMode,
    /** Whether to send `strict: true` with json_schema (only where the provider documents it). */
    val sendStrict: Boolean,
    val imageStyle: ImageStyle,
    /** Image MIME types the provider accepts; null when the research lists no restriction we hit. */
    val imageMediaTypes: Set<String>? = null,
    /** Name of the max-output-tokens body field, or null to leave the provider default. */
    val maxTokensParam: String? = null,
    /** reasoning_effort for quick transcription jobs (LABEL, PING) on registry models; null = don't send. */
    val fastEffort: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** OpenRouter: route only to endpoints that support every parameter sent (structured outputs). */
    val requireParameters: Boolean = false,
    /** Prefix some model lists put in front of ids that the chat endpoint does not want. */
    val modelIdPrefixToStrip: String? = null,
    /** Input tokens a typical photo costs, where the provider documents a fixed figure. */
    val imageTokensPerPhoto: Int? = null,
    val dataPolicy: DataPolicy,
    val models: List<ModelSpec>,
    /** Whether a custom base URL (Advanced) may replace [baseUrl]. */
    val allowsBaseUrlOverride: Boolean = true
) {
    val isCustom: Boolean get() = id == ProviderRegistry.CUSTOM_ID
    val hasVision: Boolean get() = models.any { it.vision }
    fun model(id: String): ModelSpec? = models.firstOrNull { it.id == id }
}

/** What a [Task] needs from a model. Kept next to the registry so routing and costing agree. */
val Task.job: Job
    get() = when (this) {
        Task.FOOD_PHOTO, Task.BODY -> Job.PHOTO
        Task.LABEL -> Job.LABEL
        Task.PROGRAM -> Job.PDF
        Task.COACH, Task.PLAN, Task.PING -> Job.COACH
    }

/** Tasks whose normal call carries an image. PROGRAM only does for scanned PDFs. */
val Task.visionByDefault: Boolean
    get() = this == Task.FOOD_PHOTO || this == Task.LABEL || this == Task.BODY

/** Human label for a task, used by Settings and error messages. */
val Task.label: String
    get() = when (this) {
        Task.FOOD_PHOTO -> "Meal photos"
        Task.LABEL -> "Nutrition labels"
        Task.PROGRAM -> "Program import"
        Task.COACH -> "Coach"
        Task.BODY -> "Body check-in"
        Task.PLAN -> "Planning"
        Task.PING -> "Key test"
    }

package com.laksh.forge.data.ai

import java.time.LocalDate

/**
 * Every provider Forge can talk to. The OpenAI-compatible entries are transcribed from
 * docs/research/providers.json (verified 2026-11-17): key patterns, base URLs, paths, image
 * and structured-output behaviour, data policy, and the per-job tiered models with prices.
 * Anthropic's entry comes from Anthropic's own API facts (Nov 2026).
 *
 * The order of [all] is the order the provider picker shows.
 */
object ProviderRegistry {

    const val CUSTOM_ID = "custom"

    private val ALL_JOBS = setOf(Job.PHOTO, Job.LABEL, Job.COACH, Job.PDF)

    private fun p(regex: String, confident: Boolean = true) = KeyPattern(Regex(regex), confident)

    val anthropic = ProviderSpec(
        id = "anthropic",
        name = "Anthropic (Claude)",
        shortName = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        keyPatterns = listOf(p("^sk-ant-[A-Za-z0-9_-]+$")),
        keyHint = "Anthropic keys start with sk-ant-.",
        baseUrl = "https://api.anthropic.com",
        chatPath = "/v1/messages",
        modelsPath = "/v1/models",
        jsonMode = JsonMode.ANTHROPIC_SCHEMA,
        sendStrict = false,
        imageStyle = ImageStyle.ANTHROPIC_BASE64,
        dataPolicy = DataPolicy(
            summary = "Forge's verified research does not cover Anthropic's API data terms. " +
                "Check Anthropic's commercial terms and privacy policy for retention and training.",
            risk = TrainingRisk.UNKNOWN
        ),
        models = listOf(
            ModelSpec(
                id = "claude-haiku-5-5", label = "Haiku 5.5", jobs = ALL_JOBS, tier = ModelTier.ECONOMY,
                vision = true, jsonSchema = true, schemaEnforced = true, inPerM = 0.10, outPerM = 0.50,
                note = "This price applies up to 100K prompt tokens."
            ),
            ModelSpec(
                id = "claude-sonnet-5-5", label = "Sonnet 5.5", jobs = ALL_JOBS, tier = ModelTier.BALANCED,
                vision = true, jsonSchema = true, schemaEnforced = true, inPerM = 2.00, outPerM = 10.00
            ),
            ModelSpec(
                id = "claude-opus-5-5", label = "Opus 5.5", jobs = ALL_JOBS, tier = ModelTier.MAX,
                vision = true, jsonSchema = true, schemaEnforced = true, inPerM = 4.00, outPerM = 20.00
            )
        ),
        // The native Messages API has no OpenAI-style base URL to swap.
        allowsBaseUrlOverride = false
    )

    val openai = ProviderSpec(
        id = "openai",
        name = "OpenAI",
        shortName = "OpenAI",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        keyPatterns = listOf(
            p("^sk-(proj|svcacct|admin)-[A-Za-z0-9_-]+$"),
            p("^sk-[A-Za-z0-9_-]*T3BlbkFJ[A-Za-z0-9_-]*$")
        ),
        keyHint = "OpenAI project keys start with sk-proj-. Admin keys (sk-admin-) can't be used for chat.",
        baseUrl = "https://api.openai.com/v1",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        jsonMode = JsonMode.JSON_SCHEMA,
        sendStrict = true,
        imageStyle = ImageStyle.OPENAI_OBJECT,
        imageMediaTypes = setOf("image/png", "image/jpeg", "image/webp", "image/gif"),
        maxTokensParam = "max_completion_tokens",
        // Every listed model accepts "low"; gpt-6.2-sol/astra reject "none", so "low" is the safe floor.
        fastEffort = "low",
        dataPolicy = DataPolicy(
            summary = "Not used for training by default (opt-in only). Abuse-monitoring logs are kept up to " +
                "30 days. There is no free API tier, so this applies to all API use. Zero Data Retention " +
                "needs OpenAI's approval.",
            risk = TrainingRisk.NOT_BY_DEFAULT
        ),
        models = listOf(
            ModelSpec(
                id = "gpt-6-luna", jobs = ALL_JOBS, tier = ModelTier.ECONOMY, vision = true,
                jsonSchema = true, schemaEnforced = true, inPerM = 0.10, outPerM = 0.50
            ),
            ModelSpec(
                id = "gpt-6.1-terra", jobs = ALL_JOBS, tier = ModelTier.BALANCED, vision = true,
                jsonSchema = true, schemaEnforced = true, inPerM = 1.00, outPerM = 5.00
            ),
            ModelSpec(
                id = "gpt-6.2-sol", jobs = setOf(Job.COACH, Job.PDF), tier = ModelTier.MAX, vision = true,
                jsonSchema = true, schemaEnforced = true, inPerM = 2.00, outPerM = 10.00
            ),
            ModelSpec(
                id = "gpt-6.2-astra", jobs = ALL_JOBS, tier = ModelTier.MAX, vision = true,
                jsonSchema = true, schemaEnforced = true, inPerM = 8.00, outPerM = 40.00
            )
        )
    )

    val gemini = ProviderSpec(
        id = "gemini",
        name = "Google Gemini (AI Studio key)",
        shortName = "Gemini",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        keyPatterns = listOf(
            p("^AIza[0-9A-Za-z_-]{35}$"),
            // Community-reported only; whether it works with Bearer on the compat endpoint is unverified.
            p("^AQ\\.[A-Za-z0-9._-]+$", confident = false)
        ),
        keyHint = "Standard Google keys start with AIza. Newer auth keys (AQ.) are community-reported to " +
            "sometimes fail on this endpoint; if Test fails, create a standard key with restrictions.",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
        chatPath = "chat/completions",
        modelsPath = "models",
        jsonMode = JsonMode.JSON_SCHEMA,
        // The compat doc does not document the strict flag.
        sendStrict = false,
        imageStyle = ImageStyle.OPENAI_OBJECT,
        fastEffort = "low",
        modelIdPrefixToStrip = "models/",
        dataPolicy = DataPolicy(
            summary = "Paid tier: not used to improve Google products. Free tier: content is used to improve " +
                "Google products. The invite-only Contributor paid tier may also be used. " +
                "gemini-4-carbon has no free tier.",
            risk = TrainingRisk.FREE_TIER_TRAINS,
            warning = "On the free tier (no billing on the key's project), Google uses what you send, " +
                "including meal and body photos, to improve its products. Enable billing to stop that."
        ),
        models = listOf(
            ModelSpec(
                id = "gemini-3.1-flash-lite", jobs = setOf(Job.LABEL), tier = ModelTier.ECONOMY,
                vision = true, jsonSchema = true, schemaEnforced = false, inPerM = 0.25, outPerM = 1.50,
                freeTier = true
            ),
            ModelSpec(
                id = "gemini-3.5-flash-lite", jobs = ALL_JOBS, tier = ModelTier.ECONOMY, vision = true,
                jsonSchema = true, schemaEnforced = false, inPerM = 0.30, outPerM = 2.50, freeTier = true
            ),
            ModelSpec(
                id = "gemini-4-hydrogen", jobs = ALL_JOBS, tier = ModelTier.BALANCED, vision = true,
                jsonSchema = true, schemaEnforced = false, inPerM = 1.50, outPerM = 7.50, freeTier = true
            ),
            ModelSpec(
                id = "gemini-4-carbon", jobs = setOf(Job.PHOTO, Job.COACH, Job.PDF), tier = ModelTier.MAX,
                vision = true, jsonSchema = true, schemaEnforced = false, inPerM = 2.00, outPerM = 10.00,
                note = "Paid only. From 2026-12-01 it costs \$4.00/M input and \$20.00/M output.",
                priceChanges = listOf(PriceChange(LocalDate.of(2026, 12, 1), 4.00, 20.00))
            )
        )
    )

    val openrouter = ProviderSpec(
        id = "openrouter",
        name = "OpenRouter",
        shortName = "OpenRouter",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        keyPatterns = listOf(p("^sk-or-v1-[0-9a-f]{64}$")),
        keyHint = "OpenRouter keys start with sk-or-v1-.",
        baseUrl = "https://openrouter.ai/api/v1",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        jsonMode = JsonMode.JSON_SCHEMA,
        // Some upstreams guarantee the schema, others only approximate it, so the prompt carries it too.
        sendStrict = true,
        imageStyle = ImageStyle.OPENAI_OBJECT,
        extraHeaders = mapOf("X-OpenRouter-Title" to "Forge"),
        requireParameters = true,
        dataPolicy = DataPolicy(
            summary = "Each upstream provider has its own data policy, which OpenRouter shows per endpoint. " +
                "In OpenRouter's privacy settings you can exclude providers that train on prompts (paid and " +
                "free models have separate settings) or require zero data retention.",
            risk = TrainingRisk.DEPENDS_ON_ROUTE,
            warning = "Before sending photos, open OpenRouter's privacy settings and exclude providers that " +
                "train on prompts."
        ),
        models = listOf(
            ModelSpec(
                id = "openai/gpt-6-luna", jobs = ALL_JOBS, tier = ModelTier.ECONOMY, vision = true,
                jsonSchema = true, schemaEnforced = false, inPerM = 0.10, outPerM = 0.50
            ),
            ModelSpec(
                id = "google/gemini-3.5-flash-lite", jobs = setOf(Job.PHOTO, Job.LABEL, Job.PDF),
                tier = ModelTier.ECONOMY, vision = true, jsonSchema = true, schemaEnforced = false,
                inPerM = 0.30, outPerM = 2.50
            ),
            ModelSpec(
                id = "google/gemini-4-hydrogen", jobs = ALL_JOBS, tier = ModelTier.BALANCED, vision = true,
                jsonSchema = true, schemaEnforced = false, inPerM = 1.50, outPerM = 7.50
            ),
            ModelSpec(
                id = "openai/gpt-6.1-terra", jobs = ALL_JOBS, tier = ModelTier.BALANCED, vision = true,
                jsonSchema = true, schemaEnforced = false, inPerM = 1.00, outPerM = 5.00
            ),
            ModelSpec(
                id = "anthropic/claude-opus-5.5", jobs = setOf(Job.PHOTO, Job.COACH, Job.PDF),
                tier = ModelTier.MAX, vision = true, jsonSchema = true, schemaEnforced = false,
                inPerM = 4.00, outPerM = 20.00
            ),
            ModelSpec(
                id = "openai/gpt-6.2-astra", jobs = ALL_JOBS, tier = ModelTier.MAX, vision = true,
                jsonSchema = true, schemaEnforced = false, inPerM = 8.00, outPerM = 40.00
            )
        )
    )

    val groq = ProviderSpec(
        id = "groq",
        name = "Groq",
        shortName = "Groq",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        keyPatterns = listOf(p("^gsk_[A-Za-z0-9]+$")),
        keyHint = "Groq keys start with gsk_.",
        baseUrl = "https://api.groq.com/openai/v1",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        jsonMode = JsonMode.JSON_SCHEMA,
        sendStrict = true,
        imageStyle = ImageStyle.OPENAI_OBJECT,
        dataPolicy = DataPolicy(
            summary = "Inputs and outputs are not retained by default, but may be logged up to 30 days for " +
                "reliability or abuse investigation (opt out in Data Controls). Zero data retention is " +
                "self-serve. Groq's data page does not say whether data is used for training.",
            risk = TrainingRisk.UNKNOWN
        ),
        models = listOf(
            ModelSpec(
                id = "openai/gpt-oss-20b", jobs = setOf(Job.COACH, Job.PDF), tier = ModelTier.ECONOMY,
                vision = false, jsonSchema = true, schemaEnforced = true, inPerM = 0.075, outPerM = 0.30
            ),
            ModelSpec(
                id = "openai/gpt-oss-120b", jobs = setOf(Job.COACH, Job.PDF), tier = ModelTier.BALANCED,
                vision = false, jsonSchema = true, schemaEnforced = true, inPerM = 0.15, outPerM = 0.60
            ),
            ModelSpec(
                id = "qwen/qwen3.8-27b", jobs = ALL_JOBS, tier = ModelTier.MAX, vision = true,
                jsonSchema = true, schemaEnforced = true, inPerM = 0.80, outPerM = 4.00,
                maxImages = 3, imageTokens = 2048, preview = true,
                note = "Preview model: Groq may discontinue it at short notice. Groq's only self-serve " +
                    "vision model, so photos and labels use it at every tier."
            )
        )
    )

    val xai = ProviderSpec(
        id = "xai",
        name = "xAI (Grok)",
        shortName = "xAI",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        keyPatterns = listOf(p("^xai-[A-Za-z0-9_-]{10,}$")),
        keyHint = "xAI keys start with xai-.",
        baseUrl = "https://api.x.ai/v1",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        jsonMode = JsonMode.JSON_SCHEMA,
        sendStrict = true,
        imageStyle = ImageStyle.OPENAI_OBJECT,
        imageMediaTypes = setOf("image/jpeg", "image/jpg", "image/png"),
        dataPolicy = DataPolicy(
            summary = "API requests and responses are kept 30 days for abuse auditing, then deleted. xAI does " +
                "not train on them without permission. Zero-data-retention teams skip the 30-day retention.",
            risk = TrainingRisk.NOT_BY_DEFAULT
        ),
        models = listOf(
            ModelSpec(
                id = "grok-4.20-0309-non-reasoning", jobs = setOf(Job.PHOTO, Job.LABEL),
                tier = ModelTier.ECONOMY, vision = true, jsonSchema = true, schemaEnforced = true,
                inPerM = 1.25, outPerM = 2.50
            ),
            ModelSpec(
                id = "grok-4.3", jobs = ALL_JOBS, tier = ModelTier.BALANCED, vision = true,
                jsonSchema = true, schemaEnforced = true, inPerM = 1.25, outPerM = 2.50
            ),
            ModelSpec(
                id = "grok-4.7", jobs = ALL_JOBS, tier = ModelTier.MAX, vision = true,
                jsonSchema = true, schemaEnforced = true, inPerM = 2.00, outPerM = 6.00
            )
        )
    )

    val mistral = ProviderSpec(
        id = "mistral",
        name = "Mistral AI",
        shortName = "Mistral",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        keyPatterns = emptyList(),
        keyHint = "Mistral keys have no documented prefix, so Forge checks the key against Mistral's API.",
        baseUrl = "https://api.mistral.ai/v1",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        // The raw json_schema shape and strict flag are unverified; the request falls back on a 400.
        jsonMode = JsonMode.JSON_SCHEMA,
        sendStrict = false,
        imageStyle = ImageStyle.PLAIN_STRING,
        maxTokensParam = "max_tokens",
        dataPolicy = DataPolicy(
            summary = "Free mode: Mistral may use inputs and outputs to train its models, with an opt-out. " +
                "Pay-as-you-go: you keep control and can opt out at any time. Zero Data Retention is available.",
            risk = TrainingRisk.FREE_TIER_TRAINS,
            warning = "On Mistral's free mode, what you send (including photos) may be used for training " +
                "unless you opt out."
        ),
        models = listOf(
            ModelSpec(
                id = "ministral-8b-2512", jobs = setOf(Job.PHOTO, Job.LABEL), tier = ModelTier.ECONOMY,
                vision = true, jsonSchema = true, schemaEnforced = false, inPerM = 0.15, outPerM = 0.15
            ),
            ModelSpec(
                id = "mistral-small-2603", jobs = setOf(Job.COACH, Job.PDF), tier = ModelTier.ECONOMY,
                vision = false, jsonSchema = true, schemaEnforced = false, inPerM = 0.15, outPerM = 0.60
            ),
            ModelSpec(
                id = "ministral-14b-2512", jobs = setOf(Job.PHOTO, Job.LABEL), tier = ModelTier.BALANCED,
                vision = true, jsonSchema = true, schemaEnforced = false, inPerM = 0.20, outPerM = 0.20
            ),
            ModelSpec(
                id = "mistral-large-4-0", jobs = ALL_JOBS, tier = ModelTier.MAX, vision = true,
                jsonSchema = true, schemaEnforced = false, inPerM = 0.70, outPerM = 2.10
            )
        )
    )

    val deepseek = ProviderSpec(
        id = "deepseek",
        name = "DeepSeek",
        shortName = "DeepSeek",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        // Unverified and overlaps OpenAI's legacy sk- shape, so it is always confirmed by a probe.
        keyPatterns = listOf(p("^sk-[0-9a-f]{32}$", confident = false)),
        keyHint = "DeepSeek publishes no key format; sk- keys are confirmed against DeepSeek's API.",
        baseUrl = "https://api.deepseek.com",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        // No json_schema: json_object only, and the prompt must say "json" and show an example.
        jsonMode = JsonMode.JSON_OBJECT,
        sendStrict = false,
        imageStyle = ImageStyle.OPENAI_OBJECT,
        imageMediaTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp"),
        maxTokensParam = "max_tokens",
        // Thinking is on by default; "none" is documented for fast label OCR.
        fastEffort = "none",
        dataPolicy = DataPolicy(
            summary = "Not clearly stated for API traffic. DeepSeek's privacy policy says personal data is " +
                "stored and processed in the People's Republic of China, lists model training as a purpose, " +
                "and grants a right to opt out. No zero-retention offer was found.",
            risk = TrainingRisk.MAY_TRAIN,
            warning = "Treat anything you send, including meal photos, as possibly trained on and retained " +
                "while your account exists."
        ),
        models = listOf(
            ModelSpec(
                id = "deepseek-flash", jobs = ALL_JOBS, tier = ModelTier.ECONOMY, vision = true,
                jsonSchema = false, schemaEnforced = false, inPerM = 0.30, outPerM = 1.20,
                note = "Peak price shown; off-peak is half. Peak is 06:30-09:30 and 11:30-15:30 IST on weekdays."
            ),
            ModelSpec(
                id = "deepseek-v4-pro", jobs = setOf(Job.COACH, Job.PDF), tier = ModelTier.MAX,
                vision = false, jsonSchema = false, schemaEnforced = false, inPerM = 1.32, outPerM = 3.96,
                note = "Peak price shown; off-peak is \$0.66/\$1.98."
            )
        )
    )

    val together = ProviderSpec(
        id = "together",
        name = "Together AI",
        shortName = "Together",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        keyPatterns = emptyList(),
        keyHint = "Together keys have no documented format, so Forge checks the key against Together's API.",
        baseUrl = "https://api.together.ai/v1",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        // No strict flag documented; Together advises also putting the schema in the prompt.
        jsonMode = JsonMode.JSON_SCHEMA,
        sendStrict = false,
        imageStyle = ImageStyle.OPENAI_OBJECT,
        maxTokensParam = "max_tokens",
        imageTokensPerPhoto = 6404,
        dataPolicy = DataPolicy(
            summary = "Training on your data is opt-in and off by default (Organization Settings > Privacy). " +
                "An org admin can turn storage off for zero data retention, which also disables passthrough models.",
            risk = TrainingRisk.NOT_BY_DEFAULT
        ),
        models = listOf(
            ModelSpec(
                id = "Qwen/Qwen3.5-9B", jobs = setOf(Job.PHOTO, Job.LABEL), tier = ModelTier.ECONOMY,
                vision = true, jsonSchema = true, schemaEnforced = false, inPerM = 0.17, outPerM = 0.25
            ),
            ModelSpec(
                id = "Qwen/Qwen3.8-Flash", jobs = setOf(Job.COACH, Job.PDF), tier = ModelTier.ECONOMY,
                vision = false, jsonSchema = true, schemaEnforced = false, inPerM = 0.15, outPerM = 0.47
            ),
            ModelSpec(
                id = "thinkingmachines/Inkling-2-Small", jobs = setOf(Job.PHOTO, Job.LABEL),
                tier = ModelTier.BALANCED, vision = true, jsonSchema = true, schemaEnforced = false,
                inPerM = 0.30, outPerM = 1.00
            ),
            ModelSpec(
                id = "deepseek-ai/DeepSeek-V4.1-Flash", jobs = setOf(Job.COACH, Job.PDF),
                tier = ModelTier.BALANCED, vision = false, jsonSchema = true, schemaEnforced = false,
                inPerM = 0.30, outPerM = 1.20
            ),
            ModelSpec(
                id = "Qwen/Qwen4-Max", jobs = ALL_JOBS, tier = ModelTier.MAX, vision = true,
                jsonSchema = true, schemaEnforced = false, inPerM = 1.425, outPerM = 4.275
            ),
            ModelSpec(
                id = "moonshotai/Kimi-K3.1-Pro", jobs = setOf(Job.PHOTO, Job.COACH, Job.PDF),
                tier = ModelTier.MAX, vision = true, jsonSchema = true, schemaEnforced = false,
                inPerM = 2.00, outPerM = 10.00
            )
        )
    )

    /**
     * Any other OpenAI-compatible endpoint (self-hosted or a provider not listed). Nothing is
     * known about it, so it has no models (the user picks one in Advanced), asks for json_schema
     * and falls back on a 400, and its data policy is whatever the endpoint's owner says.
     */
    val custom = ProviderSpec(
        id = CUSTOM_ID,
        name = "Custom (OpenAI-compatible)",
        shortName = "Custom",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        keyPatterns = emptyList(),
        keyHint = "Any key your endpoint accepts as a Bearer token. The base URL must use HTTPS.",
        baseUrl = "",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        jsonMode = JsonMode.JSON_SCHEMA,
        sendStrict = false,
        imageStyle = ImageStyle.OPENAI_OBJECT,
        maxTokensParam = "max_tokens",
        dataPolicy = DataPolicy(
            summary = "Depends entirely on who runs this endpoint. Forge knows nothing about its retention " +
                "or training terms.",
            risk = TrainingRisk.UNKNOWN
        ),
        models = emptyList()
    )

    val all: List<ProviderSpec> = listOf(
        anthropic, openai, gemini, openrouter, groq, xai, mistral, deepseek, together, custom
    )

    /** Providers with no documented key format; an unrecognised key is probed against these. */
    val probeCandidates: List<ProviderSpec> get() = listOf(mistral, together)

    fun byId(id: String): ProviderSpec? = all.firstOrNull { it.id == id }
}

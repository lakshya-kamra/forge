package com.laksh.forge.data.ai

/** Result of looking at a pasted key, before any network call. */
sealed interface KeyDetection {
    /** Nothing pasted yet. */
    data object Empty : KeyDetection

    /**
     * The key's shape points at one provider. When [confident] is false the shape is only
     * "commonly observed" (or shared with another provider), so Forge confirms it with a
     * GET /models probe before saving.
     */
    data class Match(
        val provider: ProviderSpec,
        val confident: Boolean,
        val note: String? = null
    ) : KeyDetection

    /** The shape fits several providers; probe them (in order) or let the user pick. */
    data class Ambiguous(val candidates: List<ProviderSpec>) : KeyDetection

    /** No known shape. Providers without a documented format are probed; otherwise the user picks. */
    data class Unknown(val probeCandidates: List<ProviderSpec>) : KeyDetection

    /** A key that can never work for chat, with the reason. */
    data class Rejected(val provider: ProviderSpec?, val reason: String) : KeyDetection
}

/**
 * Works out which provider a pasted key belongs to from its shape alone (pure, no I/O).
 *
 * Order matters (providers.json notes): OpenRouter's sk-or-v1- and Anthropic's sk-ant- are
 * tested before any generic sk- rule, because DeepSeek keys and legacy OpenAI keys both start
 * with sk- too.
 *
 * Prefixes that come from third-party sources (gsk_, xai-, AIza) are still treated as
 * confident: no other provider in the registry shares them, so a wrong guess is impossible
 * within Forge's list, and Test confirms the key works. Only shapes that collide (DeepSeek's
 * sk-hex) or are community-reported (Gemini AQ.) need a probe.
 */
object KeyDetector {

    /**
     * Normalise what people actually paste: surrounding quotes, a copied "Bearer " prefix,
     * an env-file "NAME=" prefix, and line breaks from wrapped text. Keys never contain
     * whitespace, so all of it goes.
     */
    // Notes apps and word processors turn pasted quotes into curly ones.
    private val QUOTES = charArrayOf('"', '\'', '`', '“', '”', '‘', '’')

    fun clean(raw: String): String {
        var k = raw.trim()
        // OPENAI_API_KEY=sk-... copied from a .env file
        val envMatch = Regex("^[A-Z][A-Z0-9_]*\\s*=\\s*(.+)$").find(k)
        if (envMatch != null) k = envMatch.groupValues[1].trim()
        k = k.trim(*QUOTES, ' ')
        if (k.startsWith("Bearer ", ignoreCase = true)) k = k.substring(7)
        return k.filter { !it.isWhitespace() }.trim(*QUOTES)
    }

    fun detect(raw: String): KeyDetection {
        val key = clean(raw)
        if (key.isEmpty()) return KeyDetection.Empty
        val r = ProviderRegistry
        // Keys are printable ASCII; anything else (a "…" from a shortened dashboard copy, a
        // stray smart quote) can't go in an HTTP header and would never authenticate.
        if (key.any { it.code !in 0x21..0x7E }) {
            return KeyDetection.Rejected(
                null,
                "That key contains characters a real key never has (an ellipsis, smart quotes or " +
                    "accented letters). Copy the full key again from the provider's dashboard."
            )
        }

        if (key.startsWith("sk-ant-")) return KeyDetection.Match(r.anthropic, confident = true)

        if (key.startsWith("sk-or-")) {
            val exact = r.openrouter.keyPatterns.any { it.regex.matches(key) }
            return KeyDetection.Match(
                r.openrouter,
                confident = true,
                note = if (exact) null else "Shorter or longer than a usual OpenRouter key. Check it copied completely."
            )
        }

        if (key.startsWith("sk-admin-")) {
            return KeyDetection.Rejected(
                r.openai,
                "That's an OpenAI admin key. Admin keys manage an organisation and can't be used for chat. " +
                    "Create a project key (sk-proj-...) instead."
            )
        }

        if (r.openai.keyPatterns.any { it.regex.matches(key) }) {
            return KeyDetection.Match(r.openai, confident = true)
        }

        if (r.deepseek.keyPatterns.any { it.regex.matches(key) }) {
            return KeyDetection.Match(
                r.deepseek,
                confident = false,
                note = "Looks like a DeepSeek key. Forge will confirm with DeepSeek before saving."
            )
        }

        // Another sk- shape: an older OpenAI key without the usual marker, or a DeepSeek key of
        // an unusual length. Probing both is cheap and spends no tokens.
        if (key.startsWith("sk-")) return KeyDetection.Ambiguous(listOf(r.openai, r.deepseek))

        if (key.startsWith("AIza")) {
            val exact = r.gemini.keyPatterns.first().regex.matches(key)
            return KeyDetection.Match(
                r.gemini,
                confident = exact,
                note = if (exact) null else "Starts like a Google key but has an unusual length."
            )
        }
        if (key.startsWith("AQ.")) {
            return KeyDetection.Match(
                r.gemini,
                confident = false,
                note = "Google auth key (AQ.). Some are reported to fail on Gemini's OpenAI-compatible " +
                    "endpoint; if Test fails, create a standard key."
            )
        }

        if (key.startsWith("gsk_")) return KeyDetection.Match(r.groq, confident = true)
        if (key.startsWith("xai-")) return KeyDetection.Match(r.xai, confident = true)

        return KeyDetection.Unknown(r.probeCandidates)
    }

    /** "sk-ant-…a1B2": enough to recognise the key, not enough to reuse it. */
    fun mask(key: String): String {
        val k = clean(key)
        if (k.length <= 12) return "••••" + k.takeLast(2)
        return k.take(7) + "…" + k.takeLast(4)
    }
}

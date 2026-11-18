package com.laksh.forge.data.ai

import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The AI key's lifecycle: where it lives (the [SecretStore]), the one-time move out of v1's
 * plaintext setting, and which provider it belongs to.
 */
object AiKeys {

    /** SecretStore entry for the LLM API key. */
    const val API_KEY = "ai_api_key"

    /** settings.providerId value meaning "work it out from the key". */
    const val AUTO = "auto"

    private val migrationLock = Mutex()

    suspend fun current(): String? = SecretStore.get(API_KEY)?.takeIf { it.isNotBlank() }

    fun peek(): String? = SecretStore.peek(API_KEY)?.takeIf { it.isNotBlank() }

    /**
     * Move v1's plaintext `settings.apiKey` into the encrypted store, blank the plaintext and
     * mark AI configured. Also pins the provider (v1 keys were Anthropic keys, but detection
     * decides) so later synchronous routing never needs the key itself.
     *
     * Idempotent and cheap after the first run; safe to call on every launch. Also repairs
     * the restored-from-backup case where `aiConfigured` came back but the secret (which is
     * excluded from backup) did not.
     */
    suspend fun migrateLegacy(store: SettingsStore) {
        migrationLock.withLock { migrateLocked(store) }
    }

    private suspend fun migrateLocked(store: SettingsStore) {
        val s = store.settings.first()
        val legacy = KeyDetector.clean(s.apiKey)
        val stored = current()
        if (legacy.isNotEmpty()) {
            if (stored != legacy) SecretStore.put(API_KEY, legacy)
            // Only blank the plaintext once the encrypted copy reads back identically.
            if (current() == legacy) {
                if (s.providerId == AUTO || stored != legacy) {
                    val detected = (KeyDetector.detect(legacy) as? KeyDetection.Match)?.provider
                    store.setProviderId(detected?.id ?: ProviderRegistry.anthropic.id)
                }
                store.setApiKey("")
                store.setAiConfigured(true)
            }
        } else if (s.aiConfigured && stored == null) {
            store.setAiConfigured(false)
        } else if (!s.aiConfigured && stored != null) {
            store.setAiConfigured(true)
        }
    }

    /**
     * The provider for [s] and (optionally) the key. A pinned providerId wins; "auto" falls
     * back to detection from the key's shape.
     *
     * @throws AiException when no provider can be determined.
     */
    fun providerFor(s: ForgeSettings, key: String?): ProviderSpec {
        if (s.providerId != AUTO && s.providerId.isNotBlank()) {
            return ProviderRegistry.byId(s.providerId)
                ?: throw AiException("Unknown AI provider \"${s.providerId}\". Choose one in Settings → AI.")
        }
        val k = key?.takeIf { it.isNotBlank() } ?: s.apiKey.takeIf { it.isNotBlank() }
            ?: throw AiException("AI isn't set up yet. Add an API key in Settings → AI.")
        return when (val d = KeyDetector.detect(k)) {
            is KeyDetection.Match -> d.provider
            is KeyDetection.Rejected -> throw AiException(d.reason)
            else -> throw AiException("Forge can't tell which provider this key is for. Pick one in Settings → AI.")
        }
    }
}

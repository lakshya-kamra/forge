package com.laksh.forge.data.ai

import android.content.Context
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.SettingsStore
import com.laksh.forge.data.remote.Task

/**
 * The app's [ForgeAi]: whatever key the user pasted, routed per task to (provider, model).
 *
 * Provider = settings.providerId (pinned when the key is saved), or detected from the key.
 * Model = per-task override from settings.modelOverridesJson, else the provider's tier map
 * from the registry, with vision-capable fallbacks for photo jobs.
 *
 * Settings are read through [settings] synchronously (the repository's cached snapshot) so
 * [describe] and [isConfigured] never suspend.
 *
 * @param settingsStore when given, a leftover v1 plaintext key is migrated into the
 *   [SecretStore] the first time it is needed.
 */
class MultiProviderAi(
    context: Context,
    private val settings: () -> ForgeSettings,
    private val settingsStore: SettingsStore? = null
) : AiEngine() {

    init {
        SecretStore.attach(context)
    }

    override val isConfigured: Boolean
        get() = settings().hasApiKey

    /** The provider currently in use, or null when AI isn't set up. */
    fun activeProvider(): ProviderSpec? =
        runCatching { AiKeys.providerFor(settings(), AiKeys.peek()) }.getOrNull()

    override fun route(task: Task, imageCount: Int): AiEngine.Route {
        val s = settings()
        val spec = AiKeys.providerFor(s, AiKeys.peek())
        val overrides = ModelOverrides.parse(s.modelOverridesJson, spec.id)
        val pick = ModelRouter.pick(spec, s.tier, task, overrides, needsVision = imageCount > 0)
        val maxImages = pick.spec?.maxImages
        if (maxImages != null && imageCount > maxImages) {
            throw AiException(
                "${spec.shortName}'s ${pick.model} reads at most $maxImages images per request, and this " +
                    "has $imageCount. Use a text PDF or a provider without that limit."
            )
        }
        return AiEngine.Route(AiProviders.create(spec, s.customBaseUrl), pick.model, pick.spec)
    }

    override suspend fun apiKey(): String {
        AiKeys.current()?.let { return it }
        val legacy = KeyDetector.clean(settings().apiKey)
        if (legacy.isNotEmpty()) {
            settingsStore?.let { store -> runCatching { AiKeys.migrateLegacy(store) } }
            return legacy
        }
        throw AiException("No API key on this device. Add one in Settings → AI.")
    }
}

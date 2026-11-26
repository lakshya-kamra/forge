package com.laksh.forge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.ai.AiException
import com.laksh.forge.data.ai.AiHttp
import com.laksh.forge.data.ai.AiKeys
import com.laksh.forge.data.ai.AiProviders
import com.laksh.forge.data.ai.CostEstimator
import com.laksh.forge.data.ai.KeyDetection
import com.laksh.forge.data.ai.KeyDetector
import com.laksh.forge.data.ai.ModelOverrides
import com.laksh.forge.data.ai.ModelRouter
import com.laksh.forge.data.ai.MultiProviderAi
import com.laksh.forge.data.ai.ProviderRegistry
import com.laksh.forge.data.ai.ProviderSpec
import com.laksh.forge.data.ai.SecretStore
import com.laksh.forge.data.ai.TierPreset
import com.laksh.forge.data.ai.visionByDefault
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.remote.CostTier
import com.laksh.forge.data.remote.Task
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the screen is waiting on; drives the inline loading indicators and disables actions. */
enum class AiBusy { SAVING, CHECKING, TESTING, REMOVING }

data class AiStatus(val text: String, val isError: Boolean)

/** One job's routing, as shown in the tier breakdown. */
data class AiJobRow(
    val task: Task,
    val model: String?,
    val price: String?,
    val note: String?,
    val overridden: Boolean,
    val error: String?
)

data class AiTierOption(val tier: CostTier, val monthly: String?)

sealed interface AiModelsState {
    data object Idle : AiModelsState
    data object Loading : AiModelsState
    data class Loaded(val providerId: String, val ids: List<String>) : AiModelsState
    data class Error(val message: String) : AiModelsState
}

data class AiSettingsUiState(
    val loading: Boolean = true,
    val settings: ForgeSettings = ForgeSettings(),
    /** Masked form of the stored key, or null when none is stored on this phone. */
    val savedKeyMask: String? = null,
    /** The provider in use (null when AI isn't set up). */
    val provider: ProviderSpec? = null,
    val providerError: String? = null,
    val busy: AiBusy? = null,
    val status: AiStatus? = null,
    val jobs: List<AiJobRow> = emptyList(),
    val tiers: List<AiTierOption> = emptyList(),
    val estimate: CostEstimator.Estimate? = null,
    /**
     * The tier really in force for [provider]: settings.tier, or Economy when the saved tier is
     * one this provider doesn't offer (Ultra-cheap after leaving OpenRouter).
     */
    val selectedTier: CostTier = CostTier.BALANCED,
    /** The hand-picked routing behind [selectedTier], when the provider has one. */
    val preset: TierPreset? = null,
    /** Per-task overrides for [provider], keyed by Task name or [ModelOverrides.DEFAULT_KEY]. */
    val overrides: Map<String, String> = emptyMap(),
    val models: AiModelsState = AiModelsState.Idle
) {
    val hasKey: Boolean get() = savedKeyMask != null
}

/** One-off UI reactions (haptics, clearing the key field) that must not replay on rotation. */
sealed interface AiSettingsEvent {
    data object KeySaved : AiSettingsEvent
    data object Succeeded : AiSettingsEvent
    data object Failed : AiSettingsEvent
}

class AiSettingsViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val store = repo.settingsStore

    /**
     * Latest settings for this screen's own [MultiProviderAi]. Using a private instance (rather
     * than repo.ai) means Test and the model list always see what was saved a moment ago,
     * without waiting for the app-wide settings cache to catch up.
     */
    @Volatile private var snapshot: ForgeSettings = ForgeSettings()
    private val ai = MultiProviderAi(ForgeApp.instance, { snapshot }, store)

    private data class Transient(
        val keyLoaded: Boolean = false,
        val savedKeyMask: String? = null,
        val busy: AiBusy? = null,
        val status: AiStatus? = null,
        val models: AiModelsState = AiModelsState.Idle
    )

    private val transient = MutableStateFlow(Transient())
    private val _events = Channel<AiSettingsEvent>(Channel.BUFFERED)
    val events: Flow<AiSettingsEvent> = _events.receiveAsFlow()

    val state: StateFlow<AiSettingsUiState> = combine(store.settings, transient) { s, t ->
        snapshot = s
        build(s, t)
    }
        // Routing and cost estimates are small pure computations, but they run on every settings
        // change, so keep them off the main thread.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    init {
        viewModelScope.launch {
            // Covers a v1 key that the app-start migration hasn't reached yet.
            runCatching { AiKeys.migrateLegacy(store) }
            refreshKey()
        }
    }

    // ---------------------------------------------------------------- key

    /**
     * Save a pasted key. [choice] is [AiKeys.AUTO] or a provider id from the picker; a custom
     * endpoint also needs [rawBaseUrl]. Keys whose provider can't be told from their shape are
     * confirmed against candidate providers' model lists (free, no tokens) before saving.
     */
    fun save(rawKey: String, choice: String, rawBaseUrl: String) {
        if (transient.value.busy != null) return
        viewModelScope.launch {
            val key = KeyDetector.clean(rawKey)
            if (key.isEmpty()) {
                fail("Paste a key first.")
                return@launch
            }
            setBusy(AiBusy.SAVING, null)
            val resolved = try {
                resolveProvider(key, choice, rawBaseUrl)
            } catch (e: AiException) {
                fail(e.message ?: "Couldn't save that key.")
                return@launch
            }
            val (provider, baseUrl) = resolved
            var tierNote = ""
            runCatching {
                val previous = store.settings.first()
                SecretStore.put(AiKeys.API_KEY, key)
                store.setProviderId(provider.id)
                if (previous.providerId != provider.id) {
                    // Overrides and a base URL belong to the provider they were set for.
                    store.setModelOverridesJson("")
                    store.setCustomBaseUrl("")
                    transient.update { it.copy(models = AiModelsState.Idle) }
                    // A provider with a researched preset starts on it (OpenRouter: Ultra-cheap).
                    // Only on a switch, so re-saving the same key never undoes the user's choice.
                    val defaultTier = ModelRouter.defaultTierFor(provider)
                    if (defaultTier != null && defaultTier != previous.tier) {
                        store.setTier(defaultTier)
                        tierNote = provider.preset(defaultTier.name)?.let { "Using the ${it.title} setup. " }.orEmpty()
                    } else if (defaultTier == null && previous.tier.name == ModelRouter.ULTRA_CHEAP) {
                        store.setTier(CostTier.BALANCED)
                    }
                }
                if (provider.isCustom) store.setCustomBaseUrl(baseUrl)
                // The v1 plaintext slot must never hold a key again.
                store.setApiKey("")
                store.setAiConfigured(true)
                syncSettings()
            }.onFailure { e ->
                fail("Couldn't store the key securely: ${e.message ?: "unknown error"}")
                return@launch
            }
            refreshKey()
            _events.trySend(AiSettingsEvent.KeySaved)
            // Saving is only half the answer; show whether the key actually works.
            runTest(prefix = "Saved for ${provider.shortName}. $tierNote")
        }
    }

    fun test() {
        if (transient.value.busy != null) return
        viewModelScope.launch { runTest(prefix = "") }
    }

    fun remove() {
        if (transient.value.busy != null) return
        viewModelScope.launch {
            setBusy(AiBusy.REMOVING, null)
            runCatching {
                SecretStore.remove(AiKeys.API_KEY)
                store.setApiKey("")
                store.setAiConfigured(false)
                store.setProviderId(AiKeys.AUTO)
                store.setModelOverridesJson("")
                store.setCustomBaseUrl("")
                syncSettings()
            }.onSuccess {
                transient.update {
                    it.copy(
                        savedKeyMask = null,
                        busy = null,
                        models = AiModelsState.Idle,
                        status = AiStatus("Key removed from this phone. AI features are off until you add one.", false)
                    )
                }
                _events.trySend(AiSettingsEvent.Succeeded)
            }.onFailure { e -> fail("Couldn't remove the key: ${e.message ?: "unknown error"}") }
        }
    }

    fun dismissStatus() = transient.update { it.copy(status = null) }

    // ---------------------------------------------------------------- tier & models

    fun setTier(tier: CostTier) {
        viewModelScope.launch {
            store.setTier(tier)
            syncSettings()
        }
    }

    /** [taskKey] is a Task name or [ModelOverrides.DEFAULT_KEY]; null [model] restores the tier default. */
    fun setOverride(taskKey: String, model: String?) {
        viewModelScope.launch {
            val s = store.settings.first()
            val provider = state.value.provider ?: return@launch
            val current = ModelOverrides.parse(s.modelOverridesJson, provider.id).toMutableMap()
            if (model.isNullOrBlank()) current.remove(taskKey) else current[taskKey] = model.trim()
            store.setModelOverridesJson(ModelOverrides.serialize(provider.id, current))
            syncSettings()
        }
    }

    fun loadModels(force: Boolean = false) {
        val provider = state.value.provider ?: return
        val current = transient.value.models
        if (!force && (current is AiModelsState.Loading ||
                (current is AiModelsState.Loaded && current.providerId == provider.id))
        ) return
        viewModelScope.launch {
            transient.update { it.copy(models = AiModelsState.Loading) }
            syncSettings()
            runCatching { ai.listModels() }
                .onSuccess { ids -> transient.update { it.copy(models = AiModelsState.Loaded(provider.id, ids)) } }
                .onFailure { e ->
                    transient.update {
                        it.copy(models = AiModelsState.Error(e.message ?: "Couldn't load the model list."))
                    }
                }
        }
    }

    /** Blank clears the override (back to the provider's own URL). */
    fun setBaseUrl(raw: String) {
        viewModelScope.launch {
            if (raw.isBlank()) {
                if (state.value.provider?.isCustom == true) {
                    fail("A custom endpoint needs its base URL.")
                    return@launch
                }
                store.setCustomBaseUrl("")
                syncSettings()
                transient.update { it.copy(status = AiStatus("Using the provider's standard address.", false), models = AiModelsState.Idle) }
                return@launch
            }
            val normalized = AiHttp.normalizeBaseUrl(raw)
            if (normalized == null) {
                fail("That isn't a usable HTTPS URL. Use the form https://host/v1.")
                return@launch
            }
            store.setCustomBaseUrl(normalized)
            syncSettings()
            transient.update { it.copy(status = AiStatus("Base URL set to $normalized.", false), models = AiModelsState.Idle) }
            _events.trySend(AiSettingsEvent.Succeeded)
        }
    }

    // ---------------------------------------------------------------- internals

    private suspend fun runTest(prefix: String) {
        setBusy(AiBusy.TESTING, if (prefix.isNotEmpty()) AiStatus(prefix + "Testing...", false) else null)
        syncSettings()
        runCatching { ai.testKey() }
            .onSuccess { msg ->
                transient.update { it.copy(busy = null, status = AiStatus(prefix + msg, false)) }
                _events.trySend(AiSettingsEvent.Succeeded)
            }
            .onFailure { e -> fail(prefix + (e.message ?: "Test failed.")) }
    }

    private suspend fun resolveProvider(key: String, choice: String, rawBaseUrl: String): Pair<ProviderSpec, String> {
        if (choice != AiKeys.AUTO) {
            val spec = ProviderRegistry.byId(choice) ?: throw AiException("Pick a provider.")
            if (spec.isCustom) {
                val base = AiHttp.normalizeBaseUrl(rawBaseUrl)
                    ?: throw AiException("Enter the endpoint's HTTPS base URL, e.g. https://example.com/v1.")
                return spec to base
            }
            (KeyDetector.detect(key) as? KeyDetection.Rejected)?.let { r ->
                if (r.provider?.id == spec.id) throw AiException(r.reason)
            }
            return spec to ""
        }
        return when (val d = KeyDetector.detect(key)) {
            KeyDetection.Empty -> throw AiException("Paste a key first.")
            is KeyDetection.Rejected -> throw AiException(d.reason)
            is KeyDetection.Match -> {
                if (d.confident) {
                    d.provider to ""
                } else {
                    // DeepSeek's sk-hex shape could also be an older OpenAI key, so check both.
                    val candidates = if (d.provider.id == ProviderRegistry.deepseek.id) {
                        listOf(d.provider, ProviderRegistry.openai)
                    } else {
                        listOf(d.provider)
                    }
                    probe(key, candidates) to ""
                }
            }
            is KeyDetection.Ambiguous -> probe(key, d.candidates) to ""
            is KeyDetection.Unknown -> probe(key, d.probeCandidates) to ""
        }
    }

    /** First candidate whose GET /models accepts the key. Spends no tokens. */
    private suspend fun probe(key: String, candidates: List<ProviderSpec>): ProviderSpec {
        setBusy(
            AiBusy.CHECKING,
            AiStatus("Checking which provider accepts this key (${candidates.joinToString(", ") { it.shortName }})...", false)
        )
        val rejectedBy = mutableListOf<String>()
        var lastError: AiException? = null
        for (spec in candidates) {
            try {
                if (AiProviders.create(spec).probe(key)) return spec
                rejectedBy += spec.shortName
            } catch (e: AiException) {
                lastError = e
            }
        }
        if (rejectedBy.size == candidates.size) {
            throw AiException(
                "Couldn't recognise this key - pick your provider below and save again " +
                    "(${rejectedBy.joinToString(" and ")} didn't accept it). Command Code keys always need picking."
            )
        }
        throw lastError ?: AiException("Couldn't confirm the provider. Pick it under Provider and save again.")
    }

    private suspend fun refreshKey() {
        val key = runCatching { AiKeys.current() }.getOrNull()
        transient.update { it.copy(keyLoaded = true, savedKeyMask = key?.let(KeyDetector::mask)) }
    }

    /** Push freshly written settings to this screen's AI instance and the app-wide cache. */
    private suspend fun syncSettings() {
        val s = store.settings.first()
        snapshot = s
        repo.updateSettingsCache(s)
    }

    private fun setBusy(busy: AiBusy?, status: AiStatus?) =
        transient.update { it.copy(busy = busy, status = status ?: it.status.takeIf { busy == null }) }

    private fun fail(message: String) {
        transient.update { it.copy(busy = null, status = AiStatus(message, true)) }
        _events.trySend(AiSettingsEvent.Failed)
    }

    private fun build(s: ForgeSettings, t: Transient): AiSettingsUiState {
        val configured = t.savedKeyMask != null || s.apiKey.isNotBlank()
        val providerResult = if (configured) runCatching { AiKeys.providerFor(s, AiKeys.peek()) } else null
        val provider = providerResult?.getOrNull()
        val overrides = provider?.let { ModelOverrides.parse(s.modelOverridesJson, it.id) } ?: emptyMap()
        val today = LocalDate.now()

        val selected = provider?.let { ModelRouter.effectiveCostTier(it, s.tier) } ?: s.tier
        val jobs = provider?.let { p -> SETTINGS_TASKS.map { task -> jobRow(p, selected, task, overrides, today) } }
            ?: emptyList()
        val tiers = provider?.let { p ->
            ModelRouter.tiersFor(p).map { tier ->
                AiTierOption(tier, CostEstimator.monthly(p, tier, overrides, today).totalUsd?.let(CostEstimator::formatUsd))
            }
        } ?: emptyList()

        return AiSettingsUiState(
            loading = !t.keyLoaded,
            settings = s,
            savedKeyMask = t.savedKeyMask,
            provider = provider,
            providerError = providerResult?.exceptionOrNull()?.message,
            busy = t.busy,
            status = t.status,
            jobs = jobs,
            tiers = tiers,
            estimate = provider?.let { CostEstimator.monthly(it, selected, overrides, today) },
            selectedTier = selected,
            preset = provider?.preset(selected.name),
            overrides = overrides,
            models = t.models
        )
    }

    private fun jobRow(
        provider: ProviderSpec,
        tier: CostTier,
        task: Task,
        overrides: Map<String, String>,
        today: LocalDate
    ): AiJobRow = try {
        val pick = ModelRouter.pick(provider, tier, task, overrides, needsVision = task.visionByDefault)
        AiJobRow(
            task = task,
            model = pick.model,
            price = pick.spec?.let { CostEstimator.priceLabel(it, today) } ?: "Price not in Forge's list",
            note = pick.note ?: pick.spec?.note,
            overridden = pick.overridden,
            error = null
        )
    } catch (e: AiException) {
        AiJobRow(task, null, null, null, overridden = false, error = e.message)
    }

    override fun onCleared() {
        _events.close()
        super.onCleared()
    }

    companion object {
        /** Jobs shown in the breakdown and the Advanced overrides, in the order users think of them. */
        val SETTINGS_TASKS = listOf(Task.FOOD_PHOTO, Task.LABEL, Task.COACH, Task.PROGRAM, Task.BODY, Task.PLAN)

        val Factory = forgeFactory { AiSettingsViewModel(it) }
    }
}

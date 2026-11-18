package com.laksh.forge.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.health.HealthAvailability
import com.laksh.forge.data.prefs.ForgeSettings
import com.laksh.forge.data.prefs.GoalMode
import com.laksh.forge.data.prefs.Sex
import com.laksh.forge.data.prefs.WeightUnit
import com.laksh.forge.data.remote.CostTier
import com.laksh.forge.domain.NutritionMath
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: ForgeSettings = ForgeSettings(),
    val latestWeightKg: Double? = null,
    val keyTestResult: String? = null,
    val testingKey: Boolean = false,
    val healthAvailability: HealthAvailability = HealthAvailability.NOT_SUPPORTED,
    val healthGranted: Boolean = false,
    val syncing: Boolean = false,
    val syncMessage: String? = null
) {
    val previewTargets get() = NutritionMath.targets(
        settings,
        latestWeightKg ?: 75.0,
        settings.learnedTdee.takeIf { it > 0 }
    )
}

class SettingsViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val transient = MutableStateFlow(SettingsUiState())

    val state: StateFlow<SettingsUiState> = combine(
        repo.settingsStore.settings,
        repo.weightDao.observeLatest(),
        transient
    ) { settings, weight, t ->
        t.copy(settings = settings, latestWeightKg = weight?.kg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init { refreshHealthStatus() }

    fun refreshHealthStatus() = viewModelScope.launch {
        val availability = repo.health.availability()
        val granted = if (availability == HealthAvailability.AVAILABLE) {
            repo.health.hasAllPermissions()
        } else false
        transient.update { it.copy(healthAvailability = availability, healthGranted = granted) }
    }

    fun healthPermissions(): Set<String> = repo.health.permissions
    fun healthContract() = repo.health.permissionContract()
    fun healthSettingsIntent() = repo.health.settingsIntent()
    fun healthInstallIntent() = repo.health.playStoreIntent()

    fun setApiKey(v: String) = viewModelScope.launch {
        repo.settingsStore.setApiKey(v)
        transient.update { it.copy(keyTestResult = null) }
    }

    fun testKey() = viewModelScope.launch {
        transient.update { it.copy(testingKey = true, keyTestResult = null) }
        // Give the settings cache a moment to pick up a key typed a beat ago.
        val current = repo.settingsStore.settings.first()
        repo.updateSettingsCache(current)
        runCatching { repo.anthropic.testKey() }
            .onSuccess { transient.update { s -> s.copy(testingKey = false, keyTestResult = "Key works. Running on the ${current.tier.label} tier.") } }
            .onFailure { e -> transient.update { s -> s.copy(testingKey = false, keyTestResult = e.message ?: "Test failed.") } }
    }

    fun setTier(v: CostTier) = viewModelScope.launch { repo.settingsStore.setTier(v) }
    fun setGoal(v: GoalMode) = viewModelScope.launch {
        repo.settingsStore.setGoal(v)
        // A goal change implies a new default rate unless the user has set one.
        val weight = repo.weightDao.observeLatest().first()?.kg ?: 75.0
        repo.settingsStore.setRate(NutritionMath.defaultRate(v, weight))
    }
    fun setSex(v: Sex) = viewModelScope.launch { repo.settingsStore.setSex(v) }
    fun setAge(v: Int) = viewModelScope.launch { repo.settingsStore.setAge(v) }
    fun setHeight(v: Double) = viewModelScope.launch { repo.settingsStore.setHeight(v) }
    fun setActivity(v: Double) = viewModelScope.launch { repo.settingsStore.setActivityFactor(v) }
    fun setUseAuto(v: Boolean) = viewModelScope.launch { repo.settingsStore.setUseAutoTargets(v) }
    fun setManualKcal(v: Int) = viewModelScope.launch { repo.settingsStore.setManualKcal(v) }
    fun setProteinPerKg(v: Double) = viewModelScope.launch { repo.settingsStore.setProteinPerKg(v) }
    fun setFatPerKg(v: Double) = viewModelScope.launch { repo.settingsStore.setFatPerKg(v) }
    fun setRate(v: Double) = viewModelScope.launch { repo.settingsStore.setRate(v) }
    fun setUnit(v: WeightUnit) = viewModelScope.launch { repo.settingsStore.setWeightUnit(v) }
    fun setSafetyMargin(v: Int) = viewModelScope.launch { repo.settingsStore.setSafetyMargin(v) }
    fun setStorePhotos(v: Boolean) = viewModelScope.launch { repo.settingsStore.setStorePhotos(v) }
    fun setOnboarded(v: Boolean) = viewModelScope.launch { repo.settingsStore.setOnboarded(v) }

    fun onHealthPermissionResult() = viewModelScope.launch {
        refreshHealthStatus()
        if (repo.health.hasAllPermissions()) {
            repo.settingsStore.setHealthConnectEnabled(true)
            syncHealth()
        }
    }

    fun syncHealth() = viewModelScope.launch {
        transient.update { it.copy(syncing = true, syncMessage = null) }
        runCatching { repo.syncHealth() }
            .onSuccess { count ->
                transient.update {
                    it.copy(
                        syncing = false,
                        syncMessage = if (count == 0)
                            "Connected, but no data came back. Check Zepp: Profile - Settings - Health Connect, and make sure sharing is on."
                        else "Synced $count days of wearable data."
                    )
                }
            }
            .onFailure { e ->
                transient.update { it.copy(syncing = false, syncMessage = e.message ?: "Sync failed.") }
            }
    }

    fun clearSyncMessage() = transient.update { it.copy(syncMessage = null) }

    companion object {
        val Factory = forgeFactory { SettingsViewModel(it) }
    }
}

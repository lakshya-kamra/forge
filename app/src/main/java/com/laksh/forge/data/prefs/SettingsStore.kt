package com.laksh.forge.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laksh.forge.data.remote.CostTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "forge_settings")

enum class GoalMode(val label: String, val blurb: String) {
    CUT("Cut", "Lose fat, hold muscle"),
    MAINGAIN("Maingain", "Drift up slowly, stay lean"),
    LEAN_GAIN("Lean gain", "Build with minimal fat"),
    MAINTAIN("Maintain", "Hold bodyweight"),
    RECOMP("Recomp", "At maintenance, add muscle");
}

enum class Sex { MALE, FEMALE }

enum class WeightUnit(val label: String) { KG("kg"), LB("lb") }

data class ForgeSettings(
    val apiKey: String = "",
    val tier: CostTier = CostTier.BALANCED,
    val onboarded: Boolean = false,
    val goal: GoalMode = GoalMode.MAINGAIN,
    val sex: Sex = Sex.MALE,
    val age: Int = 30,
    val heightCm: Double = 175.0,
    val activityFactor: Double = 1.45,
    val useAutoTargets: Boolean = true,
    val manualKcal: Int = 2400,
    val proteinPerKg: Double = 2.0,
    val fatPerKg: Double = 0.8,
    /** 0.0 means "not set" - targets then derive the rate from the goal. */
    val rateKgPerWeek: Double = 0.0,
    val weightUnit: WeightUnit = WeightUnit.KG,
    /** Deliberate upward padding on AI photo estimates, in percent. */
    val safetyMarginPct: Int = 12,
    /** When false, meal photos are analysed in memory and never written to disk. */
    val storePhotos: Boolean = true,
    val healthConnectEnabled: Boolean = false,
    val lastHealthSync: Long = 0L,
    /** Adaptive TDEE learned from weight trend vs. intake; 0 until enough data. */
    val learnedTdee: Double = 0.0
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()
}

class SettingsStore(private val context: Context) {

    private object K {
        val apiKey = stringPreferencesKey("api_key")
        val tier = stringPreferencesKey("cost_tier")
        val onboarded = booleanPreferencesKey("onboarded")
        val goal = stringPreferencesKey("goal")
        val sex = stringPreferencesKey("sex")
        val age = intPreferencesKey("age")
        val heightCm = doublePreferencesKey("height_cm")
        val activityFactor = doublePreferencesKey("activity_factor")
        val useAutoTargets = booleanPreferencesKey("use_auto_targets")
        val manualKcal = intPreferencesKey("manual_kcal")
        val proteinPerKg = doublePreferencesKey("protein_per_kg")
        val fatPerKg = doublePreferencesKey("fat_per_kg")
        val rateKgPerWeek = doublePreferencesKey("rate_kg_per_week")
        val weightUnit = stringPreferencesKey("weight_unit")
        val safetyMarginPct = intPreferencesKey("safety_margin_pct")
        val storePhotos = booleanPreferencesKey("store_photos")
        val healthConnectEnabled = booleanPreferencesKey("hc_enabled")
        val lastHealthSync = longPreferencesKey("last_health_sync")
        val learnedTdee = doublePreferencesKey("learned_tdee")
    }

    val settings: Flow<ForgeSettings> = context.dataStore.data.map { p ->
        ForgeSettings(
            apiKey = p[K.apiKey] ?: "",
            tier = runCatching { CostTier.valueOf(p[K.tier] ?: "BALANCED") }.getOrDefault(CostTier.BALANCED),
            onboarded = p[K.onboarded] ?: false,
            goal = runCatching { GoalMode.valueOf(p[K.goal] ?: "MAINGAIN") }.getOrDefault(GoalMode.MAINGAIN),
            sex = runCatching { Sex.valueOf(p[K.sex] ?: "MALE") }.getOrDefault(Sex.MALE),
            age = p[K.age] ?: 30,
            heightCm = p[K.heightCm] ?: 175.0,
            activityFactor = p[K.activityFactor] ?: 1.45,
            useAutoTargets = p[K.useAutoTargets] ?: true,
            manualKcal = p[K.manualKcal] ?: 2400,
            proteinPerKg = p[K.proteinPerKg] ?: 2.0,
            fatPerKg = p[K.fatPerKg] ?: 0.8,
            rateKgPerWeek = p[K.rateKgPerWeek] ?: 0.0,
            weightUnit = runCatching { WeightUnit.valueOf(p[K.weightUnit] ?: "KG") }.getOrDefault(WeightUnit.KG),
            safetyMarginPct = p[K.safetyMarginPct] ?: 12,
            storePhotos = p[K.storePhotos] ?: true,
            healthConnectEnabled = p[K.healthConnectEnabled] ?: false,
            lastHealthSync = p[K.lastHealthSync] ?: 0L,
            learnedTdee = p[K.learnedTdee] ?: 0.0
        )
    }

    suspend fun setApiKey(v: String) = edit { it[K.apiKey] = v.trim() }
    suspend fun setTier(v: CostTier) = edit { it[K.tier] = v.name }
    suspend fun setOnboarded(v: Boolean) = edit { it[K.onboarded] = v }
    suspend fun setGoal(v: GoalMode) = edit { it[K.goal] = v.name }
    suspend fun setSex(v: Sex) = edit { it[K.sex] = v.name }
    suspend fun setAge(v: Int) = edit { it[K.age] = v }
    suspend fun setHeight(v: Double) = edit { it[K.heightCm] = v }
    suspend fun setActivityFactor(v: Double) = edit { it[K.activityFactor] = v }
    suspend fun setUseAutoTargets(v: Boolean) = edit { it[K.useAutoTargets] = v }
    suspend fun setManualKcal(v: Int) = edit { it[K.manualKcal] = v }
    suspend fun setProteinPerKg(v: Double) = edit { it[K.proteinPerKg] = v }
    suspend fun setFatPerKg(v: Double) = edit { it[K.fatPerKg] = v }
    suspend fun setRate(v: Double) = edit { it[K.rateKgPerWeek] = v }
    suspend fun setWeightUnit(v: WeightUnit) = edit { it[K.weightUnit] = v.name }
    suspend fun setSafetyMargin(v: Int) = edit { it[K.safetyMarginPct] = v.coerceIn(0, 40) }
    suspend fun setStorePhotos(v: Boolean) = edit { it[K.storePhotos] = v }
    suspend fun setHealthConnectEnabled(v: Boolean) = edit { it[K.healthConnectEnabled] = v }
    suspend fun setLastHealthSync(v: Long) = edit { it[K.lastHealthSync] = v }
    suspend fun setLearnedTdee(v: Double) = edit { it[K.learnedTdee] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}

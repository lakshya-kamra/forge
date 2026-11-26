package com.laksh.forge.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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

enum class TrainingAge(val label: String, val blurb: String) {
    UNKNOWN("Not set", ""),
    NOVICE("Beginner", "Under ~1 year of consistent lifting"),
    INTERMEDIATE("Intermediate", "1-4 years, still progressing month to month"),
    ADVANCED("Advanced", "4+ years, progress is slow and hard-won");
}

enum class PregnancyStatus { NONE, PREGNANT, BREASTFEEDING }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ForgeSettings(
    /**
     * Legacy plaintext key from v1. The AI layer migrates it into the encrypted secret store
     * and blanks this field; check [hasApiKey], never this, to know whether AI is usable.
     */
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
    val learnedTdee: Double = 0.0,

    // ---------------------------------------------------------------- v2: AI provider
    /** True once a key is stored in the encrypted secret store (see data/ai). */
    val aiConfigured: Boolean = false,
    /** "auto" = detect from the key; otherwise a provider id from the provider registry. */
    val providerId: String = "auto",
    /** Base URL for a custom OpenAI-compatible endpoint; blank = provider default. */
    val customBaseUrl: String = "",
    /** JSON object {"FOOD_PHOTO": "model-id", ...} overriding the tier's model per task. */
    val modelOverridesJson: String = "",

    // ---------------------------------------------------------------- v2: profile & safety
    /** 0 = v1 onboarding only; 2 = completed the v2 photo/measurement onboarding. */
    val onboardingVersion: Int = 0,
    val trainingAge: TrainingAge = TrainingAge.UNKNOWN,
    val pregnancyStatus: PregnancyStatus = PregnancyStatus.NONE,
    /** Result of the SCOFF eating-disorder screen; positive changes how numbers are shown. */
    val edScreenPositive: Boolean = false,
    val edScreenAt: Long = 0L,
    /** User chose to hide calorie numbers (offered after a positive screen). */
    val hideNumbers: Boolean = false,
    val stepsTarget: Int = 8000,
    /** ISO week start (yyyy-MM-dd) of the last completed weekly check-in. */
    val lastCheckinWeek: String = "",

    // ---------------------------------------------------------------- v2: appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val amoledBlack: Boolean = false,
    val haptics: Boolean = true,
    val reduceMotion: Boolean = false,

    // ---------------------------------------------------------------- v2: wearables & reminders
    val whoopConnected: Boolean = false,
    val lastWhoopSync: Long = 0L,
    val weighInReminder: Boolean = false,
    val mealReminder: Boolean = false,
    val workoutReminder: Boolean = false,
    /**
     * Monthly "time for progress photos" nudge. Unset reads as on for anyone who finished the
     * v2 onboarding (they were shown the photo protocol), off for everyone else.
     */
    val progressPhotoReminder: Boolean = false
) {
    val hasApiKey: Boolean get() = aiConfigured || apiKey.isNotBlank()
    val isMinor: Boolean get() = age < 18
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

        val aiConfigured = booleanPreferencesKey("ai_configured")
        val providerId = stringPreferencesKey("provider_id")
        val customBaseUrl = stringPreferencesKey("custom_base_url")
        val modelOverrides = stringPreferencesKey("model_overrides")

        val onboardingVersion = intPreferencesKey("onboarding_version")
        val trainingAge = stringPreferencesKey("training_age")
        val pregnancy = stringPreferencesKey("pregnancy_status")
        val edScreenPositive = booleanPreferencesKey("ed_screen_positive")
        val edScreenAt = longPreferencesKey("ed_screen_at")
        val hideNumbers = booleanPreferencesKey("hide_numbers")
        val stepsTarget = intPreferencesKey("steps_target")
        val lastCheckinWeek = stringPreferencesKey("last_checkin_week")

        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val amoled = booleanPreferencesKey("amoled_black")
        val haptics = booleanPreferencesKey("haptics")
        val reduceMotion = booleanPreferencesKey("reduce_motion")

        val whoopConnected = booleanPreferencesKey("whoop_connected")
        val lastWhoopSync = longPreferencesKey("last_whoop_sync")
        val weighInReminder = booleanPreferencesKey("reminder_weigh_in")
        val mealReminder = booleanPreferencesKey("reminder_meal")
        val workoutReminder = booleanPreferencesKey("reminder_workout")
        val progressPhotoReminder = booleanPreferencesKey("reminder_progress_photos")
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, default: T): T =
        runCatching { enumValueOf<T>(raw ?: default.name) }.getOrDefault(default)

    val settings: Flow<ForgeSettings> = context.dataStore.data.map { p ->
        ForgeSettings(
            apiKey = p[K.apiKey] ?: "",
            tier = enumOr(p[K.tier], CostTier.BALANCED),
            onboarded = p[K.onboarded] ?: false,
            goal = enumOr(p[K.goal], GoalMode.MAINGAIN),
            sex = enumOr(p[K.sex], Sex.MALE),
            age = p[K.age] ?: 30,
            heightCm = p[K.heightCm] ?: 175.0,
            activityFactor = p[K.activityFactor] ?: 1.45,
            useAutoTargets = p[K.useAutoTargets] ?: true,
            manualKcal = p[K.manualKcal] ?: 2400,
            proteinPerKg = p[K.proteinPerKg] ?: 2.0,
            fatPerKg = p[K.fatPerKg] ?: 0.8,
            rateKgPerWeek = p[K.rateKgPerWeek] ?: 0.0,
            weightUnit = enumOr(p[K.weightUnit], WeightUnit.KG),
            safetyMarginPct = p[K.safetyMarginPct] ?: 12,
            storePhotos = p[K.storePhotos] ?: true,
            healthConnectEnabled = p[K.healthConnectEnabled] ?: false,
            lastHealthSync = p[K.lastHealthSync] ?: 0L,
            learnedTdee = p[K.learnedTdee] ?: 0.0,

            aiConfigured = p[K.aiConfigured] ?: false,
            providerId = p[K.providerId] ?: "auto",
            customBaseUrl = p[K.customBaseUrl] ?: "",
            modelOverridesJson = p[K.modelOverrides] ?: "",

            onboardingVersion = p[K.onboardingVersion] ?: 0,
            trainingAge = enumOr(p[K.trainingAge], TrainingAge.UNKNOWN),
            pregnancyStatus = enumOr(p[K.pregnancy], PregnancyStatus.NONE),
            edScreenPositive = p[K.edScreenPositive] ?: false,
            edScreenAt = p[K.edScreenAt] ?: 0L,
            hideNumbers = p[K.hideNumbers] ?: false,
            stepsTarget = p[K.stepsTarget] ?: 8000,
            lastCheckinWeek = p[K.lastCheckinWeek] ?: "",

            themeMode = enumOr(p[K.themeMode], ThemeMode.SYSTEM),
            dynamicColor = p[K.dynamicColor] ?: false,
            amoledBlack = p[K.amoled] ?: false,
            haptics = p[K.haptics] ?: true,
            reduceMotion = p[K.reduceMotion] ?: false,

            whoopConnected = p[K.whoopConnected] ?: false,
            lastWhoopSync = p[K.lastWhoopSync] ?: 0L,
            weighInReminder = p[K.weighInReminder] ?: false,
            mealReminder = p[K.mealReminder] ?: false,
            workoutReminder = p[K.workoutReminder] ?: false,
            progressPhotoReminder = p[K.progressPhotoReminder] ?: ((p[K.onboardingVersion] ?: 0) >= 2)
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

    suspend fun setAiConfigured(v: Boolean) = edit { it[K.aiConfigured] = v }
    suspend fun setProviderId(v: String) = edit { it[K.providerId] = v }
    suspend fun setCustomBaseUrl(v: String) = edit { it[K.customBaseUrl] = v.trim() }
    suspend fun setModelOverridesJson(v: String) = edit { it[K.modelOverrides] = v }

    suspend fun setOnboardingVersion(v: Int) = edit { it[K.onboardingVersion] = v }
    suspend fun setTrainingAge(v: TrainingAge) = edit { it[K.trainingAge] = v.name }
    suspend fun setPregnancyStatus(v: PregnancyStatus) = edit { it[K.pregnancy] = v.name }
    suspend fun setEdScreen(positive: Boolean, at: Long) = edit {
        it[K.edScreenPositive] = positive
        it[K.edScreenAt] = at
    }
    suspend fun setHideNumbers(v: Boolean) = edit { it[K.hideNumbers] = v }
    suspend fun setStepsTarget(v: Int) = edit { it[K.stepsTarget] = v }
    suspend fun setLastCheckinWeek(v: String) = edit { it[K.lastCheckinWeek] = v }

    suspend fun setThemeMode(v: ThemeMode) = edit { it[K.themeMode] = v.name }
    suspend fun setDynamicColor(v: Boolean) = edit { it[K.dynamicColor] = v }
    suspend fun setAmoledBlack(v: Boolean) = edit { it[K.amoled] = v }
    suspend fun setHaptics(v: Boolean) = edit { it[K.haptics] = v }
    suspend fun setReduceMotion(v: Boolean) = edit { it[K.reduceMotion] = v }

    suspend fun setWhoopConnected(v: Boolean) = edit { it[K.whoopConnected] = v }
    suspend fun setLastWhoopSync(v: Long) = edit { it[K.lastWhoopSync] = v }
    suspend fun setWeighInReminder(v: Boolean) = edit { it[K.weighInReminder] = v }
    suspend fun setMealReminder(v: Boolean) = edit { it[K.mealReminder] = v }
    suspend fun setWorkoutReminder(v: Boolean) = edit { it[K.workoutReminder] = v }
    suspend fun setProgressPhotoReminder(v: Boolean) = edit { it[K.progressPhotoReminder] = v }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}

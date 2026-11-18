package com.laksh.forge.domain.plan

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laksh.forge.ForgeApp
import com.laksh.forge.data.prefs.ForgeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Onboarding answers that have no column in SettingsStore (which this feature does not own).
// Kept in their own small DataStore file so health details stay separate from app settings.
private val Context.planProfileDataStore: DataStore<Preferences> by preferencesDataStore(name = "forge_plan_profile")

/** Health and training context collected in onboarding and edited in Profile settings. */
data class PlanProfile(
    val kidneyDisease: Boolean = false,
    val diabetesMedication: Boolean = false,
    /** Any other condition that changes weight or nutrition advice. */
    val otherWeightCondition: Boolean = false,
    val southAsian: Boolean = false,
    val detrained: Boolean = false,
    val trainingGoal: TrainingGoal = TrainingGoal.LOOK,
    val preference: PhasePreference = PhasePreference.NO_PREFERENCE,
    /** 0 = not answered. */
    val daysPerWeek: Int = 0,
    val equipment: Equipment = Equipment.FULL_GYM,
    /** When free text last matched the crisis screen; 0 = never. The text itself is never stored. */
    val crisisFlaggedAt: Long = 0L,
    /** When the person agreed that photos may be sent for one analysis; 0 = never. */
    val photoConsentAt: Long = 0L,
    /** Last AI photo estimate, to keep the 14-day minimum between estimates. */
    val lastPhotoEstimateAt: Long = 0L,
    /**
     * safety_medical_red_flags_stop: when red-flag symptoms (chest pain, fainting...) were
     * reported; 0 = none. Workouts stay locked until the person confirms medical clearance.
     */
    val trainingHoldAt: Long = 0L,
    /**
     * When the supportive low-intake / rapid-loss check-in last fired; 0 = none open. An open
     * event blocks photo body-fat analysis (photo_feature_blocked_groups) for 30 days or until
     * a negative eating screen.
     */
    val safetyEventAt: Long = 0L
) {
    fun safetyEventOpen(now: Long = System.currentTimeMillis()): Boolean =
        safetyEventAt > 0 && now - safetyEventAt < CRISIS_LOCK_MS

    val trainingHold: Boolean get() = trainingHoldAt > 0

    /** Conditions that, per the onboarding decision rules, hold the plan at maintenance. */
    val medicalHold: Boolean get() = kidneyDisease || diabetesMedication || otherWeightCondition

    /** safety_crisis_disclosure applies the 30-day SCOFF-positive locks. */
    fun crisisLockActive(now: Long = System.currentTimeMillis()): Boolean =
        crisisFlaggedAt > 0 && now - crisisFlaggedAt < CRISIS_LOCK_MS

    companion object {
        /** guardrails.final.json safety_scoff_positive_response: locks last at least 30 days. */
        const val CRISIS_LOCK_MS = 30L * 86_400_000L
    }
}

/**
 * safety_scoff_positive_response: a positive screen (or a crisis disclosure, which applies the
 * same locks) holds for at least 30 days; only a negative re-screen after that lifts it.
 */
fun edScreenLockActive(s: ForgeSettings, now: Long = System.currentTimeMillis()): Boolean =
    s.edScreenPositive && now - s.edScreenAt < PlanProfile.CRISIS_LOCK_MS

object PlanProfileStore {

    private object K {
        val kidney = booleanPreferencesKey("kidney_disease")
        val diabetes = booleanPreferencesKey("diabetes_medication")
        val otherCondition = booleanPreferencesKey("other_weight_condition")
        val southAsian = booleanPreferencesKey("south_asian")
        val detrained = booleanPreferencesKey("detrained")
        val goal = stringPreferencesKey("training_goal")
        val preference = stringPreferencesKey("phase_preference")
        val days = intPreferencesKey("days_per_week")
        val equipment = stringPreferencesKey("equipment")
        val crisisAt = longPreferencesKey("crisis_flagged_at")
        val photoConsentAt = longPreferencesKey("photo_consent_at")
        val lastPhotoEstimateAt = longPreferencesKey("last_photo_estimate_at")
        val trainingHoldAt = longPreferencesKey("training_hold_at")
        val safetyEventAt = longPreferencesKey("safety_event_at")
    }

    private val store: DataStore<Preferences> get() = ForgeApp.instance.planProfileDataStore

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, default: T): T =
        runCatching { enumValueOf<T>(raw ?: default.name) }.getOrDefault(default)

    /** A corrupt file must never block onboarding, so read errors fall back to defaults. */
    val profile: Flow<PlanProfile>
        get() = store.data
            .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
            .map { read(it) }

    private fun read(p: Preferences) = PlanProfile(
        kidneyDisease = p[K.kidney] ?: false,
        diabetesMedication = p[K.diabetes] ?: false,
        otherWeightCondition = p[K.otherCondition] ?: false,
        southAsian = p[K.southAsian] ?: false,
        detrained = p[K.detrained] ?: false,
        trainingGoal = enumOr(p[K.goal], TrainingGoal.LOOK),
        preference = enumOr(p[K.preference], PhasePreference.NO_PREFERENCE),
        daysPerWeek = p[K.days] ?: 0,
        equipment = enumOr(p[K.equipment], Equipment.FULL_GYM),
        crisisFlaggedAt = p[K.crisisAt] ?: 0L,
        photoConsentAt = p[K.photoConsentAt] ?: 0L,
        lastPhotoEstimateAt = p[K.lastPhotoEstimateAt] ?: 0L,
        trainingHoldAt = p[K.trainingHoldAt] ?: 0L,
        safetyEventAt = p[K.safetyEventAt] ?: 0L
    )

    suspend fun current(): PlanProfile = profile.first()

    suspend fun update(transform: (PlanProfile) -> PlanProfile) {
        store.edit { p ->
            val old = read(p)
            val n = transform(old)
            p[K.kidney] = n.kidneyDisease
            p[K.diabetes] = n.diabetesMedication
            p[K.otherCondition] = n.otherWeightCondition
            p[K.southAsian] = n.southAsian
            p[K.detrained] = n.detrained
            p[K.goal] = n.trainingGoal.name
            p[K.preference] = n.preference.name
            p[K.days] = n.daysPerWeek
            p[K.equipment] = n.equipment.name
            p[K.crisisAt] = n.crisisFlaggedAt
            p[K.photoConsentAt] = n.photoConsentAt
            p[K.lastPhotoEstimateAt] = n.lastPhotoEstimateAt
            p[K.trainingHoldAt] = n.trainingHoldAt
            p[K.safetyEventAt] = n.safetyEventAt
        }
    }

    /** Lock workouts after a medical red flag (onboarding or coach). Keeps the first report's date. */
    suspend fun holdTraining(now: Long = System.currentTimeMillis()) =
        update { if (it.trainingHold) it else it.copy(trainingHoldAt = now) }

    /** The person confirmed a doctor has cleared them to train. */
    suspend fun clearTrainingHold() = update { it.copy(trainingHoldAt = 0L) }

    val trainingHold: Flow<Boolean> get() = profile.map { it.trainingHold }

}

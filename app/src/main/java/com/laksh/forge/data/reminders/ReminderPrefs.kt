package com.laksh.forge.data.reminders

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laksh.forge.data.prefs.ForgeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.DayOfWeek

/**
 * The three daily reminders. The on/off switches live in the shared settings
 * (weighInReminder / mealReminder / workoutReminder); everything else about a reminder -
 * its time, the training days - lives in this feature's own DataStore file.
 */
enum class ReminderType(
    val title: String,
    val blurb: String,
    val defaultMinutes: Int,
    val notificationId: Int
) {
    WEIGH_IN(
        "Morning weigh-in",
        "Only if you haven't logged a weight yet today",
        7 * 60 + 30,
        4101
    ),
    MEAL(
        "Evening food check",
        "Only if fewer than 2 items are logged today",
        20 * 60,
        4102
    ),
    WORKOUT(
        "Training days",
        "On the days you pick, if no session is logged yet",
        17 * 60 + 30,
        4103
    );

    fun isEnabled(s: ForgeSettings): Boolean = when (this) {
        WEIGH_IN -> s.weighInReminder
        MEAL -> s.mealReminder
        WORKOUT -> s.workoutReminder
    }

    companion object {
        fun fromName(name: String?): ReminderType? = entries.firstOrNull { it.name == name }
    }
}

data class ReminderPrefs(
    val weighInMinutes: Int = ReminderType.WEIGH_IN.defaultMinutes,
    val mealMinutes: Int = ReminderType.MEAL.defaultMinutes,
    val workoutMinutes: Int = ReminderType.WORKOUT.defaultMinutes,
    /** Days the workout reminder may fire. Defaults to every day until the person narrows it. */
    val trainingDays: Set<DayOfWeek> = DayOfWeek.entries.toSet()
) {
    fun minutesFor(type: ReminderType): Int = when (type) {
        ReminderType.WEIGH_IN -> weighInMinutes
        ReminderType.MEAL -> mealMinutes
        ReminderType.WORKOUT -> workoutMinutes
    }
}

private val Context.reminderStore: DataStore<Preferences> by preferencesDataStore(name = "forge_reminders")

object ReminderPrefsStore {

    private val WEIGH_IN = intPreferencesKey("weigh_in_minutes")
    private val MEAL = intPreferencesKey("meal_minutes")
    private val WORKOUT = intPreferencesKey("workout_minutes")
    private val DAYS = stringPreferencesKey("training_days")

    fun prefs(context: Context): Flow<ReminderPrefs> =
        context.applicationContext.reminderStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { p ->
                val d = ReminderPrefs()
                ReminderPrefs(
                    weighInMinutes = p[WEIGH_IN]?.takeIf { it in 0 until 1440 } ?: d.weighInMinutes,
                    mealMinutes = p[MEAL]?.takeIf { it in 0 until 1440 } ?: d.mealMinutes,
                    workoutMinutes = p[WORKOUT]?.takeIf { it in 0 until 1440 } ?: d.workoutMinutes,
                    trainingDays = p[DAYS]?.let(::decodeDays) ?: d.trainingDays
                )
            }

    suspend fun current(context: Context): ReminderPrefs = prefs(context).first()

    suspend fun setTime(context: Context, type: ReminderType, minutesOfDay: Int) {
        val m = minutesOfDay.coerceIn(0, 1439)
        context.applicationContext.reminderStore.edit {
            when (type) {
                ReminderType.WEIGH_IN -> it[WEIGH_IN] = m
                ReminderType.MEAL -> it[MEAL] = m
                ReminderType.WORKOUT -> it[WORKOUT] = m
            }
        }
    }

    suspend fun setTrainingDays(context: Context, days: Set<DayOfWeek>) {
        context.applicationContext.reminderStore.edit {
            it[DAYS] = days.sortedBy { d -> d.value }.joinToString(",") { d -> d.value.toString() }
        }
    }

    /** An empty stored set is a valid choice ("no training days"), so it isn't treated as missing. */
    private fun decodeDays(raw: String): Set<DayOfWeek>? = runCatching {
        if (raw.isBlank()) emptySet()
        else raw.split(',').map { DayOfWeek.of(it.trim().toInt()) }.toSet()
    }.getOrNull()
}

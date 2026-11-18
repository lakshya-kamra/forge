package com.laksh.forge.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Every logged item of food. `localDate` is denormalised (yyyy-MM-dd in the device's
 * zone at the time of logging) so day-grouping queries never have to do timezone math.
 */
@Entity(tableName = "food_entry", indices = [Index("localDate"), Index("timestamp")])
data class FoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val localDate: String,
    val name: String,
    val brand: String? = null,
    val servingDesc: String,
    val quantity: Double = 1.0,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double = 0.0,
    val source: String,              // PHOTO | BARCODE | MANUAL | FAVOURITE
    val mealSlot: String,            // BREAKFAST | LUNCH | DINNER | SNACK
    val photoPath: String? = null,
    /** Claude's own confidence in a photo estimate: HIGH | MEDIUM | LOW. */
    val confidence: String? = null,
    /** What the model said it was hedging on - shown verbatim in the entry detail. */
    val estimateNote: String? = null,
    /** Pre-safety-margin value, so the user can see how much padding was applied. */
    val rawKcal: Double? = null,
    val barcode: String? = null
)

@Entity(tableName = "weight_entry", indices = [Index("timestamp"), Index(value = ["localDate"])])
data class WeightEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val localDate: String,
    val kg: Double,
    val bodyFatPct: Double? = null,
    val note: String? = null,
    val source: String = "MANUAL"   // MANUAL | HEALTH_CONNECT
)

@Entity(tableName = "program")
data class Program(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val author: String? = null,
    val sourceFile: String? = null,
    val notes: String? = null,
    val createdAt: Long,
    val isActive: Boolean = false
)

@Entity(tableName = "program_day", indices = [Index("programId")])
data class ProgramDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val programId: Long,
    val name: String,
    val orderIdx: Int,
    val focus: String? = null
)

@Entity(tableName = "program_exercise", indices = [Index("dayId")])
data class ProgramExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val name: String,
    val orderIdx: Int,
    val sets: Int,
    val repLow: Int,
    val repHigh: Int,
    val rirTarget: Double? = null,
    val restSeconds: Int? = null,
    val notes: String? = null,
    val substitutions: String? = null
)

@Entity(tableName = "workout_session", indices = [Index("startedAt"), Index("localDate")])
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val programDayId: Long? = null,
    val title: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val localDate: String,
    val notes: String? = null
)

@Entity(tableName = "set_log", indices = [Index("sessionId"), Index("exerciseName"), Index("timestamp")])
data class SetLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseName: String,
    val setIdx: Int,
    val weightKg: Double,
    val reps: Int,
    val rir: Double? = null,
    val isWarmup: Boolean = false,
    val timestamp: Long
)

@Entity(tableName = "chat_message", indices = [Index("timestamp")])
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,               // user | assistant
    val content: String,
    val timestamp: Long
)

/** One row per day of wearable data pulled from Health Connect (Zepp writes here). */
@Entity(tableName = "health_daily")
data class HealthDaily(
    @PrimaryKey val localDate: String,
    val steps: Long? = null,
    val activeKcal: Double? = null,
    val totalKcal: Double? = null,
    val restingHr: Long? = null,
    val avgHr: Long? = null,
    val sleepMinutes: Long? = null,
    val exerciseMinutes: Long? = null,
    val distanceMeters: Double? = null,
    val syncedAt: Long
)

/** Cached barcode lookups so a rescan of the same product is instant and offline. */
@Entity(tableName = "food_cache")
data class CachedFood(
    @PrimaryKey val barcode: String,
    val name: String,
    val brand: String?,
    val servingDesc: String,
    val kcalPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    val fiberPer100g: Double,
    val servingSizeG: Double?,
    val cachedAt: Long
)

package com.laksh.forge.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema v2. Every v1 column is unchanged so Room's AutoMigration(1 -> 2) can carry an
// existing install forward without data loss. Columns added in v2 are nullable or carry a
// SQL default - never change that, or the auto-migration stops being possible.

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
    val source: String,              // PHOTO | BARCODE | MANUAL | FAVOURITE | LABEL
    val mealSlot: String,            // BREAKFAST | LUNCH | DINNER | SNACK
    val photoPath: String? = null,
    /** The model's own confidence in a photo estimate: HIGH | MEDIUM | LOW. */
    val confidence: String? = null,
    /** What the model said it was hedging on - shown verbatim in the entry detail. */
    val estimateNote: String? = null,
    /** Pre-safety-margin value, so the user can see how much padding was applied. */
    val rawKcal: Double? = null,
    val barcode: String? = null,
    // ---- v2
    /** "provider/model" that produced an AI estimate, for auditing estimate quality later. */
    val estimator: String? = null
)

@Entity(tableName = "weight_entry", indices = [Index("timestamp"), Index(value = ["localDate"])])
data class WeightEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val localDate: String,
    val kg: Double,
    val bodyFatPct: Double? = null,
    val note: String? = null,
    val source: String = "MANUAL"   // MANUAL | HEALTH_CONNECT | WHOOP | APPLE_HEALTH
)

@Entity(tableName = "program")
data class Program(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val author: String? = null,
    val sourceFile: String? = null,
    val notes: String? = null,
    val createdAt: Long,
    val isActive: Boolean = false,
    // ---- v2
    /** Id of the bundled library template this was adopted from; null for PDF imports. */
    val libraryId: String? = null,
    val category: String? = null,       // STRENGTH | POWERBUILDING | HYPERTROPHY | GENERAL
    val level: String? = null,          // BEGINNER | INTERMEDIATE | ADVANCED
    val daysPerWeek: Int? = null,
    val schedule: String? = null,
    /** Serialized progression rule (see domain/program). Null = log-only, no prescriptions. */
    val progressionJson: String? = null,
    /** Serialized week-by-week overrides for wave programs (5/3/1, Candito). */
    val weeksJson: String? = null
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
    val substitutions: String? = null,
    // ---- v2
    /** Canonical exercise id from the bundled catalog, when the name resolves to one. */
    val exerciseId: String? = null,
    /** Per-set scheme for percentage programs: [{"reps":5,"percent":0.65,"of":"TM","amrap":false}]. */
    val setSchemeJson: String? = null,
    val intensityType: String? = null,  // NONE | RPE | RIR | PERCENT_1RM | PERCENT_TM
    val intensityValue: Double? = null,
    @ColumnInfo(defaultValue = "0") val lastSetAmrap: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isOptional: Boolean = false
)

@Entity(tableName = "workout_session", indices = [Index("startedAt"), Index("localDate")])
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val programDayId: Long? = null,
    val title: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val localDate: String,
    val notes: String? = null,
    // ---- v2
    val programId: Long? = null,
    /** 1-based week within a wave program, so the right week's prescription is shown. */
    val weekIndex: Int? = null
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
    val timestamp: Long,
    // ---- v2
    val exerciseId: String? = null,
    /** What was prescribed, so progression can tell a hit from a miss. */
    val targetReps: Int? = null,
    val targetWeightKg: Double? = null,
    val rpe: Double? = null
)

@Entity(tableName = "chat_message", indices = [Index("timestamp")])
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,               // user | assistant
    val content: String,
    val timestamp: Long
)

/** One row per day of wearable data, merged across every connected source. */
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
    val syncedAt: Long,
    // ---- v2
    /** Overnight HRV, RMSSD in milliseconds. */
    val hrvMs: Double? = null,
    val vo2max: Double? = null,
    val spo2Pct: Double? = null,
    val respiratoryRate: Double? = null,
    /** Skin temperature deviation from the wearer's baseline, degrees C. */
    val skinTempDeltaC: Double? = null,
    val bodyFatPct: Double? = null,
    val leanMassKg: Double? = null,
    /** Vendor recovery score (e.g. WHOOP 0-100), stored as reported - never re-derived. */
    val recoveryScore: Double? = null,
    /** Vendor strain/load score as reported. */
    val strain: Double? = null,
    /** Comma-separated data origins (package names or vendor ids) that contributed. */
    val sources: String? = null
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

// ============================================================ v2 tables

/** Tape-measure and body-fat readings. Any field may be missing on a given day. */
@Entity(tableName = "body_measurement", indices = [Index("timestamp"), Index("localDate")])
data class BodyMeasurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val localDate: String,
    val waistCm: Double? = null,
    val neckCm: Double? = null,
    val hipsCm: Double? = null,
    val chestCm: Double? = null,
    val armCm: Double? = null,
    val thighCm: Double? = null,
    val calfCm: Double? = null,
    val bodyFatPct: Double? = null,
    /** How bodyFatPct was obtained: NAVY | VISUAL_AI | SCALE | DEXA | MANUAL. */
    val bodyFatMethod: String? = null,
    val note: String? = null
)

/**
 * A progress photo. The image lives only in app-private storage (never backed up, never
 * uploaded except transiently for an analysis the user explicitly starts).
 */
@Entity(tableName = "progress_photo", indices = [Index("timestamp"), Index("localDate")])
data class ProgressPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val localDate: String,
    val path: String,
    val pose: String,                   // FRONT | SIDE | BACK | OTHER
    val weightKg: Double? = null,
    val note: String? = null
)

/** A diet/training phase recommendation (from onboarding or a later re-plan). */
@Entity(tableName = "phase_plan", indices = [Index("createdAt")])
data class PhasePlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** CUT | LEAN_GAIN | MAINGAIN | MAINTAIN | RECOMP | REVERSE_DIET | DIET_BREAK. */
    val phase: String,
    val startDate: String,
    /** When to re-assess (usually 4-8 weeks out). */
    val reviewDate: String? = null,
    val endDate: String? = null,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int,
    val rateKgPerWeek: Double,
    val stepsTarget: Int? = null,
    /** Plain-language reasoning shown to the user. */
    val rationale: String,
    /** The full structured plan (training recommendation, check-in cadence, notes). */
    val planJson: String,
    val bodyFatEstimate: Double? = null,
    /** AI | RULES | MANUAL - RULES when no AI key or the AI result failed the guardrails. */
    val source: String,
    val isActive: Boolean = false,
    /** Guardrail adjustments that were applied, as a plain-language list. */
    val guardrailNotes: String? = null
)

/** Training max per lift for percentage-based programs (5/3/1, nSuns, Candito). */
@Entity(tableName = "training_max")
data class TrainingMax(
    @PrimaryKey val exerciseKey: String,   // canonical exercise id, or lower-cased name
    val kg: Double,
    val updatedAt: Long,
    /** MANUAL | ESTIMATED_1RM | AMRAP_PROGRESSION. */
    val source: String
)

/** A weekly check-in: what happened, how it felt, and what changed as a result. */
@Entity(tableName = "weekly_checkin", indices = [Index("weekStart")])
data class WeeklyCheckin(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: String,
    val createdAt: Long,
    val avgWeightKg: Double? = null,
    val weightChangeKg: Double? = null,
    val avgKcal: Double? = null,
    /** Share of days with food logged, 0..1. */
    val adherence: Double? = null,
    val avgSleepMinutes: Double? = null,
    val avgSteps: Double? = null,
    /** 1-5 self ratings; null when skipped. */
    val energy: Int? = null,
    val hunger: Int? = null,
    val performance: Int? = null,
    val notes: String? = null,
    /** Change to daily calories decided at this check-in (can be 0). */
    val adjustmentKcal: Int = 0,
    val summary: String = ""
)

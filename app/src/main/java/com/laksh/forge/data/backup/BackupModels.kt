package com.laksh.forge.data.backup

import com.laksh.forge.data.db.BodyMeasurement
import com.laksh.forge.data.db.ChatMessage
import com.laksh.forge.data.db.FoodEntry
import com.laksh.forge.data.db.HealthDaily
import com.laksh.forge.data.db.PhasePlan
import com.laksh.forge.data.db.Program
import com.laksh.forge.data.db.ProgramDay
import com.laksh.forge.data.db.ProgramExercise
import com.laksh.forge.data.db.SetLog
import com.laksh.forge.data.db.TrainingMax
import com.laksh.forge.data.db.WeeklyCheckin
import com.laksh.forge.data.db.WeightEntry
import com.laksh.forge.data.db.WorkoutSession
import kotlinx.serialization.Serializable

/*
 * The on-disk backup format. These mirror the Room entities on purpose instead of annotating
 * the entities themselves: the file format must stay readable after the schema moves on, so
 * every field has a default and unknown keys are ignored when reading. Bump [ForgeBackup.version]
 * only for a breaking change; adding a field with a default is not one.
 *
 * Deliberately NOT in a backup: API keys and other secrets, meal-photo and progress-photo
 * files (they stay in app-private storage, per the privacy rules), and the barcode cache.
 */

@Serializable
data class ForgeBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String? = null,
    val food: List<FoodDto> = emptyList(),
    val weights: List<WeightDto> = emptyList(),
    val measurements: List<MeasurementDto> = emptyList(),
    val sessions: List<SessionDto> = emptyList(),
    val sets: List<SetDto> = emptyList(),
    val health: List<HealthDto> = emptyList(),
    val plans: List<PlanDto> = emptyList(),
    val checkins: List<CheckinDto> = emptyList(),
    val programs: List<ProgramDto> = emptyList(),
    val programDays: List<ProgramDayDto> = emptyList(),
    val programExercises: List<ProgramExerciseDto> = emptyList(),
    val trainingMaxes: List<TrainingMaxDto> = emptyList(),
    val chat: List<ChatDto> = emptyList()
) {
    companion object {
        const val FORMAT = "forge-backup"
        const val VERSION = 1
    }
}

@Serializable
data class FoodDto(
    val id: Long = 0, val timestamp: Long = 0, val localDate: String = "", val name: String = "",
    val brand: String? = null, val servingDesc: String = "", val quantity: Double = 1.0,
    val kcal: Double = 0.0, val proteinG: Double = 0.0, val carbsG: Double = 0.0, val fatG: Double = 0.0,
    val fiberG: Double = 0.0, val source: String = "MANUAL", val mealSlot: String = "SNACK",
    val confidence: String? = null, val estimateNote: String? = null, val rawKcal: Double? = null,
    val barcode: String? = null, val estimator: String? = null
)

@Serializable
data class WeightDto(
    val id: Long = 0, val timestamp: Long = 0, val localDate: String = "", val kg: Double = 0.0,
    val bodyFatPct: Double? = null, val note: String? = null, val source: String = "MANUAL"
)

@Serializable
data class MeasurementDto(
    val id: Long = 0, val timestamp: Long = 0, val localDate: String = "",
    val waistCm: Double? = null, val neckCm: Double? = null, val hipsCm: Double? = null,
    val chestCm: Double? = null, val armCm: Double? = null, val thighCm: Double? = null,
    val calfCm: Double? = null, val bodyFatPct: Double? = null, val bodyFatMethod: String? = null,
    val note: String? = null
)

@Serializable
data class SessionDto(
    val id: Long = 0, val programDayId: Long? = null, val title: String = "", val startedAt: Long = 0,
    val endedAt: Long? = null, val localDate: String = "", val notes: String? = null,
    val programId: Long? = null, val weekIndex: Int? = null
)

@Serializable
data class SetDto(
    val id: Long = 0, val sessionId: Long = 0, val exerciseName: String = "", val setIdx: Int = 0,
    val weightKg: Double = 0.0, val reps: Int = 0, val rir: Double? = null, val isWarmup: Boolean = false,
    val timestamp: Long = 0, val exerciseId: String? = null, val targetReps: Int? = null,
    val targetWeightKg: Double? = null, val rpe: Double? = null
)

@Serializable
data class HealthDto(
    val localDate: String = "", val steps: Long? = null, val activeKcal: Double? = null,
    val totalKcal: Double? = null, val restingHr: Long? = null, val avgHr: Long? = null,
    val sleepMinutes: Long? = null, val exerciseMinutes: Long? = null, val distanceMeters: Double? = null,
    val syncedAt: Long = 0, val hrvMs: Double? = null, val vo2max: Double? = null, val spo2Pct: Double? = null,
    val respiratoryRate: Double? = null, val skinTempDeltaC: Double? = null, val bodyFatPct: Double? = null,
    val leanMassKg: Double? = null, val recoveryScore: Double? = null, val strain: Double? = null,
    val sources: String? = null
)

@Serializable
data class PlanDto(
    val id: Long = 0, val createdAt: Long = 0, val phase: String = "", val startDate: String = "",
    val reviewDate: String? = null, val endDate: String? = null, val kcal: Int = 0, val proteinG: Int = 0,
    val carbsG: Int = 0, val fatG: Int = 0, val fiberG: Int = 0, val rateKgPerWeek: Double = 0.0,
    val stepsTarget: Int? = null, val rationale: String = "", val planJson: String = "{}",
    val bodyFatEstimate: Double? = null, val source: String = "MANUAL", val isActive: Boolean = false,
    val guardrailNotes: String? = null
)

@Serializable
data class CheckinDto(
    val id: Long = 0, val weekStart: String = "", val createdAt: Long = 0, val avgWeightKg: Double? = null,
    val weightChangeKg: Double? = null, val avgKcal: Double? = null, val adherence: Double? = null,
    val avgSleepMinutes: Double? = null, val avgSteps: Double? = null, val energy: Int? = null,
    val hunger: Int? = null, val performance: Int? = null, val notes: String? = null,
    val adjustmentKcal: Int = 0, val summary: String = ""
)

@Serializable
data class ProgramDto(
    val id: Long = 0, val name: String = "", val author: String? = null, val sourceFile: String? = null,
    val notes: String? = null, val createdAt: Long = 0, val isActive: Boolean = false,
    val libraryId: String? = null, val category: String? = null, val level: String? = null,
    val daysPerWeek: Int? = null, val schedule: String? = null, val progressionJson: String? = null,
    val weeksJson: String? = null
)

@Serializable
data class ProgramDayDto(
    val id: Long = 0, val programId: Long = 0, val name: String = "", val orderIdx: Int = 0,
    val focus: String? = null
)

@Serializable
data class ProgramExerciseDto(
    val id: Long = 0, val dayId: Long = 0, val name: String = "", val orderIdx: Int = 0, val sets: Int = 0,
    val repLow: Int = 0, val repHigh: Int = 0, val rirTarget: Double? = null, val restSeconds: Int? = null,
    val notes: String? = null, val substitutions: String? = null, val exerciseId: String? = null,
    val setSchemeJson: String? = null, val intensityType: String? = null, val intensityValue: Double? = null,
    val lastSetAmrap: Boolean = false, val isOptional: Boolean = false
)

@Serializable
data class TrainingMaxDto(
    val exerciseKey: String = "", val kg: Double = 0.0, val updatedAt: Long = 0, val source: String = "MANUAL"
)

@Serializable
data class ChatDto(val id: Long = 0, val role: String = "", val content: String = "", val timestamp: Long = 0)

// ====================================================================== entity -> dto

fun FoodEntry.toDto() = FoodDto(
    id, timestamp, localDate, name, brand, servingDesc, quantity, kcal, proteinG, carbsG, fatG, fiberG,
    source, mealSlot, confidence, estimateNote, rawKcal, barcode, estimator
)

fun WeightEntry.toDto() = WeightDto(id, timestamp, localDate, kg, bodyFatPct, note, source)

fun BodyMeasurement.toDto() = MeasurementDto(
    id, timestamp, localDate, waistCm, neckCm, hipsCm, chestCm, armCm, thighCm, calfCm,
    bodyFatPct, bodyFatMethod, note
)

fun WorkoutSession.toDto() = SessionDto(id, programDayId, title, startedAt, endedAt, localDate, notes, programId, weekIndex)

fun SetLog.toDto() = SetDto(
    id, sessionId, exerciseName, setIdx, weightKg, reps, rir, isWarmup, timestamp, exerciseId,
    targetReps, targetWeightKg, rpe
)

fun HealthDaily.toDto() = HealthDto(
    localDate, steps, activeKcal, totalKcal, restingHr, avgHr, sleepMinutes, exerciseMinutes,
    distanceMeters, syncedAt, hrvMs, vo2max, spo2Pct, respiratoryRate, skinTempDeltaC, bodyFatPct,
    leanMassKg, recoveryScore, strain, sources
)

fun PhasePlan.toDto() = PlanDto(
    id, createdAt, phase, startDate, reviewDate, endDate, kcal, proteinG, carbsG, fatG, fiberG,
    rateKgPerWeek, stepsTarget, rationale, planJson, bodyFatEstimate, source, isActive, guardrailNotes
)

fun WeeklyCheckin.toDto() = CheckinDto(
    id, weekStart, createdAt, avgWeightKg, weightChangeKg, avgKcal, adherence, avgSleepMinutes,
    avgSteps, energy, hunger, performance, notes, adjustmentKcal, summary
)

fun Program.toDto() = ProgramDto(
    id, name, author, sourceFile, notes, createdAt, isActive, libraryId, category, level, daysPerWeek,
    schedule, progressionJson, weeksJson
)

fun ProgramDay.toDto() = ProgramDayDto(id, programId, name, orderIdx, focus)

fun ProgramExercise.toDto() = ProgramExerciseDto(
    id, dayId, name, orderIdx, sets, repLow, repHigh, rirTarget, restSeconds, notes, substitutions,
    exerciseId, setSchemeJson, intensityType, intensityValue, lastSetAmrap, isOptional
)

fun TrainingMax.toDto() = TrainingMaxDto(exerciseKey, kg, updatedAt, source)

fun ChatMessage.toDto() = ChatDto(id, role, content, timestamp)

// ====================================================================== dto -> entity (fresh ids)

fun FoodDto.toEntity() = FoodEntry(
    id = 0, timestamp = timestamp, localDate = localDate, name = name, brand = brand,
    servingDesc = servingDesc, quantity = quantity, kcal = kcal, proteinG = proteinG, carbsG = carbsG,
    fatG = fatG, fiberG = fiberG, source = source, mealSlot = mealSlot, photoPath = null,
    confidence = confidence, estimateNote = estimateNote, rawKcal = rawKcal, barcode = barcode,
    estimator = estimator
)

fun WeightDto.toEntity() = WeightEntry(
    id = 0, timestamp = timestamp, localDate = localDate, kg = kg, bodyFatPct = bodyFatPct,
    note = note, source = source
)

fun MeasurementDto.toEntity() = BodyMeasurement(
    id = 0, timestamp = timestamp, localDate = localDate, waistCm = waistCm, neckCm = neckCm,
    hipsCm = hipsCm, chestCm = chestCm, armCm = armCm, thighCm = thighCm, calfCm = calfCm,
    bodyFatPct = bodyFatPct, bodyFatMethod = bodyFatMethod, note = note
)

fun SessionDto.toEntity(programDayId: Long?, programId: Long?) = WorkoutSession(
    id = 0, programDayId = programDayId, title = title, startedAt = startedAt, endedAt = endedAt,
    localDate = localDate, notes = notes, programId = programId, weekIndex = weekIndex
)

fun SetDto.toEntity(sessionId: Long) = SetLog(
    id = 0, sessionId = sessionId, exerciseName = exerciseName, setIdx = setIdx, weightKg = weightKg,
    reps = reps, rir = rir, isWarmup = isWarmup, timestamp = timestamp, exerciseId = exerciseId,
    targetReps = targetReps, targetWeightKg = targetWeightKg, rpe = rpe
)

fun HealthDto.toEntity() = HealthDaily(
    localDate = localDate, steps = steps, activeKcal = activeKcal, totalKcal = totalKcal,
    restingHr = restingHr, avgHr = avgHr, sleepMinutes = sleepMinutes, exerciseMinutes = exerciseMinutes,
    distanceMeters = distanceMeters, syncedAt = syncedAt, hrvMs = hrvMs, vo2max = vo2max,
    spo2Pct = spo2Pct, respiratoryRate = respiratoryRate, skinTempDeltaC = skinTempDeltaC,
    bodyFatPct = bodyFatPct, leanMassKg = leanMassKg, recoveryScore = recoveryScore, strain = strain,
    sources = sources
)

fun PlanDto.toEntity(active: Boolean) = PhasePlan(
    id = 0, createdAt = createdAt, phase = phase, startDate = startDate, reviewDate = reviewDate,
    endDate = endDate, kcal = kcal, proteinG = proteinG, carbsG = carbsG, fatG = fatG, fiberG = fiberG,
    rateKgPerWeek = rateKgPerWeek, stepsTarget = stepsTarget, rationale = rationale, planJson = planJson,
    bodyFatEstimate = bodyFatEstimate, source = source, isActive = active, guardrailNotes = guardrailNotes
)

fun CheckinDto.toEntity() = WeeklyCheckin(
    id = 0, weekStart = weekStart, createdAt = createdAt, avgWeightKg = avgWeightKg,
    weightChangeKg = weightChangeKg, avgKcal = avgKcal, adherence = adherence,
    avgSleepMinutes = avgSleepMinutes, avgSteps = avgSteps, energy = energy, hunger = hunger,
    performance = performance, notes = notes, adjustmentKcal = adjustmentKcal, summary = summary
)

fun ProgramDto.toEntity(active: Boolean) = Program(
    id = 0, name = name, author = author, sourceFile = sourceFile, notes = notes, createdAt = createdAt,
    isActive = active, libraryId = libraryId, category = category, level = level, daysPerWeek = daysPerWeek,
    schedule = schedule, progressionJson = progressionJson, weeksJson = weeksJson
)

fun ProgramDayDto.toEntity(programId: Long) = ProgramDay(
    id = 0, programId = programId, name = name, orderIdx = orderIdx, focus = focus
)

fun ProgramExerciseDto.toEntity(dayId: Long) = ProgramExercise(
    id = 0, dayId = dayId, name = name, orderIdx = orderIdx, sets = sets, repLow = repLow, repHigh = repHigh,
    rirTarget = rirTarget, restSeconds = restSeconds, notes = notes, substitutions = substitutions,
    exerciseId = exerciseId, setSchemeJson = setSchemeJson, intensityType = intensityType,
    intensityValue = intensityValue, lastSetAmrap = lastSetAmrap, isOptional = isOptional
)

fun TrainingMaxDto.toEntity() = TrainingMax(exerciseKey = exerciseKey, kg = kg, updatedAt = updatedAt, source = source)

fun ChatDto.toEntity() = ChatMessage(id = 0, role = role, content = content, timestamp = timestamp)

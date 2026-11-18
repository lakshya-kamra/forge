package com.laksh.forge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Row counts per table, for the storage summary and the "what will be exported" line. */
data class TableCounts(
    val food: Int,
    val weights: Int,
    val measurements: Int,
    val sessions: Int,
    val sets: Int,
    val programs: Int,
    val healthDays: Int,
    val progressPhotos: Int,
    val mealPhotos: Int
)

/**
 * Full-table reads and bulk writes for export, backup restore and third-party imports.
 * Owned by the data & backup feature.
 *
 * Restores always insert with id = 0 so rows get fresh primary keys; the backup layer remaps
 * foreign keys (session -> sets, program -> days -> exercises) itself. That keeps a restore
 * from ever overwriting a row that already exists on this device.
 */
@Dao
interface BackupDao {
    // ------------------------------------------------------------------ reads
    @Query("SELECT * FROM food_entry ORDER BY timestamp ASC") suspend fun allFood(): List<FoodEntry>
    @Query("SELECT * FROM weight_entry ORDER BY timestamp ASC") suspend fun allWeights(): List<WeightEntry>
    @Query("SELECT * FROM body_measurement ORDER BY timestamp ASC") suspend fun allMeasurements(): List<BodyMeasurement>
    @Query("SELECT * FROM workout_session ORDER BY startedAt ASC") suspend fun allSessions(): List<WorkoutSession>
    @Query("SELECT * FROM set_log ORDER BY timestamp ASC") suspend fun allSets(): List<SetLog>
    @Query("SELECT * FROM health_daily ORDER BY localDate ASC") suspend fun allHealth(): List<HealthDaily>
    @Query("SELECT * FROM phase_plan ORDER BY createdAt ASC") suspend fun allPlans(): List<PhasePlan>
    @Query("SELECT * FROM weekly_checkin ORDER BY weekStart ASC") suspend fun allCheckins(): List<WeeklyCheckin>
    @Query("SELECT * FROM program ORDER BY createdAt ASC") suspend fun allPrograms(): List<Program>
    @Query("SELECT * FROM program_day ORDER BY programId ASC, orderIdx ASC") suspend fun allProgramDays(): List<ProgramDay>
    @Query("SELECT * FROM program_exercise ORDER BY dayId ASC, orderIdx ASC") suspend fun allProgramExercises(): List<ProgramExercise>
    @Query("SELECT * FROM training_max ORDER BY exerciseKey ASC") suspend fun allTrainingMaxes(): List<TrainingMax>
    @Query("SELECT * FROM chat_message ORDER BY timestamp ASC") suspend fun allChat(): List<ChatMessage>

    @Query("SELECT path FROM progress_photo") suspend fun progressPhotoPaths(): List<String>
    @Query("SELECT photoPath FROM food_entry WHERE photoPath IS NOT NULL AND photoPath != ''")
    suspend fun mealPhotoPaths(): List<String>

    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM food_entry) AS food, " +
            "(SELECT COUNT(*) FROM weight_entry) AS weights, " +
            "(SELECT COUNT(*) FROM body_measurement) AS measurements, " +
            "(SELECT COUNT(*) FROM workout_session) AS sessions, " +
            "(SELECT COUNT(*) FROM set_log) AS sets, " +
            "(SELECT COUNT(*) FROM program) AS programs, " +
            "(SELECT COUNT(*) FROM health_daily) AS healthDays, " +
            "(SELECT COUNT(*) FROM progress_photo) AS progressPhotos, " +
            "(SELECT COUNT(*) FROM food_entry WHERE photoPath IS NOT NULL AND photoPath != '') AS mealPhotos"
    )
    suspend fun counts(): TableCounts

    // ------------------------------------------------------------------ writes
    @Insert suspend fun insertFood(items: List<FoodEntry>)
    @Insert suspend fun insertWeights(items: List<WeightEntry>)
    @Insert suspend fun insertMeasurements(items: List<BodyMeasurement>)
    @Insert suspend fun insertSession(item: WorkoutSession): Long
    @Insert suspend fun insertSets(items: List<SetLog>)
    @Insert suspend fun insertPlans(items: List<PhasePlan>)
    @Insert suspend fun insertCheckins(items: List<WeeklyCheckin>)
    @Insert suspend fun insertProgram(item: Program): Long
    @Insert suspend fun insertProgramDay(item: ProgramDay): Long
    @Insert suspend fun insertProgramExercises(items: List<ProgramExercise>)
    @Insert suspend fun insertChat(items: List<ChatMessage>)

    /** Keyed by date; a day already on this device wins over the backup's copy. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertHealthIfAbsent(items: List<HealthDaily>)

    /** Keyed by exercise; an existing training max wins over the backup's copy. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTrainingMaxesIfAbsent(items: List<TrainingMax>)

    /** Detach every meal photo from its entry after the files have been deleted. */
    @Query("UPDATE food_entry SET photoPath = NULL WHERE photoPath IS NOT NULL")
    suspend fun clearMealPhotoPaths(): Int
}

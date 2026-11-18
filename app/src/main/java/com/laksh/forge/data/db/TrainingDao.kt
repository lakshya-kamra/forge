package com.laksh.forge.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Training maxes and progression lookups. Owned by the program library feature. */
@Dao
interface TrainingDao {
    @Upsert suspend fun upsertTrainingMax(tm: TrainingMax)

    @Query("SELECT * FROM training_max WHERE exerciseKey = :key")
    suspend fun trainingMax(key: String): TrainingMax?

    @Query("SELECT * FROM training_max ORDER BY exerciseKey ASC")
    fun observeTrainingMaxes(): Flow<List<TrainingMax>>

    @Query("SELECT * FROM training_max")
    suspend fun trainingMaxes(): List<TrainingMax>

    /** Working sets of a lift across all sessions, newest first - the input to progression. */
    @Query(
        "SELECT * FROM set_log WHERE isWarmup = 0 AND " +
            "(exerciseId = :exerciseId OR (exerciseId IS NULL AND exerciseName = :name)) " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun workingSets(exerciseId: String?, name: String, limit: Int = 60): List<SetLog>

    @Query("SELECT * FROM workout_session WHERE programId = :programId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun sessionsForProgram(programId: Long, limit: Int = 30): List<WorkoutSession>

    @Query("SELECT * FROM set_log WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun setsForSession(sessionId: Long): List<SetLog>

    // ---------------------------------------------------------------- slot history

    /**
     * Working sets logged under one slot name (a program's exercise name), newest first.
     * Slot names are what keep GZCLP's T1 and T2 squat, or 5/3/1's main and FSL sets, apart.
     */
    @Query(
        "SELECT * FROM set_log WHERE isWarmup = 0 AND exerciseName = :name COLLATE NOCASE " +
            "AND sessionId != :excludeSession ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun slotSets(name: String, excludeSession: Long, limit: Int = 160): List<SetLog>

    @Query("SELECT * FROM workout_session WHERE id IN (:ids)")
    suspend fun sessionsByIds(ids: List<Long>): List<WorkoutSession>

    /** Finished sessions of a program, newest first - drives the next-day suggestion. */
    @Query(
        "SELECT * FROM workout_session WHERE programId = :programId AND endedAt IS NOT NULL " +
            "ORDER BY startedAt DESC LIMIT :limit"
    )
    fun observeFinishedForProgram(programId: Long, limit: Int = 12): Flow<List<WorkoutSession>>

    /** Every earlier working set of a lift, for personal-record baselines. */
    @Query(
        "SELECT * FROM set_log WHERE isWarmup = 0 AND sessionId != :excludeSession AND " +
            "(exerciseId = :exerciseId OR exerciseName = :name COLLATE NOCASE)"
    )
    suspend fun allWorkingSets(exerciseId: String?, name: String, excludeSession: Long): List<SetLog>

    // ---------------------------------------------------------------- program bookkeeping

    /** Written separately from the entity so a stale copy can never flip isActive back. */
    @Query("UPDATE program SET progressionJson = :json WHERE id = :programId")
    suspend fun setProgressionJson(programId: Long, json: String)

    /** Fill in the catalog id of a PDF-imported exercise once its name resolves. */
    @Query("UPDATE program_exercise SET exerciseId = :exerciseId WHERE id = :rowId")
    suspend fun setExerciseId(rowId: Long, exerciseId: String)

    @Query(
        "SELECT pe.* FROM program_exercise pe JOIN program_day pd ON pe.dayId = pd.id " +
            "WHERE pd.programId = :programId ORDER BY pd.orderIdx ASC, pe.orderIdx ASC"
    )
    suspend fun exercisesForProgram(programId: Long): List<ProgramExercise>
}

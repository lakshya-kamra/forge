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
}

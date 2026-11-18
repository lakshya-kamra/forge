package com.laksh.forge.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A working set joined with its session date, for weekly volume analysis. */
data class DatedSet(
    val localDate: String,
    val exerciseName: String,
    val exerciseId: String?,
    val weightKg: Double,
    val reps: Int,
    val rir: Double?,
    val rpe: Double?
)

/** Read-only analysis queries for guardrails and insights. Owned by the guardrails feature. */
@Dao
interface InsightsDao {
    @Query(
        "SELECT s.localDate AS localDate, l.exerciseName AS exerciseName, l.exerciseId AS exerciseId, " +
            "l.weightKg AS weightKg, l.reps AS reps, l.rir AS rir, l.rpe AS rpe " +
            "FROM set_log l JOIN workout_session s ON l.sessionId = s.id " +
            "WHERE l.isWarmup = 0 AND s.localDate BETWEEN :from AND :to ORDER BY s.localDate ASC"
    )
    fun observeWorkingSets(from: String, to: String): Flow<List<DatedSet>>

    @Query(
        "SELECT s.localDate AS localDate, l.exerciseName AS exerciseName, l.exerciseId AS exerciseId, " +
            "l.weightKg AS weightKg, l.reps AS reps, l.rir AS rir, l.rpe AS rpe " +
            "FROM set_log l JOIN workout_session s ON l.sessionId = s.id " +
            "WHERE l.isWarmup = 0 AND s.localDate BETWEEN :from AND :to ORDER BY s.localDate ASC"
    )
    suspend fun workingSets(from: String, to: String): List<DatedSet>
}

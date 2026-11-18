package com.laksh.forge.data.db

import androidx.room.Dao
import androidx.room.Query

/** Full-table reads for export/backup. Owned by the data & backup feature. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM food_entry ORDER BY timestamp ASC") suspend fun allFood(): List<FoodEntry>
    @Query("SELECT * FROM weight_entry ORDER BY timestamp ASC") suspend fun allWeights(): List<WeightEntry>
    @Query("SELECT * FROM body_measurement ORDER BY timestamp ASC") suspend fun allMeasurements(): List<BodyMeasurement>
    @Query("SELECT * FROM workout_session ORDER BY startedAt ASC") suspend fun allSessions(): List<WorkoutSession>
    @Query("SELECT * FROM set_log ORDER BY timestamp ASC") suspend fun allSets(): List<SetLog>
    @Query("SELECT * FROM health_daily ORDER BY localDate ASC") suspend fun allHealth(): List<HealthDaily>
    @Query("SELECT * FROM phase_plan ORDER BY createdAt ASC") suspend fun allPlans(): List<PhasePlan>
    @Query("SELECT * FROM weekly_checkin ORDER BY weekStart ASC") suspend fun allCheckins(): List<WeeklyCheckin>
}

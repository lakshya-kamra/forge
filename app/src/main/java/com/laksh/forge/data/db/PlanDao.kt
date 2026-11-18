package com.laksh.forge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Phase plans and weekly check-ins. Owned by the onboarding/plan feature. */
@Dao
interface PlanDao {
    @Insert suspend fun insertPlan(p: PhasePlan): Long
    @Update suspend fun updatePlan(p: PhasePlan)

    @Query("SELECT * FROM phase_plan WHERE isActive = 1 ORDER BY createdAt DESC LIMIT 1")
    fun observeActivePlan(): Flow<PhasePlan?>

    @Query("SELECT * FROM phase_plan WHERE isActive = 1 ORDER BY createdAt DESC LIMIT 1")
    suspend fun activePlan(): PhasePlan?

    @Query("SELECT * FROM phase_plan ORDER BY createdAt DESC")
    fun observePlans(): Flow<List<PhasePlan>>

    @Query("UPDATE phase_plan SET isActive = 0")
    suspend fun clearActivePlan()

    /** Make [plan] the single active plan. */
    @Transaction
    suspend fun activate(plan: PhasePlan): Long {
        clearActivePlan()
        return insertPlan(plan.copy(id = 0, isActive = true))
    }

    @Insert suspend fun insertCheckin(c: WeeklyCheckin): Long

    @Query("SELECT * FROM weekly_checkin ORDER BY weekStart DESC")
    fun observeCheckins(): Flow<List<WeeklyCheckin>>

    @Query("SELECT * FROM weekly_checkin ORDER BY weekStart DESC LIMIT 1")
    suspend fun latestCheckin(): WeeklyCheckin?
}

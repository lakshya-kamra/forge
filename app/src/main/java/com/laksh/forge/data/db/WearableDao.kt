package com.laksh.forge.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Wearable data across sources. Owned by the wearables feature. */
@Dao
interface WearableDao {
    @Upsert suspend fun upsert(d: HealthDaily)
    @Upsert suspend fun upsertAll(d: List<HealthDaily>)

    @Query("SELECT * FROM health_daily WHERE localDate = :date")
    suspend fun day(date: String): HealthDaily?

    @Query("SELECT * FROM health_daily WHERE localDate BETWEEN :from AND :to ORDER BY localDate ASC")
    suspend fun range(from: String, to: String): List<HealthDaily>

    @Query("SELECT * FROM health_daily WHERE localDate BETWEEN :from AND :to ORDER BY localDate ASC")
    fun observeRange(from: String, to: String): Flow<List<HealthDaily>>

    @Query("SELECT * FROM health_daily ORDER BY localDate DESC LIMIT 1")
    fun observeLatest(): Flow<HealthDaily?>
}

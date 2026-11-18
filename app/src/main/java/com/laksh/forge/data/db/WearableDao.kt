package com.laksh.forge.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Wearable data across sources. Owned by the wearables feature.
 *
 * Writes should go through data/wearables (WearableMerger), which rebuilds each row from every
 * source's layer; upserting a row built from one source alone would wipe the others' fields.
 */
@Dao
interface WearableDao {
    @Upsert suspend fun upsert(d: HealthDaily)
    @Upsert suspend fun upsertAll(d: List<HealthDaily>)

    @Query("SELECT * FROM health_daily WHERE localDate = :date")
    suspend fun day(date: String): HealthDaily?

    /** Callers keep [dates] under SQLite's bound-variable limit (WearableMerger chunks by 300). */
    @Query("SELECT * FROM health_daily WHERE localDate IN (:dates)")
    suspend fun days(dates: List<String>): List<HealthDaily>

    @Query("SELECT * FROM health_daily WHERE localDate BETWEEN :from AND :to ORDER BY localDate ASC")
    suspend fun range(from: String, to: String): List<HealthDaily>

    @Query("SELECT * FROM health_daily WHERE localDate BETWEEN :from AND :to ORDER BY localDate ASC")
    fun observeRange(from: String, to: String): Flow<List<HealthDaily>>

    @Query("SELECT * FROM health_daily ORDER BY localDate DESC LIMIT 1")
    fun observeLatest(): Flow<HealthDaily?>

    @Query("SELECT COUNT(*) FROM health_daily")
    fun observeCount(): Flow<Int>
}

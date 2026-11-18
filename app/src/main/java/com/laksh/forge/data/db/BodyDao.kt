package com.laksh.forge.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Body measurements and progress photos. Owned by the onboarding/progress feature. */
@Dao
interface BodyDao {
    @Insert suspend fun insertMeasurement(m: BodyMeasurement): Long
    @Update suspend fun updateMeasurement(m: BodyMeasurement)
    @Delete suspend fun deleteMeasurement(m: BodyMeasurement)

    @Query("SELECT * FROM body_measurement ORDER BY timestamp DESC")
    fun observeMeasurements(): Flow<List<BodyMeasurement>>

    @Query("SELECT * FROM body_measurement ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestMeasurement(): Flow<BodyMeasurement?>

    @Query("SELECT * FROM body_measurement ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestMeasurement(): BodyMeasurement?

    @Insert suspend fun insertPhoto(p: ProgressPhoto): Long
    @Delete suspend fun deletePhoto(p: ProgressPhoto)

    @Query("SELECT * FROM progress_photo ORDER BY timestamp DESC")
    fun observePhotos(): Flow<List<ProgressPhoto>>

    @Query("SELECT * FROM progress_photo WHERE id = :id")
    suspend fun photo(id: Long): ProgressPhoto?
}

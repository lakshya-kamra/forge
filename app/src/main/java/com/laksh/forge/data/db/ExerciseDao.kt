package com.laksh.forge.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Per-exercise history for the exercise detail screen. Owned by the exercise feature. */
@Dao
interface ExerciseDao {
    @Query(
        "SELECT * FROM set_log WHERE isWarmup = 0 AND " +
            "(exerciseId = :exerciseId OR exerciseName = :name COLLATE NOCASE) " +
            "ORDER BY timestamp ASC"
    )
    fun observeHistory(exerciseId: String?, name: String): Flow<List<SetLog>>

    @Query("SELECT DISTINCT exerciseName FROM set_log ORDER BY exerciseName COLLATE NOCASE ASC")
    fun observeLoggedNames(): Flow<List<String>>
}

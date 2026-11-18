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

    /**
     * Working sets logged under the canonical id or under any of [names]. Program PDFs and
     * hand-typed logs name the same lift many ways ("BB Bench", "Bench press"), and v1 logs
     * carry no id at all, so the detail screen resolves every logged name first and asks for
     * all the spellings that map to this exercise.
     */
    @Query(
        "SELECT * FROM set_log WHERE isWarmup = 0 AND " +
            "(exerciseId = :exerciseId OR exerciseName COLLATE NOCASE IN (:names)) " +
            "ORDER BY timestamp ASC"
    )
    fun observeHistoryForNames(exerciseId: String?, names: List<String>): Flow<List<SetLog>>

    @Query("SELECT DISTINCT exerciseName FROM set_log ORDER BY exerciseName COLLATE NOCASE ASC")
    fun observeLoggedNames(): Flow<List<String>>
}

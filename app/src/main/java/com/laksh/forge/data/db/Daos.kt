package com.laksh.forge.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Per-day nutrition rollup, computed in SQL so the UI never sums lists itself. */
data class DayTotals(
    val localDate: String,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val items: Int
)

data class ExerciseBest(
    val exerciseName: String,
    val bestWeight: Double,
    val reps: Int,
    val timestamp: Long
)

data class VolumePoint(val localDate: String, val volume: Double, val sets: Int)

@Dao
interface FoodDao {
    @Insert suspend fun insert(entry: FoodEntry): Long
    @Update suspend fun update(entry: FoodEntry)
    @Delete suspend fun delete(entry: FoodEntry)

    @Query("SELECT * FROM food_entry WHERE localDate = :date ORDER BY timestamp ASC")
    fun observeDay(date: String): Flow<List<FoodEntry>>

    @Query("SELECT * FROM food_entry WHERE id = :id")
    suspend fun byId(id: Long): FoodEntry?

    @Query(
        "SELECT :date AS localDate, " +
            "COALESCE(SUM(kcal), 0) AS kcal, " +
            "COALESCE(SUM(proteinG), 0) AS proteinG, " +
            "COALESCE(SUM(carbsG), 0) AS carbsG, " +
            "COALESCE(SUM(fatG), 0) AS fatG, " +
            "COALESCE(SUM(fiberG), 0) AS fiberG, " +
            "COUNT(*) AS items " +
            "FROM food_entry WHERE localDate = :date"
    )
    fun observeTotals(date: String): Flow<DayTotals?>

    @Query(
        "SELECT localDate, " +
            "COALESCE(SUM(kcal), 0) AS kcal, " +
            "COALESCE(SUM(proteinG), 0) AS proteinG, " +
            "COALESCE(SUM(carbsG), 0) AS carbsG, " +
            "COALESCE(SUM(fatG), 0) AS fatG, " +
            "COALESCE(SUM(fiberG), 0) AS fiberG, " +
            "COUNT(*) AS items " +
            "FROM food_entry WHERE localDate BETWEEN :from AND :to " +
            "GROUP BY localDate ORDER BY localDate ASC"
    )
    fun observeTotalsRange(from: String, to: String): Flow<List<DayTotals>>

    /** Distinct recent foods for the quick-add list, most-recently-used first. */
    @Query(
        "SELECT * FROM food_entry WHERE id IN " +
            "(SELECT MAX(id) FROM food_entry GROUP BY name, COALESCE(brand, '')) " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int = 40): Flow<List<FoodEntry>>

    @Query(
        "SELECT * FROM food_entry WHERE name LIKE '%' || :q || '%' OR brand LIKE '%' || :q || '%' " +
            "GROUP BY name, COALESCE(brand, '') ORDER BY timestamp DESC LIMIT 30"
    )
    suspend fun search(q: String): List<FoodEntry>
}

@Dao
interface WeightDao {
    @Insert suspend fun insert(entry: WeightEntry): Long
    @Delete suspend fun delete(entry: WeightEntry)
    @Update suspend fun update(entry: WeightEntry)

    @Query("SELECT * FROM weight_entry ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<WeightEntry>>

    @Query("SELECT * FROM weight_entry ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<WeightEntry?>

    @Query("SELECT * FROM weight_entry WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun observeSince(since: Long): Flow<List<WeightEntry>>

    @Query("SELECT * FROM weight_entry WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<WeightEntry>

    @Query("SELECT COUNT(*) FROM weight_entry WHERE localDate = :date")
    suspend fun countOn(date: String): Int
}

@Dao
interface ProgramDao {
    @Insert suspend fun insertProgram(p: Program): Long
    @Insert suspend fun insertDay(d: ProgramDay): Long
    @Insert suspend fun insertExercise(e: ProgramExercise): Long
    @Update suspend fun updateProgram(p: Program)
    @Delete suspend fun deleteProgram(p: Program)

    @Query("SELECT * FROM program ORDER BY createdAt DESC")
    fun observePrograms(): Flow<List<Program>>

    @Query("SELECT * FROM program WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<Program?>

    @Query("UPDATE program SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE program SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: Long)

    @Query("SELECT * FROM program_day WHERE programId = :programId ORDER BY orderIdx ASC")
    fun observeDays(programId: Long): Flow<List<ProgramDay>>

    @Query("SELECT * FROM program_day WHERE programId = :programId ORDER BY orderIdx ASC")
    suspend fun days(programId: Long): List<ProgramDay>

    @Query("SELECT * FROM program_exercise WHERE dayId = :dayId ORDER BY orderIdx ASC")
    fun observeExercises(dayId: Long): Flow<List<ProgramExercise>>

    @Query("SELECT * FROM program_exercise WHERE dayId = :dayId ORDER BY orderIdx ASC")
    suspend fun exercises(dayId: Long): List<ProgramExercise>

    @Query("SELECT * FROM program_day WHERE id = :id")
    suspend fun day(id: Long): ProgramDay?

    @Query("SELECT * FROM program WHERE id = :id")
    suspend fun program(id: Long): Program?
}

@Dao
interface WorkoutDao {
    @Insert suspend fun insertSession(s: WorkoutSession): Long
    @Update suspend fun updateSession(s: WorkoutSession)
    @Delete suspend fun deleteSession(s: WorkoutSession)
    @Insert suspend fun insertSet(s: SetLog): Long
    @Update suspend fun updateSet(s: SetLog)
    @Delete suspend fun deleteSet(s: SetLog)

    @Query("SELECT * FROM workout_session ORDER BY startedAt DESC LIMIT :limit")
    fun observeSessions(limit: Int = 60): Flow<List<WorkoutSession>>

    @Query("SELECT * FROM workout_session WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeOpenSession(): Flow<WorkoutSession?>

    @Query("SELECT * FROM workout_session WHERE id = :id")
    suspend fun session(id: Long): WorkoutSession?

    @Query("SELECT * FROM set_log WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeSets(sessionId: Long): Flow<List<SetLog>>

    /** Last time this lift was trained - drives the "last session" reference shown while logging. */
    @Query(
        "SELECT * FROM set_log WHERE exerciseName = :name AND isWarmup = 0 " +
            "AND sessionId != :excludeSession ORDER BY timestamp DESC LIMIT 12"
    )
    suspend fun recentSetsFor(name: String, excludeSession: Long): List<SetLog>

    @Query(
        "SELECT exerciseName, MAX(weightKg) AS bestWeight, reps, timestamp " +
            "FROM set_log WHERE isWarmup = 0 GROUP BY exerciseName"
    )
    fun observeBests(): Flow<List<ExerciseBest>>

    @Query(
        "SELECT s.localDate AS localDate, " +
            "COALESCE(SUM(l.weightKg * l.reps), 0) AS volume, " +
            "COUNT(l.id) AS sets " +
            "FROM workout_session s JOIN set_log l ON l.sessionId = s.id " +
            "WHERE l.isWarmup = 0 AND s.localDate BETWEEN :from AND :to " +
            "GROUP BY s.localDate ORDER BY s.localDate ASC"
    )
    fun observeVolume(from: String, to: String): Flow<List<VolumePoint>>

    @Query("SELECT DISTINCT exerciseName FROM set_log ORDER BY exerciseName ASC")
    fun observeExerciseNames(): Flow<List<String>>

    @Query("SELECT * FROM set_log WHERE exerciseName = :name AND isWarmup = 0 ORDER BY timestamp ASC")
    fun observeHistoryFor(name: String): Flow<List<SetLog>>

    @Query("SELECT * FROM set_log WHERE isWarmup = 0 ORDER BY timestamp DESC LIMIT :n")
    suspend fun recentSets(n: Int): List<SetLog>
}

@Dao
interface ChatDao {
    @Insert suspend fun insert(m: ChatMessage): Long

    @Query("SELECT * FROM chat_message ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_message ORDER BY timestamp DESC LIMIT :n")
    suspend fun lastN(n: Int): List<ChatMessage>

    @Query("DELETE FROM chat_message")
    suspend fun clear()
}

@Dao
interface HealthDao {
    @Upsert suspend fun upsert(d: HealthDaily)
    @Upsert suspend fun upsertAll(d: List<HealthDaily>)

    @Query("SELECT * FROM health_daily WHERE localDate = :date")
    fun observeDay(date: String): Flow<HealthDaily?>

    @Query("SELECT * FROM health_daily WHERE localDate BETWEEN :from AND :to ORDER BY localDate ASC")
    fun observeRange(from: String, to: String): Flow<List<HealthDaily>>

    @Query("SELECT * FROM health_daily WHERE localDate BETWEEN :from AND :to ORDER BY localDate ASC")
    suspend fun range(from: String, to: String): List<HealthDaily>
}

@Dao
interface FoodCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(f: CachedFood)

    @Query("SELECT * FROM food_cache WHERE barcode = :barcode")
    suspend fun get(barcode: String): CachedFood?
}

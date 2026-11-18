package com.laksh.forge.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FoodEntry::class,
        WeightEntry::class,
        Program::class,
        ProgramDay::class,
        ProgramExercise::class,
        WorkoutSession::class,
        SetLog::class,
        ChatMessage::class,
        HealthDaily::class,
        CachedFood::class,
        // v2
        BodyMeasurement::class,
        ProgressPhoto::class,
        PhasePlan::class,
        TrainingMax::class,
        WeeklyCheckin::class
    ],
    version = 2,
    exportSchema = true,
    // v1 -> v2 only adds tables and nullable/defaulted columns, which Room migrates itself.
    // Never add fallbackToDestructiveMigration: it would silently wipe a user's logs.
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao
    abstract fun weightDao(): WeightDao
    abstract fun programDao(): ProgramDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun chatDao(): ChatDao
    abstract fun healthDao(): HealthDao
    abstract fun foodCacheDao(): FoodCacheDao

    // v2 - one DAO per feature area so each feature owns its own queries.
    abstract fun bodyDao(): BodyDao
    abstract fun planDao(): PlanDao
    abstract fun trainingDao(): TrainingDao
    abstract fun wearableDao(): WearableDao
    abstract fun insightsDao(): InsightsDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun backupDao(): BackupDao

    companion object {
        @Volatile private var instance: ForgeDatabase? = null

        fun get(context: Context): ForgeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ForgeDatabase::class.java,
                    "forge.db"
                ).build().also { instance = it }
            }
    }
}

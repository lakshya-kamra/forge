package com.laksh.forge.data.db

import android.content.Context
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
        CachedFood::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao
    abstract fun weightDao(): WeightDao
    abstract fun programDao(): ProgramDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun chatDao(): ChatDao
    abstract fun healthDao(): HealthDao
    abstract fun foodCacheDao(): FoodCacheDao

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

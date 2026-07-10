package com.dheyab.qiyas.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

const val DEFAULT_PROFILE_ID = 1L

@Database(
    entities = [ProfileEntity::class, ReadingEntity::class, WeeklyReportEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class QiyasDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun readingDao(): ReadingDao
    abstract fun weeklyReportDao(): WeeklyReportDao

    /** Seeds the single default profile (Hard Rule 8: multi-profile schema, single-profile v0 UI). */
    class SeedCallback : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO profiles(id, name, created_at) " +
                    "VALUES($DEFAULT_PROFILE_ID, 'Default', ${System.currentTimeMillis()})"
            )
        }
    }
}

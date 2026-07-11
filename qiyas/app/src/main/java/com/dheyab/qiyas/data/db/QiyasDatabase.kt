package com.dheyab.qiyas.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

const val DEFAULT_PROFILE_ID = 1L

/** v1 → v2: photo attachments on readings. Destructive migrations are forbidden (spec §2). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE readings ADD COLUMN photo_path TEXT")
    }
}

@Database(
    entities = [ProfileEntity::class, ReadingEntity::class, WeeklyReportEntity::class],
    version = 2,
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

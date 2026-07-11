package com.dheyab.qiyas.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(profile: ProfileEntity): Long

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): ProfileEntity?
}

@Dao
interface ReadingDao {
    @Insert
    suspend fun insert(reading: ReadingEntity): Long

    @Update
    suspend fun update(reading: ReadingEntity)

    @Query("DELETE FROM readings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM readings WHERE id = :id")
    suspend fun getById(id: Long): ReadingEntity?

    @Query("SELECT * FROM readings WHERE profile_id = :profileId ORDER BY measured_at DESC")
    fun observeAll(profileId: Long): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM readings WHERE profile_id = :profileId AND type = :type ORDER BY measured_at DESC")
    fun observeByType(profileId: Long, type: String): Flow<List<ReadingEntity>>

    @Query(
        "SELECT * FROM readings WHERE profile_id = :profileId AND type = :type " +
            "ORDER BY measured_at DESC LIMIT 1"
    )
    fun observeLatestOfType(profileId: Long, type: String): Flow<ReadingEntity?>

    @Query(
        "SELECT * FROM readings WHERE profile_id = :profileId AND measured_at >= :fromInclusive " +
            "AND measured_at < :toExclusive ORDER BY measured_at ASC"
    )
    suspend fun readingsBetween(profileId: Long, fromInclusive: Long, toExclusive: Long): List<ReadingEntity>

    @Query(
        "SELECT * FROM readings WHERE profile_id = :profileId AND type = :type " +
            "AND measured_at >= :fromInclusive AND measured_at < :toExclusive ORDER BY measured_at ASC"
    )
    fun observeBetweenOfType(
        profileId: Long,
        type: String,
        fromInclusive: Long,
        toExclusive: Long,
    ): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM readings WHERE profile_id = :profileId ORDER BY measured_at ASC")
    suspend fun allForExport(profileId: Long): List<ReadingEntity>

    @Query(
        "SELECT COUNT(*) FROM readings WHERE profile_id = :profileId AND type = :type " +
            "AND measured_at >= :fromInclusive AND measured_at < :toExclusive"
    )
    fun observeCountBetweenOfType(
        profileId: Long,
        type: String,
        fromInclusive: Long,
        toExclusive: Long,
    ): Flow<Int>
}

@Dao
interface WeeklyReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: WeeklyReportEntity): Long

    @Query(
        "SELECT * FROM weekly_reports WHERE profile_id = :profileId AND week_start_date = :weekStartDate"
    )
    suspend fun getByWeek(profileId: Long, weekStartDate: String): WeeklyReportEntity?
}

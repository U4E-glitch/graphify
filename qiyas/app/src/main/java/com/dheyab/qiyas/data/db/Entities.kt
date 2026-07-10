package com.dheyab.qiyas.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "readings",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["profile_id", "type", "measured_at"])],
)
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "glucose_mgdl") val glucoseMgdl: Float?,
    @ColumnInfo(name = "glucose_context") val glucoseContext: String?,
    @ColumnInfo(name = "systolic") val systolic: Int?,
    @ColumnInfo(name = "diastolic") val diastolic: Int?,
    @ColumnInfo(name = "pulse") val pulse: Int?,
    @ColumnInfo(name = "bp_context") val bpContext: String?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "zone") val zone: String,
)

@Entity(
    tableName = "weekly_reports",
    indices = [Index(value = ["profile_id", "week_start_date"], unique = true)],
)
data class WeeklyReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "profile_id") val profileId: Long,
    @ColumnInfo(name = "week_start_date") val weekStartDate: String,
    @ColumnInfo(name = "generated_at") val generatedAt: Long,
    @ColumnInfo(name = "json_payload") val jsonPayload: String,
)

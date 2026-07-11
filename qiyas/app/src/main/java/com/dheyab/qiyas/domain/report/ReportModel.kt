package com.dheyab.qiyas.domain.report

import kotlinx.serialization.Serializable

/**
 * Serializable weekly report (spec §10). Stored as JSON in weekly_reports and
 * rendered by the Report screen and PDF exporter.
 */
@Serializable
data class ReportModel(
    val weekStartDate: String, // ISO yyyy-MM-dd
    val weekEndDate: String,   // inclusive display end (start + 6 days)
    val glucoseCount: Int,
    val bpCount: Int,
    val glucose: GlucoseSummary?,
    val bp: BpSummary?,
    val trends: Trends,
    val flags: List<String>,
)

/** Pooled context groups (spec §10 aggregations). */
enum class GlucoseGroup { FASTING_PRE_MEAL, POST_MEAL, BEDTIME, RANDOM }

@Serializable
data class GroupStats(
    val count: Int,
    val mean: Float,
    val inRangePct: Float,
)

@Serializable
data class ExtremeReading(
    val valueMgdl: Float,
    val measuredAt: Long,
)

@Serializable
data class GlucoseSummary(
    val count: Int,
    /** Keyed by [GlucoseGroup] name; empty groups are omitted, never 0/NaN. */
    val groups: Map<String, GroupStats>,
    val min: ExtremeReading,
    val max: ExtremeReading,
    /** Keyed by Zone name; only non-zero counts present. */
    val zoneDistribution: Map<String, Int>,
)

@Serializable
data class BpGroupStats(
    val count: Int,
    val meanSystolic: Float,
    val meanDiastolic: Float,
)

@Serializable
data class WorstBp(
    val systolic: Int,
    val diastolic: Int,
    val measuredAt: Long,
    val zone: String,
)

@Serializable
data class BpSummary(
    val count: Int,
    val meanSystolic: Float,
    val meanDiastolic: Float,
    val morning: BpGroupStats?,
    val evening: BpGroupStats?,
    /** Keyed by Zone name; percentage of the week's BP readings; non-zero only. */
    val zonePct: Map<String, Float>,
    val worst: WorstBp,
)

/** Deltas current-week minus previous-week; null when either week lacks ≥4 readings of that type. */
@Serializable
data class Trends(
    val fastingMeanDelta: Float? = null,
    val postMealMeanDelta: Float? = null,
    val systolicMeanDelta: Float? = null,
    val diastolicMeanDelta: Float? = null,
)

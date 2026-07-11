package com.dheyab.qiyas.domain.report

import com.dheyab.qiyas.domain.model.BpContext
import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.domain.report.ReportTestData.bp
import com.dheyab.qiyas.domain.report.ReportTestData.build
import com.dheyab.qiyas.domain.report.ReportTestData.glucose
import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Test

/** Mandatory test 5 (spec §13): means, percentages, trend deltas, empty-group omission. */
class ReportMathTest {

    @Test
    fun glucoseGroupMeansAndInRangePercent() {
        val week = listOf(
            glucose(100f, GlucoseContext.FASTING, dayOffset = 0),   // IN_RANGE
            glucose(120f, GlucoseContext.PRE_MEAL, dayOffset = 1),  // IN_RANGE (pooled with fasting)
            glucose(140f, GlucoseContext.FASTING, dayOffset = 2),   // ABOVE_RANGE
            glucose(160f, GlucoseContext.POST_MEAL_1H, dayOffset = 0), // IN_RANGE
            glucose(200f, GlucoseContext.POST_MEAL_2H, dayOffset = 1), // ABOVE_RANGE
        )
        val report = build(week)
        val groups = report.glucose!!.groups

        val fasting = groups.getValue(GlucoseGroup.FASTING_PRE_MEAL.name)
        assertThat(fasting.count).isEqualTo(3)
        assertThat(fasting.mean).isWithin(0.01f).of(120f)
        assertThat(fasting.inRangePct).isWithin(0.01f).of(200f / 3f)

        val post = groups.getValue(GlucoseGroup.POST_MEAL.name)
        assertThat(post.count).isEqualTo(2)
        assertThat(post.mean).isWithin(0.01f).of(180f)
        assertThat(post.inRangePct).isWithin(0.01f).of(50f)
    }

    @Test
    fun emptyGroupsAreOmitted() {
        val week = listOf(glucose(100f, GlucoseContext.FASTING))
        val groups = build(week).glucose!!.groups
        assertThat(groups.keys).containsExactly(GlucoseGroup.FASTING_PRE_MEAL.name)
        assertThat(groups.keys).doesNotContain(GlucoseGroup.POST_MEAL.name)
        assertThat(groups.keys).doesNotContain(GlucoseGroup.BEDTIME.name)
        assertThat(groups.keys).doesNotContain(GlucoseGroup.RANDOM.name)
    }

    @Test
    fun minMaxCarryTimestamps() {
        val low = glucose(80f, GlucoseContext.RANDOM, dayOffset = 2, hour = 14)
        val high = glucose(210f, GlucoseContext.POST_MEAL_1H, dayOffset = 4, hour = 20)
        val week = listOf(glucose(100f), low, high)
        val summary = build(week).glucose!!
        assertThat(summary.min.valueMgdl).isEqualTo(80f)
        assertThat(summary.min.measuredAt).isEqualTo(low.measuredAt)
        assertThat(summary.max.valueMgdl).isEqualTo(210f)
        assertThat(summary.max.measuredAt).isEqualTo(high.measuredAt)
    }

    @Test
    fun zoneDistributionCountsPerZone() {
        val week = listOf(
            glucose(100f, GlucoseContext.FASTING), // IN_RANGE
            glucose(105f, GlucoseContext.FASTING), // IN_RANGE
            glucose(140f, GlucoseContext.FASTING), // ABOVE_RANGE
            glucose(60f, GlucoseContext.RANDOM),   // EMERGENCY_LOW
        )
        val dist = build(week).glucose!!.zoneDistribution
        assertThat(dist).containsEntry(Zone.IN_RANGE.name, 2)
        assertThat(dist).containsEntry(Zone.ABOVE_RANGE.name, 1)
        assertThat(dist).containsEntry(Zone.EMERGENCY_LOW.name, 1)
        assertThat(dist).doesNotContainKey(Zone.HIGH.name)
    }

    @Test
    fun bpMeansOverallAndByContext() {
        val week = listOf(
            bp(120, 80, BpContext.MORNING, dayOffset = 0),
            bp(130, 84, BpContext.MORNING, dayOffset = 1),
            bp(110, 70, BpContext.EVENING, dayOffset = 0, hour = 20),
            bp(112, 74, BpContext.EVENING, dayOffset = 1, hour = 20),
            bp(140, 90, BpContext.OTHER, dayOffset = 2),
        )
        val summary = build(week).bp!!
        assertThat(summary.count).isEqualTo(5)
        assertThat(summary.meanSystolic).isWithin(0.01f).of((120 + 130 + 110 + 112 + 140) / 5f)
        assertThat(summary.meanDiastolic).isWithin(0.01f).of((80 + 84 + 70 + 74 + 90) / 5f)
        assertThat(summary.morning!!.meanSystolic).isWithin(0.01f).of(125f)
        assertThat(summary.evening!!.meanSystolic).isWithin(0.01f).of(111f)
        // Worst = the HIGH reading (140/90).
        assertThat(summary.worst.zone).isEqualTo(Zone.HIGH.name)
        assertThat(summary.worst.systolic).isEqualTo(140)
    }

    @Test
    fun bpZonePercentages() {
        val week = listOf(
            bp(110, 70), bp(112, 72),        // NORMAL x2
            bp(150, 95), bp(145, 92),        // HIGH x2
        )
        val pct = build(week).bp!!.zonePct
        assertThat(pct.getValue(Zone.NORMAL.name)).isWithin(0.01f).of(50f)
        assertThat(pct.getValue(Zone.HIGH.name)).isWithin(0.01f).of(50f)
        assertThat(pct).doesNotContainKey(Zone.ELEVATED.name)
    }

    @Test
    fun trendsComputedWhenBothWeeksHaveFourReadings() {
        val current = (0..3).map { glucose(110f, GlucoseContext.FASTING, dayOffset = it) } +
            (0..3).map { bp(120, 80, dayOffset = it) }
        val previous = (0..3).map { glucose(100f, GlucoseContext.FASTING, dayOffset = it) } +
            (0..3).map { bp(110, 74, dayOffset = it) }
        val trends = build(current, previous).trends
        assertThat(trends.fastingMeanDelta!!).isWithin(0.01f).of(10f)
        assertThat(trends.systolicMeanDelta!!).isWithin(0.01f).of(10f)
        assertThat(trends.diastolicMeanDelta!!).isWithin(0.01f).of(6f)
        // No post-meal readings in either week -> that delta is absent.
        assertThat(trends.postMealMeanDelta).isNull()
    }

    @Test
    fun trendsSuppressedWhenPreviousWeekTooSmall() {
        val current = (0..4).map { glucose(110f, GlucoseContext.FASTING, dayOffset = it) } +
            (0..4).map { bp(120, 80, dayOffset = it) }
        val previous = listOf(glucose(100f), bp(110, 74)) // < 4 of each
        val trends = build(current, previous).trends
        assertThat(trends.fastingMeanDelta).isNull()
        assertThat(trends.postMealMeanDelta).isNull()
        assertThat(trends.systolicMeanDelta).isNull()
        assertThat(trends.diastolicMeanDelta).isNull()
    }

    @Test
    fun weekBoundsAndDates() {
        val report = build(listOf(glucose(100f)))
        assertThat(report.weekStartDate).isEqualTo("2026-06-29")
        assertThat(report.weekEndDate).isEqualTo("2026-07-05")
    }

    @Test
    fun weekMathFindsPreviousOrSameStartDay() {
        // 2026-07-10 is a Friday.
        val friday = LocalDate.of(2026, 7, 10)
        assertThat(WeekMath.weekStartFor(friday, DayOfWeek.MONDAY)).isEqualTo(LocalDate.of(2026, 7, 6))
        assertThat(WeekMath.weekStartFor(friday, DayOfWeek.FRIDAY)).isEqualTo(friday)
        assertThat(WeekMath.weekStartFor(friday, DayOfWeek.SATURDAY)).isEqualTo(LocalDate.of(2026, 7, 4))
    }

    @Test
    fun emptyWeekProducesNullSummaries() {
        val report = build(emptyList())
        assertThat(report.glucose).isNull()
        assertThat(report.bp).isNull()
        assertThat(report.glucoseCount).isEqualTo(0)
        assertThat(report.bpCount).isEqualTo(0)
    }
}

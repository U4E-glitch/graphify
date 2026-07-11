package com.dheyab.qiyas.domain.report

import com.dheyab.qiyas.domain.model.BpContext
import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.report.ReportBuilder.Companion.FLAG_ALL_IN_RANGE
import com.dheyab.qiyas.domain.report.ReportBuilder.Companion.FLAG_BP_CRISIS_EVENT
import com.dheyab.qiyas.domain.report.ReportBuilder.Companion.FLAG_BP_HIGH_PATTERN
import com.dheyab.qiyas.domain.report.ReportBuilder.Companion.FLAG_BP_LOW_PATTERN
import com.dheyab.qiyas.domain.report.ReportBuilder.Companion.FLAG_BP_MORNING_DOMINANCE
import com.dheyab.qiyas.domain.report.ReportBuilder.Companion.FLAG_FASTING_HIGH_PATTERN
import com.dheyab.qiyas.domain.report.ReportBuilder.Companion.FLAG_GLUCOSE_VARIABILITY
import com.dheyab.qiyas.domain.report.ReportBuilder.Companion.FLAG_HYPO_EVENT
import com.dheyab.qiyas.domain.report.ReportBuilder.Companion.FLAG_LOW_DATA
import com.dheyab.qiyas.domain.report.ReportBuilder.Companion.FLAG_POSTMEAL_HIGH_PATTERN
import com.dheyab.qiyas.domain.report.ReportTestData.bp
import com.dheyab.qiyas.domain.report.ReportTestData.build
import com.dheyab.qiyas.domain.report.ReportTestData.cleanWeek
import com.dheyab.qiyas.domain.report.ReportTestData.glucose
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Mandatory test 4 (spec §13): each flag has a firing week and a non-firing week. */
class FlagRulesTest {

    @Test
    fun lowData_firesWhenEitherTypeUnder4() {
        val week = (0..2).map { glucose(100f, dayOffset = it) } + // 3 glucose
            (0..4).map { bp(110, 70, dayOffset = it) }            // 5 bp
        assertThat(build(week).flags).contains(FLAG_LOW_DATA)
    }

    @Test
    fun lowData_doesNotFireWithFourOfEach() {
        assertThat(build(cleanWeek()).flags).doesNotContain(FLAG_LOW_DATA)
    }

    @Test
    fun hypoEvent_firesOnAnyReadingBelow70() {
        val week = cleanWeek() + glucose(65f, GlucoseContext.RANDOM, dayOffset = 4)
        assertThat(build(week).flags).contains(FLAG_HYPO_EVENT)
    }

    @Test
    fun hypoEvent_doesNotFireAt70() {
        val week = cleanWeek() + glucose(70f, GlucoseContext.RANDOM, dayOffset = 4)
        assertThat(build(week).flags).doesNotContain(FLAG_HYPO_EVENT)
    }

    @Test
    fun fastingHighPattern_firesWithThreeAbove130() {
        val week = cleanWeek() + listOf(
            glucose(135f, GlucoseContext.FASTING, dayOffset = 1),
            glucose(140f, GlucoseContext.PRE_MEAL, dayOffset = 2),
            glucose(150f, GlucoseContext.FASTING, dayOffset = 3),
        )
        assertThat(build(week).flags).contains(FLAG_FASTING_HIGH_PATTERN)
    }

    @Test
    fun fastingHighPattern_needsThree() {
        val week = cleanWeek() + listOf(
            glucose(135f, GlucoseContext.FASTING, dayOffset = 1),
            glucose(140f, GlucoseContext.FASTING, dayOffset = 2),
            // 131+ in a post-meal context must not count toward the fasting pattern
            glucose(150f, GlucoseContext.POST_MEAL_2H, dayOffset = 3),
        )
        assertThat(build(week).flags).doesNotContain(FLAG_FASTING_HIGH_PATTERN)
    }

    @Test
    fun postMealHighPattern_firesWithThreeAtOrAbove180() {
        val week = cleanWeek() + listOf(
            glucose(180f, GlucoseContext.POST_MEAL_1H, dayOffset = 1),
            glucose(200f, GlucoseContext.POST_MEAL_2H, dayOffset = 2),
            glucose(210f, GlucoseContext.POST_MEAL_1H, dayOffset = 3),
        )
        assertThat(build(week).flags).contains(FLAG_POSTMEAL_HIGH_PATTERN)
    }

    @Test
    fun postMealHighPattern_doesNotFireBelowThreshold() {
        val week = cleanWeek() + listOf(
            glucose(179f, GlucoseContext.POST_MEAL_1H, dayOffset = 1),
            glucose(179f, GlucoseContext.POST_MEAL_2H, dayOffset = 2),
            glucose(179f, GlucoseContext.POST_MEAL_1H, dayOffset = 3),
        )
        assertThat(build(week).flags).doesNotContain(FLAG_POSTMEAL_HIGH_PATTERN)
    }

    @Test
    fun glucoseVariability_firesWithSpreadOver100AndSixReadings() {
        val week = listOf(
            glucose(90f, dayOffset = 0), glucose(95f, dayOffset = 1), glucose(100f, dayOffset = 2),
            glucose(105f, dayOffset = 3), glucose(80f, GlucoseContext.RANDOM, dayOffset = 4),
            glucose(185f, GlucoseContext.RANDOM, dayOffset = 5),
        ) + (0..3).map { bp(110, 70, dayOffset = it) }
        assertThat(build(week).flags).contains(FLAG_GLUCOSE_VARIABILITY)
    }

    @Test
    fun glucoseVariability_needsSixReadings() {
        val week = listOf(
            glucose(90f, dayOffset = 0), glucose(95f, dayOffset = 1), glucose(100f, dayOffset = 2),
            glucose(105f, dayOffset = 3), glucose(200f, GlucoseContext.RANDOM, dayOffset = 4),
        ) + (0..3).map { bp(110, 70, dayOffset = it) }
        assertThat(build(week).flags).doesNotContain(FLAG_GLUCOSE_VARIABILITY)
    }

    @Test
    fun bpHighPattern_firesWithTwoHighReadings() {
        val week = cleanWeek() + listOf(bp(145, 92, dayOffset = 1), bp(150, 95, dayOffset = 2))
        assertThat(build(week).flags).contains(FLAG_BP_HIGH_PATTERN)
    }

    @Test
    fun bpHighPattern_elevatedDoesNotCount() {
        val week = cleanWeek() + listOf(bp(132, 78, dayOffset = 1), bp(135, 79, dayOffset = 2))
        assertThat(build(week).flags).doesNotContain(FLAG_BP_HIGH_PATTERN)
    }

    @Test
    fun bpCrisisEvent_firesOnSingleCrisis() {
        val week = cleanWeek() + bp(185, 100, dayOffset = 2)
        assertThat(build(week).flags).contains(FLAG_BP_CRISIS_EVENT)
    }

    @Test
    fun bpCrisisEvent_doesNotFireOnMerelyHigh() {
        val week = cleanWeek() + bp(160, 100, dayOffset = 2)
        assertThat(build(week).flags).doesNotContain(FLAG_BP_CRISIS_EVENT)
    }

    @Test
    fun morningDominance_firesWhenMorningSystolicTenHigher() {
        val week = (0..3).map { glucose(100f, dayOffset = it) } + listOf(
            bp(130, 70, BpContext.MORNING, dayOffset = 0),
            bp(132, 70, BpContext.MORNING, dayOffset = 1),
            bp(118, 70, BpContext.EVENING, dayOffset = 0, hour = 20),
            bp(120, 70, BpContext.EVENING, dayOffset = 1, hour = 20),
        )
        assertThat(build(week).flags).contains(FLAG_BP_MORNING_DOMINANCE)
    }

    @Test
    fun morningDominance_needsTwoReadingsEach() {
        val week = (0..3).map { glucose(100f, dayOffset = it) } + listOf(
            bp(140, 70, BpContext.MORNING, dayOffset = 0),
            bp(110, 70, BpContext.EVENING, dayOffset = 0, hour = 20),
            bp(110, 70, BpContext.EVENING, dayOffset = 1, hour = 20),
            bp(110, 70, BpContext.OTHER, dayOffset = 2),
        )
        assertThat(build(week).flags).doesNotContain(FLAG_BP_MORNING_DOMINANCE)
    }

    @Test
    fun bpLowPattern_firesWithTwoLowReadings() {
        val week = cleanWeek() + listOf(bp(85, 55, dayOffset = 1), bp(88, 58, dayOffset = 2))
        assertThat(build(week).flags).contains(FLAG_BP_LOW_PATTERN)
    }

    @Test
    fun bpLowPattern_needsTwo() {
        val week = cleanWeek() + bp(85, 55, dayOffset = 1)
        assertThat(build(week).flags).doesNotContain(FLAG_BP_LOW_PATTERN)
    }

    @Test
    fun allInRange_firesAloneOnCleanWeek() {
        assertThat(build(cleanWeek()).flags).containsExactly(FLAG_ALL_IN_RANGE)
    }

    @Test
    fun allInRange_doesNotFireWithAnyNonGreenReading() {
        val week = cleanWeek() + glucose(75f, GlucoseContext.FASTING, dayOffset = 5) // BELOW_RANGE (yellow)
        assertThat(build(week).flags).doesNotContain(FLAG_ALL_IN_RANGE)
    }

    @Test
    fun allInRange_neverFiresAlongsideOtherFlags() {
        // In-range readings but only 3 glucose -> LOW_DATA fires, so ALL_IN_RANGE must not.
        val week = (0..2).map { glucose(100f, dayOffset = it) } +
            (0..3).map { bp(110, 70, dayOffset = it) }
        val flags = build(week).flags
        assertThat(flags).contains(FLAG_LOW_DATA)
        assertThat(flags).doesNotContain(FLAG_ALL_IN_RANGE)
    }
}

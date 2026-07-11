package com.dheyab.qiyas.domain.report

import com.dheyab.qiyas.domain.TestThresholds
import com.dheyab.qiyas.domain.model.BpContext
import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import java.time.LocalDate
import java.time.ZoneOffset

/** Synthetic-week helpers. Zones are computed with the real classifier + shipped thresholds. */
object ReportTestData {

    val WEEK_START: LocalDate = LocalDate.of(2026, 6, 29) // a Monday

    private var nextId = 1L

    fun at(dayOffset: Int, hour: Int = 8): Long =
        WEEK_START.plusDays(dayOffset.toLong()).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    fun glucose(
        mgdl: Float,
        context: GlucoseContext = GlucoseContext.FASTING,
        dayOffset: Int = 0,
        hour: Int = 8,
    ): Reading = Reading(
        id = nextId++,
        profileId = 1,
        type = ReadingType.GLUCOSE,
        measuredAt = at(dayOffset, hour),
        createdAt = at(dayOffset, hour),
        glucoseMgdl = mgdl,
        glucoseContext = context,
        zone = TestThresholds.classifier.classifyGlucose(mgdl, context).zone,
    )

    fun bp(
        systolic: Int,
        diastolic: Int,
        context: BpContext = BpContext.MORNING,
        dayOffset: Int = 0,
        hour: Int = 8,
    ): Reading = Reading(
        id = nextId++,
        profileId = 1,
        type = ReadingType.BP,
        measuredAt = at(dayOffset, hour),
        createdAt = at(dayOffset, hour),
        systolic = systolic,
        diastolic = diastolic,
        bpContext = context,
        zone = TestThresholds.classifier.classifyBp(systolic, diastolic).zone,
    )

    /** 4+ in-range glucose and BP readings — a clean base week that fires nothing but ALL_IN_RANGE. */
    fun cleanWeek(): List<Reading> =
        (0..3).map { glucose(100f, GlucoseContext.FASTING, dayOffset = it) } +
            (0..3).map { bp(110, 70, if (it % 2 == 0) BpContext.MORNING else BpContext.EVENING, dayOffset = it) }

    fun build(week: List<Reading>, previous: List<Reading> = emptyList()): ReportModel =
        ReportBuilder().build(WEEK_START, week, previous)
}

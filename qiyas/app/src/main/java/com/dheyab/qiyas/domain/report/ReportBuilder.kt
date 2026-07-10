package com.dheyab.qiyas.domain.report

import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import com.dheyab.qiyas.domain.model.Severity
import com.dheyab.qiyas.domain.model.Zone
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Pure-Kotlin week arithmetic: week = [weekStart 00:00, +7 days) in the given zone (spec §10). */
object WeekMath {
    fun weekStartFor(date: LocalDate, weekStartDay: DayOfWeek): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(weekStartDay))

    fun weekBoundsMillis(weekStart: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val from = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        return from to to
    }
}

/**
 * Pure-Kotlin weekly report engine (spec §10). Zero Android dependencies.
 * The caller supplies the readings for the report week and the previous week.
 */
class ReportBuilder {

    companion object {
        const val MIN_READINGS_FOR_TRENDS = 4
        private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

        /** Flag ids in spec §10 table order. */
        const val FLAG_LOW_DATA = "LOW_DATA"
        const val FLAG_HYPO_EVENT = "HYPO_EVENT"
        const val FLAG_FASTING_HIGH_PATTERN = "FASTING_HIGH_PATTERN"
        const val FLAG_POSTMEAL_HIGH_PATTERN = "POSTMEAL_HIGH_PATTERN"
        const val FLAG_GLUCOSE_VARIABILITY = "GLUCOSE_VARIABILITY"
        const val FLAG_BP_HIGH_PATTERN = "BP_HIGH_PATTERN"
        const val FLAG_BP_CRISIS_EVENT = "BP_CRISIS_EVENT"
        const val FLAG_BP_MORNING_DOMINANCE = "BP_MORNING_DOMINANCE"
        const val FLAG_BP_LOW_PATTERN = "BP_LOW_PATTERN"
        const val FLAG_ALL_IN_RANGE = "ALL_IN_RANGE"
    }

    fun build(
        weekStart: LocalDate,
        weekReadings: List<Reading>,
        previousWeekReadings: List<Reading>,
    ): ReportModel {
        val glucose = weekReadings.filter { it.type == ReadingType.GLUCOSE }
        val bp = weekReadings.filter { it.type == ReadingType.BP }
        val prevGlucose = previousWeekReadings.filter { it.type == ReadingType.GLUCOSE }
        val prevBp = previousWeekReadings.filter { it.type == ReadingType.BP }

        val glucoseSummary = buildGlucoseSummary(glucose)
        val bpSummary = buildBpSummary(bp)
        val trends = buildTrends(glucose, prevGlucose, bp, prevBp)
        val flags = evaluateFlags(glucose, bp, glucoseSummary, bpSummary)

        return ReportModel(
            weekStartDate = ISO.format(weekStart),
            weekEndDate = ISO.format(weekStart.plusDays(6)),
            glucoseCount = glucose.size,
            bpCount = bp.size,
            glucose = glucoseSummary,
            bp = bpSummary,
            trends = trends,
            flags = flags,
        )
    }

    private fun groupOf(context: GlucoseContext?): GlucoseGroup? = when (context) {
        GlucoseContext.FASTING, GlucoseContext.PRE_MEAL -> GlucoseGroup.FASTING_PRE_MEAL
        GlucoseContext.POST_MEAL_1H, GlucoseContext.POST_MEAL_2H -> GlucoseGroup.POST_MEAL
        GlucoseContext.BEDTIME -> GlucoseGroup.BEDTIME
        GlucoseContext.RANDOM -> GlucoseGroup.RANDOM
        null -> null
    }

    private fun buildGlucoseSummary(glucose: List<Reading>): GlucoseSummary? {
        if (glucose.isEmpty()) return null
        val byGroup = glucose.groupBy { groupOf(it.glucoseContext) }
        val groups = GlucoseGroup.entries.mapNotNull { group ->
            val readings = byGroup[group].orEmpty()
            if (readings.isEmpty()) return@mapNotNull null // empty groups omitted (spec §10)
            val values = readings.mapNotNull { it.glucoseMgdl }
            group.name to GroupStats(
                count = readings.size,
                mean = values.sum() / values.size,
                inRangePct = readings.count { it.zone == Zone.IN_RANGE } * 100f / readings.size,
            )
        }.toMap()

        val minReading = glucose.minBy { it.glucoseMgdl ?: Float.MAX_VALUE }
        val maxReading = glucose.maxBy { it.glucoseMgdl ?: Float.MIN_VALUE }
        val zoneDistribution = glucose.groupingBy { it.zone.name }.eachCount()

        return GlucoseSummary(
            count = glucose.size,
            groups = groups,
            min = ExtremeReading(minReading.glucoseMgdl ?: 0f, minReading.measuredAt),
            max = ExtremeReading(maxReading.glucoseMgdl ?: 0f, maxReading.measuredAt),
            zoneDistribution = zoneDistribution,
        )
    }

    private val bpWorstOrder = listOf(Zone.CRISIS, Zone.HIGH, Zone.ELEVATED, Zone.LOW, Zone.NORMAL)

    private fun buildBpSummary(bp: List<Reading>): BpSummary? {
        if (bp.isEmpty()) return null
        val morning = bp.filter { it.bpContext == com.dheyab.qiyas.domain.model.BpContext.MORNING }
        val evening = bp.filter { it.bpContext == com.dheyab.qiyas.domain.model.BpContext.EVENING }
        val worst = bp.sortedWith(
            compareBy<Reading> { bpWorstOrder.indexOf(it.zone) }
                .thenByDescending { it.systolic ?: 0 }
                .thenByDescending { it.diastolic ?: 0 }
        ).first()

        fun groupStats(readings: List<Reading>): BpGroupStats? {
            if (readings.isEmpty()) return null
            return BpGroupStats(
                count = readings.size,
                meanSystolic = readings.mapNotNull { it.systolic }.let { it.sum().toFloat() / it.size },
                meanDiastolic = readings.mapNotNull { it.diastolic }.let { it.sum().toFloat() / it.size },
            )
        }

        return BpSummary(
            count = bp.size,
            meanSystolic = bp.mapNotNull { it.systolic }.let { it.sum().toFloat() / it.size },
            meanDiastolic = bp.mapNotNull { it.diastolic }.let { it.sum().toFloat() / it.size },
            morning = groupStats(morning),
            evening = groupStats(evening),
            zonePct = bp.groupingBy { it.zone.name }.eachCount()
                .mapValues { (_, count) -> count * 100f / bp.size },
            worst = WorstBp(
                systolic = worst.systolic ?: 0,
                diastolic = worst.diastolic ?: 0,
                measuredAt = worst.measuredAt,
                zone = worst.zone.name,
            ),
        )
    }

    private fun buildTrends(
        glucose: List<Reading>,
        prevGlucose: List<Reading>,
        bp: List<Reading>,
        prevBp: List<Reading>,
    ): Trends {
        val glucoseEligible = glucose.size >= MIN_READINGS_FOR_TRENDS &&
            prevGlucose.size >= MIN_READINGS_FOR_TRENDS
        val bpEligible = bp.size >= MIN_READINGS_FOR_TRENDS && prevBp.size >= MIN_READINGS_FOR_TRENDS

        fun groupMean(readings: List<Reading>, group: GlucoseGroup): Float? {
            val values = readings.filter { groupOf(it.glucoseContext) == group }.mapNotNull { it.glucoseMgdl }
            return if (values.isEmpty()) null else values.sum() / values.size
        }

        fun delta(current: Float?, previous: Float?): Float? =
            if (current != null && previous != null) current - previous else null

        return Trends(
            fastingMeanDelta = if (glucoseEligible) {
                delta(
                    groupMean(glucose, GlucoseGroup.FASTING_PRE_MEAL),
                    groupMean(prevGlucose, GlucoseGroup.FASTING_PRE_MEAL),
                )
            } else null,
            postMealMeanDelta = if (glucoseEligible) {
                delta(
                    groupMean(glucose, GlucoseGroup.POST_MEAL),
                    groupMean(prevGlucose, GlucoseGroup.POST_MEAL),
                )
            } else null,
            systolicMeanDelta = if (bpEligible) {
                bp.mapNotNull { it.systolic }.average().toFloat() -
                    prevBp.mapNotNull { it.systolic }.average().toFloat()
            } else null,
            diastolicMeanDelta = if (bpEligible) {
                bp.mapNotNull { it.diastolic }.average().toFloat() -
                    prevBp.mapNotNull { it.diastolic }.average().toFloat()
            } else null,
        )
    }

    private fun evaluateFlags(
        glucose: List<Reading>,
        bp: List<Reading>,
        glucoseSummary: GlucoseSummary?,
        bpSummary: BpSummary?,
    ): List<String> {
        val flags = mutableListOf<String>()

        // LOW_DATA: evaluated per type (spec §10) — fires when either type is under 4.
        if (glucose.size < 4 || bp.size < 4) flags += FLAG_LOW_DATA

        // Glucose value comparisons mirror the classifier's rounding.
        fun mgdlInt(r: Reading): Int = (r.glucoseMgdl ?: 0f).roundToInt()

        if (glucose.any { mgdlInt(it) < 70 }) flags += FLAG_HYPO_EVENT

        val fastingGroup = glucose.filter { groupOf(it.glucoseContext) == GlucoseGroup.FASTING_PRE_MEAL }
        if (fastingGroup.count { mgdlInt(it) > 130 } >= 3) flags += FLAG_FASTING_HIGH_PATTERN

        val postGroup = glucose.filter { groupOf(it.glucoseContext) == GlucoseGroup.POST_MEAL }
        if (postGroup.count { mgdlInt(it) >= 180 } >= 3) flags += FLAG_POSTMEAL_HIGH_PATTERN

        if (glucose.size >= 6 && glucoseSummary != null &&
            glucoseSummary.max.valueMgdl - glucoseSummary.min.valueMgdl >= 100f
        ) {
            flags += FLAG_GLUCOSE_VARIABILITY
        }

        if (bp.count { it.zone == Zone.HIGH || it.zone == Zone.CRISIS } >= 2) flags += FLAG_BP_HIGH_PATTERN
        if (bp.any { it.zone == Zone.CRISIS }) flags += FLAG_BP_CRISIS_EVENT

        val morning = bpSummary?.morning
        val evening = bpSummary?.evening
        if (morning != null && evening != null && morning.count >= 2 && evening.count >= 2 &&
            morning.meanSystolic - evening.meanSystolic >= 10f
        ) {
            flags += FLAG_BP_MORNING_DOMINANCE
        }

        if (bp.count { it.zone == Zone.LOW } >= 2) flags += FLAG_BP_LOW_PATTERN

        // ALL_IN_RANGE only fires alone (spec §10).
        if (flags.isEmpty()) {
            val loggedTypes = buildList {
                if (glucose.isNotEmpty()) add(glucose)
                if (bp.isNotEmpty()) add(bp)
            }
            val allGreen = (glucose + bp).all { it.zone.severity == Severity.GREEN }
            val enoughOfEach = loggedTypes.isNotEmpty() && loggedTypes.all { it.size >= 4 }
            if (allGreen && enoughOfEach) flags += FLAG_ALL_IN_RANGE
        }
        return flags
    }
}

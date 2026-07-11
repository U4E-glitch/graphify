package com.dheyab.qiyas.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.dheyab.qiyas.R
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.core.NumberUtils
import com.dheyab.qiyas.core.UnitConverter
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.domain.report.GlucoseGroup
import com.dheyab.qiyas.domain.report.RecommendationsConfig
import com.dheyab.qiyas.domain.report.ReportModel
import com.dheyab.qiyas.ui.common.Formatters
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A4 PDF render of the weekly report (spec §11) via android.graphics.pdf.
 * Arabic output lays out RTL through StaticLayout + TextDirectionHeuristics.RTL;
 * all numeric values stay Latin (Hard Rule 5).
 */
@Singleton
class PdfExporter @Inject constructor(
    private val recommendations: RecommendationsConfig,
) {
    private companion object {
        const val PAGE_WIDTH = 595  // A4 @72dpi
        const val PAGE_HEIGHT = 842
        const val MARGIN = 40
    }

    internal data class Line(val text: String, val sizePt: Float, val bold: Boolean = false, val spacingBefore: Float = 0f)

    fun build(context: Context, report: ReportModel, unit: GlucoseUnit): ByteArray {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val rtl = locale.language == "ar"
        val lines = buildLines(context, report, unit, locale)

        val document = PdfDocument()
        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        var pageNumber = 1
        var page = document.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        )
        var y = MARGIN.toFloat()

        for (line in lines) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = line.sizePt
                color = Color.BLACK
                typeface = if (line.bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            }
            val layout = StaticLayout.Builder
                .obtain(line.text, 0, line.text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
                .setLineSpacing(2f, 1f)
                .build()

            y += line.spacingBefore
            if (y + layout.height > PAGE_HEIGHT - MARGIN) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                )
                y = MARGIN.toFloat()
            }
            page.canvas.save()
            page.canvas.translate(MARGIN.toFloat(), y)
            layout.draw(page.canvas)
            page.canvas.restore()
            y += layout.height
        }

        document.finishPage(page)
        val output = ByteArrayOutputStream()
        document.writeTo(output)
        document.close()
        return output.toByteArray()
    }

    internal fun buildLines(
        context: Context,
        report: ReportModel,
        unit: GlucoseUnit,
        locale: Locale,
    ): List<Line> {
        fun str(resId: Int, vararg args: Any) = context.getString(resId, *args)
        // Numeric fragments are BiDi-isolated so RTL sentences can't reorder them.
        fun glucoseValue(mgdl: Float) = NumberUtils.bidiIsolate(
            "${UnitConverter.formatCanonical(mgdl, unit)} ${str(if (unit == GlucoseUnit.MGDL) R.string.unit_mgdl else R.string.unit_mmol)}"
        )
        fun pct(v: Float) = NumberUtils.bidiIsolate(String.format(Locale.US, "%.0f%%", v))
        fun bpValue(systolic: Int, diastolic: Int) = NumberUtils.bidiIsolate("$systolic/$diastolic")

        val zoneId = ZoneId.systemDefault()
        val weekStart = LocalDate.parse(report.weekStartDate)
        val startMillis = weekStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = weekStart.plusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val range = str(
            R.string.report_week_range,
            Formatters.date(startMillis, locale),
            Formatters.date(endMillis, locale),
        )

        fun zoneLabel(zoneName: String): String {
            val zone = runCatching { Zone.valueOf(zoneName) }.getOrNull() ?: return zoneName
            return str(
                when (zone) {
                    Zone.EMERGENCY_LOW_SEVERE -> R.string.zone_emergency_low_severe
                    Zone.EMERGENCY_LOW -> R.string.zone_emergency_low
                    Zone.EMERGENCY_HIGH -> R.string.zone_emergency_high
                    Zone.BELOW_RANGE -> R.string.zone_below_range
                    Zone.IN_RANGE -> R.string.zone_in_range
                    Zone.ABOVE_RANGE -> R.string.zone_above_range
                    Zone.HIGH -> R.string.zone_high
                    Zone.CRISIS -> R.string.zone_crisis
                    Zone.ELEVATED -> R.string.zone_elevated
                    Zone.LOW -> R.string.zone_low
                    Zone.NORMAL -> R.string.zone_normal
                }
            )
        }

        fun groupLabel(groupName: String): String = when (groupName) {
            GlucoseGroup.FASTING_PRE_MEAL.name -> str(R.string.report_group_fasting)
            GlucoseGroup.POST_MEAL.name -> str(R.string.report_group_postmeal)
            GlucoseGroup.BEDTIME.name -> str(R.string.context_bedtime)
            else -> str(R.string.context_random)
        }

        val lines = mutableListOf<Line>()
        lines += Line("${str(R.string.app_name)} — ${str(R.string.report_title)}", 18f, bold = true)
        lines += Line(range, 12f, spacingBefore = 4f)
        lines += Line(
            str(R.string.report_adequacy, report.glucoseCount.toString(), report.bpCount.toString()),
            10f,
            spacingBefore = 2f,
        )

        // Glucose summary
        lines += Line(str(R.string.report_glucose_summary), 14f, bold = true, spacingBefore = 14f)
        val glucose = report.glucose
        if (glucose == null) {
            lines += Line(str(R.string.report_no_data), 11f, spacingBefore = 4f)
        } else {
            lines += Line("${str(R.string.report_readings_count)}: ${glucose.count}", 11f, spacingBefore = 4f)
            glucose.groups.forEach { (groupName, stats) ->
                lines += Line(
                    "${groupLabel(groupName)}: ${str(R.string.report_mean)} ${glucoseValue(stats.mean)} · " +
                        "${str(R.string.report_in_range)} ${pct(stats.inRangePct)}",
                    11f,
                    spacingBefore = 2f,
                )
            }
            lines += Line(
                "${str(R.string.report_lowest)}: ${glucoseValue(glucose.min.valueMgdl)} · " +
                    Formatters.dateTime(glucose.min.measuredAt, locale),
                11f,
                spacingBefore = 2f,
            )
            lines += Line(
                "${str(R.string.report_highest)}: ${glucoseValue(glucose.max.valueMgdl)} · " +
                    Formatters.dateTime(glucose.max.measuredAt, locale),
                11f,
                spacingBefore = 2f,
            )
            lines += Line(str(R.string.report_zone_distribution) + ":", 11f, bold = true, spacingBefore = 6f)
            glucose.zoneDistribution.forEach { (zoneName, count) ->
                lines += Line("${zoneLabel(zoneName)}: $count", 11f, spacingBefore = 2f)
            }
        }

        // BP summary
        lines += Line(str(R.string.report_bp_summary), 14f, bold = true, spacingBefore = 14f)
        val bp = report.bp
        if (bp == null) {
            lines += Line(str(R.string.report_no_data), 11f, spacingBefore = 4f)
        } else {
            lines += Line("${str(R.string.report_readings_count)}: ${bp.count}", 11f, spacingBefore = 4f)
            lines += Line(
                "${str(R.string.report_mean)}: ${bpValue(bp.meanSystolic.toInt(), bp.meanDiastolic.toInt())}",
                11f,
                spacingBefore = 2f,
            )
            bp.morning?.let {
                lines += Line(
                    "${str(R.string.report_morning)}: ${bpValue(it.meanSystolic.toInt(), it.meanDiastolic.toInt())}",
                    11f,
                    spacingBefore = 2f,
                )
            }
            bp.evening?.let {
                lines += Line(
                    "${str(R.string.report_evening)}: ${bpValue(it.meanSystolic.toInt(), it.meanDiastolic.toInt())}",
                    11f,
                    spacingBefore = 2f,
                )
            }
            bp.zonePct.forEach { (zoneName, percentage) ->
                lines += Line("${zoneLabel(zoneName)}: ${pct(percentage)}", 11f, spacingBefore = 2f)
            }
            lines += Line(
                "${str(R.string.report_worst_reading)}: ${bpValue(bp.worst.systolic, bp.worst.diastolic)} · " +
                    Formatters.dateTime(bp.worst.measuredAt, locale),
                11f,
                spacingBefore = 2f,
            )
        }

        // Trends
        val trends = report.trends
        val trendLines = mutableListOf<Line>()
        fun glucoseDelta(delta: Float) = NumberUtils.bidiIsolate(
            when (unit) {
                GlucoseUnit.MGDL -> String.format(Locale.US, "%+.0f", delta)
                GlucoseUnit.MMOL -> String.format(Locale.US, "%+.1f", UnitConverter.mgdlToMmol(delta))
            }
        )
        trends.fastingMeanDelta?.let {
            trendLines += Line(
                "${str(R.string.report_fasting_mean)}: ${if (it > 0) "▲" else if (it < 0) "▼" else ""} ${glucoseDelta(it)}",
                11f, spacingBefore = 2f,
            )
        }
        trends.postMealMeanDelta?.let {
            trendLines += Line(
                "${str(R.string.report_postmeal_mean)}: ${if (it > 0) "▲" else if (it < 0) "▼" else ""} ${glucoseDelta(it)}",
                11f, spacingBefore = 2f,
            )
        }
        trends.systolicMeanDelta?.let {
            trendLines += Line(
                "${str(R.string.report_systolic_mean)}: ${if (it > 0) "▲" else if (it < 0) "▼" else ""} " +
                    NumberUtils.bidiIsolate(String.format(Locale.US, "%+.0f", it)),
                11f, spacingBefore = 2f,
            )
        }
        trends.diastolicMeanDelta?.let {
            trendLines += Line(
                "${str(R.string.report_diastolic_mean)}: ${if (it > 0) "▲" else if (it < 0) "▼" else ""} " +
                    NumberUtils.bidiIsolate(String.format(Locale.US, "%+.0f", it)),
                11f, spacingBefore = 2f,
            )
        }
        lines += Line(str(R.string.report_trends), 14f, bold = true, spacingBefore = 14f)
        if (trendLines.isEmpty()) {
            lines += Line(str(R.string.report_no_trends), 11f, spacingBefore = 4f)
        } else {
            lines += trendLines
        }

        // Flags & recommendations
        if (report.flags.isNotEmpty()) {
            lines += Line(str(R.string.report_flags), 14f, bold = true, spacingBefore = 14f)
            val languageTag = if (locale.language == "ar") "ar" else "en"
            report.flags.forEach { flagId ->
                recommendations.text(flagId, languageTag)?.let {
                    lines += Line("• ${NumberUtils.toLatinDigits(it)}", 11f, spacingBefore = 4f)
                }
            }
        }

        // Footer disclaimer (Appendix B verbatim)
        lines += Line(str(R.string.report_footer), 10f, spacingBefore = 18f)
        return lines
    }
}

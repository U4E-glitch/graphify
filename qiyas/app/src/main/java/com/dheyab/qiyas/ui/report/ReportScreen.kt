package com.dheyab.qiyas.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheyab.qiyas.R
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.core.NumberUtils
import com.dheyab.qiyas.core.UnitConverter
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.domain.report.GlucoseGroup
import com.dheyab.qiyas.domain.report.ReportModel
import com.dheyab.qiyas.ui.common.Formatters
import com.dheyab.qiyas.ui.common.currentLocale
import com.dheyab.qiyas.ui.common.labelRes
import com.dheyab.qiyas.ui.theme.color
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    onBack: () -> Unit,
    onExportPdf: (ReportModel) -> Unit,
    onExportCsv: () -> Unit,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unit by viewModel.unit.collectAsStateWithLifecycle()
    var weekMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { weekMenuOpen = true }) {
                        Icon(
                            Icons.Filled.CalendarMonth,
                            contentDescription = stringResource(R.string.report_pick_week),
                        )
                    }
                    DropdownMenu(expanded = weekMenuOpen, onDismissRequest = { weekMenuOpen = false }) {
                        state.availableWeeks.forEach { week ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (week == state.currentWeekStart) {
                                            stringResource(R.string.report_current_week)
                                        } else {
                                            weekLabel(week)
                                        }
                                    )
                                },
                                onClick = {
                                    weekMenuOpen = false
                                    viewModel.selectWeek(week)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val report = state.report
        if (state.loading || report == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReportHeader(report)
            GlucoseSection(report, unit)
            BpSection(report)
            TrendsSection(report, unit)
            FlagsSection(report, viewModel)
            HorizontalDivider()
            Text(
                text = stringResource(R.string.report_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onExportPdf(report) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.report_export_pdf))
                }
                OutlinedButton(onClick = onExportCsv, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.report_export_csv))
                }
            }
        }
    }
}

@Composable
private fun weekLabel(weekStart: LocalDate): String {
    val locale = currentLocale()
    val zone = ZoneId.systemDefault()
    val startMillis = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMillis = weekStart.plusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
    return stringResource(
        R.string.report_week_range,
        Formatters.date(startMillis, locale),
        Formatters.date(endMillis, locale),
    )
}

@Composable
private fun ReportHeader(report: ReportModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            weekLabel(LocalDate.parse(report.weekStartDate)),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(
                R.string.report_adequacy,
                report.glucoseCount.toString(),
                report.bpCount.toString(),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun glucoseValue(mgdl: Float, unit: GlucoseUnit): String = NumberUtils.bidiIsolate(
    "${UnitConverter.formatCanonical(mgdl, unit)} ${stringResource(unit.labelRes())}"
)

private fun pct(value: Float): String = NumberUtils.bidiIsolate(String.format(Locale.US, "%.0f%%", value))

private fun bpValue(systolic: Int, diastolic: Int): String = NumberUtils.bidiIsolate("$systolic/$diastolic")

@Composable
fun glucoseGroupLabel(groupName: String): String = when (groupName) {
    GlucoseGroup.FASTING_PRE_MEAL.name -> stringResource(R.string.report_group_fasting)
    GlucoseGroup.POST_MEAL.name -> stringResource(R.string.report_group_postmeal)
    GlucoseGroup.BEDTIME.name -> stringResource(R.string.context_bedtime)
    else -> stringResource(R.string.context_random)
}

@Composable
private fun GlucoseSection(report: ReportModel, unit: GlucoseUnit) {
    SectionCard(
        stringResource(R.string.report_glucose_summary),
        icon = androidx.compose.material.icons.Icons.Filled.WaterDrop,
    ) {
        val glucose = report.glucose
        if (glucose == null) {
            Text(stringResource(R.string.report_no_data), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        StatRow(stringResource(R.string.report_readings_count), glucose.count.toString())
        glucose.groups.forEach { (groupName, stats) ->
            StatRow(
                "${glucoseGroupLabel(groupName)} — ${stringResource(R.string.report_mean)}",
                glucoseValue(stats.mean, unit),
            )
            StatRow(
                "${glucoseGroupLabel(groupName)} — ${stringResource(R.string.report_in_range)}",
                pct(stats.inRangePct),
            )
        }
        val locale = currentLocale()
        StatRow(
            stringResource(R.string.report_lowest),
            "${glucoseValue(glucose.min.valueMgdl, unit)} · ${Formatters.dateTime(glucose.min.measuredAt, locale)}",
        )
        StatRow(
            stringResource(R.string.report_highest),
            "${glucoseValue(glucose.max.valueMgdl, unit)} · ${Formatters.dateTime(glucose.max.measuredAt, locale)}",
        )
        Text(stringResource(R.string.report_zone_distribution), style = MaterialTheme.typography.titleSmall)
        glucose.zoneDistribution.forEach { (zoneName, count) ->
            val zone = runCatching { Zone.valueOf(zoneName) }.getOrNull()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    zone?.let { stringResource(it.labelRes()) } ?: zoneName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = zone?.color() ?: MaterialTheme.colorScheme.onSurface,
                )
                Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun BpSection(report: ReportModel) {
    SectionCard(
        stringResource(R.string.report_bp_summary),
        icon = androidx.compose.material.icons.Icons.Filled.Favorite,
    ) {
        val bp = report.bp
        if (bp == null) {
            Text(stringResource(R.string.report_no_data), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        StatRow(stringResource(R.string.report_readings_count), bp.count.toString())
        StatRow(
            stringResource(R.string.report_mean),
            bpValue(bp.meanSystolic.toInt(), bp.meanDiastolic.toInt()),
        )
        bp.morning?.let {
            StatRow(
                stringResource(R.string.report_morning),
                bpValue(it.meanSystolic.toInt(), it.meanDiastolic.toInt()),
            )
        }
        bp.evening?.let {
            StatRow(
                stringResource(R.string.report_evening),
                bpValue(it.meanSystolic.toInt(), it.meanDiastolic.toInt()),
            )
        }
        Text(stringResource(R.string.report_zone_distribution), style = MaterialTheme.typography.titleSmall)
        bp.zonePct.forEach { (zoneName, percentage) ->
            val zone = runCatching { Zone.valueOf(zoneName) }.getOrNull()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    zone?.let { stringResource(it.labelRes()) } ?: zoneName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = zone?.color() ?: MaterialTheme.colorScheme.onSurface,
                )
                Text(pct(percentage), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val locale = currentLocale()
        StatRow(
            stringResource(R.string.report_worst_reading),
            "${bpValue(bp.worst.systolic, bp.worst.diastolic)} · ${Formatters.dateTime(bp.worst.measuredAt, locale)}",
        )
    }
}

private fun trendText(delta: Float, formatted: String): String {
    val value = NumberUtils.bidiIsolate(if (delta > 0) "+$formatted" else formatted)
    return if (delta > 0) "▲ $value" else if (delta < 0) "▼ $value" else value
}

@Composable
private fun TrendsSection(report: ReportModel, unit: GlucoseUnit) {
    SectionCard(
        stringResource(R.string.report_trends),
        icon = androidx.compose.material.icons.Icons.AutoMirrored.Filled.TrendingUp,
    ) {
        val trends = report.trends
        val anyTrend = listOf(
            trends.fastingMeanDelta,
            trends.postMealMeanDelta,
            trends.systolicMeanDelta,
            trends.diastolicMeanDelta,
        ).any { it != null }
        if (!anyTrend) {
            Text(stringResource(R.string.report_no_trends), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        trends.fastingMeanDelta?.let { delta ->
            StatRow(
                stringResource(R.string.report_fasting_mean),
                trendText(delta, glucoseDelta(delta, unit)),
            )
        }
        trends.postMealMeanDelta?.let { delta ->
            StatRow(
                stringResource(R.string.report_postmeal_mean),
                trendText(delta, glucoseDelta(delta, unit)),
            )
        }
        trends.systolicMeanDelta?.let { delta ->
            StatRow(
                stringResource(R.string.report_systolic_mean),
                trendText(delta, String.format(Locale.US, "%.0f", delta)),
            )
        }
        trends.diastolicMeanDelta?.let { delta ->
            StatRow(
                stringResource(R.string.report_diastolic_mean),
                trendText(delta, String.format(Locale.US, "%.0f", delta)),
            )
        }
    }
}

private fun glucoseDelta(deltaMgdl: Float, unit: GlucoseUnit): String = when (unit) {
    GlucoseUnit.MGDL -> String.format(Locale.US, "%.0f", deltaMgdl)
    GlucoseUnit.MMOL -> String.format(Locale.US, "%.1f", UnitConverter.mgdlToMmol(deltaMgdl))
}

@Composable
private fun FlagsSection(report: ReportModel, viewModel: ReportViewModel) {
    if (report.flags.isEmpty()) return
    val languageTag = if (currentLocale().language == "ar") "ar" else "en"
    SectionCard(
        stringResource(R.string.report_flags),
        icon = androidx.compose.material.icons.Icons.Filled.Lightbulb,
    ) {
        report.flags.forEach { flagId ->
            viewModel.recommendations.text(flagId, languageTag)?.let { text ->
                Text(
                    text = NumberUtils.toLatinDigits(text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

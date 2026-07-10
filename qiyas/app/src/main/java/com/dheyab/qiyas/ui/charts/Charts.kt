package com.dheyab.qiyas.ui.charts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.core.NumberUtils
import com.dheyab.qiyas.core.UnitConverter
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.ui.common.currentLocale
import com.dheyab.qiyas.ui.theme.ZoneYellow
import com.dheyab.qiyas.ui.theme.color
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import com.patrykandpatrick.vico.core.common.shape.DashedShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Chart X values are hours since the window start; each reading keeps its own X
 * so per-point zone colors can be looked up exactly.
 */
private fun xOf(measuredAt: Long, windowStartMillis: Long): Double =
    (measuredAt - windowStartMillis) / 3_600_000.0

/** Colors each data point by its reading's zone (spec §12). */
private class ZonePointProvider(
    private val colorByX: Map<Double, Int>,
    private val fallbackColor: Int,
) : LineCartesianLayer.PointProvider {
    private fun point(argb: Int) = LineCartesianLayer.Point(
        component = ShapeComponent(fill = Fill(argb), shape = CorneredShape.Pill),
        sizeDp = 8f,
    )

    override fun getPoint(
        entry: LineCartesianLayerModel.Entry,
        seriesIndex: Int,
        extraStore: ExtraStore,
    ): LineCartesianLayer.Point = point(colorByX[entry.x] ?: fallbackColor)

    override fun getLargestPoint(extraStore: ExtraStore): LineCartesianLayer.Point = point(fallbackColor)
}

private fun dashedGuide(argb: Int): LineComponent =
    LineComponent(fill = Fill(argb), thicknessDp = 1f, shape = DashedShape())

private fun guideLine(y: Double, argb: Int): HorizontalLine =
    HorizontalLine(y = { y }, line = dashedGuide(argb), label = { "" })

/** Bottom-axis labels: narrow weekday initial in the UI locale + Latin-digit day of month. */
private fun dayFormatter(windowStartMillis: Long, locale: Locale): CartesianValueFormatter {
    val dayInitial = DateTimeFormatter.ofPattern("EEEEE", locale)
    val zone = ZoneId.systemDefault()
    return CartesianValueFormatter { _, value, _ ->
        val instant = Instant.ofEpochMilli(windowStartMillis + (value * 3_600_000).toLong())
        val date = instant.atZone(zone).toLocalDate()
        NumberUtils.toLatinDigits("${dayInitial.format(date)} ${date.dayOfMonth}")
    }
}

/** Latin-digit Y labels (Hard Rule 5). */
private val latinYFormatter = CartesianValueFormatter { _, value, _ ->
    if (value == value.toLong().toDouble()) {
        String.format(Locale.US, "%d", value.toLong())
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

/**
 * Glucose line chart with zone-colored points and dashed guides at 70/130/180
 * mg/dL (converted for mmol/L display). Time axis stays LTR in both languages
 * (spec §3/§12).
 */
@Composable
fun GlucoseChart(
    readings: List<Reading>,
    unit: GlucoseUnit,
    windowStartMillis: Long,
    modifier: Modifier = Modifier,
    showAxes: Boolean = true,
) {
    if (readings.isEmpty()) return
    val locale = currentLocale()
    val sorted = remember(readings) { readings.sortedBy { it.measuredAt } }
    val modelProducer = remember { CartesianChartModelProducer() }

    val xs = sorted.map { xOf(it.measuredAt, windowStartMillis) }
    val ys = sorted.map { UnitConverter.fromCanonicalMgdl(it.glucoseMgdl ?: 0f, unit) }
    val colorByX = sorted.associate { xOf(it.measuredAt, windowStartMillis) to it.zone.color().toArgb() }

    LaunchedEffect(sorted, unit) {
        modelProducer.runTransaction { lineSeries { series(xs, ys) } }
    }

    val guides = listOf(70f, 130f, 180f).map { mgdl ->
        UnitConverter.fromCanonicalMgdl(mgdl, unit).toDouble()
    }
    val dataMin = ys.min().toDouble()
    val dataMax = ys.max().toDouble()
    val minY = minOf(dataMin, guides.first()) * 0.9
    val maxY = maxOf(dataMax, guides.last()) * 1.05

    val lineColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val pointProvider = remember(colorByX) { ZonePointProvider(colorByX, ZoneYellow.toArgb()) }

    ForceLtr {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(lineColor)),
                            pointProvider = pointProvider,
                        ),
                    ),
                    rangeProvider = remember(minY, maxY) {
                        CartesianLayerRangeProvider.fixed(minY = minY, maxY = maxY)
                    },
                ),
                startAxis = if (showAxes) {
                    VerticalAxis.rememberStart(valueFormatter = latinYFormatter)
                } else null,
                bottomAxis = if (showAxes) {
                    HorizontalAxis.rememberBottom(
                        valueFormatter = remember(windowStartMillis, locale) {
                            dayFormatter(windowStartMillis, locale)
                        },
                    )
                } else null,
                decorations = guides.map { guideLine(it, ZoneYellow.toArgb()) },
            ),
            modelProducer = modelProducer,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            modifier = modifier.fillMaxSize(),
        )
    }
}

/** Dual-line systolic/diastolic chart with dashed guides at 130/80 and 140/90 (spec §12). */
@Composable
fun BpChart(
    readings: List<Reading>,
    windowStartMillis: Long,
    modifier: Modifier = Modifier,
    showAxes: Boolean = true,
) {
    if (readings.isEmpty()) return
    val locale = currentLocale()
    val sorted = remember(readings) { readings.sortedBy { it.measuredAt } }
    val modelProducer = remember { CartesianChartModelProducer() }

    val xs = sorted.map { xOf(it.measuredAt, windowStartMillis) }
    val sys = sorted.map { it.systolic ?: 0 }
    val dia = sorted.map { it.diastolic ?: 0 }

    LaunchedEffect(sorted) {
        modelProducer.runTransaction {
            lineSeries {
                series(xs, sys)
                series(xs, dia)
            }
        }
    }

    val guides = listOf(80.0, 90.0, 130.0, 140.0)
    val minY = minOf(dia.min().toDouble(), 60.0) * 0.9
    val maxY = maxOf(sys.max().toDouble(), 150.0) * 1.05

    val sysColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val diaColor = androidx.compose.material3.MaterialTheme.colorScheme.secondary

    ForceLtr {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(sysColor)),
                        ),
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(diaColor)),
                        ),
                    ),
                    rangeProvider = remember(minY, maxY) {
                        CartesianLayerRangeProvider.fixed(minY = minY, maxY = maxY)
                    },
                ),
                startAxis = if (showAxes) {
                    VerticalAxis.rememberStart(valueFormatter = latinYFormatter)
                } else null,
                bottomAxis = if (showAxes) {
                    HorizontalAxis.rememberBottom(
                        valueFormatter = remember(windowStartMillis, locale) {
                            dayFormatter(windowStartMillis, locale)
                        },
                    )
                } else null,
                decorations = guides.map { guideLine(it, ZoneYellow.toArgb()) },
            ),
            modelProducer = modelProducer,
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            modifier = modifier.fillMaxSize(),
        )
    }
}

/** Charts keep time flowing left-to-right in both languages (spec §3). */
@Composable
fun ForceLtr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

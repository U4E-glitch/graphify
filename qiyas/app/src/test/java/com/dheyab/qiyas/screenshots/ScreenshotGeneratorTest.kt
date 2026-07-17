package com.dheyab.qiyas.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.dheyab.qiyas.data.repo.ReportRepository
import com.dheyab.qiyas.domain.model.BpContext
import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import com.dheyab.qiyas.integration.PlainTestApp
import com.dheyab.qiyas.integration.TestGraph
import com.dheyab.qiyas.ui.dashboard.DashboardScreen
import com.dheyab.qiyas.ui.dashboard.DashboardViewModel
import com.dheyab.qiyas.ui.entry.AddGlucoseScreen
import com.dheyab.qiyas.ui.entry.GlucoseEntryViewModel
import com.dheyab.qiyas.ui.history.HistoryScreen
import com.dheyab.qiyas.ui.history.HistoryViewModel
import com.dheyab.qiyas.ui.report.ReportScreen
import com.dheyab.qiyas.ui.report.ReportViewModel
import com.dheyab.qiyas.ui.theme.QiyasTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens with realistic sample data and saves PNGs for the
 * Play Store listing (playstore/graphics/screenshots). Runs as part of the
 * unit-test suite; rendering is Robolectric native graphics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlainTestApp::class, qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotGeneratorTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        graph = TestGraph(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { seedSampleWeek() }
    }

    private suspend fun seedSampleWeek() {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        fun glucose(mgdl: Float, context: GlucoseContext, daysAgo: Int, hourOffset: Long = 0) = Reading(
            profileId = 1,
            type = ReadingType.GLUCOSE,
            measuredAt = now - daysAgo * day - hourOffset,
            createdAt = now,
            glucoseMgdl = mgdl,
            glucoseContext = context,
            zone = graph.classifier.classifyGlucose(mgdl, context).zone,
        )
        fun bp(sys: Int, dia: Int, context: BpContext, daysAgo: Int, hourOffset: Long = 0) = Reading(
            profileId = 1,
            type = ReadingType.BP,
            measuredAt = now - daysAgo * day - hourOffset,
            createdAt = now,
            systolic = sys,
            diastolic = dia,
            pulse = 68 + daysAgo,
            bpContext = context,
            zone = graph.classifier.classifyBp(sys, dia).zone,
        )
        listOf(
            glucose(96f, GlucoseContext.FASTING, 6), glucose(148f, GlucoseContext.POST_MEAL_2H, 6, 3_600_000),
            glucose(104f, GlucoseContext.FASTING, 5), glucose(165f, GlucoseContext.POST_MEAL_1H, 5, 3_600_000),
            glucose(92f, GlucoseContext.FASTING, 4),
            glucose(138f, GlucoseContext.FASTING, 3), glucose(188f, GlucoseContext.POST_MEAL_2H, 3, 3_600_000),
            glucose(99f, GlucoseContext.FASTING, 2), glucose(112f, GlucoseContext.BEDTIME, 2, 7_200_000),
            glucose(101f, GlucoseContext.FASTING, 1), glucose(154f, GlucoseContext.POST_MEAL_2H, 1, 3_600_000),
            glucose(97f, GlucoseContext.FASTING, 0),
            bp(118, 76, BpContext.MORNING, 6), bp(124, 80, BpContext.EVENING, 6, 3_600_000),
            bp(121, 78, BpContext.MORNING, 4), bp(132, 84, BpContext.EVENING, 4, 3_600_000),
            bp(117, 75, BpContext.MORNING, 2), bp(126, 81, BpContext.EVENING, 2, 3_600_000),
            bp(119, 77, BpContext.MORNING, 0),
        ).forEach { graph.readingRepository.insert(it) }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File(
            if (File("../playstore").exists()) "../playstore/graphics/screenshots"
            else "playstore/graphics/screenshots"
        ).apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(30)
            compose.waitForIdle()
        }
        compose.waitForIdle()
    }

    /** Software capture: draws the activity's decor view into a bitmap. */
    private fun capture(name: String) {
        compose.waitForIdle()
        val decor: View = compose.activity.window.decorView
        var width = decor.width
        var height = decor.height
        if (width == 0 || height == 0) {
            width = 1080
            height = 2400
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, width, height)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        save(bitmap, name)
    }

    @Test
    fun dashboard_en() {
        val vm = DashboardViewModel(graph.readingRepository, graph.settingsRepository)
        compose.setContent {
            QiyasTheme {
                DashboardScreen(null, {}, {}, {}, {}, {}, {}, viewModel = vm)
            }
        }
        await { vm.latestGlucose.value != null && vm.latestBp.value != null }
        capture("01_dashboard_en")
    }

    @Test
    fun add_glucose_en() {
        val vm = GlucoseEntryViewModel(
            graph.readingRepository, graph.settingsRepository, graph.classifier,
            graph.photoStore, graph.scanner, SavedStateHandle(),
        )
        compose.setContent {
            QiyasTheme { AddGlucoseScreen(onDone = {}, onBack = {}, viewModel = vm) }
        }
        vm.onValueChanged("112")
        vm.onContextSelected(GlucoseContext.FASTING)
        capture("02_add_glucose_en")
    }

    @Test
    fun history_en() {
        val vm = HistoryViewModel(graph.readingRepository, graph.settingsRepository, graph.photoStore)
        compose.setContent {
            QiyasTheme { HistoryScreen(null, {}, {}, {}, viewModel = vm) }
        }
        await { vm.readings.value.isNotEmpty() }
        capture("03_history_en")
    }

    @Test
    fun report_en() {
        val reportRepository = ReportRepository(
            graph.readingRepository, graph.database.weeklyReportDao(), graph.settingsRepository,
        )
        val vm = ReportViewModel(reportRepository, graph.settingsRepository, graph.recommendations)
        compose.setContent {
            QiyasTheme { ReportScreen(onBack = {}, onExportPdf = {}, onExportCsv = {}, viewModel = vm) }
        }
        await { vm.state.value.report != null }
        capture("04_report_en")
    }

    @Test
    @Config(qualifiers = "+ar")
    fun dashboard_ar() {
        val vm = DashboardViewModel(graph.readingRepository, graph.settingsRepository)
        compose.setContent {
            QiyasTheme {
                DashboardScreen(null, {}, {}, {}, {}, {}, {}, viewModel = vm)
            }
        }
        await { vm.latestGlucose.value != null && vm.latestBp.value != null }
        capture("05_dashboard_ar")
    }

    @Test
    @Config(qualifiers = "+ar")
    fun report_ar() {
        val reportRepository = ReportRepository(
            graph.readingRepository, graph.database.weeklyReportDao(), graph.settingsRepository,
        )
        val vm = ReportViewModel(reportRepository, graph.settingsRepository, graph.recommendations)
        compose.setContent {
            QiyasTheme { ReportScreen(onBack = {}, onExportPdf = {}, onExportCsv = {}, viewModel = vm) }
        }
        await { vm.state.value.report != null }
        capture("06_report_ar")
    }
}

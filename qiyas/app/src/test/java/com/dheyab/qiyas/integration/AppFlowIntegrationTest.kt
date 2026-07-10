package com.dheyab.qiyas.integration

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.data.db.DEFAULT_PROFILE_ID
import com.dheyab.qiyas.data.repo.ReportRepository
import com.dheyab.qiyas.domain.model.BpContext
import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.ReadingType
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.domain.report.ReportBuilder
import com.dheyab.qiyas.export.CsvExporter
import com.dheyab.qiyas.export.PdfExporter
import com.dheyab.qiyas.ui.entry.BpEntryViewModel
import com.dheyab.qiyas.ui.entry.GlucoseEntryViewModel
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end self-test of the real app flows on the JVM (Robolectric):
 * real SQLite, real shipped assets, real ViewModels — no mocks.
 *
 * ViewModel saves run asynchronously (Room executors), so state changes are
 * awaited with [awaitTrue].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlainTestApp::class)
class AppFlowIntegrationTest {

    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        graph = TestGraph(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun awaitTrue(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertWithMessage("timed out waiting for: $message").that(condition()).isTrue()
    }

    private fun newGlucoseVm(readingId: Long? = null) = GlucoseEntryViewModel(
        graph.readingRepository,
        graph.settingsRepository,
        graph.classifier,
        readingId?.let { SavedStateHandle(mapOf("readingId" to it)) } ?: SavedStateHandle(),
    )

    private fun awaitSaveFinished(vm: GlucoseEntryViewModel) =
        awaitTrue("glucose save to finish") {
            vm.state.value.alert != null || vm.state.value.closeWithZone != null
        }

    private fun awaitSaveFinished(vm: BpEntryViewModel) =
        awaitTrue("bp save to finish") {
            vm.state.value.alert != null || vm.state.value.closeWithZone != null
        }

    @Test
    fun defaultProfileIsSeededOnFirstOpen() = runBlocking {
        graph.readingRepository.observeAll(DEFAULT_PROFILE_ID).first()
        assertThat(graph.database.profileDao().getById(DEFAULT_PROFILE_ID)).isNotNull()
    }

    @Test
    fun glucoseSaveFlow_normalReadingPersistsWithZone() = runBlocking {
        val vm = newGlucoseVm()
        vm.onValueChanged("100")
        vm.onContextSelected(GlucoseContext.FASTING)
        assertThat(vm.state.value.canSave).isTrue()
        vm.save()
        awaitSaveFinished(vm)

        assertThat(vm.state.value.alert).isNull()
        assertThat(vm.state.value.closeWithZone).isEqualTo(Zone.IN_RANGE)
        val saved = graph.readingRepository.observeAll(DEFAULT_PROFILE_ID).first().single()
        assertThat(saved.glucoseMgdl).isEqualTo(100f)
        assertThat(saved.zone).isEqualTo(Zone.IN_RANGE)
    }

    @Test
    fun glucoseSaveFlow_saveDisabledWithoutContext() {
        val vm = newGlucoseVm()
        vm.onValueChanged("100")
        // Hard Rule 2: value alone is not enough.
        assertThat(vm.state.value.canSave).isFalse()
        vm.onContextSelected(GlucoseContext.RANDOM)
        assertThat(vm.state.value.canSave).isTrue()
    }

    @Test
    fun glucoseSaveFlow_emergencyLowRaisesBlockingAlertAndStillSaves() = runBlocking {
        val vm = newGlucoseVm()
        vm.onValueChanged("58")
        vm.onContextSelected(GlucoseContext.RANDOM)
        vm.save()
        awaitSaveFinished(vm)

        // Alert first, reading saved regardless (spec §8).
        assertThat(vm.state.value.alert?.zone).isEqualTo(Zone.EMERGENCY_LOW)
        assertThat(vm.state.value.closeWithZone).isNull()
        val saved = graph.readingRepository.observeAll(DEFAULT_PROFILE_ID).first().single()
        assertThat(saved.zone).isEqualTo(Zone.EMERGENCY_LOW)

        vm.onAlertUnderstood()
        assertThat(vm.state.value.closeWithZone).isEqualTo(Zone.EMERGENCY_LOW)
    }

    @Test
    fun glucoseSaveFlow_mmolInputStoresCanonicalMgdl() = runBlocking {
        graph.settingsRepository.setGlucoseUnit(GlucoseUnit.MMOL)
        val vm = newGlucoseVm()
        awaitTrue("unit setting to load") { vm.state.value.unit == GlucoseUnit.MMOL }

        vm.onValueChanged("٥٫٥") // Eastern Arabic "5.5" — normalization + conversion in one flow
        vm.onContextSelected(GlucoseContext.FASTING)
        assertThat(vm.state.value.canSave).isTrue()
        vm.save()
        awaitSaveFinished(vm)

        val saved = graph.readingRepository.observeAll(DEFAULT_PROFILE_ID).first().single()
        assertThat(saved.glucoseMgdl!!).isWithin(0.01f).of(99.088f) // canonical mg/dL (Hard Rule 3)
        assertThat(saved.zone).isEqualTo(Zone.IN_RANGE)
    }

    @Test
    fun editFlow_recomputesZoneAndFiresAlertWhenMovedIntoEmergency() = runBlocking {
        val addVm = newGlucoseVm()
        addVm.onValueChanged("100")
        addVm.onContextSelected(GlucoseContext.FASTING)
        addVm.save()
        awaitSaveFinished(addVm)
        val id = graph.readingRepository.observeAll(DEFAULT_PROFILE_ID).first().single().id

        val editVm = newGlucoseVm(readingId = id)
        assertThat(editVm.state.value.isEdit).isTrue()
        awaitTrue("edit prefill to load") { editVm.state.value.valueText == "100" }

        editVm.onValueChanged("45")
        editVm.save()
        awaitSaveFinished(editVm)

        assertThat(editVm.state.value.alert?.zone).isEqualTo(Zone.EMERGENCY_LOW_SEVERE)
        val updated = graph.readingRepository.getById(id)!!
        assertThat(updated.zone).isEqualTo(Zone.EMERGENCY_LOW_SEVERE)
        assertThat(updated.glucoseMgdl).isEqualTo(45f)
    }

    @Test
    fun bpSaveFlow_crisisOffersFollowUpAndValidatesSysOverDia() = runBlocking {
        val vm = BpEntryViewModel(graph.readingRepository, graph.classifier, SavedStateHandle())

        // systolic <= diastolic errors on both fields (spec §6)
        vm.onSystolicChanged("80")
        vm.onDiastolicChanged("95")
        assertThat(vm.state.value.systolicError).isNotNull()
        assertThat(vm.state.value.diastolicError).isNotNull()

        vm.onSystolicChanged("185")
        vm.onDiastolicChanged("95")
        assertThat(vm.state.value.systolicError).isNull()
        vm.onContextSelected(BpContext.MORNING)
        vm.save()
        awaitSaveFinished(vm)

        assertThat(vm.state.value.alert?.zone).isEqualTo(Zone.CRISIS)
        vm.onAddFollowUp()
        assertThat(vm.state.value.goToFollowUp).isTrue()
        val saved = graph.readingRepository.observeAll(DEFAULT_PROFILE_ID).first().single()
        assertThat(saved.zone).isEqualTo(Zone.CRISIS)
    }

    @Test
    fun deleteRemovesReading() = runBlocking {
        val vm = newGlucoseVm()
        vm.onValueChanged("100")
        vm.onContextSelected(GlucoseContext.FASTING)
        vm.save()
        awaitSaveFinished(vm)
        val id = graph.readingRepository.observeAll(DEFAULT_PROFILE_ID).first().single().id

        graph.readingRepository.delete(id)
        assertThat(graph.readingRepository.observeAll(DEFAULT_PROFILE_ID).first()).isEmpty()
    }

    @Test
    fun reportPipeline_generatesPersistsAndFlags() = runBlocking {
        val reportRepository = ReportRepository(
            graph.readingRepository, graph.database.weeklyReportDao(), graph.settingsRepository,
        )
        val weekStart = reportRepository.currentWeekStart()

        // Log a realistic week through the real entry flow: 4 fasting + a hypo + 4 BP.
        fun logGlucose(value: String, context: GlucoseContext) {
            val vm = newGlucoseVm()
            vm.onValueChanged(value)
            vm.onContextSelected(context)
            vm.save()
            awaitSaveFinished(vm)
        }
        fun logBp(sys: String, dia: String, context: BpContext) {
            val vm = BpEntryViewModel(graph.readingRepository, graph.classifier, SavedStateHandle())
            vm.onSystolicChanged(sys)
            vm.onDiastolicChanged(dia)
            vm.onContextSelected(context)
            vm.save()
            awaitSaveFinished(vm)
        }
        logGlucose("100", GlucoseContext.FASTING)
        logGlucose("110", GlucoseContext.FASTING)
        logGlucose("120", GlucoseContext.FASTING)
        logGlucose("95", GlucoseContext.FASTING)
        logGlucose("62", GlucoseContext.RANDOM) // hypo event
        logBp("120", "80", BpContext.MORNING)
        logBp("118", "78", BpContext.MORNING)
        logBp("115", "75", BpContext.EVENING)
        logBp("117", "76", BpContext.EVENING)

        val report = reportRepository.generate(DEFAULT_PROFILE_ID, weekStart)

        assertThat(report.glucoseCount).isEqualTo(5)
        assertThat(report.bpCount).isEqualTo(4)
        assertThat(report.flags).contains(ReportBuilder.FLAG_HYPO_EVENT)
        assertThat(report.flags).doesNotContain(ReportBuilder.FLAG_ALL_IN_RANGE)
        assertThat(report.glucose!!.groups).containsKey("FASTING_PRE_MEAL")

        // Persisted row exists and every fired flag has bilingual copy.
        val row = graph.database.weeklyReportDao().getByWeek(DEFAULT_PROFILE_ID, report.weekStartDate)
        assertThat(row).isNotNull()
        assertThat(row!!.jsonPayload).contains("HYPO_EVENT")
        for (flag in report.flags) {
            assertThat(graph.recommendations.text(flag, "en")).isNotEmpty()
            assertThat(graph.recommendations.text(flag, "ar")).isNotEmpty()
        }
    }

    @Test
    fun csvExport_producesBomAndCorrectRows() = runBlocking {
        val vm = newGlucoseVm()
        vm.onValueChanged("100")
        vm.onContextSelected(GlucoseContext.FASTING)
        vm.save()
        awaitSaveFinished(vm)

        val readings = graph.readingRepository.allForExport(DEFAULT_PROFILE_ID)
        assertThat(readings).hasSize(1)
        val bytes = CsvExporter().buildCsvBytes(readings, GlucoseUnit.MGDL)

        // UTF-8 BOM (spec §11 Excel Arabic compatibility)
        assertThat(bytes[0]).isEqualTo(0xEF.toByte())
        assertThat(bytes[1]).isEqualTo(0xBB.toByte())
        assertThat(bytes[2]).isEqualTo(0xBF.toByte())

        val text = bytes.decodeToString().removePrefix("﻿")
        val lines = text.trim().split("\r\n")
        assertThat(lines[0]).isEqualTo(
            "type,measured_at_iso8601,glucose_mgdl,glucose_display_value,glucose_unit," +
                "glucose_context,systolic,diastolic,pulse,bp_context,zone,note"
        )
        assertThat(lines[1]).contains("GLUCOSE")
        assertThat(lines[1]).contains(",100,100,MGDL,FASTING")
        assertThat(lines[1]).contains("IN_RANGE")
    }

    @Test
    fun pdfContent_hasVerbatimFooterAndLatinDigits_inBothLanguages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reportRepository = ReportRepository(
            graph.readingRepository, graph.database.weeklyReportDao(), graph.settingsRepository,
        )
        graph.readingRepository.insert(
            com.dheyab.qiyas.domain.model.Reading(
                profileId = DEFAULT_PROFILE_ID,
                type = ReadingType.GLUCOSE,
                measuredAt = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis(),
                glucoseMgdl = 62f,
                glucoseContext = GlucoseContext.RANDOM,
                zone = Zone.EMERGENCY_LOW,
            )
        )
        val report = reportRepository.generate(DEFAULT_PROFILE_ID, reportRepository.currentWeekStart())
        val exporter = PdfExporter(graph.recommendations)

        // English rendering
        val enLines = exporter.buildLines(context, report, GlucoseUnit.MGDL, Locale.ENGLISH)
            .map { it.text }
        assertThat(enLines).contains(
            "This report is for personal tracking and is not medical advice. Review it with your physician."
        )
        assertThat(enLines.any { it.contains("62 mg/dL") }).isTrue()

        // Arabic rendering: verbatim footer + Latin digits only (Hard Rule 5)
        val arContext = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(Locale("ar"))
            }
        )
        val arLines = exporter.buildLines(arContext, report, GlucoseUnit.MGDL, Locale("ar"))
            .map { it.text }
        assertThat(arLines).contains(
            "هذا التقرير لغرض التتبع الشخصي وليس نصيحة طبية. راجعه مع طبيبك."
        )
        val easternDigits = '٠'..'٩'
        for (line in arLines) {
            assertWithMessage("Eastern digits leaked into PDF line: $line")
                .that(line.any { it in easternDigits })
                .isFalse()
        }

        // BiDi regression: min/max lines carry FSI…PDI isolates around each numeric
        // fragment so Arabic text can't reorder "62 mg/dL · <date>".
        val minLine = arLines.first { it.startsWith("الأدنى") }
        assertThat(minLine).contains("⁨62 mg/dL⁩")
        assertThat(minLine.count { it == '⁨' }).isEqualTo(2) // value + date
    }
}

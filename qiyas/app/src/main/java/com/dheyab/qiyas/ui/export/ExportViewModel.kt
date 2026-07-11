package com.dheyab.qiyas.ui.export

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dheyab.qiyas.data.db.DEFAULT_PROFILE_ID
import com.dheyab.qiyas.data.repo.ReadingRepository
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.domain.report.ReportModel
import com.dheyab.qiyas.export.CsvExporter
import com.dheyab.qiyas.export.PdfExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Writes CSV/PDF exports to SAF documents picked via ACTION_CREATE_DOCUMENT (spec §11). */
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val readingRepository: ReadingRepository,
    private val settingsRepository: SettingsRepository,
    private val csvExporter: CsvExporter,
    private val pdfExporter: PdfExporter,
) : ViewModel() {

    fun csvFileName(): String =
        "qiyas_export_${LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))}.csv"

    fun pdfFileName(report: ReportModel): String = "qiyas_report_${report.weekStartDate}.pdf"

    fun writeCsv(context: Context, uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val unit = settingsRepository.settings.first().glucoseUnit
                val readings = readingRepository.allForExport(DEFAULT_PROFILE_ID)
                val bytes = csvExporter.buildCsvBytes(readings, unit)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
        }
    }

    fun writePdf(context: Context, uri: Uri, report: ReportModel) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val unit = settingsRepository.settings.first().glucoseUnit
                val bytes = pdfExporter.build(context, report, unit)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
        }
    }
}

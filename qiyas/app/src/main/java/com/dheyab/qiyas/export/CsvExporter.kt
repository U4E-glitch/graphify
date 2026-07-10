package com.dheyab.qiyas.export

import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.core.UnitConverter
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CSV export (spec §11): all readings, canonical mg/dL plus the display value
 * in the current unit, Latin digits, ISO-8601 timestamps. The UTF-8 BOM is
 * prepended at write time for Excel Arabic compatibility.
 */
@Singleton
class CsvExporter @Inject constructor() {

    private val header = listOf(
        "type", "measured_at_iso8601", "glucose_mgdl", "glucose_display_value", "glucose_unit",
        "glucose_context", "systolic", "diastolic", "pulse", "bp_context", "zone", "note",
    )

    fun buildCsv(readings: List<Reading>, unit: GlucoseUnit): String {
        val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val zone = ZoneId.systemDefault()
        val sb = StringBuilder()
        sb.append(header.joinToString(",")).append("\r\n")
        for (r in readings) {
            val cells = listOf(
                r.type.name,
                isoFormatter.format(Instant.ofEpochMilli(r.measuredAt).atZone(zone)),
                if (r.type == ReadingType.GLUCOSE) UnitConverter.formatMgdl(r.glucoseMgdl ?: 0f) else "",
                if (r.type == ReadingType.GLUCOSE) UnitConverter.formatCanonical(r.glucoseMgdl ?: 0f, unit) else "",
                if (r.type == ReadingType.GLUCOSE) unit.name else "",
                r.glucoseContext?.name.orEmpty(),
                r.systolic?.toString().orEmpty(),
                r.diastolic?.toString().orEmpty(),
                r.pulse?.toString().orEmpty(),
                r.bpContext?.name.orEmpty(),
                r.zone.name,
                r.note.orEmpty(),
            )
            sb.append(cells.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** Bytes ready for ACTION_CREATE_DOCUMENT: UTF-8 with BOM (spec §11). */
    fun buildCsvBytes(readings: List<Reading>, unit: GlucoseUnit): ByteArray =
        ("\uFEFF" + buildCsv(readings, unit)).toByteArray(Charsets.UTF_8)

    private fun escape(cell: String): String =
        if (cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${cell.replace("\"", "\"\"")}\""
        } else {
            cell
        }
}

package com.dheyab.qiyas.domain.ocr

import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.core.NumberUtils
import com.dheyab.qiyas.domain.InputLimits

/** One recognized text fragment with its rough position/size on the photo. */
data class OcrToken(
    val text: String,
    val top: Int,
    val height: Int,
)

data class BpCandidate(
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
)

/**
 * Pure-Kotlin extraction of meter values from OCR tokens (spec v1 photo/OCR input).
 *
 * OCR output from seven-segment displays is noisy, so results are treated as
 * PRE-FILL ONLY — the user must always review before saving. Heuristics:
 * the main reading is the tallest plausible number; time/date-looking tokens
 * are ignored.
 */
object MeterValueParser {

    private val numberPattern = Regex("""\d+(?:\.\d+)?""")

    private data class Candidate(val value: Float, val top: Int, val height: Int)

    private fun candidates(tokens: List<OcrToken>): List<Candidate> =
        tokens.flatMap { token ->
            val normalized = NumberUtils.normalizeDigits(token.text)
            // Skip time/date/ratio fragments like 12:30, 07/2026.
            if (normalized.contains(':') || normalized.contains('/')) return@flatMap emptyList()
            numberPattern.findAll(normalized).mapNotNull { match ->
                match.value.toFloatOrNull()?.let { Candidate(it, token.top, token.height) }
            }.toList()
        }

    /**
     * Picks the glucose value in the current display unit: the tallest token
     * whose value is inside the §6 input range for that unit.
     */
    fun parseGlucose(tokens: List<OcrToken>, unit: GlucoseUnit): Float? {
        val range = InputLimits.glucoseRange(unit)
        val plausible = candidates(tokens).filter { it.value in range }
        // mmol displays always show one decimal; prefer decimal-looking values there.
        val preferred = if (unit == GlucoseUnit.MMOL) {
            plausible.filter { it.value != it.value.toInt().toFloat() }.ifEmpty { plausible }
        } else {
            plausible.filter { it.value == it.value.toInt().toFloat() }.ifEmpty { plausible }
        }
        return preferred.maxByOrNull { it.height }?.value
    }

    /**
     * Picks systolic/diastolic(/pulse) from a BP monitor photo. Monitors stack
     * the values vertically: systolic on top, diastolic below, pulse last —
     * so plausible integers are assigned in top-to-bottom order, enforcing
     * systolic > diastolic.
     */
    fun parseBp(tokens: List<OcrToken>): BpCandidate? {
        val ints = candidates(tokens)
            .filter { it.value == it.value.toInt().toFloat() }
            .map { Candidate(it.value, it.top, it.height) }
            .sortedBy { it.top }

        val systolic = ints.firstOrNull { it.value.toInt() in InputLimits.SYSTOLIC } ?: return null
        val below = ints.filter { it.top > systolic.top }
        val diastolic = below.firstOrNull {
            it.value.toInt() in InputLimits.DIASTOLIC && it.value < systolic.value
        } ?: return null
        val pulse = below.firstOrNull {
            it.top > diastolic.top && it.value.toInt() in InputLimits.PULSE
        }
        return BpCandidate(
            systolic = systolic.value.toInt(),
            diastolic = diastolic.value.toInt(),
            pulse = pulse?.value?.toInt(),
        )
    }
}

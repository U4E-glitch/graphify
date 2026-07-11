package com.dheyab.qiyas.domain.ocr

import com.dheyab.qiyas.core.GlucoseUnit
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Photo-scan value extraction (v1 photo/OCR input): pure-Kotlin heuristics. */
class MeterValueParserTest {

    private fun token(text: String, top: Int = 0, height: Int = 10) = OcrToken(text, top, height)

    // ---- Glucose ----

    @Test
    fun glucosePicksTheTallestPlausibleNumber() {
        val tokens = listOf(
            token("GLUCOSE", top = 0, height = 12),
            token("128", top = 40, height = 90),      // the big display value
            token("12:30", top = 140, height = 14),   // clock — ignored
            token("07/2026", top = 160, height = 14), // date — ignored
        )
        assertThat(MeterValueParser.parseGlucose(tokens, GlucoseUnit.MGDL)).isEqualTo(128f)
    }

    @Test
    fun glucoseIgnoresOutOfRangeNumbers() {
        val tokens = listOf(
            token("9999", top = 10, height = 80), // implausible
            token("105", top = 100, height = 40),
        )
        assertThat(MeterValueParser.parseGlucose(tokens, GlucoseUnit.MGDL)).isEqualTo(105f)
    }

    @Test
    fun glucoseMmolPrefersDecimalValues() {
        val tokens = listOf(
            token("22", top = 0, height = 60),   // battery %, plausible in mmol range but integer
            token("5.8", top = 40, height = 50), // real mmol reading
        )
        assertThat(MeterValueParser.parseGlucose(tokens, GlucoseUnit.MMOL)).isEqualTo(5.8f)
    }

    @Test
    fun glucoseNormalizesEasternArabicDigits() {
        val tokens = listOf(token("١٢٨", top = 0, height = 50))
        assertThat(MeterValueParser.parseGlucose(tokens, GlucoseUnit.MGDL)).isEqualTo(128f)
    }

    @Test
    fun glucoseReturnsNullWhenNothingPlausible() {
        val tokens = listOf(token("mem"), token("12:45"), token("---"))
        assertThat(MeterValueParser.parseGlucose(tokens, GlucoseUnit.MGDL)).isNull()
    }

    // ---- Blood pressure ----

    @Test
    fun bpAssignsTopToBottomSysDiaPulse() {
        val tokens = listOf(
            token("SYS", top = 0),
            token("132", top = 20, height = 60),
            token("DIA", top = 90),
            token("84", top = 110, height = 55),
            token("PUL", top = 180),
            token("72", top = 200, height = 40),
        )
        val result = MeterValueParser.parseBp(tokens)!!
        assertThat(result.systolic).isEqualTo(132)
        assertThat(result.diastolic).isEqualTo(84)
        assertThat(result.pulse).isEqualTo(72)
    }

    @Test
    fun bpWorksWithoutPulse() {
        val tokens = listOf(
            token("120", top = 10, height = 60),
            token("80", top = 100, height = 55),
        )
        val result = MeterValueParser.parseBp(tokens)!!
        assertThat(result.systolic).isEqualTo(120)
        assertThat(result.diastolic).isEqualTo(80)
        assertThat(result.pulse).isNull()
    }

    @Test
    fun bpEnforcesSystolicGreaterThanDiastolic() {
        // A stray large number below systolic must not be taken as diastolic.
        val tokens = listOf(
            token("135", top = 10, height = 60),
            token("140", top = 60, height = 20), // e.g. a target/reference figure
            token("88", top = 120, height = 55),
        )
        val result = MeterValueParser.parseBp(tokens)!!
        assertThat(result.systolic).isEqualTo(135)
        assertThat(result.diastolic).isEqualTo(88)
    }

    @Test
    fun bpIgnoresTimeTokens() {
        val tokens = listOf(
            token("07:45", top = 0, height = 14),
            token("118", top = 30, height = 60),
            token("76", top = 110, height = 55),
        )
        val result = MeterValueParser.parseBp(tokens)!!
        assertThat(result.systolic).isEqualTo(118)
        assertThat(result.diastolic).isEqualTo(76)
    }

    @Test
    fun bpReturnsNullWithoutBothValues() {
        assertThat(MeterValueParser.parseBp(listOf(token("120", top = 0, height = 60)))).isNull()
        assertThat(MeterValueParser.parseBp(emptyList())).isNull()
    }
}

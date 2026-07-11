package com.dheyab.qiyas.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Mandatory test 2 (spec §13): unit conversion round-trips + rounding at §7 boundaries. */
class UnitConverterTest {

    @Test
    fun specRoundTripExamples() {
        // 5.5 mmol/L -> 99 mg/dL
        assertThat(UnitConverter.formatMgdl(UnitConverter.mmolToMgdl(5.5f))).isEqualTo("99")
        // 70 mg/dL -> 3.9 mmol/L
        assertThat(UnitConverter.formatMmol(UnitConverter.mgdlToMmol(70f))).isEqualTo("3.9")
    }

    @Test
    fun boundaryValuesConvertAndFormat() {
        // Every §7 glucose boundary in mg/dL and its mmol/L display value.
        val expected = mapOf(
            54f to "3.0",
            70f to "3.9",
            80f to "4.4",
            130f to "7.2",
            180f to "10.0",
            250f to "13.9",
            300f to "16.7",
        )
        for ((mgdl, mmolText) in expected) {
            assertThat(UnitConverter.formatMmol(UnitConverter.mgdlToMmol(mgdl))).isEqualTo(mmolText)
        }
    }

    @Test
    fun roundTripMgdlThroughMmolStaysWithinOneUnit() {
        for (mgdl in listOf(20, 54, 69, 70, 79, 80, 130, 131, 180, 181, 249, 250, 299, 300, 600)) {
            val roundTripped = UnitConverter.mmolToMgdl(UnitConverter.mgdlToMmol(mgdl.toFloat()))
            assertThat(UnitConverter.formatMgdl(roundTripped)).isEqualTo(mgdl.toString())
        }
    }

    @Test
    fun roundTripMmolThroughMgdlPreservesOneDecimal() {
        val values = listOf(1.1f, 3.0f, 3.9f, 5.5f, 7.2f, 10.0f, 13.9f, 16.7f, 33.3f)
        for (mmol in values) {
            val roundTripped = UnitConverter.mgdlToMmol(UnitConverter.mmolToMgdl(mmol))
            assertThat(UnitConverter.formatMmol(roundTripped)).isEqualTo(UnitConverter.formatMmol(mmol))
        }
    }

    @Test
    fun canonicalConversionRespectsUnit() {
        assertThat(UnitConverter.toCanonicalMgdl(120f, GlucoseUnit.MGDL)).isEqualTo(120f)
        assertThat(UnitConverter.toCanonicalMgdl(5.5f, GlucoseUnit.MMOL)).isWithin(0.01f).of(99.088f)
        assertThat(UnitConverter.fromCanonicalMgdl(99.088f, GlucoseUnit.MGDL)).isEqualTo(99f)
        assertThat(UnitConverter.fromCanonicalMgdl(99.088f, GlucoseUnit.MMOL)).isEqualTo(5.5f)
    }

    @Test
    fun displayFormattingUsesLatinDigits() {
        assertThat(UnitConverter.formatCanonical(99.4f, GlucoseUnit.MGDL)).isEqualTo("99")
        assertThat(UnitConverter.formatCanonical(99.5f, GlucoseUnit.MGDL)).isEqualTo("100")
        assertThat(UnitConverter.formatCanonical(100f, GlucoseUnit.MMOL)).isEqualTo("5.6")
    }
}

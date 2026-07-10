package com.dheyab.qiyas.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Mandatory test 1 (spec §13): digit normalization. */
class NumberUtilsTest {

    @Test
    fun easternArabicDigitsAreMapped() {
        assertThat(NumberUtils.normalizeDigits("٠١٢٣٤٥٦٧٨٩")).isEqualTo("0123456789")
    }

    @Test
    fun persianDigitsAreMapped() {
        assertThat(NumberUtils.normalizeDigits("۰۱۲۳۴۵۶۷۸۹")).isEqualTo("0123456789")
    }

    @Test
    fun arabicDecimalSeparatorBecomesDot() {
        assertThat(NumberUtils.normalizeDigits("٥٫٥")).isEqualTo("5.5")
    }

    @Test
    fun commaBecomesDot() {
        assertThat(NumberUtils.normalizeDigits("5,5")).isEqualTo("5.5")
    }

    @Test
    fun whitespaceIsStripped() {
        assertThat(NumberUtils.normalizeDigits(" 1 2 0 ")).isEqualTo("120")
        assertThat(NumberUtils.normalizeDigits(" ٩٩\t")).isEqualTo("99")
    }

    @Test
    fun mixedScriptStringNormalizes() {
        assertThat(NumberUtils.normalizeDigits("١2۳٫5")).isEqualTo("123.5")
        assertThat(NumberUtils.parseDecimal("١2۳٫5")).isEqualTo(123.5f)
    }

    @Test
    fun latinInputIsUntouched() {
        assertThat(NumberUtils.normalizeDigits("120.5")).isEqualTo("120.5")
    }

    @Test
    fun parseDecimalRejectsGarbage() {
        assertThat(NumberUtils.parseDecimal("abc")).isNull()
        assertThat(NumberUtils.parseDecimal("")).isNull()
        assertThat(NumberUtils.parseDecimal("١٢٣أ")).isNull()
    }

    @Test
    fun parseIntWorksWithEasternDigits() {
        assertThat(NumberUtils.parseInt("١٢٠")).isEqualTo(120)
        assertThat(NumberUtils.parseInt("۸۵")).isEqualTo(85)
        assertThat(NumberUtils.parseInt("5.5")).isNull()
    }

    @Test
    fun toLatinDigitsPreservesNonDigits() {
        assertThat(NumberUtils.toLatinDigits("١٢ يناير ٢٠٢٦")).isEqualTo("12 يناير 2026")
        assertThat(NumberUtils.toLatinDigits("۱۴:۳۰")).isEqualTo("14:30")
    }
}

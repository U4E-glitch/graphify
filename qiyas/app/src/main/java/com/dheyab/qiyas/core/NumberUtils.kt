package com.dheyab.qiyas.core

/**
 * Pure-Kotlin digit handling (Hard Rule 5, spec §3).
 * All numeric input must pass through [normalizeDigits] before parsing, and all
 * displayed medical values must render with Latin digits in both locales.
 */
object NumberUtils {

    private const val EASTERN_ARABIC_ZERO = '٠' // ٠
    private const val EASTERN_ARABIC_NINE = '٩' // ٩
    private const val PERSIAN_ZERO = '۰'        // ۰
    private const val PERSIAN_NINE = '۹'        // ۹
    private const val ARABIC_DECIMAL_SEPARATOR = '٫' // ٫

    /**
     * Maps Eastern Arabic (٠–٩) and Persian (۰–۹) digits to Latin 0–9, maps the
     * Arabic decimal separator (٫) and the comma to '.', and strips all whitespace.
     */
    fun normalizeDigits(s: String): String = buildString(s.length) {
        for (ch in s) {
            when {
                ch in EASTERN_ARABIC_ZERO..EASTERN_ARABIC_NINE -> append('0' + (ch - EASTERN_ARABIC_ZERO))
                ch in PERSIAN_ZERO..PERSIAN_NINE -> append('0' + (ch - PERSIAN_ZERO))
                ch == ARABIC_DECIMAL_SEPARATOR || ch == ',' -> append('.')
                ch.isWhitespace() -> Unit
                else -> append(ch)
            }
        }
    }

    /** Converts any Eastern Arabic / Persian digits to Latin, leaving all other characters intact. */
    fun toLatinDigits(s: String): String = buildString(s.length) {
        for (ch in s) {
            when {
                ch in EASTERN_ARABIC_ZERO..EASTERN_ARABIC_NINE -> append('0' + (ch - EASTERN_ARABIC_ZERO))
                ch in PERSIAN_ZERO..PERSIAN_NINE -> append('0' + (ch - PERSIAN_ZERO))
                else -> append(ch)
            }
        }
    }

    /** Normalizes then parses a decimal number; null when not a valid number. */
    fun parseDecimal(s: String): Float? = normalizeDigits(s).toFloatOrNull()

    /** Normalizes then parses an integer; null when not a valid whole number. */
    fun parseInt(s: String): Int? = normalizeDigits(s).toIntOrNull()
}

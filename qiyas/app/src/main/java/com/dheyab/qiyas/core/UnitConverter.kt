package com.dheyab.qiyas.core

import java.util.Locale
import kotlin.math.roundToInt

/** Glucose display units. Canonical storage is ALWAYS mg/dL (Hard Rule 3). */
enum class GlucoseUnit { MGDL, MMOL }

/**
 * Pure-Kotlin unit conversion (spec §5).
 * mg/dL is canonical; mmol/L exists only at the display/input layer.
 */
object UnitConverter {

    const val GLUCOSE_MMOL_FACTOR = 18.016f

    fun mgdlToMmol(mgdl: Float): Float = mgdl / GLUCOSE_MMOL_FACTOR

    fun mmolToMgdl(mmol: Float): Float = mmol * GLUCOSE_MMOL_FACTOR

    /** mg/dL displays as a rounded integer, Latin digits. */
    fun formatMgdl(mgdl: Float): String = String.format(Locale.US, "%d", mgdl.roundToInt())

    /** mmol/L displays with one decimal, Latin digits. */
    fun formatMmol(mmol: Float): String = String.format(Locale.US, "%.1f", mmol)

    /** Formats a canonical mg/dL value in the given display unit. */
    fun formatCanonical(mgdl: Float, unit: GlucoseUnit): String = when (unit) {
        GlucoseUnit.MGDL -> formatMgdl(mgdl)
        GlucoseUnit.MMOL -> formatMmol(mgdlToMmol(mgdl))
    }

    /** Converts a value entered in the display unit to canonical mg/dL. */
    fun toCanonicalMgdl(value: Float, unit: GlucoseUnit): Float = when (unit) {
        GlucoseUnit.MGDL -> value
        GlucoseUnit.MMOL -> mmolToMgdl(value)
    }

    /** Converts a canonical mg/dL value to the display unit's numeric value. */
    fun fromCanonicalMgdl(mgdl: Float, unit: GlucoseUnit): Float = when (unit) {
        GlucoseUnit.MGDL -> mgdl.roundToInt().toFloat()
        GlucoseUnit.MMOL -> (mgdlToMmol(mgdl) * 10f).roundToInt() / 10f
    }
}

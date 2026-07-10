package com.dheyab.qiyas.domain

import com.dheyab.qiyas.core.GlucoseUnit

/**
 * Input validation limits (spec §6). These bound what can be typed — they are
 * not classification thresholds (those live in assets/thresholds.json).
 */
object InputLimits {
    val GLUCOSE_MGDL = 20f..600f
    val GLUCOSE_MMOL = 1.1f..33.3f
    val SYSTOLIC = 60..260
    val DIASTOLIC = 30..160
    val PULSE = 30..220

    fun glucoseRange(unit: GlucoseUnit): ClosedFloatingPointRange<Float> = when (unit) {
        GlucoseUnit.MGDL -> GLUCOSE_MGDL
        GlucoseUnit.MMOL -> GLUCOSE_MMOL
    }
}

enum class FieldError {
    INVALID_NUMBER,
    GLUCOSE_OUT_OF_RANGE,
    SYSTOLIC_OUT_OF_RANGE,
    DIASTOLIC_OUT_OF_RANGE,
    SYS_NOT_GREATER_THAN_DIA,
    PULSE_OUT_OF_RANGE,
    FUTURE_TIME,
}

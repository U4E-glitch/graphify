package com.dheyab.qiyas.ui.common

import androidx.annotation.StringRes
import com.dheyab.qiyas.R
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.domain.FieldError
import com.dheyab.qiyas.domain.model.BpContext
import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Zone

@StringRes
fun GlucoseContext.labelRes(): Int = when (this) {
    GlucoseContext.FASTING -> R.string.context_fasting
    GlucoseContext.PRE_MEAL -> R.string.context_pre_meal
    GlucoseContext.POST_MEAL_1H -> R.string.context_post_meal_1h
    GlucoseContext.POST_MEAL_2H -> R.string.context_post_meal_2h
    GlucoseContext.BEDTIME -> R.string.context_bedtime
    GlucoseContext.RANDOM -> R.string.context_random
}

@StringRes
fun BpContext.labelRes(): Int = when (this) {
    BpContext.MORNING -> R.string.context_morning
    BpContext.EVENING -> R.string.context_evening
    BpContext.OTHER -> R.string.context_other
}

@StringRes
fun Zone.labelRes(): Int = when (this) {
    Zone.EMERGENCY_LOW_SEVERE -> R.string.zone_emergency_low_severe
    Zone.EMERGENCY_LOW -> R.string.zone_emergency_low
    Zone.EMERGENCY_HIGH -> R.string.zone_emergency_high
    Zone.BELOW_RANGE -> R.string.zone_below_range
    Zone.IN_RANGE -> R.string.zone_in_range
    Zone.ABOVE_RANGE -> R.string.zone_above_range
    Zone.HIGH -> R.string.zone_high
    Zone.CRISIS -> R.string.zone_crisis
    Zone.ELEVATED -> R.string.zone_elevated
    Zone.LOW -> R.string.zone_low
    Zone.NORMAL -> R.string.zone_normal
}

@StringRes
fun GlucoseUnit.labelRes(): Int = when (this) {
    GlucoseUnit.MGDL -> R.string.unit_mgdl
    GlucoseUnit.MMOL -> R.string.unit_mmol
}

@StringRes
fun FieldError.messageRes(): Int = when (this) {
    FieldError.INVALID_NUMBER -> R.string.err_invalid_number
    FieldError.GLUCOSE_OUT_OF_RANGE -> R.string.err_glucose_range
    FieldError.SYSTOLIC_OUT_OF_RANGE -> R.string.err_systolic_range
    FieldError.DIASTOLIC_OUT_OF_RANGE -> R.string.err_diastolic_range
    FieldError.SYS_NOT_GREATER_THAN_DIA -> R.string.err_sys_not_greater
    FieldError.PULSE_OUT_OF_RANGE -> R.string.err_pulse_range
    FieldError.FUTURE_TIME -> R.string.err_future_time
}

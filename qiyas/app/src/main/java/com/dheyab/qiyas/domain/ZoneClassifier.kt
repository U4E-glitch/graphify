package com.dheyab.qiyas.domain

import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Severity
import com.dheyab.qiyas.domain.model.Zone
import kotlin.math.roundToInt

/**
 * Pure-Kotlin classification engine (spec §7). Zero Android dependencies.
 * All thresholds come from [ThresholdConfig] (Hard Rule 4).
 */
class ZoneClassifier(private val config: ThresholdConfig) {

    data class Result(val zone: Zone) {
        val severity: Severity get() = zone.severity
        val requiresBlockingAlert: Boolean get() = zone.requiresBlockingAlert
    }

    /**
     * Classifies a canonical mg/dL value. The value is rounded to the nearest
     * integer first so the inclusive integer bands of Appendix A are total —
     * classification always matches the integer the user sees on screen.
     */
    fun classifyGlucose(mgdl: Float, context: GlucoseContext): Result {
        val v = mgdl.roundToInt()
        val emergency = config.glucose.emergency
        val zone = when {
            v < emergency.severeLowBelow -> Zone.EMERGENCY_LOW_SEVERE
            v < emergency.lowBelow -> Zone.EMERGENCY_LOW
            v >= emergency.highAtOrAbove -> Zone.EMERGENCY_HIGH
            else -> {
                val bands = config.glucose.contexts[context.name]
                    ?: throw IllegalStateException("No thresholds configured for context ${context.name}")
                when {
                    bands.belowRange != null && v in bands.belowRange[0]..bands.belowRange[1] -> Zone.BELOW_RANGE
                    v in bands.inRange[0]..bands.inRange[1] -> Zone.IN_RANGE
                    v in bands.aboveRange[0]..bands.aboveRange[1] -> Zone.ABOVE_RANGE
                    else -> Zone.HIGH
                }
            }
        }
        return Result(zone)
    }

    /**
     * Worse-of rule: systolic and diastolic are classified independently and the
     * worse zone wins (spec §7).
     */
    fun classifyBp(systolic: Int, diastolic: Int): Result {
        val worse = maxOf(classifySystolic(systolic), classifyDiastolic(diastolic))
        return Result(worse.zone)
    }

    private enum class BpRank(val zone: Zone) {
        NORMAL(Zone.NORMAL),
        LOW(Zone.LOW),
        ELEVATED(Zone.ELEVATED),
        HIGH(Zone.HIGH),
        CRISIS(Zone.CRISIS),
    }

    private fun classifySystolic(value: Int): BpRank = when {
        value >= config.bp.crisis.systolicAtOrAbove -> BpRank.CRISIS
        value >= config.bp.high.systolicAtOrAbove -> BpRank.HIGH
        value >= config.bp.elevated.systolicAtOrAbove -> BpRank.ELEVATED
        value < config.bp.low.systolicBelow -> BpRank.LOW
        else -> BpRank.NORMAL
    }

    private fun classifyDiastolic(value: Int): BpRank = when {
        value >= config.bp.crisis.diastolicAtOrAbove -> BpRank.CRISIS
        value >= config.bp.high.diastolicAtOrAbove -> BpRank.HIGH
        value >= config.bp.elevated.diastolicAtOrAbove -> BpRank.ELEVATED
        value < config.bp.low.diastolicBelow -> BpRank.LOW
        else -> BpRank.NORMAL
    }
}

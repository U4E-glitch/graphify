package com.dheyab.qiyas.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parsed form of assets/thresholds.json (spec Appendix A).
 * Hard Rule 4: every classification threshold comes from here — never from Kotlin constants.
 */
@Serializable
data class ThresholdConfig(
    val version: Int,
    val source: String,
    @SerialName("glucose_mgdl") val glucose: GlucoseThresholds,
    val bp: BpThresholds,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): ThresholdConfig = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class GlucoseThresholds(
    val emergency: GlucoseEmergency,
    val contexts: Map<String, GlucoseBands>,
    val note: String? = null,
)

@Serializable
data class GlucoseEmergency(
    @SerialName("severe_low_below") val severeLowBelow: Int,
    @SerialName("low_below") val lowBelow: Int,
    @SerialName("high_at_or_above") val highAtOrAbove: Int,
)

/** Bands are inclusive [min, max] pairs; below_range is absent for post-meal-style contexts. */
@Serializable
data class GlucoseBands(
    @SerialName("below_range") val belowRange: List<Int>? = null,
    @SerialName("in_range") val inRange: List<Int>,
    @SerialName("above_range") val aboveRange: List<Int>,
)

@Serializable
data class BpThresholds(
    val crisis: BpCutoff,
    val high: BpCutoff,
    val elevated: BpCutoff,
    val low: BpLowCutoff,
)

@Serializable
data class BpCutoff(
    @SerialName("systolic_at_or_above") val systolicAtOrAbove: Int,
    @SerialName("diastolic_at_or_above") val diastolicAtOrAbove: Int,
)

@Serializable
data class BpLowCutoff(
    @SerialName("systolic_below") val systolicBelow: Int,
    @SerialName("diastolic_below") val diastolicBelow: Int,
)

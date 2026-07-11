package com.dheyab.qiyas.domain.model

enum class ReadingType { GLUCOSE, BP }

enum class GlucoseContext { FASTING, PRE_MEAL, POST_MEAL_1H, POST_MEAL_2H, BEDTIME, RANDOM }

enum class BpContext { MORNING, EVENING, OTHER }

/** Traffic-light severity used for zone colors (spec §7). */
enum class Severity { GREEN, YELLOW, RED }

enum class Zone(val severity: Severity, val requiresBlockingAlert: Boolean) {
    // Glucose emergencies (blocking alert at save time — Hard Rule 6)
    EMERGENCY_LOW_SEVERE(Severity.RED, true),
    EMERGENCY_LOW(Severity.RED, true),
    EMERGENCY_HIGH(Severity.RED, true),

    // Glucose bands
    BELOW_RANGE(Severity.YELLOW, false),
    IN_RANGE(Severity.GREEN, false),
    ABOVE_RANGE(Severity.YELLOW, false),
    HIGH(Severity.RED, false),

    // Blood pressure
    CRISIS(Severity.RED, true),
    ELEVATED(Severity.YELLOW, false),
    LOW(Severity.YELLOW, false),
    NORMAL(Severity.GREEN, false),
}

/**
 * Domain reading. For GLUCOSE readings the glucose fields are set and the BP
 * fields are null; for BP readings the reverse. Glucose is canonical mg/dL
 * (Hard Rule 3).
 */
data class Reading(
    val id: Long = 0,
    val profileId: Long,
    val type: ReadingType,
    val measuredAt: Long,
    val createdAt: Long,
    val glucoseMgdl: Float? = null,
    val glucoseContext: GlucoseContext? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val pulse: Int? = null,
    val bpContext: BpContext? = null,
    val note: String? = null,
    val zone: Zone,
    /** App-private relative path of an attached meter photo. */
    val photoPath: String? = null,
)

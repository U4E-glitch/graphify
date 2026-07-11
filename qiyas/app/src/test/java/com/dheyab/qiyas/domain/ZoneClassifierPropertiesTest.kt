package com.dheyab.qiyas.domain

import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Severity
import com.dheyab.qiyas.domain.model.Zone
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Non-parameterized classifier properties: blocking-alert wiring, severity, rounding. */
class ZoneClassifierPropertiesTest {

    private val classifier = TestThresholds.classifier

    @Test
    fun emergencyZonesRequireBlockingAlert() {
        assertThat(classifier.classifyGlucose(40f, GlucoseContext.RANDOM).requiresBlockingAlert).isTrue()
        assertThat(classifier.classifyGlucose(60f, GlucoseContext.FASTING).requiresBlockingAlert).isTrue()
        assertThat(classifier.classifyGlucose(350f, GlucoseContext.POST_MEAL_2H).requiresBlockingAlert).isTrue()
        assertThat(classifier.classifyBp(185, 70).requiresBlockingAlert).isTrue()
        assertThat(classifier.classifyBp(120, 125).requiresBlockingAlert).isTrue()
    }

    @Test
    fun nonEmergencyZonesDoNotBlock() {
        assertThat(classifier.classifyGlucose(100f, GlucoseContext.FASTING).requiresBlockingAlert).isFalse()
        assertThat(classifier.classifyGlucose(200f, GlucoseContext.RANDOM).requiresBlockingAlert).isFalse()
        assertThat(classifier.classifyBp(150, 95).requiresBlockingAlert).isFalse()
        assertThat(classifier.classifyBp(85, 55).requiresBlockingAlert).isFalse()
    }

    @Test
    fun severitiesMatchSpecColors() {
        assertThat(classifier.classifyGlucose(100f, GlucoseContext.FASTING).severity).isEqualTo(Severity.GREEN)
        assertThat(classifier.classifyGlucose(75f, GlucoseContext.FASTING).severity).isEqualTo(Severity.YELLOW)
        assertThat(classifier.classifyGlucose(200f, GlucoseContext.FASTING).severity).isEqualTo(Severity.RED)
        assertThat(classifier.classifyBp(100, 70).severity).isEqualTo(Severity.GREEN)
        assertThat(classifier.classifyBp(132, 70).severity).isEqualTo(Severity.YELLOW)
        assertThat(classifier.classifyBp(85, 70).severity).isEqualTo(Severity.YELLOW)
        assertThat(classifier.classifyBp(150, 70).severity).isEqualTo(Severity.RED)
    }

    @Test
    fun fractionalMmolConversionsClassifyLikeTheDisplayedInteger() {
        // 5.5 mmol/L -> 99.088 mg/dL displays as 99 and must classify as 99.
        assertThat(classifier.classifyGlucose(99.088f, GlucoseContext.FASTING).zone).isEqualTo(Zone.IN_RANGE)
        // 3.0 mmol/L -> 54.048 -> displays 54 -> EMERGENCY_LOW (not severe).
        assertThat(classifier.classifyGlucose(54.048f, GlucoseContext.RANDOM).zone).isEqualTo(Zone.EMERGENCY_LOW)
        // 53.6 rounds to 54 -> EMERGENCY_LOW.
        assertThat(classifier.classifyGlucose(53.6f, GlucoseContext.RANDOM).zone).isEqualTo(Zone.EMERGENCY_LOW)
        // 53.4 rounds to 53 -> severe.
        assertThat(classifier.classifyGlucose(53.4f, GlucoseContext.RANDOM).zone).isEqualTo(Zone.EMERGENCY_LOW_SEVERE)
    }

    @Test
    fun configComesFromJsonNotCode() {
        // Changing the JSON must change behavior: prove the classifier reads config.
        val tweaked = TestThresholds.config.copy(
            glucose = TestThresholds.config.glucose.copy(
                emergency = TestThresholds.config.glucose.emergency.copy(highAtOrAbove = 400)
            )
        )
        val tweakedClassifier = ZoneClassifier(tweaked)
        assertThat(tweakedClassifier.classifyGlucose(350f, GlucoseContext.RANDOM).zone).isEqualTo(Zone.HIGH)
        assertThat(classifier.classifyGlucose(350f, GlucoseContext.RANDOM).zone).isEqualTo(Zone.EMERGENCY_HIGH)
    }
}

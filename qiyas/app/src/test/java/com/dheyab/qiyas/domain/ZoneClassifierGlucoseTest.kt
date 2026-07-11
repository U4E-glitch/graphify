package com.dheyab.qiyas.domain

import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Zone
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Mandatory test 3 (spec §13): glucose zone boundaries across every context. */
@RunWith(Parameterized::class)
class ZoneClassifierGlucoseTest(
    private val mgdl: Int,
    private val context: GlucoseContext,
    private val expected: Zone,
) {
    companion object {
        private val FASTING_LIKE = listOf(GlucoseContext.FASTING, GlucoseContext.PRE_MEAL)
        private val POST_LIKE = listOf(
            GlucoseContext.POST_MEAL_1H,
            GlucoseContext.POST_MEAL_2H,
            GlucoseContext.BEDTIME,
            GlucoseContext.RANDOM,
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0} mg/dL {1} -> {2}")
        fun data(): Collection<Array<Any>> {
            val cases = mutableListOf<Array<Any>>()

            // Emergency boundaries apply in every context.
            for (context in FASTING_LIKE + POST_LIKE) {
                cases += arrayOf<Any>(53, context, Zone.EMERGENCY_LOW_SEVERE)
                cases += arrayOf<Any>(54, context, Zone.EMERGENCY_LOW)
                cases += arrayOf<Any>(69, context, Zone.EMERGENCY_LOW)
                cases += arrayOf<Any>(300, context, Zone.EMERGENCY_HIGH)
            }

            // Fasting / pre-meal bands.
            for (context in FASTING_LIKE) {
                cases += arrayOf<Any>(70, context, Zone.BELOW_RANGE)
                cases += arrayOf<Any>(79, context, Zone.BELOW_RANGE)
                cases += arrayOf<Any>(80, context, Zone.IN_RANGE)
                cases += arrayOf<Any>(130, context, Zone.IN_RANGE)
                cases += arrayOf<Any>(131, context, Zone.ABOVE_RANGE)
                cases += arrayOf<Any>(180, context, Zone.ABOVE_RANGE)
                cases += arrayOf<Any>(181, context, Zone.HIGH)
                cases += arrayOf<Any>(249, context, Zone.HIGH)
                cases += arrayOf<Any>(250, context, Zone.HIGH)
                cases += arrayOf<Any>(299, context, Zone.HIGH)
            }

            // Post-meal / bedtime / random bands.
            for (context in POST_LIKE) {
                cases += arrayOf<Any>(70, context, Zone.IN_RANGE)
                cases += arrayOf<Any>(79, context, Zone.IN_RANGE)
                cases += arrayOf<Any>(80, context, Zone.IN_RANGE)
                cases += arrayOf<Any>(130, context, Zone.IN_RANGE)
                cases += arrayOf<Any>(131, context, Zone.IN_RANGE)
                cases += arrayOf<Any>(179, context, Zone.IN_RANGE)
                cases += arrayOf<Any>(180, context, Zone.ABOVE_RANGE)
                cases += arrayOf<Any>(181, context, Zone.ABOVE_RANGE)
                cases += arrayOf<Any>(249, context, Zone.ABOVE_RANGE)
                cases += arrayOf<Any>(250, context, Zone.HIGH)
                cases += arrayOf<Any>(299, context, Zone.HIGH)
            }
            return cases
        }
    }

    @Test
    fun classifies() {
        val result = TestThresholds.classifier.classifyGlucose(mgdl.toFloat(), context)
        assertThat(result.zone).isEqualTo(expected)
    }
}

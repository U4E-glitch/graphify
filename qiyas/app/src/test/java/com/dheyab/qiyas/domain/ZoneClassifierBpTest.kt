package com.dheyab.qiyas.domain

import com.dheyab.qiyas.domain.model.Zone
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Mandatory test 3 (spec §13): BP boundaries + worse-of rule. */
@RunWith(Parameterized::class)
class ZoneClassifierBpTest(
    private val systolic: Int,
    private val diastolic: Int,
    private val expected: Zone,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1} -> {2}")
        fun data(): Collection<Array<Any>> = listOf(
            // Systolic boundaries with a mid-normal diastolic (70).
            arrayOf<Any>(89, 70, Zone.LOW),
            arrayOf<Any>(90, 70, Zone.NORMAL),
            arrayOf<Any>(129, 70, Zone.NORMAL),
            arrayOf<Any>(130, 70, Zone.ELEVATED),
            arrayOf<Any>(139, 70, Zone.ELEVATED),
            arrayOf<Any>(140, 70, Zone.HIGH),
            arrayOf<Any>(179, 70, Zone.HIGH),
            arrayOf<Any>(180, 70, Zone.CRISIS),
            // Diastolic boundaries with a mid-normal systolic (110).
            arrayOf<Any>(110, 59, Zone.LOW),
            arrayOf<Any>(110, 60, Zone.NORMAL),
            arrayOf<Any>(110, 79, Zone.NORMAL),
            arrayOf<Any>(110, 80, Zone.ELEVATED),
            arrayOf<Any>(110, 89, Zone.ELEVATED),
            arrayOf<Any>(110, 90, Zone.HIGH),
            arrayOf<Any>(110, 119, Zone.HIGH),
            arrayOf<Any>(110, 120, Zone.CRISIS),
            // Worse-of rule: each side dominating in turn.
            arrayOf<Any>(85, 95, Zone.HIGH),      // sys LOW vs dia HIGH
            arrayOf<Any>(190, 61, Zone.CRISIS),   // sys CRISIS vs dia NORMAL
            arrayOf<Any>(95, 120, Zone.CRISIS),   // dia CRISIS vs sys NORMAL
            arrayOf<Any>(125, 85, Zone.ELEVATED), // dia ELEVATED vs sys NORMAL
            arrayOf<Any>(145, 75, Zone.HIGH),     // sys HIGH vs dia NORMAL
            arrayOf<Any>(135, 92, Zone.HIGH),     // sys ELEVATED vs dia HIGH
            arrayOf<Any>(89, 59, Zone.LOW),       // both LOW
            arrayOf<Any>(100, 70, Zone.NORMAL),   // both NORMAL
            arrayOf<Any>(181, 121, Zone.CRISIS),  // both CRISIS
        )
    }

    @Test
    fun classifies() {
        val result = TestThresholds.classifier.classifyBp(systolic, diastolic)
        assertThat(result.zone).isEqualTo(expected)
    }
}

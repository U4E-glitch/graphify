package com.dheyab.qiyas.domain

import java.io.File

/** Loads the REAL shipped thresholds.json so tests validate the actual config (Hard Rule 4). */
object TestThresholds {
    val config: ThresholdConfig by lazy {
        val candidates = listOf(
            File("src/main/assets/thresholds.json"),
            File("app/src/main/assets/thresholds.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("thresholds.json not found from working dir ${File(".").absolutePath}")
        ThresholdConfig.fromJson(file.readText())
    }

    val classifier: ZoneClassifier by lazy { ZoneClassifier(config) }
}

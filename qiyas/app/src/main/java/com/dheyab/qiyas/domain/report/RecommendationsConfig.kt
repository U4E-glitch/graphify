package com.dheyab.qiyas.domain.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parsed form of assets/recommendations.json (spec Appendix C). Flag copy is
 * data-driven; nothing medical is hardcoded in Kotlin.
 */
@Serializable
data class RecommendationsConfig(
    val version: Int,
    val flags: Map<String, Map<String, String>>,
) {
    /** Returns the recommendation for [flagId] in [languageTag] ("en"/"ar"), falling back to English. */
    fun text(flagId: String, languageTag: String): String? {
        val entry = flags[flagId] ?: return null
        return entry[languageTag] ?: entry["en"]
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): RecommendationsConfig = json.decodeFromString(serializer(), text)
    }
}

package com.dheyab.qiyas.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dheyab.qiyas.core.GlucoseUnit
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** UI language choice: follow the system, or force English / Arabic. */
enum class LanguageChoice { SYSTEM, EN, AR }

data class QiyasSettings(
    val glucoseUnit: GlucoseUnit = GlucoseUnit.MGDL,
    val language: LanguageChoice = LanguageChoice.SYSTEM,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val reportNotificationEnabled: Boolean = true,
    val disclaimerAccepted: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val GLUCOSE_UNIT = stringPreferencesKey("glucose_unit")
        val LANGUAGE = stringPreferencesKey("language")
        val WEEK_START = stringPreferencesKey("week_start")
        val REPORT_NOTIFICATION_ENABLED = booleanPreferencesKey("report_notification_enabled")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
    }

    val settings: Flow<QiyasSettings> = dataStore.data.map { prefs ->
        QiyasSettings(
            glucoseUnit = prefs[Keys.GLUCOSE_UNIT]?.let { runCatching { GlucoseUnit.valueOf(it) }.getOrNull() }
                ?: GlucoseUnit.MGDL,
            language = prefs[Keys.LANGUAGE]?.let { runCatching { LanguageChoice.valueOf(it) }.getOrNull() }
                ?: LanguageChoice.SYSTEM,
            weekStart = prefs[Keys.WEEK_START]?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
                ?: DayOfWeek.MONDAY,
            reportNotificationEnabled = prefs[Keys.REPORT_NOTIFICATION_ENABLED] ?: true,
            disclaimerAccepted = prefs[Keys.DISCLAIMER_ACCEPTED] ?: false,
        )
    }

    suspend fun setGlucoseUnit(unit: GlucoseUnit) {
        dataStore.edit { it[Keys.GLUCOSE_UNIT] = unit.name }
    }

    suspend fun setLanguage(language: LanguageChoice) {
        dataStore.edit { it[Keys.LANGUAGE] = language.name }
    }

    suspend fun setWeekStart(day: DayOfWeek) {
        dataStore.edit { it[Keys.WEEK_START] = day.name }
    }

    suspend fun setReportNotificationEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.REPORT_NOTIFICATION_ENABLED] = enabled }
    }

    suspend fun setDisclaimerAccepted(accepted: Boolean) {
        dataStore.edit { it[Keys.DISCLAIMER_ACCEPTED] = accepted }
    }
}

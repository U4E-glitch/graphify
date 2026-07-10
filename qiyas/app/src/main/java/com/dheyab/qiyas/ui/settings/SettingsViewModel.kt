package com.dheyab.qiyas.ui.settings

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.data.settings.LanguageChoice
import com.dheyab.qiyas.data.settings.QiyasSettings
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.work.WeeklyReportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

fun LanguageChoice.toLocaleList(): LocaleListCompat = when (this) {
    LanguageChoice.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
    LanguageChoice.EN -> LocaleListCompat.forLanguageTags("en")
    LanguageChoice.AR -> LocaleListCompat.forLanguageTags("ar")
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val application: Application,
) : ViewModel() {

    val settings: StateFlow<QiyasSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setLanguage(language: LanguageChoice) {
        viewModelScope.launch {
            settingsRepository.setLanguage(language)
            AppCompatDelegate.setApplicationLocales(language.toLocaleList())
        }
    }

    fun setGlucoseUnit(unit: GlucoseUnit) {
        viewModelScope.launch { settingsRepository.setGlucoseUnit(unit) }
    }

    fun setWeekStart(day: DayOfWeek) {
        viewModelScope.launch {
            settingsRepository.setWeekStart(day)
            WeeklyReportWorker.schedule(application, day)
        }
    }

    fun setReportNotificationEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setReportNotificationEnabled(enabled) }
    }
}

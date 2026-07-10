package com.dheyab.qiyas

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.work.WeeklyReportWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    application: Application,
) : ViewModel() {

    /** null while settings load; gates the start destination (spec §9 onboarding). */
    val disclaimerAccepted: StateFlow<Boolean?> = settingsRepository.settings
        .map { it.disclaimerAccepted }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            WeeklyReportWorker.schedule(application, settings.weekStart)
        }
    }
}

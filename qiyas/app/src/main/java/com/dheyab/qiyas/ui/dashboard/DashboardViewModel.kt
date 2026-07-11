package com.dheyab.qiyas.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.data.db.DEFAULT_PROFILE_ID
import com.dheyab.qiyas.data.repo.ReadingRepository
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import com.dheyab.qiyas.domain.report.WeekMath
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: ReadingRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /** 7-day sparkline window, fixed at screen creation. */
    val windowStartMillis: Long = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000

    val unit: StateFlow<GlucoseUnit> = settingsRepository.settings
        .map { it.glucoseUnit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlucoseUnit.MGDL)

    val latestGlucose: StateFlow<Reading?> =
        repository.observeLatestOfType(DEFAULT_PROFILE_ID, ReadingType.GLUCOSE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestBp: StateFlow<Reading?> =
        repository.observeLatestOfType(DEFAULT_PROFILE_ID, ReadingType.BP)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val glucose7d: StateFlow<List<Reading>> =
        repository.observeBetweenOfType(DEFAULT_PROFILE_ID, ReadingType.GLUCOSE, windowStartMillis, Long.MAX_VALUE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bp7d: StateFlow<List<Reading>> =
        repository.observeBetweenOfType(DEFAULT_PROFILE_ID, ReadingType.BP, windowStartMillis, Long.MAX_VALUE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Banner when the current settings-defined week has <4 readings of either type (spec §9). */
    val showLowDataBanner: StateFlow<Boolean> = settingsRepository.settings
        .flatMapLatest { settings ->
            val weekStart = WeekMath.weekStartFor(LocalDate.now(), settings.weekStart)
            val (from, to) = WeekMath.weekBoundsMillis(weekStart, ZoneId.systemDefault())
            combine(
                repository.observeCountBetweenOfType(DEFAULT_PROFILE_ID, ReadingType.GLUCOSE, from, to),
                repository.observeCountBetweenOfType(DEFAULT_PROFILE_ID, ReadingType.BP, from, to),
            ) { glucoseCount, bpCount -> glucoseCount < 4 || bpCount < 4 }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
}

package com.dheyab.qiyas.ui.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.data.db.DEFAULT_PROFILE_ID
import com.dheyab.qiyas.data.repo.ReportRepository
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.domain.report.RecommendationsConfig
import com.dheyab.qiyas.domain.report.ReportModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReportUiState(
    val weekStart: LocalDate? = null,
    val currentWeekStart: LocalDate? = null,
    val availableWeeks: List<LocalDate> = emptyList(),
    val report: ReportModel? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val reportRepository: ReportRepository,
    settingsRepository: SettingsRepository,
    val recommendations: RecommendationsConfig,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportUiState())
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    val unit: StateFlow<GlucoseUnit> = settingsRepository.settings
        .map { it.glucoseUnit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlucoseUnit.MGDL)

    init {
        viewModelScope.launch {
            val current = reportRepository.currentWeekStart()
            _state.value = _state.value.copy(
                currentWeekStart = current,
                availableWeeks = (0..11L).map { current.minusWeeks(it) },
            )
            selectWeek(current)
        }
    }

    /** Reports are regenerated on every open/selection, so edits are always reflected (spec §10). */
    fun selectWeek(weekStart: LocalDate) {
        _state.value = _state.value.copy(weekStart = weekStart, loading = true)
        viewModelScope.launch {
            val report = reportRepository.generate(DEFAULT_PROFILE_ID, weekStart)
            _state.value = _state.value.copy(report = report, loading = false)
        }
    }
}

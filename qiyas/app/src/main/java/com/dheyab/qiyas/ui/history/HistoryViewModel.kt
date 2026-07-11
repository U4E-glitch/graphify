package com.dheyab.qiyas.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.data.db.DEFAULT_PROFILE_ID
import com.dheyab.qiyas.data.repo.ReadingRepository
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HistoryFilter { ALL, GLUCOSE, BP }

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: ReadingRepository,
    settingsRepository: SettingsRepository,
    private val photoStore: com.dheyab.qiyas.data.photo.PhotoStore,
) : ViewModel() {

    fun photoFile(relativePath: String) = photoStore.fileFor(relativePath)

    private val _filter = MutableStateFlow(HistoryFilter.ALL)
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    val glucoseUnit: StateFlow<GlucoseUnit> = settingsRepository.settings
        .map { it.glucoseUnit }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlucoseUnit.MGDL)

    val readings: StateFlow<List<Reading>> =
        combine(repository.observeAll(DEFAULT_PROFILE_ID), _filter) { all, filter ->
            when (filter) {
                HistoryFilter.ALL -> all
                HistoryFilter.GLUCOSE -> all.filter { it.type == ReadingType.GLUCOSE }
                HistoryFilter.BP -> all.filter { it.type == ReadingType.BP }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(filter: HistoryFilter) {
        _filter.value = filter
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            // The attached photo goes with the reading.
            repository.getById(id)?.photoPath?.let(photoStore::delete)
            repository.delete(id)
        }
    }
}

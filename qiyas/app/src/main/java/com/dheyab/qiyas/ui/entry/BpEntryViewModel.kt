package com.dheyab.qiyas.ui.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dheyab.qiyas.core.NumberUtils
import com.dheyab.qiyas.data.db.DEFAULT_PROFILE_ID
import com.dheyab.qiyas.data.repo.ReadingRepository
import com.dheyab.qiyas.domain.FieldError
import com.dheyab.qiyas.domain.InputLimits
import com.dheyab.qiyas.domain.ZoneClassifier
import com.dheyab.qiyas.domain.model.BpContext
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.ui.common.EmergencyAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BpEntryUiState(
    val systolicText: String = "",
    val diastolicText: String = "",
    val pulseText: String = "",
    val context: BpContext? = null,
    val measuredAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val systolicError: FieldError? = null,
    val diastolicError: FieldError? = null,
    val pulseError: FieldError? = null,
    val timeError: FieldError? = null,
    val isEdit: Boolean = false,
    val alert: EmergencyAlert? = null,
    val closeWithZone: Zone? = null,
    val goToFollowUp: Boolean = false,
    val saving: Boolean = false,
) {
    // Hard Rule 2: Save stays disabled until a context is chosen.
    val canSave: Boolean
        get() = context != null &&
            systolicText.isNotBlank() && diastolicText.isNotBlank() &&
            systolicError == null && diastolicError == null &&
            pulseError == null && timeError == null && !saving
}

@HiltViewModel
class BpEntryViewModel @Inject constructor(
    private val repository: ReadingRepository,
    private val classifier: ZoneClassifier,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val readingId: Long = savedStateHandle.get<Long>("readingId") ?: -1L
    private val isEdit = readingId >= 0
    private var existing: Reading? = null

    private val _state = MutableStateFlow(BpEntryUiState(isEdit = isEdit))
    val state: StateFlow<BpEntryUiState> = _state.asStateFlow()

    init {
        if (isEdit) {
            viewModelScope.launch {
                repository.getById(readingId)?.let { reading ->
                    existing = reading
                    _state.value = _state.value.copy(
                        systolicText = reading.systolic?.toString().orEmpty(),
                        diastolicText = reading.diastolic?.toString().orEmpty(),
                        pulseText = reading.pulse?.toString().orEmpty(),
                        context = reading.bpContext,
                        measuredAt = reading.measuredAt,
                        note = reading.note.orEmpty(),
                    )
                }
            }
        }
    }

    fun onSystolicChanged(text: String) = revalidate(_state.value.copy(systolicText = text))

    fun onDiastolicChanged(text: String) = revalidate(_state.value.copy(diastolicText = text))

    fun onPulseChanged(text: String) = revalidate(_state.value.copy(pulseText = text))

    fun onContextSelected(context: BpContext) {
        _state.value = _state.value.copy(context = context)
    }

    fun onTimeChanged(millis: Long) {
        val error = if (millis > System.currentTimeMillis()) FieldError.FUTURE_TIME else null
        _state.value = _state.value.copy(measuredAt = millis, timeError = error)
    }

    fun onNoteChanged(note: String) {
        _state.value = _state.value.copy(note = note)
    }

    private fun revalidate(s: BpEntryUiState) {
        val sys = if (s.systolicText.isBlank()) null else NumberUtils.parseInt(s.systolicText)
        val dia = if (s.diastolicText.isBlank()) null else NumberUtils.parseInt(s.diastolicText)
        val pulse = if (s.pulseText.isBlank()) null else NumberUtils.parseInt(s.pulseText)

        var sysError: FieldError? = when {
            s.systolicText.isBlank() -> null
            sys == null -> FieldError.INVALID_NUMBER
            sys !in InputLimits.SYSTOLIC -> FieldError.SYSTOLIC_OUT_OF_RANGE
            else -> null
        }
        var diaError: FieldError? = when {
            s.diastolicText.isBlank() -> null
            dia == null -> FieldError.INVALID_NUMBER
            dia !in InputLimits.DIASTOLIC -> FieldError.DIASTOLIC_OUT_OF_RANGE
            else -> null
        }
        // Systolic must exceed diastolic — error shown on BOTH fields (spec §6).
        if (sys != null && dia != null && sysError == null && diaError == null && sys <= dia) {
            sysError = FieldError.SYS_NOT_GREATER_THAN_DIA
            diaError = FieldError.SYS_NOT_GREATER_THAN_DIA
        }
        val pulseError: FieldError? = when {
            s.pulseText.isBlank() -> null
            pulse == null -> FieldError.INVALID_NUMBER
            pulse !in InputLimits.PULSE -> FieldError.PULSE_OUT_OF_RANGE
            else -> null
        }
        _state.value = s.copy(systolicError = sysError, diastolicError = diaError, pulseError = pulseError)
    }

    fun save() {
        val s = _state.value
        val context = s.context ?: return
        val sys = NumberUtils.parseInt(s.systolicText) ?: return
        val dia = NumberUtils.parseInt(s.diastolicText) ?: return
        val pulse = if (s.pulseText.isBlank()) null else NumberUtils.parseInt(s.pulseText)
        if (s.systolicError != null || s.diastolicError != null || s.pulseError != null) return
        if (s.measuredAt > System.currentTimeMillis()) {
            _state.value = s.copy(timeError = FieldError.FUTURE_TIME)
            return
        }
        _state.value = s.copy(saving = true)
        viewModelScope.launch {
            val result = classifier.classifyBp(sys, dia)
            val reading = Reading(
                id = if (isEdit) readingId else 0,
                profileId = existing?.profileId ?: DEFAULT_PROFILE_ID,
                type = ReadingType.BP,
                measuredAt = s.measuredAt,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                systolic = sys,
                diastolic = dia,
                pulse = pulse,
                bpContext = context,
                note = s.note.ifBlank { null },
                zone = result.zone,
            )
            if (isEdit) repository.update(reading) else repository.insert(reading)
            if (result.requiresBlockingAlert) {
                _state.value = _state.value.copy(
                    saving = false,
                    alert = EmergencyAlert(zone = result.zone, systolic = sys, diastolic = dia),
                )
            } else {
                _state.value = _state.value.copy(saving = false, closeWithZone = result.zone)
            }
        }
    }

    fun onAlertUnderstood() {
        val zone = _state.value.alert?.zone ?: return
        _state.value = _state.value.copy(alert = null, closeWithZone = zone)
    }

    /** CRISIS dialog second button: reopen the Add BP screen for a follow-up reading (spec §8). */
    fun onAddFollowUp() {
        _state.value = _state.value.copy(alert = null, goToFollowUp = true)
    }
}

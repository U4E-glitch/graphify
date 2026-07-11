package com.dheyab.qiyas.ui.entry

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.core.NumberUtils
import com.dheyab.qiyas.core.UnitConverter
import com.dheyab.qiyas.data.db.DEFAULT_PROFILE_ID
import com.dheyab.qiyas.data.photo.MeterScanner
import com.dheyab.qiyas.data.photo.PhotoStore
import com.dheyab.qiyas.data.repo.ReadingRepository
import com.dheyab.qiyas.data.settings.SettingsRepository
import com.dheyab.qiyas.domain.FieldError
import com.dheyab.qiyas.domain.InputLimits
import com.dheyab.qiyas.domain.ZoneClassifier
import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.domain.ocr.MeterValueParser
import com.dheyab.qiyas.ui.common.EmergencyAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Photo-scan progress shown under the photo section. */
enum class ScanStatus { SCANNING, FILLED, VALUE_NOT_FOUND }

data class GlucoseEntryUiState(
    val valueText: String = "",
    val unit: GlucoseUnit = GlucoseUnit.MGDL,
    val context: GlucoseContext? = null,
    val measuredAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val valueError: FieldError? = null,
    val timeError: FieldError? = null,
    val isEdit: Boolean = false,
    val alert: EmergencyAlert? = null,
    val closeWithZone: Zone? = null,
    val saving: Boolean = false,
    val photoPath: String? = null,
    val scanStatus: ScanStatus? = null,
) {
    // Hard Rule 2: Save stays disabled until a context is chosen.
    val canSave: Boolean
        get() = context != null && valueText.isNotBlank() && valueError == null && timeError == null && !saving
}

@HiltViewModel
class GlucoseEntryViewModel @Inject constructor(
    private val repository: ReadingRepository,
    private val settingsRepository: SettingsRepository,
    private val classifier: ZoneClassifier,
    private val photoStore: PhotoStore,
    private val scanner: MeterScanner,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val readingId: Long = savedStateHandle.get<Long>("readingId") ?: -1L
    private val isEdit = readingId >= 0
    private var existing: Reading? = null

    /** Photo imported this session but not yet saved — cleaned up if abandoned. */
    private var unsavedPhotoPath: String? = null

    private val _state = MutableStateFlow(GlucoseEntryUiState(isEdit = isEdit))
    val state: StateFlow<GlucoseEntryUiState> = _state.asStateFlow()

    fun photoFile(relativePath: String) = photoStore.fileFor(relativePath)

    fun newCaptureTarget() = photoStore.newCaptureTarget()

    init {
        viewModelScope.launch {
            val unit = settingsRepository.settings.first().glucoseUnit
            _state.value = _state.value.copy(unit = unit)
            if (isEdit) {
                repository.getById(readingId)?.let { reading ->
                    existing = reading
                    _state.value = _state.value.copy(
                        valueText = UnitConverter.formatCanonical(reading.glucoseMgdl ?: 0f, unit),
                        context = reading.glucoseContext,
                        measuredAt = reading.measuredAt,
                        note = reading.note.orEmpty(),
                        photoPath = reading.photoPath,
                    )
                }
            }
        }
    }

    fun onValueChanged(text: String) {
        _state.value = _state.value.copy(
            valueText = text,
            valueError = validateValue(text, _state.value.unit),
            scanStatus = null,
        )
    }

    fun onContextSelected(context: GlucoseContext) {
        _state.value = _state.value.copy(context = context)
    }

    fun onTimeChanged(millis: Long) {
        val error = if (millis > System.currentTimeMillis()) FieldError.FUTURE_TIME else null
        _state.value = _state.value.copy(measuredAt = millis, timeError = error)
    }

    fun onNoteChanged(note: String) {
        _state.value = _state.value.copy(note = note)
    }

    /**
     * Reads the meter photo on-device: attaches it and pre-fills the value.
     * OCR output is a suggestion only — the user reviews before saving.
     */
    fun onPhotoSelected(uri: Uri) {
        _state.value = _state.value.copy(scanStatus = ScanStatus.SCANNING)
        viewModelScope.launch {
            val bitmap = photoStore.loadBitmap(uri)
            if (bitmap == null) {
                _state.value = _state.value.copy(scanStatus = ScanStatus.VALUE_NOT_FOUND)
                return@launch
            }
            // Replace any photo imported earlier in this session.
            if (unsavedPhotoPath != null) photoStore.delete(unsavedPhotoPath)
            val path = photoStore.import(bitmap)
            unsavedPhotoPath = path

            val tokens = scanner.recognize(bitmap)
            val value = MeterValueParser.parseGlucose(tokens, _state.value.unit)
            if (value != null) {
                val text = when (_state.value.unit) {
                    GlucoseUnit.MGDL -> UnitConverter.formatMgdl(value)
                    GlucoseUnit.MMOL -> UnitConverter.formatMmol(value)
                }
                _state.value = _state.value.copy(
                    valueText = text,
                    valueError = validateValue(text, _state.value.unit),
                    photoPath = path,
                    scanStatus = ScanStatus.FILLED,
                )
            } else {
                _state.value = _state.value.copy(photoPath = path, scanStatus = ScanStatus.VALUE_NOT_FOUND)
            }
        }
    }

    fun onPhotoRemoved() {
        if (_state.value.photoPath == unsavedPhotoPath) {
            photoStore.delete(unsavedPhotoPath)
            unsavedPhotoPath = null
        }
        _state.value = _state.value.copy(photoPath = null, scanStatus = null)
    }

    private fun validateValue(text: String, unit: GlucoseUnit): FieldError? {
        if (text.isBlank()) return null
        val value = NumberUtils.parseDecimal(text) ?: return FieldError.INVALID_NUMBER
        return if (value !in InputLimits.glucoseRange(unit)) FieldError.GLUCOSE_OUT_OF_RANGE else null
    }

    fun save() {
        val s = _state.value
        val context = s.context ?: return
        val value = NumberUtils.parseDecimal(s.valueText) ?: return
        if (validateValue(s.valueText, s.unit) != null) return
        if (s.measuredAt > System.currentTimeMillis()) {
            _state.value = s.copy(timeError = FieldError.FUTURE_TIME)
            return
        }
        _state.value = s.copy(saving = true)
        viewModelScope.launch {
            val mgdl = UnitConverter.toCanonicalMgdl(value, s.unit)
            val result = classifier.classifyGlucose(mgdl, context)
            val reading = Reading(
                id = if (isEdit) readingId else 0,
                profileId = existing?.profileId ?: DEFAULT_PROFILE_ID,
                type = ReadingType.GLUCOSE,
                measuredAt = s.measuredAt,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                glucoseMgdl = mgdl,
                glucoseContext = context,
                note = s.note.ifBlank { null },
                zone = result.zone,
                photoPath = s.photoPath,
            )
            if (isEdit) repository.update(reading) else repository.insert(reading)
            // The saved photo is now owned by the reading; drop a replaced/removed original.
            existing?.photoPath?.takeIf { it != s.photoPath }?.let(photoStore::delete)
            unsavedPhotoPath = null
            if (result.requiresBlockingAlert) {
                _state.value = _state.value.copy(
                    saving = false,
                    alert = EmergencyAlert(zone = result.zone, glucoseMgdl = mgdl),
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

    override fun onCleared() {
        // Abandoned without saving — don't leak the imported photo file.
        unsavedPhotoPath?.let(photoStore::delete)
        super.onCleared()
    }
}

package com.dheyab.qiyas.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheyab.qiyas.R
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.core.UnitConverter
import com.dheyab.qiyas.domain.FieldError
import com.dheyab.qiyas.domain.InputLimits
import com.dheyab.qiyas.domain.model.GlucoseContext
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.ui.common.ContextChips
import com.dheyab.qiyas.ui.common.DateTimeField
import com.dheyab.qiyas.ui.common.EmergencyAlertDialog
import com.dheyab.qiyas.ui.common.labelRes
import com.dheyab.qiyas.ui.common.messageRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGlucoseScreen(
    onDone: (Zone) -> Unit,
    onBack: () -> Unit,
    viewModel: GlucoseEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.closeWithZone) {
        state.closeWithZone?.let(onDone)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isEdit) R.string.title_edit_glucose else R.string.title_add_glucose))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.valueText,
                onValueChange = viewModel::onValueChanged,
                label = { Text(stringResource(R.string.label_glucose_value)) },
                suffix = { Text(stringResource(state.unit.labelRes())) },
                textStyle = MaterialTheme.typography.headlineMedium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = state.valueError != null,
                supportingText = state.valueError?.let { { Text(glucoseErrorText(it, state.unit)) } },
                modifier = Modifier.fillMaxWidth(),
            )

            PhotoSection(
                photoPath = state.photoPath,
                scanStatus = state.scanStatus,
                fileFor = viewModel::photoFile,
                newCaptureTarget = viewModel::newCaptureTarget,
                onPhotoSelected = viewModel::onPhotoSelected,
                onPhotoRemoved = viewModel::onPhotoRemoved,
            )

            Text(stringResource(R.string.label_context), style = MaterialTheme.typography.titleSmall)
            ContextChips(
                options = GlucoseContext.entries,
                selected = state.context,
                labelOf = { stringResource(it.labelRes()) },
                onSelect = viewModel::onContextSelected,
            )

            Text(stringResource(R.string.label_time), style = MaterialTheme.typography.titleSmall)
            DateTimeField(
                millis = state.measuredAt,
                errorText = state.timeError?.let { stringResource(it.messageRes()) },
                onChange = viewModel::onTimeChanged,
            )

            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChanged,
                label = { Text(stringResource(R.string.label_note)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }

    state.alert?.let { alert ->
        EmergencyAlertDialog(
            alert = alert,
            unit = state.unit,
            onUnderstood = viewModel::onAlertUnderstood,
        )
    }
}

@Composable
private fun glucoseErrorText(error: FieldError, unit: GlucoseUnit): String =
    if (error == FieldError.GLUCOSE_OUT_OF_RANGE) {
        val range = InputLimits.glucoseRange(unit)
        val (min, max) = when (unit) {
            GlucoseUnit.MGDL -> UnitConverter.formatMgdl(range.start) to UnitConverter.formatMgdl(range.endInclusive)
            GlucoseUnit.MMOL -> UnitConverter.formatMmol(range.start) to UnitConverter.formatMmol(range.endInclusive)
        }
        stringResource(R.string.err_glucose_range, min, max, stringResource(unit.labelRes()))
    } else {
        stringResource(error.messageRes())
    }

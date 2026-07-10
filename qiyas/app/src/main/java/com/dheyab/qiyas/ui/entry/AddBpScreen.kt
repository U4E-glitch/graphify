package com.dheyab.qiyas.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.dheyab.qiyas.domain.model.BpContext
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.ui.common.ContextChips
import com.dheyab.qiyas.ui.common.DateTimeField
import com.dheyab.qiyas.ui.common.EmergencyAlertDialog
import com.dheyab.qiyas.ui.common.labelRes
import com.dheyab.qiyas.ui.common.messageRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBpScreen(
    onDone: (Zone) -> Unit,
    onBack: () -> Unit,
    onCrisisFollowUp: () -> Unit,
    viewModel: BpEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.closeWithZone) {
        state.closeWithZone?.let(onDone)
    }
    LaunchedEffect(state.goToFollowUp) {
        if (state.goToFollowUp) onCrisisFollowUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isEdit) R.string.title_edit_bp else R.string.title_add_bp))
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.systolicText,
                    onValueChange = viewModel::onSystolicChanged,
                    label = { Text(stringResource(R.string.label_systolic)) },
                    suffix = { Text(stringResource(R.string.unit_mmhg)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = state.systolicError != null,
                    supportingText = state.systolicError?.let { { Text(stringResource(it.messageRes())) } },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.diastolicText,
                    onValueChange = viewModel::onDiastolicChanged,
                    label = { Text(stringResource(R.string.label_diastolic)) },
                    suffix = { Text(stringResource(R.string.unit_mmhg)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = state.diastolicError != null,
                    supportingText = state.diastolicError?.let { { Text(stringResource(it.messageRes())) } },
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = state.pulseText,
                onValueChange = viewModel::onPulseChanged,
                label = { Text(stringResource(R.string.label_pulse)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = state.pulseError != null,
                supportingText = state.pulseError?.let { { Text(stringResource(it.messageRes())) } },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.label_context), style = MaterialTheme.typography.titleSmall)
            ContextChips(
                options = BpContext.entries,
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
            unit = com.dheyab.qiyas.core.GlucoseUnit.MGDL,
            onUnderstood = viewModel::onAlertUnderstood,
            onAddFollowUp = viewModel::onAddFollowUp,
        )
    }
}

package com.dheyab.qiyas.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheyab.qiyas.R
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.data.settings.LanguageChoice
import com.dheyab.qiyas.ui.common.currentLocale
import com.dheyab.qiyas.ui.common.labelRes
import com.dheyab.qiyas.ui.export.ExportViewModel
import java.time.DayOfWeek
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    exportViewModel: ExportViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = currentLocale()

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { exportViewModel.writeCsv(context, it) } }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setReportNotificationEnabled(granted) }

    var languageDialog by remember { mutableStateOf(false) }
    var unitDialog by remember { mutableStateOf(false) }
    var weekStartDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        val current = settings ?: return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = {
                    Text(
                        when (current.language) {
                            LanguageChoice.SYSTEM -> stringResource(R.string.language_system)
                            LanguageChoice.EN -> stringResource(R.string.language_english)
                            LanguageChoice.AR -> stringResource(R.string.language_arabic)
                        }
                    )
                },
                modifier = Modifier.clickable { languageDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_glucose_unit)) },
                supportingContent = { Text(stringResource(current.glucoseUnit.labelRes())) },
                modifier = Modifier.clickable { unitDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_week_start)) },
                supportingContent = { Text(current.weekStart.getDisplayName(TextStyle.FULL, locale)) },
                modifier = Modifier.clickable { weekStartDialog = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_notification)) },
                trailingContent = {
                    Switch(
                        checked = current.reportNotificationEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= 33) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setReportNotificationEnabled(enabled)
                            }
                        },
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_export_all)) },
                modifier = Modifier.clickable { csvLauncher.launch(exportViewModel.csvFileName()) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_about)) },
                modifier = Modifier.clickable(onClick = onOpenAbout),
            )
        }

        if (languageDialog) {
            ChoiceDialog(
                title = stringResource(R.string.settings_language),
                options = LanguageChoice.entries.toList(),
                labelOf = {
                    when (it) {
                        LanguageChoice.SYSTEM -> stringResource(R.string.language_system)
                        LanguageChoice.EN -> stringResource(R.string.language_english)
                        LanguageChoice.AR -> stringResource(R.string.language_arabic)
                    }
                },
                onSelect = {
                    languageDialog = false
                    viewModel.setLanguage(it)
                },
                onDismiss = { languageDialog = false },
            )
        }
        if (unitDialog) {
            ChoiceDialog(
                title = stringResource(R.string.settings_glucose_unit),
                options = GlucoseUnit.entries.toList(),
                labelOf = { stringResource(it.labelRes()) },
                onSelect = {
                    unitDialog = false
                    viewModel.setGlucoseUnit(it)
                },
                onDismiss = { unitDialog = false },
            )
        }
        if (weekStartDialog) {
            ChoiceDialog(
                title = stringResource(R.string.settings_week_start),
                options = DayOfWeek.entries.toList(),
                labelOf = { it.getDisplayName(TextStyle.FULL, locale) },
                onSelect = {
                    weekStartDialog = false
                    viewModel.setWeekStart(it)
                },
                onDismiss = { weekStartDialog = false },
            )
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(labelOf(option)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

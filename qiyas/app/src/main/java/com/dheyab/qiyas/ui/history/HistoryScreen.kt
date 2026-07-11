package com.dheyab.qiyas.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheyab.qiyas.R
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.core.UnitConverter
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import com.dheyab.qiyas.domain.model.Zone
import androidx.compose.ui.text.font.FontWeight
import com.dheyab.qiyas.ui.common.Formatters
import com.dheyab.qiyas.ui.common.ZonePill
import com.dheyab.qiyas.ui.common.currentLocale
import com.dheyab.qiyas.ui.common.labelRes
import com.dheyab.qiyas.ui.theme.color
import com.dheyab.qiyas.ui.theme.containerColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    savedZoneName: String?,
    onSavedZoneConsumed: () -> Unit,
    onEdit: (Reading) -> Unit,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val readings by viewModel.readings.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val unit by viewModel.glucoseUnit.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Reading?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val savedZone = savedZoneName?.let { runCatching { Zone.valueOf(it) }.getOrNull() }
    val savedMessage = savedZone?.let { stringResource(R.string.snackbar_saved, stringResource(it.labelRes())) }
    LaunchedEffect(savedMessage) {
        if (savedMessage != null) {
            onSavedZoneConsumed()
            snackbarHostState.showSnackbar(savedMessage)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                HistoryFilter.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = filter == option,
                        onClick = { viewModel.setFilter(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = HistoryFilter.entries.size),
                        label = {
                            Text(
                                stringResource(
                                    when (option) {
                                        HistoryFilter.ALL -> R.string.filter_all
                                        HistoryFilter.GLUCOSE -> R.string.filter_glucose
                                        HistoryFilter.BP -> R.string.filter_bp
                                    }
                                )
                            )
                        },
                    )
                }
            }

            if (readings.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(),
                )
            } else {
                val zone = ZoneId.systemDefault()
                val grouped: Map<LocalDate, List<Reading>> = readings.groupBy {
                    Instant.ofEpochMilli(it.measuredAt).atZone(zone).toLocalDate()
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    grouped.forEach { (date, dayReadings) ->
                        item(key = "header-$date") {
                            Text(
                                text = Formatters.date(
                                    dayReadings.first().measuredAt,
                                    currentLocale(),
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(dayReadings.size, key = { dayReadings[it].id }) { index ->
                            ReadingRow(
                                reading = dayReadings[index],
                                unit = unit,
                                photoFile = viewModel::photoFile,
                                onEdit = { onEdit(dayReadings[index]) },
                                onDelete = { pendingDelete = dayReadings[index] },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { reading ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(reading.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ReadingRow(
    reading: Reading,
    unit: GlucoseUnit,
    photoFile: (String) -> java.io.File,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var photoViewerOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .background(reading.zone.containerColor(), CircleShape),
        ) {
            Icon(
                imageVector = when (reading.type) {
                    ReadingType.GLUCOSE -> Icons.Filled.WaterDrop
                    ReadingType.BP -> Icons.Filled.Favorite
                },
                contentDescription = null,
                tint = reading.zone.color(),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = readingValueText(reading, unit),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                ZonePill(reading.zone)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                contextLabel(reading)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    Formatters.time(reading.measuredAt, currentLocale()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        reading.photoPath?.let { path ->
            com.dheyab.qiyas.ui.entry.PhotoThumbnail(
                file = photoFile(path),
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { photoViewerOpen = true },
            )
            if (photoViewerOpen) {
                com.dheyab.qiyas.ui.entry.PhotoViewerDialog(
                    file = photoFile(path),
                    onDismiss = { photoViewerOpen = false },
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_edit)) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun readingValueText(reading: Reading, unit: GlucoseUnit): String = when (reading.type) {
    ReadingType.GLUCOSE ->
        "${UnitConverter.formatCanonical(reading.glucoseMgdl ?: 0f, unit)} ${stringResource(unit.labelRes())}"
    ReadingType.BP -> buildString {
        append("${reading.systolic}/${reading.diastolic}")
        reading.pulse?.let { append(" · $it") }
    }
}

@Composable
private fun contextLabel(reading: Reading): String? = when (reading.type) {
    ReadingType.GLUCOSE -> reading.glucoseContext?.let { stringResource(it.labelRes()) }
    ReadingType.BP -> reading.bpContext?.let { stringResource(it.labelRes()) }
}

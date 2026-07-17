package com.dheyab.qiyas.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dheyab.qiyas.R
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.core.UnitConverter
import com.dheyab.qiyas.domain.model.Reading
import com.dheyab.qiyas.domain.model.ReadingType
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.ui.charts.BpChart
import com.dheyab.qiyas.ui.charts.GlucoseChart
import com.dheyab.qiyas.ui.common.Formatters
import com.dheyab.qiyas.ui.common.ZonePill
import com.dheyab.qiyas.ui.common.labelRes
import com.dheyab.qiyas.ui.theme.ZoneYellow
import com.dheyab.qiyas.ui.theme.color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    savedZoneName: String?,
    onSavedZoneConsumed: () -> Unit,
    onAddGlucose: () -> Unit,
    onAddBp: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val savedZone = savedZoneName?.let { runCatching { Zone.valueOf(it) }.getOrNull() }
    val savedMessage = savedZone?.let { stringResource(R.string.snackbar_saved, stringResource(it.labelRes())) }

    val unit by viewModel.unit.collectAsStateWithLifecycle()
    val latestGlucose by viewModel.latestGlucose.collectAsStateWithLifecycle()
    val latestBp by viewModel.latestBp.collectAsStateWithLifecycle()
    val glucose7d by viewModel.glucose7d.collectAsStateWithLifecycle()
    val bp7d by viewModel.bp7d.collectAsStateWithLifecycle()
    val showBanner by viewModel.showLowDataBanner.collectAsStateWithLifecycle()

    LaunchedEffect(savedMessage) {
        if (savedMessage != null) {
            onSavedZoneConsumed()
            snackbarHostState.showSnackbar(savedMessage)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.nav_history),
                        )
                    }
                    IconButton(onClick = onOpenReport) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = stringResource(R.string.nav_report),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.nav_settings),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showBanner) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ZoneYellow.copy(alpha = 0.14f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = ZoneYellow)
                        Text(
                            text = stringResource(R.string.dash_low_data_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            LatestReadingCard(
                title = stringResource(R.string.dash_latest_glucose),
                icon = Icons.Filled.WaterDrop,
                reading = latestGlucose,
                valueText = latestGlucose?.let { UnitConverter.formatCanonical(it.glucoseMgdl ?: 0f, unit) },
                unitText = stringResource(unit.labelRes()),
                contextText = latestGlucose?.glucoseContext?.let { stringResource(it.labelRes()) },
            ) {
                if (glucose7d.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                        GlucoseChart(
                            readings = glucose7d,
                            unit = unit,
                            windowStartMillis = viewModel.windowStartMillis,
                            showAxes = false,
                        )
                    }
                }
            }

            LatestReadingCard(
                title = stringResource(R.string.dash_latest_bp),
                icon = Icons.Filled.Favorite,
                reading = latestBp,
                valueText = latestBp?.let { "${it.systolic}/${it.diastolic}" },
                unitText = stringResource(R.string.unit_mmhg),
                contextText = latestBp?.bpContext?.let { stringResource(it.labelRes()) },
            ) {
                if (bp7d.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                        BpChart(
                            readings = bp7d,
                            windowStartMillis = viewModel.windowStartMillis,
                            showAxes = false,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ExtendedFloatingActionButton(
                    onClick = onAddGlucose,
                    icon = { Icon(Icons.Filled.WaterDrop, contentDescription = null) },
                    text = {
                        Text(
                            stringResource(R.string.dash_add_glucose),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                    modifier = Modifier.weight(1f),
                )
                ExtendedFloatingActionButton(
                    onClick = onAddBp,
                    icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                    text = {
                        Text(
                            stringResource(R.string.dash_add_bp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LatestReadingCard(
    title: String,
    icon: ImageVector,
    reading: Reading?,
    valueText: String?,
    unitText: String,
    contextText: String?,
    sparkline: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                )
                reading?.let { ZonePill(it.zone) }
            }
            if (reading == null || valueText == null) {
                Text(
                    stringResource(R.string.dash_no_reading_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = reading.zone.color(),
                    )
                    Text(
                        text = unitText,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    contextText?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "· ${Formatters.relative(reading.measuredAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (reading.type == ReadingType.BP && reading.zone == Zone.LOW) {
                    Text(
                        stringResource(R.string.caution_bp_low),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZoneYellow,
                    )
                }
                sparkline()
            }
        }
    }
}

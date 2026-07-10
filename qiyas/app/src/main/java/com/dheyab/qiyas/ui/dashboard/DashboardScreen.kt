package com.dheyab.qiyas.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dheyab.qiyas.R
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.ui.common.labelRes

/**
 * Temporary Phase-3 dashboard: quick-add buttons + saved-reading snackbar.
 * Replaced by the full dashboard in Phase 5.
 */
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
) {
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
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onAddGlucose, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dash_add_glucose))
                }
                Button(onClick = onAddBp, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dash_add_bp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onOpenHistory, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nav_history))
                }
                Button(onClick = onOpenReport, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nav_report))
                }
                Button(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nav_settings))
                }
            }
        }
    }
}

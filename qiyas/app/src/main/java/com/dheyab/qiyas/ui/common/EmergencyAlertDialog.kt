package com.dheyab.qiyas.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.dheyab.qiyas.R
import com.dheyab.qiyas.core.GlucoseUnit
import com.dheyab.qiyas.core.UnitConverter
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.ui.theme.ZoneRed

/**
 * Emergency information carried from save-time classification to the blocking
 * dialog (spec §8). Glucose alerts carry the canonical mg/dL value and render
 * it in the current display unit; CRISIS carries systolic/diastolic.
 */
data class EmergencyAlert(
    val zone: Zone,
    val glucoseMgdl: Float? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
)

/**
 * Blocking alert (Hard Rule 6): not dismissible by outside tap or back press.
 * CRISIS additionally offers a follow-up-reading action (spec §8).
 */
@Composable
fun EmergencyAlertDialog(
    alert: EmergencyAlert,
    unit: GlucoseUnit,
    onUnderstood: () -> Unit,
    onAddFollowUp: (() -> Unit)? = null,
) {
    val glucoseValueText = alert.glucoseMgdl?.let {
        "${UnitConverter.formatCanonical(it, unit)} ${stringResource(unit.labelRes())}"
    } ?: ""
    val body = when (alert.zone) {
        Zone.EMERGENCY_LOW_SEVERE -> stringResource(R.string.alert_hypo_severe, glucoseValueText)
        Zone.EMERGENCY_LOW -> stringResource(R.string.alert_hypo, glucoseValueText)
        Zone.EMERGENCY_HIGH -> stringResource(R.string.alert_hyper_emergency, glucoseValueText)
        Zone.CRISIS -> stringResource(
            R.string.alert_bp_crisis,
            alert.systolic?.toString() ?: "",
            alert.diastolic?.toString() ?: "",
        )
        else -> ""
    }
    AlertDialog(
        onDismissRequest = { /* blocking: only the buttons dismiss */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = ZoneRed) },
        title = { Text(stringResource(alert.zone.labelRes()), color = ZoneRed) },
        text = { Text(body, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onUnderstood) {
                Text(stringResource(R.string.alert_understood))
            }
        },
        dismissButton = if (alert.zone == Zone.CRISIS && onAddFollowUp != null) {
            {
                TextButton(onClick = onAddFollowUp) {
                    Text(stringResource(R.string.alert_add_followup))
                }
            }
        } else {
            null
        },
    )
}

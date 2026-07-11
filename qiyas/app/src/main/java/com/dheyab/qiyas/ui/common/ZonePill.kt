package com.dheyab.qiyas.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dheyab.qiyas.domain.model.Zone
import com.dheyab.qiyas.ui.theme.color
import com.dheyab.qiyas.ui.theme.containerColor

/** Rounded status chip showing a reading's zone: colored dot + label on a tinted pill. */
@Composable
fun ZonePill(zone: Zone, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(zone.containerColor(), MaterialTheme.shapes.large)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(zone.color(), CircleShape),
        )
        Text(
            text = stringResource(zone.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = zone.color(),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

package com.dheyab.qiyas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dheyab.qiyas.domain.model.Severity
import com.dheyab.qiyas.domain.model.Zone

// Zone colors fixed by spec §7 — identical in light and dark themes.
val ZoneGreen = Color(0xFF2E7D32)
val ZoneYellow = Color(0xFFF9A825)
val ZoneRed = Color(0xFFC62828)

fun Zone.color(): Color = when (severity) {
    Severity.GREEN -> ZoneGreen
    Severity.YELLOW -> ZoneYellow
    Severity.RED -> ZoneRed
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF00696D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1F5),
    onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF4A6365),
    surface = Color(0xFFFAFDFC),
    background = Color(0xFFFAFDFC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4D9),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Color(0xFF9CF1F5),
    secondary = Color(0xFFB1CBCD),
    surface = Color(0xFF191C1C),
    background = Color(0xFF191C1C),
)

@Composable
fun QiyasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

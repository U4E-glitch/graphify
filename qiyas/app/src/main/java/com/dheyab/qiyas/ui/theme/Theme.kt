package com.dheyab.qiyas.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

/** Soft container tint used behind zone pills and banners. */
fun Zone.containerColor(): Color = color().copy(alpha = 0.14f)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00696D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1F5),
    onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF4A6365),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8EA),
    onSecondaryContainer = Color(0xFF051F21),
    tertiary = Color(0xFF4E5F7D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6E3FF),
    onTertiaryContainer = Color(0xFF091C36),
    background = Color(0xFFF6FAFA),
    onBackground = Color(0xFF171D1D),
    surface = Color(0xFFF6FAFA),
    onSurface = Color(0xFF171D1D),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F4949),
    outline = Color(0xFF6F7979),
    surfaceContainerLow = Color(0xFFEFF4F4),
    surfaceContainer = Color(0xFFE9EFEF),
    surfaceContainerHigh = Color(0xFFE3E9E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4D9),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Color(0xFF9CF1F5),
    secondary = Color(0xFFB1CBCD),
    onSecondary = Color(0xFF1B3437),
    secondaryContainer = Color(0xFF324B4D),
    onSecondaryContainer = Color(0xFFCCE8EA),
    tertiary = Color(0xFFB6C7EA),
    onTertiary = Color(0xFF20314D),
    tertiaryContainer = Color(0xFF374764),
    onTertiaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF0F1415),
    onBackground = Color(0xFFDEE4E4),
    surface = Color(0xFF0F1415),
    onSurface = Color(0xFFDEE4E4),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC8C9),
    outline = Color(0xFF899393),
    surfaceContainerLow = Color(0xFF171D1D),
    surfaceContainer = Color(0xFF1B2122),
    surfaceContainerHigh = Color(0xFF252B2C),
)

private val QiyasShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun QiyasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = QiyasShapes,
        content = content,
    )
}

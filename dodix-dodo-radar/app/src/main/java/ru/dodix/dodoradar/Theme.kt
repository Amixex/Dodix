package ru.dodix.dodoradar

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DodoOrange = Color(0xFFFF6900)

private val Light = lightColorScheme(
    primary = DodoOrange, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE6D5), onPrimaryContainer = Color(0xFF3A1800),
    background = Color(0xFFFAFAFA), surface = Color.White,
    surfaceVariant = Color(0xFFF1F1F1),
)
private val Dark = darkColorScheme(
    primary = DodoOrange, onPrimary = Color.White,
    primaryContainer = Color(0xFF5C2600), onPrimaryContainer = Color(0xFFFFDBC7),
    background = Color(0xFF121212), surface = Color(0xFF1C1C1C),
    surfaceVariant = Color(0xFF2A2A2A),
)

@Composable
fun DodoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

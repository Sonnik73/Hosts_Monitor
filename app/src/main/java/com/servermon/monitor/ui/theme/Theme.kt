package com.servermon.monitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF1B3A4B)
private val Mint = Color(0xFF7FD8BE)

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = Color(0xFF2E6E5C),
    tertiary = Mint,
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    secondary = Color(0xFF7FD8BE),
    tertiary = Teal,
)

@Composable
fun HostsMonitorTheme(themeMode: String, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

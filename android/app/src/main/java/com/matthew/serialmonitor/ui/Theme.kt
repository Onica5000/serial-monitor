package com.matthew.serialmonitor.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.matthew.serialmonitor.core.Level
import com.matthew.serialmonitor.data.ThemeMode

private val Teal = Color(0xFF00979D) // Arduino teal
private val LightColors = lightColorScheme(primary = Teal, secondary = Color(0xFF4A6365), tertiary = Color(0xFFE47128))
private val DarkColors = darkColorScheme(primary = Color(0xFF4FD8DD), secondary = Color(0xFFB0CCCE), tertiary = Color(0xFFFFB68A))

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

object TermColors {
    fun forLevel(level: Level, dark: Boolean): Color? = when (level) {
        Level.CRASH -> if (dark) Color(0xFFFF6E6E) else Color(0xFFB00020)
        Level.ERROR -> if (dark) Color(0xFFFF8A80) else Color(0xFFC62828)
        Level.WARN -> if (dark) Color(0xFFFFD54F) else Color(0xFF9A6700)
        Level.INFO -> if (dark) Color(0xFF90A4AE) else Color(0xFF607D8B)
        Level.NONE -> null
    }
    fun tx(dark: Boolean) = if (dark) Color(0xFF82B1FF) else Color(0xFF1565C0)
    val crashBg = Color(0x33FF0000)

    val SERIES = listOf(
        Color(0xFF2196F3), Color(0xFFF44336), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF9C27B0), Color(0xFF00BCD4),
        Color(0xFFE91E63), Color(0xFF8BC34A), Color(0xFF795548), Color(0xFF607D8B), Color(0xFFFFEB3B), Color(0xFF3F51B5),
    )
}

package com.matthew.serialmonitor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matthew.serialmonitor.BuildConfig
import com.matthew.serialmonitor.data.ThemeMode

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier) {
    val s = vm.session.settings
    val prefs by s.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var errKw by remember { mutableStateOf(prefs.errorKeywords) }
    var warnKw by remember { mutableStateOf(prefs.warnKeywords) }
    var logsVersion by remember { mutableIntStateOf(0) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionTitle("Appearance")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Theme", Modifier.width(120.dp))
            Dropdown(prefs.theme.name.lowercase().replaceFirstChar { it.uppercase() } + if (prefs.theme == ThemeMode.SYSTEM) " default" else "",
                ThemeMode.entries, { when (it) { ThemeMode.SYSTEM -> "Follow system"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" } },
                { v -> s.update { it.copy(theme = v) } })
        }

        SectionTitle("Terminal")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Buffer lines", Modifier.width(120.dp))
            Dropdown("${prefs.maxLines}", listOf(1000, 2000, 5000, 10000, 20000), { it.toString() }, { v -> s.update { it.copy(maxLines = v) } })
        }
        SwitchRow("Show sent data", prefs.showSent, { v -> s.update { it.copy(showSent = v) } })
        OutlinedTextField(errKw, { errKw = it; s.update { p -> p.copy(errorKeywords = it) } }, Modifier.fillMaxWidth(),
            label = { Text("Highlight red (comma-separated)") }, singleLine = true)
        OutlinedTextField(warnKw, { warnKw = it; s.update { p -> p.copy(warnKeywords = it) } }, Modifier.fillMaxWidth().padding(top = 4.dp),
            label = { Text("Highlight yellow (comma-separated)") }, singleLine = true)
        Text("ESP-IDF \"E (..)\"/\"W (..)\" and Arduino-ESP32 \"[E]\"/\"[W]\" log lines and crash dumps (Guru Meditation, Backtrace, abort) are highlighted automatically.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle("Connection")
        SwitchRow("Keep screen on while connected", prefs.keepScreenOn, { v -> s.update { it.copy(keepScreenOn = v) } })
        SwitchRow("Auto-reconnect", prefs.autoReconnect, { v -> s.update { it.copy(autoReconnect = v) } },
            subtitle = "Re-open the port after unplug/reset or Bluetooth link loss")
        SwitchRow("Connect when a board is plugged in", prefs.autoConnectOnAttach, { v -> s.update { it.copy(autoConnectOnAttach = v) } })

        SectionTitle("Logging")
        SwitchRow("Log all lines to a text file", prefs.fileLogging, { v -> s.update { it.copy(fileLogging = v) } }, subtitle = "Starts with the next connection")
        SwitchRow("Log plot values to CSV", prefs.csvLogging, { v -> s.update { it.copy(csvLogging = v) } }, subtitle = "Starts with the next connection")
        val files = remember(logsVersion, prefs) { vm.session.logsDir().listFiles()?.sortedByDescending { it.lastModified() }.orEmpty() }
        if (files.isEmpty()) Text("No log files yet.", style = MaterialTheme.typography.bodySmall)
        files.take(30).forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${f.length() / 1024} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { shareFile(ctx, f, if (f.name.endsWith(".csv")) "text/csv" else "text/plain") }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { f.delete(); logsVersion++ }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
        if (files.isNotEmpty()) TextButton(onClick = { files.forEach { it.delete() }; logsVersion++ }) { Text("Delete all logs") }

        SectionTitle("History & macros")
        TextButton(onClick = { s.update { it.copy(history = emptyList()) } }) { Text("Clear command history (${prefs.history.size})") }

        if (BuildConfig.DEBUG) {
            SectionTitle("Debug")
            TextButton(onClick = { vm.startDemo() }) { Text("Start demo feed (sample ESP32 output)") }
        }

        SectionTitle("About")
        Text("Serial Monitor ${BuildConfig.VERSION_NAME}\nUSB: CH340/CH341, CH9102/CH343, CP210x, FTDI, PL2303, CDC-ACM (Uno R4/Leonardo/RP2040), Espressif native USB.\nBluetooth: Classic SPP and BLE UART.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

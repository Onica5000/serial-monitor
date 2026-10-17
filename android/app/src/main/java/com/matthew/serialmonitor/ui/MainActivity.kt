package com.matthew.serialmonitor.ui

import android.content.Intent
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matthew.serialmonitor.BuildConfig
import com.matthew.serialmonitor.data.ThemeMode
import com.matthew.serialmonitor.session.ConnState
import com.matthew.serialmonitor.session.ConnStatus

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val prefs by vm.session.settings.state.collectAsStateWithLifecycle()
            val status by vm.session.status.collectAsStateWithLifecycle()
            AppTheme(prefs.theme) { MainScreen(vm) }
            val keepOn = prefs.keepScreenOn && status.state != ConnState.DISCONNECTED
            LaunchedEffect(keepOn) {
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshUsb()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.let { vm.session.onUsbAttached(it) }
        }
        // Debug builds only: `adb shell am start -n …/.ui.MainActivity --es theme dark --ei tab 1 --ez demo true`
        if (BuildConfig.DEBUG && intent != null) {
            intent.getStringExtra("theme")?.let { t ->
                runCatching { ThemeMode.valueOf(t.uppercase()) }.getOrNull()?.let { m -> vm.session.settings.update { it.copy(theme = m) } }
            }
            if (intent.getBooleanExtra("demo", false) && vm.session.status.value.state == ConnState.DISCONNECTED) vm.startDemo()
            if (intent.hasExtra("tab")) vm.navigate.value = intent.getIntExtra("tab", 1)
        }
    }
}

@Composable
fun StatusDot(state: ConnState) {
    val c = when (state) {
        ConnState.CONNECTED -> Color(0xFF2E7D32)
        ConnState.CONNECTING, ConnState.RECONNECTING -> Color(0xFFF9A825)
        ConnState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Spacer(Modifier.size(10.dp).background(c, CircleShape))
}

fun statusLine(s: ConnStatus): String = when (s.state) {
    ConnState.DISCONNECTED -> "Not connected"
    ConnState.CONNECTING -> "Connecting…"
    ConnState.CONNECTED -> s.description.ifEmpty { s.target?.label ?: "Connected" }
    ConnState.RECONNECTING -> s.description
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val status by vm.session.status.collectAsStateWithLifecycle()
    val nav by vm.navigate.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tabs = listOf("Connect" to Icons.Filled.Usb, "Terminal" to Icons.Filled.Terminal,
        "Plotter" to Icons.AutoMirrored.Filled.ShowChart, "Settings" to Icons.Filled.Settings)
    LaunchedEffect(nav) { nav?.let { tab = it; vm.navigate.value = null } }
    // Jump to the terminal when a connection is established (only on the transition, not after rotation).
    var lastState by rememberSaveable { mutableStateOf(status.state) }
    LaunchedEffect(status.state) {
        if (status.state == ConnState.CONNECTED && lastState != ConnState.CONNECTED && lastState != ConnState.RECONNECTING && tab == 0) tab = 1
        lastState = status.state
    }
    LaunchedEffect(Unit) { vm.session.events.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(status.state)
                        Spacer(Modifier.width(8.dp))
                        // One line: app name when idle, the connection when there is one.
                        Text(
                            if (status.state == ConnState.DISCONNECTED) "Serial Monitor" else statusLine(status),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (tab == 1) TerminalActions(vm)
                    else if (status.state != ConnState.DISCONNECTED) {
                        IconButton(onClick = { vm.session.disconnect() }) { Icon(Icons.Filled.LinkOff, "Disconnect") }
                    } else if (tab != 0) {
                        IconButton(onClick = { tab = 0 }) { Icon(Icons.Filled.Link, "Connect") }
                    }
                },
            )
        },
        bottomBar = {
            // Hide the tab bar while typing so the terminal keeps its height; landscape uses a side rail instead.
            if (!landscape && !WindowInsets.isImeVisible) NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
    ) { inner ->
        Row(Modifier.padding(inner).consumeWindowInsets(inner).fillMaxSize()) {
            if (landscape) NavigationRail(windowInsets = WindowInsets(0)) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationRailItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
            val m = Modifier.weight(1f).fillMaxHeight()
            when (tab) {
                0 -> ConnectScreen(vm, m)
                1 -> TerminalScreen(vm, m)
                2 -> PlotScreen(vm, m)
                else -> SettingsScreen(vm, m)
            }
        }
    }
}

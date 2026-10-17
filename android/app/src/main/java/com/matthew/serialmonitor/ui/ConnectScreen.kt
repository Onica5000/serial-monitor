package com.matthew.serialmonitor.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matthew.serialmonitor.core.BoardType
import com.matthew.serialmonitor.core.UsbIds
import com.matthew.serialmonitor.session.ConnState
import kotlinx.coroutines.delay

/** Runs [action] once the given runtime permissions are granted (requesting them if needed). */
@Composable
fun rememberPermissionRunner(vm: MainViewModel): (List<String>, () -> Unit) -> Unit {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val p = pending; pending = null
        if (res.values.all { it }) p?.invoke() else vm.session.info("Permission denied: ${res.filterValues { !it }.keys.joinToString { it.substringAfterLast('.') }}")
    }
    return { perms, action ->
        val missing = vm.missing(perms)
        if (missing.isEmpty()) action() else { pending = action; launcher.launch(missing.toTypedArray()) }
    }
}

@Composable
fun ConnectScreen(vm: MainViewModel, modifier: Modifier) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val status by vm.session.status.collectAsStateWithLifecycle()
    val runWithPerms = rememberPermissionRunner(vm)
    val notifPerm = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    // Ask for notifications once (foreground-service notification); connect regardless of the answer.
    val connectWithNotif: (() -> Unit) -> Unit = { action ->
        if (notifPerm.isNotEmpty() && vm.missing(notifPerm).isNotEmpty()) runWithPerms(notifPerm) {}
        action()
    }

    val usbDevices by vm.usb.collectAsStateWithLifecycle()
    val firstUsb = usbDevices.firstOrNull { it.driverName != null }
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                val labels = listOf("USB", "Bluetooth", "BLE")
                val icons = listOf(Icons.Filled.Usb, Icons.Filled.Bluetooth, Icons.AutoMirrored.Filled.BluetoothSearching)
                labels.forEachIndexed { i, l ->
                    SegmentedButton(selected = mode == i, onClick = { mode = i }, shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                        icon = { SegmentedButtonDefaults.Icon(mode == i) { Icon(icons[i], null, Modifier.size(18.dp)) } }) {
                        Text(l, maxLines = 1)
                    }
                }
            }
            if (status.state != ConnState.DISCONNECTED) {
                Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(status.state)
                        Spacer(Modifier.width(8.dp))
                        Text(status.target?.label ?: "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        TextButton(onClick = { vm.session.disconnect() }) { Text("Disconnect") }
                    }
                }
            }
            when (mode) {
                0 -> UsbSection(vm, connectWithNotif)
                1 -> ClassicSection(vm, runWithPerms, connectWithNotif)
                else -> BleSection(vm, runWithPerms, connectWithNotif)
            }
            Spacer(Modifier.height(88.dp)) // room for the FAB
        }
        if (mode == 0 && firstUsb != null && status.state == ConnState.DISCONNECTED) {
            ExtendedFloatingActionButton(
                onClick = { connectWithNotif { vm.connectUsb(firstUsb, 0) } },
                icon = { Icon(Icons.Filled.Link, null) },
                text = { Text("Connect ${firstUsb.chip ?: firstUsb.driverName}") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun UsbSection(vm: MainViewModel, connect: (() -> Unit) -> Unit) {
    val devices by vm.usb.collectAsStateWithLifecycle()
    val prefs by vm.session.settings.state.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(2000); vm.refreshUsb() } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("USB serial devices")
        Spacer(Modifier.weight(1f))
        IconButtonText("Refresh", Icons.Filled.Refresh) { vm.refreshUsb() }
    }
    if (devices.isEmpty()) {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Usb, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text("No USB devices. Plug the board in with a USB OTG adapter or cable.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    devices.forEach { item ->
        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Memory, null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text("${item.chip ?: "Unknown chip"} · ${item.driverName ?: "no driver"} · ${item.vidPid}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.likelyEsp) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("ESP", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (item.likelyEsp && (prefs.baud != 115200 || prefs.boardType != BoardType.ESP32)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (UsbIds.isEspressifNative(item.device.vendorId)) "Espressif native USB detected." else "Looks like an ESP32 board.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.applyEspDefaults() }) { Text("Use 115200 + ESP32 reset") }
                    }
                }
                if (item.driverName == null) {
                    Text("Not a supported USB-serial device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (port in 0 until maxOf(1, item.ports)) {
                            Button(onClick = { connect { vm.connectUsb(item, port) } }) {
                                Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                                Text(if (item.ports > 1) "Connect port $port" else "Connect at ${prefs.baud}")
                            }
                        }
                    }
                }
            }
        }
    }

    SectionTitle("Port settings")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledDropdown("Baud rate", "${prefs.baud}", UsbIds.BAUD_RATES, { it.toString() }, { b -> vm.session.settings.update { it.copy(baud = b) } },
            Modifier.weight(1f))
        LabeledDropdown("Board type", prefs.boardType.label, BoardType.entries, { it.label }, { b -> vm.session.settings.update { it.copy(boardType = b) } },
            Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    SwitchRow("Reset board on connect", prefs.resetOnConnect, { v -> vm.session.settings.update { it.copy(resetOnConnect = v) } },
        subtitle = when (prefs.boardType) {
            BoardType.ARDUINO -> "DTR pulse, like the Arduino IDE"
            BoardType.ESP32 -> "EN pulse via RTS with IO0 high (normal boot, never download mode)"
            BoardType.NONE -> "No reset for this board type"
        })
    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced: data bits, parity, stop bits, DTR/RTS") }
    if (advanced) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Data bits", Modifier.width(96.dp))
            Dropdown("${prefs.dataBits}", listOf(5, 6, 7, 8), { it.toString() }, { v -> vm.session.settings.update { it.copy(dataBits = v) } })
        }
        val parities = listOf("None", "Odd", "Even", "Mark", "Space")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Parity", Modifier.width(96.dp))
            Dropdown(parities[prefs.parity.coerceIn(0, 4)], (0..4).toList(), { parities[it] }, { v -> vm.session.settings.update { it.copy(parity = v) } })
        }
        val stops = mapOf(1 to "1", 3 to "1.5", 2 to "2")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Stop bits", Modifier.width(96.dp))
            Dropdown(stops[prefs.stopBits] ?: "1", stops.keys.toList(), { stops.getValue(it) }, { v -> vm.session.settings.update { it.copy(stopBits = v) } })
        }
        val forced = prefs.boardType == BoardType.ESP32
        SwitchRow("Assert DTR on open", prefs.dtr && !forced, { v -> vm.session.settings.update { it.copy(dtr = v) } },
            subtitle = if (forced) "ESP32 mode always opens with DTR/RTS released" else "Needed by Leonardo/Micro (native USB) to send data", enabled = !forced)
        SwitchRow("Assert RTS on open", prefs.rts && !forced, { v -> vm.session.settings.update { it.copy(rts = v) } }, enabled = !forced)
    }
}

@Composable
private fun ClassicSection(vm: MainViewModel, runWithPerms: (List<String>, () -> Unit) -> Unit, connect: (() -> Unit) -> Unit) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val found by vm.discovered.collectAsStateWithLifecycle()
    val discovering by vm.discovering.collectAsStateWithLifecycle()
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshPaired() }
    LaunchedEffect(Unit) { vm.refreshPaired() }

    if (!vm.hasBluetooth) { Text("This phone has no Bluetooth."); return }
    if (vm.missing(vm.btPermissions(false)).isNotEmpty()) {
        Text("Bluetooth permission is needed to list paired devices.")
        Button(onClick = { runWithPerms(vm.btPermissions(false)) { vm.refreshPaired() } }) { Text("Grant Bluetooth permission") }
        return
    }
    if (!vm.bluetoothOn) {
        Button(onClick = { runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } }) { Text("Turn Bluetooth on") }
    }
    Text("HC-05 / HC-06 and ESP32 BluetoothSerial (classic SPP). Default PIN is usually 1234 or 0000.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SectionTitle("Paired devices")
    if (paired.isEmpty()) Text("No paired devices yet. Scan below.", style = MaterialTheme.typography.bodyMedium)
    paired.forEach { d -> DeviceRow(d.name, d.address, null) { connect { vm.connectClassic(d) } } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Nearby devices")
        Spacer(Modifier.weight(1f))
        if (discovering) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = { vm.stopDiscovery() }) { Text("Stop") }
        } else {
            TextButton(onClick = { runWithPerms(vm.btPermissions(true)) { vm.startDiscovery() } }) { Text("Scan") }
        }
    }
    if (Build.VERSION.SDK_INT < 31) Text("Android ${Build.VERSION.RELEASE}: scanning needs Location permission and Location turned on.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    found.filter { f -> paired.none { it.address == f.address } }.forEach { d ->
        DeviceRow(d.name, d.address, null,
            extra = { if (!d.bonded) TextButton(onClick = { vm.pair(d.address) }) { Text("Pair") } }) { connect { vm.connectClassic(d) } }
    }
}

@Composable
private fun BleSection(vm: MainViewModel, runWithPerms: (List<String>, () -> Unit) -> Unit, connect: (() -> Unit) -> Unit) {
    val results by vm.ble.collectAsStateWithLifecycle()
    val scanning by vm.bleScanning.collectAsStateWithLifecycle()
    if (!vm.hasBluetooth) { Text("This phone has no Bluetooth."); return }
    Text("HM-10/HC-08 (FFE0/FFE1), Nordic UART (ESP32 BLE_uart, NimBLE), ESP-IDF SPP and Microchip UART are detected automatically; any other service can be picked after connecting.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("BLE devices")
        Spacer(Modifier.weight(1f))
        if (scanning) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            TextButton(onClick = { vm.stopBleScan() }) { Text("Stop") }
        } else {
            TextButton(onClick = { runWithPerms(vm.btPermissions(true)) { vm.startBleScan() } }) { Text("Scan") }
        }
    }
    if (!vm.bluetoothOn) Text("Bluetooth is off.", color = MaterialTheme.colorScheme.error)
    if (Build.VERSION.SDK_INT < 31) Text("Android ${Build.VERSION.RELEASE}: BLE scan needs Location permission and Location turned on.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    results.forEach { r ->
        DeviceRow(r.name.ifEmpty { "(no name)" }, "${r.address} · ${r.rssi} dBm", r.tag) {
            runWithPerms(vm.btPermissions(false)) { connect { vm.connectBle(r) } }
        }
    }
}

@Composable
private fun DeviceRow(title: String, subtitle: String, tag: String?, extra: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tag != null) AssistChip(onClick = onClick, label = { Text(tag) })
            extra()
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onClick) { Text("Connect") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun IconButtonText(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(text) }
}

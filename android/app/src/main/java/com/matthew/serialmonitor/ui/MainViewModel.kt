package com.matthew.serialmonitor.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.matthew.serialmonitor.core.UsbIds
import com.matthew.serialmonitor.session.SerialSession
import com.matthew.serialmonitor.session.Target
import com.matthew.serialmonitor.transport.BleUuids
import com.matthew.serialmonitor.transport.UsbParams
import com.matthew.serialmonitor.transport.UsbProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UsbItem(
    val device: UsbDevice, val driverName: String?, val chip: String?, val ports: Int, val likelyEsp: Boolean,
) {
    val vidPid get() = UsbIds.vidPid(device.vendorId, device.productId)
    val title: String get() = (runCatching { device.productName }.getOrNull()?.takeIf { it.isNotBlank() } ?: chip ?: "USB device")
}

data class BtItem(val address: String, val name: String, val bonded: Boolean, val type: Int)
data class BleItem(val address: String, val name: String, val rssi: Int, val tag: String?)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val session = SerialSession.get(application)
    private val app = application
    private val btAdapter: BluetoothAdapter? = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _usb = MutableStateFlow<List<UsbItem>>(emptyList())
    val usb: StateFlow<List<UsbItem>> = _usb.asStateFlow()
    private val _paired = MutableStateFlow<List<BtItem>>(emptyList())
    val paired: StateFlow<List<BtItem>> = _paired.asStateFlow()
    private val _discovered = MutableStateFlow<List<BtItem>>(emptyList())
    val discovered: StateFlow<List<BtItem>> = _discovered.asStateFlow()
    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()
    private val _ble = MutableStateFlow<List<BleItem>>(emptyList())
    val ble: StateFlow<List<BleItem>> = _ble.asStateFlow()
    private val _bleScanning = MutableStateFlow(false)
    val bleScanning: StateFlow<Boolean> = _bleScanning.asStateFlow()
    /** Requests from the session/activity to switch tabs (e.g. after auto-connect). */
    val navigate = MutableStateFlow<Int?>(null)

    // Terminal UI state shared between the top-bar actions and the terminal body.
    val filterOn = MutableStateFlow(false)
    val filterText = MutableStateFlow("")
    val showBlePicker = MutableStateFlow(false)

    fun startDemo() { session.connect(Target.Demo()) }

    private var bleJob: Job? = null

    val hasBluetooth: Boolean get() = btAdapter != null
    val bluetoothOn: Boolean get() = btAdapter?.isEnabled == true

    init {
        refreshUsb()
        viewModelScope.launch {
            session.attachRequests.collect { dev ->
                refreshUsb()
                if (session.settings.value.autoConnectOnAttach) {
                    _usb.value.firstOrNull { it.device.deviceName == dev.deviceName && it.driverName != null }?.let {
                        connectUsb(it, 0); navigate.value = 1
                    }
                }
            }
        }
    }

    fun hasPerm(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    /** Runtime permissions needed for Bluetooth: [scan] = discovery/BLE scan. */
    fun btPermissions(scan: Boolean): List<String> = when {
        Build.VERSION.SDK_INT >= 31 -> listOfNotNull(Manifest.permission.BLUETOOTH_CONNECT, if (scan) Manifest.permission.BLUETOOTH_SCAN else null)
        scan -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }

    fun missing(perms: List<String>) = perms.filterNot { hasPerm(it) }

    fun refreshUsb() {
        _usb.value = session.usbDevices().map { d ->
            val drv = UsbProbe.probe(d)
            UsbItem(d, drv?.let { UsbProbe.driverName(it) }, UsbIds.chipName(d.vendorId, d.productId), drv?.ports?.size ?: 0,
                UsbIds.isLikelyEsp(d.vendorId, d.productId))
        }
    }

    fun connectUsb(item: UsbItem, port: Int) {
        val p = session.settings.value
        val d = item.device
        session.connect(
            Target.Usb(
                d.deviceName, d.vendorId, d.productId, port, "${item.chip ?: item.driverName ?: "USB"} ${item.vidPid}",
                UsbParams(p.baud, p.dataBits, p.stopBits, p.parity), p.boardType, p.resetOnConnect, p.dtr, p.rts,
            )
        )
    }

    /** Apply the ESP32 suggestion: 115200 baud + ESP reset sequence. */
    fun applyEspDefaults() = session.settings.update { it.copy(baud = 115200, boardType = com.matthew.serialmonitor.core.BoardType.ESP32) }

    @SuppressLint("MissingPermission")
    fun refreshPaired() {
        val a = btAdapter ?: return
        if (missing(btPermissions(false)).isNotEmpty()) return
        _paired.value = runCatching {
            a.bondedDevices.orEmpty().map { BtItem(it.address, it.name ?: it.address, true, it.type) }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    // Classic or dual-mode only; pure-LE devices belong in the BLE tab.
                    if (d.type == BluetoothDevice.DEVICE_TYPE_LE) return
                    val item = BtItem(d.address, runCatching { d.name }.getOrNull() ?: d.address, d.bondState == BluetoothDevice.BOND_BONDED, d.type)
                    _discovered.value = (_discovered.value.filter { it.address != item.address } + item)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _discovering.value = false
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshPaired()
            }
        }
    }
    private var discoveryRegistered = false

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val a = btAdapter ?: return
        if (missing(btPermissions(true)).isNotEmpty()) return
        if (!discoveryRegistered) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(app, discoveryReceiver, f, ContextCompat.RECEIVER_EXPORTED)
            discoveryRegistered = true
        }
        _discovered.value = emptyList()
        runCatching { a.cancelDiscovery() }
        _discovering.value = runCatching { a.startDiscovery() }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() { runCatching { btAdapter?.cancelDiscovery() }; _discovering.value = false }

    @SuppressLint("MissingPermission")
    fun pair(address: String) {
        val a = btAdapter ?: return
        runCatching { a.cancelDiscovery(); a.getRemoteDevice(address).createBond() }
    }

    fun connectClassic(item: BtItem) { stopDiscovery(); session.connect(Target.Classic(item.address, item.name)) }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            val name = result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull() ?: ""
            val item = BleItem(d.address, name, result.rssi, BleUuids.tagFor(uuids))
            val list = _ble.value.toMutableList()
            val i = list.indexOfFirst { it.address == item.address }
            if (i >= 0) list[i] = item.copy(name = item.name.ifEmpty { list[i].name }) else list += item
            _ble.value = list.sortedWith(compareBy<BleItem>({ it.tag == null }, { it.name.isEmpty() }, { -it.rssi }))
        }
        override fun onScanFailed(errorCode: Int) { _bleScanning.value = false; session.info("BLE scan failed ($errorCode)") }
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        val scanner = btAdapter?.bluetoothLeScanner ?: return
        if (missing(btPermissions(true)).isNotEmpty()) return
        stopBleScan()
        _ble.value = emptyList()
        if (runCatching { scanner.startScan(scanCallback) }.isFailure) return
        _bleScanning.value = true
        bleJob = viewModelScope.launch { delay(12_000); stopBleScan() }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        bleJob?.cancel(); bleJob = null
        if (_bleScanning.value) runCatching { btAdapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _bleScanning.value = false
    }

    fun connectBle(item: BleItem) { stopBleScan(); session.connect(Target.Ble(item.address, item.name.ifEmpty { item.address })) }

    override fun onCleared() {
        stopBleScan(); stopDiscovery()
        if (discoveryRegistered) runCatching { app.unregisterReceiver(discoveryReceiver) }
    }
}

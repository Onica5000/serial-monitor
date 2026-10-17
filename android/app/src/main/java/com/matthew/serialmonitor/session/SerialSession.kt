package com.matthew.serialmonitor.session

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.matthew.serialmonitor.core.BoardType
import com.matthew.serialmonitor.core.ControlStep
import com.matthew.serialmonitor.core.CrashDetector
import com.matthew.serialmonitor.core.CsvLogger
import com.matthew.serialmonitor.core.Hex
import com.matthew.serialmonitor.core.Highlighter
import com.matthew.serialmonitor.core.Level
import com.matthew.serialmonitor.core.LineAssembler
import com.matthew.serialmonitor.core.PlotBuffer
import com.matthew.serialmonitor.core.PlotParser
import com.matthew.serialmonitor.core.PlotSnapshot
import com.matthew.serialmonitor.core.ResetSequences
import com.matthew.serialmonitor.core.TextDecode
import com.matthew.serialmonitor.core.UsbIds
import com.matthew.serialmonitor.data.Macro
import com.matthew.serialmonitor.data.Settings
import com.matthew.serialmonitor.transport.BleCharInfo
import com.matthew.serialmonitor.transport.BleTransport
import com.matthew.serialmonitor.transport.ClassicTransport
import com.matthew.serialmonitor.transport.Transport
import com.matthew.serialmonitor.transport.TransportListener
import com.matthew.serialmonitor.transport.UsbParams
import com.matthew.serialmonitor.transport.UsbProbe
import com.matthew.serialmonitor.transport.UsbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed class Target {
    abstract val label: String

    data class Usb(
        val deviceName: String, val vid: Int, val pid: Int, val portIndex: Int, override val label: String,
        val params: UsbParams, val board: BoardType, val resetOnConnect: Boolean, val dtr: Boolean, val rts: Boolean,
    ) : Target()

    data class Classic(val address: String, override val label: String) : Target()
    data class Ble(val address: String, override val label: String) : Target()
    /** Debug-build demo feed (sample ESP32 output) used for UI testing and screenshots. */
    data class Demo(override val label: String = "Demo ESP32 (debug)") : Target()
}

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

data class ConnStatus(val state: ConnState = ConnState.DISCONNECTED, val target: Target? = null, val description: String = "", val dtr: Boolean = false, val rts: Boolean = false)

enum class Kind { RX, TX, INFO }

class TermLine(val id: Long, val time: Long, val text: String, val raw: ByteArray, val kind: Kind, val level: Level)

class TermSnapshot(val lines: List<TermLine>, val pending: String, val pendingRaw: ByteArray, val version: Long) {
    companion object { val EMPTY = TermSnapshot(emptyList(), "", ByteArray(0), 0) }
}

data class Stats(val rxBytes: Long = 0, val txBytes: Long = 0, val rxLines: Long = 0)
data class CrashInfo(val text: String, val count: Int)
data class BleChars(val all: List<BleCharInfo>, val notify: BleCharInfo?, val write: BleCharInfo?)

/**
 * Process-wide serial session: owns the transport, parsing pipeline and buffers.
 * Lives in the Application process (not the Activity), so rotation never drops the link; the
 * foreground [SerialService] keeps the process alive while connected/backgrounded.
 */
class SerialSession private constructor(context: Context) {
    private val app = context.applicationContext
    val settings = Settings(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _status = MutableStateFlow(ConnStatus())
    val status: StateFlow<ConnStatus> = _status.asStateFlow()
    private val _terminal = MutableStateFlow(TermSnapshot.EMPTY)
    val terminal: StateFlow<TermSnapshot> = _terminal.asStateFlow()
    private val _plot = MutableStateFlow(PlotSnapshot.EMPTY)
    val plot: StateFlow<PlotSnapshot> = _plot.asStateFlow()
    private val _crash = MutableStateFlow<CrashInfo?>(null)
    val crash: StateFlow<CrashInfo?> = _crash.asStateFlow()
    private val _bleChars = MutableStateFlow<BleChars?>(null)
    val bleChars: StateFlow<BleChars?> = _bleChars.asStateFlow()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()
    val plotPaused = MutableStateFlow(false)
    /** Set by the activity when an attach should be auto-connected (USB intent filter). */
    val attachRequests = MutableSharedFlow<UsbDevice>(extraBufferCapacity = 4)

    // --- pipeline state (guarded by lock) ---
    private val lock = Any()
    private val buffer = ArrayDeque<TermLine>()
    private val assembler = LineAssembler()
    private val plotParser = PlotParser()
    private val plotBuffer = PlotBuffer(settings.value.plotWindow)
    private val crashDetector = CrashDetector()
    private var highlighter = Highlighter(Highlighter.DEFAULT_ERROR, Highlighter.DEFAULT_WARN)
    private var nextId = 0L
    private var termVersion = 0L
    @Volatile private var termDirty = true
    @Volatile private var plotDirty = false
    @Volatile private var lastRx = 0L
    private var rxBytes = 0L
    private var txBytes = 0L
    private var rxLines = 0L
    private var logWriter: Writer? = null
    private var csv: CsvLogger? = null

    // --- connection state ---
    private var transport: Transport? = null
    private var connectJob: Job? = null
    @Volatile private var userDisconnect = false
    private var pendingPermissionTarget: Target.Usb? = null
    private val blePreferred = HashMap<String, Pair<UUID?, UUID?>>()

    private val logTimeFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    init {
        registerReceivers()
        scope.launch {
            settings.state.collect { p ->
                synchronized(lock) {
                    highlighter = Highlighter(p.errorKeywords.split(','), p.warnKeywords.split(','))
                    if (p.plotWindow != plotBuffer.capacity) { plotBuffer.resize(p.plotWindow); plotDirty = true }
                    while (buffer.size > p.maxLines) buffer.removeFirst()
                    termDirty = true
                }
            }
        }
        scope.launch { // UI publisher, throttled (~16 fps terminal, ~20 fps plot)
            var tick = 0
            while (isActive) {
                delay(50)
                tick++
                if (assembler.hasPendingCr() && System.currentTimeMillis() - lastRx > 150) {
                    synchronized(lock) { assembler.flush()?.let { addRxLine(it.content, it.raw) } }
                }
                if (termDirty) publishTerminal()
                if (plotDirty && !plotPaused.value) { plotDirty = false; _plot.value = plotBuffer.snapshot() }
                if (tick % 20 == 0) _stats.value = Stats(rxBytes, txBytes, rxLines)
            }
        }
    }

    // ---------------------------------------------------------------- receive pipeline

    private val listener = object : TransportListener {
        override fun onData(data: ByteArray) = onReceive(data)
        override fun onLost(error: Exception?) = onConnectionLost(error)
        override fun onInfo(message: String) = info(message)
    }

    private fun onReceive(data: ByteArray) {
        lastRx = System.currentTimeMillis()
        synchronized(lock) {
            rxBytes += data.size
            for (line in assembler.feed(data)) addRxLine(line.content, line.raw)
            termDirty = true
        }
    }

    /** Must hold [lock]. */
    private fun addRxLine(content: ByteArray, raw: ByteArray) {
        val now = System.currentTimeMillis()
        val text = TextDecode.decodeLine(content)
        rxLines++
        val inCrash = crashDetector.feed(text)
        var level = highlighter.classify(text)
        if (inCrash) {
            level = Level.CRASH
            _crash.value = crashDetector.lastDump?.let { CrashInfo(it, crashDetector.dumpCount) }
        }
        plotParser.parse(text)?.let { sample ->
            plotBuffer.add(sample)
            plotDirty = true
            csv?.let { runCatching { it.write(now, sample) } }
        }
        logWriter?.let { runCatching { it.write(fmtTime(now) + " " + text + "\n") } }
        push(TermLine(nextId++, now, text, raw, Kind.RX, level))
    }

    private fun push(line: TermLine) {
        buffer.addLast(line)
        val max = settings.value.maxLines
        while (buffer.size > max) buffer.removeFirst()
        termDirty = true
    }

    private fun publishTerminal() {
        termDirty = false
        val snap = synchronized(lock) {
            val pend = assembler.pending()
            TermSnapshot(buffer.toList(), if (pend.isEmpty()) "" else TextDecode.decodeLine(pend), pend, ++termVersion)
        }
        _terminal.value = snap
    }

    fun info(msg: String, level: Level = Level.INFO) {
        synchronized(lock) {
            logWriter?.let { runCatching { it.write(fmtTime(System.currentTimeMillis()) + " [" + msg + "]\n") } }
            push(TermLine(nextId++, System.currentTimeMillis(), msg, ByteArray(0), Kind.INFO, level))
        }
    }

    fun clearTerminal() {
        synchronized(lock) { buffer.clear(); assembler.reset(); crashDetector.reset(); termDirty = true }
        _crash.value = null
    }

    fun clearPlot() { plotBuffer.clear(); plotParser.reset(); _plot.value = plotBuffer.snapshot() }

    fun message(text: String) { _events.tryEmit(text) }

    fun dismissCrash() { _crash.value = null }

    fun exportText(withTimestamps: Boolean): String = synchronized(lock) {
        buildString {
            for (l in buffer) {
                if (withTimestamps) append(fmtTime(l.time)).append(' ')
                when (l.kind) { Kind.TX -> append("> "); Kind.INFO -> append("# "); else -> {} }
                append(l.text).append('\n')
            }
        }
    }

    // ---------------------------------------------------------------- sending

    fun send(text: String, hex: Boolean = settings.value.sendHex, addToHistory: Boolean = true): Boolean {
        val t = transport
        if (t == null || _status.value.state != ConnState.CONNECTED) { _events.tryEmit("Not connected"); return false }
        val p = settings.value
        val payload = if (hex) {
            try { Hex.parse(text) } catch (e: IllegalArgumentException) { _events.tryEmit(e.message ?: "Invalid hex"); return false }
        } else text.toByteArray(Charsets.UTF_8)
        val framed = p.lineEnding.apply(payload + p.crc.bytes(payload))
        t.write(framed)
        synchronized(lock) { txBytes += framed.size }
        if (p.showSent) synchronized(lock) {
            push(TermLine(nextId++, System.currentTimeMillis(), if (hex) Hex.toHex(framed) else text, framed, Kind.TX, Level.NONE))
        }
        if (addToHistory) settings.addHistory(text)
        return true
    }

    fun sendMacro(m: Macro) = send(m.command, hex = m.hex, addToHistory = false)

    // ---------------------------------------------------------------- control lines / reset

    fun setDtr(v: Boolean) { transport?.setDtr(v); _status.value = _status.value.copy(dtr = v) }
    fun setRts(v: Boolean) { transport?.setRts(v); _status.value = _status.value.copy(rts = v) }

    private suspend fun runSteps(t: Transport, steps: List<ControlStep>) {
        for (s in steps) {
            // Deassert RTS before DTR so the ESP auto-reset circuit never sees (DTR off, RTS on) by accident.
            if (s.rts == false) { s.rts.let { t.setRts(it) }; s.dtr?.let { t.setDtr(it) } }
            else { s.dtr?.let { t.setDtr(it) }; s.rts?.let { t.setRts(it) } }
            s.dtr?.let { d -> _status.value = _status.value.copy(dtr = d) }
            s.rts?.let { r -> _status.value = _status.value.copy(rts = r) }
            if (s.delayMs > 0) delay(s.delayMs)
        }
    }

    fun reset(board: BoardType) {
        val t = transport ?: return
        val target = _status.value.target as? Target.Usb ?: run { _events.tryEmit("Reset needs a USB connection"); return }
        scope.launch {
            when (board) {
                BoardType.ESP32 -> { info("ESP reset (EN pulse via RTS, IO0 high)"); runSteps(t, ResetSequences.esp(UsbIds.isUsbSerialJtag(target.vid, target.pid))) }
                else -> { info("Arduino reset (DTR pulse)"); runSteps(t, ResetSequences.ARDUINO) }
            }
        }
    }

    // ---------------------------------------------------------------- connect / disconnect

    fun connect(target: Target) {
        disconnectInternal(stopService = false)
        userDisconnect = false
        _status.value = ConnStatus(ConnState.CONNECTING, target, target.label)
        SerialService.start(app)
        connectJob = scope.launch { attemptLoop(target, reconnecting = false) }
    }

    fun disconnect() {
        userDisconnect = true
        disconnectInternal(stopService = true)
        if (_status.value.state != ConnState.DISCONNECTED) info("Disconnected")
        _status.value = ConnStatus()
    }

    private fun disconnectInternal(stopService: Boolean) {
        connectJob?.cancel(); connectJob = null
        transport?.let { runCatching { it.close() } }
        transport = null
        closeLogs()
        if (stopService) SerialService.stop(app)
    }

    private suspend fun attemptLoop(target: Target, reconnecting: Boolean) {
        var attempt = 0
        while (scope.isActive && !userDisconnect) {
            val result = runCatching { open(target, isReconnect = reconnecting) }
            val t = result.getOrNull()
            if (t != null) {
                transport = t
                _status.value = _status.value.copy(state = ConnState.CONNECTED, target = target, description = t.description)
                info("Connected: ${t.description}")
                openLogs()
                return
            }
            val err = result.exceptionOrNull()
            if (err is PermissionPending) {
                _status.value = _status.value.copy(state = if (reconnecting) ConnState.RECONNECTING else ConnState.CONNECTING, description = "Waiting for USB permission…")
                return // the permission receiver resumes the connection
            }
            if (attempt == 0 || attempt % 10 == 0) info("Connect failed: ${err?.message ?: err}", Level.ERROR)
            if (!reconnecting) { _status.value = ConnStatus(); SerialService.stop(app); return }
            attempt++
            _status.value = _status.value.copy(state = ConnState.RECONNECTING, description = "Reconnecting… (attempt $attempt)")
            delay(if (target is Target.Usb) 1500 else 3000)
        }
    }

    private class PermissionPending : Exception("Waiting for USB permission")

    @SuppressLint("MissingPermission")
    private suspend fun open(target: Target, isReconnect: Boolean): Transport = when (target) {
        is Target.Usb -> {
            val dev = findUsb(target) ?: throw java.io.IOException("USB device not attached")
            if (!usbManager.hasPermission(dev)) {
                requestUsbPermission(dev, target.copy(deviceName = dev.deviceName))
                throw PermissionPending()
            }
            val driver = UsbProbe.probe(dev) ?: throw java.io.IOException("No serial driver for ${UsbIds.vidPid(dev.vendorId, dev.productId)}")
            val t = UsbTransport(usbManager, dev, driver, target.portIndex, target.params)
            t.open(listener)
            runSteps(t, listOf(ResetSequences.initialLines(target.board, target.dtr, target.rts)))
            // Never reset again on auto-reconnect: native-USB boards re-enumerate after a reset,
            // so resetting on every reconnect would loop forever.
            if (target.resetOnConnect && !isReconnect) when (target.board) {
                BoardType.ARDUINO -> runSteps(t, ResetSequences.ARDUINO)
                BoardType.ESP32 -> runSteps(t, ResetSequences.esp(UsbIds.isUsbSerialJtag(dev.vendorId, dev.productId)))
                BoardType.NONE -> {}
            }
            t
        }
        is Target.Classic -> {
            val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: throw java.io.IOException("No Bluetooth adapter")
            if (!adapter.isEnabled) throw java.io.IOException("Bluetooth is off")
            ClassicTransport(adapter, adapter.getRemoteDevice(target.address)).also { it.open(listener) }
        }
        is Target.Demo -> com.matthew.serialmonitor.transport.DemoTransport().also { it.open(listener) }
        is Target.Ble -> {
            val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                ?: throw java.io.IOException("No Bluetooth adapter")
            if (!adapter.isEnabled) throw java.io.IOException("Bluetooth is off")
            val pref = blePreferred[target.address]
            BleTransport(app, adapter.getRemoteDevice(target.address), pref?.first, pref?.second) { all, n, w ->
                _bleChars.value = BleChars(all, n, w)
            }.also { it.open(listener) }
        }
    }

    fun selectBleChars(notify: BleCharInfo?, write: BleCharInfo?) {
        val t = transport as? BleTransport ?: return
        val target = _status.value.target as? Target.Ble ?: return
        blePreferred[target.address] = notify?.uuid to write?.uuid
        t.select(notify, write)
        _bleChars.value = _bleChars.value?.copy(notify = notify ?: _bleChars.value?.notify, write = write ?: _bleChars.value?.write)
    }

    private val lostLock = Any()

    private fun onConnectionLost(error: Exception?) {
        val target = synchronized(lostLock) {
            val st = _status.value
            if (userDisconnect || st.state != ConnState.CONNECTED || st.target == null) return
            _status.value = st.copy(state = ConnState.RECONNECTING, description = "Connection lost")
            st.target
        }
        scope.launch {
            transport?.let { runCatching { it.close() } }
            transport = null
            closeLogs()
            synchronized(lock) { assembler.flush()?.let { addRxLine(it.content, it.raw) } }
            info("Connection lost: ${error?.message ?: "unknown"}", Level.WARN)
            if (settings.value.autoReconnect) {
                _status.value = _status.value.copy(state = ConnState.RECONNECTING, description = "Reconnecting…")
                connectJob = scope.launch { delay(500); attemptLoop(target, reconnecting = true) }
            } else {
                _status.value = ConnStatus()
                SerialService.stop(app)
            }
        }
    }

    // ---------------------------------------------------------------- USB plumbing

    fun usbDevices(): List<UsbDevice> = usbManager.deviceList.values.sortedBy { it.deviceName }

    private fun findUsb(t: Target.Usb): UsbDevice? {
        val list = usbManager.deviceList.values
        return list.firstOrNull { it.deviceName == t.deviceName && it.vendorId == t.vid && it.productId == t.pid }
            ?: list.firstOrNull { it.vendorId == t.vid && it.productId == t.pid } // re-plugged: new device name
    }

    private fun requestUsbPermission(dev: UsbDevice, target: Target.Usb) {
        pendingPermissionTarget = target
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        // Explicit (package-scoped) intent: required for a mutable PendingIntent on Android 14+.
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        val pi = PendingIntent.getBroadcast(app, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
        usbManager.requestPermission(dev, pi)
    }

    /** Called when a board is plugged in (manifest intent filter or dynamic broadcast). */
    fun onUsbAttached(dev: UsbDevice) {
        val st = _status.value
        val t = st.target
        if (st.state == ConnState.RECONNECTING && t is Target.Usb && dev.vendorId == t.vid && dev.productId == t.pid) {
            connectJob?.cancel()
            connectJob = scope.launch { delay(300); attemptLoop(t.copy(deviceName = dev.deviceName), reconnecting = true) }
        } else if (st.state == ConnState.DISCONNECTED) {
            attachRequests.tryEmit(dev)
        }
    }

    private fun registerReceivers() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val target = pendingPermissionTarget ?: return
                        pendingPermissionTarget = null
                        if (granted || (dev != null && usbManager.hasPermission(dev))) {
                            val reconnecting = _status.value.state == ConnState.RECONNECTING
                            connectJob?.cancel()
                            connectJob = scope.launch { attemptLoop(target, reconnecting) }
                        } else {
                            info("USB permission denied", Level.WARN)
                            _status.value = ConnStatus(); SerialService.stop(app)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val t = _status.value.target as? Target.Usb ?: return
                        if (dev != null && dev.deviceName == t.deviceName && _status.value.state == ConnState.CONNECTED) {
                            onConnectionLost(java.io.IOException("USB device unplugged"))
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> dev?.let { onUsbAttached(it) }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ---------------------------------------------------------------- logs

    fun logsDir(): File = File(app.getExternalFilesDir(null) ?: app.filesDir, "logs").apply { mkdirs() }

    private fun openLogs() {
        val p = settings.value
        val stamp = logTimeFmt.format(Date())
        synchronized(lock) {
            if (p.fileLogging) logWriter = runCatching { FileWriter(File(logsDir(), "serial-$stamp.txt"), true) }.getOrNull()
            if (p.csvLogging) csv = runCatching { CsvLogger(FileWriter(File(logsDir(), "plot-$stamp.csv"), true)) }.getOrNull()
        }
        if (p.fileLogging || p.csvLogging) info("Logging to ${logsDir().absolutePath}")
    }

    private fun closeLogs() = synchronized(lock) {
        runCatching { logWriter?.close() }; logWriter = null
        runCatching { csv?.close() }; csv = null
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    fun fmtTime(t: Long): String = synchronized(timeFmt) { timeFmt.format(Date(t)) }

    companion object {
        const val ACTION_USB_PERMISSION = "com.matthew.serialmonitor.USB_PERMISSION"
        @SuppressLint("StaticFieldLeak") // holds only the application context
        @Volatile private var instance: SerialSession? = null
        fun get(context: Context): SerialSession =
            instance ?: synchronized(this) { instance ?: SerialSession(context).also { instance = it } }
    }
}

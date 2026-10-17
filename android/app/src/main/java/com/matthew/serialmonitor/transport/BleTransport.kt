package com.matthew.serialmonitor.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

data class BleCharInfo(val service: UUID, val uuid: UUID, val properties: Int) {
    val canNotify get() = properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    val canWrite get() = properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    val propsLabel: String get() = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNR")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString("/")
}

data class BleProfile(val name: String, val service: UUID, val notify: UUID, val write: UUID)

object BleUuids {
    private fun short(s: String): UUID = UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = short("2902")
    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** Known UART-over-BLE profiles, tried in order. */
    val PROFILES = listOf(
        // Nordic UART: RX (phone->device) 6E400002 write, TX (device->phone) 6E400003 notify. ESP32 BLE_uart, NimBLE, nRF.
        BleProfile("Nordic UART", NUS_SERVICE, UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"), UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")),
        BleProfile("HM-10 / HC-08 / JDY", short("ffe0"), short("ffe1"), short("ffe1")),
        BleProfile("ESP-IDF SPP", short("abf0"), short("abf2"), short("abf1")),
        BleProfile("Microchip Transparent UART", UUID.fromString("49535343-fe7d-4ab5-8fa9-9fafd205e455"),
            UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616"), UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")),
    )
    private val STANDARD = setOf(short("1800"), short("1801"), short("180a"), short("180f"))
    fun isStandard(u: UUID) = u in STANDARD

    fun tagFor(serviceUuids: Collection<UUID>): String? = PROFILES.firstOrNull { it.service in serviceUuids }?.name

    fun shortName(u: UUID): String {
        val s = u.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) "0x" + s.substring(4, 8).uppercase() else s.uppercase()
    }
}

/** BLE "serial" over a notify characteristic (RX) and a write characteristic (TX). */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked by the caller
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val preferredNotify: UUID? = null,
    private val preferredWrite: UUID? = null,
    /** Called with the discovered characteristics so the UI can offer a manual choice. */
    private val onCharacteristics: (List<BleCharInfo>, BleCharInfo?, BleCharInfo?) -> Unit = { _, _, _ -> },
) : Transport {
    override val description: String = "BLE ${device.name ?: device.address}"

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var listener: TransportListener? = null
    private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var closing = false
    @Volatile private var mtu = 23
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private sealed class Op {
        class Write(val ch: BluetoothGattCharacteristic, val data: ByteArray) : Op()
        class Desc(val d: BluetoothGattDescriptor, val value: ByteArray) : Op()
    }
    private val queue = ArrayDeque<Op>()
    private var busy = false
    private var retries = 0
    private val watchdog = Runnable { busy = false; pump() }

    @Suppress("DEPRECATION") // connectGatt overloads are deprecated on API 37 but are the only option for minSdk 24
    override suspend fun open(listener: TransportListener) {
        this.listener = listener
        val r = CompletableDeferred<Unit>()
        ready = r
        main.post {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        try {
            withTimeout(20_000) { r.await() }
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException("BLE connect timed out", e)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    listener?.onInfo("BLE link up, requesting MTU 517")
                    if (!g.requestMtu(517)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    runCatching { g.close() }
                    val r = ready
                    if (r != null && !r.isCompleted) r.completeExceptionally(IOException("BLE connection failed (status $status)"))
                    else if (!closing) listener?.onLost(IOException("BLE disconnected (status $status)"))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                listener?.onInfo("MTU $mtu (payload ${chunkSize()} bytes)")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { setup(g, status) }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION") val v = ch.value
                if (ch.uuid == notifyChar?.uuid && v != null) listener?.onData(v.copyOf())
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == notifyChar?.uuid) listener?.onData(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post { opDone() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                opDone()
                val r = ready
                if (d.uuid == BleUuids.CCCD && r != null && !r.isCompleted) {
                    if (status == BluetoothGatt.GATT_SUCCESS) r.complete(Unit)
                    else r.completeExceptionally(IOException("Enabling notifications failed (status $status)"))
                }
            }
        }
    }

    private fun setup(g: BluetoothGatt, status: Int) {
        val r = ready
        if (status != BluetoothGatt.GATT_SUCCESS) { r?.completeExceptionally(IOException("Service discovery failed ($status)")); return }
        val all = g.services.flatMap { s -> s.characteristics.map { BleCharInfo(s.uuid, it.uuid, it.properties) } }
        fun find(svc: UUID?, ch: UUID) = g.services.filter { svc == null || it.uuid == svc }.firstNotNullOfOrNull { it.getCharacteristic(ch) }

        var n: BluetoothGattCharacteristic? = null
        var w: BluetoothGattCharacteristic? = null
        if (preferredNotify != null) n = find(null, preferredNotify)
        if (preferredWrite != null) w = find(null, preferredWrite)
        if (n == null || w == null) {
            for (p in BleUuids.PROFILES) {
                val pn = find(p.service, p.notify); val pw = find(p.service, p.write)
                if (pn != null && pw != null) { n = n ?: pn; w = w ?: pw; listener?.onInfo("Using ${p.name} profile"); break }
            }
        }
        if (n == null || w == null) { // generic fallback: first notify + first writable in a non-standard service
            val custom = g.services.filter { !BleUuids.isStandard(it.uuid) }
            for (s in custom) {
                val sn = s.characteristics.firstOrNull { BleCharInfo(s.uuid, it.uuid, it.properties).canNotify }
                val sw = s.characteristics.firstOrNull { BleCharInfo(s.uuid, it.uuid, it.properties).canWrite }
                if (n == null && sn != null && (sw != null || w != null)) { n = sn; w = w ?: sw }
            }
            if (n != null) listener?.onInfo("No known UART service; guessed ${BleUuids.shortName(n.uuid)} (notify) / ${w?.let { BleUuids.shortName(it.uuid) }} (write). Change via menu > BLE characteristics.")
        }
        notifyChar = n; writeChar = w
        onCharacteristics(all, n?.let { BleCharInfo(it.service.uuid, it.uuid, it.properties) }, w?.let { BleCharInfo(it.service.uuid, it.uuid, it.properties) })
        if (n == null) {
            // Connected but nothing to listen to: still "ready" so the user can pick characteristics.
            listener?.onInfo("No notify characteristic found. Pick one via menu > BLE characteristics.")
            r?.complete(Unit)
            return
        }
        enableNotify(g, n, r)
    }

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic, r: CompletableDeferred<Unit>?) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(BleUuids.CCCD)
        if (cccd == null) { r?.complete(Unit); return }
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        enqueue(Op.Desc(cccd, value))
    }

    /** Switch characteristics at runtime (from the picker dialog). */
    fun select(notify: BleCharInfo?, write: BleCharInfo?) = main.post {
        val g = gatt ?: return@post
        val newN = notify?.let { g.getService(it.service)?.getCharacteristic(it.uuid) }
        val newW = write?.let { g.getService(it.service)?.getCharacteristic(it.uuid) }
        if (newN != null && newN != notifyChar) {
            notifyChar?.let { old -> g.setCharacteristicNotification(old, false) }
            notifyChar = newN
            enableNotify(g, newN, null)
        }
        if (newW != null) writeChar = newW
        listener?.onInfo("BLE notify=${notifyChar?.uuid?.let(BleUuids::shortName)} write=${writeChar?.uuid?.let(BleUuids::shortName)}")
    }

    private fun chunkSize() = (mtu - 3).coerceIn(20, 512)

    override fun write(data: ByteArray) {
        main.post {
            val w = writeChar
            if (w == null) { listener?.onInfo("No write characteristic selected"); return@post }
            val size = chunkSize()
            var i = 0
            while (i < data.size) {
                val end = minOf(data.size, i + size)
                queue.addLast(Op.Write(w, data.copyOfRange(i, end)))
                i = end
            }
            pump()
        }
    }

    private fun enqueue(op: Op) { queue.addLast(op); pump() }

    private fun opDone() { main.removeCallbacks(watchdog); busy = false; pump() }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (busy || closing) return
        val g = gatt ?: return
        val op = queue.removeFirstOrNull() ?: return
        busy = true
        val ok = when (op) {
            is Op.Write -> {
                val type = if (op.ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(op.ch, op.data, type) == BluetoothStatusCodes.SUCCESS
                else { op.ch.writeType = type; op.ch.value = op.data; g.writeCharacteristic(op.ch) }
            }
            is Op.Desc -> {
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(op.d, op.value) == BluetoothStatusCodes.SUCCESS
                else { op.d.value = op.value; g.writeDescriptor(op.d) }
            }
        }
        if (!ok) {
            busy = false
            // Stack busy: retry shortly instead of dropping the chunk (give up after ~1 s).
            if (++retries <= 50) { queue.addFirst(op); main.postDelayed({ pump() }, 20) }
            else { retries = 0; listener?.onInfo("BLE write failed, chunk dropped"); main.post { pump() } }
            return
        }
        retries = 0
        main.postDelayed(watchdog, 3000)
    }

    override fun close() {
        closing = true
        main.post {
            main.removeCallbacks(watchdog)
            queue.clear()
            gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
            gatt = null
        }
    }
}

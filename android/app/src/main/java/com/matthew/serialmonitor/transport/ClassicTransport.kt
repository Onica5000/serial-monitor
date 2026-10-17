package com.matthew.serialmonitor.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/** Bluetooth Classic RFCOMM/SPP: HC-05, HC-06, ESP32 BluetoothSerial. */
@SuppressLint("MissingPermission") // callers check BLUETOOTH_CONNECT before creating a transport
class ClassicTransport(private val adapter: BluetoothAdapter, private val device: BluetoothDevice) : Transport {
    override val description: String = "Bluetooth SPP ${device.name ?: device.address}"
    private var socket: BluetoothSocket? = null
    @Volatile private var closing = false
    private val writer = Executors.newSingleThreadExecutor()
    private var listener: TransportListener? = null

    override suspend fun open(listener: TransportListener) = withContext(Dispatchers.IO) {
        this@ClassicTransport.listener = listener
        runCatching { adapter.cancelDiscovery() }
        val s = connectWithFallbacks(listener)
        socket = s
        val input = s.inputStream
        Thread({
            val buf = ByteArray(4096)
            try {
                while (!closing) {
                    val n = input.read(buf)
                    if (n < 0) throw IOException("Stream closed")
                    if (n > 0) listener.onData(buf.copyOf(n))
                }
            } catch (e: IOException) {
                if (!closing) listener.onLost(e)
            }
        }, "bt-classic-read").apply { isDaemon = true }.start()
    }

    private fun connectWithFallbacks(listener: TransportListener): BluetoothSocket {
        var last: IOException? = null
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "secure" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "insecure" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            // Some HC-05 clones answer SDP badly; channel 1 direct is the classic workaround.
            "channel 1" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
            },
        )
        for ((name, make) in attempts) {
            if (closing) break
            val s = try { make() } catch (e: Exception) { last = IOException(e.message, e); continue }
            try {
                s.connect()
                if (name != "secure") listener.onInfo("Connected using $name RFCOMM socket")
                return s
            } catch (e: IOException) {
                last = e
                runCatching { s.close() }
            }
        }
        throw last ?: IOException("Bluetooth connect failed")
    }

    override fun write(data: ByteArray) {
        if (closing) return
        writer.execute {
            try { socket?.outputStream?.apply { write(data); flush() } } catch (e: IOException) {
                if (!closing) listener?.onLost(e)
            }
        }
    }

    override fun close() {
        closing = true
        runCatching { socket?.close() }
        writer.shutdownNow()
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

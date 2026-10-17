package com.matthew.serialmonitor.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.matthew.serialmonitor.core.UsbIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

data class UsbParams(val baud: Int, val dataBits: Int, val stopBits: Int, val parity: Int)

object UsbProbe {
    /** Extra VID/PIDs mapped to the CDC-ACM driver (ESP native USB, WCH CH9102/CH343), probed before the defaults. */
    private val customProber: UsbSerialProber by lazy {
        val table = ProbeTable()
        for (pid in UsbIds.ESPRESSIF_CDC_PIDS) table.addProduct(UsbIds.VID_ESPRESSIF, pid, CdcAcmSerialDriver::class.java)
        for (pid in UsbIds.WCH_CDC_PIDS) table.addProduct(UsbIds.VID_WCH, pid, CdcAcmSerialDriver::class.java)
        UsbSerialProber(table)
    }

    fun probe(device: UsbDevice): UsbSerialDriver? =
        runCatching { customProber.probeDevice(device) }.getOrNull()
            ?: runCatching { UsbSerialProber.getDefaultProber().probeDevice(device) }.getOrNull()
            // Any other Espressif device exposing a CDC interface (new chips / custom PIDs)
            ?: if (device.vendorId == UsbIds.VID_ESPRESSIF && CdcAcmSerialDriver.probe(device)) CdcAcmSerialDriver(device) else null

    fun driverName(driver: UsbSerialDriver): String = driver.javaClass.simpleName.removeSuffix("SerialDriver").let {
        when (it) { "Ch34x" -> "CH34x"; "Cp21xx" -> "CP210x"; "Ftdi" -> "FTDI"; "Prolific" -> "PL2303"; "CdcAcm" -> "CDC-ACM"; else -> it }
    }
}

class UsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val driver: UsbSerialDriver,
    private val portIndex: Int,
    private val params: UsbParams,
) : Transport {
    override val description: String =
        "${UsbProbe.driverName(driver)} ${UsbIds.vidPid(device.vendorId, device.productId)}" + if (driver.ports.size > 1) " port $portIndex" else ""
    override val supportsControlLines = true

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var closing = false
    private var listener: TransportListener? = null

    val deviceName: String get() = device.deviceName

    override suspend fun open(listener: TransportListener) = withContext(Dispatchers.IO) {
        this@UsbTransport.listener = listener
        val connection = usbManager.openDevice(device) ?: throw IOException("Cannot open USB device (permission denied?)")
        val p = driver.ports.getOrNull(portIndex) ?: throw IOException("Port $portIndex not available")
        try {
            p.open(connection)
            try {
                p.setParameters(params.baud, params.dataBits, params.stopBits, params.parity)
            } catch (e: UnsupportedOperationException) {
                listener.onInfo("Driver ignores line settings: ${e.message}") // e.g. some native CDC devices
            }
        } catch (e: Exception) {
            runCatching { p.close() }
            throw if (e is IOException) e else IOException(e.message, e)
        }
        port = p
        val mgr = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = listener.onData(data)
            override fun onRunError(e: Exception) { if (!closing) listener.onLost(e) }
        })
        mgr.readBufferSize = maxOf(4096, p.readEndpoint?.maxPacketSize ?: 64)
        ioManager = mgr
        mgr.start()
    }

    override fun write(data: ByteArray) {
        if (closing) return
        writer.execute {
            try { port?.write(data, 2000) } catch (e: IOException) { if (!closing) listener?.onInfo("Write failed: ${e.message}") }
        }
    }

    override fun setDtr(value: Boolean) { runCatching { port?.setDTR(value) } }
    override fun setRts(value: Boolean) { runCatching { port?.setRTS(value) } }

    override fun close() {
        closing = true
        runCatching { ioManager?.listener = null; ioManager?.stop() }
        runCatching { port?.close() }
        writer.shutdownNow()
        port = null
    }
}

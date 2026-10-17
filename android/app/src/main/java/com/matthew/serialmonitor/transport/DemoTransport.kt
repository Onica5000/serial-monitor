package com.matthew.serialmonitor.transport

import kotlin.math.PI
import kotlin.math.sin

/** Fake ESP32 that prints a boot log, sensor readings (plottable), warnings and a crash dump. Debug UI aid only. */
class DemoTransport : Transport {
    override val description = "Demo feed · 115200 baud"
    override val supportsControlLines = true
    @Volatile private var running = false
    private var listener: TransportListener? = null

    override suspend fun open(listener: TransportListener) {
        this.listener = listener
        running = true
        Thread({
            emit(BOOT)
            var t = 0
            while (running) {
                Thread.sleep(400)
                t++
                val temp = 23.0 + 1.5 * sin(t * PI / 20)
                val hum = 41.0 + 4 * sin(t * PI / 33 + 1)
                emit("temp:%.2f,hum:%.1f,light:%d\r\n".format(java.util.Locale.US, temp, hum, (520 + 60 * sin(t * PI / 12)).toInt()))
                when (t % 25) {
                    7 -> emit("[%6d][W][WiFiGeneric.cpp:1062] RSSI low: -82 dBm\r\n".format(t * 400))
                    15 -> emit("[%6d][I][main.cpp:88] MQTT publish ok (%d bytes)\r\n".format(t * 400, 48 + t % 9))
                    21 -> emit("[%6d][E][sensor.cpp:41] ERROR: BME280 read timeout\r\n".format(t * 400))
                }
                if (t == 60) emit(CRASH)
            }
        }, "demo-feed").apply { isDaemon = true }.start()
    }

    private fun emit(s: String) { if (running) listener?.onData(s.toByteArray()) }

    override fun write(data: ByteArray) {
        val cmd = String(data).trim()
        emit("> $cmd\r\nOK ($cmd)\r\n")
    }

    override fun close() { running = false }

    private companion object {
        val BOOT = """
            ets Jul 29 2019 12:21:46

            rst:0x1 (POWERON_RESET),boot:0x13 (SPI_FAST_FLASH_BOOT)
            configsip: 0, SPIWP:0xee
            mode:DIO, clock div:1
            load:0x3fff0030,len:1184
            entry 0x400805e4
            [     5][I][esp32-hal-cpu.c:244] setCpuFrequencyMhz(): F_CPU: 240 MHz
            Weather station v1.4 starting…
            WiFi: connecting to "workshop"…
            WiFi: connected, IP 192.168.1.42
            [   812][W][sensor.cpp:22] BME280 not found at 0x76, trying 0x77
            BME280 OK at 0x77
            temp,hum,light
        """.trimIndent().replace("\n", "\r\n") + "\r\n"

        val CRASH = """
            Guru Meditation Error: Core  1 panic'ed (LoadProhibited). Exception was unhandled.

            Core  1 register dump:
            PC      : 0x400d1f2a  PS      : 0x00060630  A0      : 0x800d2b55  A1      : 0x3ffb1f50
            EXCVADDR: 0x00000000  LBEG    : 0x4000c2e0  LEND    : 0x4000c2f6  LCOUNT  : 0xffffffff

            Backtrace: 0x400d1f27:0x3ffb1f50 0x400d2b52:0x3ffb1f70 0x400d8a1d:0x3ffb1f90

            ELF file SHA256: 4f6b2e1a9c3d7e80

            Rebooting...
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }
}

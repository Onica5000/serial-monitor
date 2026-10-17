package com.matthew.serialmonitor.core

enum class BoardType(val label: String) { ARDUINO("Arduino"), ESP32("ESP32 / ESP8266"), NONE("None") }

/** One control-line step: set DTR and/or RTS (null = leave), then wait [delayMs]. */
data class ControlStep(val dtr: Boolean?, val rts: Boolean?, val delayMs: Long)

object ResetSequences {
    /** Arduino IDE style: drop DTR/RTS, then assert -> falling edge through the 100nF cap pulses RESET. */
    val ARDUINO = listOf(ControlStep(dtr = false, rts = false, delayMs = 100), ControlStep(dtr = true, rts = true, delayMs = 0))

    /**
     * esptool "hard reset": with DTR deasserted (IO0 stays HIGH -> normal boot, never download mode),
     * assert RTS (EN LOW), wait, release RTS. USB-Serial/JTAG (ESP32-C3/S3/C6/H2) gets longer delays,
     * like esptool's HardReset(uses_usb=True).
     */
    fun esp(usbJtag: Boolean): List<ControlStep> = listOf(
        ControlStep(dtr = false, rts = false, delayMs = 10),
        ControlStep(dtr = false, rts = true, delayMs = if (usbJtag) 200 else 100),
        ControlStep(dtr = false, rts = false, delayMs = if (usbJtag) 200 else 0),
    )

    /** State to put lines in right after opening the port (before optional reset). */
    fun initialLines(board: BoardType, dtr: Boolean, rts: Boolean): ControlStep = when (board) {
        BoardType.ESP32 -> ControlStep(dtr = false, rts = false, delayMs = 0) // never hold EN low / IO0 low
        else -> ControlStep(dtr = dtr, rts = rts, delayMs = 0)
    }
}

object UsbIds {
    const val VID_ESPRESSIF = 0x303A
    const val VID_WCH = 0x1A86
    const val VID_SILABS = 0x10C4
    const val VID_FTDI = 0x0403

    /** WCH chips that enumerate as standard CDC-ACM (CH9102 is common on ESP32 DevKit clones). */
    val WCH_CDC_PIDS = intArrayOf(0x55D2, 0x55D3, 0x55D4, 0x55D5, 0x55D8)

    /** Espressif native USB PIDs: 0x1001 USB-Serial/JTAG (C3/S3/C6/H2/P4), 0x0002 S2/S3 TinyUSB CDC, etc. */
    val ESPRESSIF_CDC_PIDS = intArrayOf(0x0002, 0x0003, 0x0004, 0x0005, 0x0009, 0x1001, 0x1002, 0x80D1)

    fun isEspressifNative(vid: Int) = vid == VID_ESPRESSIF
    fun isUsbSerialJtag(vid: Int, pid: Int) = vid == VID_ESPRESSIF && pid == 0x1001

    /** Bridges mostly found on ESP32 boards: CH9102/CH343 and CP210x. (CH340 is ambiguous: Uno clones too.) */
    fun isLikelyEsp(vid: Int, pid: Int): Boolean =
        vid == VID_ESPRESSIF || (vid == VID_WCH && pid in WCH_CDC_PIDS) || (vid == VID_SILABS && pid in intArrayOf(0xEA60, 0xEA70, 0xEA71))

    fun chipName(vid: Int, pid: Int): String? = when (vid) {
        VID_WCH -> when (pid) {
            0x7523 -> "CH340"; 0x5523 -> "CH341"; 0x7522 -> "CH340K"; 0x55D4 -> "CH9102"; 0x55D3 -> "CH343"
            0x55D2 -> "CH342"; 0x55D5 -> "CH9143"; 0x55D8 -> "CH9101"; else -> "WCH"
        }
        VID_SILABS -> when (pid) { 0xEA60 -> "CP2102/CP2104"; 0xEA70 -> "CP2105"; 0xEA71 -> "CP2108"; else -> "CP210x" }
        VID_FTDI -> when (pid) { 0x6001 -> "FT232R"; 0x6010 -> "FT2232"; 0x6011 -> "FT4232"; 0x6014 -> "FT232H"; 0x6015 -> "FT231X"; else -> "FTDI" }
        0x067B -> "PL2303"
        VID_ESPRESSIF -> when (pid) { 0x1001 -> "ESP USB-Serial/JTAG"; 0x0002 -> "ESP32-S2/S3 USB CDC"; else -> "Espressif USB" }
        0x2341, 0x2A03 -> "Arduino"
        0x2E8A -> "Raspberry Pi RP2040"
        0x239A -> "Adafruit"
        else -> null
    }

    fun vidPid(vid: Int, pid: Int) = "%04X:%04X".format(vid, pid)

    val BAUD_RATES = listOf(300, 600, 750, 1200, 2400, 4800, 9600, 19200, 31250, 38400, 57600, 74880, 115200, 230400, 250000, 460800, 500000, 921600, 1000000, 2000000)
}

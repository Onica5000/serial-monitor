package com.matthew.serialmonitor.core

/** Common CRCs used in hobby serial protocols. All verified against the "123456789" check values. */
enum class CrcType(val label: String, val size: Int) {
    NONE("None", 0),
    CRC8("CRC-8 (SMBus, poly 0x07)", 1),
    CRC8_MAXIM("CRC-8/MAXIM (Dallas 1-Wire)", 1),
    CRC16_MODBUS("CRC-16/MODBUS (LSB first)", 2),
    CRC16_CCITT("CRC-16/CCITT-FALSE (MSB first)", 2),
    CRC16_XMODEM("CRC-16/XMODEM (MSB first)", 2),
    CRC32("CRC-32 (zlib, LSB first)", 4);

    fun compute(data: ByteArray): Long = when (this) {
        NONE -> 0
        CRC8 -> Crc.crc8(data).toLong()
        CRC8_MAXIM -> Crc.crc8Maxim(data).toLong()
        CRC16_MODBUS -> Crc.crc16Modbus(data).toLong()
        CRC16_CCITT -> Crc.crc16Ccitt(data, 0xFFFF).toLong()
        CRC16_XMODEM -> Crc.crc16Ccitt(data, 0x0000).toLong()
        CRC32 -> Crc.crc32(data)
    }

    /** Bytes to append to a frame, in the byte order the protocol conventionally uses. */
    fun bytes(data: ByteArray): ByteArray {
        val v = compute(data)
        return when (this) {
            NONE -> ByteArray(0)
            CRC8, CRC8_MAXIM -> byteArrayOf(v.toByte())
            CRC16_MODBUS -> byteArrayOf(v.toByte(), (v shr 8).toByte())
            CRC16_CCITT, CRC16_XMODEM -> byteArrayOf((v shr 8).toByte(), v.toByte())
            CRC32 -> byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        }
    }
}

object Crc {
    fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    fun crc8Maxim(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x01 != 0) (crc ushr 1) xor 0x8C else crc ushr 1 }
        }
        return crc
    }

    fun crc16Modbus(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    fun crc16Ccitt(data: ByteArray, init: Int): Int {
        var crc = init
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    fun crc32(data: ByteArray): Long {
        val c = java.util.zip.CRC32()
        c.update(data)
        return c.value
    }
}

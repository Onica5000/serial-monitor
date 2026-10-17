package com.matthew.serialmonitor.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Standard "123456789" check values from the CRC catalogue (reveng). */
class CrcTest {
    private val check = "123456789".toByteArray()

    @Test fun crc8() = assertEquals(0xF4, Crc.crc8(check))
    @Test fun crc8Maxim() = assertEquals(0xA1, Crc.crc8Maxim(check))
    @Test fun modbus() = assertEquals(0x4B37, Crc.crc16Modbus(check))
    @Test fun ccittFalse() = assertEquals(0x29B1, Crc.crc16Ccitt(check, 0xFFFF))
    @Test fun xmodem() = assertEquals(0x31C3, Crc.crc16Ccitt(check, 0))
    @Test fun crc32() = assertEquals(0xCBF43926L, Crc.crc32(check))

    @Test fun modbusFrameBytesLsbFirst() {
        // Read holding registers: 01 03 00 00 00 0A -> CRC C5 CD
        val frame = Hex.parse("01 03 00 00 00 0A")
        assertArrayEquals(Hex.parse("C5 CD"), CrcType.CRC16_MODBUS.bytes(frame))
    }

    @Test fun appendOrder() {
        assertArrayEquals(Hex.parse("29 B1"), CrcType.CRC16_CCITT.bytes(check))
        assertArrayEquals(Hex.parse("26 39 F4 CB"), CrcType.CRC32.bytes(check))
        assertArrayEquals(ByteArray(0), CrcType.NONE.bytes(check))
    }
}

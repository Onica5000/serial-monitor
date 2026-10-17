package com.matthew.serialmonitor.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun spaced() = assertArrayEquals(b(0x01, 0x02, 0xFF), Hex.parse("01 02 ff"))
    @Test fun prefixedAndCommas() = assertArrayEquals(b(0x01, 0xAB), Hex.parse("0x01, 0XAB"))
    @Test fun packed() = assertArrayEquals(b(0xDE, 0xAD, 0xBE, 0xEF), Hex.parse("DEADBEEF"))
    @Test fun colonsAndDashes() = assertArrayEquals(b(0x0A, 0x0B, 0x0C), Hex.parse("0a:0b-0c"))
    @Test fun singleNibble() = assertArrayEquals(b(0x0A, 0x01), Hex.parse("A 1"))
    @Test fun empty() = assertArrayEquals(ByteArray(0), Hex.parse("   "))

    @Test(expected = IllegalArgumentException::class) fun oddPacked() { Hex.parse("ABC") }
    @Test(expected = IllegalArgumentException::class) fun invalidChar() { Hex.parse("0G") }
    @Test(expected = IllegalArgumentException::class) fun bare0x() { Hex.parse("0x") }

    @Test fun toHexAndDump() {
        assertEquals("48 69 0D 0A", Hex.toHex("Hi\r\n".toByteArray()))
        assertEquals("48 69 0D 0A  |Hi..|", Hex.dumpLine("Hi\r\n".toByteArray()))
    }

    @Test fun roundTrip() {
        val data = ByteArray(256) { it.toByte() }
        assertArrayEquals(data, Hex.parse(Hex.toHex(data)))
    }
}

package com.matthew.serialmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlotParserTest {
    private val p = PlotParser()

    @Test fun singleNumber() = assertEquals(mapOf("value 1" to 42.0), p.parse("42"))
    @Test fun spaceSeparated() = assertEquals(mapOf("value 1" to 1.0, "value 2" to -2.5, "value 3" to 3e2), p.parse("1 -2.5 3e2"))
    @Test fun commaSeparated() = assertEquals(mapOf("value 1" to 1.0, "value 2" to 2.0), p.parse("1,2"))
    @Test fun tabSeparated() = assertEquals(mapOf("value 1" to 1.0, "value 2" to 2.0), p.parse("1\t2\r"))
    @Test fun labeled() = assertEquals(mapOf("temp" to 21.5, "hum" to 40.0), p.parse("temp:21.5 hum:40"))
    @Test fun labeledComma() = assertEquals(mapOf("temp" to 21.5, "hum" to 40.0), p.parse("temp:21.5,hum:40"))
    @Test fun labelWithSpaceAfterColon() = assertEquals(mapOf("Temp" to 23.0), p.parse("Temp: 23"))
    @Test fun labelWithSpacesInCommaMode() = assertEquals(mapOf("Sensor A" to 1.0, "Sensor B" to 2.0), p.parse("Sensor A:1, Sensor B:2"))
    @Test fun mixedNamedAndUnnamed() = assertEquals(mapOf("a" to 1.0, "value 1" to 5.0), p.parse("a:1 5"))

    @Test fun headerThenValues() {
        assertNull(p.parse("x,y,z"))
        assertEquals(mapOf("x" to 1.0, "y" to 2.0, "z" to 3.0), p.parse("1,2,3"))
    }

    @Test fun textIgnored() {
        assertNull(p.parse("Hello world"))
        assertNull(p.parse("Temp 23 C"))
        assertNull(p.parse("I (123) wifi: connected"))
        assertNull(p.parse("rst:0x1 (POWERON_RESET),boot:0x13 (SPI_FAST_FLASH_BOOT)"))
        assertNull(p.parse("12:30:45"))
        assertNull(p.parse(""))
        assertNull(p.parse("nan"))
        assertEquals(emptyList<String>(), p.headerLabels) // "Hello world" (space separated) must not become a header
    }

    @Test fun infinityRejected() = assertNull(p.parse("a:Infinity"))
}

package com.matthew.serialmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlighterCrashTest {
    private val h = Highlighter(Highlighter.DEFAULT_ERROR, Highlighter.DEFAULT_WARN)

    @Test fun keywords() {
        assertEquals(Level.ERROR, h.classify("SD init error"))
        assertEquals(Level.WARN, h.classify("warning: low battery"))
        assertEquals(Level.NONE, h.classify("hello"))
    }

    @Test fun espLogPrefixes() {
        assertEquals(Level.ERROR, h.classify("E (1234) wifi: auth failed"))
        assertEquals(Level.WARN, h.classify("W (55) boot: something"))
        assertEquals(Level.ERROR, h.classify("[  1234][E][WiFiGeneric.cpp:1583] fail"))
        assertEquals(Level.WARN, h.classify("[   12][W][esp32-hal.c:1] meh"))
        assertEquals(Level.CRASH, h.classify("Guru Meditation Error: Core  1 panic'ed (LoadProhibited). Exception was unhandled."))
    }

    @Test fun esp32PanicCollected() {
        val c = CrashDetector()
        val log = listOf(
            "loop 5",
            "Guru Meditation Error: Core  1 panic'ed (LoadProhibited). Exception was unhandled.",
            "",
            "Core  1 register dump:",
            "PC      : 0x400d1234  PS      : 0x00060a30",
            "",
            "Backtrace: 0x400d1234:0x3ffb1f50 0x400d5678:0x3ffb1f70",
            "",
            "ELF file SHA256: 0000000000000000",
            "",
            "Rebooting...",
            "ets Jun  8 2016 00:22:57",
        )
        val flags = log.map { c.feed(it) }
        assertFalse(flags[0])
        assertTrue(flags[1]); assertTrue(flags[4]); assertTrue(flags[6])
        val dump = c.lastDump!!
        assertTrue(dump.startsWith("Guru Meditation Error"))
        assertTrue(dump.contains("Backtrace: 0x400d1234"))
        assertFalse(dump.contains("ets Jun"))
        assertTrue("ELF hash kept for decoding", dump.contains("ELF file SHA256"))
        assertFalse(flags.last())
        assertEquals(1, c.dumpCount)
    }

    @Test fun abortWithBacktrace() {
        val c = CrashDetector()
        c.feed("abort() was called at PC 0x40082a3e on core 1")
        c.feed("")
        c.feed("Backtrace: 0x40083a1d:0x3ffb2140 0x40089d61:0x3ffb2160")
        c.feed("")
        assertFalse(c.feed("normal line after"))
        assertTrue(c.lastDump!!.contains("Backtrace:"))
    }

    @Test fun esp8266Stack() {
        val c = CrashDetector()
        c.feed("Exception (28):")
        c.feed("epc1=0x40201234 epc2=0x00000000")
        c.feed(">>>stack>>>")
        c.feed("")
        c.feed("3ffffdb0:  00000000 00000000")
        assertTrue(c.feed("<<<stack<<<"))
        assertFalse(c.feed("ets Jan  8 2013,rst cause:2, boot mode:(3,6)"))
        assertTrue(c.lastDump!!.contains("3ffffdb0"))
    }
}

package com.matthew.serialmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetTest {
    /**
     * Simulate the standard ESP32 auto-reset circuit (two cross-coupled NPNs):
     * EN low iff RTS asserted and DTR not; IO0 low iff DTR asserted and RTS not.
     * Lines are set in the same order the session uses (RTS first when releasing RTS).
     */
    private fun simulate(steps: List<ControlStep>, startDtr: Boolean, startRts: Boolean): Pair<Boolean, List<Pair<Boolean, Boolean>>> {
        var dtr = startDtr; var rts = startRts
        val states = ArrayList<Pair<Boolean, Boolean>>() // (enLow, io0Low) after every single line change
        var enteredDownload = false
        var prevEnLow = rts && !dtr
        fun record() {
            val enLow = rts && !dtr; val io0Low = dtr && !rts
            if (prevEnLow && !enLow && io0Low) enteredDownload = true // EN rising edge while IO0 low
            prevEnLow = enLow
            states += enLow to io0Low
        }
        for (s in steps) {
            if (s.rts == false) { rts = false; record(); s.dtr?.let { dtr = it; record() } }
            else { s.dtr?.let { dtr = it; record() }; s.rts?.let { rts = it; record() } }
        }
        return enteredDownload to states
    }

    @Test fun espResetPulsesEnAndNeverDownloadMode() {
        for (jtag in listOf(false, true)) for (d in listOf(false, true)) for (r in listOf(false, true)) {
            val (download, states) = simulate(listOf(ResetSequences.initialLines(BoardType.ESP32, true, true)) + ResetSequences.esp(jtag), d, r)
            assertFalse("download mode entered (jtag=$jtag start=$d/$r)", download)
            assertTrue("EN was pulsed", states.any { it.first })
            assertEquals("ends released", false to false, states.last())
        }
    }

    @Test fun espOpenReleasesBothLines() {
        val s = ResetSequences.initialLines(BoardType.ESP32, dtr = true, rts = true)
        assertEquals(false, s.dtr); assertEquals(false, s.rts)
        val (download, states) = simulate(listOf(s), startDtr = true, startRts = true)
        assertFalse(download)
        assertFalse("opening must not hold EN low", states.last().first)
    }

    @Test fun jtagUsesLongerDelays() {
        assertTrue(ResetSequences.esp(true).sumOf { it.delayMs } > ResetSequences.esp(false).sumOf { it.delayMs })
    }

    @Test fun arduinoEndsWithDtrAsserted() {
        val last = ResetSequences.ARDUINO.last()
        assertEquals(true, last.dtr)
        assertEquals(false, ResetSequences.ARDUINO.first().dtr)
    }

    @Test fun espIds() {
        assertTrue(UsbIds.isLikelyEsp(0x1A86, 0x55D4)) // CH9102
        assertTrue(UsbIds.isLikelyEsp(0x10C4, 0xEA60)) // CP2102
        assertTrue(UsbIds.isLikelyEsp(0x303A, 0x1001)) // USB-Serial/JTAG
        assertFalse(UsbIds.isLikelyEsp(0x1A86, 0x7523)) // CH340: could be an Uno clone
        assertTrue(UsbIds.isUsbSerialJtag(0x303A, 0x1001))
        assertEquals("CH9102", UsbIds.chipName(0x1A86, 0x55D4))
        assertTrue(74880 in UsbIds.BAUD_RATES && 115200 in UsbIds.BAUD_RATES && 2000000 in UsbIds.BAUD_RATES && 300 in UsbIds.BAUD_RATES)
    }
}

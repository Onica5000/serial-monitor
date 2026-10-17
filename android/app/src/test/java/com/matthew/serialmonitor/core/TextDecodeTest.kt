package com.matthew.serialmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDecodeTest {
    @Test fun invalidUtf8Replaced() {
        assertEquals("a\uFFFDb", TextDecode.utf8(byteArrayOf(0x61, 0xFF.toByte(), 0x62)))
    }

    @Test fun ansiStripped() {
        assertEquals("I (123) wifi: connected", TextDecode.decodeLine("\u001B[0;32mI (123) wifi: connected\u001B[0m".toByteArray()))
    }

    @Test fun controlCharsVisibleTabsKept() {
        assertEquals("a\t\u2400b", TextDecode.decodeLine(byteArrayOf(0x61, 0x09, 0x00, 0x62)))
    }
}

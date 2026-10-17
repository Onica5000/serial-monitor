package com.matthew.serialmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

class CsvLoggerTest {
    @Test fun headerRewrittenOnNewSeries() {
        val w = StringWriter()
        val c = CsvLogger(w)
        c.write(1000, mapOf("a" to 1.0))
        c.write(1010, mapOf("a" to 2.5, "b,c" to 3.0))
        c.write(1020, mapOf("b,c" to 4.0))
        assertEquals("time_ms,a\n1000,1\ntime_ms,a,\"b,c\"\n1010,2.5,3\n1020,,4\n", w.toString())
    }
}

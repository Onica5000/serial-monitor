package com.matthew.serialmonitor.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotBufferTest {
    @Test fun windowAndOrder() {
        val b = PlotBuffer(capacity = 3)
        for (i in 1..5) b.add(mapOf("a" to i.toDouble()))
        val s = b.snapshot()
        assertEquals(listOf("a"), s.names)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), s.series[0], 0f)
        assertEquals(5L, s.total)
    }

    @Test fun lateSeriesAndMissingValuesAreNaN() {
        val b = PlotBuffer(capacity = 4)
        b.add(mapOf("a" to 1.0))
        b.add(mapOf("a" to 2.0, "b" to 20.0))
        b.add(mapOf("b" to 30.0))
        val s = b.snapshot()
        assertEquals(listOf("a", "b"), s.names)
        assertTrue(s.series[0][2].isNaN())
        assertTrue(s.series[1][0].isNaN())
        assertEquals(30f, s.series[1][2])
    }

    @Test fun maxSeriesCap() {
        val b = PlotBuffer(capacity = 10, maxSeries = 2)
        b.add(mapOf("a" to 1.0, "b" to 2.0, "c" to 3.0))
        assertEquals(2, b.snapshot().names.size)
    }
}

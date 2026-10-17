package com.matthew.serialmonitor.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LineEndingTest {
    private val p = "AT".toByteArray()
    @Test fun none() = assertArrayEquals("AT".toByteArray(), LineEnding.NONE.apply(p))
    @Test fun nl() = assertArrayEquals("AT\n".toByteArray(), LineEnding.NL.apply(p))
    @Test fun cr() = assertArrayEquals("AT\r".toByteArray(), LineEnding.CR.apply(p))
    @Test fun both() = assertArrayEquals("AT\r\n".toByteArray(), LineEnding.BOTH.apply(p)) // Arduino sends CR then LF
}

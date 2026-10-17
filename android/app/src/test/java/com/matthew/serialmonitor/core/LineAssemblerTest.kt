package com.matthew.serialmonitor.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineAssemblerTest {
    private fun texts(lines: List<RawLine>) = lines.map { String(it.content) }

    @Test fun lf() {
        val a = LineAssembler()
        assertEquals(listOf("a", "b"), texts(a.feed("a\nb\n".toByteArray())))
    }

    @Test fun crlfIsOneLine() {
        val a = LineAssembler()
        val out = a.feed("hello\r\nworld\r\n".toByteArray())
        assertEquals(listOf("hello", "world"), texts(out))
        assertArrayEquals(byteArrayOf(13, 10), out[0].terminator)
    }

    @Test fun crlfSplitAcrossReads() {
        val a = LineAssembler()
        assertEquals(emptyList<String>(), texts(a.feed("hel".toByteArray())))
        assertEquals(emptyList<String>(), texts(a.feed("lo\r".toByteArray())))
        assertTrue(a.hasPendingCr())
        val out = a.feed("\nnext\n".toByteArray())
        assertEquals(listOf("hello", "next"), texts(out))
        assertArrayEquals("hello\r\n".toByteArray(), out[0].raw)
    }

    @Test fun loneCrTerminates() {
        val a = LineAssembler()
        assertEquals(listOf("a", "b"), texts(a.feed("a\rb\r\n".toByteArray())))
    }

    @Test fun emptyLinesPreserved() {
        val a = LineAssembler()
        assertEquals(listOf("a", "", "b"), texts(a.feed("a\r\n\r\nb\n".toByteArray())))
    }

    @Test fun pendingAndFlush() {
        val a = LineAssembler()
        a.feed("partial".toByteArray())
        assertEquals("partial", String(a.pending()))
        val f = a.flush()!!
        assertEquals("partial", String(f.content))
        assertFalse(a.hasPending())
        assertNull(a.flush())
    }

    @Test fun trailingCrFlushedAsLine() {
        val a = LineAssembler()
        a.feed("x\r".toByteArray())
        val f = a.flush()!!
        assertEquals("x", String(f.content))
        assertArrayEquals(byteArrayOf(13), f.terminator)
    }

    @Test fun longLineForceBroken() {
        val a = LineAssembler(maxLineBytes = 10)
        val out = a.feed(ByteArray(25) { 'x'.code.toByte() })
        assertEquals(2, out.size)
        assertEquals(10, out[0].content.size)
        assertEquals(5, a.pending().size)
    }

    @Test fun lengthParameterRespected() {
        val a = LineAssembler()
        val buf = "ab\ncd\nXXXX".toByteArray()
        assertEquals(listOf("ab", "cd"), texts(a.feed(buf, 6)))
        assertFalse(a.hasPending())
    }

    @Test fun utf8SplitAcrossReadsDecodesOnLineBoundary() {
        val a = LineAssembler()
        val bytes = "temp 23°C\n".toByteArray(Charsets.UTF_8)
        val first = a.feed(bytes.copyOfRange(0, 8)) // splits the 2-byte '°'
        assertTrue(first.isEmpty())
        val out = a.feed(bytes.copyOfRange(8, bytes.size))
        assertEquals("temp 23°C", TextDecode.decodeLine(out[0].content))
    }
}

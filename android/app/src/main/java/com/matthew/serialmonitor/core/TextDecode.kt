package com.matthew.serialmonitor.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object TextDecode {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** UTF-8 decode with U+FFFD replacement for malformed input. */
    fun utf8(bytes: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return dec.decode(ByteBuffer.wrap(bytes)).toString()
    }

    /** Remove ANSI color/cursor escapes (ESP-IDF logs use them) and make other control chars visible. */
    fun clean(s: String, stripAnsi: Boolean = true): String {
        val t = if (stripAnsi && s.indexOf('\u001B') >= 0) ANSI.replace(s, "") else s
        if (t.none { it < ' ' && it != '\t' }) return t
        val sb = StringBuilder(t.length)
        for (c in t) {
            when {
                c == '\t' -> sb.append(c)
                c < ' ' -> sb.append((0x2400 + c.code).toChar()) // Unicode control pictures, e.g. ␀
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun decodeLine(bytes: ByteArray, stripAnsi: Boolean = true): String = clean(utf8(bytes), stripAnsi)
}

package com.matthew.serialmonitor.core

/** One received line: [content] without terminator, [terminator] is "", "\n", "\r" or "\r\n" (raw bytes). */
class RawLine(val content: ByteArray, val terminator: ByteArray) {
    val raw: ByteArray get() = content + terminator
}

/**
 * Splits a byte stream into lines. Handles "\n", "\r\n" and lone "\r" terminators, including a
 * "\r\n" pair split across two reads. Lines longer than [maxLineBytes] are force-broken.
 * Not thread safe; callers serialize access.
 */
class LineAssembler(private val maxLineBytes: Int = 4096) {
    private var buf = ByteArray(256)
    private var len = 0
    private var pendingCr = false

    fun feed(data: ByteArray, length: Int = data.size): List<RawLine> {
        val out = ArrayList<RawLine>(4)
        for (i in 0 until length) {
            val b = data[i]
            if (pendingCr) {
                pendingCr = false
                if (b == LF) { out += take(CRLF); continue }
                out += take(CR_ONLY)
            }
            when (b) {
                LF -> out += take(LF_ONLY)
                CR -> pendingCr = true
                else -> {
                    append(b)
                    if (len >= maxLineBytes) out += take(EMPTY)
                }
            }
        }
        return out
    }

    /** Bytes of the incomplete line received so far (no terminator). */
    fun pending(): ByteArray = buf.copyOf(len)

    fun hasPendingCr(): Boolean = pendingCr

    fun hasPending(): Boolean = len > 0 || pendingCr

    /** Emit whatever is buffered as a line (used on idle timeout / disconnect). */
    fun flush(): RawLine? {
        if (!hasPending()) return null
        val term = if (pendingCr) CR_ONLY else EMPTY
        pendingCr = false
        return take(term)
    }

    fun reset() { len = 0; pendingCr = false }

    private fun append(b: Byte) {
        if (len == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[len++] = b
    }

    private fun take(term: ByteArray): RawLine {
        val line = RawLine(buf.copyOf(len), term)
        len = 0
        return line
    }

    companion object {
        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D
        private val EMPTY = ByteArray(0)
        private val LF_ONLY = byteArrayOf(LF)
        private val CR_ONLY = byteArrayOf(CR)
        private val CRLF = byteArrayOf(CR, LF)
    }
}

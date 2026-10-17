package com.matthew.serialmonitor.core

object Hex {
    private val DIGITS = "0123456789ABCDEF".toCharArray()
    private val SEPARATORS = Regex("[\\s,;:\\-]+")

    fun toHex(bytes: ByteArray, sep: String = " "): String {
        val sb = StringBuilder(bytes.size * (2 + sep.length))
        bytes.forEachIndexed { i, b ->
            if (i > 0) sb.append(sep)
            val v = b.toInt() and 0xFF
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** Hex + printable ASCII, e.g. "48 69 0D 0A  |Hi..|" */
    fun dumpLine(bytes: ByteArray): String {
        val ascii = String(CharArray(bytes.size) { i ->
            val v = bytes[i].toInt() and 0xFF
            if (v in 0x20..0x7E) v.toChar() else '.'
        })
        return toHex(bytes) + "  |" + ascii + "|"
    }

    /**
     * Parse user hex input. Accepts "01 02 ff", "0x01,0x02", "01:02", "DEADBEEF", "A" (single nibble).
     * @throws IllegalArgumentException on invalid input.
     */
    fun parse(input: String): ByteArray {
        val out = ArrayList<Byte>()
        for (raw in input.trim().split(SEPARATORS)) {
            if (raw.isEmpty()) continue
            var tok = raw
            if (tok.startsWith("0x") || tok.startsWith("0X")) tok = tok.substring(2)
            require(tok.isNotEmpty()) { "Empty hex token in \"$raw\"" }
            require(tok.all { it.digitToIntOrNull(16) != null }) { "Invalid hex: \"$raw\"" }
            if (tok.length == 1) { out += tok.toInt(16).toByte(); continue }
            require(tok.length % 2 == 0) { "Odd number of hex digits: \"$raw\"" }
            for (i in tok.indices step 2) out += tok.substring(i, i + 2).toInt(16).toByte()
        }
        return out.toByteArray()
    }
}
